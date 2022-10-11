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
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.statusbar.pipeline.StatusBarPipelineFlags
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/**
 * A view model for a wifi icon in a specific location. This allows us to control parameters that
 * are location-specific (for example, different tints of the icon in different locations).
 *
 * Must be subclassed for each distinct location.
 */
abstract class LocationBasedWifiViewModel(
    statusBarPipelineFlags: StatusBarPipelineFlags,
    debugTint: Int,

    /** The wifi icon that should be displayed. Null if we shouldn't display any icon. */
    val wifiIcon: StateFlow<Icon.Resource?>,

    /** True if the activity in view should be visible. */
    val isActivityInViewVisible: Flow<Boolean>,

    /** True if the activity out view should be visible. */
    val isActivityOutViewVisible: Flow<Boolean>,

    /** True if the activity container view should be visible. */
    val isActivityContainerVisible: Flow<Boolean>,
) {
    /** The color that should be used to tint the icon. */
    val tint: Flow<Int> =
        flowOf(
            if (statusBarPipelineFlags.useNewPipelineDebugColoring()) {
                debugTint
            } else {
                DEFAULT_TINT
            }
        )

    companion object {
        /**
         * A default icon tint.
         *
         * TODO(b/238425913): The tint is actually controlled by
         * [com.android.systemui.statusbar.phone.StatusBarIconController.TintedIconManager]. We
         * should use that logic instead of white as a default.
         */
        private const val DEFAULT_TINT = Color.WHITE
    }
}
