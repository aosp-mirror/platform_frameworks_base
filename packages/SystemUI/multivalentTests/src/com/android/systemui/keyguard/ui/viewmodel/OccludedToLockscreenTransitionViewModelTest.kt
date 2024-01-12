/*
 * Copyright (C) 2022 The Android Open Source Project
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
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
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
class OccludedToLockscreenTransitionViewModelTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope

    val biometricSettingsRepository = kosmos.biometricSettingsRepository
    val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    val configurationRepository = kosmos.fakeConfigurationRepository
    val underTest = kosmos.occludedToLockscreenTransitionViewModel

    @Test
    fun lockscreenFadeIn() =
        testScope.runTest {
            val values by collectValues(underTest.lockscreenAlpha)
            runCurrent()

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0.1f),
                    // Should start running here...
                    step(0.3f),
                    step(0.4f),
                    step(0.5f),
                    step(0.6f),
                    // ...up to here
                    step(0.8f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun lockscreenTranslationY() =
        testScope.runTest {
            configurationRepository.setDimensionPixelSize(
                R.dimen.occluded_to_lockscreen_transition_lockscreen_translation_y,
                100
            )
            val values by collectValues(underTest.lockscreenTranslationY)
            runCurrent()

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.3f),
                    step(0.5f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(-100f, 0f)) }
        }

    @Test
    fun lockscreenTranslationYResettedAfterJobCancelled() =
        testScope.runTest {
            configurationRepository.setDimensionPixelSize(
                R.dimen.occluded_to_lockscreen_transition_lockscreen_translation_y,
                100
            )
            val values by collectValues(underTest.lockscreenTranslationY)
            runCurrent()

            keyguardTransitionRepository.sendTransitionStep(step(0.5f, TransitionState.CANCELED))

            assertThat(values.last()).isEqualTo(0f)
        }

    @Test
    fun deviceEntryParentViewFadeIn() =
        testScope.runTest {
            val values by collectValues(underTest.deviceEntryParentViewAlpha)
            runCurrent()

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0.1f),
                    // Should start running here...
                    step(0.3f),
                    step(0.4f),
                    step(0.5f),
                    step(0.6f),
                    // ...up to here
                    step(0.8f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun deviceEntryBackgroundViewShows() =
        testScope.runTest {
            fingerprintPropertyRepository.supportsUdfps()
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            val values by collectValues(underTest.deviceEntryBackgroundViewAlpha)

            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            keyguardTransitionRepository.sendTransitionStep(step(0.1f))
            keyguardTransitionRepository.sendTransitionStep(step(0.3f))
            keyguardTransitionRepository.sendTransitionStep(step(0.4f))
            keyguardTransitionRepository.sendTransitionStep(step(0.5f))
            keyguardTransitionRepository.sendTransitionStep(step(0.6f))
            keyguardTransitionRepository.sendTransitionStep(step(0.8f))
            keyguardTransitionRepository.sendTransitionStep(step(1f))

            values.forEach { assertThat(it).isEqualTo(1f) }
        }

    @Test
    fun deviceEntryBackgroundView_noUdfpsEnrolled_noUpdates() =
        testScope.runTest {
            fingerprintPropertyRepository.supportsRearFps()
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            val values by collectValues(underTest.deviceEntryBackgroundViewAlpha)

            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            keyguardTransitionRepository.sendTransitionStep(step(0.1f))
            keyguardTransitionRepository.sendTransitionStep(step(0.3f))
            keyguardTransitionRepository.sendTransitionStep(step(0.4f))
            keyguardTransitionRepository.sendTransitionStep(step(0.5f))
            keyguardTransitionRepository.sendTransitionStep(step(0.6f))
            keyguardTransitionRepository.sendTransitionStep(step(0.8f))
            keyguardTransitionRepository.sendTransitionStep(step(1f))

            assertThat(values).isEmpty() // no updates
        }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.OCCLUDED,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
            ownerName = "OccludedToLockscreenTransitionViewModelTest"
        )
    }
}
