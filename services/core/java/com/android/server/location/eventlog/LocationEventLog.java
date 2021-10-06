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

package com.android.server.location.eventlog;

import static android.os.PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_FOREGROUND_ONLY;
import static android.os.PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_NO_CHANGE;
import static android.os.PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF;
import static android.util.TimeUtils.formatDuration;

import static com.android.server.location.LocationManagerService.D;

import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

import android.annotation.Nullable;
import android.location.LocationRequest;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.PowerManager.LocationPowerSaveMode;
import android.os.SystemClock;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

/** In memory event log for location events. */
public class LocationEventLog extends LocalEventLog {

    public static final LocationEventLog EVENT_LOG = new LocationEventLog();

    private static int getLogSize() {
        if (D) {
            return 600;
        } else {
            return 200;
        }
    }

    private static final int EVENT_USER_SWITCHED = 1;
    private static final int EVENT_LOCATION_ENABLED = 2;
    private static final int EVENT_ADAS_LOCATION_ENABLED = 3;
    private static final int EVENT_PROVIDER_ENABLED = 4;
    private static final int EVENT_PROVIDER_MOCKED = 5;
    private static final int EVENT_PROVIDER_CLIENT_REGISTER = 6;
    private static final int EVENT_PROVIDER_CLIENT_UNREGISTER = 7;
    private static final int EVENT_PROVIDER_CLIENT_FOREGROUND = 8;
    private static final int EVENT_PROVIDER_CLIENT_BACKGROUND = 9;
    private static final int EVENT_PROVIDER_CLIENT_PERMITTED = 10;
    private static final int EVENT_PROVIDER_CLIENT_UNPERMITTED = 11;
    private static final int EVENT_PROVIDER_UPDATE_REQUEST = 12;
    private static final int EVENT_PROVIDER_RECEIVE_LOCATION = 13;
    private static final int EVENT_PROVIDER_DELIVER_LOCATION = 14;
    private static final int EVENT_PROVIDER_STATIONARY_THROTTLED = 15;
    private static final int EVENT_LOCATION_POWER_SAVE_MODE_CHANGE = 16;

    @GuardedBy("mAggregateStats")
    private final ArrayMap<String, ArrayMap<CallerIdentity, AggregateStats>> mAggregateStats;

    public LocationEventLog() {
        super(getLogSize());
        mAggregateStats = new ArrayMap<>(4);
    }

    /** Copies out all aggregated stats. */
    public ArrayMap<String, ArrayMap<CallerIdentity, AggregateStats>> copyAggregateStats() {
        synchronized (mAggregateStats) {
            ArrayMap<String, ArrayMap<CallerIdentity, AggregateStats>> copy = new ArrayMap<>(
                    mAggregateStats);
            for (int i = 0; i < copy.size(); i++) {
                copy.setValueAt(i, new ArrayMap<>(copy.valueAt(i)));
            }
            return copy;
        }
    }

    private AggregateStats getAggregateStats(String provider, CallerIdentity identity) {
        synchronized (mAggregateStats) {
            ArrayMap<CallerIdentity, AggregateStats> packageMap = mAggregateStats.get(provider);
            if (packageMap == null) {
                packageMap = new ArrayMap<>(2);
                mAggregateStats.put(provider, packageMap);
            }
            CallerIdentity aggregate = CallerIdentity.forAggregation(identity);
            AggregateStats stats = packageMap.get(aggregate);
            if (stats == null) {
                stats = new AggregateStats();
                packageMap.put(aggregate, stats);
            }
            return stats;
        }
    }

    /** Logs a user switched event. */
    public void logUserSwitched(int userIdFrom, int userIdTo) {
        addLogEvent(EVENT_USER_SWITCHED, userIdFrom, userIdTo);
    }

    /** Logs a location enabled/disabled event. */
    public void logLocationEnabled(int userId, boolean enabled) {
        addLogEvent(EVENT_LOCATION_ENABLED, userId, enabled);
    }

    /** Logs a location enabled/disabled event. */
    public void logAdasLocationEnabled(int userId, boolean enabled) {
        addLogEvent(EVENT_ADAS_LOCATION_ENABLED, userId, enabled);
    }

    /** Logs a location provider enabled/disabled event. */
    public void logProviderEnabled(String provider, int userId, boolean enabled) {
        addLogEvent(EVENT_PROVIDER_ENABLED, provider, userId, enabled);
    }

    /** Logs a location provider being replaced/unreplaced by a mock provider. */
    public void logProviderMocked(String provider, boolean mocked) {
        addLogEvent(EVENT_PROVIDER_MOCKED, provider, mocked);
    }

    /** Logs a new client registration for a location provider. */
    public void logProviderClientRegistered(String provider, CallerIdentity identity,
            LocationRequest request) {
        addLogEvent(EVENT_PROVIDER_CLIENT_REGISTER, provider, identity, request);
        getAggregateStats(provider, identity).markRequestAdded(request.getIntervalMillis());
    }

    /** Logs a client unregistration for a location provider. */
    public void logProviderClientUnregistered(String provider, CallerIdentity identity) {
        addLogEvent(EVENT_PROVIDER_CLIENT_UNREGISTER, provider, identity);
        getAggregateStats(provider, identity).markRequestRemoved();
    }

    /** Logs a client for a location provider entering the active state. */
    public void logProviderClientActive(String provider, CallerIdentity identity) {
        getAggregateStats(provider, identity).markRequestActive();
    }

    /** Logs a client for a location provider leaving the active state. */
    public void logProviderClientInactive(String provider, CallerIdentity identity) {
        getAggregateStats(provider, identity).markRequestInactive();
    }

    /** Logs a client for a location provider entering the foreground state. */
    public void logProviderClientForeground(String provider, CallerIdentity identity) {
        if (D) {
            addLogEvent(EVENT_PROVIDER_CLIENT_FOREGROUND, provider, identity);
        }
        getAggregateStats(provider, identity).markRequestForeground();
    }

    /** Logs a client for a location provider leaving the foreground state. */
    public void logProviderClientBackground(String provider, CallerIdentity identity) {
        if (D) {
            addLogEvent(EVENT_PROVIDER_CLIENT_BACKGROUND, provider, identity);
        }
        getAggregateStats(provider, identity).markRequestBackground();
    }

    /** Logs a client for a location provider entering the permitted state. */
    public void logProviderClientPermitted(String provider, CallerIdentity identity) {
        if (D) {
            addLogEvent(EVENT_PROVIDER_CLIENT_PERMITTED, provider, identity);
        }
    }

    /** Logs a client for a location provider leaving the permitted state. */
    public void logProviderClientUnpermitted(String provider, CallerIdentity identity) {
        if (D) {
            addLogEvent(EVENT_PROVIDER_CLIENT_UNPERMITTED, provider, identity);
        }
    }

    /** Logs a change to the provider request for a location provider. */
    public void logProviderUpdateRequest(String provider, ProviderRequest request) {
        addLogEvent(EVENT_PROVIDER_UPDATE_REQUEST, provider, request);
    }

    /** Logs a new incoming location for a location provider. */
    public void logProviderReceivedLocations(String provider, int numLocations) {
        addLogEvent(EVENT_PROVIDER_RECEIVE_LOCATION, provider, numLocations);
    }

    /** Logs a location deliver for a client of a location provider. */
    public void logProviderDeliveredLocations(String provider, int numLocations,
            CallerIdentity identity) {
        if (D) {
            addLogEvent(EVENT_PROVIDER_DELIVER_LOCATION, provider, numLocations, identity);
        }
        getAggregateStats(provider, identity).markLocationDelivered();
    }

    /** Logs that a provider has entered or exited stationary throttling. */
    public void logProviderStationaryThrottled(String provider, boolean throttled,
            ProviderRequest request) {
        addLogEvent(EVENT_PROVIDER_STATIONARY_THROTTLED, provider, throttled, request);
    }

    /** Logs that the location power save mode has changed. */
    public void logLocationPowerSaveMode(
            @LocationPowerSaveMode int locationPowerSaveMode) {
        addLogEvent(EVENT_LOCATION_POWER_SAVE_MODE_CHANGE, locationPowerSaveMode);
    }

    @Override
    protected LogEvent createLogEvent(long timeDelta, int event, Object... args) {
        switch (event) {
            case EVENT_USER_SWITCHED:
                return new UserSwitchedEvent(timeDelta, (Integer) args[0], (Integer) args[1]);
            case EVENT_LOCATION_ENABLED:
                return new LocationEnabledEvent(timeDelta, (Integer) args[0], (Boolean) args[1]);
            case EVENT_ADAS_LOCATION_ENABLED:
                return new LocationAdasEnabledEvent(timeDelta, (Integer) args[0],
                        (Boolean) args[1]);
            case EVENT_PROVIDER_ENABLED:
                return new ProviderEnabledEvent(timeDelta, (String) args[0], (Integer) args[1],
                        (Boolean) args[2]);
            case EVENT_PROVIDER_MOCKED:
                return new ProviderMockedEvent(timeDelta, (String) args[0], (Boolean) args[1]);
            case EVENT_PROVIDER_CLIENT_REGISTER:
                return new ProviderClientRegisterEvent(timeDelta, (String) args[0], true,
                        (CallerIdentity) args[1], (LocationRequest) args[2]);
            case EVENT_PROVIDER_CLIENT_UNREGISTER:
                return new ProviderClientRegisterEvent(timeDelta, (String) args[0], false,
                        (CallerIdentity) args[1], null);
            case EVENT_PROVIDER_CLIENT_FOREGROUND:
                return new ProviderClientForegroundEvent(timeDelta, (String) args[0], true,
                        (CallerIdentity) args[1]);
            case EVENT_PROVIDER_CLIENT_BACKGROUND:
                return new ProviderClientForegroundEvent(timeDelta, (String) args[0], false,
                        (CallerIdentity) args[1]);
            case EVENT_PROVIDER_CLIENT_PERMITTED:
                return new ProviderClientPermittedEvent(timeDelta, (String) args[0], true,
                        (CallerIdentity) args[1]);
            case EVENT_PROVIDER_CLIENT_UNPERMITTED:
                return new ProviderClientPermittedEvent(timeDelta, (String) args[0], false,
                        (CallerIdentity) args[1]);
            case EVENT_PROVIDER_UPDATE_REQUEST:
                return new ProviderUpdateEvent(timeDelta, (String) args[0],
                        (ProviderRequest) args[1]);
            case EVENT_PROVIDER_RECEIVE_LOCATION:
                return new ProviderReceiveLocationEvent(timeDelta, (String) args[0],
                        (Integer) args[1]);
            case EVENT_PROVIDER_DELIVER_LOCATION:
                return new ProviderDeliverLocationEvent(timeDelta, (String) args[0],
                        (Integer) args[1], (CallerIdentity) args[2]);
            case EVENT_PROVIDER_STATIONARY_THROTTLED:
                return new ProviderStationaryThrottledEvent(timeDelta, (String) args[0],
                        (Boolean) args[1], (ProviderRequest) args[2]);
            case EVENT_LOCATION_POWER_SAVE_MODE_CHANGE:
                return new LocationPowerSaveModeEvent(timeDelta, (Integer) args[0]);
            default:
                throw new AssertionError();
        }
    }

    private abstract static class ProviderEvent extends LogEvent {

        protected final String mProvider;

        ProviderEvent(long timeDelta, String provider) {
            super(timeDelta);
            mProvider = provider;
        }

        @Override
        public boolean filter(String filter) {
            return mProvider.equals(filter);
        }
    }

    private static final class ProviderEnabledEvent extends ProviderEvent {

        private final int mUserId;
        private final boolean mEnabled;

        ProviderEnabledEvent(long timeDelta, String provider, int userId,
                boolean enabled) {
            super(timeDelta, provider);
            mUserId = userId;
            mEnabled = enabled;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider [u" + mUserId + "] " + (mEnabled ? "enabled"
                    : "disabled");
        }
    }

    private static final class ProviderMockedEvent extends ProviderEvent {

        private final boolean mMocked;

        ProviderMockedEvent(long timeDelta, String provider, boolean mocked) {
            super(timeDelta, provider);
            mMocked = mocked;
        }

        @Override
        public String getLogString() {
            if (mMocked) {
                return mProvider + " provider added mock provider override";
            } else {
                return mProvider + " provider removed mock provider override";
            }
        }
    }

    private static final class ProviderClientRegisterEvent extends ProviderEvent {

        private final boolean mRegistered;
        private final CallerIdentity mIdentity;
        @Nullable private final LocationRequest mLocationRequest;

        ProviderClientRegisterEvent(long timeDelta, String provider, boolean registered,
                CallerIdentity identity, @Nullable LocationRequest locationRequest) {
            super(timeDelta, provider);
            mRegistered = registered;
            mIdentity = identity;
            mLocationRequest = locationRequest;
        }

        @Override
        public String getLogString() {
            if (mRegistered) {
                return mProvider + " provider +registration " + mIdentity + " -> "
                        + mLocationRequest;
            } else {
                return mProvider + " provider -registration " + mIdentity;
            }
        }
    }

    private static final class ProviderClientForegroundEvent extends ProviderEvent {

        private final boolean mForeground;
        private final CallerIdentity mIdentity;

        ProviderClientForegroundEvent(long timeDelta, String provider, boolean foreground,
                CallerIdentity identity) {
            super(timeDelta, provider);
            mForeground = foreground;
            mIdentity = identity;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider client " + mIdentity + " -> "
                    + (mForeground ? "foreground" : "background");
        }
    }

    private static final class ProviderClientPermittedEvent extends ProviderEvent {

        private final boolean mPermitted;
        private final CallerIdentity mIdentity;

        ProviderClientPermittedEvent(long timeDelta, String provider, boolean permitted,
                CallerIdentity identity) {
            super(timeDelta, provider);
            mPermitted = permitted;
            mIdentity = identity;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider client " + mIdentity + " -> "
                    + (mPermitted ? "permitted" : "unpermitted");
        }
    }

    private static final class ProviderUpdateEvent extends ProviderEvent {

        private final ProviderRequest mRequest;

        ProviderUpdateEvent(long timeDelta, String provider, ProviderRequest request) {
            super(timeDelta, provider);
            mRequest = request;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider request = " + mRequest;
        }
    }

    private static final class ProviderReceiveLocationEvent extends ProviderEvent {

        private final int mNumLocations;

        ProviderReceiveLocationEvent(long timeDelta, String provider, int numLocations) {
            super(timeDelta, provider);
            mNumLocations = numLocations;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider received location[" + mNumLocations + "]";
        }
    }

    private static final class ProviderDeliverLocationEvent extends ProviderEvent {

        private final int mNumLocations;
        @Nullable private final CallerIdentity mIdentity;

        ProviderDeliverLocationEvent(long timeDelta, String provider, int numLocations,
                @Nullable CallerIdentity identity) {
            super(timeDelta, provider);
            mNumLocations = numLocations;
            mIdentity = identity;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider delivered location[" + mNumLocations + "] to "
                    + mIdentity;
        }
    }

    private static final class ProviderStationaryThrottledEvent extends ProviderEvent {

        private final boolean mStationaryThrottled;
        private final ProviderRequest mRequest;

        ProviderStationaryThrottledEvent(long timeDelta, String provider,
                boolean stationaryThrottled, ProviderRequest request) {
            super(timeDelta, provider);
            mStationaryThrottled = stationaryThrottled;
            mRequest = request;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider stationary/idle " + (mStationaryThrottled ? "throttled"
                    : "unthrottled") + ", request = " + mRequest;
        }
    }

    private static final class LocationPowerSaveModeEvent extends LogEvent {

        @LocationPowerSaveMode
        private final int mLocationPowerSaveMode;

        LocationPowerSaveModeEvent(long timeDelta,
                @LocationPowerSaveMode int locationPowerSaveMode) {
            super(timeDelta);
            mLocationPowerSaveMode = locationPowerSaveMode;
        }

        @Override
        public String getLogString() {
            String mode;
            switch (mLocationPowerSaveMode) {
                case LOCATION_MODE_NO_CHANGE:
                    mode = "NO_CHANGE";
                    break;
                case LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF:
                    mode = "GPS_DISABLED_WHEN_SCREEN_OFF";
                    break;
                case LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF:
                    mode = "ALL_DISABLED_WHEN_SCREEN_OFF";
                    break;
                case LOCATION_MODE_FOREGROUND_ONLY:
                    mode = "FOREGROUND_ONLY";
                    break;
                case LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF:
                    mode = "THROTTLE_REQUESTS_WHEN_SCREEN_OFF";
                    break;
                default:
                    mode = "UNKNOWN";
                    break;
            }
            return "location power save mode changed to " + mode;
        }
    }

    private static final class UserSwitchedEvent extends LogEvent {

        private final int mUserIdFrom;
        private final int mUserIdTo;

        UserSwitchedEvent(long timeDelta, int userIdFrom, int userIdTo) {
            super(timeDelta);
            mUserIdFrom = userIdFrom;
            mUserIdTo = userIdTo;
        }

        @Override
        public String getLogString() {
            return "current user switched from u" + mUserIdFrom + " to u" + mUserIdTo;
        }
    }

    private static final class LocationEnabledEvent extends LogEvent {

        private final int mUserId;
        private final boolean mEnabled;

        LocationEnabledEvent(long timeDelta, int userId, boolean enabled) {
            super(timeDelta);
            mUserId = userId;
            mEnabled = enabled;
        }

        @Override
        public String getLogString() {
            return "location [u" + mUserId + "] " + (mEnabled ? "enabled" : "disabled");
        }
    }

    private static final class LocationAdasEnabledEvent extends LogEvent {

        private final int mUserId;
        private final boolean mEnabled;

        LocationAdasEnabledEvent(long timeDelta, int userId, boolean enabled) {
            super(timeDelta);
            mUserId = userId;
            mEnabled = enabled;
        }

        @Override
        public String getLogString() {
            return "adas location [u" + mUserId + "] " + (mEnabled ? "enabled" : "disabled");
        }
    }

    /**
     * Aggregate statistics for a single package under a single provider.
     */
    public static final class AggregateStats {

        @GuardedBy("this")
        private int mAddedRequestCount;
        @GuardedBy("this")
        private int mActiveRequestCount;
        @GuardedBy("this")
        private int mForegroundRequestCount;
        @GuardedBy("this")
        private int mDeliveredLocationCount;

        @GuardedBy("this")
        private long mFastestIntervalMs = Long.MAX_VALUE;
        @GuardedBy("this")
        private long mSlowestIntervalMs = 0;

        @GuardedBy("this")
        private long mAddedTimeTotalMs;
        @GuardedBy("this")
        private long mAddedTimeLastUpdateRealtimeMs;

        @GuardedBy("this")
        private long mActiveTimeTotalMs;
        @GuardedBy("this")
        private long mActiveTimeLastUpdateRealtimeMs;

        @GuardedBy("this")
        private long mForegroundTimeTotalMs;
        @GuardedBy("this")
        private long mForegroundTimeLastUpdateRealtimeMs;

        AggregateStats() {}

        synchronized void markRequestAdded(long intervalMillis) {
            if (mAddedRequestCount++ == 0) {
                mAddedTimeLastUpdateRealtimeMs = SystemClock.elapsedRealtime();
            }

            mFastestIntervalMs = min(intervalMillis, mFastestIntervalMs);
            mSlowestIntervalMs = max(intervalMillis, mSlowestIntervalMs);
        }

        synchronized void markRequestRemoved() {
            updateTotals();
            --mAddedRequestCount;
            Preconditions.checkState(mAddedRequestCount >= 0);

            mActiveRequestCount = min(mAddedRequestCount, mActiveRequestCount);
            mForegroundRequestCount = min(mAddedRequestCount, mForegroundRequestCount);
        }

        synchronized void markRequestActive() {
            Preconditions.checkState(mAddedRequestCount > 0);
            if (mActiveRequestCount++ == 0) {
                mActiveTimeLastUpdateRealtimeMs = SystemClock.elapsedRealtime();
            }
        }

        synchronized void markRequestInactive() {
            updateTotals();
            --mActiveRequestCount;
            Preconditions.checkState(mActiveRequestCount >= 0);
        }

        synchronized void markRequestForeground() {
            Preconditions.checkState(mAddedRequestCount > 0);
            if (mForegroundRequestCount++ == 0) {
                mForegroundTimeLastUpdateRealtimeMs = SystemClock.elapsedRealtime();
            }
        }

        synchronized void markRequestBackground() {
            updateTotals();
            --mForegroundRequestCount;
            Preconditions.checkState(mForegroundRequestCount >= 0);
        }

        synchronized void markLocationDelivered() {
            mDeliveredLocationCount++;
        }

        public synchronized void updateTotals() {
            if (mAddedRequestCount > 0) {
                long realtimeMs = SystemClock.elapsedRealtime();
                mAddedTimeTotalMs += realtimeMs - mAddedTimeLastUpdateRealtimeMs;
                mAddedTimeLastUpdateRealtimeMs = realtimeMs;
            }
            if (mActiveRequestCount > 0) {
                long realtimeMs = SystemClock.elapsedRealtime();
                mActiveTimeTotalMs += realtimeMs - mActiveTimeLastUpdateRealtimeMs;
                mActiveTimeLastUpdateRealtimeMs = realtimeMs;
            }
            if (mForegroundRequestCount > 0) {
                long realtimeMs = SystemClock.elapsedRealtime();
                mForegroundTimeTotalMs += realtimeMs - mForegroundTimeLastUpdateRealtimeMs;
                mForegroundTimeLastUpdateRealtimeMs = realtimeMs;
            }
        }

        @Override
        public synchronized String toString() {
            return "min/max interval = " + intervalToString(mFastestIntervalMs) + "/"
                    + intervalToString(mSlowestIntervalMs)
                    + ", total/active/foreground duration = " + formatDuration(mAddedTimeTotalMs)
                    + "/" + formatDuration(mActiveTimeTotalMs) + "/"
                    + formatDuration(mForegroundTimeTotalMs) + ", locations = "
                    + mDeliveredLocationCount;
        }

        private static String intervalToString(long intervalMs) {
            if (intervalMs == LocationRequest.PASSIVE_INTERVAL) {
                return "passive";
            } else {
                return MILLISECONDS.toSeconds(intervalMs) + "s";
            }
        }
    }
}
