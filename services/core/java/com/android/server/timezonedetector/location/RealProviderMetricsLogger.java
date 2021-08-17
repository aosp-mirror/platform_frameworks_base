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

package com.android.server.timezonedetector.location;

import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__CERTAIN;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__DESTROYED;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__INITIALIZING;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__PERM_FAILED;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__STOPPED;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__UNCERTAIN;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__UNKNOWN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_DESTROYED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_PERM_FAILED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STARTED_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_STOPPED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.PROVIDER_STATE_UNKNOWN;

import android.annotation.IntRange;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderMetricsLogger;
import com.android.server.timezonedetector.location.LocationTimeZoneProvider.ProviderState.ProviderStateEnum;

/**
 * The real implementation of {@link ProviderMetricsLogger} which logs using
 * {@link FrameworkStatsLog}.
 */
public class RealProviderMetricsLogger implements ProviderMetricsLogger {

    @IntRange(from = 0, to = 1)
    private final int mProviderIndex;

    public RealProviderMetricsLogger(@IntRange(from = 0, to = 1) int providerIndex) {
        mProviderIndex = providerIndex;
    }

    @Override
    public void onProviderStateChanged(@ProviderStateEnum int stateEnum) {
        FrameworkStatsLog.write(FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED,
                mProviderIndex,
                metricsProviderState(stateEnum));
    }

    private static int metricsProviderState(@ProviderStateEnum int stateEnum) {
        switch (stateEnum) {
            case PROVIDER_STATE_STARTED_INITIALIZING:
                return LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__INITIALIZING;
            case PROVIDER_STATE_STARTED_UNCERTAIN:
                return LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__UNCERTAIN;
            case PROVIDER_STATE_STARTED_CERTAIN:
                return LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__CERTAIN;
            case PROVIDER_STATE_STOPPED:
                return LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__STOPPED;
            case PROVIDER_STATE_DESTROYED:
                return LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__DESTROYED;
            case PROVIDER_STATE_PERM_FAILED:
                return LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__PERM_FAILED;
            case PROVIDER_STATE_UNKNOWN:
            default:
                return LOCATION_TIME_ZONE_PROVIDER_STATE_CHANGED__STATE__UNKNOWN;
        }
    }
}
