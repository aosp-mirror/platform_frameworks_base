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
import android.app.timezonedetector.ITimeZoneConfigurationListener;
import android.app.timezonedetector.ITimeZoneDetectorService;
import android.app.timezonedetector.ManualTimeZoneSuggestion;
import android.app.timezonedetector.TelephonyTimeZoneSuggestion;
import android.app.timezonedetector.TimeZoneCapabilities;
import android.app.timezonedetector.TimeZoneConfiguration;
import android.content.Context;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.ShellCallback;
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

    private static final String TAG = "TimeZoneDetectorService";

    /**
     * A compile time constant "feature switch" for enabling / disabling location-based time zone
     * detection on Android. If this is {@code false}, there should be few / little changes in
     * behavior with previous releases and little overhead associated with geolocation components.
     */
    public static final boolean GEOLOCATION_TIME_ZONE_DETECTION_ENABLED = false;

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

            TimeZoneDetectorStrategy timeZoneDetectorStrategy =
                    TimeZoneDetectorStrategyImpl.create(
                            context, handler, GEOLOCATION_TIME_ZONE_DETECTION_ENABLED);

            // Create and publish the local service for use by internal callers.
            TimeZoneDetectorInternal internal =
                    new TimeZoneDetectorInternalImpl(context, handler, timeZoneDetectorStrategy);
            publishLocalService(TimeZoneDetectorInternal.class, internal);

            // Publish the binder service so it can be accessed from other (appropriately
            // permissioned) processes.
            TimeZoneDetectorService service = TimeZoneDetectorService.create(
                    context, handler, timeZoneDetectorStrategy);
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

    @GuardedBy("mConfigurationListeners")
    @NonNull
    private final ArrayList<ITimeZoneConfigurationListener> mConfigurationListeners =
            new ArrayList<>();

    private static TimeZoneDetectorService create(
            @NonNull Context context, @NonNull Handler handler,
            @NonNull TimeZoneDetectorStrategy timeZoneDetectorStrategy) {

        CallerIdentityInjector callerIdentityInjector = CallerIdentityInjector.REAL;
        TimeZoneDetectorService service = new TimeZoneDetectorService(
                context, handler, callerIdentityInjector, timeZoneDetectorStrategy);
        return service;
    }

    @VisibleForTesting
    public TimeZoneDetectorService(@NonNull Context context, @NonNull Handler handler,
            @NonNull CallerIdentityInjector callerIdentityInjector,
            @NonNull TimeZoneDetectorStrategy timeZoneDetectorStrategy) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mCallerIdentityInjector = Objects.requireNonNull(callerIdentityInjector);
        mTimeZoneDetectorStrategy = Objects.requireNonNull(timeZoneDetectorStrategy);

        // Wire up a change listener so that ITimeZoneConfigurationListeners can be notified when
        // the configuration changes for any reason.
        mTimeZoneDetectorStrategy.addConfigChangeListener(this::handleConfigurationChanged);
    }

    @Override
    @NonNull
    public TimeZoneCapabilities getCapabilities() {
        enforceManageTimeZoneDetectorConfigurationPermission();

        int userId = mCallerIdentityInjector.getCallingUserId();
        long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            return mTimeZoneDetectorStrategy.getConfigurationInternal(userId).createCapabilities();
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean updateConfiguration(@NonNull TimeZoneConfiguration configuration) {
        enforceManageTimeZoneDetectorConfigurationPermission();
        Objects.requireNonNull(configuration);

        int callingUserId = mCallerIdentityInjector.getCallingUserId();
        if (callingUserId != configuration.getUserId()) {
            return false;
        }

        long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            return mTimeZoneDetectorStrategy.updateConfiguration(configuration);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public void addConfigurationListener(@NonNull ITimeZoneConfigurationListener listener) {
        enforceManageTimeZoneDetectorConfigurationPermission();
        Objects.requireNonNull(listener);

        synchronized (mConfigurationListeners) {
            if (mConfigurationListeners.contains(listener)) {
                return;
            }
            try {
                // Ensure the reference to the listener will be removed if the client process dies.
                listener.asBinder().linkToDeath(this, 0 /* flags */);

                // Only add the listener if we can linkToDeath().
                mConfigurationListeners.add(listener);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to linkToDeath() for listener=" + listener, e);
            }
        }
    }

    @Override
    public void removeConfigurationListener(@NonNull ITimeZoneConfigurationListener listener) {
        enforceManageTimeZoneDetectorConfigurationPermission();
        Objects.requireNonNull(listener);

        synchronized (mConfigurationListeners) {
            boolean removedListener = false;
            if (mConfigurationListeners.remove(listener)) {
                // Stop listening for the client process to die.
                listener.asBinder().unlinkToDeath(this, 0 /* flags */);
                removedListener = true;
            }
            if (!removedListener) {
                Slog.w(TAG, "Client asked to remove listener=" + listener
                        + ", but no listeners were removed."
                        + " mConfigurationListeners=" + mConfigurationListeners);
            }
        }
    }

    @Override
    public void binderDied() {
        // Should not be used as binderDied(IBinder who) is overridden.
        Slog.wtf(TAG, "binderDied() called unexpectedly.");
    }

    /**
     * Called when one of the ITimeZoneConfigurationListener processes dies before calling
     * {@link #removeConfigurationListener(ITimeZoneConfigurationListener)}.
     */
    @Override
    public void binderDied(IBinder who) {
        synchronized (mConfigurationListeners) {
            boolean removedListener = false;
            final int listenerCount = mConfigurationListeners.size();
            for (int listenerIndex = listenerCount - 1; listenerIndex >= 0; listenerIndex--) {
                ITimeZoneConfigurationListener listener =
                        mConfigurationListeners.get(listenerIndex);
                if (listener.asBinder().equals(who)) {
                    mConfigurationListeners.remove(listenerIndex);
                    removedListener = true;
                    break;
                }
            }
            if (!removedListener) {
                Slog.w(TAG, "Notified of binder death for who=" + who
                        + ", but did not remove any listeners."
                        + " mConfigurationListeners=" + mConfigurationListeners);
            }
        }
    }

    void handleConfigurationChanged() {
        // Configuration has changed, but each user may have a different view of the configuration.
        // It's possible that this will cause unnecessary notifications but that shouldn't be a
        // problem.
        synchronized (mConfigurationListeners) {
            final int listenerCount = mConfigurationListeners.size();
            for (int listenerIndex = 0; listenerIndex < listenerCount; listenerIndex++) {
                ITimeZoneConfigurationListener listener =
                        mConfigurationListeners.get(listenerIndex);
                try {
                    listener.onChange();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Unable to notify listener=" + listener, e);
                }
            }
        }
    }

    /** Provided for command-line access. This is not exposed as a binder API. */
    void suggestGeolocationTimeZone(@NonNull GeolocationTimeZoneSuggestion timeZoneSuggestion) {
        enforceSuggestGeolocationTimeZonePermission();
        Objects.requireNonNull(timeZoneSuggestion);

        mHandler.post(
                () -> mTimeZoneDetectorStrategy.suggestGeolocationTimeZone(timeZoneSuggestion));
    }

    @Override
    public boolean suggestManualTimeZone(@NonNull ManualTimeZoneSuggestion timeZoneSuggestion) {
        enforceSuggestManualTimeZonePermission();
        Objects.requireNonNull(timeZoneSuggestion);

        int userId = mCallerIdentityInjector.getCallingUserId();
        long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            return mTimeZoneDetectorStrategy.suggestManualTimeZone(userId, timeZoneSuggestion);
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public void suggestTelephonyTimeZone(@NonNull TelephonyTimeZoneSuggestion timeZoneSuggestion) {
        enforceSuggestTelephonyTimeZonePermission();
        Objects.requireNonNull(timeZoneSuggestion);

        mHandler.post(() -> mTimeZoneDetectorStrategy.suggestTelephonyTimeZone(timeZoneSuggestion));
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        mTimeZoneDetectorStrategy.dump(ipw, args);
        ipw.flush();
    }

    private void enforceManageTimeZoneDetectorConfigurationPermission() {
        // TODO Switch to a dedicated MANAGE_TIME_AND_ZONE_CONFIGURATION permission.
        mContext.enforceCallingPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS,
                "manage time and time zone configuration");
    }

    private void enforceSuggestGeolocationTimeZonePermission() {
        // The associated method is only used for the shell command interface, it's not possible to
        // call it via Binder, and Shell currently can set the time zone directly anyway.
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_TIME_ZONE,
                "suggest geolocation time zone");
    }

    private void enforceSuggestTelephonyTimeZonePermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE,
                "suggest telephony time and time zone");
    }

    private void enforceSuggestManualTimeZonePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE,
                "suggest manual time and time zone");
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out,
            FileDescriptor err, String[] args, ShellCallback callback,
            ResultReceiver resultReceiver) {
        new TimeZoneDetectorShellCommand(this).exec(
                this, in, out, err, args, callback, resultReceiver);
    }
}

