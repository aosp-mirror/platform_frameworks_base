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
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.KeyguardState.DOZING
import com.android.systemui.keyguard.shared.model.KeyguardState.DREAMING
import com.android.systemui.keyguard.shared.model.KeyguardState.GONE
import com.android.systemui.keyguard.shared.model.KeyguardState.LOCKSCREEN
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.CANCELED
import com.android.systemui.keyguard.shared.model.TransitionState.FINISHED
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionState.STARTED
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class DreamingToLockscreenTransitionViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    private val underTest = kosmos.dreamingToLockscreenTransitionViewModel

    @Test
    fun dreamOverlayTranslationY() =
        testScope.runTest {
            val pixels = 100
            val values by collectValues(underTest.dreamOverlayTranslationY(pixels))

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.3f),
                    step(0.5f),
                    step(0.6f),
                    step(0.8f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(7)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 100f)) }
        }

    @Test
    fun dreamOverlayFadeOut() =
        testScope.runTest {
            val values by collectValues(underTest.dreamOverlayAlpha)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    // Should start running here...
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.1f),
                    step(0.5f),
                    // ...up to here
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun lockscreenFadeIn() =
        testScope.runTest {
            val values by collectValues(underTest.lockscreenAlpha)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.1f),
                    step(0.2f),
                    step(0.3f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun deviceEntryParentViewFadeIn() =
        testScope.runTest {
            val values by collectValues(underTest.deviceEntryParentViewAlpha)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.1f),
                    step(0.2f),
                    step(0.3f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    @Test
    fun deviceEntryBackgroundViewAppear() =
        testScope.runTest {
            fingerprintPropertyRepository.setProperties(
                sensorId = 0,
                strength = SensorStrength.STRONG,
                sensorType = FingerprintSensorType.UDFPS_OPTICAL,
                sensorLocations = emptyMap(),
            )
            val values by collectValues(underTest.deviceEntryBackgroundViewAlpha)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.1f),
                    step(0.2f),
                    step(0.3f),
                    step(1f),
                ),
                testScope,
            )

            values.forEach { assertThat(it).isEqualTo(1f) }
        }

    @Test
    fun deviceEntryBackground_noUdfps_noUpdates() =
        testScope.runTest {
            fingerprintPropertyRepository.setProperties(
                sensorId = 0,
                strength = SensorStrength.STRONG,
                sensorType = FingerprintSensorType.REAR,
                sensorLocations = emptyMap(),
            )
            val values by collectValues(underTest.deviceEntryBackgroundViewAlpha)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    step(0f, TransitionState.STARTED),
                    step(0f),
                    step(0.1f),
                    step(0.2f),
                    step(0.3f),
                    step(1f),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(0) // no updates
        }

    @Test
    fun lockscreenTranslationY() =
        testScope.runTest {
            val pixels = 100
            val values by collectValues(underTest.lockscreenTranslationY(pixels))

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
    fun transitionEnded() =
        testScope.runTest {
            val values by collectValues(underTest.transitionEnded)

            keyguardTransitionRepository.sendTransitionSteps(
                listOf(
                    TransitionStep(DOZING, DREAMING, 0.0f, STARTED),
                    TransitionStep(DOZING, DREAMING, 1.0f, FINISHED),
                    TransitionStep(DREAMING, LOCKSCREEN, 0.0f, STARTED),
                    TransitionStep(DREAMING, LOCKSCREEN, 0.1f, RUNNING),
                    TransitionStep(DREAMING, LOCKSCREEN, 1.0f, FINISHED),
                    TransitionStep(LOCKSCREEN, DREAMING, 0.0f, STARTED),
                    TransitionStep(LOCKSCREEN, DREAMING, 0.5f, RUNNING),
                    TransitionStep(LOCKSCREEN, DREAMING, 1.0f, FINISHED),
                    TransitionStep(DREAMING, GONE, 0.0f, STARTED),
                    TransitionStep(DREAMING, GONE, 0.5f, RUNNING),
                    TransitionStep(DREAMING, GONE, 1.0f, CANCELED),
                    TransitionStep(DREAMING, AOD, 0.0f, STARTED),
                    TransitionStep(DREAMING, AOD, 1.0f, FINISHED),
                ),
                testScope,
            )

            assertThat(values.size).isEqualTo(3)
            values.forEach {
                assertThat(it.transitionState == FINISHED || it.transitionState == CANCELED)
                    .isTrue()
            }
        }

    private fun step(value: Float, state: TransitionState = RUNNING): TransitionStep {
        return TransitionStep(
            from = DREAMING,
            to = LOCKSCREEN,
            value = value,
            transitionState = state,
            ownerName = "DreamingToLockscreenTransitionViewModelTest"
        )
    }
}
