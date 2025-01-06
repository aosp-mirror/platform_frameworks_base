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

package com.android.server.wm.flicker.helpers

import android.app.Instrumentation
import android.os.SystemClock
import android.view.KeyEvent.ACTION_DOWN
import android.view.KeyEvent.ACTION_UP
import android.view.KeyEvent

/**
 * Helper class for injecting a custom key event. This is used for instrumenting keyboard shortcut
 * actions.
 */
class KeyEventHelper(
    private val instr: Instrumentation,
) {
    fun press(keyCode: Int, metaState: Int = 0) {
        actionDown(keyCode, metaState)
        actionUp(keyCode, metaState)
    }

    fun actionDown(keyCode: Int, metaState: Int = 0, time: Long = SystemClock.uptimeMillis()) {
        injectKeyEvent(ACTION_DOWN, keyCode, metaState, downTime = time, eventTime = time)
    }

    fun actionUp(keyCode: Int, metaState: Int = 0, time: Long = SystemClock.uptimeMillis()) {
        injectKeyEvent(ACTION_UP, keyCode, metaState, downTime = time, eventTime = time)
    }

    private fun injectKeyEvent(
        action: Int,
        keyCode: Int,
        metaState: Int = 0,
        downTime: Long = SystemClock.uptimeMillis(),
        eventTime: Long = SystemClock.uptimeMillis()
    ): KeyEvent {
        val event = KeyEvent(downTime, eventTime, action, keyCode, /* repeat= */ 0, metaState)
        injectKeyEvent(event)
        return event
    }

    private fun injectKeyEvent(event: KeyEvent) {
        instr.uiAutomation.injectInputEvent(event, true)
    }
}