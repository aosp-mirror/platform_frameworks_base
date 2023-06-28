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

import android.app.StatusBarManager.SESSION_KEYGUARD
import android.content.pm.UserInfo
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_CANCELED
import android.hardware.biometrics.BiometricFaceConstants.FACE_ERROR_LOCKOUT_PERMANENT
import android.hardware.biometrics.ComponentInfoInternal
import android.hardware.face.FaceManager
import android.hardware.face.FaceSensorProperties
import android.hardware.face.FaceSensorPropertiesInternal
import android.os.CancellationSignal
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId.fakeInstanceId
import com.android.internal.logging.UiEventLogger
import com.android.keyguard.FaceAuthUiEvent
import com.android.keyguard.FaceAuthUiEvent.FACE_AUTH_TRIGGERED_ALTERNATE_BIOMETRIC_BOUNCER_SHOWN
import com.android.keyguard.FaceAuthUiEvent.FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.DumpManager
import com.android.systemui.dump.logcatLogBuffer
import com.android.systemui.keyguard.shared.model.AuthenticationStatus
import com.android.systemui.keyguard.shared.model.DetectionStatus
import com.android.systemui.keyguard.shared.model.ErrorAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpAuthenticationStatus
import com.android.systemui.keyguard.shared.model.SuccessAuthenticationStatus
import com.android.systemui.log.FaceAuthenticationLogger
import com.android.systemui.log.SessionTracker
import com.android.systemui.statusbar.phone.KeyguardBypassController
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import java.io.PrintWriter
import java.io.StringWriter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.isNull
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class KeyguardFaceAuthManagerTest : SysuiTestCase() {
    private lateinit var underTest: KeyguardFaceAuthManagerImpl

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
    @Captor
    private lateinit var faceLockoutResetCallback: ArgumentCaptor<FaceManager.LockoutResetCallback>
    private lateinit var testDispatcher: TestDispatcher

    private lateinit var testScope: TestScope
    private lateinit var fakeUserRepository: FakeUserRepository
    private lateinit var authStatus: FlowValue<AuthenticationStatus?>
    private lateinit var detectStatus: FlowValue<DetectionStatus?>
    private lateinit var authRunning: FlowValue<Boolean?>
    private lateinit var lockedOut: FlowValue<Boolean?>

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        fakeUserRepository = FakeUserRepository()
        fakeUserRepository.setUserInfos(listOf(currentUser))
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        whenever(sessionTracker.getSessionId(SESSION_KEYGUARD)).thenReturn(keyguardSessionId)
        whenever(bypassController.bypassEnabled).thenReturn(true)
        underTest = createFaceAuthManagerImpl(faceManager)
    }

    private fun createFaceAuthManagerImpl(
        fmOverride: FaceManager? = faceManager,
        bypassControllerOverride: KeyguardBypassController? = bypassController
    ) =
        KeyguardFaceAuthManagerImpl(
            mContext,
            fmOverride,
            fakeUserRepository,
            bypassControllerOverride,
            testScope.backgroundScope,
            testDispatcher,
            sessionTracker,
            uiEventLogger,
            FaceAuthenticationLogger(logcatLogBuffer("KeyguardFaceAuthManagerLog")),
            dumpManager,
        )

    @Test
    fun faceAuthRunsAndProvidesAuthStatusUpdates() =
        testScope.runTest {
            testSetup(this)

            FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER.extraInfo = 10
            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()
            uiEventIsLogged(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)

            assertThat(authRunning()).isTrue()

            val successResult = successResult()
            authenticationCallback.value.onAuthenticationSucceeded(successResult)

            assertThat(authStatus()).isEqualTo(SuccessAuthenticationStatus(successResult))

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
            testSetup(this)

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
            testSetup(this)
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
            assertThat(createFaceAuthManagerImpl(fmOverride = null).isDetectionSupported).isFalse()

            whenever(faceManager.sensorPropertiesInternal).thenReturn(null)
            assertThat(createFaceAuthManagerImpl().isDetectionSupported).isFalse()

            whenever(faceManager.sensorPropertiesInternal).thenReturn(listOf())
            assertThat(createFaceAuthManagerImpl().isDetectionSupported).isFalse()

            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(listOf(createFaceSensorProperties(supportsFaceDetection = false)))
            assertThat(createFaceAuthManagerImpl().isDetectionSupported).isFalse()

            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(
                    listOf(
                        createFaceSensorProperties(supportsFaceDetection = false),
                        createFaceSensorProperties(supportsFaceDetection = true)
                    )
                )
            assertThat(createFaceAuthManagerImpl().isDetectionSupported).isFalse()

            whenever(faceManager.sensorPropertiesInternal)
                .thenReturn(
                    listOf(
                        createFaceSensorProperties(supportsFaceDetection = true),
                        createFaceSensorProperties(supportsFaceDetection = false)
                    )
                )
            assertThat(createFaceAuthManagerImpl().isDetectionSupported).isTrue()
        }

    @Test
    fun cancelStopsFaceAuthentication() =
        testScope.runTest {
            testSetup(this)

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
            underTest = createFaceAuthManagerImpl()
            testSetup(this)

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
            underTest = createFaceAuthManagerImpl()
            testSetup(this)
            clearInvocations(faceManager)

            underTest.detect()

            verify(faceManager, never()).detectFace(any(), any(), anyInt())
        }

    @Test
    fun faceAuthShouldWaitAndRunIfTriggeredWhileCancelling() =
        testScope.runTest {
            testSetup(this)

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
            testSetup(this)

            underTest.authenticate(FACE_AUTH_TRIGGERED_SWIPE_UP_ON_BOUNCER)
            faceAuthenticateIsCalled()

            clearInvocations(faceManager)
            underTest.cancel()
            advanceTimeBy(KeyguardFaceAuthManagerImpl.DEFAULT_CANCEL_SIGNAL_TIMEOUT + 1)

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
            underTest = createFaceAuthManagerImpl()
            testSetup(this)

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
            fakeUserRepository.setSelectedUserInfo(currentUser)
            underTest.dump(PrintWriter(StringWriter()), emptyArray())

            underTest =
                createFaceAuthManagerImpl(fmOverride = null, bypassControllerOverride = null)
            fakeUserRepository.setSelectedUserInfo(currentUser)

            underTest.dump(PrintWriter(StringWriter()), emptyArray())
        }

    private suspend fun testSetup(testScope: TestScope) {
        with(testScope) {
            authStatus = collectLastValue(underTest.authenticationStatus)
            detectStatus = collectLastValue(underTest.detectionStatus)
            authRunning = collectLastValue(underTest.isAuthRunning)
            lockedOut = collectLastValue(underTest.isLockedOut)
            fakeUserRepository.setSelectedUserInfo(currentUser)
        }
    }

    private fun successResult() = FaceManager.AuthenticationResult(null, null, currentUserId, false)

    private fun faceDetectIsCalled() {
        verify(faceManager)
            .detectFace(
                cancellationSignal.capture(),
                detectionCallback.capture(),
                eq(currentUserId)
            )
    }

    private fun faceAuthenticateIsCalled() {
        verify(faceManager)
            .authenticate(
                isNull(),
                cancellationSignal.capture(),
                authenticationCallback.capture(),
                isNull(),
                eq(currentUserId),
                eq(true)
            )
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
        const val currentUserId = 1
        val keyguardSessionId = fakeInstanceId(10)!!
        val currentUser = UserInfo(currentUserId, "test user", 0)
    }
}
