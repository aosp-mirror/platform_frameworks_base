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
import com.android.keyguard.logging.BiometricUnlockLogger
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.classifier.FalsingCollector
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryHapticsInteractor
import com.android.systemui.keyevent.data.repository.FakeKeyEventRepository
import com.android.systemui.keyevent.domain.interactor.KeyEventInteractor
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.statusbar.phone.ScreenOffAnimationController
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryHapticsInteractorTest : SysuiTestCase() {

    private lateinit var repository: DeviceEntryHapticsRepository
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository
    private lateinit var keyEventRepository: FakeKeyEventRepository
    private lateinit var powerRepository: FakePowerRepository
    private lateinit var systemClock: FakeSystemClock
    private lateinit var underTest: DeviceEntryHapticsInteractor

    @Before
    fun setUp() {
        repository = DeviceEntryHapticsRepositoryImpl()
        fingerprintPropertyRepository = FakeFingerprintPropertyRepository()
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        keyEventRepository = FakeKeyEventRepository()
        powerRepository = FakePowerRepository()
        systemClock = FakeSystemClock()
        underTest =
            DeviceEntryHapticsInteractor(
                repository = repository,
                fingerprintPropertyRepository = fingerprintPropertyRepository,
                biometricSettingsRepository = biometricSettingsRepository,
                keyEventInteractor = KeyEventInteractor(keyEventRepository),
                powerInteractor =
                    PowerInteractor(
                        powerRepository,
                        mock(FalsingCollector::class.java),
                        mock(ScreenOffAnimationController::class.java),
                        mock(StatusBarStateController::class.java),
                    ),
                systemClock = systemClock,
                logger = mock(BiometricUnlockLogger::class.java),
            )
    }

    @Test
    fun nonPowerButtonFPS_vibrateSuccess() = runTest {
        val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
        setFingerprintSensorType(FingerprintSensorType.UDFPS_ULTRASONIC)
        underTest.vibrateSuccess()
        assertThat(playSuccessHaptic).isTrue()
    }

    @Test
    fun powerButtonFPS_vibrateSuccess() = runTest {
        val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
        setPowerButtonFingerprintProperty()
        setFingerprintEnrolled()
        keyEventRepository.setPowerButtonDown(false)

        // It's been 10 seconds since the last power button wakeup
        setAwakeFromPowerButton()
        runCurrent()
        systemClock.setUptimeMillis(systemClock.uptimeMillis() + 10000)

        underTest.vibrateSuccess()
        assertThat(playSuccessHaptic).isTrue()
    }

    @Test
    fun powerButtonFPS_powerDown_doNotVibrateSuccess() = runTest {
        val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
        setPowerButtonFingerprintProperty()
        setFingerprintEnrolled()
        keyEventRepository.setPowerButtonDown(true) // power button is currently DOWN

        // It's been 10 seconds since the last power button wakeup
        setAwakeFromPowerButton()
        runCurrent()
        systemClock.setUptimeMillis(systemClock.uptimeMillis() + 10000)

        underTest.vibrateSuccess()
        assertThat(playSuccessHaptic).isFalse()
    }

    @Test
    fun powerButtonFPS_powerButtonRecentlyPressed_doNotVibrateSuccess() = runTest {
        val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
        setPowerButtonFingerprintProperty()
        setFingerprintEnrolled()
        keyEventRepository.setPowerButtonDown(false)

        // It's only been 50ms since the last power button wakeup
        setAwakeFromPowerButton()
        runCurrent()
        systemClock.setUptimeMillis(systemClock.uptimeMillis() + 50)

        underTest.vibrateSuccess()
        assertThat(playSuccessHaptic).isFalse()
    }

    @Test
    fun nonPowerButtonFPS_vibrateError() = runTest {
        val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
        setFingerprintSensorType(FingerprintSensorType.UDFPS_ULTRASONIC)
        underTest.vibrateError()
        assertThat(playErrorHaptic).isTrue()
    }

    @Test
    fun powerButtonFPS_vibrateError() = runTest {
        val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
        setPowerButtonFingerprintProperty()
        setFingerprintEnrolled()
        underTest.vibrateError()
        assertThat(playErrorHaptic).isTrue()
    }

    @Test
    fun powerButtonFPS_powerDown_doNotVibrateError() = runTest {
        val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
        setPowerButtonFingerprintProperty()
        setFingerprintEnrolled()
        keyEventRepository.setPowerButtonDown(true)
        underTest.vibrateError()
        assertThat(playErrorHaptic).isFalse()
    }

    private fun setFingerprintSensorType(fingerprintSensorType: FingerprintSensorType) {
        fingerprintPropertyRepository.setProperties(
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
        biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
    }

    private fun setAwakeFromPowerButton() {
        powerRepository.updateWakefulness(
            WakefulnessState.AWAKE,
            WakeSleepReason.POWER_BUTTON,
            WakeSleepReason.POWER_BUTTON,
            powerButtonLaunchGestureTriggered = false,
        )
    }
}
