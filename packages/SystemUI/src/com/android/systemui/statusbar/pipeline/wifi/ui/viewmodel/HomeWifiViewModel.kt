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
 * A view model for the wifi icon shown on the "home" page (aka, when the device is unlocked and not
 * showing the shade, so the user is on the home-screen, or in an app).
 */
class HomeWifiViewModel(
    statusBarPipelineFlags: StatusBarPipelineFlags,
    wifiIcon: StateFlow<WifiIcon>,
    isActivityInViewVisible: Flow<Boolean>,
    isActivityOutViewVisible: Flow<Boolean>,
    isActivityContainerVisible: Flow<Boolean>,
    isAirplaneSpacerVisible: Flow<Boolean>,
) :
    LocationBasedWifiViewModel(
        statusBarPipelineFlags,
        debugTint = Color.CYAN,
        wifiIcon,
        isActivityInViewVisible,
        isActivityOutViewVisible,
        isActivityContainerVisible,
        isAirplaneSpacerVisible,
    )
