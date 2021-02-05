/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.location.injector;

import static android.os.PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_FOREGROUND_ONLY;
import static android.os.PowerManager.LOCATION_MODE_GPS_DISABLED_WHEN_SCREEN_OFF;
import static android.os.PowerManager.LOCATION_MODE_NO_CHANGE;
import static android.os.PowerManager.LOCATION_MODE_THROTTLE_REQUESTS_WHEN_SCREEN_OFF;

import static com.android.server.location.LocationManagerService.D;

import android.annotation.Nullable;
import android.location.LocationRequest;
import android.location.provider.ProviderRequest;
import android.location.util.identity.CallerIdentity;
import android.os.Build;
import android.os.PowerManager.LocationPowerSaveMode;

import com.android.server.location.eventlog.LocalEventLog;

/** In memory event log for location events. */
public class LocationEventLog extends LocalEventLog {

    private static int getLogSize() {
        if (Build.IS_DEBUGGABLE || D) {
            return 500;
        } else {
            return 200;
        }
    }

    private static final int EVENT_LOCATION_ENABLED = 1;
    private static final int EVENT_PROVIDER_ENABLED = 2;
    private static final int EVENT_PROVIDER_MOCKED = 3;
    private static final int EVENT_PROVIDER_REGISTER_CLIENT = 4;
    private static final int EVENT_PROVIDER_UNREGISTER_CLIENT = 5;
    private static final int EVENT_PROVIDER_UPDATE_REQUEST = 6;
    private static final int EVENT_PROVIDER_RECEIVE_LOCATION = 7;
    private static final int EVENT_PROVIDER_DELIVER_LOCATION = 8;
    private static final int EVENT_LOCATION_POWER_SAVE_MODE_CHANGE = 9;

    public LocationEventLog() {
        super(getLogSize());
    }

    /** Logs a location enabled/disabled event. */
    public void logLocationEnabled(int userId, boolean enabled) {
        addLogEvent(EVENT_LOCATION_POWER_SAVE_MODE_CHANGE, userId, enabled);
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
        addLogEvent(EVENT_PROVIDER_REGISTER_CLIENT, provider, identity, request);
    }

    /** Logs a client unregistration for a location provider. */
    public void logProviderClientUnregistered(String provider,
            CallerIdentity identity) {
        addLogEvent(EVENT_PROVIDER_UNREGISTER_CLIENT, provider, identity);
    }

    /** Logs a change to the provider request for a location provider. */
    public void logProviderUpdateRequest(String provider, ProviderRequest request) {
        addLogEvent(EVENT_PROVIDER_UPDATE_REQUEST, provider, request);
    }

    /** Logs a new incoming location for a location provider. */
    public void logProviderReceivedLocations(String provider, int numLocations) {
        if (Build.IS_DEBUGGABLE || D) {
            addLogEvent(EVENT_PROVIDER_RECEIVE_LOCATION, provider, numLocations);
        }
    }

    /** Logs a location deliver for a client of a location provider. */
    public void logProviderDeliveredLocations(String provider, int numLocations,
            CallerIdentity identity) {
        if (Build.IS_DEBUGGABLE || D) {
            addLogEvent(EVENT_PROVIDER_DELIVER_LOCATION, provider, numLocations, identity);
        }
    }

    /** Logs that the location power save mode has changed. */
    public void logLocationPowerSaveMode(
            @LocationPowerSaveMode int locationPowerSaveMode) {
        addLogEvent(EVENT_LOCATION_POWER_SAVE_MODE_CHANGE, locationPowerSaveMode);
    }

    @Override
    protected LogEvent createLogEvent(long timeDelta, int event, Object... args) {
        switch (event) {
            case EVENT_LOCATION_ENABLED:
                return new LocationEnabledEvent(timeDelta, (Integer) args[1], (Boolean) args[2]);
            case EVENT_PROVIDER_ENABLED:
                return new ProviderEnabledEvent(timeDelta, (String) args[0], (Integer) args[1],
                        (Boolean) args[2]);
            case EVENT_PROVIDER_MOCKED:
                return new ProviderMockedEvent(timeDelta, (String) args[0], (Boolean) args[1]);
            case EVENT_PROVIDER_REGISTER_CLIENT:
                return new ProviderRegisterEvent(timeDelta, (String) args[0], true,
                        (CallerIdentity) args[1], (LocationRequest) args[2]);
            case EVENT_PROVIDER_UNREGISTER_CLIENT:
                return new ProviderRegisterEvent(timeDelta, (String) args[0], false,
                        (CallerIdentity) args[1], null);
            case EVENT_PROVIDER_UPDATE_REQUEST:
                return new ProviderUpdateEvent(timeDelta, (String) args[0],
                        (ProviderRequest) args[1]);
            case EVENT_PROVIDER_RECEIVE_LOCATION:
                return new ProviderReceiveLocationEvent(timeDelta, (String) args[0],
                        (Integer) args[1]);
            case EVENT_PROVIDER_DELIVER_LOCATION:
                return new ProviderDeliverLocationEvent(timeDelta, (String) args[0],
                        (Integer) args[1], (CallerIdentity) args[2]);
            case EVENT_LOCATION_POWER_SAVE_MODE_CHANGE:
                return new LocationPowerSaveModeEvent(timeDelta, (Integer) args[0]);
            default:
                throw new AssertionError();
        }
    }

    private static class ProviderEnabledEvent extends LogEvent {

        private final String mProvider;
        private final int mUserId;
        private final boolean mEnabled;

        protected ProviderEnabledEvent(long timeDelta, String provider, int userId,
                boolean enabled) {
            super(timeDelta);
            mProvider = provider;
            mUserId = userId;
            mEnabled = enabled;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider [u" + mUserId + "] " + (mEnabled ? "enabled"
                    : "disabled");
        }
    }

    private static class ProviderMockedEvent extends LogEvent {

        private final String mProvider;
        private final boolean mMocked;

        protected ProviderMockedEvent(long timeDelta, String provider, boolean mocked) {
            super(timeDelta);
            mProvider = provider;
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

    private static class ProviderRegisterEvent extends LogEvent {

        private final String mProvider;
        private final boolean mRegistered;
        private final CallerIdentity mIdentity;
        @Nullable private final LocationRequest mLocationRequest;

        private ProviderRegisterEvent(long timeDelta, String provider, boolean registered,
                CallerIdentity identity, @Nullable LocationRequest locationRequest) {
            super(timeDelta);
            mProvider = provider;
            mRegistered = registered;
            mIdentity = identity;
            mLocationRequest = locationRequest;
        }

        @Override
        public String getLogString() {
            if (mRegistered) {
                return mProvider + " provider " + "+registration " + mIdentity + " -> "
                        + mLocationRequest;
            } else {
                return mProvider + " provider " + "-registration " + mIdentity;
            }
        }
    }

    private static class ProviderUpdateEvent extends LogEvent {

        private final String mProvider;
        private final ProviderRequest mRequest;

        private ProviderUpdateEvent(long timeDelta, String provider, ProviderRequest request) {
            super(timeDelta);
            mProvider = provider;
            mRequest = request;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider request = " + mRequest;
        }
    }

    private static class ProviderReceiveLocationEvent extends LogEvent {

        private final String mProvider;
        private final int mNumLocations;

        private ProviderReceiveLocationEvent(long timeDelta, String provider, int numLocations) {
            super(timeDelta);
            mProvider = provider;
            mNumLocations = numLocations;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider received location[" + mNumLocations + "]";
        }
    }

    private static class ProviderDeliverLocationEvent extends LogEvent {

        private final String mProvider;
        private final int mNumLocations;
        @Nullable private final CallerIdentity mIdentity;

        private ProviderDeliverLocationEvent(long timeDelta, String provider, int numLocations,
                @Nullable CallerIdentity identity) {
            super(timeDelta);
            mProvider = provider;
            mNumLocations = numLocations;
            mIdentity = identity;
        }

        @Override
        public String getLogString() {
            return mProvider + " provider delivered location[" + mNumLocations + "] to "
                    + mIdentity;
        }
    }

    private static class LocationPowerSaveModeEvent extends LogEvent {

        @LocationPowerSaveMode
        private final int mLocationPowerSaveMode;

        private LocationPowerSaveModeEvent(long timeDelta,
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

    private static class LocationEnabledEvent extends LogEvent {

        private final int mUserId;
        private final boolean mEnabled;

        private LocationEnabledEvent(long timeDelta, int userId, boolean enabled) {
            super(timeDelta);
            mUserId = userId;
            mEnabled = enabled;
        }

        @Override
        public String getLogString() {
            return "[u" + mUserId + "] location setting " + (mEnabled ? "enabled" : "disabled");
        }
    }
}
