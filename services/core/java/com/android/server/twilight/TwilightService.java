/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.twilight;

import android.annotation.NonNull;
import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.icu.impl.CalendarAstronomer;
import android.icu.util.Calendar;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.server.SystemService;

import java.util.Objects;

/**
 * Figures out whether it's twilight time based on the user's location.
 * <p>
 * Used by the UI mode manager and other components to adjust night mode
 * effects based on sunrise and sunset.
 */
public final class TwilightService extends SystemService
        implements AlarmManager.OnAlarmListener, Handler.Callback, LocationListener {

    private static final String TAG = "TwilightService";
    private static final boolean DEBUG = false;

    private static final int MSG_START_LISTENING = 1;
    private static final int MSG_STOP_LISTENING = 2;

    @GuardedBy("mListeners")
    private final ArrayMap<TwilightListener, Handler> mListeners = new ArrayMap<>();

    private final Handler mHandler;

    private AlarmManager mAlarmManager;
    private LocationManager mLocationManager;

    private boolean mBootCompleted;
    private boolean mHasListeners;

    private BroadcastReceiver mTimeChangedReceiver;
    private Location mLastLocation;

    @GuardedBy("mListeners")
    private TwilightState mLastTwilightState;

    public TwilightService(Context context) {
        super(context);
        mHandler = new Handler(Looper.getMainLooper(), this);
    }

    @Override
    public void onStart() {
        publishLocalService(TwilightManager.class, new TwilightManager() {
            @Override
            public void registerListener(@NonNull TwilightListener listener,
                    @NonNull Handler handler) {
                synchronized (mListeners) {
                    final boolean wasEmpty = mListeners.isEmpty();
                    mListeners.put(listener, handler);

                    if (wasEmpty && !mListeners.isEmpty()) {
                        mHandler.sendEmptyMessage(MSG_START_LISTENING);
                    }
                }
            }

            @Override
            public void unregisterListener(@NonNull TwilightListener listener) {
                synchronized (mListeners) {
                    final boolean wasEmpty = mListeners.isEmpty();
                    mListeners.remove(listener);

                    if (!wasEmpty && mListeners.isEmpty()) {
                        mHandler.sendEmptyMessage(MSG_STOP_LISTENING);
                    }
                }
            }

            @Override
            public TwilightState getLastTwilightState() {
                synchronized (mListeners) {
                    return mLastTwilightState;
                }
            }
        });
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            final Context c = getContext();
            mAlarmManager = (AlarmManager) c.getSystemService(Context.ALARM_SERVICE);
            mLocationManager = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);

            mBootCompleted = true;
            if (mHasListeners) {
                startListening();
            }
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_START_LISTENING:
                if (!mHasListeners) {
                    mHasListeners = true;
                    if (mBootCompleted) {
                        startListening();
                    }
                }
                return true;
            case MSG_STOP_LISTENING:
                if (mHasListeners) {
                    mHasListeners = false;
                    if (mBootCompleted) {
                        stopListening();
                    }
                }
                return true;
        }
        return false;
    }

    private void startListening() {
        if (DEBUG) Slog.d(TAG, "startListening");

        // Start listening for location updates (default: low power, max 1h, min 10m).
        mLocationManager.requestLocationUpdates(
                null /* default */, this, Looper.getMainLooper());

        // Request the device's location immediately if a previous location isn't available.
        if (mLocationManager.getLastLocation() == null) {
            if (mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocationManager.requestSingleUpdate(
                        LocationManager.NETWORK_PROVIDER, this, Looper.getMainLooper());
            } else if (mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocationManager.requestSingleUpdate(
                        LocationManager.GPS_PROVIDER, this, Looper.getMainLooper());
            }
        }

        // Update whenever the system clock is changed.
        if (mTimeChangedReceiver == null) {
            mTimeChangedReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (DEBUG) Slog.d(TAG, "onReceive: " + intent);
                    updateTwilightState();
                }
            };

            final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_TIME_CHANGED);
            intentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            getContext().registerReceiver(mTimeChangedReceiver, intentFilter);
        }

        // Force an update now that we have listeners registered.
        updateTwilightState();
    }

    private void stopListening() {
        if (DEBUG) Slog.d(TAG, "stopListening");

        if (mTimeChangedReceiver != null) {
            getContext().unregisterReceiver(mTimeChangedReceiver);
            mTimeChangedReceiver = null;
        }

        if (mLastTwilightState != null) {
            mAlarmManager.cancel(this);
        }

        mLocationManager.removeUpdates(this);
        mLastLocation = null;
    }

    private void updateTwilightState() {
        // Calculate the twilight state based on the current time and location.
        final long currentTimeMillis = System.currentTimeMillis();
        final Location location = mLastLocation != null ? mLastLocation
                : mLocationManager.getLastLocation();
        final TwilightState state = calculateTwilightState(location, currentTimeMillis);
        if (DEBUG) {
            Slog.d(TAG, "updateTwilightState: " + state);
        }

        // Notify listeners if the state has changed.
        synchronized (mListeners) {
            if (!Objects.equals(mLastTwilightState, state)) {
                mLastTwilightState = state;

                for (int i = mListeners.size() - 1; i >= 0; --i) {
                    final TwilightListener listener = mListeners.keyAt(i);
                    final Handler handler = mListeners.valueAt(i);
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onTwilightStateChanged(state);
                        }
                    });
                }
            }
        }

        // Schedule an alarm to update the state at the next sunrise or sunset.
        if (state != null) {
            final long triggerAtMillis = state.isNight()
                    ? state.sunriseTimeMillis() : state.sunsetTimeMillis();
            mAlarmManager.setExact(AlarmManager.RTC, triggerAtMillis, TAG, this, mHandler);
        }
    }

    @Override
    public void onAlarm() {
        if (DEBUG) Slog.d(TAG, "onAlarm");
        updateTwilightState();
    }

    @Override
    public void onLocationChanged(Location location) {
        if (DEBUG) Slog.d(TAG, "onLocationChanged: " + location);
        mLastLocation = location;
        updateTwilightState();
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
    }

    @Override
    public void onProviderDisabled(String provider) {
    }

    /**
     * Calculates the twilight state for a specific location and time.
     *
     * @param location the location to use
     * @param timeMillis the reference time to use
     * @return the calculated {@link TwilightState}, or {@code null} if location is {@code null}
     */
    private static TwilightState calculateTwilightState(Location location, long timeMillis) {
        if (location == null) {
            return null;
        }

        final CalendarAstronomer ca = new CalendarAstronomer(
                location.getLongitude(), location.getLatitude());

        final Calendar noon = Calendar.getInstance();
        noon.setTimeInMillis(timeMillis);
        noon.set(Calendar.HOUR_OF_DAY, 12);
        noon.set(Calendar.MINUTE, 0);
        noon.set(Calendar.SECOND, 0);
        noon.set(Calendar.MILLISECOND, 0);
        ca.setTime(noon.getTimeInMillis());

        long sunriseTimeMillis = ca.getSunRiseSet(true /* rise */);
        long sunsetTimeMillis = ca.getSunRiseSet(false /* rise */);

        if (sunsetTimeMillis < timeMillis) {
            noon.add(Calendar.DATE, 1);
            ca.setTime(noon.getTimeInMillis());
            sunriseTimeMillis = ca.getSunRiseSet(true /* rise */);
        } else if (sunriseTimeMillis > timeMillis) {
            noon.add(Calendar.DATE, -1);
            ca.setTime(noon.getTimeInMillis());
            sunsetTimeMillis = ca.getSunRiseSet(false /* rise */);
        }

        return new TwilightState(sunriseTimeMillis, sunsetTimeMillis);
    }
}
