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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.bouncer.domain.interactor.primaryBouncerInteractor
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.PRIMARY_BOUNCER
import com.android.systemui.keyguard.shared.model.ScrimAlpha
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidJUnit4::class)
class BouncerToGoneFlowsTest : SysuiTestCase() {
    @Mock private lateinit var shadeInteractor: ShadeInteractor

    private val shadeExpansionStateFlow = MutableStateFlow(0.1f)

    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply {
                set(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT, false)
                set(Flags.FULL_SCREEN_USER_SWITCHER, false)
            }
        }
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val shadeRepository = kosmos.shadeRepository
    private val sysuiStatusBarStateController = kosmos.sysuiStatusBarStateController
    private val primaryBouncerInteractor = kosmos.primaryBouncerInteractor
    private val underTest = kosmos.bouncerToGoneFlows

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(primaryBouncerInteractor.willRunDismissFromKeyguard()).thenReturn(false)
        sysuiStatusBarStateController.setLeaveOpenOnKeyguardHide(false)
    }

    @Test
    fun scrimAlpha_runDimissFromKeyguard_shadeExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.scrimAlpha(500.milliseconds, PRIMARY_BOUNCER))
            runCurrent()

            shadeRepository.setLockscreenShadeExpansion(1f)
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
    fun scrimAlpha_runDimissFromKeyguard_shadeNotExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.scrimAlpha(500.milliseconds, PRIMARY_BOUNCER))
            runCurrent()

            shadeRepository.setLockscreenShadeExpansion(0f)

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
