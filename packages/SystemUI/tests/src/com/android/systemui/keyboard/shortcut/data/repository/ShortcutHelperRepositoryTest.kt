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

package com.android.systemui.keyboard.shortcut.data.repository

import android.hardware.input.fakeInputManager
import android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState
import com.android.systemui.keyboard.shortcut.shortcutHelperRepository
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutHelperRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()

    private val repo = kosmos.shortcutHelperRepository
    private val helper = kosmos.shortcutHelperTestHelper
    private val testScope = kosmos.testScope
    private val fakeInputManager = kosmos.fakeInputManager

    @Test
    fun state_activeThroughToggle_emitsActiveWithDeviceIdFromEvent() =
        testScope.runTest {
            val deviceId = 123
            val state by collectLastValue(repo.state)

            helper.toggle(deviceId)

            assertThat(state).isEqualTo(ShortcutHelperState.Active(deviceId))
        }

    @Test
    fun state_activeThroughActivity_noKeyboardActive_emitsActiveWithVirtualDeviceId() =
        testScope.runTest {
            val state by collectLastValue(repo.state)

            helper.showFromActivity()

            assertThat(state).isEqualTo(ShortcutHelperState.Active(VIRTUAL_KEYBOARD))
        }

    @Test
    fun state_activeThroughActivity_virtualKeyboardActive_emitsActiveWithVirtualDeviceId() =
        testScope.runTest {
            val state by collectLastValue(repo.state)

            fakeInputManager.addVirtualKeyboard()
            helper.showFromActivity()

            assertThat(state).isEqualTo(ShortcutHelperState.Active(VIRTUAL_KEYBOARD))
        }

    @Test
    fun state_activeThroughActivity_physicalKeyboardActive_emitsActiveWithDeviceId() =
        testScope.runTest {
            val deviceId = 456
            val state by collectLastValue(repo.state)

            fakeInputManager.addPhysicalKeyboard(deviceId)
            helper.showFromActivity()

            assertThat(state).isEqualTo(ShortcutHelperState.Active(deviceId))
        }

    @Test
    fun state_activeThroughActivity_physicalKeyboardDisabled_emitsActiveWithVirtualDeviceId() =
        testScope.runTest {
            val deviceId = 456
            val state by collectLastValue(repo.state)

            fakeInputManager.addPhysicalKeyboard(deviceId)
            fakeInputManager.inputManager.disableInputDevice(deviceId)
            helper.showFromActivity()

            assertThat(state).isEqualTo(ShortcutHelperState.Active(VIRTUAL_KEYBOARD))
        }
}
