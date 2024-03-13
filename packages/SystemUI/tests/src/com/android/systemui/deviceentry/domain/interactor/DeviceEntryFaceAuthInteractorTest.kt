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

import android.content.pm.UserInfo
import android.hardware.biometrics.BiometricFaceConstants
import android.hardware.biometrics.BiometricSourceType
import android.os.PowerManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.keyguard.keyguardUpdateMonitor
import com.android.keyguard.trustManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.CameraInfo
import com.android.systemui.biometrics.data.repository.FaceSensorInfo
import com.android.systemui.biometrics.data.repository.facePropertyRepository
import com.android.systemui.biometrics.shared.model.LockoutMode
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.bouncer.domain.interactor.primaryBouncerInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeFaceWakeUpTriggersConfig
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent
import com.android.systemui.deviceentry.shared.model.ErrorFaceAuthenticationStatus
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.testKosmos
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.eq
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryFaceAuthInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope: TestScope = kosmos.testScope

    private lateinit var underTest: SystemUIDeviceEntryFaceAuthInteractor
    private val bouncerRepository = kosmos.fakeKeyguardBouncerRepository
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor
    private val faceAuthRepository = kosmos.fakeDeviceEntryFaceAuthRepository
    private val fakeUserRepository = kosmos.fakeUserRepository
    private val facePropertyRepository = kosmos.facePropertyRepository
    private val fakeDeviceEntryFingerprintAuthInteractor =
        kosmos.deviceEntryFingerprintAuthInteractor
    private val powerInteractor = kosmos.powerInteractor
    private val fakeBiometricSettingsRepository = kosmos.fakeBiometricSettingsRepository

    private val keyguardUpdateMonitor = kosmos.keyguardUpdateMonitor
    private val faceWakeUpTriggersConfig = kosmos.fakeFaceWakeUpTriggersConfig
    private val trustManager = kosmos.trustManager

    @Before
    fun setup() {
        fakeUserRepository.setUserInfos(listOf(primaryUser, secondaryUser))
        underTest =
            SystemUIDeviceEntryFaceAuthInteractor(
                mContext,
                testScope.backgroundScope,
                kosmos.testDispatcher,
                faceAuthRepository,
                { kosmos.primaryBouncerInteractor },
                kosmos.alternateBouncerInteractor,
                keyguardTransitionInteractor,
                FaceAuthenticationLogger(logcatLogBuffer("faceAuthBuffer")),
                keyguardUpdateMonitor,
                fakeDeviceEntryFingerprintAuthInteractor,
                fakeUserRepository,
                facePropertyRepository,
                faceWakeUpTriggersConfig,
                powerInteractor,
                fakeBiometricSettingsRepository,
                trustManager,
            )
    }

    @Test
    fun faceAuthIsRequestedWhenLockscreenBecomesVisibleFromOffState() =
        testScope.runTest {
            underTest.start()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_LID)
            faceWakeUpTriggersConfig.setTriggerFaceAuthOnWakeUpFrom(
                setOf(WakeSleepReason.LID.powerManagerWakeReason)
            )

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.OFF,
                    KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED
                )
            )

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED, true)
                )
        }

    @Test
    fun whenFaceIsLockedOutAnyAttemptsToTriggerFaceAuthMustProvideLockoutError() =
        testScope.runTest {
            underTest.start()
            val authenticationStatus = collectLastValue(underTest.authenticationStatus)
            faceAuthRepository.setLockedOut(true)

            underTest.onDeviceLifted()

            val outputValue = authenticationStatus()!! as ErrorFaceAuthenticationStatus
            assertThat(outputValue.msgId)
                .isEqualTo(BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT)
            assertThat(outputValue.msg).isEqualTo("Face Unlock unavailable")
            assertThat(faceAuthRepository.runningAuthRequest.value).isNull()
        }

    @Test
    fun faceAuthIsRequestedWhenLockscreenBecomesVisibleFromAodState() =
        testScope.runTest {
            underTest.start()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_LID)
            faceWakeUpTriggersConfig.setTriggerFaceAuthOnWakeUpFrom(
                setOf(WakeSleepReason.LID.powerManagerWakeReason)
            )

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.AOD,
                    KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED
                )
            )

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED, true)
                )
        }

    @Test
    fun faceAuthIsNotRequestedWhenLockscreenBecomesVisibleDueToIgnoredWakeReasons() =
        testScope.runTest {
            underTest.start()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_LIFT)
            faceWakeUpTriggersConfig.setTriggerFaceAuthOnWakeUpFrom(
                setOf(WakeSleepReason.LID.powerManagerWakeReason)
            )

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.DOZING,
                    KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED
                )
            )

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value).isNull()
        }

    @Test
    fun faceAuthIsRequestedWhenLockscreenBecomesVisibleFromDozingState() =
        testScope.runTest {
            underTest.start()

            powerInteractor.setAwakeForTest(reason = PowerManager.WAKE_REASON_LID)
            faceWakeUpTriggersConfig.setTriggerFaceAuthOnWakeUpFrom(
                setOf(WakeSleepReason.LID.powerManagerWakeReason)
            )

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    KeyguardState.DOZING,
                    KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED
                )
            )

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED, true)
                )
        }

    @Test
    fun faceAuthLockedOutStateIsUpdatedAfterUserSwitch() =
        testScope.runTest {
            underTest.start()

            // User switching has started
            fakeUserRepository.setSelectedUserInfo(primaryUser, SelectionStatus.SELECTION_COMPLETE)
            fakeUserRepository.setSelectedUserInfo(
                primaryUser,
                SelectionStatus.SELECTION_IN_PROGRESS
            )
            runCurrent()

            bouncerRepository.setPrimaryShow(true)
            // New user is not locked out.
            facePropertyRepository.setLockoutMode(secondaryUser.id, LockoutMode.NONE)
            fakeUserRepository.setSelectedUserInfo(
                secondaryUser,
                SelectionStatus.SELECTION_COMPLETE
            )
            runCurrent()

            assertThat(faceAuthRepository.isLockedOut.value).isFalse()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value!!.first)
                .isEqualTo(FaceAuthUiEvent.FACE_AUTH_UPDATED_USER_SWITCHING)
            assertThat(faceAuthRepository.runningAuthRequest.value!!.second).isEqualTo(false)
        }

    @Test
    fun faceAuthIsRequestedWhenPrimaryBouncerIsVisible() =
        testScope.runTest {
            underTest.start()

            bouncerRepository.setPrimaryShow(false)
            runCurrent()

            bouncerRepository.setPrimaryShow(true)

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(Pair(FaceAuthUiEvent.FACE_AUTH_UPDATED_PRIMARY_BOUNCER_SHOWN, false))
        }

    @Test
    fun faceAuthIsRequestedWhenAlternateBouncerIsVisible() =
        testScope.runTest {
            underTest.start()

            bouncerRepository.setAlternateVisible(false)
            runCurrent()

            bouncerRepository.setAlternateVisible(true)

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(
                        FaceAuthUiEvent.FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN,
                        false
                    )
                )
        }

    @Test
    fun faceAuthIsRequestedWhenUdfpsSensorTouched() =
        testScope.runTest {
            underTest.start()

            underTest.onUdfpsSensorTouched()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_UDFPS_POINTER_DOWN, false))
        }

    @Test
    fun faceAuthIsRequestedWhenOnAssistantTriggeredOnLockScreen() =
        testScope.runTest {
            underTest.start()

            underTest.onAssistantTriggeredOnLockScreen()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_UPDATED_ASSISTANT_VISIBILITY_CHANGED, true)
                )
        }

    @Test
    fun faceAuthIsRequestedWhenDeviceLifted() =
        testScope.runTest {
            underTest.start()

            underTest.onDeviceLifted()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_PICK_UP_GESTURE_TRIGGERED, true)
                )
        }

    @Test
    fun faceAuthIsRequestedWhenQsExpansionStared() =
        testScope.runTest {
            underTest.start()

            underTest.onQsExpansionStared()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_QS_EXPANDED, true))
        }

    @Test
    fun faceAuthIsRequestedWhenNotificationPanelClicked() =
        testScope.runTest {
            underTest.start()

            underTest.onNotificationPanelClicked()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(
                    Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED, true)
                )
        }

    @Test
    fun faceAuthIsCancelledWhenUserInputOnPrimaryBouncer() =
        testScope.runTest {
            underTest.start()

            underTest.onSwipeUpOnBouncer()

            runCurrent()
            assertThat(faceAuthRepository.isAuthRunning.value).isTrue()

            underTest.onPrimaryBouncerUserInput()

            runCurrent()

            assertThat(faceAuthRepository.isAuthRunning.value).isFalse()
        }

    @Test
    fun faceAuthIsRequestedWhenSwipeUpOnBouncer() =
        testScope.runTest {
            underTest.start()

            underTest.onSwipeUpOnBouncer()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER, false))
        }

    @Test
    fun faceAuthIsRequestedWhenWalletIsLaunchedAndIfFaceAuthIsStrong() =
        testScope.runTest {
            underTest.start()
            facePropertyRepository.setSensorInfo(FaceSensorInfo(1, SensorStrength.STRONG))

            underTest.onWalletLaunched()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(Pair(FaceAuthUiEvent.FACE_AUTH_TRIGGERED_OCCLUDING_APP_REQUESTED, true))
        }

    @Test
    fun faceAuthIsNotTriggeredIfFaceAuthIsWeak() =
        testScope.runTest {
            underTest.start()
            facePropertyRepository.setSensorInfo(FaceSensorInfo(1, SensorStrength.WEAK))

            underTest.onWalletLaunched()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value).isNull()
        }

    @Test
    fun faceAuthIsNotTriggeredIfFaceAuthIsConvenience() =
        testScope.runTest {
            underTest.start()
            facePropertyRepository.setSensorInfo(FaceSensorInfo(1, SensorStrength.CONVENIENCE))

            underTest.onWalletLaunched()

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value).isNull()
        }

    @Test
    fun faceUnlockIsDisabledWhenFpIsLockedOut() =
        testScope.runTest {
            underTest.start()
            fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(faceAuthRepository.isLockedOut.value).isTrue()
        }

    @Test
    fun faceLockoutStateIsResetWheneverFingerprintIsNotLockedOut() =
        testScope.runTest {
            underTest.start()
            fakeUserRepository.setSelectedUserInfo(primaryUser, SelectionStatus.SELECTION_COMPLETE)
            fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(faceAuthRepository.isLockedOut.value).isTrue()
            facePropertyRepository.setLockoutMode(primaryUserId, LockoutMode.NONE)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(false)
            runCurrent()

            assertThat(faceAuthRepository.isLockedOut.value).isFalse()
        }

    @Test
    fun faceLockoutStateIsSetToUsersLockoutStateWheneverFingerprintIsNotLockedOut() =
        testScope.runTest {
            underTest.start()
            fakeUserRepository.setSelectedUserInfo(primaryUser, SelectionStatus.SELECTION_COMPLETE)
            fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(faceAuthRepository.isLockedOut.value).isTrue()
            facePropertyRepository.setLockoutMode(primaryUserId, LockoutMode.TIMED)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(false)
            runCurrent()

            assertThat(faceAuthRepository.isLockedOut.value).isTrue()
        }

    @Test
    fun whenIsAuthenticatedFalse_clearFaceBiometrics() =
        testScope.runTest {
            underTest.start()

            faceAuthRepository.isAuthenticated.value = true
            runCurrent()
            verify(trustManager, never())
                .clearAllBiometricRecognized(eq(BiometricSourceType.FACE), anyInt())

            faceAuthRepository.isAuthenticated.value = false
            runCurrent()

            verify(trustManager).clearAllBiometricRecognized(eq(BiometricSourceType.FACE), anyInt())
        }

    @Test
    fun faceAuthIsRequestedWhenAuthIsRunningWhileCameraInfoChanged() =
        testScope.runTest {
            facePropertyRepository.setCameraIno(null)
            underTest.start()

            faceAuthRepository.requestAuthenticate(
                FaceAuthUiEvent.FACE_AUTH_UPDATED_KEYGUARD_VISIBILITY_CHANGED,
                true
            )
            facePropertyRepository.setCameraIno(CameraInfo("0", "1", null))

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value)
                .isEqualTo(Pair(FaceAuthUiEvent.FACE_AUTH_CAMERA_AVAILABLE_CHANGED, true))
        }

    @Test
    fun faceAuthIsNotRequestedWhenNoAuthRunningWhileCameraInfoChanged() =
        testScope.runTest {
            facePropertyRepository.setCameraIno(null)
            underTest.start()

            facePropertyRepository.setCameraIno(CameraInfo("0", "1", null))

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value).isNull()
        }

    @Test
    fun faceAuthIsNotRequestedWhenAuthIsRunningWhileCameraInfoIsNull() =
        testScope.runTest {
            facePropertyRepository.setCameraIno(null)
            underTest.start()

            facePropertyRepository.setCameraIno(null)

            runCurrent()
            assertThat(faceAuthRepository.runningAuthRequest.value).isNull()
        }

    @Test
    fun lockedOut_providesSameValueFromRepository() =
        testScope.runTest {
            assertThat(underTest.lockedOut).isSameInstanceAs(faceAuthRepository.isLockedOut)
        }

    @Test
    fun authenticated_providesSameValueFromRepository() =
        testScope.runTest {
            assertThat(underTest.authenticated).isSameInstanceAs(faceAuthRepository.isAuthenticated)
        }

    companion object {
        private const val primaryUserId = 1
        private val primaryUser = UserInfo(primaryUserId, "test user", UserInfo.FLAG_PRIMARY)

        private val secondaryUser = UserInfo(2, "secondary user", 0)
    }
}
