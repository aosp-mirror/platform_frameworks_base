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

package com.android.systemui.keyboard.shortcut.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyboard.shortcut.fakeShortcutHelperStartActivity
import com.android.systemui.keyboard.shortcut.shortcutHelperActivityStarter
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.keyboard.shortcut.ui.view.ShortcutHelperActivity
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ShortcutHelperActivityStarterTest : SysuiTestCase() {

    private val kosmos =
        Kosmos().also {
            it.testCase = this
            it.testDispatcher = UnconfinedTestDispatcher()
        }

    private val testScope = kosmos.testScope
    private val testHelper = kosmos.shortcutHelperTestHelper
    private val fakeStartActivity = kosmos.fakeShortcutHelperStartActivity
    private val starter = kosmos.shortcutHelperActivityStarter

    @Test
    fun start_doesNotStartByDefault() =
        testScope.runTest {
            starter.start()

            assertThat(fakeStartActivity.startIntents).isEmpty()
        }

    @Test
    fun start_onToggle_startsActivity() =
        testScope.runTest {
            starter.start()

            testHelper.toggle(deviceId = 456)

            verifyShortcutHelperActivityStarted()
        }

    @Test
    fun start_onToggle_multipleTimesStartsActivityOnlyWhenNotStarted() =
        testScope.runTest {
            starter.start()

            testHelper.toggle(deviceId = 456)
            testHelper.toggle(deviceId = 456)
            testHelper.toggle(deviceId = 456)
            testHelper.toggle(deviceId = 456)

            verifyShortcutHelperActivityStarted(numTimes = 2)
        }

    @Test
    fun start_onRequestShowShortcuts_startsActivity() =
        testScope.runTest {
            starter.start()

            testHelper.showFromActivity()

            verifyShortcutHelperActivityStarted()
        }

    @Test
    fun start_onRequestShowShortcuts_multipleTimes_startsActivityOnlyOnce() =
        testScope.runTest {
            starter.start()

            testHelper.showFromActivity()
            testHelper.showFromActivity()
            testHelper.showFromActivity()

            verifyShortcutHelperActivityStarted(numTimes = 1)
        }

    @Test
    fun start_onRequestShowShortcuts_multipleTimes_startsActivityOnlyWhenNotStarted() =
        testScope.runTest {
            starter.start()

            testHelper.hideFromActivity()
            testHelper.hideForSystem()
            testHelper.toggle(deviceId = 987)
            testHelper.showFromActivity()
            testHelper.hideFromActivity()
            testHelper.hideForSystem()
            testHelper.toggle(deviceId = 456)
            testHelper.showFromActivity()

            verifyShortcutHelperActivityStarted(numTimes = 2)
        }

    private fun verifyShortcutHelperActivityStarted(numTimes: Int = 1) {
        assertThat(fakeStartActivity.startIntents).hasSize(numTimes)
        fakeStartActivity.startIntents.forEach { intent ->
            assertThat(intent.flags).isEqualTo(Intent.FLAG_ACTIVITY_NEW_TASK)
            assertThat(intent.filterEquals(Intent(context, ShortcutHelperActivity::class.java)))
                .isTrue()
        }
    }
}
