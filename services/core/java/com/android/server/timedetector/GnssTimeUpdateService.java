/*
 * Copyright (C) 2021 The Android Open Source Project
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
import android.annotation.RequiresPermission;
import android.app.AlarmManager;
import android.app.time.UnixEpochTime;
import android.content.Context;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.os.Binder;
import android.os.Handler;
import android.os.ResultReceiver;
import android.os.ShellCallback;
import android.os.SystemClock;
import android.util.LocalLog;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * Monitors the GNSS time.
 *
 * <p>When available, the time is always suggested to the {@link
 * com.android.server.timedetector.TimeDetectorInternal} where it may be used to set the device
 * system clock, depending on user settings and what other signals are available.
 */
public final class GnssTimeUpdateService extends Binder {
    private static final String TAG = "GnssTimeUpdateService";
    private static final boolean D = Log.isLoggable(TAG, Log.DEBUG);

    /**
     * Handles the lifecycle events for the GnssTimeUpdateService.
     */
    public static class Lifecycle extends SystemService {
        private GnssTimeUpdateService mService;

        public Lifecycle(@NonNull Context context) {
            super(context);
        }

        @Override
        public void onStart() {
            Context context = getContext().createAttributionContext(ATTRIBUTION_TAG);
            AlarmManager alarmManager = context.getSystemService(AlarmManager.class);
            LocationManager locationManager = context.getSystemService(LocationManager.class);
            LocationManagerInternal locationManagerInternal =
                    LocalServices.getService(LocationManagerInternal.class);
            TimeDetectorInternal timeDetectorInternal =
                    LocalServices.getService(TimeDetectorInternal.class);

            mService = new GnssTimeUpdateService(context, alarmManager, locationManager,
                    locationManagerInternal, timeDetectorInternal);
            publishBinderService("gnss_time_update_service", mService);
        }

        @Override
        public void onBootPhase(int phase) {
            // Need to wait for some location providers to be enabled. If done at
            // PHASE_SYSTEM_SERVICES_READY, error where "gps" provider does not exist could occur.
            if (phase == PHASE_THIRD_PARTY_APPS_CAN_START) {
                // Initiate location updates. On boot, GNSS might not be available right away.
                // Instead of polling GNSS time periodically, passive location updates are enabled.
                // Once an update is received, the gnss time will be queried and suggested to
                // TimeDetectorService.
                mService.startGnssListeningInternal();
            }
        }
    }

    private static final Duration GNSS_TIME_UPDATE_ALARM_INTERVAL = Duration.ofHours(4);
    private static final String ATTRIBUTION_TAG = "GnssTimeUpdateService";

    /**
     * A log that records the decisions to fetch a GNSS time update.
     * This is logged in bug reports to assist with debugging issues with GNSS time suggestions.
     */
    private final LocalLog mLocalLog = new LocalLog(10, false /* useLocalTimestamps */);
    /** The executor used for async operations */
    private final Executor mExecutor = FgThread.getExecutor();
    /** The handler used for async operations */
    private final Handler mHandler = FgThread.getHandler();

    private final Context mContext;
    private final TimeDetectorInternal mTimeDetectorInternal;
    private final AlarmManager mAlarmManager;
    private final LocationManager mLocationManager;
    private final LocationManagerInternal mLocationManagerInternal;


    private final Object mLock = new Object();
    @GuardedBy("mLock") @Nullable private AlarmManager.OnAlarmListener mAlarmListener;
    @GuardedBy("mLock") @Nullable private LocationListener mLocationListener;

    @Nullable private volatile UnixEpochTime mLastSuggestedGnssTime;

    @VisibleForTesting
    GnssTimeUpdateService(@NonNull Context context, @NonNull AlarmManager alarmManager,
            @NonNull LocationManager locationManager,
            @NonNull LocationManagerInternal locationManagerInternal,
            @NonNull TimeDetectorInternal timeDetectorInternal) {
        mContext = Objects.requireNonNull(context);
        mAlarmManager = Objects.requireNonNull(alarmManager);
        mLocationManager = Objects.requireNonNull(locationManager);
        mLocationManagerInternal = Objects.requireNonNull(locationManagerInternal);
        mTimeDetectorInternal = Objects.requireNonNull(timeDetectorInternal);
    }

    /**
     * Used by {@link com.android.server.timedetector.GnssTimeUpdateServiceShellCommand} to force
     * the service into GNSS listening mode.
     */
    @RequiresPermission(android.Manifest.permission.SET_TIME)
    boolean startGnssListening() {
        mContext.enforceCallingPermission(
                android.Manifest.permission.SET_TIME, "Start GNSS listening");
        mLocalLog.log("startGnssListening() called");

        final long token = Binder.clearCallingIdentity();
        try {
            return startGnssListeningInternal();
        } finally {
            Binder.restoreCallingIdentity(token);
        }
    }

    /**
     * Starts listening for passive location updates. Such a request will not trigger any active
     * locations or power usage itself. Returns {@code true} if the service is listening after the
     * method returns and {@code false} otherwise. At present this method only returns {@code false}
     * if there is no GPS provider on the device.
     *
     * <p>If the service is already listening for locations this is a no-op. If the device is in a
     * "sleeping" state between listening periods then it will return to listening.
     */
    @VisibleForTesting
    boolean startGnssListeningInternal() {
        if (!mLocationManager.hasProvider(LocationManager.GPS_PROVIDER)) {
            logError("GPS provider does not exist on this device");
            return false;
        }

        synchronized (mLock) {
            if (mLocationListener != null) {
                logDebug("Already listening for GNSS updates");
                return true;
            }

            // If startGnssListening() is called during manual tests to jump back into location
            // listening then there will usually be an alarm set.
            if (mAlarmListener != null) {
                mAlarmManager.cancel(mAlarmListener);
                mAlarmListener = null;
            }

            startGnssListeningLocked();
            return true;
        }
    }

    @GuardedBy("mLock")
    private void startGnssListeningLocked() {
        logDebug("startGnssListeningLocked()");

        // Location Listener triggers onLocationChanged() when GNSS data is available, so
        // that the getGnssTimeMillis() function doesn't need to be continuously polled.
        mLocationListener = location -> handleLocationAvailable();
        mLocationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                new LocationRequest.Builder(LocationRequest.PASSIVE_INTERVAL)
                        .setMinUpdateIntervalMillis(0)
                        .build(),
                mExecutor,
                mLocationListener);
    }

    private void handleLocationAvailable() {
        logDebug("handleLocationAvailable()");

        // getGnssTimeMillis() can return null when the Master Location Switch for the
        // foreground user is disabled.
        LocationTime locationTime = mLocationManagerInternal.getGnssTimeMillis();
        if (locationTime != null) {
            String msg = "Passive location time received: " + locationTime;
            logDebug(msg);
            mLocalLog.log(msg);
            suggestGnssTime(locationTime);
        } else {
            logDebug("getGnssTimeMillis() returned null");
        }

        synchronized (mLock) {
            if (mLocationListener == null) {
                logWarning("mLocationListener unexpectedly null");
            } else {
                mLocationManager.removeUpdates(mLocationListener);
                mLocationListener = null;
            }

            if (mAlarmListener != null) {
                logWarning("mAlarmListener was unexpectedly non-null");
                mAlarmManager.cancel(mAlarmListener);
            }

            // Set next alarm to re-enable location updates.
            long next = SystemClock.elapsedRealtime()
                    + GNSS_TIME_UPDATE_ALARM_INTERVAL.toMillis();
            mAlarmListener = this::handleAlarmFired;
            mAlarmManager.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    next,
                    TAG,
                    mAlarmListener,
                    mHandler);
        }
    }

    private void handleAlarmFired() {
        logDebug("handleAlarmFired()");

        synchronized (mLock) {
            mAlarmListener = null;
            startGnssListeningLocked();
        }
    }

    /**
     * Convert LocationTime to TimestampedValue. Then suggest TimestampedValue to Time Detector.
     */
    private void suggestGnssTime(LocationTime locationTime) {
        logDebug("suggestGnssTime()");

        long gnssUnixEpochTimeMillis = locationTime.getUnixEpochTimeMillis();
        long elapsedRealtimeMs = locationTime.getElapsedRealtimeNanos() / 1_000_000L;

        UnixEpochTime unixEpochTime = new UnixEpochTime(elapsedRealtimeMs, gnssUnixEpochTimeMillis);
        mLastSuggestedGnssTime = unixEpochTime;

        GnssTimeSuggestion suggestion = new GnssTimeSuggestion(unixEpochTime);
        mTimeDetectorInternal.suggestGnssTime(suggestion);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        pw.println("mLastSuggestedGnssTime: " + mLastSuggestedGnssTime);
        synchronized (mLock) {
            pw.print("state: ");
            if (mLocationListener != null) {
                pw.println("time updates enabled");
            } else {
                pw.println("alarm enabled");
            }
        }
        pw.println("Log:");
        mLocalLog.dump(pw);
    }

    @Override
    public void onShellCommand(FileDescriptor in, FileDescriptor out, FileDescriptor err,
            String[] args, ShellCallback callback, ResultReceiver resultReceiver) {
        new GnssTimeUpdateServiceShellCommand(this).exec(
                this, in, out, err, args, callback, resultReceiver);
    }

    private void logError(String msg) {
        Log.e(TAG, msg);
        mLocalLog.log(msg);
    }

    private void logWarning(String msg) {
        Log.w(TAG, msg);
        mLocalLog.log(msg);
    }

    private void logDebug(String msg) {
        if (D) {
            Log.d(TAG, msg);
        }
    }
}
