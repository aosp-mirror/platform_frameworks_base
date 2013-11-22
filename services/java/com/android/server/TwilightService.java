/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Slog;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;

import libcore.util.Objects;

/**
 * Figures out whether it's twilight time based on the user's location.
 *
 * Used by the UI mode manager and other components to adjust night mode
 * effects based on sunrise and sunset.
 */
public final class TwilightService {
    private static final String TAG = "TwilightService";

    private static final boolean DEBUG = false;

    private static final String ACTION_UPDATE_TWILIGHT_STATE =
            "com.android.server.action.UPDATE_TWILIGHT_STATE";

    private final Context mContext;
    private final AlarmManager mAlarmManager;
    private final LocationManager mLocationManager;
    private final LocationHandler mLocationHandler;

    private final Object mLock = new Object();

    private final ArrayList<TwilightListenerRecord> mListeners =
            new ArrayList<TwilightListenerRecord>();

    private boolean mSystemReady;

    private TwilightState mTwilightState;

    public TwilightService(Context context) {
        mContext = context;

        mAlarmManager = (AlarmManager)mContext.getSystemService(Context.ALARM_SERVICE);
        mLocationManager = (LocationManager)mContext.getSystemService(Context.LOCATION_SERVICE);
        mLocationHandler = new LocationHandler();
    }

    void systemReady() {
        synchronized (mLock) {
            mSystemReady = true;

            IntentFilter filter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(ACTION_UPDATE_TWILIGHT_STATE);
            mContext.registerReceiver(mUpdateLocationReceiver, filter);

            if (!mListeners.isEmpty()) {
                mLocationHandler.enableLocationUpdates();
            }
        }
    }

    /**
     * Gets the current twilight state.
     *
     * @return The current twilight state, or null if no information is available.
     */
    public TwilightState getCurrentState() {
        synchronized (mLock) {
            return mTwilightState;
        }
    }

    /**
     * Listens for twilight time.
     *
     * @param listener The listener.
     * @param handler The handler on which to post calls into the listener.
     */
    public void registerListener(TwilightListener listener, Handler handler) {
        synchronized (mLock) {
            mListeners.add(new TwilightListenerRecord(listener, handler));

            if (mSystemReady && mListeners.size() == 1) {
                mLocationHandler.enableLocationUpdates();
            }
        }
    }

    private void setTwilightState(TwilightState state) {
        synchronized (mLock) {
            if (!Objects.equal(mTwilightState, state)) {
                if (DEBUG) {
                    Slog.d(TAG, "Twilight state changed: " + state);
                }

                mTwilightState = state;
                int count = mListeners.size();
                for (int i = 0; i < count; i++) {
                    mListeners.get(i).post();
                }
            }
        }
    }

    // The user has moved if the accuracy circles of the two locations don't overlap.
    private static boolean hasMoved(Location from, Location to) {
        if (to == null) {
            return false;
        }

        if (from == null) {
            return true;
        }

        // if new location is older than the current one, the device hasn't moved.
        if (to.getElapsedRealtimeNanos() < from.getElapsedRealtimeNanos()) {
            return false;
        }

        // Get the distance between the two points.
        float distance = from.distanceTo(to);

        // Get the total accuracy radius for both locations.
        float totalAccuracy = from.getAccuracy() + to.getAccuracy();

        // If the distance is greater than the combined accuracy of the two
        // points then they can't overlap and hence the user has moved.
        return distance >= totalAccuracy;
    }

    /**
     * Describes whether it is day or night.
     * This object is immutable.
     */
    public static final class TwilightState {
        private final boolean mIsNight;
        private final long mYesterdaySunset;
        private final long mTodaySunrise;
        private final long mTodaySunset;
        private final long mTomorrowSunrise;

        TwilightState(boolean isNight,
                long yesterdaySunset,
                long todaySunrise, long todaySunset,
                long tomorrowSunrise) {
            mIsNight = isNight;
            mYesterdaySunset = yesterdaySunset;
            mTodaySunrise = todaySunrise;
            mTodaySunset = todaySunset;
            mTomorrowSunrise = tomorrowSunrise;
        }

        /**
         * Returns true if it is currently night time.
         */
        public boolean isNight() {
            return mIsNight;
        }

        /**
         * Returns the time of yesterday's sunset in the System.currentTimeMillis() timebase,
         * or -1 if the sun never sets.
         */
        public long getYesterdaySunset() {
            return mYesterdaySunset;
        }

        /**
         * Returns the time of today's sunrise in the System.currentTimeMillis() timebase,
         * or -1 if the sun never rises.
         */
        public long getTodaySunrise() {
            return mTodaySunrise;
        }

        /**
         * Returns the time of today's sunset in the System.currentTimeMillis() timebase,
         * or -1 if the sun never sets.
         */
        public long getTodaySunset() {
            return mTodaySunset;
        }

        /**
         * Returns the time of tomorrow's sunrise in the System.currentTimeMillis() timebase,
         * or -1 if the sun never rises.
         */
        public long getTomorrowSunrise() {
            return mTomorrowSunrise;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof TwilightState && equals((TwilightState)o);
        }

        public boolean equals(TwilightState other) {
            return other != null
                    && mIsNight == other.mIsNight
                    && mYesterdaySunset == other.mYesterdaySunset
                    && mTodaySunrise == other.mTodaySunrise
                    && mTodaySunset == other.mTodaySunset
                    && mTomorrowSunrise == other.mTomorrowSunrise;
        }

        @Override
        public int hashCode() {
            return 0; // don't care
        }

        @Override
        public String toString() {
            DateFormat f = DateFormat.getDateTimeInstance();
            return "{TwilightState: isNight=" + mIsNight
                    + ", mYesterdaySunset=" + f.format(new Date(mYesterdaySunset))
                    + ", mTodaySunrise=" + f.format(new Date(mTodaySunrise))
                    + ", mTodaySunset=" + f.format(new Date(mTodaySunset))
                    + ", mTomorrowSunrise=" + f.format(new Date(mTomorrowSunrise))
                    + "}";
        }
    }

    /**
     * Listener for changes in twilight state.
     */
    public interface TwilightListener {
        public void onTwilightStateChanged();
    }

    private static final class TwilightListenerRecord implements Runnable {
        private final TwilightListener mListener;
        private final Handler mHandler;

        public TwilightListenerRecord(TwilightListener listener, Handler handler) {
            mListener = listener;
            mHandler = handler;
        }

        public void post() {
            mHandler.post(this);
        }

        @Override
        public void run() {
            mListener.onTwilightStateChanged();
        }
    }

    private final class LocationHandler extends Handler {
        private static final int MSG_ENABLE_LOCATION_UPDATES = 1;
        private static final int MSG_GET_NEW_LOCATION_UPDATE = 2;
        private static final int MSG_PROCESS_NEW_LOCATION = 3;
        private static final int MSG_DO_TWILIGHT_UPDATE = 4;

        private static final long LOCATION_UPDATE_MS = 24 * DateUtils.HOUR_IN_MILLIS;
        private static final long MIN_LOCATION_UPDATE_MS = 30 * DateUtils.MINUTE_IN_MILLIS;
        private static final float LOCATION_UPDATE_DISTANCE_METER = 1000 * 20;
        private static final long LOCATION_UPDATE_ENABLE_INTERVAL_MIN = 5000;
        private static final long LOCATION_UPDATE_ENABLE_INTERVAL_MAX =
                15 * DateUtils.MINUTE_IN_MILLIS;
        private static final double FACTOR_GMT_OFFSET_LONGITUDE =
                1000.0 * 360.0 / DateUtils.DAY_IN_MILLIS;

        private boolean mPassiveListenerEnabled;
        private boolean mNetworkListenerEnabled;
        private boolean mDidFirstInit;
        private long mLastNetworkRegisterTime = -MIN_LOCATION_UPDATE_MS;
        private long mLastUpdateInterval;
        private Location mLocation;
        private final TwilightCalculator mTwilightCalculator = new TwilightCalculator();

        public void processNewLocation(Location location) {
            Message msg = obtainMessage(MSG_PROCESS_NEW_LOCATION, location);
            sendMessage(msg);
        }

        public void enableLocationUpdates() {
            sendEmptyMessage(MSG_ENABLE_LOCATION_UPDATES);
        }

        public void requestLocationUpdate() {
            sendEmptyMessage(MSG_GET_NEW_LOCATION_UPDATE);
        }

        public void requestTwilightUpdate() {
            sendEmptyMessage(MSG_DO_TWILIGHT_UPDATE);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PROCESS_NEW_LOCATION: {
                    final Location location = (Location)msg.obj;
                    final boolean hasMoved = hasMoved(mLocation, location);
                    final boolean hasBetterAccuracy = mLocation == null
                            || location.getAccuracy() < mLocation.getAccuracy();
                    if (DEBUG) {
                        Slog.d(TAG, "Processing new location: " + location
                               + ", hasMoved=" + hasMoved
                               + ", hasBetterAccuracy=" + hasBetterAccuracy);
                    }
                    if (hasMoved || hasBetterAccuracy) {
                        setLocation(location);
                    }
                    break;
                }

                case MSG_GET_NEW_LOCATION_UPDATE:
                    if (!mNetworkListenerEnabled) {
                        // Don't do anything -- we are still trying to get a
                        // location.
                        return;
                    }
                    if ((mLastNetworkRegisterTime + MIN_LOCATION_UPDATE_MS) >=
                            SystemClock.elapsedRealtime()) {
                        // Don't do anything -- it hasn't been long enough
                        // since we last requested an update.
                        return;
                    }

                    // Unregister the current location monitor, so we can
                    // register a new one for it to get an immediate update.
                    mNetworkListenerEnabled = false;
                    mLocationManager.removeUpdates(mEmptyLocationListener);

                    // Fall through to re-register listener.
                case MSG_ENABLE_LOCATION_UPDATES:
                    // enable network provider to receive at least location updates for a given
                    // distance.
                    boolean networkLocationEnabled;
                    try {
                        networkLocationEnabled =
                            mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
                    } catch (Exception e) {
                        // we may get IllegalArgumentException if network location provider
                        // does not exist or is not yet installed.
                        networkLocationEnabled = false;
                    }
                    if (!mNetworkListenerEnabled && networkLocationEnabled) {
                        mNetworkListenerEnabled = true;
                        mLastNetworkRegisterTime = SystemClock.elapsedRealtime();
                        mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER,
                                LOCATION_UPDATE_MS, 0, mEmptyLocationListener);

                        if (!mDidFirstInit) {
                            mDidFirstInit = true;
                            if (mLocation == null) {
                                retrieveLocation();
                            }
                        }
                    }

                    // enable passive provider to receive updates from location fixes (gps
                    // and network).
                    boolean passiveLocationEnabled;
                    try {
                        passiveLocationEnabled =
                            mLocationManager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER);
                    } catch (Exception e) {
                        // we may get IllegalArgumentException if passive location provider
                        // does not exist or is not yet installed.
                        passiveLocationEnabled = false;
                    }

                    if (!mPassiveListenerEnabled && passiveLocationEnabled) {
                        mPassiveListenerEnabled = true;
                        mLocationManager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER,
                                0, LOCATION_UPDATE_DISTANCE_METER , mLocationListener);
                    }

                    if (!(mNetworkListenerEnabled && mPassiveListenerEnabled)) {
                        mLastUpdateInterval *= 1.5;
                        if (mLastUpdateInterval == 0) {
                            mLastUpdateInterval = LOCATION_UPDATE_ENABLE_INTERVAL_MIN;
                        } else if (mLastUpdateInterval > LOCATION_UPDATE_ENABLE_INTERVAL_MAX) {
                            mLastUpdateInterval = LOCATION_UPDATE_ENABLE_INTERVAL_MAX;
                        }
                        sendEmptyMessageDelayed(MSG_ENABLE_LOCATION_UPDATES, mLastUpdateInterval);
                    }
                    break;

                case MSG_DO_TWILIGHT_UPDATE:
                    updateTwilightState();
                    break;
            }
        }

        private void retrieveLocation() {
            Location location = null;
            final Iterator<String> providers =
                    mLocationManager.getProviders(new Criteria(), true).iterator();
            while (providers.hasNext()) {
                final Location lastKnownLocation =
                        mLocationManager.getLastKnownLocation(providers.next());
                // pick the most recent location
                if (location == null || (lastKnownLocation != null &&
                        location.getElapsedRealtimeNanos() <
                        lastKnownLocation.getElapsedRealtimeNanos())) {
                    location = lastKnownLocation;
                }
            }

            // In the case there is no location available (e.g. GPS fix or network location
            // is not available yet), the longitude of the location is estimated using the timezone,
            // latitude and accuracy are set to get a good average.
            if (location == null) {
                Time currentTime = new Time();
                currentTime.set(System.currentTimeMillis());
                double lngOffset = FACTOR_GMT_OFFSET_LONGITUDE *
                        (currentTime.gmtoff - (currentTime.isDst > 0 ? 3600 : 0));
                location = new Location("fake");
                location.setLongitude(lngOffset);
                location.setLatitude(0);
                location.setAccuracy(417000.0f);
                location.setTime(System.currentTimeMillis());
                location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());

                if (DEBUG) {
                    Slog.d(TAG, "Estimated location from timezone: " + location);
                }
            }

            setLocation(location);
        }

        private void setLocation(Location location) {
            mLocation = location;
            updateTwilightState();
        }

        private void updateTwilightState() {
            if (mLocation == null) {
                setTwilightState(null);
                return;
            }

            final long now = System.currentTimeMillis();

            // calculate yesterday's twilight
            mTwilightCalculator.calculateTwilight(now - DateUtils.DAY_IN_MILLIS,
                    mLocation.getLatitude(), mLocation.getLongitude());
            final long yesterdaySunset = mTwilightCalculator.mSunset;

            // calculate today's twilight
            mTwilightCalculator.calculateTwilight(now,
                    mLocation.getLatitude(), mLocation.getLongitude());
            final boolean isNight = (mTwilightCalculator.mState == TwilightCalculator.NIGHT);
            final long todaySunrise = mTwilightCalculator.mSunrise;
            final long todaySunset = mTwilightCalculator.mSunset;

            // calculate tomorrow's twilight
            mTwilightCalculator.calculateTwilight(now + DateUtils.DAY_IN_MILLIS,
                    mLocation.getLatitude(), mLocation.getLongitude());
            final long tomorrowSunrise = mTwilightCalculator.mSunrise;

            // set twilight state
            TwilightState state = new TwilightState(isNight, yesterdaySunset,
                    todaySunrise, todaySunset, tomorrowSunrise);
            if (DEBUG) {
                Slog.d(TAG, "Updating twilight state: " + state);
            }
            setTwilightState(state);

            // schedule next update
            long nextUpdate = 0;
            if (todaySunrise == -1 || todaySunset == -1) {
                // In the case the day or night never ends the update is scheduled 12 hours later.
                nextUpdate = now + 12 * DateUtils.HOUR_IN_MILLIS;
            } else {
                // add some extra time to be on the safe side.
                nextUpdate += DateUtils.MINUTE_IN_MILLIS;

                if (now > todaySunset) {
                    nextUpdate += tomorrowSunrise;
                } else if (now > todaySunrise) {
                    nextUpdate += todaySunset;
                } else {
                    nextUpdate += todaySunrise;
                }
            }

            if (DEBUG) {
                Slog.d(TAG, "Next update in " + (nextUpdate - now) + " ms");
            }

            Intent updateIntent = new Intent(ACTION_UPDATE_TWILIGHT_STATE);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, updateIntent, 0);
            mAlarmManager.cancel(pendingIntent);
            mAlarmManager.setExact(AlarmManager.RTC, nextUpdate, pendingIntent);
        }
    };

    private final BroadcastReceiver mUpdateLocationReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())
                    && !intent.getBooleanExtra("state", false)) {
                // Airplane mode is now off!
                mLocationHandler.requestLocationUpdate();
                return;
            }

            // Time zone has changed or alarm expired.
            mLocationHandler.requestTwilightUpdate();
        }
    };

    // A LocationListener to initialize the network location provider. The location updates
    // are handled through the passive location provider.
    private final LocationListener mEmptyLocationListener =  new LocationListener() {
        public void onLocationChanged(Location location) {
        }

        public void onProviderDisabled(String provider) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };

    private final LocationListener mLocationListener = new LocationListener() {
        public void onLocationChanged(Location location) {
            mLocationHandler.processNewLocation(location);
        }

        public void onProviderDisabled(String provider) {
        }

        public void onProviderEnabled(String provider) {
        }

        public void onStatusChanged(String provider, int status, Bundle extras) {
        }
    };
}
