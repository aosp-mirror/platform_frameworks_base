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
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.KeyguardState.AOD
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
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
class AlternateBouncerToAodTransitionViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    private val biometricSettingsRepository = kosmos.biometricSettingsRepository
    private val underTest = kosmos.alternateBouncerToAodTransitionViewModel

    @Test
    fun deviceEntryParentViewAppear() =
        testScope.runTest {
            fingerprintPropertyRepository.setProperties(
                sensorId = 0,
                strength = SensorStrength.STRONG,
                sensorType = FingerprintSensorType.UDFPS_OPTICAL,
                sensorLocations = emptyMap(),
            )
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
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

            values.forEach { assertThat(it).isEqualTo(1f) }
        }

    @Test
    fun deviceEntryBackgroundViewDisappear() =
        testScope.runTest {
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

            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }
        }

    private fun step(value: Float, state: TransitionState = RUNNING): TransitionStep {
        return TransitionStep(
            from = KeyguardState.ALTERNATE_BOUNCER,
            to = AOD,
            value = value,
            transitionState = state,
            ownerName = "AlternateBouncerToAodTransitionViewModelTest"
        )
    }
}
