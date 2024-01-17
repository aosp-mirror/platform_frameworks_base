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

package com.android.systemui.qs

import android.testing.AndroidTestingRunner
import android.view.KeyEvent
import android.view.KeyEvent.KEYCODE_DPAD_LEFT
import android.view.View
import androidx.core.util.Consumer
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class LeftRightArrowPressedListenerTest : SysuiTestCase() {

    private lateinit var underTest: LeftRightArrowPressedListener
    private val callback =
        object : Consumer<Int> {
            var lastValue: Int? = null

            override fun accept(keyCode: Int?) {
                lastValue = keyCode
            }
        }

    private val view = View(context)

    @Before
    fun setUp() {
        underTest = LeftRightArrowPressedListener.createAndRegisterListenerForView(view)
        underTest.setArrowKeyPressedListener(callback)
    }

    @Test
    fun shouldTriggerCallback_whenArrowUpReceived_afterArrowDownReceived() {
        underTest.sendKey(KeyEvent.ACTION_DOWN, KEYCODE_DPAD_LEFT)

        underTest.sendKey(KeyEvent.ACTION_UP, KEYCODE_DPAD_LEFT)

        assertThat(callback.lastValue).isEqualTo(KEYCODE_DPAD_LEFT)
    }

    @Test
    fun shouldNotTriggerCallback_whenKeyUpReceived_ifKeyDownNotReceived() {
        underTest.sendKey(KeyEvent.ACTION_UP, KEYCODE_DPAD_LEFT)

        assertThat(callback.lastValue).isNull()
    }

    @Test
    fun shouldNotTriggerCallback_whenKeyUpReceived_ifKeyDownWasRepeated() {
        underTest.sendKeyWithRepeat(KeyEvent.ACTION_UP, KEYCODE_DPAD_LEFT, repeat = 2)
        underTest.sendKey(KeyEvent.ACTION_UP, KEYCODE_DPAD_LEFT)

        assertThat(callback.lastValue).isNull()
    }

    @Test
    fun shouldNotTriggerCallback_whenKeyUpReceived_ifKeyDownReceivedBeforeLosingFocus() {
        underTest.sendKey(KeyEvent.ACTION_DOWN, KEYCODE_DPAD_LEFT)
        underTest.onFocusChange(view, hasFocus = false)
        underTest.onFocusChange(view, hasFocus = true)

        underTest.sendKey(KeyEvent.ACTION_UP, KEYCODE_DPAD_LEFT)

        assertThat(callback.lastValue).isNull()
    }

    private fun LeftRightArrowPressedListener.sendKey(action: Int, keyCode: Int) {
        onKey(view, keyCode, KeyEvent(action, keyCode))
    }

    private fun LeftRightArrowPressedListener.sendKeyWithRepeat(
        action: Int,
        keyCode: Int,
        repeat: Int
    ) {
        val keyEvent =
            KeyEvent(
                /* downTime= */ 0L,
                /* eventTime= */ 0L,
                /* action= */ action,
                /* code= */ KEYCODE_DPAD_LEFT,
                /* repeat= */ repeat
            )
        onKey(view, keyCode, keyEvent)
    }
}
