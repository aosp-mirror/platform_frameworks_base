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

package com.android.systemui.keyboard.shortcut.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyboard.shortcut.shortcutHelperTestHelper
import com.android.systemui.keyboard.shortcut.shortcutHelperViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.testCase
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.model.sysUiState
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_SHORTCUT_HELPER_SHOWING
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class ShortcutHelperViewModelTest : SysuiTestCase() {

    private val kosmos =
        Kosmos().also {
            it.testCase = this
            it.testDispatcher = UnconfinedTestDispatcher()
        }

    private val testScope = kosmos.testScope
    private val testHelper = kosmos.shortcutHelperTestHelper
    private val sysUiState = kosmos.sysUiState
    private val viewModel = kosmos.shortcutHelperViewModel

    @Test
    fun shouldShow_falseByDefault() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            assertThat(shouldShow).isFalse()
        }

    @Test
    fun shouldShow_trueAfterShowRequested() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.showFromActivity()

            assertThat(shouldShow).isTrue()
        }

    @Test
    fun shouldShow_trueAfterToggleRequested() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.toggle(deviceId = 123)

            assertThat(shouldShow).isTrue()
        }

    @Test
    fun shouldShow_falseAfterToggleTwice() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.toggle(deviceId = 123)
            testHelper.toggle(deviceId = 123)

            assertThat(shouldShow).isFalse()
        }

    @Test
    fun shouldShow_falseAfterViewClosed() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.toggle(deviceId = 567)
            viewModel.onViewClosed()

            assertThat(shouldShow).isFalse()
        }

    @Test
    fun shouldShow_doesNotEmitDuplicateValues() =
        testScope.runTest {
            val shouldShowValues by collectValues(viewModel.shouldShow)

            testHelper.hideForSystem()
            testHelper.toggle(deviceId = 987)
            testHelper.showFromActivity()
            viewModel.onViewClosed()
            testHelper.hideFromActivity()
            testHelper.hideForSystem()
            testHelper.toggle(deviceId = 456)
            testHelper.showFromActivity()

            assertThat(shouldShowValues).containsExactly(false, true, false, true).inOrder()
        }

    @Test
    fun shouldShow_emitsLatestValueToNewSubscribers() =
        testScope.runTest {
            val shouldShow by collectLastValue(viewModel.shouldShow)

            testHelper.showFromActivity()

            val shouldShowNew by collectLastValue(viewModel.shouldShow)
            assertThat(shouldShowNew).isEqualTo(shouldShow)
        }

    @Test
    fun sysUiStateFlag_disabledByDefault() =
        testScope.runTest {
            assertThat(sysUiState.isFlagEnabled(SYSUI_STATE_SHORTCUT_HELPER_SHOWING)).isFalse()
        }

    @Test
    fun sysUiStateFlag_trueAfterViewOpened() =
        testScope.runTest {
            viewModel.onViewOpened()

            assertThat(sysUiState.isFlagEnabled(SYSUI_STATE_SHORTCUT_HELPER_SHOWING)).isTrue()
        }

    @Test
    fun sysUiStateFlag_falseAfterViewClosed() =
        testScope.runTest {
            viewModel.onViewOpened()
            viewModel.onViewClosed()

            assertThat(sysUiState.isFlagEnabled(SYSUI_STATE_SHORTCUT_HELPER_SHOWING)).isFalse()
        }
}
