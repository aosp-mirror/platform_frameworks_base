/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.mode

import com.android.server.display.config.RefreshRateData
import com.android.server.display.config.SupportedModeData

internal fun createVotesSummary(
        isDisplayResolutionRangeVotingEnabled: Boolean = true,
        supportedModesVoteEnabled: Boolean = true,
        loggingEnabled: Boolean = true,
        supportsFrameRateOverride: Boolean = true
): VoteSummary {
    return VoteSummary(isDisplayResolutionRangeVotingEnabled, supportedModesVoteEnabled,
            loggingEnabled, supportsFrameRateOverride)
}

fun createRefreshRateData(
        defaultRefreshRate: Int = 60,
        defaultPeakRefreshRate: Int = 60,
        defaultRefreshRateInHbmHdr: Int = 60,
        defaultRefreshRateInHbmSunlight: Int = 60,
        lowPowerSupportedModes: List<SupportedModeData> = emptyList()
): RefreshRateData {
        return RefreshRateData(defaultRefreshRate, defaultPeakRefreshRate,
                defaultRefreshRateInHbmHdr, defaultRefreshRateInHbmSunlight,
                lowPowerSupportedModes)
}
