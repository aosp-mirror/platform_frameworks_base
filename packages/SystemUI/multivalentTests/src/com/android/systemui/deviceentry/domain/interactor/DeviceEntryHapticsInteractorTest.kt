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

package com.android.systemui.deviceentry.domain.interactor

import android.hardware.face.FaceManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.biometrics.authController
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyevent.data.repository.fakeKeyEventRepository
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.configureKeyguardBypass
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.powerRepository
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.phone.dozeScrimController
import com.android.systemui.statusbar.phone.screenOffAnimationController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.kotlin.whenever

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryHapticsInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private lateinit var underTest: DeviceEntryHapticsInteractor

    @Before
    fun setup() {
        if (SceneContainerFlag.isEnabled) {
            whenever(kosmos.authController.isUdfpsFingerDown).thenReturn(false)
            whenever(kosmos.dozeScrimController.isPulsing).thenReturn(false)
            whenever(kosmos.keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(true)
            whenever(kosmos.screenOffAnimationController.isKeyguardShowDelayed()).thenReturn(false)

            // Dependencies for DeviceEntrySourceInteractor#biometricUnlockStateOnKeyguardDismissed
            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            whenever(kosmos.keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(true)

            // Mock authenticationMethodIsSecure true
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )

            kosmos.keyguardBouncerRepository.setAlternateVisible(false)
            kosmos.sceneInteractor.changeScene(Scenes.Lockscreen, "reason")
        } else {
            underTest = kosmos.deviceEntryHapticsInteractor
        }
    }

    @DisableSceneContainer
    @Test
    fun nonPowerButtonFPS_vibrateSuccess() =
        testScope.runTest {
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            enrollFingerprint(FingerprintSensorType.UDFPS_ULTRASONIC)
            runCurrent()
            enterDeviceFromFingerprintUnlockLegacy()
            assertThat(playSuccessHaptic).isNotNull()
        }

    @DisableSceneContainer
    @Test
    fun powerButtonFPS_vibrateSuccess() =
        testScope.runTest {
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            enrollFingerprint(FingerprintSensorType.POWER_BUTTON)
            kosmos.fakeKeyEventRepository.setPowerButtonDown(false)

            // It's been 10 seconds since the last power button wakeup
            setAwakeFromPowerButton()
            advanceTimeBy(10000)
            runCurrent()

            enterDeviceFromFingerprintUnlockLegacy()
            assertThat(playSuccessHaptic).isNotNull()
        }

    @DisableSceneContainer
    @Test
    fun powerButtonFPS_powerDown_doNotVibrateSuccess() =
        testScope.runTest {
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            enrollFingerprint(FingerprintSensorType.POWER_BUTTON)
            kosmos.fakeKeyEventRepository.setPowerButtonDown(true) // power button is currently DOWN

            // It's been 10 seconds since the last power button wakeup
            setAwakeFromPowerButton()
            advanceTimeBy(10000)
            runCurrent()

            enterDeviceFromFingerprintUnlockLegacy()
            assertThat(playSuccessHaptic).isNull()
        }

    @DisableSceneContainer
    @Test
    fun powerButtonFPS_powerButtonRecentlyPressed_doNotVibrateSuccess() =
        testScope.runTest {
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            enrollFingerprint(FingerprintSensorType.POWER_BUTTON)
            kosmos.fakeKeyEventRepository.setPowerButtonDown(false)

            // It's only been 50ms since the last power button wakeup
            setAwakeFromPowerButton()
            advanceTimeBy(50)
            runCurrent()

            enterDeviceFromFingerprintUnlockLegacy()
            assertThat(playSuccessHaptic).isNull()
        }

    @Test
    fun nonPowerButtonFPS_vibrateError() =
        testScope.runTest {
            val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
            enrollFingerprint(FingerprintSensorType.UDFPS_ULTRASONIC)
            runCurrent()
            fingerprintFailure()
            assertThat(playErrorHaptic).isNotNull()
        }

    @Test
    fun nonPowerButtonFPS_coExFaceFailure_doNotVibrateError() =
        testScope.runTest {
            val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
            enrollFingerprint(FingerprintSensorType.UDFPS_ULTRASONIC)
            enrollFace()
            runCurrent()
            faceFailure()
            assertThat(playErrorHaptic).isNull()
        }

    @Test
    fun powerButtonFPS_vibrateError() =
        testScope.runTest {
            val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
            enrollFingerprint(FingerprintSensorType.POWER_BUTTON)
            runCurrent()
            fingerprintFailure()
            assertThat(playErrorHaptic).isNotNull()
        }

    @Test
    fun powerButtonFPS_powerDown_doNotVibrateError() =
        testScope.runTest {
            val playErrorHaptic by collectLastValue(underTest.playErrorHaptic)
            enrollFingerprint(FingerprintSensorType.POWER_BUTTON)
            kosmos.fakeKeyEventRepository.setPowerButtonDown(true)
            runCurrent()
            fingerprintFailure()
            assertThat(playErrorHaptic).isNull()
        }

    @EnableSceneContainer
    @Test
    fun playSuccessHaptic_onDeviceEntryFromUdfps_sceneContainer() =
        testScope.runTest {
            kosmos.configureKeyguardBypass(isBypassAvailable = false)
            underTest = kosmos.deviceEntryHapticsInteractor
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            enrollFingerprint(FingerprintSensorType.UDFPS_ULTRASONIC)
            runCurrent()
            configureDeviceEntryFromBiometricSource(isFpUnlock = true)
            verifyDeviceEntryFromFingerprintAuth()
            assertThat(playSuccessHaptic).isNotNull()
        }

    @EnableSceneContainer
    @Test
    fun playSuccessHaptic_onDeviceEntryFromSfps_sceneContainer() =
        testScope.runTest {
            kosmos.configureKeyguardBypass(isBypassAvailable = false)
            underTest = kosmos.deviceEntryHapticsInteractor
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            enrollFingerprint(FingerprintSensorType.POWER_BUTTON)
            kosmos.fakeKeyEventRepository.setPowerButtonDown(false)

            // It's been 10 seconds since the last power button wakeup
            setAwakeFromPowerButton()
            advanceTimeBy(10000)
            runCurrent()

            configureDeviceEntryFromBiometricSource(isFpUnlock = true)
            verifyDeviceEntryFromFingerprintAuth()
            assertThat(playSuccessHaptic).isNotNull()
        }

    @EnableSceneContainer
    @Test
    fun playSuccessHaptic_onDeviceEntryFromFaceAuth_sceneContainer() =
        testScope.runTest {
            enrollFace()
            kosmos.configureKeyguardBypass(isBypassAvailable = true)
            underTest = kosmos.deviceEntryHapticsInteractor
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            configureDeviceEntryFromBiometricSource(isFaceUnlock = true)
            verifyDeviceEntryFromFaceAuth()
            assertThat(playSuccessHaptic).isNotNull()
        }

    @EnableSceneContainer
    @Test
    fun skipSuccessHaptic_onDeviceEntryFromSfps_whenPowerDown_sceneContainer() =
        testScope.runTest {
            kosmos.configureKeyguardBypass(isBypassAvailable = false)
            underTest = kosmos.deviceEntryHapticsInteractor
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            enrollFingerprint(FingerprintSensorType.POWER_BUTTON)
            // power button is currently DOWN
            kosmos.fakeKeyEventRepository.setPowerButtonDown(true)

            // It's been 10 seconds since the last power button wakeup
            setAwakeFromPowerButton()
            advanceTimeBy(10000)
            runCurrent()

            configureDeviceEntryFromBiometricSource(isFpUnlock = true)
            verifyDeviceEntryFromFingerprintAuth()
            assertThat(playSuccessHaptic).isNull()
        }

    @EnableSceneContainer
    @Test
    fun skipSuccessHaptic_onDeviceEntryFromSfps_whenPowerButtonRecentlyPressed_sceneContainer() =
        testScope.runTest {
            kosmos.configureKeyguardBypass(isBypassAvailable = false)
            underTest = kosmos.deviceEntryHapticsInteractor
            val playSuccessHaptic by collectLastValue(underTest.playSuccessHaptic)
            enrollFingerprint(FingerprintSensorType.POWER_BUTTON)
            kosmos.fakeKeyEventRepository.setPowerButtonDown(false)

            // It's only been 50ms since the last power button wakeup
            setAwakeFromPowerButton()
            advanceTimeBy(50)
            runCurrent()

            configureDeviceEntryFromBiometricSource(isFpUnlock = true)
            verifyDeviceEntryFromFingerprintAuth()
            assertThat(playSuccessHaptic).isNull()
        }

    // Mock dependencies for DeviceEntrySourceInteractor#deviceEntryFromBiometricSource
    private fun configureDeviceEntryFromBiometricSource(
        isFpUnlock: Boolean = false,
        isFaceUnlock: Boolean = false,
    ) {
        // Mock DeviceEntrySourceInteractor#deviceEntryBiometricAuthSuccessState
        if (isFpUnlock) {
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
        }
        if (isFaceUnlock) {
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
                SuccessFaceAuthenticationStatus(
                    FaceManager.AuthenticationResult(null, null, 0, true)
                )
            )

            // Mock DeviceEntrySourceInteractor#faceWakeAndUnlockMode = MODE_UNLOCK_COLLAPSING
            kosmos.sceneInteractor.setTransitionState(
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.Lockscreen)
                )
            )
        }
        underTest = kosmos.deviceEntryHapticsInteractor
    }

    private fun TestScope.verifyDeviceEntryFromFingerprintAuth() {
        val deviceEntryFromBiometricSource by
            collectLastValue(kosmos.deviceEntrySourceInteractor.deviceEntryFromBiometricSource)
        assertThat(deviceEntryFromBiometricSource)
            .isEqualTo(BiometricUnlockSource.FINGERPRINT_SENSOR)
    }

    private fun TestScope.verifyDeviceEntryFromFaceAuth() {
        val deviceEntryFromBiometricSource by
            collectLastValue(kosmos.deviceEntrySourceInteractor.deviceEntryFromBiometricSource)
        assertThat(deviceEntryFromBiometricSource).isEqualTo(BiometricUnlockSource.FACE_SENSOR)
    }

    private fun enterDeviceFromFingerprintUnlockLegacy() {
        kosmos.fakeKeyguardRepository.setBiometricUnlockSource(
            BiometricUnlockSource.FINGERPRINT_SENSOR
        )
        kosmos.fakeKeyguardRepository.setBiometricUnlockState(BiometricUnlockMode.WAKE_AND_UNLOCK)
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

    private fun enrollFingerprint(fingerprintSensorType: FingerprintSensorType?) {
        if (fingerprintSensorType == null) {
            kosmos.biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
        } else {
            kosmos.biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            kosmos.fingerprintPropertyRepository.setProperties(
                sensorId = 0,
                strength = SensorStrength.STRONG,
                sensorType = fingerprintSensorType,
                sensorLocations = mapOf(),
            )
        }
    }

    private fun enrollFace() {
        kosmos.biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
    }

    private fun setAwakeFromPowerButton() {
        kosmos.powerRepository.updateWakefulness(
            WakefulnessState.AWAKE,
            WakeSleepReason.POWER_BUTTON,
            WakeSleepReason.POWER_BUTTON,
            powerButtonLaunchGestureTriggered = false,
        )
    }
}
