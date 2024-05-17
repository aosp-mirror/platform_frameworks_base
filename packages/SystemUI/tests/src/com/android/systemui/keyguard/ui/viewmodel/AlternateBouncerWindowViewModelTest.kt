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

import androidx.test.filters.SmallTest
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.statusbar.policy.keyguardStateController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.kotlin.whenever

@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
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
            val canShowAlternateBouncer by collectLastValue(underTest.canShowAlternateBouncer)
            val alternateBouncerWindowRequired by
                collectLastValue(underTest.alternateBouncerWindowRequired)
            givenCanShowAlternateBouncer()
            fingerprintPropertyRepository.supportsUdfps()
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepToLockscreen(0f, TransitionState.STARTED),
                    stepToLockscreen(.4f),
                    stepToLockscreen(1f, TransitionState.FINISHED),
                ),
                testScope,
            )
            assertThat(canShowAlternateBouncer).isTrue()
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromLockscreenToAlternateBouncer(0f, TransitionState.STARTED),
                    stepFromLockscreenToAlternateBouncer(.4f),
                    stepFromLockscreenToAlternateBouncer(.6f),
                ),
                testScope,
            )
            assertThat(canShowAlternateBouncer).isTrue()
            assertThat(alternateBouncerWindowRequired).isTrue()

            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromAlternateBouncer(0f, TransitionState.STARTED),
                    stepFromAlternateBouncer(.2f),
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
            givenCanShowAlternateBouncer()
            fingerprintPropertyRepository.supportsUdfps()
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepToLockscreen(0f, TransitionState.STARTED),
                    stepToLockscreen(.4f),
                    stepToLockscreen(1f, TransitionState.FINISHED),
                ),
                testScope,
            )
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromLockscreenToAlternateBouncer(0f, TransitionState.STARTED),
                    stepFromLockscreenToAlternateBouncer(.4f),
                    stepFromLockscreenToAlternateBouncer(.6f),
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
            givenCanShowAlternateBouncer()
            fingerprintPropertyRepository.supportsUdfps()
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromLockscreenToDozing(0f, TransitionState.STARTED),
                    stepFromLockscreenToDozing(.4f),
                    stepFromLockscreenToDozing(.6f),
                    stepFromLockscreenToDozing(1f, TransitionState.FINISHED),
                ),
                testScope,
            )
            assertThat(alternateBouncerWindowRequired).isFalse()
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromDozingToLockscreen(0f, TransitionState.STARTED),
                    stepFromDozingToLockscreen(.4f),
                    stepFromDozingToLockscreen(.6f),
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
            givenCanShowAlternateBouncer()
            fingerprintPropertyRepository.supportsRearFps()
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepToLockscreen(0f, TransitionState.STARTED),
                    stepToLockscreen(.4f),
                    stepToLockscreen(1f, TransitionState.FINISHED),
                ),
                testScope,
            )
            transitionRepository.sendTransitionSteps(
                listOf(
                    stepFromLockscreenToAlternateBouncer(0f, TransitionState.STARTED),
                    stepFromLockscreenToAlternateBouncer(.4f),
                    stepFromLockscreenToAlternateBouncer(.6f),
                ),
                testScope,
            )
            assertThat(alternateBouncerWindowRequired).isFalse()
        }

    private fun stepToLockscreen(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return step(
            from = KeyguardState.GONE,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
        )
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

    private fun stepFromLockscreenToAlternateBouncer(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return step(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.ALTERNATE_BOUNCER,
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

    private fun stepFromLockscreenToDozing(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return step(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.DOZING,
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

    /**
     * Given the alternate bouncer parameters are set so that the alternate bouncer can show, aside
     * from the fingerprint modality.
     */
    private fun givenCanShowAlternateBouncer() {
        kosmos.fakeKeyguardBouncerRepository.setPrimaryShow(false)
        kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
        kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
        whenever(kosmos.keyguardUpdateMonitor.isFingerprintLockedOut).thenReturn(false)
        whenever(kosmos.keyguardStateController.isUnlocked).thenReturn(false)
    }
}
