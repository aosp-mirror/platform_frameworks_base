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

package com.android.systemui.keyguard.data.repository

import android.app.StatusBarManager.CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP
import android.app.StatusBarManager.SESSION_KEYGUARD
import android.content.pm.UserInfo
import android.content.pm.UserInfo.FLAG_PRIMARY
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_CANCELED
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_HW_UNAVAILABLE
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT
import android.hardware.biometrics.ComponentInfoInternal
import android.hardware.face.FaceAuthenticateOptions
import android.hardware.face.FaceManager
import android.hardware.face.FaceSensorProperties
import android.hardware.face.FaceSensorPropertiesInternal
import android.os.CancellationSignal
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId.fakeInstanceId
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.FaceAuthUiEvent
import com.android.keyguard.FaceAuthUiEvent.FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN
import com.android.keyguard.FaceAuthUiEvent.FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER
import com.android.systemui.R
import com.android.systemui.RoboPilotTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.DumpManager
import com.android.systemui.dump.logcatLogBuffer
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags.FACE_AUTH_REFACTOR
import com.android.systemui.keyguard.domain.interactor.AlternateBouncerInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.AuthenticationStatus
import com.android.systemui.keyguard.shared.model.DetectionStatus
import com.android.systemui.keyguard.shared.model.ErrorAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpAuthenticationStatus
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.SuccessAuthenticationStatus
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.log.SessionTracker
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.phone.FakeKeyguardStateController
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.KotlinArgumentCaptor
import com.android.systemui.util.mockito.captureMany
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.time.FakeSystemClock
import com.android.systemui.util.time.SystemClock
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
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
@RoboPilotTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryFaceAuthRepositoryTest : SysuiTestCase() {
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
    @Captor private lateinit var cancellationSignal: ArgumentCaptor<CancellationSignal>

    private lateinit var bypassStateChangedListener:
        KotlinArgumentCaptor<KeyguardBypassController.OnBypassStateChangedListener>

    @Captor
    private lateinit var faceLockoutResetCallback: ArgumentCaptor<FaceManager.LockoutResetCallback>
    private lateinit var testDispatcher: TestDispatcher

    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var testScope: TestScope
    private lateinit var fakeUserRepository: FakeUserRepository
    private lateinit var authStatus: FlowValue<AuthenticationStatus?>
    private lateinit var detectStatus: FlowValue<DetectionStatus?>
    private lateinit var authRunning: FlowValue<Boolean?>
    private lateinit var bypassEnabled: FlowValue<Boolean?>
    private lateinit var lockedOut: FlowValue<Boolean?>
    private lateinit var canFaceAuthRun: FlowValue<Boolean?>
    private lateinit var authenticated: FlowValue<Boolean?>
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository
    private lateinit var deviceEntryFingerprintAuthRepository:
        FakeDeviceEntryFingerprintAuthRepository
    private lateinit var trustRepository: FakeTrustRepository
    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var keyguardInteractor: KeyguardInteractor
    private lateinit var alternateBouncerInteractor: AlternateBouncerInteractor
    private lateinit var bouncerRepository: FakeKeyguardBouncerRepository
    private lateinit var fakeCommandQueue: FakeCommandQueue
    private lateinit var featureFlags: FakeFeatureFlags

    private var wasAuthCancelled = false
    private var wasDetectCancelled = false

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        fakeUserRepository = FakeUserRepository()
        fakeUserRepository.setUserInfos(listOf(primaryUser, secondaryUser))
        testDispatcher = StandardTestDispatcher()
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        deviceEntryFingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository()
        trustRepository = FakeTrustRepository()
        keyguardRepository = FakeKeyguardRepository()
        bouncerRepository = FakeKeyguardBouncerRepository()
        featureFlags = FakeFeatureFlags().apply { set(FACE_AUTH_REFACTOR, true) }
        fakeCommandQueue = FakeCommandQueue()
        keyguardInteractor =
            KeyguardInteractor(
                keyguardRepository,
                fakeCommandQueue,
                featureFlags,
                bouncerRepository
            )
        alternateBouncerInteractor =
            AlternateBouncerInteractor(
                bouncerRepository = bouncerRepository,
                biometricSettingsRepository = biometricSettingsRepository,
                deviceEntryFingerprintAuthRepository = deviceEntryFingerprintAuthRepository,
                systemClock = mock(SystemClock::class.java),
                keyguardStateController = FakeKeyguardStateController(),
                statusBarStateController = mock(StatusBarStateController::class.java),
            )

        bypassStateChangedListener =
            KotlinArgumentCaptor(KeyguardBypassController.OnBypassStateChangedListener::class.java)
        testScope = TestScope(testDispatcher)
        whenever(sessionTracker.getSessionId(SESSION_KEYGUARD)).thenReturn(keyguardSessionId)
        whenever(faceManager.sensorPropertiesInternal)
            .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = true)))
        whenever(bypassController.bypassEnabled).thenReturn(true)
        underTest = createDeviceEntryFaceAuthRepositoryImpl(faceManager, bypassController)
    }

    private fun createDeviceEntryFaceAuthRepositoryImpl(
        fmOverride: FaceManager? = faceManager,
        bypassControllerOverride: KeyguardBypassController? = bypassController
    ): DeviceEntryFaceAuthRepositoryImpl {
        val systemClock = FakeSystemClock()
        val faceAuthBuffer =
            TableLogBuffer(
                10,
                "face auth",
                systemClock,
                mock(),
                testDispatcher,
                testScope.backgroundScope
            )
        val faceDetectBuffer =
            TableLogBuffer(
                10,
                "face detect",
                systemClock,
                mock(),
                testDispatcher,
                testScope.backgroundScope
            )
        keyguardTransitionRepository = FakeKeyguardTransitionRepository()
        val keyguardTransitionInteractor =
            KeyguardTransitionInteractor(keyguardTransitionRepository, testScope.backgroundScope)
        return DeviceEntryFaceAuthRepositoryImpl(
            mContext,
            fmOverride,
            fakeUserRepository,
            bypassControllerOverride,
            testScope.backgroundScope,
            testDispatcher,
            sessionTracker,
            uiEventLogger,
            FaceAuthenticationLogger(logcatLogBuffer("DeviceEntryFaceAuthRepositoryLog")),
            biometricSettingsRepository,
            deviceEntryFingerprintAuthRepository,
            trustRepository,
            keyguardRepository,
            keyguardInteractor,
            alternateBouncerInteractor,
            faceDetectBuffer,
            faceAuthBuffer,
            keyguardTransitionInteractor,
            featureFlags,
            dumpManager,
        )
    }

    @Test
    fun faceAuthRunsAndProvidesAuthStatusUpdates() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER.extraInfo = 10
            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()
            uiEventIsLogged(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)

            assertThat(authRunning()).isTrue()

            val successResult = successResult()
            authenticationCallback.value.onAuthenticationSucceeded(successResult)

            assertThat(authStatus()).isEqualTo(SuccessAuthenticationStatus(successResult))
            assertThat(authenticated()).isTrue()
            assertThat(authRunning()).isFalse()
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

            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()
            clearInvocations(faceManager)
            clearInvocations(uiEventLogger)

            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            verifyNoMoreInteractions(faceManager)
            verifyNoMoreInteractions(uiEventLogger)
        }

    @Test
    fun faceLockoutStatusIsPropagated() =
        testScope.runTest {
            initCollectors()
            verify(faceManager).addLockoutResetCallback(faceLockoutResetCallback.capture())

            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
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

            whenever(faceManager.sensorPropertiesInternal).thenReturn(null)
            assertThat(createDeviceEntryFaceAuthRepositoryImpl().isDetectionSupported).isFalse()

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

            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
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

            underTest.detect()
            faceDetectIsCalled()

            detectionCallback.value.onFaceDetected(1, 1, true)

            assertThat(detectStatus()).isEqualTo(DetectionStatus(1, 1, true))
        }

    @Test
    fun faceDetectDoesNotRunIfDetectionIsNotSupported() =
        testScope.runTest {
            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = false)))
            underTest = createDeviceEntryFaceAuthRepositoryImpl()
            initCollectors()
            clearInvocations(faceManager)

            underTest.detect()

            verify(faceManager, never())
                .detectFace(any(), any(), any(FaceAuthenticateOptions::class.java))
        }

    @Test
    fun faceAuthShouldWaitAndRunIfTriggeredWhileCancelling() =
        testScope.runTest {
            initCollectors()

            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()

            // Enter cancelling state
            underTest.cancel()
            clearInvocations(faceManager)

            // Auth is while cancelling.
            underTest.authenticate(FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN)
            // Auth is not started
            verifyNoMoreInteractions(faceManager)

            // Auth is done cancelling.
            authenticationCallback.value.onAuthenticationError(
                FACE_ERROR_CANCELED,
                "First auth attempt cancellation completed"
            )
            assertThat(authStatus())
                .isEqualTo(
                    ErrorAuthenticationStatus(
                        FACE_ERROR_CANCELED,
                        "First auth attempt cancellation completed"
                    )
                )

            faceAuthenticateIsCalled()
            uiEventIsLogged(FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN)
        }

    @Test
    fun faceAuthAutoCancelsAfterDefaultCancellationTimeout() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()

            clearInvocations(faceManager)
            underTest.cancel()
            advanceTimeBy(DeviceEntryFaceAuthRepositoryImpl.DEFAULT_CANCEL_SIGNAL_TIMEOUT + 1)

            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()
        }

    @Test
    fun faceHelpMessagesAreIgnoredBasedOnConfig() =
        testScope.runTest {
            overrideResource(
                R.array.config_face_acquire_device_entry_ignorelist,
                intArrayOf(10, 11)
            )
            underTest = createDeviceEntryFaceAuthRepositoryImpl()
            initCollectors()

            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()

            authenticationCallback.value.onAuthenticationHelp(9, "help msg")
            authenticationCallback.value.onAuthenticationHelp(10, "Ignored help msg")
            authenticationCallback.value.onAuthenticationHelp(11, "Ignored help msg")

            assertThat(authStatus()).isEqualTo(HelpAuthenticationStatus(9, "help msg"))
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
    fun authenticateDoesNotRunIfFaceIsNotEnrolled() =
        testScope.runTest {
            testGatingCheckForFaceAuth { biometricSettingsRepository.setFaceEnrolled(false) }
        }

    @Test
    fun authenticateDoesNotRunIfFaceIsNotEnabled() =
        testScope.runTest {
            testGatingCheckForFaceAuth { biometricSettingsRepository.setIsFaceAuthEnabled(false) }
        }

    @Test
    fun authenticateDoesNotRunIfUserIsInLockdown() =
        testScope.runTest {
            testGatingCheckForFaceAuth { biometricSettingsRepository.setIsUserInLockdown(true) }
        }

    @Test
    fun authenticateDoesNotRunIfUserIsCurrentlySwitching() =
        testScope.runTest {
            testGatingCheckForFaceAuth { fakeUserRepository.setUserSwitching(true) }
        }

    @Test
    fun authenticateDoesNotRunWhenFpIsLockedOut() =
        testScope.runTest {
            testGatingCheckForFaceAuth { deviceEntryFingerprintAuthRepository.setLockedOut(true) }
        }

    @Test
    fun authenticateDoesNotRunWhenUserIsCurrentlyTrusted() =
        testScope.runTest {
            testGatingCheckForFaceAuth { trustRepository.setCurrentUserTrusted(true) }
        }

    @Test
    fun authenticateDoesNotRunWhenKeyguardIsGoingAway() =
        testScope.runTest {
            testGatingCheckForFaceAuth { keyguardRepository.setKeyguardGoingAway(true) }
        }

    @Test
    fun authenticateDoesNotRunWhenDeviceIsGoingToSleep() =
        testScope.runTest {
            testGatingCheckForFaceAuth {
                keyguardRepository.setWakefulnessModel(
                    WakefulnessModel(
                        state = WakefulnessState.STARTING_TO_SLEEP,
                        lastWakeReason = WakeSleepReason.OTHER,
                        lastSleepReason = WakeSleepReason.OTHER,
                    )
                )
            }
        }

    @Test
    fun authenticateDoesNotRunWhenDeviceIsSleeping() =
        testScope.runTest {
            testGatingCheckForFaceAuth {
                keyguardRepository.setWakefulnessModel(
                    WakefulnessModel(
                        state = WakefulnessState.ASLEEP,
                        lastWakeReason = WakeSleepReason.OTHER,
                        lastSleepReason = WakeSleepReason.OTHER,
                    )
                )
            }
        }

    @Test
    fun authenticateDoesNotRunWhenNonStrongBiometricIsNotAllowed() =
        testScope.runTest {
            testGatingCheckForFaceAuth {
                biometricSettingsRepository.setIsNonStrongBiometricAllowed(false)
            }
        }

    @Test
    fun authenticateDoesNotRunWhenSecureCameraIsActive() =
        testScope.runTest {
            testGatingCheckForFaceAuth {
                bouncerRepository.setAlternateVisible(false)
                fakeCommandQueue.doForEachCallback {
                    it.onCameraLaunchGestureDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
                }
            }
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
            biometricSettingsRepository.setIsNonStrongBiometricAllowed(false)
            assertThat(canFaceAuthRun()).isFalse()
            underTest.authenticate(
                FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER,
                fallbackToDetection = true
            )
            faceAuthenticateIsNotCalled()

            faceDetectIsCalled()
        }

    @Test
    fun everythingWorksWithFaceAuthRefactorFlagDisabled() =
        testScope.runTest {
            featureFlags.set(FACE_AUTH_REFACTOR, false)

            underTest = createDeviceEntryFaceAuthRepositoryImpl()
            initCollectors()

            // Collecting any flows exposed in the public API doesn't throw any error
            authStatus()
            detectStatus()
            authRunning()
            bypassEnabled()
            lockedOut()
            canFaceAuthRun()
            authenticated()
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
    fun isAuthenticatedIsResetToFalseWhenKeyguardIsGoingAway() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            triggerFaceAuth(false)

            authenticationCallback.value.onAuthenticationSucceeded(
                mock(FaceManager.AuthenticationResult::class.java)
            )

            assertThat(authenticated()).isTrue()

            keyguardRepository.setKeyguardGoingAway(true)

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

            keyguardRepository.setWakefulnessModel(
                WakefulnessModel(
                    WakefulnessState.STARTING_TO_SLEEP,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.POWER_BUTTON
                )
            )

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

            keyguardRepository.setWakefulnessModel(
                WakefulnessModel(
                    WakefulnessState.ASLEEP,
                    lastWakeReason = WakeSleepReason.POWER_BUTTON,
                    lastSleepReason = WakeSleepReason.POWER_BUTTON
                )
            )

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

            fakeUserRepository.setUserSwitching(true)

            assertThat(authenticated()).isFalse()
        }

    @Test
    fun detectDoesNotRunWhenFaceIsNotEnrolled() =
        testScope.runTest {
            testGatingCheckForDetect { biometricSettingsRepository.setFaceEnrolled(false) }
        }

    @Test
    fun detectDoesNotRunWhenFaceIsNotEnabled() =
        testScope.runTest {
            testGatingCheckForDetect { biometricSettingsRepository.setIsFaceAuthEnabled(false) }
        }

    @Test
    fun detectDoesNotRunWhenUserSwitchingInProgress() =
        testScope.runTest { testGatingCheckForDetect { fakeUserRepository.setUserSwitching(true) } }

    @Test
    fun detectDoesNotRunWhenKeyguardGoingAway() =
        testScope.runTest {
            testGatingCheckForDetect { keyguardRepository.setKeyguardGoingAway(true) }
        }

    @Test
    fun detectDoesNotRunWhenDeviceSleepingStartingToSleep() =
        testScope.runTest {
            testGatingCheckForDetect {
                keyguardRepository.setWakefulnessModel(
                    WakefulnessModel(
                        state = WakefulnessState.STARTING_TO_SLEEP,
                        lastWakeReason = WakeSleepReason.OTHER,
                        lastSleepReason = WakeSleepReason.OTHER,
                    )
                )
            }
        }

    @Test
    fun detectDoesNotRunWhenSecureCameraIsActive() =
        testScope.runTest {
            testGatingCheckForDetect {
                bouncerRepository.setAlternateVisible(false)
                fakeCommandQueue.doForEachCallback {
                    it.onCameraLaunchGestureDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
                }
            }
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
    fun detectDoesNotRunWhenNonStrongBiometricIsAllowed() =
        testScope.runTest {
            testGatingCheckForDetect {
                biometricSettingsRepository.setIsNonStrongBiometricAllowed(true)
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
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.DOZING,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.FINISHED
                )
            )

            runCurrent()
            verify(faceManager).scheduleWatchdog()
        }

    @Test
    fun schedulesFaceManagerWatchdogWhenKeyguardIsGoneFromAod() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.AOD,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.FINISHED
                )
            )

            runCurrent()
            verify(faceManager).scheduleWatchdog()
        }

    @Test
    fun schedulesFaceManagerWatchdogWhenKeyguardIsGoneFromLockscreen() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.FINISHED
                )
            )

            runCurrent()
            verify(faceManager).scheduleWatchdog()
        }

    @Test
    fun schedulesFaceManagerWatchdogWhenKeyguardIsGoneFromBouncer() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.PRIMARY_BOUNCER,
                    to = KeyguardState.GONE,
                    transitionState = TransitionState.FINISHED
                )
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

    private suspend fun TestScope.testGatingCheckForFaceAuth(gatingCheckModifier: () -> Unit) {
        initCollectors()
        allPreconditionsToRunFaceAuthAreTrue()

        gatingCheckModifier()
        runCurrent()

        // gating check doesn't allow face auth to run.
        assertThat(underTest.canRunFaceAuth.value).isFalse()

        // flip the gating check back on.
        allPreconditionsToRunFaceAuthAreTrue()

        triggerFaceAuth(false)

        // Flip gating check off
        gatingCheckModifier()
        runCurrent()

        // Stops currently running auth
        assertThat(wasAuthCancelled).isTrue()
        clearInvocations(faceManager)

        // Try auth again
        underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)

        // Auth can't run again
        faceAuthenticateIsNotCalled()
    }

    private suspend fun TestScope.testGatingCheckForDetect(gatingCheckModifier: () -> Unit) {
        initCollectors()
        allPreconditionsToRunFaceAuthAreTrue()

        // This will stop face auth from running but is required to be false for detect.
        biometricSettingsRepository.setIsNonStrongBiometricAllowed(false)
        runCurrent()

        assertThat(canFaceAuthRun()).isFalse()

        // Trigger authenticate with detection fallback
        underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER, fallbackToDetection = true)

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
        underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER, fallbackToDetection = true)

        // Detect won't run because preconditions are not true anymore.
        faceDetectIsNotCalled()
    }

    private suspend fun triggerFaceAuth(fallbackToDetect: Boolean) {
        assertThat(canFaceAuthRun()).isTrue()
        underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER, fallbackToDetect)
        faceAuthenticateIsCalled()
        assertThat(authRunning()).isTrue()
        cancellationSignal.value.setOnCancelListener { wasAuthCancelled = true }
    }

    private suspend fun TestScope.allPreconditionsToRunFaceAuthAreTrue() {
        biometricSettingsRepository.setFaceEnrolled(true)
        biometricSettingsRepository.setIsFaceAuthEnabled(true)
        fakeUserRepository.setUserSwitching(false)
        deviceEntryFingerprintAuthRepository.setLockedOut(false)
        trustRepository.setCurrentUserTrusted(false)
        keyguardRepository.setKeyguardGoingAway(false)
        keyguardRepository.setWakefulnessModel(
            WakefulnessModel(
                WakefulnessState.STARTING_TO_WAKE,
                WakeSleepReason.OTHER,
                WakeSleepReason.OTHER
            )
        )
        biometricSettingsRepository.setIsNonStrongBiometricAllowed(true)
        biometricSettingsRepository.setIsUserInLockdown(false)
        fakeUserRepository.setSelectedUserInfo(primaryUser)
        biometricSettingsRepository.setIsFaceAuthSupportedInCurrentPosture(true)
        bouncerRepository.setAlternateVisible(true)
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
    }

    private fun successResult() = FaceManager.AuthenticationResult(null, null, primaryUserId, false)

    private fun faceDetectIsCalled() {
        verify(faceManager)
            .detectFace(
                cancellationSignal.capture(),
                detectionCallback.capture(),
                eq(FaceAuthenticateOptions.Builder().setUserId(primaryUserId).build())
            )
    }

    private fun faceAuthenticateIsCalled() {
        verify(faceManager)
            .authenticate(
                isNull(),
                cancellationSignal.capture(),
                authenticationCallback.capture(),
                isNull(),
                eq(FaceAuthenticateOptions.Builder().setUserId(primaryUserId).build())
            )
    }

    private fun faceAuthenticateIsNotCalled() {
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
