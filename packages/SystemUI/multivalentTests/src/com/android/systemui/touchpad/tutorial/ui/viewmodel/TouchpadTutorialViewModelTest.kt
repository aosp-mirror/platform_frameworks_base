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

package com.android.systemui.touchpad.tutorial.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.inputdevice.tutorial.inputDeviceTutorialLogger
import com.android.systemui.model.sysUiState
import com.android.systemui.settings.displayTracker
import com.android.systemui.shared.system.QuickStepContract.SYSUI_STATE_TOUCHPAD_GESTURES_DISABLED
import com.android.systemui.shared.system.QuickStepContract.SystemUiStateFlags
import com.android.systemui.testKosmos
import com.android.systemui.touchpad.tutorial.domain.interactor.TouchpadGesturesInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class TouchpadTutorialViewModelTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = TestScope()
    private val sysUiState = kosmos.sysUiState
    private val viewModel =
        TouchpadTutorialViewModel(
            TouchpadGesturesInteractor(
                sysUiState,
                kosmos.displayTracker,
                testScope.backgroundScope,
                kosmos.inputDeviceTutorialLogger
            ),
            kosmos.inputDeviceTutorialLogger
        )

    @Test
    fun sysUiStateFlag_disabledByDefault() =
        testScope.runTest {
            assertThat(isFlagEnabled(SYSUI_STATE_TOUCHPAD_GESTURES_DISABLED)).isFalse()
        }

    @Test
    fun sysUiStateFlag_enabledAfterTutorialOpened() =
        testScope.runTest {
            viewModel.onOpened()

            assertThat(isFlagEnabled(SYSUI_STATE_TOUCHPAD_GESTURES_DISABLED)).isTrue()
        }

    @Test
    fun sysUiStateFlag_disabledAfterTutorialClosed() =
        testScope.runTest {
            viewModel.onOpened()
            viewModel.onClosed()

            assertThat(isFlagEnabled(SYSUI_STATE_TOUCHPAD_GESTURES_DISABLED)).isFalse()
        }

    private fun TestScope.isFlagEnabled(@SystemUiStateFlags flag: Long): Boolean {
        // sysui state is changed on background scope so let's make sure it's executed
        runCurrent()
        return sysUiState.isFlagEnabled(flag)
    }
}
