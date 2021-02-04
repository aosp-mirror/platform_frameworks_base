/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.timezonedetector.location;

import static android.app.time.LocationTimeZoneManager.PRIMARY_PROVIDER_NAME;
import static android.app.time.LocationTimeZoneManager.PROVIDER_MODE_OVERRIDE_DISABLED;
import static android.app.time.LocationTimeZoneManager.PROVIDER_MODE_OVERRIDE_NONE;
import static android.app.time.LocationTimeZoneManager.PROVIDER_MODE_OVERRIDE_SIMULATED;
import static android.app.time.LocationTimeZoneManager.SECONDARY_PROVIDER_NAME;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteCallback;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.service.timezone.TimeZoneProviderService;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.SystemService;
import com.android.server.timezonedetector.TimeZoneDetectorInternal;
import com.android.server.timezonedetector.TimeZoneDetectorService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A service class that acts as a container for the {@link LocationTimeZoneProviderController},
 * which determines what {@link com.android.server.timezonedetector.GeolocationTimeZoneSuggestion}
 * are made to the {@link TimeZoneDetectorInternal}, and the {@link LocationTimeZoneProvider}s that
 * (indirectly) generate {@link TimeZoneProviderEvent}s.
 *
 * <p>For details of the time zone suggestion behavior, see {@link
 * LocationTimeZoneProviderController}.
 *
 * <p>Implementation details:
 *
 * <p>For simplicity, with the exception of a few outliers like {@link #dump}, all processing in
 * this service (and package-private helper objects) takes place on a single thread / handler, the
 * one indicated by {@link ThreadingDomain}. Because methods like {@link #dump} can be invoked on
 * another thread, the service and its related objects must still be thread-safe.
 *
 * <p>For testing / reproduction of bugs, it is possible to put providers into "simulation
 * mode" where the real binder clients are replaced by {@link
 * SimulatedLocationTimeZoneProviderProxy}. This means that the real client providers are never
 * bound (ensuring no real location events will be received) and simulated events / behaviors
 * can be injected via the command line.
 *
 * <p>To enter simulation mode for a provider, use {@code adb shell cmd location_time_zone_manager
 * set_provider_mode_override &lt;provider name&gt; simulated} and restart the service with {@code
 * adb shell cmd location_time_zone_manager stop} and {@code adb shell cmd
 * location_time_zone_manager start}.
 *
 * <p>e.g. {@code adb shell cmd location_time_zone_manager set_provider_mode_override primary
 * simulated}.
 *
 * <p>See {@code adb shell cmd location_time_zone_manager help}" for more options.
 */
public class LocationTimeZoneManagerService extends Binder {

    /**
     * Controls lifecycle of the {@link LocationTimeZoneManagerService}.
     */
    public static class Lifecycle extends SystemService {

        private LocationTimeZoneManagerService mService;

        public Lifecycle(@NonNull Context context) {
            super(Objects.requireNonNull(context));
        }

        @Override
        public void onStart() {
            Context context = getContext();
            if (TimeZoneDetectorService.isGeoLocationTimeZoneDetectionSupported(context)) {
                mService = new LocationTimeZoneManagerService(context);

                // The service currently exposes no LocalService or Binder API, but it extends
                // Binder and is registered as a binder service so it can receive shell commands.
                publishBinderService("location_time_zone_manager", mService);
            } else {
                Slog.i(TAG, getClass() + " is disabled");
            }
        }

        @Override
        public void onBootPhase(int phase) {
            Context context = getContext();
            if (TimeZoneDetectorService.isGeoLocationTimeZoneDetectionSupported(context)) {
                if (phase == PHASE_SYSTEM_SERVICES_READY) {
                    // The location service must be functioning after this boot phase.
                    mService.onSystemReady();
                } else if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                    // Some providers rely on non-platform code (e.g. gcore), so we wait to
                    // initialize providers until third party code is allowed to run.
                    mService.onSystemThirdPartyAppsCanStart();
                }
            }
        }
    }

    static final String TAG = "LocationTZDetector";

    private static final long BLOCKING_OP_WAIT_DURATION_MILLIS = Duration.ofSeconds(20).toMillis();

    private static final String ATTRIBUTION_TAG = "LocationTimeZoneService";

    private static final String PRIMARY_LOCATION_TIME_ZONE_SERVICE_ACTION =
            TimeZoneProviderService.PRIMARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE;
    private static final String SECONDARY_LOCATION_TIME_ZONE_SERVICE_ACTION =
            TimeZoneProviderService.SECONDARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE;


    @NonNull private final Context mContext;

    /**
     * The {@link ThreadingDomain} used to supply the shared lock object used by the controller and
     * related components.
     *
     * <p>Most operations are executed on the associated handler thread <em>but not all</em>, hence
     * the requirement for additional synchronization using a shared lock.
     */
    @NonNull private final ThreadingDomain mThreadingDomain;

    /** A handler associated with the {@link #mThreadingDomain}. */
    @NonNull private final Handler mHandler;

    /** The shared lock from {@link #mThreadingDomain}. */
    @NonNull private final Object mSharedLock;

    // Lazily initialized. Can be null if the service has been stopped.
    @GuardedBy("mSharedLock")
    private ControllerImpl mLocationTimeZoneDetectorController;

    // Lazily initialized. Can be null if the service has been stopped.
    @GuardedBy("mSharedLock")
    private ControllerEnvironmentImpl mEnvironment;

    @GuardedBy("mSharedLock")
    @NonNull
    private String mPrimaryProviderModeOverride = PROVIDER_MODE_OVERRIDE_NONE;

    @GuardedBy("mSharedLock")
    @NonNull
    private String mSecondaryProviderModeOverride = PROVIDER_MODE_OVERRIDE_NONE;

    LocationTimeZoneManagerService(Context context) {
        mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        mHandler = FgThread.getHandler();
        mThreadingDomain = new HandlerThreadingDomain(mHandler);
        mSharedLock = mThreadingDomain.getLockObject();
    }

    void onSystemReady() {
        // Called on an arbitrary thread during initialization.
        synchronized (mSharedLock) {
            // TODO(b/152744911): LocationManagerService watches for packages disappearing. Need to
            //  do anything here?

            // TODO(b/152744911): LocationManagerService watches for foreground app changes. Need to
            //  do anything here?
            // TODO(b/152744911): LocationManagerService watches screen state. Need to do anything
            //  here?
        }
    }

    void onSystemThirdPartyAppsCanStart() {
        // Called on an arbitrary thread during initialization. We do not want to wait for
        // completion as it would delay boot.
        final boolean waitForCompletion = false;
        startInternal(waitForCompletion);
    }

    /**
     * Starts the service during server initialization or during tests after a call to
     * {@link #stop()}.
     */
    void start() {
        enforceManageTimeZoneDetectorPermission();

        final boolean waitForCompletion = true;
        startInternal(waitForCompletion);
    }

    /**
     * Starts the service during server initialization or during tests after a call to
     * {@link #stop()}.
     *
     * <p>To avoid tests needing to sleep, when {@code waitForCompletion} is {@code true}, this
     * method will not return until all the system server components have started.
     */
    private void startInternal(boolean waitForCompletion) {
        Runnable runnable = () -> {
            synchronized (mSharedLock) {
                if (mLocationTimeZoneDetectorController == null) {
                    LocationTimeZoneProvider primary = createPrimaryProvider();
                    LocationTimeZoneProvider secondary = createSecondaryProvider();
                    mLocationTimeZoneDetectorController =
                            new ControllerImpl(mThreadingDomain, primary, secondary);
                    ControllerCallbackImpl callback = new ControllerCallbackImpl(
                            mThreadingDomain);
                    mEnvironment = new ControllerEnvironmentImpl(
                            mThreadingDomain, mLocationTimeZoneDetectorController);
                    mLocationTimeZoneDetectorController.initialize(mEnvironment, callback);
                }
            }
        };
        if (waitForCompletion) {
            mThreadingDomain.postAndWait(runnable, BLOCKING_OP_WAIT_DURATION_MILLIS);
        } else {
            mThreadingDomain.post(runnable);
        }
    }

    private LocationTimeZoneProvider createPrimaryProvider() {
        LocationTimeZoneProviderProxy proxy;
        if (isProviderInSimulationMode(PRIMARY_PROVIDER_NAME)) {
            proxy = new SimulatedLocationTimeZoneProviderProxy(mContext, mThreadingDomain);
        } else if (isProviderDisabled(PRIMARY_PROVIDER_NAME)) {
            proxy = new NullLocationTimeZoneProviderProxy(mContext, mThreadingDomain);
        } else {
            proxy = new RealLocationTimeZoneProviderProxy(
                    mContext,
                    mHandler,
                    mThreadingDomain,
                    PRIMARY_LOCATION_TIME_ZONE_SERVICE_ACTION,
                    R.bool.config_enablePrimaryLocationTimeZoneOverlay,
                    R.string.config_primaryLocationTimeZoneProviderPackageName
            );
        }
        return new BinderLocationTimeZoneProvider(mThreadingDomain, PRIMARY_PROVIDER_NAME, proxy);
    }

    private LocationTimeZoneProvider createSecondaryProvider() {
        LocationTimeZoneProviderProxy proxy;
        if (isProviderInSimulationMode(SECONDARY_PROVIDER_NAME)) {
            proxy = new SimulatedLocationTimeZoneProviderProxy(mContext, mThreadingDomain);
        } else if (isProviderDisabled(SECONDARY_PROVIDER_NAME)) {
            proxy = new NullLocationTimeZoneProviderProxy(mContext, mThreadingDomain);
        } else {
            proxy = new RealLocationTimeZoneProviderProxy(
                    mContext,
                    mHandler,
                    mThreadingDomain,
                    SECONDARY_LOCATION_TIME_ZONE_SERVICE_ACTION,
                    R.bool.config_enableSecondaryLocationTimeZoneOverlay,
                    R.string.config_secondaryLocationTimeZoneProviderPackageName
            );
        }
        return new BinderLocationTimeZoneProvider(mThreadingDomain, SECONDARY_PROVIDER_NAME, proxy);
    }

    /** Used for bug triage and in tests to simulate provider events. */
    private boolean isProviderInSimulationMode(String providerName) {
        return isProviderModeOverrideSet(providerName, PROVIDER_MODE_OVERRIDE_SIMULATED);
    }

    /** Used for bug triage, tests and experiments to remove a provider. */
    private boolean isProviderDisabled(String providerName) {
        return !isProviderEnabledInConfig(providerName)
                || isProviderModeOverrideSet(providerName, PROVIDER_MODE_OVERRIDE_DISABLED);
    }

    private boolean isProviderEnabledInConfig(String providerName) {
        int providerEnabledConfigId;
        switch (providerName) {
            case PRIMARY_PROVIDER_NAME: {
                providerEnabledConfigId = R.bool.config_enablePrimaryLocationTimeZoneProvider;
                break;
            }
            case SECONDARY_PROVIDER_NAME: {
                providerEnabledConfigId = R.bool.config_enableSecondaryLocationTimeZoneProvider;
                break;
            }
            default: {
                throw new IllegalArgumentException(providerName);
            }
        }
        Resources resources = mContext.getResources();
        return resources.getBoolean(providerEnabledConfigId);
    }

    private boolean isProviderModeOverrideSet(@NonNull String providerName, @NonNull String mode) {
        switch (providerName) {
            case PRIMARY_PROVIDER_NAME: {
                return Objects.equals(mPrimaryProviderModeOverride, mode);
            }
            case SECONDARY_PROVIDER_NAME: {
                return Objects.equals(mSecondaryProviderModeOverride, mode);
            }
            default: {
                throw new IllegalArgumentException(providerName);
            }
        }
    }

    /**
     * Stops the service for tests. To avoid tests needing to sleep, this method will not return
     * until all the system server components have stopped.
     */
    void stop() {
        enforceManageTimeZoneDetectorPermission();

        mThreadingDomain.postAndWait(() -> {
            synchronized (mSharedLock) {
                if (mLocationTimeZoneDetectorController != null) {
                    mLocationTimeZoneDetectorController.destroy();
                    mLocationTimeZoneDetectorController = null;
                    mEnvironment.destroy();
                    mEnvironment = null;
                }
            }
        }, BLOCKING_OP_WAIT_DURATION_MILLIS);
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new LocationTimeZoneManagerShellCommand(this)).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    /** Sets this service into provider state recording mode for tests. */
    void setProviderModeOverride(@NonNull String providerName, @NonNull String mode) {
        enforceManageTimeZoneDetectorPermission();

        Preconditions.checkArgument(
                PRIMARY_PROVIDER_NAME.equals(providerName)
                        || SECONDARY_PROVIDER_NAME.equals(providerName));
        Preconditions.checkArgument(PROVIDER_MODE_OVERRIDE_DISABLED.equals(mode)
                || PROVIDER_MODE_OVERRIDE_SIMULATED.equals(mode)
                || PROVIDER_MODE_OVERRIDE_NONE.equals(mode));

        mThreadingDomain.postAndWait(() -> {
            synchronized (mSharedLock) {
                switch (providerName) {
                    case PRIMARY_PROVIDER_NAME: {
                        mPrimaryProviderModeOverride = mode;
                        break;
                    }
                    case SECONDARY_PROVIDER_NAME: {
                        mSecondaryProviderModeOverride = mode;
                        break;
                    }
                }
            }
        }, BLOCKING_OP_WAIT_DURATION_MILLIS);
    }

    /** Sets this service into provider state recording mode for tests. */
    void setProviderStateRecordingEnabled(boolean enabled) {
        enforceManageTimeZoneDetectorPermission();

        mThreadingDomain.postAndWait(() -> {
            synchronized (mSharedLock) {
                if (mLocationTimeZoneDetectorController != null) {
                    mLocationTimeZoneDetectorController.setProviderStateRecordingEnabled(enabled);
                }
            }
        }, BLOCKING_OP_WAIT_DURATION_MILLIS);
    }

    /** Returns a snapshot of the current controller state for tests. */
    @NonNull
    LocationTimeZoneManagerServiceState getStateForTests() {
        enforceManageTimeZoneDetectorPermission();

        try {
            return mThreadingDomain.postAndWait(
                    () -> {
                        synchronized (mSharedLock) {
                            if (mLocationTimeZoneDetectorController == null) {
                                return null;
                            }
                            return mLocationTimeZoneDetectorController.getStateForTests();
                        }
                    },
                    BLOCKING_OP_WAIT_DURATION_MILLIS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Passes a {@link TestCommand} to the specified provider and waits for the response.
     */
    @NonNull
    Bundle handleProviderTestCommand(
            @NonNull String providerName, @NonNull TestCommand testCommand) {
        enforceManageTimeZoneDetectorPermission();

        // Because this method blocks and posts work to the threading domain thread, it would cause
        // a deadlock if it were called by the threading domain thread.
        mThreadingDomain.assertNotCurrentThread();

        AtomicReference<Bundle> resultReference = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        RemoteCallback remoteCallback = new RemoteCallback(x -> {
            resultReference.set(x);
            latch.countDown();
        });

        mThreadingDomain.post(() -> {
            synchronized (mSharedLock) {
                if (mLocationTimeZoneDetectorController == null) {
                    remoteCallback.sendResult(null);
                    return;
                }
                mLocationTimeZoneDetectorController.handleProviderTestCommand(
                        providerName, testCommand, remoteCallback);
            }
        });

        try {
            // Wait, but not indefinitely.
            if (!latch.await(BLOCKING_OP_WAIT_DURATION_MILLIS, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Command did not complete in time");
            }
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }

        return resultReference.get();
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        // Called on an arbitrary thread at any time.
        synchronized (mSharedLock) {
            ipw.println("LocationTimeZoneManagerService:");
            ipw.increaseIndent();
            if (mLocationTimeZoneDetectorController == null) {
                ipw.println("{Stopped}");
            } else {
                mLocationTimeZoneDetectorController.dump(ipw, args);
            }
            ipw.decreaseIndent();
        }
    }

    static void debugLog(String msg) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, msg);
        }
    }

    static void warnLog(String msg) {
        warnLog(msg, null);
    }

    static void warnLog(String msg, @Nullable Throwable t) {
        if (Log.isLoggable(TAG, Log.WARN)) {
            Slog.w(TAG, msg, t);
        }
    }

    private void enforceManageTimeZoneDetectorPermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION,
                "manage time and time zone detection");
    }
}
