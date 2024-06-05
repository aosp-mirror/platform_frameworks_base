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

package com.android.systemui.unfold

import com.android.systemui.shared.system.SysUiStatsLog

class DisplaySwitchLatencyLogger {

    /**
     * Based on data present in [displaySwitchLatencyEvent], logs metrics for atom
     * [DisplaySwitchLatencyTracked]
     */
    fun log(displaySwitchLatencyEvent: DisplaySwitchLatencyTracker.DisplaySwitchLatencyEvent) {
        with(displaySwitchLatencyEvent) {
            SysUiStatsLog.write(
                SysUiStatsLog.DISPLAY_SWITCH_LATENCY_TRACKED,
                latencyMs,
                fromFoldableDeviceState,
                fromState,
                fromFocusedAppUid,
                fromPipAppUid,
                fromVisibleAppsUid.toIntArray(),
                fromDensityDpi,
                toState,
                toFoldableDeviceState,
                toFocusedAppUid,
                toPipAppUid,
                toVisibleAppsUid.toIntArray(),
                toDensityDpi,
                notificationCount,
                externalDisplayCount,
                throttlingLevel,
                vskinTemperatureC,
                hallSensorToFirstHingeAngleChangeMs,
                hallSensorToDeviceStateChangeMs,
                onScreenTurningOnToOnDrawnMs,
                onDrawnToOnScreenTurnedOnMs,
            )
        }
    }
}
