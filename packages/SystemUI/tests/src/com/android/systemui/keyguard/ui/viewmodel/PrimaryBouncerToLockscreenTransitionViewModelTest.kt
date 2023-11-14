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
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class PrimaryBouncerToLockscreenTransitionViewModelTest : SysuiTestCase() {
    private lateinit var underTest: PrimaryBouncerToLockscreenTransitionViewModel
    private lateinit var repository: FakeKeyguardTransitionRepository
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository

    @Before
    fun setUp() {
        repository = FakeKeyguardTransitionRepository()
        fingerprintPropertyRepository = FakeFingerprintPropertyRepository()
        biometricSettingsRepository = FakeBiometricSettingsRepository()

        underTest =
            PrimaryBouncerToLockscreenTransitionViewModel(
                KeyguardTransitionInteractorFactory.create(
                        scope = TestScope().backgroundScope,
                        repository = repository,
                    )
                    .keyguardTransitionInteractor,
                DeviceEntryUdfpsInteractor(
                    fingerprintPropertyRepository = fingerprintPropertyRepository,
                    fingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository(),
                    biometricSettingsRepository = biometricSettingsRepository,
                ),
            )
    }

    @Test
    fun deviceEntryParentViewAlpha() = runTest {
        val deviceEntryParentViewAlpha by collectLastValue(underTest.deviceEntryParentViewAlpha)

        // immediately 1f
        repository.sendTransitionStep(step(0f, TransitionState.STARTED))
        assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)

        repository.sendTransitionStep(step(0.4f))
        assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)

        repository.sendTransitionStep(step(.85f))
        assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)

        repository.sendTransitionStep(step(1f))
        assertThat(deviceEntryParentViewAlpha).isEqualTo(1f)
    }

    @Test
    fun deviceEntryBackgroundViewAlpha_udfpsEnrolled_show() = runTest {
        fingerprintPropertyRepository.supportsUdfps()
        val bgViewAlpha by collectLastValue(underTest.deviceEntryBackgroundViewAlpha)

        // immediately 1f
        repository.sendTransitionStep(step(0f, TransitionState.STARTED))
        assertThat(bgViewAlpha).isEqualTo(1f)

        repository.sendTransitionStep(step(0.1f))
        assertThat(bgViewAlpha).isEqualTo(1f)

        repository.sendTransitionStep(step(.3f))
        assertThat(bgViewAlpha).isEqualTo(1f)

        repository.sendTransitionStep(step(.5f))
        assertThat(bgViewAlpha).isEqualTo(1f)

        repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
        assertThat(bgViewAlpha).isEqualTo(1f)
    }

    @Test
    fun deviceEntryBackgroundViewAlpha_rearFpEnrolled_noUpdates() = runTest {
        fingerprintPropertyRepository.supportsRearFps()
        val bgViewAlpha by collectLastValue(underTest.deviceEntryBackgroundViewAlpha)
        repository.sendTransitionStep(step(0f, TransitionState.STARTED))
        assertThat(bgViewAlpha).isNull()

        repository.sendTransitionStep(step(0.5f))
        assertThat(bgViewAlpha).isNull()

        repository.sendTransitionStep(step(.75f))
        assertThat(bgViewAlpha).isNull()

        repository.sendTransitionStep(step(1f))
        assertThat(bgViewAlpha).isNull()

        repository.sendTransitionStep(step(1f, TransitionState.FINISHED))
        assertThat(bgViewAlpha).isNull()
    }

    private fun step(
        value: Float,
        state: TransitionState = TransitionState.RUNNING
    ): TransitionStep {
        return TransitionStep(
            from = KeyguardState.PRIMARY_BOUNCER,
            to = KeyguardState.LOCKSCREEN,
            value = value,
            transitionState = state,
            ownerName = "PrimaryBouncerToLockscreenTransitionViewModelTest"
        )
    }
}
