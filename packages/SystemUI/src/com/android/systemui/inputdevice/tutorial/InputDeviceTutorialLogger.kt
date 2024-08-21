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

import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.dagger.InputDeviceTutorialLog
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen
import com.google.errorprone.annotations.CompileTimeConstant
import javax.inject.Inject

private const val TAG = "InputDeviceTutorial"

class InputDeviceTutorialLogger
@Inject
constructor(@InputDeviceTutorialLog private val buffer: LogBuffer) {

    fun log(@CompileTimeConstant s: String) {
        buffer.log(TAG, LogLevel.INFO, message = s)
    }

    fun logGoingToScreen(screen: Screen, context: TutorialContext) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = screen.toString()
                str2 = context.string
            },
            { "Emitting new screen $str1 in $str2" }
        )
    }

    fun logCloseTutorial(context: TutorialContext) {
        buffer.log(TAG, LogLevel.INFO, { str1 = context.string }, { "Closing $str1" })
    }

    enum class TutorialContext(val string: String) {
        KEYBOARD_TOUCHPAD_TUTORIAL("keyboard touchpad tutorial"),
        TOUCHPAD_TUTORIAL("touchpad tutorial"),
    }
}
