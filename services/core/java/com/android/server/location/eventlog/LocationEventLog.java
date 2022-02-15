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
import android.util.TimeUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.Preconditions;

import java.util.function.Consumer;

/** In memory event log for location events. */
public class LocationEventLog extends LocalEventLog<Object> {

    public static final LocationEventLog EVENT_LOG = new LocationEventLog();

    private static int getLogSize() {
        if (D) {
            return 600;
        } else {
            return 300;
        }
    }

    private static int getLocationsLogSize() {
        if (D) {
            return 200;
        } else {
            return 100;
        }
    }

    @GuardedBy("mAggregateStats")
    private final ArrayMap<String, ArrayMap<CallerIdentity, AggregateStats>> mAggregateStats;

    @GuardedBy("this")
    private final LocationsEventLog mLocationsLog;

    private LocationEventLog() {
        super(getLogSize(), Object.class);
        mAggregateStats = new ArrayMap<>(4);
        mLocationsLog = new LocationsEventLog(getLocationsLogSize());
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
        addLog(new UserSwitchedEvent(userIdFrom, userIdTo));
    }

    /** Logs a location enabled/disabled event. */
    public void logLocationEnabled(int userId, boolean enabled) {
        addLog(new LocationEnabledEvent(userId, enabled));
    }

    /** Logs a location enabled/disabled event. */
    public void logAdasLocationEnabled(int userId, boolean enabled) {
        addLog(new LocationAdasEnabledEvent(userId, enabled));
    }

    /** Logs a location provider enabled/disabled event. */
    public void logProviderEnabled(String provider, int userId, boolean enabled) {
        addLog(new ProviderEnabledEvent(provider, userId, enabled));
    }

    /** Logs a location provider being replaced/unreplaced by a mock provider. */
    public void logProviderMocked(String provider, boolean mocked) {
        addLog(new ProviderMockedEvent(provider, mocked));
    }

    /** Logs a new client registration for a location provider. */
    public void logProviderClientRegistered(String provider, CallerIdentity identity,
            LocationRequest request) {
        addLog(new ProviderClientRegisterEvent(provider, true, identity, request));
        getAggregateStats(provider, identity).markRequestAdded(request.getIntervalMillis());
    }

    /** Logs a client unregistration for a location provider. */
    public void logProviderClientUnregistered(String provider, CallerIdentity identity) {
        addLog(new ProviderClientRegisterEvent(provider, false, identity, null));
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
            addLog(new ProviderClientForegroundEvent(provider, true, identity));
        }
        getAggregateStats(provider, identity).markRequestForeground();
    }

    /** Logs a client for a location provider leaving the foreground state. */
    public void logProviderClientBackground(String provider, CallerIdentity identity) {
        if (D) {
            addLog(new ProviderClientForegroundEvent(provider, false, identity));
        }
        getAggregateStats(provider, identity).markRequestBackground();
    }

    /** Logs a client for a location provider entering the permitted state. */
    public void logProviderClientPermitted(String provider, CallerIdentity identity) {
        if (D) {
            addLog(new ProviderClientPermittedEvent(provider, true, identity));
        }
    }

    /** Logs a client for a location provider leaving the permitted state. */
    public void logProviderClientUnpermitted(String provider, CallerIdentity identity) {
        if (D) {
            addLog(new ProviderClientPermittedEvent(provider, false, identity));
        }
    }

    /** Logs a change to the provider request for a location provider. */
    public void logProviderUpdateRequest(String provider, ProviderRequest request) {
        addLog(new ProviderUpdateEvent(provider, request));
    }

    /** Logs a new incoming location for a location provider. */
    public void logProviderReceivedLocations(String provider, int numLocations) {
        synchronized (this) {
            mLocationsLog.logProviderReceivedLocations(provider, numLocations);
        }
    }

    /** Logs a location deliver for a client of a location provider. */
    public void logProviderDeliveredLocations(String provider, int numLocations,
            CallerIdentity identity) {
        synchronized (this) {
            mLocationsLog.logProviderDeliveredLocations(provider, numLocations, identity);
        }
        getAggregateStats(provider, identity).markLocationDelivered();
    }

    /** Logs that a provider has entered or exited stationary throttling. */
    public void logProviderStationaryThrottled(String provider, boolean throttled,
            ProviderRequest request) {
        addLog(new ProviderStationaryThrottledEvent(provider, throttled, request));
    }

    /** Logs that the location power save mode has changed. */
    public void logLocationPowerSaveMode(
            @LocationPowerSaveMode int locationPowerSaveMode) {
        addLog(new LocationPowerSaveModeEvent(locationPowerSaveMode));
    }

    private void addLog(Object logEvent) {
        addLog(SystemClock.elapsedRealtime(), logEvent);
    }

    @Override
    public synchronized void iterate(LogConsumer<? super Object> consumer) {
        iterate(consumer, this, mLocationsLog);
    }

    public void iterate(Consumer<String> consumer) {
        iterate(consumer, null);
    }

    public void iterate(Consumer<String> consumer, @Nullable String providerFilter) {
        long systemTimeDeltaMs = System.currentTimeMillis() - SystemClock.elapsedRealtime();
        StringBuilder builder = new StringBuilder();
        iterate(
                (time, logEvent) -> {
                    boolean match = providerFilter == null || (logEvent instanceof ProviderEvent
                            && providerFilter.equals(((ProviderEvent) logEvent).mProvider));
                    if (match) {
                        builder.setLength(0);
                        builder.append(TimeUtils.logTimeOfDay(time + systemTimeDeltaMs));
                        builder.append(": ");
                        builder.append(logEvent);
                        consumer.accept(builder.toString());
                    }
                });
    }

    private abstract static class ProviderEvent {

        protected final String mProvider;

        ProviderEvent(String provider) {
            mProvider = provider;
        }
    }

    private static final class ProviderEnabledEvent extends ProviderEvent {

        private final int mUserId;
        private final boolean mEnabled;

        ProviderEnabledEvent(String provider, int userId,
                boolean enabled) {
            super(provider);
            mUserId = userId;
            mEnabled = enabled;
        }

        @Override
        public String toString() {
            return mProvider + " provider [u" + mUserId + "] " + (mEnabled ? "enabled"
                    : "disabled");
        }
    }

    private static final class ProviderMockedEvent extends ProviderEvent {

        private final boolean mMocked;

        ProviderMockedEvent(String provider, boolean mocked) {
            super(provider);
            mMocked = mocked;
        }

        @Override
        public String toString() {
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

        ProviderClientRegisterEvent(String provider, boolean registered,
                CallerIdentity identity, @Nullable LocationRequest locationRequest) {
            super(provider);
            mRegistered = registered;
            mIdentity = identity;
            mLocationRequest = locationRequest;
        }

        @Override
        public String toString() {
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

        ProviderClientForegroundEvent(String provider, boolean foreground,
                CallerIdentity identity) {
            super(provider);
            mForeground = foreground;
            mIdentity = identity;
        }

        @Override
        public String toString() {
            return mProvider + " provider client " + mIdentity + " -> "
                    + (mForeground ? "foreground" : "background");
        }
    }

    private static final class ProviderClientPermittedEvent extends ProviderEvent {

        private final boolean mPermitted;
        private final CallerIdentity mIdentity;

        ProviderClientPermittedEvent(String provider, boolean permitted, CallerIdentity identity) {
            super(provider);
            mPermitted = permitted;
            mIdentity = identity;
        }

        @Override
        public String toString() {
            return mProvider + " provider client " + mIdentity + " -> "
                    + (mPermitted ? "permitted" : "unpermitted");
        }
    }

    private static final class ProviderUpdateEvent extends ProviderEvent {

        private final ProviderRequest mRequest;

        ProviderUpdateEvent(String provider, ProviderRequest request) {
            super(provider);
            mRequest = request;
        }

        @Override
        public String toString() {
            return mProvider + " provider request = " + mRequest;
        }
    }

    private static final class ProviderReceiveLocationEvent extends ProviderEvent {

        private final int mNumLocations;

        ProviderReceiveLocationEvent(String provider, int numLocations) {
            super(provider);
            mNumLocations = numLocations;
        }

        @Override
        public String toString() {
            return mProvider + " provider received location[" + mNumLocations + "]";
        }
    }

    private static final class ProviderDeliverLocationEvent extends ProviderEvent {

        private final int mNumLocations;
        @Nullable private final CallerIdentity mIdentity;

        ProviderDeliverLocationEvent(String provider, int numLocations,
                @Nullable CallerIdentity identity) {
            super(provider);
            mNumLocations = numLocations;
            mIdentity = identity;
        }

        @Override
        public String toString() {
            return mProvider + " provider delivered location[" + mNumLocations + "] to "
                    + mIdentity;
        }
    }

    private static final class ProviderStationaryThrottledEvent extends ProviderEvent {

        private final boolean mStationaryThrottled;
        private final ProviderRequest mRequest;

        ProviderStationaryThrottledEvent(String provider, boolean stationaryThrottled,
                ProviderRequest request) {
            super(provider);
            mStationaryThrottled = stationaryThrottled;
            mRequest = request;
        }

        @Override
        public String toString() {
            return mProvider + " provider stationary/idle " + (mStationaryThrottled ? "throttled"
                    : "unthrottled") + ", request = " + mRequest;
        }
    }

    private static final class LocationPowerSaveModeEvent {

        @LocationPowerSaveMode
        private final int mLocationPowerSaveMode;

        LocationPowerSaveModeEvent(@LocationPowerSaveMode int locationPowerSaveMode) {
            mLocationPowerSaveMode = locationPowerSaveMode;
        }

        @Override
        public String toString() {
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

    private static final class UserSwitchedEvent {

        private final int mUserIdFrom;
        private final int mUserIdTo;

        UserSwitchedEvent(int userIdFrom, int userIdTo) {
            mUserIdFrom = userIdFrom;
            mUserIdTo = userIdTo;
        }

        @Override
        public String toString() {
            return "current user switched from u" + mUserIdFrom + " to u" + mUserIdTo;
        }
    }

    private static final class LocationEnabledEvent {

        private final int mUserId;
        private final boolean mEnabled;

        LocationEnabledEvent(int userId, boolean enabled) {
            mUserId = userId;
            mEnabled = enabled;
        }

        @Override
        public String toString() {
            return "location [u" + mUserId + "] " + (mEnabled ? "enabled" : "disabled");
        }
    }

    private static final class LocationAdasEnabledEvent {

        private final int mUserId;
        private final boolean mEnabled;

        LocationAdasEnabledEvent(int userId, boolean enabled) {
            mUserId = userId;
            mEnabled = enabled;
        }

        @Override
        public String toString() {
            return "adas location [u" + mUserId + "] " + (mEnabled ? "enabled" : "disabled");
        }
    }

    private static final class LocationsEventLog extends LocalEventLog<Object> {

        LocationsEventLog(int size) {
            super(size, Object.class);
        }

        public void logProviderReceivedLocations(String provider, int numLocations) {
            addLog(new ProviderReceiveLocationEvent(provider, numLocations));
        }

        public void logProviderDeliveredLocations(String provider, int numLocations,
                CallerIdentity identity) {
            addLog(new ProviderDeliverLocationEvent(provider, numLocations, identity));
        }

        private void addLog(Object logEvent) {
            this.addLog(SystemClock.elapsedRealtime(), logEvent);
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
