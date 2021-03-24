/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.timedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.app.time.ExternalTimeSuggestion;
import android.app.time.TimeCapabilitiesAndConfig;
import android.app.time.TimeConfiguration;
import android.app.timedetector.GnssTimeSuggestion;
import android.app.timedetector.ITimeDetectorService;
import android.app.timedetector.ManualTimeSuggestion;
import android.app.timedetector.NetworkTimeSuggestion;
import android.app.timedetector.TelephonyTimeSuggestion;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Binder;
import android.os.Handler;
import android.provider.Settings;
import android.util.IndentingPrintWriter;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.SystemService;
import com.android.server.timezonedetector.CallerIdentityInjector;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

/**
 * The implementation of ITimeDetectorService.aidl.
 *
 * <p>This service is implemented as a wrapper around {@link TimeDetectorStrategy}. It handles
 * interaction with Android framework classes, enforcing caller permissions, capturing user identity
 * and making calls async, leaving the (consequently more testable) {@link TimeDetectorStrategy}
 * implementation to deal with the logic around time detection.
 */
public final class TimeDetectorService extends ITimeDetectorService.Stub {
    static final String TAG = "time_detector";

    public static class Lifecycle extends SystemService {

        public Lifecycle(@NonNull Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            TimeDetectorService service = TimeDetectorService.create(getContext());

            // Publish the binder service so it can be accessed from other (appropriately
            // permissioned) processes.
            publishBinderService(Context.TIME_DETECTOR_SERVICE, service);
        }
    }

    @NonNull private final Handler mHandler;
    @NonNull private final Context mContext;
    @NonNull private final TimeDetectorStrategy mTimeDetectorStrategy;
    @NonNull private final CallerIdentityInjector mCallerIdentityInjector;

    private static TimeDetectorService create(@NonNull Context context) {
        TimeDetectorStrategyImpl.Environment environment = new EnvironmentImpl(context);
        TimeDetectorStrategy timeDetectorStrategy = new TimeDetectorStrategyImpl(environment);

        Handler handler = FgThread.getHandler();
        TimeDetectorService timeDetectorService =
                new TimeDetectorService(context, handler, timeDetectorStrategy);

        // Wire up event listening.
        ContentResolver contentResolver = context.getContentResolver();
        contentResolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.AUTO_TIME), true,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        timeDetectorService.handleAutoTimeDetectionChanged();
                    }
                });

        return timeDetectorService;
    }

    @VisibleForTesting
    public TimeDetectorService(@NonNull Context context, @NonNull Handler handler,
            @NonNull TimeDetectorStrategy timeDetectorStrategy) {
        this(context, handler, timeDetectorStrategy, CallerIdentityInjector.REAL);
    }

    @VisibleForTesting
    public TimeDetectorService(@NonNull Context context, @NonNull Handler handler,
            @NonNull TimeDetectorStrategy timeDetectorStrategy,
            @NonNull CallerIdentityInjector callerIdentityInjector) {
        mContext = Objects.requireNonNull(context);
        mHandler = Objects.requireNonNull(handler);
        mTimeDetectorStrategy = Objects.requireNonNull(timeDetectorStrategy);
        mCallerIdentityInjector = Objects.requireNonNull(callerIdentityInjector);
    }

    @Override
    public TimeCapabilitiesAndConfig getCapabilitiesAndConfig() {
        int userId = mCallerIdentityInjector.getCallingUserId();
        return getTimeCapabilitiesAndConfig(userId);
    }

    private TimeCapabilitiesAndConfig getTimeCapabilitiesAndConfig(@UserIdInt int userId) {
        enforceManageTimeDetectorPermission();

        final long token = mCallerIdentityInjector.clearCallingIdentity();
        try {
            ConfigurationInternal configurationInternal =
                    mTimeDetectorStrategy.getConfigurationInternal(userId);
            return configurationInternal.capabilitiesAndConfig();
        } finally {
            mCallerIdentityInjector.restoreCallingIdentity(token);
        }
    }

    @Override
    public boolean updateConfiguration(TimeConfiguration timeConfiguration) {
        // TODO(b/172891783) Add actual logic
        return false;
    }

    @Override
    public void suggestTelephonyTime(@NonNull TelephonyTimeSuggestion timeSignal) {
        enforceSuggestTelephonyTimePermission();
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestTelephonyTime(timeSignal));
    }

    @Override
    public boolean suggestManualTime(@NonNull ManualTimeSuggestion timeSignal) {
        enforceSuggestManualTimePermission();
        Objects.requireNonNull(timeSignal);

        final long token = Binder.clearCallingIdentity();
        try {
            return mTimeDetectorStrategy.suggestManualTime(timeSignal);
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    @Override
    public void suggestNetworkTime(@NonNull NetworkTimeSuggestion timeSignal) {
        enforceSuggestNetworkTimePermission();
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestNetworkTime(timeSignal));
    }

    @Override
    public void suggestGnssTime(@NonNull GnssTimeSuggestion timeSignal) {
        enforceSuggestGnssTimePermission();
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestGnssTime(timeSignal));
    }

    @Override
    public void suggestExternalTime(@NonNull ExternalTimeSuggestion timeSignal) {
        enforceSuggestExternalTimePermission();
        Objects.requireNonNull(timeSignal);

        mHandler.post(() -> mTimeDetectorStrategy.suggestExternalTime(timeSignal));
    }

    /** Internal method for handling the auto time setting being changed. */
    @VisibleForTesting
    public void handleAutoTimeDetectionChanged() {
        mHandler.post(mTimeDetectorStrategy::handleAutoTimeConfigChanged);
    }

    @Override
    protected void dump(@NonNull FileDescriptor fd, @NonNull PrintWriter pw,
            @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        IndentingPrintWriter ipw = new IndentingPrintWriter(pw);
        mTimeDetectorStrategy.dump(ipw, args);
        ipw.flush();
    }

    private void enforceSuggestTelephonyTimePermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SUGGEST_TELEPHONY_TIME_AND_ZONE,
                "suggest telephony time and time zone");
    }

    private void enforceSuggestManualTimePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SUGGEST_MANUAL_TIME_AND_ZONE,
                "suggest manual time and time zone");
    }

    private void enforceSuggestNetworkTimePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_TIME,
                "set time");
    }

    private void enforceSuggestGnssTimePermission() {
        mContext.enforceCallingOrSelfPermission(
                android.Manifest.permission.SET_TIME,
                "suggest gnss time");
    }

    private void enforceSuggestExternalTimePermission() {
        // We don't expect a call from system server, so simply enforce calling permission.
        mContext.enforceCallingPermission(
                android.Manifest.permission.SUGGEST_EXTERNAL_TIME,
                "suggest time from external source");
    }

    private void enforceManageTimeDetectorPermission() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.MANAGE_TIME_AND_ZONE_DETECTION,
                "manage time and time zone detection");
    }

}
