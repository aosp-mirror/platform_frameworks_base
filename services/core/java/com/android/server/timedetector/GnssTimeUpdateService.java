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
import android.app.AlarmManager;
import android.app.timedetector.GnssTimeSuggestion;
import android.app.timedetector.TimeDetector;
import android.content.Context;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationManagerInternal;
import android.location.LocationRequest;
import android.location.LocationTime;
import android.os.Binder;
import android.os.SystemClock;
import android.os.TimestampedValue;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.DumpUtils;
import com.android.server.FgThread;
import com.android.server.LocalServices;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.time.Duration;

/**
 * Monitors the GNSS time.
 *
 * <p>When available, the time is always suggested to the {@link
 * com.android.server.timedetector.TimeDetectorService} where it may be used to set the device
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
            mService = new GnssTimeUpdateService(getContext());
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
                mService.requestGnssTimeUpdates();
            }
        }
    }

    private static final Duration GNSS_TIME_UPDATE_ALARM_INTERVAL = Duration.ofHours(4);
    private static final String ATTRIBUTION_TAG = "GnssTimeUpdateService";

    private final Context mContext;
    private final TimeDetector mTimeDetector;
    private final AlarmManager mAlarmManager;
    private final LocationManager mLocationManager;
    private final LocationManagerInternal mLocationManagerInternal;

    @Nullable private AlarmManager.OnAlarmListener mAlarmListener;
    @Nullable private LocationListener mLocationListener;
    @Nullable private TimestampedValue<Long> mLastSuggestedGnssTime;

    @VisibleForTesting
    GnssTimeUpdateService(@NonNull Context context) {
        mContext = context.createAttributionContext(ATTRIBUTION_TAG);
        mTimeDetector = mContext.getSystemService(TimeDetector.class);
        mLocationManager = mContext.getSystemService(LocationManager.class);
        mAlarmManager = mContext.getSystemService(AlarmManager.class);
        mLocationManagerInternal = LocalServices.getService(LocationManagerInternal.class);
    }

    /**
     * Request passive location updates. Such a request will not trigger any active locations or
     * power usage itself.
     */
    @VisibleForTesting
    void requestGnssTimeUpdates() {
        if (D) {
            Log.d(TAG, "requestGnssTimeUpdates()");
        }

        // Location Listener triggers onLocationChanged() when GNSS data is available, so
        // that the getGnssTimeMillis() function doesn't need to be continuously polled.
        mLocationListener = new LocationListener() {
            @Override
            public void onLocationChanged(Location location) {
                if (D) {
                    Log.d(TAG, "onLocationChanged()");
                }

                // getGnssTimeMillis() can return null when the Master Location Switch for the
                // foreground user is disabled.
                LocationTime locationTime = mLocationManagerInternal.getGnssTimeMillis();
                if (locationTime != null) {
                    suggestGnssTime(locationTime);
                } else {
                    if (D) {
                        Log.d(TAG, "getGnssTimeMillis() returned null");
                    }
                }

                mLocationManager.removeUpdates(mLocationListener);
                mLocationListener = null;

                mAlarmListener = new AlarmManager.OnAlarmListener() {
                    @Override
                    public void onAlarm() {
                        if (D) {
                            Log.d(TAG, "onAlarm()");
                        }
                        mAlarmListener = null;
                        requestGnssTimeUpdates();
                    }
                };

                // Set next alarm to re-enable location updates.
                long next = SystemClock.elapsedRealtime()
                        + GNSS_TIME_UPDATE_ALARM_INTERVAL.toMillis();
                mAlarmManager.set(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        next,
                        TAG,
                        mAlarmListener,
                        FgThread.getHandler());
            }
        };

        mLocationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                new LocationRequest.Builder(LocationRequest.PASSIVE_INTERVAL)
                        .setMinUpdateIntervalMillis(0)
                        .build(),
                FgThread.getExecutor(),
                mLocationListener);
    }

    /**
     * Convert LocationTime to TimestampedValue. Then suggest TimestampedValue to Time Detector.
     */
    private void suggestGnssTime(LocationTime locationTime) {
        if (D) {
            Log.d(TAG, "suggestGnssTime()");
        }
        long gnssTime = locationTime.getTime();
        long elapsedRealtimeMs = locationTime.getElapsedRealtimeNanos() / 1_000_000L;

        TimestampedValue<Long> timeSignal = new TimestampedValue<>(
                elapsedRealtimeMs, gnssTime);
        mLastSuggestedGnssTime = timeSignal;

        GnssTimeSuggestion timeSuggestion = new GnssTimeSuggestion(timeSignal);
        mTimeDetector.suggestGnssTime(timeSuggestion);
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;
        pw.println("mLastSuggestedGnssTime: " + mLastSuggestedGnssTime);
        pw.print("state: ");
        if (mLocationListener != null) {
            pw.println("time updates enabled");
        } else {
            pw.println("alarm enabled");
        }
    }
}
