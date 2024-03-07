/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.keyguard

import android.hardware.biometrics.BiometricSourceType
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.internal.logging.InstanceId
import com.android.internal.logging.UiEventLogger
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE
import com.android.systemui.SysuiTestCase
import com.android.systemui.log.SessionTracker
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.MockitoAnnotations
import org.mockito.Mockito.`when` as whenever

@RunWith(AndroidTestingRunner::class)
@SmallTest
class KeyguardBiometricLockoutLoggerTest : SysuiTestCase() {
    @Mock
    lateinit var uiEventLogger: UiEventLogger
    @Mock
    lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock
    lateinit var strongAuthTracker: KeyguardUpdateMonitor.StrongAuthTracker
    @Mock
    lateinit var sessionTracker: SessionTracker
    @Mock
    lateinit var sessionId: InstanceId
    @Mock
    lateinit var mSelectedUserInteractor: SelectedUserInteractor

    @Captor
    lateinit var updateMonitorCallbackCaptor: ArgumentCaptor<KeyguardUpdateMonitorCallback>
    lateinit var updateMonitorCallback: KeyguardUpdateMonitorCallback

    lateinit var keyguardBiometricLockoutLogger: KeyguardBiometricLockoutLogger

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(keyguardUpdateMonitor.strongAuthTracker).thenReturn(strongAuthTracker)
        whenever(sessionTracker.getSessionId(anyInt())).thenReturn(sessionId)
        keyguardBiometricLockoutLogger = KeyguardBiometricLockoutLogger(
                uiEventLogger,
                keyguardUpdateMonitor,
                sessionTracker,
                mSelectedUserInteractor)
    }

    @Test
    fun test_logsOnStart() {
        // GIVEN is encrypted / lockdown before start
        whenever(keyguardUpdateMonitor.isEncryptedOrLockdown(anyInt()))
                .thenReturn(true)

        // WHEN start
        keyguardBiometricLockoutLogger.start()

        // THEN encrypted / lockdown state is logged
        verify(uiEventLogger).log(KeyguardBiometricLockoutLogger.PrimaryAuthRequiredEvent
                .PRIMARY_AUTH_REQUIRED_ENCRYPTED_OR_LOCKDOWN, sessionId)
    }

    @Test
    fun test_logTimeoutChange() {
        keyguardBiometricLockoutLogger.start()
        captureUpdateMonitorCallback()

        // GIVEN primary auth required b/c timeout
        whenever(strongAuthTracker.getStrongAuthForUser(anyInt()))
                .thenReturn(STRONG_AUTH_REQUIRED_AFTER_TIMEOUT)

        // WHEN primary auth requirement changes
        updateMonitorCallback.onStrongAuthStateChanged(0)

        // THEN primary auth required state is logged
        verify(uiEventLogger).log(KeyguardBiometricLockoutLogger.PrimaryAuthRequiredEvent
                .PRIMARY_AUTH_REQUIRED_TIMEOUT, sessionId)
    }

    @Test
    fun test_logUnattendedUpdate() {
        keyguardBiometricLockoutLogger.start()
        captureUpdateMonitorCallback()

        // GIVEN primary auth required b/c unattended update
        whenever(strongAuthTracker.getStrongAuthForUser(anyInt()))
                .thenReturn(STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE)

        // WHEN primary auth requirement changes
        updateMonitorCallback.onStrongAuthStateChanged(0)

        // THEN primary auth required state is logged
        verify(uiEventLogger).log(KeyguardBiometricLockoutLogger.PrimaryAuthRequiredEvent
                .PRIMARY_AUTH_REQUIRED_UNATTENDED_UPDATE, sessionId)
    }

    @Test
    fun test_logMultipleChanges() {
        keyguardBiometricLockoutLogger.start()
        captureUpdateMonitorCallback()

        // GIVEN primary auth required b/c timeout
        whenever(strongAuthTracker.getStrongAuthForUser(anyInt()))
                .thenReturn(STRONG_AUTH_REQUIRED_AFTER_TIMEOUT
                        or STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE)

        // WHEN primary auth requirement changes
        updateMonitorCallback.onStrongAuthStateChanged(0)

        // THEN primary auth required state is logged with all the reasons
        verify(uiEventLogger).log(KeyguardBiometricLockoutLogger.PrimaryAuthRequiredEvent
                .PRIMARY_AUTH_REQUIRED_TIMEOUT, sessionId)
        verify(uiEventLogger).log(KeyguardBiometricLockoutLogger.PrimaryAuthRequiredEvent
                .PRIMARY_AUTH_REQUIRED_UNATTENDED_UPDATE, sessionId)

        // WHEN onStrongAuthStateChanged is called again
        updateMonitorCallback.onStrongAuthStateChanged(0)

        // THEN no more events are sent since there haven't been any changes
        verifyNoMoreInteractions(uiEventLogger)
    }

    @Test
    fun test_logFaceLockout() {
        keyguardBiometricLockoutLogger.start()
        captureUpdateMonitorCallback()

        // GIVEN primary auth required b/c face lock
        whenever(keyguardUpdateMonitor.isFaceLockedOut).thenReturn(true)

        // WHEN lockout state changes
        updateMonitorCallback.onLockedOutStateChanged(BiometricSourceType.FACE)

        // THEN primary auth required state is logged
        verify(uiEventLogger).log(KeyguardBiometricLockoutLogger.PrimaryAuthRequiredEvent
                .PRIMARY_AUTH_REQUIRED_FACE_LOCKED_OUT, sessionId)

        // WHEN face lockout is reset
        whenever(keyguardUpdateMonitor.isFaceLockedOut).thenReturn(false)
        updateMonitorCallback.onLockedOutStateChanged(BiometricSourceType.FACE)

        // THEN primary auth required state is logged
        verify(uiEventLogger).log(KeyguardBiometricLockoutLogger.PrimaryAuthRequiredEvent
                .PRIMARY_AUTH_REQUIRED_FACE_LOCKED_OUT_RESET, sessionId)
    }

    @Test
    fun test_logFingerprintLockout() {
        keyguardBiometricLockoutLogger.start()
        captureUpdateMonitorCallback()

        // GIVEN primary auth required b/c fingerprint lock
        whenever(keyguardUpdateMonitor.isFingerprintLockedOut).thenReturn(true)

        // WHEN lockout state changes
        updateMonitorCallback.onLockedOutStateChanged(BiometricSourceType.FINGERPRINT)

        // THEN primary auth required state is logged
        verify(uiEventLogger).log(KeyguardBiometricLockoutLogger.PrimaryAuthRequiredEvent
                .PRIMARY_AUTH_REQUIRED_FINGERPRINT_LOCKED_OUT, sessionId)

        // WHEN fingerprint lockout is reset
        whenever(keyguardUpdateMonitor.isFingerprintLockedOut).thenReturn(false)
        updateMonitorCallback.onLockedOutStateChanged(BiometricSourceType.FINGERPRINT)

        // THEN primary auth required state is logged
        verify(uiEventLogger).log(KeyguardBiometricLockoutLogger.PrimaryAuthRequiredEvent
                .PRIMARY_AUTH_REQUIRED_FINGERPRINT_LOCKED_OUT_RESET, sessionId)
    }

    fun captureUpdateMonitorCallback() {
        verify(keyguardUpdateMonitor).registerCallback(updateMonitorCallbackCaptor.capture())
        updateMonitorCallback = updateMonitorCallbackCaptor.value
    }
}
