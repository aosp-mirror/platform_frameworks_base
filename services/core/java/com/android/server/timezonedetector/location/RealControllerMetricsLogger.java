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

import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__CERTAIN;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__DESTROYED;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__FAILED;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__INITIALIZING;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__PROVIDERS_INITIALIZING;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__STOPPED;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__UNCERTAIN;
import static com.android.internal.util.FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__UNKNOWN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_CERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_DESTROYED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_FAILED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_PROVIDERS_INITIALIZING;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_STOPPED;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_UNCERTAIN;
import static com.android.server.timezonedetector.location.LocationTimeZoneProviderController.STATE_UNKNOWN;

import com.android.internal.util.FrameworkStatsLog;
import com.android.server.timezonedetector.location.LocationTimeZoneProviderController.State;

/**
 * The real implementation of {@link LocationTimeZoneProviderController.MetricsLogger} which logs
 * using {@link FrameworkStatsLog}.
 */
final class RealControllerMetricsLogger
        implements LocationTimeZoneProviderController.MetricsLogger {

    RealControllerMetricsLogger() {
    }

    @Override
    public void onStateChange(@State String state) {
        FrameworkStatsLog.write(
                FrameworkStatsLog.LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED,
                metricsState(state));
    }

    private static int metricsState(@State String state) {
        switch (state) {
            case STATE_PROVIDERS_INITIALIZING:
                // Disable lint check (line length) for generated long constant name.
                // CHECKSTYLE:OFF Generated code
                return LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__PROVIDERS_INITIALIZING;
                // CHECKSTYLE:ON Generated code
            case STATE_STOPPED:
                return LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__STOPPED;
            case STATE_INITIALIZING:
                return LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__INITIALIZING;
            case STATE_CERTAIN:
                return LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__CERTAIN;
            case STATE_UNCERTAIN:
                return LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__UNCERTAIN;
            case STATE_DESTROYED:
                return LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__DESTROYED;
            case STATE_FAILED:
                return LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__FAILED;
            case STATE_UNKNOWN:
            default:
                return LOCATION_TIME_ZONE_PROVIDER_CONTROLLER_STATE_CHANGED__STATE__UNKNOWN;
        }
    }
}
