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

import android.app.StatusBarManager
import android.content.testableContext
import android.testing.TestableLooper.RunWithLooper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.testing.TestLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.qs.fgsManagerController
import com.android.systemui.res.R
import com.android.systemui.shade.largeScreenHeaderHelper
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.disableflags.data.model.DisableFlagsModel
import com.android.systemui.statusbar.disableflags.data.repository.fakeDisableFlagsRepository
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
@OptIn(ExperimentalCoroutinesApi::class)
class QSFragmentComposeViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private val lifecycleOwner =
        TestLifecycleOwner(
            initialState = Lifecycle.State.CREATED,
            coroutineDispatcher = kosmos.testDispatcher,
        )

    private val underTest by lazy {
        kosmos.qsFragmentComposeViewModelFactory.create(lifecycleOwner.lifecycleScope)
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(kosmos.testDispatcher)
    }

    @After
    fun teardown() {
        Dispatchers.resetMain()
    }

    @Test
    fun qsExpansionValueChanges_correctExpansionState() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                val expansionState by collectLastValue(underTest.expansionState)

                underTest.qsExpansionValue = 0f
                assertThat(expansionState!!.progress).isEqualTo(0f)

                underTest.qsExpansionValue = 0.3f
                assertThat(expansionState!!.progress).isEqualTo(0.3f)

                underTest.qsExpansionValue = 1f
                assertThat(expansionState!!.progress).isEqualTo(1f)
            }
        }

    @Test
    fun qsExpansionValueChanges_clamped() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                val expansionState by collectLastValue(underTest.expansionState)

                underTest.qsExpansionValue = -1f
                assertThat(expansionState!!.progress).isEqualTo(0f)

                underTest.qsExpansionValue = 2f
                assertThat(expansionState!!.progress).isEqualTo(1f)
            }
        }

    @Test
    fun qqsHeaderHeight_largeScreenHeader_0() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                val qqsHeaderHeight by collectLastValue(underTest.qqsHeaderHeight)

                testableContext.orCreateTestableResources.addOverride(
                    R.bool.config_use_large_screen_shade_header,
                    true,
                )
                fakeConfigurationRepository.onConfigurationChange()

                assertThat(qqsHeaderHeight).isEqualTo(0)
            }
        }

    @Test
    fun qqsHeaderHeight_noLargeScreenHeader_providedByHelper() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                val qqsHeaderHeight by collectLastValue(underTest.qqsHeaderHeight)

                testableContext.orCreateTestableResources.addOverride(
                    R.bool.config_use_large_screen_shade_header,
                    false,
                )
                fakeConfigurationRepository.onConfigurationChange()

                assertThat(qqsHeaderHeight)
                    .isEqualTo(largeScreenHeaderHelper.getLargeScreenHeaderHeight())
            }
        }

    @Test
    fun footerActionsControllerInit() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                underTest
                runCurrent()
                assertThat(fgsManagerController.initialized).isTrue()
            }
        }

    @Test
    fun statusBarState_followsController() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                val statusBarState by collectLastValue(underTest.statusBarState)
                runCurrent()

                sysuiStatusBarStateController.setState(StatusBarState.SHADE)
                assertThat(statusBarState).isEqualTo(StatusBarState.SHADE)

                sysuiStatusBarStateController.setState(StatusBarState.KEYGUARD)
                assertThat(statusBarState).isEqualTo(StatusBarState.KEYGUARD)

                sysuiStatusBarStateController.setState(StatusBarState.SHADE_LOCKED)
                assertThat(statusBarState).isEqualTo(StatusBarState.SHADE_LOCKED)
            }
        }

    @Test
    fun statusBarState_changesEarlyIfUpcomingStateIsKeyguard() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                val statusBarState by collectLastValue(underTest.statusBarState)

                sysuiStatusBarStateController.setState(StatusBarState.SHADE)
                sysuiStatusBarStateController.setUpcomingState(StatusBarState.SHADE_LOCKED)
                assertThat(statusBarState).isEqualTo(StatusBarState.SHADE)

                sysuiStatusBarStateController.setUpcomingState(StatusBarState.KEYGUARD)
                assertThat(statusBarState).isEqualTo(StatusBarState.KEYGUARD)

                sysuiStatusBarStateController.setUpcomingState(StatusBarState.SHADE)
                assertThat(statusBarState).isEqualTo(StatusBarState.KEYGUARD)
            }
        }

    @Test
    fun qsEnabled_followsRepository() =
        with(kosmos) {
            testScope.testWithinLifecycle {
                val qsEnabled by collectLastValue(underTest.qsEnabled)

                fakeDisableFlagsRepository.disableFlags.value =
                    DisableFlagsModel(disable2 = QS_DISABLE_FLAG)

                assertThat(qsEnabled).isFalse()

                fakeDisableFlagsRepository.disableFlags.value = DisableFlagsModel()

                assertThat(qsEnabled).isTrue()
            }
        }

    private inline fun TestScope.testWithinLifecycle(
        crossinline block: suspend TestScope.() -> TestResult
    ): TestResult {
        return runTest {
            lifecycleOwner.setCurrentState(Lifecycle.State.RESUMED)
            block().also { lifecycleOwner.setCurrentState(Lifecycle.State.DESTROYED) }
        }
    }

    companion object {
        private const val QS_DISABLE_FLAG = StatusBarManager.DISABLE2_QUICK_SETTINGS
    }
}
