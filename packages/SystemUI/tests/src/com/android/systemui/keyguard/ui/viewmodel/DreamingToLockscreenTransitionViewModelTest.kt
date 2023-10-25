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
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryUdfpsInteractor
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
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
import com.android.systemui.util.mockito.mock
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
class DreamingToLockscreenTransitionViewModelTest : SysuiTestCase() {
    private lateinit var underTest: DreamingToLockscreenTransitionViewModel
    private lateinit var repository: FakeKeyguardTransitionRepository
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository

    @Before
    fun setUp() {
        repository = FakeKeyguardTransitionRepository()
        fingerprintPropertyRepository = FakeFingerprintPropertyRepository()
        val interactor =
            KeyguardTransitionInteractorFactory.create(
                    scope = TestScope().backgroundScope,
                    repository = repository,
                )
                .keyguardTransitionInteractor
        underTest =
            DreamingToLockscreenTransitionViewModel(
                interactor,
                mock(),
                DeviceEntryUdfpsInteractor(
                    fingerprintPropertyRepository = fingerprintPropertyRepository,
                    fingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository(),
                    biometricSettingsRepository = FakeBiometricSettingsRepository(),
                ),
            )
    }

    @Test
    fun dreamOverlayTranslationY() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val pixels = 100
            val job =
                underTest.dreamOverlayTranslationY(pixels).onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(0.6f))
            repository.sendTransitionStep(step(0.8f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(7)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 100f)) }

            job.cancel()
        }

    @Test
    fun dreamOverlayFadeOut() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.dreamOverlayAlpha.onEach { values.add(it) }.launchIn(this)

            // Should start running here...
            repository.sendTransitionStep(step(0f, STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.5f))
            // ...up to here
            repository.sendTransitionStep(step(1f))

            // Only two values should be present, since the dream overlay runs for a small fraction
            // of the overall animation time
            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }

            job.cancel()
        }

    @Test
    fun lockscreenFadeIn() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.lockscreenAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.2f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }

            job.cancel()
        }

    @Test
    fun deviceEntryParentViewFadeIn() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val job = underTest.deviceEntryParentViewAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.2f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(4)
            values.forEach { assertThat(it).isIn(Range.closed(0f, 1f)) }

            job.cancel()
        }

    @Test
    fun deviceEntryBackgroundViewAppear() =
        runTest(UnconfinedTestDispatcher()) {
            fingerprintPropertyRepository.setProperties(
                sensorId = 0,
                strength = SensorStrength.STRONG,
                sensorType = FingerprintSensorType.UDFPS_OPTICAL,
                sensorLocations = emptyMap(),
            )
            val values = mutableListOf<Float>()

            val job =
                underTest.deviceEntryBackgroundViewAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.2f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(1f))

            values.forEach { assertThat(it).isEqualTo(1f) }

            job.cancel()
        }

    @Test
    fun deviceEntryBackground_noUdfps_noUpdates() =
        runTest(UnconfinedTestDispatcher()) {
            fingerprintPropertyRepository.setProperties(
                sensorId = 0,
                strength = SensorStrength.STRONG,
                sensorType = FingerprintSensorType.REAR,
                sensorLocations = emptyMap(),
            )
            val values = mutableListOf<Float>()

            val job =
                underTest.deviceEntryBackgroundViewAlpha.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.1f))
            repository.sendTransitionStep(step(0.2f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(0) // no updates

            job.cancel()
        }

    @Test
    fun lockscreenTranslationY() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<Float>()

            val pixels = 100
            val job =
                underTest.lockscreenTranslationY(pixels).onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(step(0f, STARTED))
            repository.sendTransitionStep(step(0f))
            repository.sendTransitionStep(step(0.3f))
            repository.sendTransitionStep(step(0.5f))
            repository.sendTransitionStep(step(1f))

            assertThat(values.size).isEqualTo(5)
            values.forEach { assertThat(it).isIn(Range.closed(-100f, 0f)) }

            job.cancel()
        }

    @Test
    fun transitionEnded() =
        runTest(UnconfinedTestDispatcher()) {
            val values = mutableListOf<TransitionStep>()

            val job = underTest.transitionEnded.onEach { values.add(it) }.launchIn(this)

            repository.sendTransitionStep(TransitionStep(DOZING, DREAMING, 0.0f, STARTED))
            repository.sendTransitionStep(TransitionStep(DOZING, DREAMING, 1.0f, FINISHED))

            repository.sendTransitionStep(TransitionStep(DREAMING, LOCKSCREEN, 0.0f, STARTED))
            repository.sendTransitionStep(TransitionStep(DREAMING, LOCKSCREEN, 0.1f, RUNNING))
            repository.sendTransitionStep(TransitionStep(DREAMING, LOCKSCREEN, 1.0f, FINISHED))

            repository.sendTransitionStep(TransitionStep(LOCKSCREEN, DREAMING, 0.0f, STARTED))
            repository.sendTransitionStep(TransitionStep(LOCKSCREEN, DREAMING, 0.5f, RUNNING))
            repository.sendTransitionStep(TransitionStep(LOCKSCREEN, DREAMING, 1.0f, FINISHED))

            repository.sendTransitionStep(TransitionStep(DREAMING, GONE, 0.0f, STARTED))
            repository.sendTransitionStep(TransitionStep(DREAMING, GONE, 0.5f, RUNNING))
            repository.sendTransitionStep(TransitionStep(DREAMING, GONE, 1.0f, CANCELED))

            repository.sendTransitionStep(TransitionStep(DREAMING, AOD, 0.0f, STARTED))
            repository.sendTransitionStep(TransitionStep(DREAMING, AOD, 1.0f, FINISHED))

            assertThat(values.size).isEqualTo(3)
            values.forEach {
                assertThat(it.transitionState == FINISHED || it.transitionState == CANCELED)
                    .isTrue()
            }

            job.cancel()
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
