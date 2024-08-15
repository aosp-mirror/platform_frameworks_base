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
 *
 */

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
@SmallTest
class AlternateBouncerWindowViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val fingerprintPropertyRepository by lazy { kosmos.fakeFingerprintPropertyRepository }
    private val transitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    private val underTest by lazy { kosmos.alternateBouncerWindowViewModel }

    @Test
    fun alternateBouncerTransition_alternateBouncerWindowRequiredTrue() =
        testScope.runTest {
            mSetFlagsRule.enableFlags(Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
            val alternateBouncerWindowRequired by
                collectLastValue(underTest.alternateBouncerWindowRequired)
            fingerprintPropertyRepository.supportsUdfps()
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromAlternateBouncer(0f, TransitionState.STARTED),
                    stepFromAlternateBouncer(.4f),
                    stepFromAlternateBouncer(.6f),
                ),
                testScope,
            )
            assertThat(alternateBouncerWindowRequired).isTrue()

            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromAlternateBouncer(1.0f, TransitionState.FINISHED),
                ),
                testScope,
            )
            assertThat(alternateBouncerWindowRequired).isFalse()
        }

    @Test
    fun deviceEntryUdfpsFlagDisabled_alternateBouncerWindowRequiredFalse() =
        testScope.runTest {
            mSetFlagsRule.disableFlags(Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
            val alternateBouncerWindowRequired by
                collectLastValue(underTest.alternateBouncerWindowRequired)
            fingerprintPropertyRepository.supportsUdfps()
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromAlternateBouncer(0f, TransitionState.STARTED),
                    stepFromAlternateBouncer(.4f),
                    stepFromAlternateBouncer(.6f),
                    stepFromAlternateBouncer(1f),
                ),
                testScope,
            )
            assertThat(alternateBouncerWindowRequired).isFalse()
        }

    @Test
    fun lockscreenTransition_alternateBouncerWindowRequiredFalse() =
        testScope.runTest {
            mSetFlagsRule.enableFlags(Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
            val alternateBouncerWindowRequired by
                collectLastValue(underTest.alternateBouncerWindowRequired)
            fingerprintPropertyRepository.supportsUdfps()
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromDozingToLockscreen(0f, TransitionState.STARTED),
                    stepFromDozingToLockscreen(.4f),
                    stepFromDozingToLockscreen(.6f),
                    stepFromDozingToLockscreen(1f),
                ),
                testScope,
            )
            assertThat(alternateBouncerWindowRequired).isFalse()
        }

    @Test
    fun rearFps_alternateBouncerWindowRequiredFalse() =
        testScope.runTest {
            mSetFlagsRule.enableFlags(Flags.FLAG_DEVICE_ENTRY_UDFPS_REFACTOR)
            val alternateBouncerWindowRequired by
                collectLastValue(underTest.alternateBouncerWindowRequired)
            fingerprintPropertyRepository.supportsRearFps()
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromAlternateBouncer(0f, TransitionState.STARTED),
                    stepFromAlternateBouncer(.4f),
                    stepFromAlternateBouncer(.6f),
                    stepFromAlternateBouncer(1f),
                ),
                testScope,
            )
            assertThat(alternateBouncerWindowRequired).isFalse()
        }

    private fun stepFromAlternateBouncer(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return step(
            from = KeyguardState.ALTERNATE_BOUNCER,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
        )
    }

    private fun stepFromDozingToLockscreen(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return step(
            from = KeyguardState.DOZING,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
        )
    }

    private fun step(
        from: KeyguardState,
        to: KeyguardState,
        value: Float,
        transitionState: TransitionState
    ): TransitionStep {
        return TransitionStep(
            from = from,
            to = to,
            value = value,
            transitionState = transitionState,
            ownerName = "AlternateBouncerViewModelTest"
        )
    }
}
