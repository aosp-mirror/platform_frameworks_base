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
import com.android.compose.animation.scene.SceneKey
import com.android.keyguard.keyguardUpdateMonitor
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.biometrics.authController
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.shared.model.FaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus
import com.android.systemui.flags.DisableSceneContainer
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.configureKeyguardBypass
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.keyguardBypassRepository
import com.android.systemui.keyguard.data.repository.keyguardOcclusionRepository
import com.android.systemui.keyguard.data.repository.verifyCallback
import com.android.systemui.keyguard.shared.model.BiometricUnlockMode
import com.android.systemui.keyguard.shared.model.BiometricUnlockSource
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.phone.dozeScrimController
import com.android.systemui.statusbar.phone.screenOffAnimationController
import com.android.systemui.statusbar.policy.DevicePostureController.DEVICE_POSTURE_OPENED
import com.android.systemui.statusbar.policy.devicePostureController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
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
class DeviceEntrySourceInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private lateinit var underTest: DeviceEntrySourceInteractor

    @Before
    fun setup() {
        if (SceneContainerFlag.isEnabled) {
            whenever(kosmos.authController.isUdfpsFingerDown).thenReturn(false)
            whenever(kosmos.dozeScrimController.isPulsing).thenReturn(false)
            whenever(kosmos.keyguardUpdateMonitor.isUnlockingWithBiometricAllowed(anyBoolean()))
                .thenReturn(true)
            whenever(kosmos.screenOffAnimationController.isKeyguardShowDelayed()).thenReturn(false)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
        } else {
            underTest = kosmos.deviceEntrySourceInteractor
        }
    }

    @DisableSceneContainer
    @Test
    fun deviceEntryFromFaceUnlock() =
        testScope.runTest {
            val deviceEntryFromBiometricAuthentication by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            kosmos.fakeKeyguardRepository.setBiometricUnlockState(
                BiometricUnlockMode.WAKE_AND_UNLOCK,
                BiometricUnlockSource.FACE_SENSOR,
            )
            runCurrent()

            assertThat(deviceEntryFromBiometricAuthentication)
                .isEqualTo(BiometricUnlockSource.FACE_SENSOR)
        }

    @DisableSceneContainer
    @Test
    fun deviceEntryFromFingerprintUnlock() =
        testScope.runTest {
            val deviceEntryFromBiometricAuthentication by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            kosmos.fakeKeyguardRepository.setBiometricUnlockState(
                BiometricUnlockMode.WAKE_AND_UNLOCK,
                BiometricUnlockSource.FINGERPRINT_SENSOR,
            )
            runCurrent()

            assertThat(deviceEntryFromBiometricAuthentication)
                .isEqualTo(BiometricUnlockSource.FINGERPRINT_SENSOR)
        }

    @DisableSceneContainer
    @Test
    fun noDeviceEntry() =
        testScope.runTest {
            val deviceEntryFromBiometricAuthentication by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            kosmos.fakeKeyguardRepository.setBiometricUnlockState(
                BiometricUnlockMode.ONLY_WAKE, // doesn't dismiss keyguard:
                BiometricUnlockSource.FINGERPRINT_SENSOR,
            )
            runCurrent()

            assertThat(deviceEntryFromBiometricAuthentication).isNull()
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFingerprintUnlockOnLockScreen_sceneContainerEnabled() =
        testScope.runTest {
            underTest = kosmos.deviceEntrySourceInteractor
            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            configureDeviceEntryBiometricAuthSuccessState(isFingerprintAuth = true)
            configureBiometricUnlockState(
                alternateBouncerVisible = false,
                sceneKey = Scenes.Lockscreen,
            )
            runCurrent()

            kosmos.configureKeyguardBypass(isBypassAvailable = true)
            underTest = kosmos.deviceEntrySourceInteractor

            assertThat(deviceEntryFromBiometricSource)
                .isEqualTo(BiometricUnlockSource.FINGERPRINT_SENSOR)
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFingerprintUnlockOnAod_sceneContainerEnabled() =
        testScope.runTest {
            underTest = kosmos.deviceEntrySourceInteractor
            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(false)
            configureDeviceEntryBiometricAuthSuccessState(isFingerprintAuth = true)
            configureBiometricUnlockState(alternateBouncerVisible = false, sceneKey = Scenes.Dream)
            runCurrent()

            assertThat(deviceEntryFromBiometricSource)
                .isEqualTo(BiometricUnlockSource.FINGERPRINT_SENSOR)
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFingerprintUnlockOnBouncer_sceneContainerEnabled() =
        testScope.runTest {
            underTest = kosmos.deviceEntrySourceInteractor
            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            configureDeviceEntryBiometricAuthSuccessState(isFingerprintAuth = true)
            configureBiometricUnlockState(
                alternateBouncerVisible = false,
                sceneKey = Scenes.Bouncer,
            )
            runCurrent()

            assertThat(deviceEntryFromBiometricSource)
                .isEqualTo(BiometricUnlockSource.FINGERPRINT_SENSOR)
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFingerprintUnlockOnShade_sceneContainerEnabled() =
        testScope.runTest {
            underTest = kosmos.deviceEntrySourceInteractor
            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            configureDeviceEntryBiometricAuthSuccessState(isFingerprintAuth = true)
            configureBiometricUnlockState(
                alternateBouncerVisible = false,
                sceneKey = Scenes.Lockscreen,
            )
            runCurrent()

            assertThat(deviceEntryFromBiometricSource)
                .isEqualTo(BiometricUnlockSource.FINGERPRINT_SENSOR)
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFingerprintUnlockOnAlternateBouncer_sceneContainerEnabled() =
        testScope.runTest {
            underTest = kosmos.deviceEntrySourceInteractor
            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            configureDeviceEntryBiometricAuthSuccessState(isFingerprintAuth = true)
            configureBiometricUnlockState(
                alternateBouncerVisible = true,
                sceneKey = Scenes.Lockscreen,
            )
            runCurrent()

            assertThat(deviceEntryFromBiometricSource)
                .isEqualTo(BiometricUnlockSource.FINGERPRINT_SENSOR)
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFaceUnlockOnLockScreen_bypassAvailable_sceneContainerEnabled() =
        testScope.runTest {
            kosmos.configureKeyguardBypass(isBypassAvailable = true)
            underTest = kosmos.deviceEntrySourceInteractor

            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            configureDeviceEntryBiometricAuthSuccessState(isFaceAuth = true)
            configureBiometricUnlockState(
                alternateBouncerVisible = false,
                sceneKey = Scenes.Lockscreen,
            )
            runCurrent()

            assertThat(deviceEntryFromBiometricSource).isEqualTo(BiometricUnlockSource.FACE_SENSOR)
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFaceUnlockOnLockScreen_bypassDisabled_sceneContainerEnabled() =
        testScope.runTest {
            kosmos.configureKeyguardBypass(isBypassAvailable = false)
            underTest = kosmos.deviceEntrySourceInteractor

            collectLastValue(kosmos.keyguardBypassRepository.isBypassAvailable)
            runCurrent()

            val postureControllerCallback = kosmos.devicePostureController.verifyCallback()
            postureControllerCallback.onPostureChanged(DEVICE_POSTURE_OPENED)

            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            configureDeviceEntryBiometricAuthSuccessState(isFaceAuth = true)
            configureBiometricUnlockState(
                alternateBouncerVisible = false,
                sceneKey = Scenes.Lockscreen,
            )
            runCurrent()

            // MODE_NONE does not dismiss keyguard
            assertThat(deviceEntryFromBiometricSource).isNull()
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFaceUnlockOnBouncer_sceneContainerEnabled() =
        testScope.runTest {
            kosmos.configureKeyguardBypass(isBypassAvailable = true)
            underTest = kosmos.deviceEntrySourceInteractor
            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            configureDeviceEntryBiometricAuthSuccessState(isFaceAuth = true)
            configureBiometricUnlockState(
                alternateBouncerVisible = false,
                sceneKey = Scenes.Bouncer,
            )
            runCurrent()

            assertThat(deviceEntryFromBiometricSource).isEqualTo(BiometricUnlockSource.FACE_SENSOR)
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFaceUnlockOnShade_bypassAvailable_sceneContainerEnabled() =
        testScope.runTest {
            kosmos.configureKeyguardBypass(isBypassAvailable = true)
            underTest = kosmos.deviceEntrySourceInteractor
            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            configureDeviceEntryBiometricAuthSuccessState(isFaceAuth = true)
            configureBiometricUnlockState(alternateBouncerVisible = false, sceneKey = Scenes.Shade)
            runCurrent()

            // MODE_NONE does not dismiss keyguard
            assertThat(deviceEntryFromBiometricSource).isNull()
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFaceUnlockOnShade_bypassDisabled_sceneContainerEnabled() =
        testScope.runTest {
            kosmos.configureKeyguardBypass(isBypassAvailable = false)
            underTest = kosmos.deviceEntrySourceInteractor
            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            configureDeviceEntryBiometricAuthSuccessState(isFaceAuth = true)
            configureBiometricUnlockState(
                alternateBouncerVisible = false,
                sceneKey = Scenes.Lockscreen,
            )
            runCurrent()

            assertThat(deviceEntryFromBiometricSource).isNull()
        }

    @EnableSceneContainer
    @Test
    fun deviceEntryFromFaceUnlockOnAlternateBouncer_sceneContainerEnabled() =
        testScope.runTest {
            kosmos.configureKeyguardBypass(isBypassAvailable = true)
            underTest = kosmos.deviceEntrySourceInteractor
            val deviceEntryFromBiometricSource by
                collectLastValue(underTest.deviceEntryFromBiometricSource)

            whenever(kosmos.keyguardUpdateMonitor.isDeviceInteractive).thenReturn(true)
            kosmos.keyguardOcclusionRepository.setShowWhenLockedActivityInfo(onTop = true)
            configureDeviceEntryBiometricAuthSuccessState(isFaceAuth = true)
            configureBiometricUnlockState(
                alternateBouncerVisible = true,
                sceneKey = Scenes.Lockscreen,
            )
            runCurrent()

            assertThat(deviceEntryFromBiometricSource).isEqualTo(BiometricUnlockSource.FACE_SENSOR)
        }

    private fun configureDeviceEntryBiometricAuthSuccessState(
        isFingerprintAuth: Boolean = false,
        isFaceAuth: Boolean = false,
    ) {
        if (isFingerprintAuth) {
            val successStatus = SuccessFingerprintAuthenticationStatus(0, true)
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(successStatus)
        }

        if (isFaceAuth) {
            val successStatus: FaceAuthenticationStatus =
                SuccessFaceAuthenticationStatus(
                    FaceManager.AuthenticationResult(null, null, 0, true)
                )
            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(successStatus)
        }
    }

    private fun configureBiometricUnlockState(
        alternateBouncerVisible: Boolean,
        sceneKey: SceneKey,
    ) {
        kosmos.keyguardBouncerRepository.setAlternateVisible(alternateBouncerVisible)
        kosmos.sceneInteractor.changeScene(sceneKey, "reason")
        kosmos.sceneInteractor.setTransitionState(
            MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(sceneKey))
        )
    }
}
