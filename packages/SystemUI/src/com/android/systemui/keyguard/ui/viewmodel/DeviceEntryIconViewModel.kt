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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import android.graphics.Color
import com.android.systemui.keyguard.ui.view.DeviceEntryIconView
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

@ExperimentalCoroutinesApi
class DeviceEntryIconViewModel @Inject constructor() {
    // TODO: b/305234447 update these states from the data layer
    val iconViewModel: Flow<IconViewModel> =
        flowOf(
            IconViewModel(
                type = DeviceEntryIconView.IconType.LOCK,
                useAodVariant = false,
                tint = Color.WHITE,
                alpha = 1f,
                padding = 48,
            )
        )
    val backgroundViewModel: Flow<BackgroundViewModel> =
        flowOf(BackgroundViewModel(alpha = 1f, tint = Color.GRAY))
    val burnInViewModel: Flow<BurnInViewModel> = flowOf(BurnInViewModel(0, 0, 0f))
    val isLongPressEnabled: Flow<Boolean> = flowOf(true)
    val accessibilityDelegateHint: Flow<DeviceEntryIconView.AccessibilityHintType> =
        flowOf(DeviceEntryIconView.AccessibilityHintType.NONE)

    fun onLongPress() {
        // TODO() vibrate & perform action based on current lock/unlock state
    }
    data class BurnInViewModel(
        val x: Int, // current x burn in offset based on the aodTransitionAmount
        val y: Int, // current y burn in offset based on the aodTransitionAmount
        val progress: Float, // current progress based on the aodTransitionAmount
    )

    class IconViewModel(
        val type: DeviceEntryIconView.IconType,
        val useAodVariant: Boolean,
        val tint: Int,
        val alpha: Float,
        val padding: Int,
    )

    class BackgroundViewModel(
        val alpha: Float,
        val tint: Int,
    )
}
