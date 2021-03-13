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
        // TODO(b/172934905): Implement once the atom has landed.
    }
}
