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

import com.android.systemui.inputdevice.tutorial.domain.interactor.ConnectionState
import com.android.systemui.inputdevice.tutorial.ui.viewmodel.Screen as KeyboardTouchpadTutorialScreen
import com.android.systemui.log.ConstantStringsLogger
import com.android.systemui.log.ConstantStringsLoggerImpl
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.log.core.MessageInitializer
import com.android.systemui.log.core.MessagePrinter
import com.android.systemui.log.dagger.InputDeviceTutorialLog
import com.android.systemui.touchpad.tutorial.ui.viewmodel.Screen as TouchpadTutorialScreen
import javax.inject.Inject

private const val TAG = "InputDeviceTutorial"

class InputDeviceTutorialLogger
@Inject
constructor(@InputDeviceTutorialLog private val buffer: LogBuffer) :
    ConstantStringsLogger by ConstantStringsLoggerImpl(buffer, TAG) {

    fun logGoingToScreen(screen: TouchpadTutorialScreen, context: TutorialContext) {
        logInfo(
            {
                str1 = screen.toString()
                str2 = context.string
            },
            { "Emitting new screen $str1 in $str2" }
        )
    }

    fun logCloseTutorial(context: TutorialContext) {
        logInfo({ str1 = context.string }, { "Closing $str1" })
    }

    fun logOpenTutorial(context: TutorialContext) {
        logInfo({ str1 = context.string }, { "Opening $str1" })
    }

    fun logNextScreenMissingHardware(nextScreen: KeyboardTouchpadTutorialScreen) {
        buffer.log(
            TAG,
            LogLevel.WARNING,
            { str1 = nextScreen.toString() },
            { "next screen should be $str1 but required hardware is missing" }
        )
    }

    fun logNextScreen(nextScreen: KeyboardTouchpadTutorialScreen) {
        logInfo({ str1 = nextScreen.toString() }, { "going to $str1 screen" })
    }

    fun logNewConnectionState(connectionState: ConnectionState) {
        logInfo(
            {
                bool1 = connectionState.touchpadConnected
                bool2 = connectionState.keyboardConnected
            },
            { "Received connection state: touchpad connected: $bool1 keyboard connected: $bool2" }
        )
    }

    fun logMovingBetweenScreens(
        previousScreen: KeyboardTouchpadTutorialScreen?,
        currentScreen: KeyboardTouchpadTutorialScreen
    ) {
        logInfo(
            {
                str1 = previousScreen?.toString() ?: "NO_SCREEN"
                str2 = currentScreen.toString()
            },
            { "Moving from $str1 screen to $str2 screen" }
        )
    }

    fun logGoingBack(previousScreen: KeyboardTouchpadTutorialScreen) {
        logInfo({ str1 = previousScreen.toString() }, { "Going back to $str1 screen" })
    }

    private inline fun logInfo(
        messageInitializer: MessageInitializer,
        noinline messagePrinter: MessagePrinter
    ) {
        buffer.log(TAG, LogLevel.INFO, messageInitializer, messagePrinter)
    }

    enum class TutorialContext(val string: String) {
        KEYBOARD_TOUCHPAD_TUTORIAL("keyboard touchpad tutorial"),
        TOUCHPAD_TUTORIAL("touchpad tutorial"),
    }
}
