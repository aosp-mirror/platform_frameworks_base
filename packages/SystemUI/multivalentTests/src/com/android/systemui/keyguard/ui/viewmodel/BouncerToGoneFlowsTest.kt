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

package com.android.systemui.keyguard.ui.viewmodel

import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.domain.interactor.mockPrimaryBouncerInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.BrokenWithSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.ScrimAlpha
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.shadeTestUtil
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class BouncerToGoneFlowsTest(flags: FlagsParameterization) : SysuiTestCase() {

    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(Flags.FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val sysuiStatusBarStateController = kosmos.sysuiStatusBarStateController
    private val primaryBouncerInteractor = kosmos.mockPrimaryBouncerInteractor

    private val shadeTestUtil by lazy { kosmos.shadeTestUtil }

    private lateinit var underTest: BouncerToGoneFlows

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(primaryBouncerInteractor.willRunDismissFromKeyguard()).thenReturn(false)
        sysuiStatusBarStateController.setLeaveOpenOnKeyguardHide(false)
        underTest = kosmos.bouncerToGoneFlows
    }

    @Test
    fun scrimAlpha_runDimissFromKeyguard_shadeExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.scrimAlpha(500.milliseconds, PRIMARY_BOUNCER))
            runCurrent()

            shadeTestUtil.setLockscreenShadeExpansion(1f)
            whenever(primaryBouncerInteractor.willRunDismissFromKeyguard()).thenReturn(true)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0.3f),
                    step(0.6f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it.frontAlpha).isEqualTo(0f) }
            values.forEach { assertThat(it.behindAlpha).isIn(Range.closed(0f, 1f)) }
            values.forEach { assertThat(it.notificationsAlpha).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    @BrokenWithSceneContainer(339465026)
    fun scrimAlpha_runDimissFromKeyguard_shadeNotExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.scrimAlpha(500.milliseconds, PRIMARY_BOUNCER))
            runCurrent()

            shadeTestUtil.setLockscreenShadeExpansion(0f)

            whenever(primaryBouncerInteractor.willRunDismissFromKeyguard()).thenReturn(true)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0.3f),
                    step(0.6f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isEqualTo(ScrimAlpha()) }
        }

    @Test
    fun scrimBehindAlpha_leaveShadeOpen() =
        testScope.runTest {
            val values by collectValues(underTest.scrimAlpha(500.milliseconds, PRIMARY_BOUNCER))
            runCurrent()

            sysuiStatusBarStateController.setLeaveOpenOnKeyguardHide(true)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0.3f),
                    step(0.6f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(4)
            values.forEach {
                assertThat(it).isEqualTo(ScrimAlpha(notificationsAlpha = 1f, behindAlpha = 1f))
            }
        }

    @Test
    fun showAllNotifications_isTrue_whenLeaveShadeOpen() =
        testScope.runTest {
            val showAllNotifications by
                collectLastValue(underTest.showAllNotifications(500.milliseconds, PRIMARY_BOUNCER))

            sysuiStatusBarStateController.setLeaveOpenOnKeyguardHide(true)

            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            keyguardTransitionRepository.sendTransitionStep(step(0.1f))

            assertThat(showAllNotifications).isTrue()
            keyguardTransitionRepository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(showAllNotifications).isFalse()
        }

    @Test
    fun showAllNotifications_isFalse_whenLeaveShadeIsNotOpen() =
        testScope.runTest {
            val showAllNotifications by
                collectLastValue(underTest.showAllNotifications(500.milliseconds, PRIMARY_BOUNCER))

            sysuiStatusBarStateController.setLeaveOpenOnKeyguardHide(false)

            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            keyguardTransitionRepository.sendTransitionStep(step(0.1f))

            assertThat(showAllNotifications).isFalse()
            keyguardTransitionRepository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(showAllNotifications).isFalse()
        }

    @Test
    fun scrimBehindAlpha_doNotLeaveShadeOpen() =
        testScope.runTest {
            val values by collectValues(underTest.scrimAlpha(500.milliseconds, PRIMARY_BOUNCER))
            runCurrent()

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0.3f),
                    step(0.6f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it.notificationsAlpha).isEqualTo(0f) }
            values.forEach { assertThat(it.frontAlpha).isEqualTo(0f) }
            values.forEach { assertThat(it.behindAlpha).isIn(Range.closed(0f, 1f)) }
            assertThat(values[3].behindAlpha).isEqualTo(0f)
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.PRIMARY_BOUNCER,
            to = KeyguardState.GONE,
            value = value,
            transitionState = state,
            ownerName = "PrimaryBouncerToGoneTransitionViewModelTest"
        )
    }
}
