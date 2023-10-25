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
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@ExperimentalCoroutinesApi
@SmallTest
@RunWith(AndroidJUnit4::class)
class OccludedToLockscreenTransitionViewModelTest : SysuiTestCase() {
    private lateinit var underTest: OccludedToLockscreenTransitionViewModel
    private lateinit var repository: FakeKeyguardTransitionRepository
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository

    @Before
    fun setUp() {
        repository = FakeKeyguardTransitionRepository()
        fingerprintPropertyRepository = FakeFingerprintPropertyRepository()
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        underTest =
            OccludedToLockscreenTransitionViewModel(
                interactor =
                    KeyguardTransitionInteractorFactory.create(
                            scope = TestScope().backgroundScope,
                            repository = repository,
                        )
                        .keyguardTransitionInteractor,
                deviceEntryUdfpsInteractor =
                    DeviceEntryUdfpsInteractor(
                        fingerprintPropertyRepository = fingerprintPropertyRepository,
                        fingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository(),
                        biometricSettingsRepository = biometricSettingsRepository,
                    ),
            )
    }

    @Test
    fun lockscreenFadeIn() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.lockscreenAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0.1f))
            // Should start running here...
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.4f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(0.6f))
            // ...up to here
            repository.sendTransitionStep(step(0.8f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }

            job.cancel()
        }

    @Test
    fun lockscreenTranslationY() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val pixels = 100
            val job =
                underTest.lockscreenTranslationY(pixels).onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(-100f, 0f)) }

            job.cancel()
        }

    @Test
    fun lockscreenTranslationYResettedAfterJobCancelled() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val pixels = 100
            val job =
                underTest.lockscreenTranslationY(pixels).onEach { values.add(it) }.launchIn(this)
            repository.sendTransitionStep(step(0.5f, TransitionState.CANCELED))

            assertThat(values.last()).isEqualTo(0f)

            job.cancel()
        }

    @Test
    fun deviceEntryParentViewFadeIn() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.deviceEntryParentViewAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0.1f))
            // Should start running here...
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.4f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(0.6f))
            // ...up to here
            repository.sendTransitionStep(step(0.8f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }

            job.cancel()
        }

    @Test
    fun deviceEntryBackgroundViewShows() =
        runTest(UnconfinedTestDispatcher()) {
            fingerprintPropertyRepository.supportsUdfps()
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            val values = mutableListOf<Float>()

            val job =
                underTest.deviceEntryBackgroundViewAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.4f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(0.6f))
            repository.sendTransitionStep(step(0.8f))
            repository.sendTransitionStep(step(1f))

            values.forEach { assertThat(it).isEqualTo(1f) }

            job.cancel()
        }

    @Test
    fun deviceEntryBackgroundView_noUdfpsEnrolled_noUpdates() =
        runTest(UnconfinedTestDispatcher()) {
            fingerprintPropertyRepository.supportsRearFps()
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            val values = mutableListOf<Float>()

            val job =
                underTest.deviceEntryBackgroundViewAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, TransitionState.STARTED))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.4f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(0.6f))
            repository.sendTransitionStep(step(0.8f))
            repository.sendTransitionStep(step(1f))

            assertThat(values).isEmpty() // no updates

            job.cancel()
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
