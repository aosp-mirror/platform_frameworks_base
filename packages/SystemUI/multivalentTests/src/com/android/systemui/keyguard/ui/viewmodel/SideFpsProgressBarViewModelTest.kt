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

import android.content.applicationContext
import android.hardware.biometrics.BiometricFingerprintConstants
import android.os.PowerManager
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.biometrics.domain.interactor.biometricStatusInteractor
import com.android.systemui.biometrics.domain.interactor.displayStateInteractor
import com.android.systemui.biometrics.domain.interactor.sideFpsSensorInteractor
import com.android.systemui.biometrics.fakeFingerprintInteractiveToAuthProvider
import com.android.systemui.biometrics.shared.model.AuthenticationReason
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFingerprintAuthInteractor
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.shared.model.AcquiredFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.applicationCoroutineScope
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.dozeServiceHost
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.spy
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidJUnit4::class)
class SideFpsProgressBarViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private lateinit var underTest: SideFpsProgressBarViewModel
    private val testScope = kosmos.testScope
    private val dozeServiceHost = spy(kosmos.dozeServiceHost)
    private lateinit var mTestableLooper: TestableLooper

    @Before
    fun setup() {
        mTestableLooper = TestableLooper.get(this)
        allowTestableLooperAsMainThread()
    }

    private suspend fun setupRestToUnlockEnabled() {
        mSetFlagsRule.enableFlags(Flags.FLAG_REST_TO_UNLOCK)
        overrideResource(R.bool.config_restToUnlockSupported, true)
        kosmos.fakeFingerprintPropertyRepository.setProperties(
            1,
            SensorStrength.STRONG,
            FingerprintSensorType.POWER_BUTTON,
            mutableMapOf(Pair("sensor", mock()))
        )
        kosmos.fakeFingerprintInteractiveToAuthProvider.enabledForCurrentUser.value = true
        kosmos.fakeKeyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                value = 0.0f,
                transitionState = TransitionState.STARTED
            )
        )
        kosmos.fakeKeyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.AOD,
                value = 1.0f,
                transitionState = TransitionState.FINISHED
            )
        )
        kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
    }

    @Test
    fun whenConfigDisabled_featureIsDisabled() =
        testScope.runTest {
            overrideResource(R.bool.config_restToUnlockSupported, false)
            underTest = createViewModel()
            val enabled by collectLastValue(underTest.isProlongedTouchRequiredForAuthentication)

            assertThat(enabled).isFalse()
        }

    @Test
    fun whenConfigEnabledSensorIsPowerButtonAndSettingsToggleIsEnabled_featureIsEnabled() =
        testScope.runTest {
            overrideResource(R.bool.config_restToUnlockSupported, true)
            underTest = createViewModel()
            val enabled by collectLastValue(underTest.isProlongedTouchRequiredForAuthentication)

            assertThat(enabled).isFalse()
            kosmos.fakeFingerprintPropertyRepository.setProperties(
                1,
                SensorStrength.STRONG,
                FingerprintSensorType.POWER_BUTTON,
                mutableMapOf(Pair("sensor", mock()))
            )
            assertThat(enabled).isFalse()

            kosmos.fakeFingerprintInteractiveToAuthProvider.enabledForCurrentUser.value = true
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)

            runCurrent()
            assertThat(enabled).isTrue()
        }

    @Test
    fun whenFingerprintAcquiredStartsWhenNotDozing_wakesUpDevice() =
        testScope.runTest {
            setupRestToUnlockEnabled()
            underTest = createViewModel()

            kosmos.fakeKeyguardRepository.setIsDozing(false)
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                AcquiredFingerprintAuthenticationStatus(
                    AuthenticationReason.DeviceEntryAuthentication,
                    BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
                )
            )

            runCurrent()

            assertThat(kosmos.fakePowerRepository.lastWakeReason)
                .isEqualTo(PowerManager.WAKE_REASON_BIOMETRIC)
        }

    @Test
    fun whenFingerprintAcquiredStartsWhenDozing_pulsesAod() =
        testScope.runTest {
            setupRestToUnlockEnabled()
            underTest = createViewModel()

            kosmos.fakeKeyguardRepository.setIsDozing(true)
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                AcquiredFingerprintAuthenticationStatus(
                    AuthenticationReason.DeviceEntryAuthentication,
                    BiometricFingerprintConstants.FINGERPRINT_ACQUIRED_START
                )
            )

            runCurrent()

            verify(dozeServiceHost).fireSideFpsAcquisitionStarted()
        }

    private fun createViewModel() =
        SideFpsProgressBarViewModel(
            kosmos.applicationContext,
            kosmos.biometricStatusInteractor,
            kosmos.deviceEntryFingerprintAuthInteractor,
            kosmos.sideFpsSensorInteractor,
            dozeServiceHost,
            kosmos.keyguardInteractor,
            kosmos.displayStateInteractor,
            kosmos.testDispatcher,
            kosmos.applicationCoroutineScope,
            kosmos.powerInteractor,
        )
}
