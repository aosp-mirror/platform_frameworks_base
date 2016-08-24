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

package com.android.server.twilight;

import com.android.server.SystemService;
import com.android.server.TwilightCalculator;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.location.Criteria;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.Slog;

import java.util.ArrayList;
import java.util.Iterator;

import libcore.util.Objects;

/**
 * Figures out whether it's twilight time based on the user's location.
 *
 * Used by the UI mode manager and other components to adjust night mode
 * effects based on sunrise and sunset.
 */
public final class TwilightService extends SystemService {
    static final String TAG = "TwilightService";
    static final boolean DEBUG = false;
    static final String ACTION_UPDATE_TWILIGHT_STATE =
            "com.android.server.action.UPDATE_TWILIGHT_STATE";

    // The amount of time after or before sunrise over which to start adjusting
    // twilight affected things.  We want the change to happen gradually so that
    // it is below the threshold of perceptibility and so that the adjustment has
    // maximum effect well after dusk.
    private static final long TWILIGHT_ADJUSTMENT_TIME = DateUtils.HOUR_IN_MILLIS * 2;

    // Broadcast when twilight changes.
    public static final String ACTION_TWILIGHT_CHANGED = "android.intent.action.TWILIGHT_CHANGED";

    public static final String EXTRA_IS_NIGHT = "isNight";
    public static final String EXTRA_AMOUNT = "amount";

    // Amount of time the TwilightService will stay locked in an override state before switching
    // back to auto.
    private static final long RESET_TIME = DateUtils.HOUR_IN_MILLIS * 2;
    private static final String EXTRA_RESET_USER = "user";

    private static final String ACTION_RESET_TWILIGHT_AUTO =
            "com.android.server.action.RESET_TWILIGHT_AUTO";

    final Object mLock = new Object();

    AlarmManager mAlarmManager;
    LocationManager mLocationManager;
    LocationHandler mLocationHandler;

    final ArrayList<TwilightListenerRecord> mListeners =
            new ArrayList<TwilightListenerRecord>();

    TwilightState mTwilightState;

    private int mCurrentUser;
    private boolean mLocked;
    private boolean mBootCompleted;

    public TwilightService(Context context) {
        super(context);
    }

    @Override
    public void onStart() {
        mAlarmManager = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
        mLocationManager = (LocationManager) getContext().getSystemService(
                Context.LOCATION_SERVICE);
        mLocationHandler = new LocationHandler();
        mCurrentUser = ActivityManager.getCurrentUser();

        IntentFilter filter = new IntentFilter(Intent.ACTION_AIRPLANE_MODE_CHANGED);
        filter.addAction(Intent.ACTION_TIME_CHANGED);
        filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(ACTION_UPDATE_TWILIGHT_STATE);
        getContext().registerReceiver(mReceiver, filter);

        publishLocalService(TwilightManager.class, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            getContext().getContentResolver().registerContentObserver(
                    Secure.getUriFor(Secure.TWILIGHT_MODE), false, mContentObserver, mCurrentUser);
            mContentObserver.onChange(true);
            mBootCompleted = true;
            sendBroadcast();
        }
    }

    private void reregisterSettingObserver() {
        final ContentResolver contentResolver = getContext().getContentResolver();
        contentResolver.unregisterContentObserver(mContentObserver);
        contentResolver.registerContentObserver(Secure.getUriFor(Secure.TWILIGHT_MODE), false,
                mContentObserver, mCurrentUser);
        mContentObserver.onChange(true);
    }

    private void setLockedState(TwilightState state) {
        synchronized (mLock) {
            // Make sure we aren't locked so we can set the state.
            mLocked = false;
            setTwilightState(state);
            // Make sure we leave the state locked, so it cant be changed.
            mLocked = true;
            // TODO: Don't bother updating state when locked.
        }
    }

    private void setTwilightState(TwilightState state) {
        synchronized (mLock) {
            if (mLocked) {
                // State has been locked by secure setting, shouldn't be changed.
                return;
            }
            if (!Objects.equal(mTwilightState, state)) {
                if (DEBUG) {
                    Slog.d(TAG, "Twilight state changed: " + state);
                }

                mTwilightState = state;

                final int listenerLen = mListeners.size();
                for (int i = 0; i < listenerLen; i++) {
                    mListeners.get(i).postUpdate();
                }
            }
        }
        sendBroadcast();
    }

    private void sendBroadcast() {
        synchronized (mLock) {
            if (mTwilightState == null) {
                return;
            }
            if (mBootCompleted) {
                Intent intent = new Intent(ACTION_TWILIGHT_CHANGED);
                intent.putExtra(EXTRA_IS_NIGHT, mTwilightState.isNight());
                intent.putExtra(EXTRA_AMOUNT, mTwilightState.getAmount());
                intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
                getContext().sendStickyBroadcastAsUser(intent, UserHandle.ALL);
            }
        }
    }

    private void scheduleReset() {
        long resetTime = System.currentTimeMillis() + RESET_TIME;
        Intent resetIntent = new Intent(ACTION_RESET_TWILIGHT_AUTO);
        resetIntent.putExtra(EXTRA_RESET_USER, mCurrentUser);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                getContext(), 0, resetIntent, 0);
        mAlarmManager.cancel(pendingIntent);
        mAlarmManager.setExact(AlarmManager.RTC, resetTime, pendingIntent);
    }

    private static class TwilightListenerRecord implements Runnable {
        private final TwilightListener mListener;
        private final Handler mHandler;

        public TwilightListenerRecord(TwilightListener listener, Handler handler) {
            mListener = listener;
            mHandler = handler;
        }

        public void postUpdate() {
            mHandler.post(this);
        }

        @Override
        public void run() {
            mListener.onTwilightStateChanged();
        }

    }

    private final TwilightManager mService = new TwilightManager() {
        /**
         * Gets the current twilight state.
         *
         * @return The current twilight state, or null if no information is available.
         */
        @Override
        public TwilightState getCurrentState() {
            synchronized (mLock) {
                return mTwilightState;
            }
        }

        /**
         * Listens for twilight time.
         *
         * @param listener The listener.
         */
        @Override
        public void registerListener(TwilightListener listener, Handler handler) {
            synchronized (mLock) {
                mListeners.add(new TwilightListenerRecord(listener, handler));

                if (mListeners.size() == 1) {
                    mLocationHandler.enableLocationUpdates();
                }
            }
        }

        @Override
        public void unregisterListener(TwilightListener listener) {
            synchronized (mLock) {
                for (int i = 0; i < mListeners.size(); i++) {
                    if (mListeners.get(i).mListener == listener) {
                        mListeners.remove(i);
                    }
                }

                if (mListeners.size() == 0) {
                    mLocationHandler.disableLocationUpdates();
                }
            }
        }
    };

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

    private final class LocationHandler extends Handler {
        private static final int MSG_ENABLE_LOCATION_UPDATES = 1;
        private static final int MSG_GET_NEW_LOCATION_UPDATE = 2;
        private static final int MSG_PROCESS_NEW_LOCATION = 3;
        private static final int MSG_DO_TWILIGHT_UPDATE = 4;
        private static final int MSG_DISABLE_LOCATION_UPDATES = 5;

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

        public void disableLocationUpdates() {
            sendEmptyMessage(MSG_DISABLE_LOCATION_UPDATES);
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

                case MSG_DISABLE_LOCATION_UPDATES:
                    mLocationManager.removeUpdates(mLocationListener);
                    removeMessages(MSG_ENABLE_LOCATION_UPDATES);
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

            float amount = 0;
            if (isNight) {
                if (todaySunrise == -1 || todaySunset == -1) {
                    amount = 1;
                } else if (now > todaySunset) {
                    amount = Math.min(1, (now - todaySunset) / (float) TWILIGHT_ADJUSTMENT_TIME);
                } else {
                    amount = Math.max(0, 1
                            - (todaySunrise - now) / (float) TWILIGHT_ADJUSTMENT_TIME);
                }
            }
            // set twilight state
            TwilightState state = new TwilightState(isNight, amount);
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

                if (amount == 1 || amount == 0) {
                    if (now > todaySunset) {
                        nextUpdate += tomorrowSunrise;
                    } else if (now > todaySunrise) {
                        nextUpdate += todaySunset;
                    } else {
                        nextUpdate += todaySunrise;
                    }
                } else {
                    // This is the update rate while transitioning.
                    // Leave at 10 min for now (one from above).
                    nextUpdate += 9 * DateUtils.MINUTE_IN_MILLIS;
                }
            }

            if (DEBUG) {
                Slog.d(TAG, "Next update in " + (nextUpdate - now) + " ms");
            }

            Intent updateIntent = new Intent(ACTION_UPDATE_TWILIGHT_STATE);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(
                    getContext(), 0, updateIntent, 0);
            mAlarmManager.cancel(pendingIntent);
            mAlarmManager.setExact(AlarmManager.RTC, nextUpdate, pendingIntent);
        }
    }

    private final ContentObserver mContentObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            int value = Secure.getIntForUser(getContext().getContentResolver(),
                    Secure.TWILIGHT_MODE, Secure.TWILIGHT_MODE_LOCKED_OFF, mCurrentUser);
            if (value == Secure.TWILIGHT_MODE_LOCKED_OFF) {
                setLockedState(new TwilightState(false, 0));
            } else if (value == Secure.TWILIGHT_MODE_LOCKED_ON) {
                setLockedState(new TwilightState(true, 1));
            } else if (value == Secure.TWILIGHT_MODE_AUTO_OVERRIDE_OFF) {
                setLockedState(new TwilightState(false, 0));
                scheduleReset();
            } else if (value == Secure.TWILIGHT_MODE_AUTO_OVERRIDE_ON) {
                setLockedState(new TwilightState(true, 1));
                scheduleReset();
            } else {
                mLocked = false;
                mLocationHandler.requestTwilightUpdate();
            }
        }
    };

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) {
                mCurrentUser = ActivityManager.getCurrentUser();
                reregisterSettingObserver();
                return;
            }
            if (Intent.ACTION_AIRPLANE_MODE_CHANGED.equals(intent.getAction())
                    && !intent.getBooleanExtra("state", false)) {
                // Airplane mode is now off!
                mLocationHandler.requestLocationUpdate();
                return;
            }

            if (ACTION_RESET_TWILIGHT_AUTO.equals(intent.getAction())) {
                int user = intent.getIntExtra(EXTRA_RESET_USER, 0);
                Settings.Secure.putIntForUser(getContext().getContentResolver(),
                        Secure.TWILIGHT_MODE, Secure.TWILIGHT_MODE_AUTO, user);
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
