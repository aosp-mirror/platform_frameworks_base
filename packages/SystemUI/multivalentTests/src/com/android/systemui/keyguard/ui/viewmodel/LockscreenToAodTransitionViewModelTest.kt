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
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.flags.Flags.FULL_SCREEN_USER_SWITCHER
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class LockscreenToAodTransitionViewModelTest : SysuiTestCase() {
    private val kosmos =
        testKosmos().apply {
            fakeFeatureFlagsClassic.apply { set(FULL_SCREEN_USER_SWITCHER, false) }
        }
    private val testScope = kosmos.testScope
    private val repository = kosmos.fakeKeyguardTransitionRepository
    private val shadeRepository = kosmos.shadeRepository
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    private val biometricSettingsRepository = kosmos.biometricSettingsRepository
    private val underTest = kosmos.lockscreenToAodTransitionViewModel

    @Test
    fun backgroundViewAlpha_shadeNotExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.deviceEntryBackgroundViewAlpha)
            shadeExpanded(false)
            runCurrent()

            // fade out
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(actual).isEqualTo(1f)

            repository.sendTransitionStep(step(.3f))
            assertThat(actual).isIn(Range.closed(.1f, .9f))

            // finish fading out before the end of the full transition
            repository.sendTransitionStep(step(.7f))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(actual).isEqualTo(0f)
        }

    @Test
    fun backgroundViewAlpha_shadeExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.deviceEntryBackgroundViewAlpha)
            shadeExpanded(true)
            runCurrent()

            // immediately 0f
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(.3f))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(.7f))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(actual).isEqualTo(0f)
        }

    @Test
    fun deviceEntryParentViewAlpha_udfpsEnrolled_shadeNotExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.deviceEntryParentViewAlpha)
            fingerprintPropertyRepository.supportsUdfps()
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            shadeExpanded(false)
            runCurrent()

            repository.sendTransitionSteps(
                steps =
                    listOf(
                        step(0f, TransitionState.STARTED),
                        step(.3f),
                        step(.7f),
                        step(1f),
                    ),
                testScope = testScope,
            )
            // immediately 1f
            values.forEach { assertThat(it).isEqualTo(1f) }
        }

    @Test
    fun deviceEntryParentViewAlpha_udfpsEnrolled_shadeExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.deviceEntryParentViewAlpha)
            fingerprintPropertyRepository.supportsUdfps()
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            shadeExpanded(true)
            runCurrent()

            // fade in
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(.3f))
            assertThat(actual).isIn(Range.closed(.1f, .9f))

            // finish fading in before the end of the full transition
            repository.sendTransitionStep(step(.7f))
            assertThat(actual).isEqualTo(1f)

            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(actual).isEqualTo(1f)
        }

    @Test
    fun deviceEntryParentViewAlpha_rearFp_shadeNotExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.deviceEntryParentViewAlpha)
            fingerprintPropertyRepository.supportsRearFps()
            shadeExpanded(false)
            runCurrent()

            // fade out
            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(actual).isEqualTo(1f)

            repository.sendTransitionStep(step(.1f))
            assertThat(actual).isIn(Range.closed(.1f, .9f))

            // finish fading out before the end of the full transition
            repository.sendTransitionStep(step(.7f))
            assertThat(actual).isEqualTo(0f)

            repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(actual).isEqualTo(0f)
        }

    @Test
    fun deviceEntryParentViewAlpha_rearFp_shadeExpanded() =
        testScope.runTest {
            val values by collectValues(underTest.deviceEntryParentViewAlpha)
            fingerprintPropertyRepository.supportsRearFps()
            shadeExpanded(true)
            runCurrent()

            repository.sendTransitionSteps(
                steps =
                    listOf(
                        step(0f, TransitionState.STARTED),
                        step(.3f),
                        step(.7f),
                        step(1f),
                    ),
                testScope = testScope,
            )
            // immediately 0f
            values.forEach { assertThat(it).isEqualTo(0f) }
        }

    private fun shadeExpanded(expanded: Boolean) {
        if (expanded) {
            shadeRepository.setQsExpansion(1f)
        } else {
            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            shadeRepository.setQsExpansion(0f)
            shadeRepository.setLockscreenShadeExpansion(0f)
        }
    }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.LOCKSCREEN,
            to = KeyguardState.AOD,
            value = value,
            transitionState = state,
            ownerName = "LockscreenToAodTransitionViewModelTest"
        )
    }
}
