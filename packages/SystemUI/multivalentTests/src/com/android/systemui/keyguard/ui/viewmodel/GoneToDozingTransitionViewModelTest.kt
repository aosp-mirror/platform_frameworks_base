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

package com.android.systemui.keyguard.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionState.RUNNING
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class GoneToDozingTransitionViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var underTest: GoneToDozingTransitionViewModel

    @Before
    fun setUp() {
        fingerprintPropertyRepository = kosmos.fakeFingerprintPropertyRepository
        biometricSettingsRepository = kosmos.fakeBiometricSettingsRepository
        keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
        underTest = kosmos.goneToDozingTransitionViewModel
    }

    @Test
    fun deviceEntryParentViewAppear_udfpsEnrolledAndEnabled() =
        testScope.runTest {
            fingerprintPropertyRepository.supportsUdfps()
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
    fun deviceEntryParentViewDisappear_noUdfpsEnrolled_noUpdates() =
        testScope.runTest {
            fingerprintPropertyRepository.supportsUdfps()
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)

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

            values.forEach { assertThat(it).isNull() }
        }

    @Test
    fun notificationAlpha_fadesOut() =
        testScope.runTest {
            val alpha by collectLastValue(underTest.notificationAlpha)

            keyguardTransitionRepository.sendTransitionStep(step(0f, TransitionState.STARTED))
            assertThat(alpha).isEqualTo(1f)

            keyguardTransitionRepository.sendTransitionStep(step(0.25f))
            assertThat(alpha).isIn(Range.open(.25f, .75f))

            keyguardTransitionRepository.sendTransitionStep(step(1f))
            assertThat(alpha).isEqualTo(0f)

            keyguardTransitionRepository.sendTransitionStep(step(1f, TransitionState.FINISHED))
            assertThat(alpha).isEqualTo(1f)
        }

    private fun step(value: Float, state: TransitionState = RUNNING): TransitionStep {
        return TransitionStep(
            from = KeyguardState.GONE,
            to = KeyguardState.DOZING,
            value = value,
            transitionState = state,
            ownerName = "GoneToDozingTransitionViewModelTest"
        )
    }
}
