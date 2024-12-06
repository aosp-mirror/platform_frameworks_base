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

package com.android.systemui.qs.composefragment.viewmodel

import android.testing.TestableLooper.RunWithLooper
import androidx.test.filters.SmallTest
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
@RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
class QSFragmentComposeViewModelForceQSTest(private val testData: TestData) :
    AbstractQSFragmentComposeViewModelTest() {

    @Test
    fun forceQs_orRealExpansion() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                with(testData) {
                    sysuiStatusBarStateController.setState(statusBarState)
                    underTest.isQsExpanded = expanded
                    underTest.isStackScrollerOverscrolling = stackScrollerOverScrolling
                    fakeDeviceEntryRepository.setBypassEnabled(bypassEnabled)
                    underTest.isTransitioningToFullShade = transitioningToFullShade
                    underTest.isInSplitShade = inSplitShade

                    underTest.setQsExpansionValue(EXPANSION)

                    runCurrent()
                    assertThat(underTest.expansionState.progress)
                        .isEqualTo(if (expectedForceQS) 1f else EXPANSION)
                }
            }
        }

    data class TestData(
        val statusBarState: Int,
        val expanded: Boolean,
        val stackScrollerOverScrolling: Boolean,
        val bypassEnabled: Boolean,
        val transitioningToFullShade: Boolean,
        val inSplitShade: Boolean,
    ) {
        private val inKeyguard = statusBarState == StatusBarState.KEYGUARD

        private val showCollapsedOnKeyguard =
            bypassEnabled || (transitioningToFullShade && !inSplitShade)

        val expectedForceQS =
            (expanded || stackScrollerOverScrolling) && (inKeyguard && !showCollapsedOnKeyguard)
    }

    companion object {
        private const val EXPANSION = 0.3f

        @Parameters(name = "{0}")
        @JvmStatic
        fun createTestData(): List<TestData> {
            return statusBarStates.flatMap { statusBarState ->
                (0u..31u).map { bitfield ->
                    TestData(
                        statusBarState,
                        expanded = (bitfield and 1u) == 1u,
                        stackScrollerOverScrolling = (bitfield and 2u) == 2u,
                        bypassEnabled = (bitfield and 4u) == 4u,
                        transitioningToFullShade = (bitfield and 8u) == 8u,
                        inSplitShade = (bitfield and 16u) == 16u,
                    )
                }
            }
        }

        private val statusBarStates =
            setOf(StatusBarState.SHADE, StatusBarState.KEYGUARD, StatusBarState.SHADE_LOCKED)
    }
}
