/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.timezonedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.time.ITimeZoneDetectorListener;
import android.app.time.TimeZoneCapabilitiesAndConfig;
import android.app.time.TimeZoneConfiguration;
import android.app.time.TimeZoneState;
import android.app.timezonedetector.ITimeZoneDetectorService;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.util.ArrayMap;
import android.util.IndentingPrintWriter;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The implementation of ITimeZoneDetectorService.aidl.
 *
 * <p>This service is implemented as a wrapper around {@link TimeZoneDetectorStrategy}. It handles
 * interaction with Android framework classes, enforcing caller permissions, capturing user identity
 * and making calls async, leaving the (consequently more testable) {@link TimeZoneDetectorStrategy}
 * implementation to deal with the logic around time zone detection.
 */
public final class TimeZoneDetectorService extends ITimeZoneDetectorService.Stub
        implements IBinder.DeathRecipient {

    static final String TAG = "time_zone_detector";
    static final boolean DBG = false;

    /**
     * Handles the service lifecycle for {@link TimeZoneDetectorService} and
     * {@link TimeZoneDetectorInternalImpl}.
     */
    public static final class Lifecycle extends SystemService {

        public Lifecycle(@NonNull Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            // Obtain / create the shared dependencies.
            Context context = getContext();
            Handler handler = FgThread.getHandler();

            ServiceConfigAccessor serviceConfigAccessor =
                    ServiceConfigAccessorImpl.getInstance(context);
            TimeZoneDetectorStrategy timeZoneDetectorStrategy =
                    TimeZoneDetectorStrategyImpl.create(handler, serviceConfigAccessor);
            DeviceActivityMonitor deviceActivityMonitor =
                    DeviceActivityMonitorImpl.create(context, handler);

            // Wire up the telephony fallback behavior to activity detection.
            deviceActivityMonitor.addListener(new DeviceActivityMonitor.Listener() {
                @Override
                public void onFlightComplete() {
                    timeZoneDetectorStrategy.enableTelephonyTimeZoneFallback("onFlightComplete()");
                }
            });

            // Create and publish the local service for use by internal callers.
            CurrentUserIdentityInjector currentUserIdentityInjector =
                    CurrentUserIdentityInjector.REAL;
            TimeZoneDetectorInternal internal = new TimeZoneDetectorInternalImpl(
                    context, handler, currentUserIdentityInjector, timeZoneDetectorStrategy);
            publishLocalService(TimeZoneDetectorInternal.class, internal);

            // Publish the binder service so it can be accessed from other (appropriately
            // permissioned) processes.
            CallerIdentityInjector callerIdentityInjector = CallerIdentityInjector.REAL;
            TimeZoneDetectorService service = new TimeZoneDetectorService(
                    context, handler, callerIdentityInjector, timeZoneDetectorStrategy);

            // Dump the device activity monitor when the service is dumped.
            service.addDumpable(deviceActivityMonitor);

            publishBinderService(Context.TIME_ZONE_DETECTOR_SERVICE, service);
        }
    }

    @NonNull
    private final Context mContext;

    @NonNull
    private final Handler mHandler;

    @NonNull
    private final CallerIdentityInjector mCallerIdentityInjector;

    @NonNull
    private final TimeZoneDetectorStrategy mTimeZoneDetectorStrategy;

    /**
     * Holds the listeners. The key is the {@link IBinder} associated with the listener, the value
     * is the listener itself.
     */
    @GuardedBy("mListeners")
    @NonNull
    private final ArrayMap<IBinder, ITimeZoneDetectorListener> mListeners = new ArrayMap<>();

    /**
     * References to components that should be dumped when {@link
     * #dump(FileDescriptor, PrintWriter, String[])} is called on the service.
     */
    @GuardedBy("mDumpables")
    private final List<Dumpable> mDumpables = new ArrayList<>();

    @VisibleForTesting
    public TimeZoneDetectorService(@NonNull Context context, @NonNull Handler handler,
            @NonNull CallerIdentityInjector callerIdentityInjector,
            @NonNull TimeZoneDetectorStrategy timeZoneDetectorStrategy) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mCallerIdentityInjector = Objects.requireNonNull(callerIdentityInjector);
        mTimeZoneDetectorStrategy = Objects.requireNonNull(timeZoneDetectorStrategy);

        // Wire up a change listener so that ITimeZoneDetectorListeners can be notified when
        // the detector state changes for any reason.
        mTimeZoneDetectorStrategy.addChangeListener(
                () -> mHandler.post(this::handleChangeOnHandlerThread));
    }

    @Override
    @NonNull
    public TimeZoneCapabilitiesAndConfig getCapabilitiesAndConfig() {
        int userId = mCallerIdentityInjector.getCallingUserId();
        return getCapabilitiesAndConfig(userId);
    }

    TimeZoneCapabilitiesAndConfig getCapabilitiesAndConfig(@UserIdInt int userId) {
        enforceManageTimeZoneDetectorPermission();

        // Resolve constants like USER_CURRENT to the true user ID as needed.
        int resolvedUserId =
                mCallerIdentityInjector.resolveUserId(userId, "getCapabilitiesAndConfig");

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            final boolean bypassUserPolicyChecks = false;
            return mTimeZoneDetectorStrategy.getCapabilitiesAndConfig(
                    resolvedUserId, bypassUserPolicyChecks);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean updateConfiguration(@NonNull TimeZoneConfiguration configuration) {
        int callingUserId = mCallerIdentityInjector.getCallingUserId();
        return updateConfiguration(callingUserId, configuration);
    }

    boolean updateConfiguration(
            @UserIdInt int userId, @NonNull TimeZoneConfiguration configuration) {
        // Resolve constants like USER_CURRENT to the true user ID as needed.
        int resolvedUserId = mCallerIdentityInjector.resolveUserId(userId, "updateConfiguration");

        enforceManageTimeZoneDetectorPermission();

        Objects.requireNonNull(configuration);

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            final boolean bypassUserPolicyChecks = false;
            return mTimeZoneDetectorStrategy.updateConfiguration(
                    resolvedUserId, configuration, bypassUserPolicyChecks);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public void addListener(@NonNull ITimeZoneDetectorListener listener) {
        enforceManageTimeZoneDetectorPermission();
        Objects.requireNonNull(listener);

        synchronized (mListeners) {
            IBinder listenerBinder = listener.asBinder();
            if (mListeners.containsKey(listenerBinder)) {
                return;
            }
            try {
                // Ensure the reference to the listener will be removed if the client process dies.
                listenerBinder.linkToDeath(this, 0 /* flags */);

                // Only add the listener if we can linkToDeath().
                mListeners.put(listenerBinder, listener);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to linkToDeath() for listener=" + listener, e);
            }
        }
    }

    @Override
    public void removeListener(@NonNull ITimeZoneDetectorListener listener) {
        enforceManageTimeZoneDetectorPermission();
        Objects.requireNonNull(listener);

        synchronized (mListeners) {
            IBinder listenerBinder = listener.asBinder();
            boolean removedListener = false;
            if (mListeners.remove(listenerBinder) != null) {
                // Stop listening for the client process to die.
                listenerBinder.unlinkToDeath(this, 0 /* flags */);
                removedListener = true;
            }
            if (!removedListener) {
                Slog.w(TAG, "Client asked to remove listener=" + listener
                        + ", but no listeners were removed."
                        + " mListeners=" + mListeners);
            }
        }
    }

    @Override
    public void binderDied() {
        // Should not be used as binderDied(IBinder who) is overridden.
        Slog.wtf(TAG, "binderDied() called unexpectedly.");
    }

    /**
     * Called when one of the ITimeZoneDetectorListener processes dies before calling
     * {@link #removeListener(ITimeZoneDetectorListener)}.
     */
    @Override
    public void binderDied(IBinder who) {
        synchronized (mListeners) {
            boolean removedListener = false;
            final int listenerCount = mListeners.size();
            for (int listenerIndex = listenerCount - 1; listenerIndex >= 0; listenerIndex--) {
                IBinder listenerBinder = mListeners.keyAt(listenerIndex);
                if (listenerBinder.equals(who)) {
                    mListeners.removeAt(listenerIndex);
                    removedListener = true;
                    break;
                }
            }
            if (!removedListener) {
                Slog.w(TAG, "Notified of binder death for who=" + who
                        + ", but did not remove any listeners."
                        + " mListeners=" + mListeners);
            }
        }
    }

    void handleChangeOnHandlerThread() {
        // Detector state has changed. Each user may have a different view of the configuration so
        // no information is passed; each client must query what they're interested in.
        // It's possible that this will cause unnecessary notifications but that shouldn't be a
        // problem.
        synchronized (mListeners) {
            final int listenerCount = mListeners.size();
            for (int listenerIndex = 0; listenerIndex < listenerCount; listenerIndex++) {
                ITimeZoneDetectorListener listener = mListeners.valueAt(listenerIndex);
                try {
                    listener.onChange();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to notify listener=" + listener, e);
                }
            }
        }
    }

    /** Provided for command-line access. This is not exposed as a binder API. */
    void handleLocationAlgorithmEvent(@NonNull LocationAlgorithmEvent locationAlgorithmEvent) {
        enforceSuggestGeolocationTimeZonePermission();
        Objects.requireNonNull(locationAlgorithmEvent);

        mHandler.post(
                () -> mTimeZoneDetectorStrategy.handleLocationAlgorithmEvent(
                        locationAlgorithmEvent));
    }

    @Override
    @NonNull
    public TimeZoneState getTimeZoneState() {
        enforceManageTimeZoneDetectorPermission();

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            return mTimeZoneDetectorStrategy.getTimeZoneState();
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    void setTimeZoneState(@NonNull TimeZoneState timeZoneState) {
        enforceManageTimeZoneDetectorPermission();

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            mTimeZoneDetectorStrategy.setTimeZoneState(timeZoneState);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean confirmTimeZone(@NonNull String timeZoneId) {
        enforceManageTimeZoneDetectorPermission();

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            return mTimeZoneDetectorStrategy.confirmTimeZone(timeZoneId);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean setManualTimeZone(@NonNull ManualTimeZoneSuggestion suggestion) {
        enforceManageTimeZoneDetectorPermission();

        // This calls suggestManualTimeZone() as the logic is identical, it only differs in the
        // permission required, which is handled on the line above.
        int userId = mCallerIdentityInjector.getCallingUserId();
        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            final boolean bypassUserPolicyChecks = false;
            return mTimeZoneDetectorStrategy.suggestManualTimeZone(
                    userId, suggestion, bypassUserPolicyChecks);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean suggestManualTimeZone(@NonNull ManualTimeZoneSuggestion suggestion) {
        enforceSuggestManualTimeZonePermission();
        Objects.requireNonNull(suggestion);

        int userId = mCallerIdentityInjector.getCallingUserId();
        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            final boolean bypassUserPolicyChecks = false;
            return mTimeZoneDetectorStrategy.suggestManualTimeZone(
                    userId, suggestion, bypassUserPolicyChecks);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public void suggestTelephonyTimeZone(@NonNull TelephonyTimeZoneSuggestion suggestion) {
        enforceSuggestTelephonyTimeZonePermission();
        Objects.requireNonNull(suggestion);

        mHandler.post(() -> mTimeZoneDetectorStrategy.suggestTelephonyTimeZone(suggestion));
    }

    boolean isTelephonyTimeZoneDetectionSupported() {
        enforceManageTimeZoneDetectorPermission();

        return mTimeZoneDetectorStrategy.isTelephonyTimeZoneDetectionSupported();
    }

    boolean isGeoTimeZoneDetectionSupported() {
        enforceManageTimeZoneDetectorPermission();

        return mTimeZoneDetectorStrategy.isGeoTimeZoneDetectionSupported();
    }

    /**
     * Sends a signal to enable telephony fallback. Provided for command-line access for use
     * during tests. This is not exposed as a binder API.
     */
    void enableTelephonyFallback(@NonNull String reason) {
        enforceManageTimeZoneDetectorPermission();
        mTimeZoneDetectorStrategy.enableTelephonyTimeZoneFallback(reason);
    }

    /**
     * Registers the supplied {@link Dumpable} for dumping. When the service is dumped
     * {@link Dumpable#dump(IndentingPrintWriter, String[])} will be called on the {@code dumpable}.
     */
    void addDumpable(@NonNull Dumpable dumpable) {
        synchronized (mDumpables) {
            mDumpables.add(dumpable);
        }
    }

    @NonNull
    MetricsTimeZoneDetectorState generateMetricsState() {
        enforceManageTimeZoneDetectorPermission();

        return mTimeZoneDetectorStrategy.generateMetricsState();
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        mTimeZoneDetectorStrategy.dump(ipw, args);

        synchronized (mDumpables) {
            for (Dumpable dumpable : mDumpables) {
                dumpable.dump(ipw, args);
            }
        }

        ipw.flush();
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new TimeZoneDetectorShellCommand(this).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    private void enforceManageTimeZoneDetectorPermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION,
                "manage time and time zone detection");
    }

    private void enforceSuggestGeolocationTimeZonePermission() {
        // The associated method is only used for the shell command interface, it's not possible to
        // call it via Binder, and Shell currently can set the time zone directly anyway.
        mContext.enforceCallingPermission(
                android.Manifest.permission.SET_TIME_ZONE,
                "suggest geolocation time zone");
    }

    private void enforceSuggestTelephonyTimeZonePermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE,
                "suggest telephony time and time zone");
    }

    private void enforceSuggestManualTimeZonePermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE,
                "suggest manual time and time zone");
    }
}

