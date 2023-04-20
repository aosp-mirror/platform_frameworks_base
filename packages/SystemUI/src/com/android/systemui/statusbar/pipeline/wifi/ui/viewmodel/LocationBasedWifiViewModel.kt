/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel

import android.graphics.Color
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A view model for a wifi icon in a specific location. This allows us to control parameters that
 * are location-specific (for example, different tints of the icon in different locations).
 *
 * Must be subclassed for each distinct location.
 */
abstract class LocationBasedWifiViewModel(
    statusBarPipelineFlags: StatusBarPipelineFlags,
    debugTint: Int,

    /** The wifi icon that should be displayed. */
    val wifiIcon: StateFlow<WifiIcon>,

    /** True if the activity in view should be visible. */
    val isActivityInViewVisible: Flow<Boolean>,

    /** True if the activity out view should be visible. */
    val isActivityOutViewVisible: Flow<Boolean>,

    /** True if the activity container view should be visible. */
    val isActivityContainerVisible: Flow<Boolean>,

    /** True if the airplane spacer view should be visible. */
    val isAirplaneSpacerVisible: Flow<Boolean>,
) {
    val useDebugColoring: Boolean = statusBarPipelineFlags.useDebugColoring()

    val defaultColor: Int =
        if (useDebugColoring) {
            debugTint
        } else {
            Color.WHITE
        }
}
