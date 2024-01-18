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
import android.view.Display
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId.fakeInstanceId
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeDisplayStateRepository
import com.android.systemui.biometrics.data.repository.FakeFacePropertyRepository
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractorImpl
import com.android.systemui.bouncer.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.AlternateBouncerInteractor
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
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.SuccessFaceAuthenticationStatus
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.display.data.repository.display
import com.android.systemui.dump.DumpManager
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.keyguard.data.repository.BiometricType
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeCommandQueue
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractorFactory
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.log.SessionTracker
import com.android.systemui.log.logcatLogBuffer
import com.android.systemui.log.table.TableLogBuffer
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.power.data.repository.FakePowerRepository
import com.android.systemui.power.domain.interactor.PowerInteractor
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.PowerInteractorFactory
import com.android.systemui.res.R
import com.android.systemui.statusbar.phone.FakeKeyguardStateController
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.concurrency.FakeExecutor
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
    private lateinit var underTest: DeviceEntryFaceAuthRepositoryImpl

    @Mock private lateinit var faceManager: FaceManager
    @Mock private lateinit var bypassController: KeyguardBypassController
    @Mock private lateinit var sessionTracker: SessionTracker
    @Mock private lateinit var uiEventLogger: UiEventLogger
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor

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
    private lateinit var testDispatcher: TestDispatcher

    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var testScope: TestScope
    private lateinit var fakeUserRepository: FakeUserRepository
    private lateinit var authStatus: FlowValue<FaceAuthenticationStatus?>
    private lateinit var detectStatus: FlowValue<FaceDetectionStatus?>
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
    private lateinit var powerRepository: FakePowerRepository
    private lateinit var powerInteractor: PowerInteractor
    private lateinit var keyguardInteractor: KeyguardInteractor
    private lateinit var alternateBouncerInteractor: AlternateBouncerInteractor
    private lateinit var displayStateInteractor: DisplayStateInteractor
    private lateinit var bouncerRepository: FakeKeyguardBouncerRepository
    private lateinit var displayRepository: FakeDisplayRepository
    private lateinit var fakeCommandQueue: FakeCommandQueue
    private lateinit var featureFlags: FakeFeatureFlags
    private lateinit var fakeFacePropertyRepository: FakeFacePropertyRepository

    private var wasAuthCancelled = false
    private var wasDetectCancelled = false

    private lateinit var keyguardTransitionInteractor: KeyguardTransitionInteractor

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        fakeUserRepository = FakeUserRepository()
        fakeUserRepository.setUserInfos(listOf(primaryUser, secondaryUser))
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        deviceEntryFingerprintAuthRepository = FakeDeviceEntryFingerprintAuthRepository()
        trustRepository = FakeTrustRepository()
        featureFlags = FakeFeatureFlags()

        powerRepository = FakePowerRepository()
        powerInteractor =
            PowerInteractorFactory.create(
                    repository = powerRepository,
                )
                .powerInteractor

        val withDeps =
            KeyguardInteractorFactory.create(
                featureFlags = featureFlags,
                powerInteractor = powerInteractor,
            )
        keyguardInteractor = withDeps.keyguardInteractor
        keyguardRepository = withDeps.repository
        bouncerRepository = withDeps.bouncerRepository

        keyguardTransitionRepository = FakeKeyguardTransitionRepository()
        keyguardTransitionInteractor =
            KeyguardTransitionInteractorFactory.create(
                    scope = testScope.backgroundScope,
                    repository = keyguardTransitionRepository,
                    keyguardInteractor = keyguardInteractor,
                )
                .keyguardTransitionInteractor

        fakeCommandQueue = withDeps.commandQueue

        alternateBouncerInteractor =
            AlternateBouncerInteractor(
                bouncerRepository = bouncerRepository,
                fingerprintPropertyRepository = FakeFingerprintPropertyRepository(),
                biometricSettingsRepository = biometricSettingsRepository,
                systemClock = mock(SystemClock::class.java),
                keyguardStateController = FakeKeyguardStateController(),
                statusBarStateController = mock(StatusBarStateController::class.java),
                keyguardUpdateMonitor = keyguardUpdateMonitor,
                scope = testScope.backgroundScope,
            )

        displayRepository = FakeDisplayRepository()
        displayStateInteractor =
            DisplayStateInteractorImpl(
                applicationScope = testScope.backgroundScope,
                context = context,
                mainExecutor = FakeExecutor(FakeSystemClock()),
                displayStateRepository = FakeDisplayStateRepository(),
                displayRepository = displayRepository,
            )

        bypassStateChangedListener =
            KotlinArgumentCaptor(KeyguardBypassController.OnBypassStateChangedListener::class.java)
        whenever(sessionTracker.getSessionId(SESSION_KEYGUARD)).thenReturn(keyguardSessionId)
        whenever(faceManager.sensorPropertiesInternal)
            .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = true)))
        whenever(bypassController.bypassEnabled).thenReturn(true)
        underTest = createDeviceEntryFaceAuthRepositoryImpl(faceManager, bypassController)

        mSetFlagsRule.disableFlags(
            AConfigFlags.FLAG_KEYGUARD_WM_STATE_REFACTOR,
        )
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

        fakeFacePropertyRepository = FakeFacePropertyRepository()
        return DeviceEntryFaceAuthRepositoryImpl(
            mContext,
            fmOverride,
            fakeUserRepository,
            bypassControllerOverride,
            testScope.backgroundScope,
            testDispatcher,
            testDispatcher,
            sessionTracker,
            uiEventLogger,
            FaceAuthenticationLogger(logcatLogBuffer("DeviceEntryFaceAuthRepositoryLog")),
            biometricSettingsRepository,
            deviceEntryFingerprintAuthRepository,
            trustRepository,
            keyguardRepository,
            powerInteractor,
            keyguardInteractor,
            alternateBouncerInteractor,
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
    fun faceHelpMessagesAreIgnoredBasedOnConfig() =
        testScope.runTest {
            overrideResource(
                R.array.config_face_acquire_device_entry_ignorelist,
                intArrayOf(10, 11)
            )
            underTest = createDeviceEntryFaceAuthRepositoryImpl()
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()

            authenticationCallback.value.onAuthenticationHelp(9, "help msg")
            authenticationCallback.value.onAuthenticationHelp(10, "Ignored help msg")
            authenticationCallback.value.onAuthenticationHelp(11, "Ignored help msg")

            val response = authStatus() as HelpFaceAuthenticationStatus
            assertThat(response.msg).isEqualTo("help msg")
            assertThat(response.msgId).isEqualTo(response.msgId)
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
                fakeCommandQueue.doForEachCallback {
                    it.onCameraLaunchGestureDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
                }
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
            fakeCommandQueue.doForEachCallback {
                it.onCameraLaunchGestureDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
            }
            keyguardRepository.setKeyguardOccluded(true)
            runCurrent()
            assertThat(canFaceAuthRun()).isFalse()

            // but bouncer is shown after that.
            bouncerRepository.setPrimaryShow(true)
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
    fun authenticateFallbacksToDetectionWhenUserIsAlreadyTrustedByTrustManager() =
        testScope.runTest {
            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = true)))
            whenever(bypassController.bypassEnabled).thenReturn(true)
            underTest = createDeviceEntryFaceAuthRepositoryImpl()
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            trustRepository.setCurrentUserTrusted(true)
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

            displayRepository.emit(setOf(display(0, 0, Display.DEFAULT_DISPLAY, Display.STATE_OFF)))
            displayRepository.emitDisplayChangeEvent(Display.DEFAULT_DISPLAY)

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

                displayRepository.emit(
                    setOf(display(0, 0, Display.DEFAULT_DISPLAY, Display.STATE_OFF))
                )
                displayRepository.emitDisplayChangeEvent(Display.DEFAULT_DISPLAY)
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
    fun isAuthenticatedIsResetToFalseWhenKeyguardDoneAnimationsFinished() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()

            triggerFaceAuth(false)

            authenticationCallback.value.onAuthenticationSucceeded(
                mock(FaceManager.AuthenticationResult::class.java)
            )

            assertThat(authenticated()).isTrue()

            keyguardRepository.keyguardDoneAnimationsFinished()

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
                fakeCommandQueue.doForEachCallback {
                    it.onCameraLaunchGestureDetected(CAMERA_LAUNCH_SOURCE_POWER_DOUBLE_TAP)
                }
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
    fun authFailedCallAfterAuthLockedOutErrorShouldBeIgnored() =
        testScope.runTest {
            initCollectors()
            allPreconditionsToRunFaceAuthAreTrue()
            runCurrent()
            assertThat(canFaceAuthRun()).isTrue()

            underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_NOTIFICATION_PANEL_CLICKED, false)
            runCurrent()

            faceAuthenticateIsCalled()
            authenticationCallback.value.onAuthenticationError(
                FACE_ERROR_LOCKOUT_PERMANENT,
                "Too many attempts, face not available"
            )

            val lockoutError = authStatus() as ErrorFaceAuthenticationStatus
            assertThat(lockedOut()).isTrue()
            assertThat(lockoutError.isLockoutError()).isTrue()

            authenticationCallback.value.onAuthenticationFailed()
            runCurrent()

            assertThat(authStatus()).isEqualTo(lockoutError)
        }

    private suspend fun TestScope.testGatingCheckForFaceAuth(
        gatingCheckModifier: suspend () -> Unit
    ) {
        initCollectors()
        allPreconditionsToRunFaceAuthAreTrue()

        gatingCheckModifier()
        runCurrent()

        // gating check doesn't allow face auth to run.
        assertThat(underTest.canRunFaceAuth.value).isFalse()

        // request face auth just before gating conditions become true, this ensures any race
        // conditions won't prevent face auth from running
        underTest.requestAuthenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER, false)
        faceAuthenticateIsNotCalled()

        // flip the gating check back on.
        allPreconditionsToRunFaceAuthAreTrue()
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
        gatingCheckModifier: suspend () -> Unit
    ) {
        initCollectors()
        allPreconditionsToRunFaceAuthAreTrue()

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

    private suspend fun TestScope.allPreconditionsToRunFaceAuthAreTrue() {
        verify(faceManager, atLeastOnce())
            .addLockoutResetCallback(faceLockoutResetCallback.capture())
        trustRepository.setCurrentUserTrusted(false)
        keyguardRepository.setKeyguardGoingAway(false)
        powerInteractor.setAwakeForTest()
        biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
        biometricSettingsRepository.setIsFaceAuthSupportedInCurrentPosture(true)
        biometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)
        biometricSettingsRepository.setIsUserInLockdown(false)
        fakeUserRepository.setSelectedUserInfo(primaryUser, SelectionStatus.SELECTION_COMPLETE)
        faceLockoutResetCallback.value.onLockoutReset(0)
        bouncerRepository.setAlternateVisible(true)
        keyguardRepository.setKeyguardShowing(true)
        displayRepository.emit(setOf(display(0, 0, Display.DEFAULT_DISPLAY, Display.STATE_ON)))
        displayRepository.emitDisplayChangeEvent(Display.DEFAULT_DISPLAY)
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
