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

package com.android.systemui.deviceentry.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceEntryHapticsInteractor
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.keyevent.data.repository.fakeKeyEventRepository
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.powerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.testKosmos
import com.android.systemui.util.time.fakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryHapticsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val underTest = kosmos.deviceEntryHapticsInteractor

    @Test
    fun nonPowerButtonFPS_vibrateSuccess() =
        testScope.runTest {
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            setFingerprintSensorType(FingerprintSensorType.UDFPS_ULTRASONIC)
            runCurrent()
            enterDeviceFromBiometricUnlock()
            assertThat(playSuccessHaptic).isNotNull()
        }

    @Test
    fun powerButtonFPS_vibrateSuccess() =
        testScope.runTest {
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            setPowerButtonFingerprintProperty()
            setFingerprintEnrolled()
            kosmos.fakeKeyEventRepository.setPowerButtonDown(false)

            // It's been 10 seconds since the last power button wakeup
            setAwakeFromPowerButton()
            runCurrent()
            kosmos.fakeSystemClock.setUptimeMillis(kosmos.fakeSystemClock.uptimeMillis() + 10000)

            enterDeviceFromBiometricUnlock()
            assertThat(playSuccessHaptic).isNotNull()
        }

    @Test
    fun powerButtonFPS_powerDown_doNotVibrateSuccess() =
        testScope.runTest {
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            setPowerButtonFingerprintProperty()
            setFingerprintEnrolled()
            kosmos.fakeKeyEventRepository.setPowerButtonDown(true) // power button is currently DOWN

            // It's been 10 seconds since the last power button wakeup
            setAwakeFromPowerButton()
            runCurrent()
            kosmos.fakeSystemClock.setUptimeMillis(kosmos.fakeSystemClock.uptimeMillis() + 10000)

            enterDeviceFromBiometricUnlock()
            assertThat(playSuccessHaptic).isNull()
        }

    @Test
    fun powerButtonFPS_powerButtonRecentlyPressed_doNotVibrateSuccess() =
        testScope.runTest {
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            setPowerButtonFingerprintProperty()
            setFingerprintEnrolled()
            kosmos.fakeKeyEventRepository.setPowerButtonDown(false)

            // It's only been 50ms since the last power button wakeup
            setAwakeFromPowerButton()
            runCurrent()
            kosmos.fakeSystemClock.setUptimeMillis(kosmos.fakeSystemClock.uptimeMillis() + 50)

            enterDeviceFromBiometricUnlock()
            assertThat(playSuccessHaptic).isNull()
        }

    @Test
    fun nonPowerButtonFPS_vibrateError() =
        testScope.runTest {
            val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
            setFingerprintSensorType(FingerprintSensorType.UDFPS_ULTRASONIC)
            runCurrent()
            fingerprintFailure()
            assertThat(playErrorHaptic).isNotNull()
        }

    @Test
    fun nonPowerButtonFPS_coExFaceFailure_doNotVibrateError() =
        testScope.runTest {
            val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
            setFingerprintSensorType(FingerprintSensorType.UDFPS_ULTRASONIC)
            coExEnrolledAndEnabled()
            runCurrent()
            faceFailure()
            assertThat(playErrorHaptic).isNull()
        }

    @Test
    fun powerButtonFPS_vibrateError() =
        testScope.runTest {
            val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
            setPowerButtonFingerprintProperty()
            setFingerprintEnrolled()
            runCurrent()
            fingerprintFailure()
            assertThat(playErrorHaptic).isNotNull()
        }

    @Test
    fun powerButtonFPS_powerDown_doNotVibrateError() =
        testScope.runTest {
            val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
            setPowerButtonFingerprintProperty()
            setFingerprintEnrolled()
            kosmos.fakeKeyEventRepository.setPowerButtonDown(true)
            runCurrent()
            fingerprintFailure()
            assertThat(playErrorHaptic).isNull()
        }

    private suspend fun enterDeviceFromBiometricUnlock() {
        kosmos.fakeDeviceEntryRepository.enteringDeviceFromBiometricUnlock(
            BiometricUnlockSource.FINGERPRINT_SENSOR
        )
    }

    private fun fingerprintFailure() {
        kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
            FailFingerprintAuthenticationStatus
        )
    }

    private fun faceFailure() {
        kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
            FailedFaceAuthenticationStatus()
        )
    }

    private fun setFingerprintSensorType(fingerprintSensorType: FingerprintSensorType) {
        kosmos.fingerprintPropertyRepository.setProperties(
            sensorId = 0,
            strength = SensorStrength.STRONG,
            sensorType = fingerprintSensorType,
            sensorLocations = mapOf(),
        )
    }

    private fun setPowerButtonFingerprintProperty() {
        setFingerprintSensorType(FingerprintSensorType.POWER_BUTTON)
    }

    private fun setFingerprintEnrolled() {
        kosmos.biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
    }

    private fun setAwakeFromPowerButton() {
        kosmos.powerRepository.updateWakefulness(
            WakefulnessState.AWAKE,
            WakeSleepReason.POWER_BUTTON,
            WakeSleepReason.POWER_BUTTON,
            powerButtonLaunchGestureTriggered = false,
        )
    }

    private fun coExEnrolledAndEnabled() {
        setFingerprintEnrolled()
        kosmos.biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
    }
}
