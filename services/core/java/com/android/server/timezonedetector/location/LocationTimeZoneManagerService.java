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

import static android.app.time.LocationTimeZoneManager.SERVICE_NAME;

import static com.android.server.timezonedetector.ServiceConfigAccessor.PROVIDER_MODE_DISABLED;

import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Binder;
import android.os.Handler;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.service.timezone.TimeZoneProviderEvent;
import android.service.timezone.TimeZoneProviderService;
import android.util.IndentingPrintWriter;
import android.util.Log;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.internal.util.Preconditions;
import com.android.server.FgThread;
import com.android.server.SystemService;
import com.android.server.timezonedetector.Dumpable;
import com.android.server.timezonedetector.ServiceConfigAccessor;
import com.android.server.timezonedetector.ServiceConfigAccessorImpl;
import com.android.server.timezonedetector.TimeZoneDetectorInternal;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderMetricsLogger;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Objects;

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
 * <p>See {@code adb shell cmd location_time_zone_manager help}" for details and more options.
 */
public class LocationTimeZoneManagerService extends Binder {

    /**
     * Controls lifecycle of the {@link LocationTimeZoneManagerService}.
     */
    public static class Lifecycle extends SystemService {

        private LocationTimeZoneManagerService mService;

        @NonNull
        private final ServiceConfigAccessor mServiceConfigAccessor;

        public Lifecycle(@NonNull Context context) {
            super(Objects.requireNonNull(context));
            mServiceConfigAccessor = ServiceConfigAccessorImpl.getInstance(context);
        }

        @Override
        public void onStart() {
            Context context = getContext();
            if (mServiceConfigAccessor.isGeoTimeZoneDetectionFeatureSupportedInConfig()) {
                mService = new LocationTimeZoneManagerService(context, mServiceConfigAccessor);

                // The service currently exposes no LocalService or Binder API, but it extends
                // Binder and is registered as a binder service so it can receive shell commands.
                publishBinderService(SERVICE_NAME, mService);
            } else {
                Slog.d(TAG, "Geo time zone detection feature is disabled in config");
            }
        }

        @Override
        public void onBootPhase(@BootPhase int phase) {
            if (mServiceConfigAccessor.isGeoTimeZoneDetectionFeatureSupportedInConfig()) {
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

    @GuardedBy("mSharedLock")
    private final ProviderConfig mPrimaryProviderConfig = new ProviderConfig(
            0 /* index */, "primary",
            TimeZoneProviderService.PRIMARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE);

    @GuardedBy("mSharedLock")
    private final ProviderConfig mSecondaryProviderConfig = new ProviderConfig(
            1 /* index */, "secondary",
            TimeZoneProviderService.SECONDARY_LOCATION_TIME_ZONE_PROVIDER_SERVICE_INTERFACE);

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

    @NonNull private final ServiceConfigAccessor mServiceConfigAccessor;

    // Lazily initialized. Can be null if the service has been stopped.
    @GuardedBy("mSharedLock")
    private LocationTimeZoneProviderController mLocationTimeZoneProviderController;

    // Lazily initialized. Can be null if the service has been stopped.
    @GuardedBy("mSharedLock")
    private LocationTimeZoneProviderControllerEnvironmentImpl
            mLocationTimeZoneProviderControllerEnvironment;

    LocationTimeZoneManagerService(@NonNull Context context,
            @NonNull ServiceConfigAccessor serviceConfigAccessor) {
        mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        mHandler = FgThread.getHandler();
        mThreadingDomain = new HandlerThreadingDomain(mHandler);
        mSharedLock = mThreadingDomain.getLockObject();
        mServiceConfigAccessor = Objects.requireNonNull(serviceConfigAccessor);
    }

    // According to the SystemService docs: All lifecycle methods are called from the system
    // server's main looper thread.
    void onSystemReady() {
        mServiceConfigAccessor.addLocationTimeZoneManagerConfigListener(
                this::handleServiceConfigurationChangedOnMainThread);
    }

    private void handleServiceConfigurationChangedOnMainThread() {
        // This method is called on the main thread, but service logic takes place on the threading
        // domain thread, so we post the work there.

        // The way all service-level configuration changes are handled is to just restart this
        // service - this is simple and effective, and service configuration changes should be rare.
        mThreadingDomain.post(this::restartIfRequiredOnDomainThread);
    }

    private void restartIfRequiredOnDomainThread() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            // Avoid starting the service if it is currently stopped. This is required because
            // server flags are used by tests to set behavior with the service stopped, and we don't
            // want the service being restarted after each flag is set.
            if (mLocationTimeZoneProviderController != null) {
                // Stop and start the service, waiting until completion.
                stopOnDomainThread();
                startOnDomainThread();
            }
        }
    }

    // According to the SystemService docs: All lifecycle methods are called from the system
    // server's main looper thread.
    void onSystemThirdPartyAppsCanStart() {
        // Do not wait for completion as it would delay boot.
        final boolean waitForCompletion = false;
        startInternal(waitForCompletion);
    }

    /**
     * Starts the service during server initialization or during tests after a call to
     * {@link #stop()}.
     *
     * <p>Because this method posts work to the {@code mThreadingDomain} thread and waits for
     * completion, it cannot be called from the {@code mThreadingDomain} thread.
     */
    void start() {
        enforceManageTimeZoneDetectorPermission();

        final boolean waitForCompletion = true;
        startInternal(waitForCompletion);
    }

    /**
     * Starts the service during server initialization, if the configuration changes or during tests
     * after a call to {@link #stop()}.
     *
     * <p>To avoid tests needing to sleep, when {@code waitForCompletion} is {@code true}, this
     * method will not return until all the system server components have started.
     *
     * <p>Because this method posts work to the {@code mThreadingDomain} thread, it cannot be
     * called from the {@code mThreadingDomain} thread when {@code waitForCompletion} is true.
     */
    private void startInternal(boolean waitForCompletion) {
        Runnable runnable = this::startOnDomainThread;
        if (waitForCompletion) {
            mThreadingDomain.postAndWait(runnable, BLOCKING_OP_WAIT_DURATION_MILLIS);
        } else {
            mThreadingDomain.post(runnable);
        }
    }

    /**
     * Starts the service with fake provider package names configured for tests. The config is
     * cleared when the service next stops.
     *
     * <p>Because this method posts work to the {@code mThreadingDomain} thread and waits for
     * completion, it cannot be called from the {@code mThreadingDomain} thread.
     */
    void startWithTestProviders(@Nullable String testPrimaryProviderPackageName,
            @Nullable String testSecondaryProviderPackageName, boolean recordStateChanges) {
        enforceManageTimeZoneDetectorPermission();

        if (testPrimaryProviderPackageName == null && testSecondaryProviderPackageName == null) {
            throw new IllegalArgumentException("One or both test package names must be provided.");
        }

        mThreadingDomain.postAndWait(() -> {
            synchronized (mSharedLock) {
                stopOnDomainThread();

                mServiceConfigAccessor.setTestPrimaryLocationTimeZoneProviderPackageName(
                        testPrimaryProviderPackageName);
                mServiceConfigAccessor.setTestSecondaryLocationTimeZoneProviderPackageName(
                        testSecondaryProviderPackageName);
                mServiceConfigAccessor.setRecordStateChangesForTests(recordStateChanges);
                startOnDomainThread();
            }
        }, BLOCKING_OP_WAIT_DURATION_MILLIS);
    }

    private void startOnDomainThread() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            if (!mServiceConfigAccessor.isGeoTimeZoneDetectionFeatureSupported()) {
                debugLog("Not starting " + SERVICE_NAME + ": it is disabled in service config");
                return;
            }

            if (mLocationTimeZoneProviderController == null) {
                LocationTimeZoneProvider primary = mPrimaryProviderConfig.createProvider();
                LocationTimeZoneProvider secondary = mSecondaryProviderConfig.createProvider();
                LocationTimeZoneProviderController.MetricsLogger metricsLogger =
                        new RealControllerMetricsLogger();
                boolean recordStateChanges = mServiceConfigAccessor.getRecordStateChangesForTests();
                LocationTimeZoneProviderController controller =
                        new LocationTimeZoneProviderController(mThreadingDomain, metricsLogger,
                                primary, secondary, recordStateChanges);
                LocationTimeZoneProviderControllerEnvironmentImpl environment =
                        new LocationTimeZoneProviderControllerEnvironmentImpl(
                                mThreadingDomain, mServiceConfigAccessor, controller);
                LocationTimeZoneProviderControllerCallbackImpl callback =
                        new LocationTimeZoneProviderControllerCallbackImpl(mThreadingDomain);
                controller.initialize(environment, callback);

                mLocationTimeZoneProviderControllerEnvironment = environment;
                mLocationTimeZoneProviderController = controller;
            }
        }
    }

    /**
     * Stops the service for tests and other rare cases. To avoid tests needing to sleep, this
     * method will not return until all the system server components have stopped.
     *
     * <p>Because this method posts work to the {@code mThreadingDomain} thread and waits it cannot
     * be called from the {@code mThreadingDomain} thread.
     */
    void stop() {
        enforceManageTimeZoneDetectorPermission();

        mThreadingDomain.postAndWait(this::stopOnDomainThread, BLOCKING_OP_WAIT_DURATION_MILLIS);
    }

    private void stopOnDomainThread() {
        mThreadingDomain.assertCurrentThread();

        synchronized (mSharedLock) {
            if (mLocationTimeZoneProviderController != null) {
                mLocationTimeZoneProviderController.destroy();
                mLocationTimeZoneProviderController = null;
                mLocationTimeZoneProviderControllerEnvironment.destroy();
                mLocationTimeZoneProviderControllerEnvironment = null;

                // Clear test state so it won't be used the next time the service is started.
                mServiceConfigAccessor.resetVolatileTestConfig();
            }
        }
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        (new LocationTimeZoneManagerShellCommand(this)).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    /** Clears recorded provider state for tests. */
    void clearRecordedProviderStates() {
        enforceManageTimeZoneDetectorPermission();

        mThreadingDomain.postAndWait(() -> {
            synchronized (mSharedLock) {
                if (mLocationTimeZoneProviderController != null) {
                    mLocationTimeZoneProviderController.clearRecordedStates();
                }
            }
        }, BLOCKING_OP_WAIT_DURATION_MILLIS);
    }

    /**
     * Returns a snapshot of the current controller state for tests. Returns {@code null} if the
     * service is stopped.
     */
    @Nullable
    LocationTimeZoneManagerServiceState getStateForTests() {
        enforceManageTimeZoneDetectorPermission();

        try {
            return mThreadingDomain.postAndWait(
                    () -> {
                        synchronized (mSharedLock) {
                            if (mLocationTimeZoneProviderController == null) {
                                return null;
                            }
                            return mLocationTimeZoneProviderController.getStateForTests();
                        }
                    },
                    BLOCKING_OP_WAIT_DURATION_MILLIS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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

            ipw.println("Primary provider config:");
            ipw.increaseIndent();
            mPrimaryProviderConfig.dump(ipw, args);
            ipw.decreaseIndent();

            ipw.println("Secondary provider config:");
            ipw.increaseIndent();
            mSecondaryProviderConfig.dump(ipw, args);
            ipw.decreaseIndent();

            if (mLocationTimeZoneProviderController == null) {
                ipw.println("{Stopped}");
            } else {
                mLocationTimeZoneProviderController.dump(ipw, args);
            }
            ipw.decreaseIndent();
        }
    }

    static void debugLog(String msg) {
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Slog.d(TAG, msg);
        }
    }

    static void infoLog(String msg) {
        if (Log.isLoggable(TAG, Log.INFO)) {
            Slog.i(TAG, msg);
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

    /** An inner class for managing a provider's config. */
    private final class ProviderConfig implements Dumpable {
        @IntRange(from = 0, to = 1) private final int mIndex;
        @NonNull private final String mName;
        @NonNull private final String mServiceAction;

        ProviderConfig(@IntRange(from = 0, to = 1) int index, @NonNull String name,
                @NonNull String serviceAction) {
            Preconditions.checkArgument(index >= 0 && index <= 1);
            mIndex = index;
            mName = Objects.requireNonNull(name);
            mServiceAction = Objects.requireNonNull(serviceAction);
        }

        @NonNull
        LocationTimeZoneProvider createProvider() {
            LocationTimeZoneProviderProxy proxy = createProxy();
            ProviderMetricsLogger providerMetricsLogger = new RealProviderMetricsLogger(mIndex);
            return new BinderLocationTimeZoneProvider(
                    providerMetricsLogger, mThreadingDomain, mName, proxy,
                    mServiceConfigAccessor.getRecordStateChangesForTests());
        }

        @Override
        public void dump(IndentingPrintWriter ipw, String[] args) {
            ipw.printf("getMode()=%s\n", getMode());
            ipw.printf("getPackageName()=%s\n", getPackageName());
        }

        @NonNull
        private LocationTimeZoneProviderProxy createProxy() {
            String mode = getMode();
            if (Objects.equals(mode, PROVIDER_MODE_DISABLED)) {
                return new NullLocationTimeZoneProviderProxy(mContext, mThreadingDomain);
            } else {
                // mode == PROVIDER_MODE_OVERRIDE_ENABLED (or unknown).
                return createRealProxy();
            }
        }

        /** Returns the mode of the provider (enabled/disabled). */
        @NonNull
        private String getMode() {
            if (mIndex == 0) {
                return mServiceConfigAccessor.getPrimaryLocationTimeZoneProviderMode();
            } else {
                return mServiceConfigAccessor.getSecondaryLocationTimeZoneProviderMode();
            }
        }

        @NonNull
        private RealLocationTimeZoneProviderProxy createRealProxy() {
            String providerServiceAction = mServiceAction;
            boolean isTestProvider = isTestProvider();
            String providerPackageName = getPackageName();
            return new RealLocationTimeZoneProviderProxy(
                    mContext, mHandler, mThreadingDomain, providerServiceAction,
                    providerPackageName, isTestProvider);
        }

        private boolean isTestProvider() {
            if (mIndex == 0) {
                return mServiceConfigAccessor.isTestPrimaryLocationTimeZoneProvider();
            } else {
                return mServiceConfigAccessor.isTestSecondaryLocationTimeZoneProvider();
            }
        }

        @NonNull
        private String getPackageName() {
            if (mIndex == 0) {
                return mServiceConfigAccessor.getPrimaryLocationTimeZoneProviderPackageName();
            } else {
                return mServiceConfigAccessor.getSecondaryLocationTimeZoneProviderPackageName();
            }
        }
    }
}
