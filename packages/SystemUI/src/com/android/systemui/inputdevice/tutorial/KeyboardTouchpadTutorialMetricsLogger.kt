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

package com.android.systemui.inputdevice.tutorial

import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_ENTRY_POINT_CONTEXTUAL_EDU
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_ENTRY_POINT_SCHEDULER
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_KEYBOARD
import com.android.systemui.inputdevice.tutorial.ui.view.KeyboardTouchpadTutorialActivity.Companion.INTENT_TUTORIAL_SCOPE_TOUCHPAD
import com.android.systemui.shared.system.SysUiStatsLog
import javax.inject.Inject

class KeyboardTouchpadTutorialMetricsLogger @Inject constructor() {

    fun logPeripheralTutorialLaunched(entryPointExtra: String?, tutorialTypeExtra: String?) {
        val entryPoint =
            when (entryPointExtra) {
                INTENT_TUTORIAL_ENTRY_POINT_SCHEDULER ->
                    SysUiStatsLog.PERIPHERAL_TUTORIAL_LAUNCHED__ENTRY_POINT__SCHEDULED
                INTENT_TUTORIAL_ENTRY_POINT_CONTEXTUAL_EDU ->
                    SysUiStatsLog.PERIPHERAL_TUTORIAL_LAUNCHED__ENTRY_POINT__CONTEXTUAL_EDU
                else -> SysUiStatsLog.PERIPHERAL_TUTORIAL_LAUNCHED__ENTRY_POINT__APP
            }

        val tutorialType =
            when (tutorialTypeExtra) {
                INTENT_TUTORIAL_SCOPE_KEYBOARD ->
                    SysUiStatsLog.PERIPHERAL_TUTORIAL_LAUNCHED__TUTORIAL_TYPE__KEYBOARD
                INTENT_TUTORIAL_SCOPE_TOUCHPAD ->
                    SysUiStatsLog.PERIPHERAL_TUTORIAL_LAUNCHED__TUTORIAL_TYPE__TOUCHPAD
                else -> SysUiStatsLog.PERIPHERAL_TUTORIAL_LAUNCHED__TUTORIAL_TYPE__BOTH
            }

        SysUiStatsLog.write(SysUiStatsLog.PERIPHERAL_TUTORIAL_LAUNCHED, entryPoint, tutorialType)
    }

    fun logPeripheralTutorialLaunchedFromSettings() {
        val entryPoint = SysUiStatsLog.PERIPHERAL_TUTORIAL_LAUNCHED__ENTRY_POINT__SETTINGS
        val tutorialType = SysUiStatsLog.PERIPHERAL_TUTORIAL_LAUNCHED__TUTORIAL_TYPE__TOUCHPAD
        SysUiStatsLog.write(SysUiStatsLog.PERIPHERAL_TUTORIAL_LAUNCHED, entryPoint, tutorialType)
    }
}
