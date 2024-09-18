/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.touchpad.tutorial.domain.interactor

import com.android.systemui.inputdevice.tutorial.InputDeviceTutorialLogger
import com.android.systemui.model.SysUiState
import com.android.systemui.settings.DisplayTracker
import com.android.systemui.shared.system.QuickStepContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class TouchpadGesturesInteractor(
    private val sysUiState: SysUiState,
    private val displayTracker: DisplayTracker,
    private val backgroundScope: CoroutineScope,
    private val logger: InputDeviceTutorialLogger,
) {
    fun disableGestures() {
        logger.d("Disabling touchpad gestures across the system")
        setGesturesState(disabled = true)
    }

    fun enableGestures() {
        logger.d("Enabling touchpad gestures across the system")
        setGesturesState(disabled = false)
    }

    private fun setGesturesState(disabled: Boolean) {
        backgroundScope.launch {
            sysUiState
                .setFlag(QuickStepContract.SYSUI_STATE_TOUCHPAD_GESTURES_DISABLED, disabled)
                .commitUpdate(displayTracker.defaultDisplayId)
        }
    }
}
