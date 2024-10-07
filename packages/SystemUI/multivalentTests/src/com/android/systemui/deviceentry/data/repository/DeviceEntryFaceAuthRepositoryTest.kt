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

package com.android.systemui.deviceentry.data.repository

import android.app.StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP
import android.app.StatusBarManager.SESSION_KEYGUARD
import android.content.pm.UserInfo
import android.content.pm.UserInfo.FLAG_PRIMARY
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_CANCELED
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT
import android.hardware.biometrics.ComponentInfoInternal
import android.hardware.face.FaceAuthenticateOptions
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_NOTIFICATION_PANEL_CLICKED
import android.hardware.face.FaceAuthenticateOptions.AUTHENTICATE_REASON_SWIPE_UP_ON_BOUNCER
import android.hardware.face.FaceManager
import android.hardware.face.FaceSensorProperties
import android.hardware.face.FaceSensorPropertiesInternal
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.internal.logging.InstanceId.fakeInstanceId
import com.android.internal.logging.UiEventLogger
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.domain.interactor.displayStateInteractor
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.concurrency.fakeExecutor
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent.FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent.FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED
import com.android.systemui.deviceentry.shared.FaceAuthUiEvent.FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER
import com.android.systemui.deviceentry.shared.model.ErrorFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FaceDetectionStatus
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus
import com.android.systemui.display.data.repository.displayRepository
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.keyguard.data.repository.BiometricType
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.domain.interactor.keyguardInteractor
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testDispatcher
import com.android.systemui.kosmos.testScope
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.log.SessionTracker
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.log.table.logcatTableLogBuffer
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.testKosmos
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.KotlinArgumentCaptor
import com.android.systemui.util.mockito.captureMany
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.isNull
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryFaceAuthRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private lateinit var underTest: DeviceEntryFaceAuthRepositoryImpl

    @Mock private lateinit var faceManager: FaceManager
    @Mock private lateinit var bypassController: KeyguardBypassController
    @Mock private lateinit var sessionTracker: SessionTracker
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var dumpManager: DumpManager

    @Captor
    private lateinit var authenticationCallback: ArgumentCaptor<FaceManager.AuthenticationCallback>

    @Captor
    private lateinit var detectionCallback: ArgumentCaptor<FaceManager.FaceDetectionCallback>
    @Captor private lateinit var faceAuthenticateOptions: ArgumentCaptor<FaceAuthenticateOptions>
    @Captor private lateinit var cancellationSignal: ArgumentCaptor<CancellationSignal>

    private lateinit var bypassStateChangedListener:
        KotlinArgumentCaptor<KeyguardBypassController.OnBypassStateChangedListener>

    @Captor
    private lateinit var faceLockoutResetCallback: ArgumentCaptor<FaceManager.LockoutResetCallback>
    private val testDispatcher by lazy { kosmos.testDispatcher }

    private val keyguardTransitionRepository by lazy { kosmos.fakeKeyguardTransitionRepository }
    private val testScope by lazy { kosmos.testScope }
    private val fakeUserRepository by lazy { kosmos.fakeUserRepository }
    private val fakeExecutor by lazy { kosmos.fakeExecutor }
    private lateinit var authStatus: FlowValue<FaceAuthenticationStatus?>
    private lateinit var detectStatus: FlowValue<FaceDetectionStatus?>
    private lateinit var authRunning: FlowValue<Boolean?>
    private lateinit var bypassEnabled: FlowValue<Boolean?>
    private lateinit var lockedOut: FlowValue<Boolean?>
    private lateinit var canFaceAuthRun: FlowValue<Boolean?>
    private lateinit var authenticated: FlowValue<Boolean?>
    private val biometricSettingsRepository by lazy { kosmos.fakeBiometricSettingsRepository }
    private val deviceEntryFingerprintAuthRepository by lazy {
        kosmos.fakeDeviceEntryFingerprintAuthRepository
    }
    private val trustRepository by lazy { kosmos.fakeTrustRepository }
    private val keyguardRepository by lazy { kosmos.fakeKeyguardRepository }
    private val powerInteractor by lazy { kosmos.powerInteractor }
    private val keyguardInteractor by lazy { kosmos.keyguardInteractor }
    private val alternateBouncerInteractor by lazy { kosmos.alternateBouncerInteractor }
    private val displayStateInteractor by lazy { kosmos.displayStateInteractor }
    private val bouncerRepository by lazy { kosmos.fakeKeyguardBouncerRepository }
    private val displayRepository by lazy { kosmos.displayRepository }
    private val keyguardTransitionInteractor by lazy { kosmos.keyguardTransitionInteractor }
    private lateinit var featureFlags: FakeFeatureFlags

    private var wasAuthCancelled = false
    private var wasDetectCancelled = false

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        fakeUserRepository.setUserInfos(listOf(primaryUser, secondaryUser))
        featureFlags = FakeFeatureFlags()

        bypassStateChangedListener =
            KotlinArgumentCaptor(KeyguardBypassController.OnBypassStateChangedListener::class.java)
        whenever(sessionTracker.getSessionId(SESSION_KEYGUARD)).thenReturn(keyguardSessionId)
        whenever(faceManager.sensorPropertiesInternal)
            .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = true)))
        whenever(bypassController.bypassEnabled).thenReturn(true)
        underTest = createDeviceEntryFaceAuthRepositoryImpl(faceManager, bypassController)

        if (!SceneContainerFlag.isEnabled) {
            mSetFlagsRule.disableFlags(
                AConfigFlags.FLAG_KEYGUARD_WM_STATE_REFACTOR,
            )
        }
    }

    private fun createDeviceEntryFaceAuthRepositoryImpl(
        fmOverride: FaceManager? = faceManager,
        bypassControllerOverride: KeyguardBypassController? = bypassController
    ): DeviceEntryFaceAuthRepositoryImpl {
        val faceAuthBuffer = logcatTableLogBuffer(kosmos, "face auth")
        val faceDetectBuffer = logcatTableLogBuffer(kosmos, "face detect")

        return DeviceEntryFaceAuthRepositoryImpl(
            mContext,
            fmOverride,
            fakeUserRepository,
            bypassControllerOverride,
            testScope.backgroundScope,
            testDispatcher,
            testDispatcher,
            fakeExecutor,
            sessionTracker,
            uiEventLogger,
            FaceAuthenticationLogger(logcatLogBuffer("DeviceEntryFaceAuthRepositoryLog")),
            biometricSettingsRepository,
            deviceEntryFingerprintAuthRepository,
            keyguardRepository,
            powerInteractor,
            keyguardInteractor,
            alternateBouncerInteractor,
            { kosmos.sceneInteractor },
            faceDetectBuffer,
            faceAuthBuffer,
            keyguardTransitionInteractor,
            displayStateInteractor,
            dumpManager,
        )
    }

    @Test
    fun faceAuthRunsAndProvidesAuthStatusUpdates() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER.extraInfo = 10
            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()
            uiEventIsLogged(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)

            assertThat(authRunning()).isTrue()

            val successResult = successResult()
            authenticationCallback.value.onAuthenticationSucceeded(successResult)

            val response = authStatus() as SuccessFaceAuthenticationStatus
            assertThat(response.successResult).isEqualTo(successResult)
            assertThat(authenticated()).isTrue()
            assertThat(authRunning()).isFalse()
            assertThat(canFaceAuthRun()).isFalse()
        }

    private fun uiEventIsLogged(faceAuthUiEvent: FaceAuthUiEvent) {
        verify(uiEventLogger)
            .logWithInstanceIdAndPosition(
                faceAuthUiEvent,
                0,
                null,
                keyguardSessionId,
                faceAuthUiEvent.extraInfo
            )
    }

    @Test
    fun faceAuthDoesNotRunWhileItIsAlreadyRunning() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()
            clearInvocations(faceManager)
            clearInvocations(uiEventLogger)

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            verifyNoMoreInteractions(faceManager)
            verifyNoMoreInteractions(uiEventLogger)
        }

    @Test
    fun faceLockoutStatusIsPropagated() =
        testScope.runTest {
            initCollectors()
            fakeExecutor.runAllReady()
            verify(faceManager).addLockoutResetCallback(faceLockoutResetCallback.capture())
            allPreconditionsToRunFaceAuthAreTrue()

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()

            authenticationCallback.value.onAuthenticationError(
                FACE_ERROR_LOCKOUT_PERMANENT,
                "face locked out"
            )

            assertThat(lockedOut()).isTrue()

            faceLockoutResetCallback.value.onLockoutReset(0)
            assertThat(lockedOut()).isFalse()
        }

    @Test
    fun faceDetectionSupportIsTheCorrectValue() =
        testScope.runTest {
            assertThat(
                    createDeviceEntryFaceAuthRepositoryImpl(fmOverride = null).isDetectionSupported
                )
                .isFalse()

            whenever(faceManager.sensorPropertiesInternal).thenReturn(listOf())
            assertThat(createDeviceEntryFaceAuthRepositoryImpl().isDetectionSupported).isFalse()

            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = false)))
            assertThat(createDeviceEntryFaceAuthRepositoryImpl().isDetectionSupported).isFalse()

            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(
                    listOf(
                        createFaceSensorProperties(supportsFaceDetection = false),
                        createFaceSensorProperties(supportsFaceDetection = true)
                    )
                )
            assertThat(createDeviceEntryFaceAuthRepositoryImpl().isDetectionSupported).isFalse()

            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(
                    listOf(
                        createFaceSensorProperties(supportsFaceDetection = true),
                        createFaceSensorProperties(supportsFaceDetection = false)
                    )
                )
            assertThat(createDeviceEntryFaceAuthRepositoryImpl().isDetectionSupported).isTrue()
        }

    @Test
    fun cancelStopsFaceAuthentication() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()

            var wasAuthCancelled = false
            cancellationSignal.value.setOnCancelListener { wasAuthCancelled = true }

            underTest.cancel()
            assertThat(wasAuthCancelled).isTrue()
            assertThat(authRunning()).isFalse()
        }

    @Test
    fun cancelInvokedWithoutFaceAuthRunningIsANoop() = testScope.runTest { underTest.cancel() }

    @Test
    fun faceDetectionRunsAndPropagatesDetectionStatus() =
        testScope.runTest {
            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = true)))
            underTest = createDeviceEntryFaceAuthRepositoryImpl()
            initCollectors()

            underTest.detect(FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED)
            faceDetectIsCalled()

            detectionCallback.value.onFaceDetected(1, 1, true)

            val status = detectStatus()!!
            assertThat(status.sensorId).isEqualTo(1)
            assertThat(status.userId).isEqualTo(1)
            assertThat(status.isStrongBiometric).isEqualTo(true)
            assertThat(faceAuthenticateOptions.value.authenticateReason)
                .isEqualTo(AUTHENTICATE_REASON_NOTIFICATION_PANEL_CLICKED)
        }

    @Test
    fun faceDetectDoesNotRunIfDetectionIsNotSupported() =
        testScope.runTest {
            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = false)))
            underTest = createDeviceEntryFaceAuthRepositoryImpl()
            initCollectors()
            clearInvocations(faceManager)

            underTest.detect(FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED)

            verify(faceManager, never())
                .detectFace(any(), any(), any(FaceAuthenticateOptions::class.java))
        }

    @Test
    fun faceAuthShouldWaitAndRunIfTriggeredWhileCancelling() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()

            // Enter cancelling state
            underTest.cancel()
            clearInvocations(faceManager)

            // Auth is while cancelling.
            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN)
            // Auth is not started
            verifyNoMoreInteractions(faceManager)

            // Auth is done cancelling.
            authenticationCallback.value.onAuthenticationError(
                FACE_ERROR_CANCELED,
                "First auth attempt cancellation completed"
            )
            val value = authStatus() as ErrorFaceAuthenticationStatus
            assertThat(value.msgId).isEqualTo(FACE_ERROR_CANCELED)
            assertThat(value.msg).isEqualTo("First auth attempt cancellation completed")

            faceAuthenticateIsCalled()
            uiEventIsLogged(FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN)
            assertThat(faceAuthenticateOptions.value.authenticateReason)
                .isEqualTo(AUTHENTICATE_REASON_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN)
        }

    @Test
    fun faceAuthAutoCancelsAfterDefaultCancellationTimeout() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()

            clearInvocations(faceManager)
            underTest.cancel()
            advanceTimeBy(DeviceEntryFaceAuthRepositoryImpl.DEFAULT_CANCEL_SIGNAL_TIMEOUT + 1)

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()
            assertThat(faceAuthenticateOptions.value.authenticateReason)
                .isEqualTo(AUTHENTICATE_REASON_SWIPE_UP_ON_BOUNCER)
        }

    @Test
    fun multipleCancelCallsShouldNotCauseMultipleCancellationStatusBeingEmitted() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()
            val emittedValues by collectValues(underTest.authenticationStatus)

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            underTest.cancel()
            advanceTimeBy(100)
            underTest.cancel()

            advanceTimeBy(DeviceEntryFaceAuthRepositoryImpl.DEFAULT_CANCEL_SIGNAL_TIMEOUT)
            runCurrent()
            advanceTimeBy(DeviceEntryFaceAuthRepositoryImpl.DEFAULT_CANCEL_SIGNAL_TIMEOUT)
            runCurrent()

            assertThat(emittedValues.size).isEqualTo(1)
            assertThat(emittedValues.first())
                .isInstanceOf(ErrorFaceAuthenticationStatus::class.java)
            assertThat((emittedValues.first() as ErrorFaceAuthenticationStatus).msgId).isEqualTo(-1)
        }

    @Test
    fun dumpDoesNotErrorOutWhenFaceManagerOrBypassControllerIsNull() =
        testScope.runTest {
            fakeUserRepository.setSelectedUserInfo(primaryUser)
            underTest.dump(PrintWriter(StringWriter()), emptyArray())

            underTest =
                createDeviceEntryFaceAuthRepositoryImpl(
                    fmOverride = null,
                    bypassControllerOverride = null
                )
            fakeUserRepository.setSelectedUserInfo(primaryUser)

            underTest.dump(PrintWriter(StringWriter()), emptyArray())
        }

    @Test
    fun authenticateDoesNotRunIfFaceIsNotUsuallyAllowed() =
        testScope.runTest {
            testGatingCheckForFaceAuth {
                biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            }
        }

    @Test
    fun authenticateDoesNotRunIfUserIsInLockdown() =
        testScope.runTest {
            testGatingCheckForFaceAuth { biometricSettingsRepository.setIsUserInLockdown(true) }
        }

    @Test
    fun authenticateDoesNotRunIfUserSwitchingIsCurrentlyInProgress() =
        testScope.runTest {
            testGatingCheckForFaceAuth {
                fakeUserRepository.setSelectedUserInfo(
                    primaryUser,
                    SelectionStatus.SELECTION_IN_PROGRESS
                )
            }
        }

    @Test
    fun detectDoesNotRunIfUserSwitchingIsCurrentlyInProgress() =
        testScope.runTest {
            testGatingCheckForDetect {
                fakeUserRepository.setSelectedUserInfo(
                    userInfo = primaryUser,
                    selectionStatus = SelectionStatus.SELECTION_IN_PROGRESS
                )
            }
        }

    @Test
    fun authenticateDoesNotRunIfKeyguardIsNotShowing() =
        testScope.runTest {
            testGatingCheckForFaceAuth { keyguardRepository.setKeyguardShowing(false) }
        }

    @Test
    fun detectDoesNotRunIfKeyguardIsNotShowing() =
        testScope.runTest {
            testGatingCheckForDetect { keyguardRepository.setKeyguardShowing(false) }
        }

    @Test
    fun authenticateDoesNotRunWhenFaceIsDisabled() =
        testScope.runTest { testGatingCheckForFaceAuth { underTest.setLockedOut(true) } }

    @Test
    fun authenticateDoesNotRunWhenKeyguardIsGoingAway() =
        testScope.runTest {
            testGatingCheckForFaceAuth { keyguardRepository.setKeyguardGoingAway(true) }
        }

    @Test
    @EnableSceneContainer
    fun withSceneContainerEnabled_authenticateDoesNotRunWhenKeyguardIsGoingAway() =
        testScope.runTest {
            testGatingCheckForFaceAuth(sceneContainerEnabled = true) {
                kosmos.sceneInteractor.setTransitionState(
                    MutableStateFlow(
                        ObservableTransitionState.Transition(
                            fromScene = Scenes.Bouncer,
                            toScene = Scenes.Gone,
                            currentScene = flowOf(Scenes.Bouncer),
                            progress = MutableStateFlow(0.2f),
                            isInitiatedByUserInput = true,
                            isUserInputOngoing = flowOf(false),
                        )
                    )
                )
                runCurrent()
            }
        }

    @Test
    @EnableSceneContainer
    fun withSceneContainerEnabled_authenticateDoesNotRunWhenLockscreenIsGone() =
        testScope.runTest {
            testGatingCheckForFaceAuth(sceneContainerEnabled = true) {
                kosmos.sceneInteractor.setTransitionState(
                    MutableStateFlow(ObservableTransitionState.Idle(Scenes.Gone))
                )
                runCurrent()
            }
        }

    @Test
    fun authenticateDoesNotRunWhenDeviceIsGoingToSleep() =
        testScope.runTest {
            testGatingCheckForFaceAuth {
                powerInteractor.setAsleepForTest()
                keyguardTransitionRepository.sendTransitionStep(
                    TransitionStep(
                        transitionState = TransitionState.STARTED,
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.AOD,
                    )
                )
                runCurrent()
            }
        }

    @Test
    fun authenticateDoesNotRunWhenSecureCameraIsActive() =
        testScope.runTest {
            testGatingCheckForFaceAuth {
                bouncerRepository.setAlternateVisible(false)
                // Keyguard is occluded when secure camera is active.
                keyguardRepository.setKeyguardOccluded(true)
                keyguardInteractor.onCameraLaunchDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
            }
        }

    @Test
    fun authenticateRunsWhenSecureCameraIsActiveIfBouncerIsShowing() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()
            bouncerRepository.setAlternateVisible(false)
            bouncerRepository.setPrimaryShow(false)

            assertThat(canFaceAuthRun()).isTrue()

            // launch secure camera
            keyguardInteractor.onCameraLaunchDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
            keyguardRepository.setKeyguardOccluded(true)
            runCurrent()
            assertThat(canFaceAuthRun()).isFalse()

            // but bouncer is shown after that.
            bouncerRepository.setPrimaryShow(true)
            assertThat(canFaceAuthRun()).isTrue()
        }

    @Test
    @EnableSceneContainer
    fun withSceneContainer_authenticateRunsWhenSecureCameraIsActiveIfBouncerIsShowing() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue(sceneContainerEnabled = true)
            bouncerRepository.setAlternateVisible(false)

            // launch secure camera
            keyguardInteractor.onCameraLaunchDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
            keyguardRepository.setKeyguardOccluded(true)
            kosmos.sceneInteractor.snapToScene(Scenes.Lockscreen, "for-test")
            runCurrent()
            assertThat(canFaceAuthRun()).isFalse()

            // but bouncer is shown after that.
            kosmos.sceneInteractor.changeScene(Scenes.Bouncer, "for-test")
            kosmos.sceneInteractor.setTransitionState(
                MutableStateFlow(ObservableTransitionState.Idle(Scenes.Bouncer))
            )
            runCurrent()

            assertThat(canFaceAuthRun()).isTrue()
        }

    @Test
    fun authenticateDoesNotRunOnUnsupportedPosture() =
        testScope.runTest {
            testGatingCheckForFaceAuth {
                biometricSettingsRepository.setIsFaceAuthSupportedInCurrentPosture(false)
            }
        }

    @Test
    fun authenticateFallbacksToDetectionWhenItCannotRun() =
        testScope.runTest {
            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = true)))
            whenever(bypassController.bypassEnabled).thenReturn(true)
            underTest = createDeviceEntryFaceAuthRepositoryImpl()
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            // Flip one precondition to false.
            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(false)
            assertThat(canFaceAuthRun()).isFalse()
            underTest.requestAuthenticate(
                FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER,
                fallbackToDetection = true
            )
            faceAuthenticateIsNotCalled()

            faceDetectIsCalled()
        }

    @Test
    fun authenticateFallbacksToDetectionWhenKeyguardIsAlreadyDismissible() =
        testScope.runTest {
            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = true)))
            whenever(bypassController.bypassEnabled).thenReturn(true)
            underTest = createDeviceEntryFaceAuthRepositoryImpl()
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            keyguardRepository.setKeyguardDismissible(true)
            assertThat(canFaceAuthRun()).isFalse()
            underTest.requestAuthenticate(
                FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER,
                fallbackToDetection = true
            )
            faceAuthenticateIsNotCalled()

            faceDetectIsCalled()
        }

    @Test
    fun authenticateCanRunWhenDisplayIsOffAndAwakeButTransitioningFromOff() =
        testScope.runTest {
            initCollectors()

            allPreconditionsToRunFaceAuthAreTrue()

            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.OFF,
                testScope
            )
            runCurrent()
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.OFF,
                    to = KeyguardState.LOCKSCREEN,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()

            displayRepository.setDefaultDisplayOff(true)
            runCurrent()

            assertThat(canFaceAuthRun()).isTrue()
        }

    @Test
    fun authenticateDoesNotRunWhenDisplayIsOffAndAwake() =
        testScope.runTest {
            testGatingCheckForFaceAuth {
                powerInteractor.onFinishedWakingUp()
                keyguardTransitionRepository.sendTransitionSteps(
                    from = KeyguardState.OFF,
                    to = KeyguardState.LOCKSCREEN,
                    testScope
                )
                runCurrent()

                displayRepository.setDefaultDisplayOff(true)
            }
        }

    @Test
    fun everythingEmitsADefaultValueAndDoesNotErrorOut() =
        testScope.runTest {
            underTest = createDeviceEntryFaceAuthRepositoryImpl()
            initCollectors()

            // Collecting any flows exposed in the public API doesn't throw any error
            assertThat(authStatus()).isNull()
            assertThat(detectStatus()).isNull()
            assertThat(authRunning()).isNotNull()
            assertThat(bypassEnabled()).isNotNull()
            assertThat(lockedOut()).isNotNull()
            assertThat(canFaceAuthRun()).isNotNull()
            assertThat(authenticated()).isNotNull()
        }

    @Test
    fun isAuthenticatedIsFalseWhenFaceAuthFails() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            triggerFaceAuth(false)

            authenticationCallback.value.onAuthenticationFailed()

            assertThat(authenticated()).isFalse()
        }

    @Test
    fun isAuthenticatedIsFalseWhenFaceAuthErrorsOut() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            triggerFaceAuth(false)

            authenticationCallback.value.onAuthenticationError(-1, "some error")

            assertThat(authenticated()).isFalse()
        }

    @Test
    fun isAuthenticatedIsResetToFalseWhenDeviceStartsGoingToSleep() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            triggerFaceAuth(false)

            authenticationCallback.value.onAuthenticationSucceeded(
                mock(FaceManager.AuthenticationResult::class.java)
            )

            assertThat(authenticated()).isTrue()

            powerInteractor.setAsleepForTest()

            assertThat(authenticated()).isFalse()
        }

    @Test
    fun isAuthenticatedIsResetToFalseWhenDeviceGoesToSleep() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            triggerFaceAuth(false)

            authenticationCallback.value.onAuthenticationSucceeded(
                mock(FaceManager.AuthenticationResult::class.java)
            )

            assertThat(authenticated()).isTrue()

            powerInteractor.setAsleepForTest()

            assertThat(authenticated()).isFalse()
        }

    @Test
    fun isAuthenticatedIsResetToFalseWhenUserIsSwitching() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            triggerFaceAuth(false)

            authenticationCallback.value.onAuthenticationSucceeded(
                mock(FaceManager.AuthenticationResult::class.java)
            )

            assertThat(authenticated()).isTrue()

            fakeUserRepository.setSelectedUserInfo(
                primaryUser,
                SelectionStatus.SELECTION_IN_PROGRESS
            )

            assertThat(authenticated()).isFalse()
        }

    @Test
    fun isAuthenticatedIsResetToFalseWhenFinishedTransitioningToGoneAndStatusBarStateShade() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            triggerFaceAuth(false)

            keyguardRepository.setStatusBarState(StatusBarState.KEYGUARD)
            authenticationCallback.value.onAuthenticationSucceeded(
                mock(FaceManager.AuthenticationResult::class.java)
            )

            assertThat(authenticated()).isTrue()

            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.STARTED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    transitionState = TransitionState.FINISHED,
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                )
            )
            assertThat(authenticated()).isTrue()

            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            assertThat(authenticated()).isFalse()
        }

    @Test
    fun detectDoesNotRunWhenFaceIsNotUsuallyAllowed() =
        testScope.runTest {
            testGatingCheckForDetect {
                biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            }
        }

    @Test
    fun detectDoesNotRunWhenKeyguardGoingAway() =
        testScope.runTest {
            testGatingCheckForDetect { keyguardRepository.setKeyguardGoingAway(true) }
        }

    @Test
    @EnableSceneContainer
    fun withSceneContainer_faceDetectDoesNotRunWhenKeyguardGoingAway() =
        testScope.runTest {
            testGatingCheckForDetect(sceneContainerEnabled = true) {
                kosmos.sceneInteractor.setTransitionState(
                    MutableStateFlow(
                        ObservableTransitionState.Transition(
                            fromScene = Scenes.Bouncer,
                            toScene = Scenes.Gone,
                            currentScene = flowOf(Scenes.Bouncer),
                            progress = MutableStateFlow(0.2f),
                            isInitiatedByUserInput = true,
                            isUserInputOngoing = flowOf(false),
                        )
                    )
                )

                runCurrent()
            }
        }

    @Test
    @EnableSceneContainer
    fun withSceneContainer_faceDetectDoesNotRunWhenLockscreenIsGone() =
        testScope.runTest {
            testGatingCheckForDetect(sceneContainerEnabled = true) {
                kosmos.sceneInteractor.setTransitionState(
                    MutableStateFlow(ObservableTransitionState.Idle(Scenes.Gone))
                )

                runCurrent()
            }
        }

    @Test
    fun detectDoesNotRunWhenDeviceSleepingStartingToSleep() =
        testScope.runTest {
            testGatingCheckForDetect {
                powerInteractor.setAsleepForTest()
                keyguardTransitionRepository.sendTransitionStep(
                    TransitionStep(
                        transitionState = TransitionState.STARTED,
                        from = KeyguardState.LOCKSCREEN,
                        to = KeyguardState.AOD,
                    )
                )
                runCurrent()
            }
        }

    @Test
    fun detectDoesNotRunWhenSecureCameraIsActive() =
        testScope.runTest {
            testGatingCheckForDetect {
                bouncerRepository.setAlternateVisible(false)
                // Keyguard is occluded when secure camera is active.
                keyguardRepository.setKeyguardOccluded(true)
                keyguardInteractor.onCameraLaunchDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
            }
        }

    @Test
    fun disableFaceUnlockLocksOutFaceUnlock() =
        testScope.runTest {
            runCurrent()
            initCollectors()
            assertThat(underTest.isLockedOut.value).isFalse()

            underTest.setLockedOut(true)
            runCurrent()

            assertThat(underTest.isLockedOut.value).isTrue()
        }

    @Test
    fun detectDoesNotRunWhenFaceAuthNotSupportedInCurrentPosture() =
        testScope.runTest {
            testGatingCheckForDetect {
                biometricSettingsRepository.setIsFaceAuthSupportedInCurrentPosture(false)
            }
        }

    @Test
    fun detectDoesNotRunWhenCurrentUserInLockdown() =
        testScope.runTest {
            testGatingCheckForDetect { biometricSettingsRepository.setIsUserInLockdown(true) }
        }

    @Test
    fun detectDoesNotRunWhenBypassIsNotEnabled() =
        testScope.runTest {
            runCurrent()
            verify(bypassController)
                .registerOnBypassStateChangedListener(bypassStateChangedListener.capture())

            testGatingCheckForDetect {
                bypassStateChangedListener.value.onBypassStateChanged(false)
            }
        }

    @Test
    fun isBypassEnabledReflectsBypassControllerState() =
        testScope.runTest {
            initCollectors()
            runCurrent()
            val listeners = captureMany {
                verify(bypassController, atLeastOnce())
                    .registerOnBypassStateChangedListener(capture())
            }

            listeners.forEach { it.onBypassStateChanged(true) }
            assertThat(bypassEnabled()).isTrue()

            listeners.forEach { it.onBypassStateChanged(false) }
            assertThat(bypassEnabled()).isFalse()
        }

    @Test
    fun detectDoesNotRunWhenFaceAuthIsCurrentlyAllowedToRun() =
        testScope.runTest {
            testGatingCheckForDetect {
                biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)
            }
        }

    @Test
    fun detectDoesNotRunIfUdfpsIsRunning() =
        testScope.runTest {
            testGatingCheckForDetect {
                deviceEntryFingerprintAuthRepository.setAvailableFpSensorType(
                    BiometricType.UNDER_DISPLAY_FINGERPRINT
                )
                deviceEntryFingerprintAuthRepository.setIsRunning(true)
            }
        }

    @Test
    fun schedulesFaceManagerWatchdogWhenKeyguardIsGoneFromDozing() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.DOZING,
                to = KeyguardState.GONE,
                testScope
            )
            runCurrent()
            verify(faceManager).scheduleWatchdog()
        }

    @Test
    fun schedulesFaceManagerWatchdogWhenKeyguardIsGoneFromAod() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.AOD,
                to = KeyguardState.GONE,
                testScope
            )
            runCurrent()
            verify(faceManager).scheduleWatchdog()
        }

    @Test
    fun schedulesFaceManagerWatchdogWhenKeyguardIsGoneFromLockscreen() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.LOCKSCREEN,
                to = KeyguardState.GONE,
                testScope
            )
            runCurrent()
            verify(faceManager).scheduleWatchdog()
        }

    @Test
    fun schedulesFaceManagerWatchdogWhenKeyguardIsGoneFromBouncer() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionSteps(
                from = KeyguardState.PRIMARY_BOUNCER,
                to = KeyguardState.GONE,
                testScope
            )
            runCurrent()
            verify(faceManager).scheduleWatchdog()
        }

    @Test
    fun retryFaceIfThereIsAHardwareError() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            triggerFaceAuth(fallbackToDetect = false)
            clearInvocations(faceManager)

            authenticationCallback.value.onAuthenticationError(
                FACE_ERROR_HW_UNAVAILABLE,
                "HW unavailable"
            )

            advanceTimeBy(DeviceEntryFaceAuthRepositoryImpl.HAL_ERROR_RETRY_TIMEOUT)
            runCurrent()

            faceAuthenticateIsCalled()
        }

    @Test
    fun queuedAuthOnlyRequestShouldNotBeProcessedIfOnlyDetectionCanBeRun() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            // This will prevent auth from running but not detection
            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(false)

            runCurrent()
            assertThat(canFaceAuthRun()).isFalse()

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED, false)
            runCurrent()

            faceDetectIsNotCalled()
            faceAuthenticateIsNotCalled()

            biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)
            faceAuthenticateIsCalled()
        }

    @Test
    fun retryFaceAuthAfterCancel() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()
            val isAuthRunning by collectLastValue(underTest.isAuthRunning)

            underTest.requestAuthenticate(FaceAuthUiEvent.FACE_AUTH_CAMERA_AVAILABLE_CHANGED)
            underTest.cancel()
            clearInvocations(faceManager)
            underTest.requestAuthenticate(FaceAuthUiEvent.FACE_AUTH_CAMERA_AVAILABLE_CHANGED)

            advanceTimeBy(DeviceEntryFaceAuthRepositoryImpl.DEFAULT_CANCEL_SIGNAL_TIMEOUT)
            runCurrent()

            assertThat(isAuthRunning).isEqualTo(true)
            faceAuthenticateIsCalled()
        }

    private suspend fun TestScope.testGatingCheckForFaceAuth(
        sceneContainerEnabled: Boolean = false,
        gatingCheckModifier: suspend () -> Unit
    ) {
        initCollectors()
        allPreconditionsToRunFaceAuthAreTrue(sceneContainerEnabled)

        gatingCheckModifier()
        runCurrent()

        // gating check doesn't allow face auth to run.
        assertThat(underTest.canRunFaceAuth.value).isFalse()

        // request face auth just before gating conditions become true, this ensures any race
        // conditions won't prevent face auth from running
        underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER, false)
        faceAuthenticateIsNotCalled()

        // flip the gating check back on.
        allPreconditionsToRunFaceAuthAreTrue(sceneContainerEnabled)
        assertThat(underTest.canRunFaceAuth.value).isTrue()

        faceAuthenticateIsCalled()
        assertThat(authRunning()).isTrue()
        cancellationSignal.value.setOnCancelListener { wasAuthCancelled = true }

        // Flip gating check off
        gatingCheckModifier()
        runCurrent()

        // Stops currently running auth
        assertThat(wasAuthCancelled).isTrue()
        clearInvocations(faceManager)

        // Try auth again
        underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)

        runCurrent()

        // Auth can't run again
        faceAuthenticateIsNotCalled()
    }

    private suspend fun TestScope.testGatingCheckForDetect(
        sceneContainerEnabled: Boolean = false,
        gatingCheckModifier: suspend () -> Unit
    ) {
        initCollectors()
        allPreconditionsToRunFaceAuthAreTrue(sceneContainerEnabled)

        // This will stop face auth from running but is required to be false for detect.
        biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(false)
        runCurrent()

        assertThat(canFaceAuthRun()).isFalse()

        // Trigger authenticate with detection fallback
        underTest.requestAuthenticate(
            FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER,
            fallbackToDetection = true
        )
        runCurrent()

        faceAuthenticateIsNotCalled()
        faceDetectIsCalled()
        cancellationSignal.value.setOnCancelListener { wasDetectCancelled = true }

        // Flip gating check
        gatingCheckModifier()
        runCurrent()

        // Stops currently running detect
        assertThat(wasDetectCancelled).isTrue()
        clearInvocations(faceManager)

        // Try to run detect again
        underTest.requestAuthenticate(
            FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER,
            fallbackToDetection = true
        )

        // Detect won't run because preconditions are not true anymore.
        faceDetectIsNotCalled()
    }

    private fun TestScope.triggerFaceAuth(fallbackToDetect: Boolean) {
        assertThat(canFaceAuthRun()).isTrue()
        underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER, fallbackToDetect)

        runCurrent()

        faceAuthenticateIsCalled()
        assertThat(authRunning()).isTrue()
        cancellationSignal.value.setOnCancelListener { wasAuthCancelled = true }
    }

    private suspend fun TestScope.allPreconditionsToRunFaceAuthAreTrue(
        sceneContainerEnabled: Boolean = false
    ) {
        fakeExecutor.runAllReady()
        verify(faceManager, atLeastOnce())
            .addLockoutResetCallback(faceLockoutResetCallback.capture())
        trustRepository.setCurrentUserTrusted(false)
        if (sceneContainerEnabled) {
            // Keyguard is not going away
            kosmos.fakeKeyguardTransitionRepository.sendTransitionStep(
                TransitionStep(KeyguardState.OFF, KeyguardState.LOCKSCREEN, value = 1.0f),
                validateStep = false
            )
            kosmos.sceneInteractor.setTransitionState(
                MutableStateFlow(ObservableTransitionState.Idle(Scenes.Lockscreen))
            )
        } else {
            keyguardRepository.setKeyguardGoingAway(false)
        }
        powerInteractor.setAwakeForTest()
        biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
        biometricSettingsRepository.setIsFaceAuthSupportedInCurrentPosture(true)
        biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)
        biometricSettingsRepository.setIsUserInLockdown(false)
        fakeUserRepository.setSelectedUserInfo(primaryUser, SelectionStatus.SELECTION_COMPLETE)
        faceLockoutResetCallback.value.onLockoutReset(0)
        bouncerRepository.setAlternateVisible(true)
        keyguardRepository.setKeyguardShowing(true)
        displayRepository.setDefaultDisplayOff(false)
        keyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.AOD,
            to = KeyguardState.LOCKSCREEN,
            testScope
        )
        runCurrent()
    }

    private suspend fun TestScope.initCollectors() {
        authStatus = collectLastValue(underTest.authenticationStatus)
        detectStatus = collectLastValue(underTest.detectionStatus)
        authRunning = collectLastValue(underTest.isAuthRunning)
        lockedOut = collectLastValue(underTest.isLockedOut)
        canFaceAuthRun = collectLastValue(underTest.canRunFaceAuth)
        authenticated = collectLastValue(underTest.isAuthenticated)
        bypassEnabled = collectLastValue(underTest.isBypassEnabled)
        fakeUserRepository.setSelectedUserInfo(primaryUser)
        runCurrent()
    }

    private fun successResult() = FaceManager.AuthenticationResult(null, null, primaryUserId, false)

    private fun TestScope.faceDetectIsCalled() {
        runCurrent()

        verify(faceManager)
            .detectFace(
                cancellationSignal.capture(),
                detectionCallback.capture(),
                faceAuthenticateOptions.capture(),
            )
    }

    private fun TestScope.faceAuthenticateIsCalled() {
        runCurrent()

        verify(faceManager)
            .authenticate(
                isNull(),
                cancellationSignal.capture(),
                authenticationCallback.capture(),
                isNull(),
                faceAuthenticateOptions.capture(),
            )
    }

    private fun TestScope.faceAuthenticateIsNotCalled() {
        runCurrent()

        verify(faceManager, never())
            .authenticate(
                isNull(),
                any(),
                any(),
                isNull(),
                any(FaceAuthenticateOptions::class.java)
            )
    }

    private fun faceDetectIsNotCalled() {
        verify(faceManager, never())
            .detectFace(any(), any(), any(FaceAuthenticateOptions::class.java))
    }

    private fun createFaceSensorProperties(
        supportsFaceDetection: Boolean
    ): FaceSensorPropertiesInternal {
        val componentInfo =
            listOf(
                ComponentInfoInternal(
                    "faceSensor" /* componentId */,
                    "vendor/model/revision" /* hardwareVersion */,
                    "1.01" /* firmwareVersion */,
                    "00000001" /* serialNumber */,
                    "" /* softwareVersion */
                )
            )
        return FaceSensorPropertiesInternal(
            0 /* id */,
            FaceSensorProperties.STRENGTH_STRONG,
            1 /* maxTemplatesAllowed */,
            componentInfo,
            FaceSensorProperties.TYPE_UNKNOWN,
            supportsFaceDetection /* supportsFaceDetection */,
            true /* supportsSelfIllumination */,
            false /* resetLockoutRequiresChallenge */
        )
    }

    companion object {
        const val primaryUserId = 1
        val keyguardSessionId = fakeInstanceId(10)!!
        val primaryUser = UserInfo(primaryUserId, "test user", FLAG_PRIMARY)

        val secondaryUser = UserInfo(2, "secondary user", 0)
    }
}
