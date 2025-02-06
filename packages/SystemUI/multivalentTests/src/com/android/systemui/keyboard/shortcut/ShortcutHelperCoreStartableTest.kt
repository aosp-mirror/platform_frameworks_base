/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.keyboard.shortcut

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyboard.shortcut.shared.model.ShortcutHelperState
import com.android.systemui.kosmos.testScope
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.plugins.activityStarter
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutHelperCoreStartableTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val repo = kosmos.shortcutHelperStateRepository
    private val helper = kosmos.shortcutHelperTestHelper
    private val testScope = kosmos.testScope
    private val activityStarter = kosmos.activityStarter

    @Test
    fun shortcutHelperState_whenToggled_doesNotBecomeActive_ifDeviceIsLocked() {
        testScope.runTest {
            assumedKeyguardIsNotDismissed()

            val state by collectLastValue(repo.state)
            helper.toggle(deviceId = 456)

            assertThat(state).isEqualTo(ShortcutHelperState.Inactive)
        }
    }

    @Test
    fun shortcutHelperState_whenToggled_becomesActive_ifDeviceIsUnlocked() {
        testScope.runTest {
            assumeKeyguardIsDismissed()

            val state by collectLastValue(repo.state)
            helper.toggle(deviceId = 456)

            assertThat(state).isEqualTo(ShortcutHelperState.Active(deviceId = 456))
        }
    }

    private fun assumeKeyguardIsDismissed(){
        whenever(activityStarter.dismissKeyguardThenExecute(any(), any(), eq(true))).then {
            (it.arguments[0] as ActivityStarter.OnDismissAction).onDismiss()
        }
    }

    private fun assumedKeyguardIsNotDismissed(){
        // Do nothing, simulating keyguard not being dismissed and action not being not executed
        doNothing().whenever(activityStarter).dismissKeyguardThenExecute(any(), any(), eq(true))
    }
}
