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
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.whenever
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class PrimaryBouncerToGoneTransitionViewModelTest : SysuiTestCase() {
    val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply {
                set(Flags.REFACTOR_KEYGUARD_DISMISS_INTENT, false)
                set(Flags.FULL_SCREEN_USER_SWITCHER, false)
            }
        }
    val testScope = kosmos.testScope

    val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    val primaryBouncerInteractor = kosmos.primaryBouncerInteractor
    val sysuiStatusBarStateController = kosmos.sysuiStatusBarStateController
    val underTest = kosmos.primaryBouncerToGoneTransitionViewModel

    @Before
    fun setUp() {
        whenever(primaryBouncerInteractor.willRunDismissFromKeyguard()).thenReturn(false)
        sysuiStatusBarStateController.setLeaveOpenOnKeyguardHide(false)
    }

    @Test
    fun bouncerAlpha() =
        testScope.runTest {
            val values by collectValues(underTest.bouncerAlpha)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0.3f),
                    step(0.6f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(3)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun bouncerAlpha_runDimissFromKeyguard() =
        testScope.runTest {
            val values by collectValues(underTest.bouncerAlpha)

            whenever(primaryBouncerInteractor.willRunDismissFromKeyguard()).thenReturn(true)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0.3f),
                    step(0.6f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(3)
            values.forEach { assertThat(it).isEqualTo(0f) }
        }

    @Test
    fun lockscreenAlpha() =
        testScope.runTest {
            val values by collectValues(underTest.lockscreenAlpha)

            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            keyguardTransitionRepository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(2)
            values.forEach { assertThat(it).isEqualTo(0f) }
        }

    @Test
    fun lockscreenAlpha_runDimissFromKeyguard() =
        testScope.runTest {
            val values by collectValues(underTest.lockscreenAlpha)

            sysuiStatusBarStateController.setLeaveOpenOnKeyguardHide(true)

            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            keyguardTransitionRepository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(2)
            values.forEach { assertThat(it).isEqualTo(1f) }
        }

    @Test
    fun lockscreenAlpha_leaveShadeOpen() =
        testScope.runTest {
            val values by collectValues(underTest.lockscreenAlpha)

            sysuiStatusBarStateController.setLeaveOpenOnKeyguardHide(true)

            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            keyguardTransitionRepository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(2)
            values.forEach { assertThat(it).isEqualTo(1f) }
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
