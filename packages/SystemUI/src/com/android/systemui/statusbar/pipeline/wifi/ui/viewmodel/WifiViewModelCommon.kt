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

package com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel

import com.android.systemui.statusbar.pipeline.wifi.ui.model.WifiIcon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * A common view model interface that can be used for delegation between [WifiViewModel] and
 * [LocationBasedWifiViewModel].
 */
interface WifiViewModelCommon {
    /** The wifi icon that should be displayed. */
    val wifiIcon: StateFlow<WifiIcon>

    /** True if the activity in view should be visible. */
    val isActivityInViewVisible: Flow<Boolean>

    /** True if the activity out view should be visible. */
    val isActivityOutViewVisible: Flow<Boolean>

    /** True if the activity container view should be visible. */
    val isActivityContainerVisible: Flow<Boolean>

    /** True if the airplane spacer view should be visible. */
    val isAirplaneSpacerVisible: Flow<Boolean>
}
