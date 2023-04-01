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

import android.hardware.biometrics.BiometricAuthenticator.TYPE_FINGERPRINT
import android.hardware.biometrics.BiometricSourceType
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.DumpManager
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class DeviceEntryFingerprintAuthRepositoryTest : SysuiTestCase() {
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var authController: AuthController
    @Captor
    private lateinit var updateMonitorCallback: ArgumentCaptor<KeyguardUpdateMonitorCallback>

    private lateinit var testScope: TestScope

    private lateinit var underTest: DeviceEntryFingerprintAuthRepository

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()

        underTest =
            DeviceEntryFingerprintAuthRepositoryImpl(
                authController,
                keyguardUpdateMonitor,
                testScope.backgroundScope,
                dumpManager,
            )
    }

    @Test
    fun isLockedOut_whenFingerprintLockoutStateChanges_emitsNewValue() =
        testScope.runTest {
            val isLockedOutValue = collectLastValue(underTest.isLockedOut)
            runCurrent()

            verify(keyguardUpdateMonitor).registerCallback(updateMonitorCallback.capture())
            val callback = updateMonitorCallback.value
            whenever(keyguardUpdateMonitor.isFingerprintLockedOut).thenReturn(true)

            callback.onLockedOutStateChanged(BiometricSourceType.FACE)
            assertThat(isLockedOutValue()).isFalse()

            callback.onLockedOutStateChanged(BiometricSourceType.FINGERPRINT)
            assertThat(isLockedOutValue()).isTrue()

            whenever(keyguardUpdateMonitor.isFingerprintLockedOut).thenReturn(false)
            callback.onLockedOutStateChanged(BiometricSourceType.FINGERPRINT)
            assertThat(isLockedOutValue()).isFalse()
        }

    @Test
    fun fpRunningStateIsPropagated() =
        testScope.runTest {
            val isRunning = collectLastValue(underTest.isRunning)
            whenever(keyguardUpdateMonitor.isFingerprintDetectionRunning).thenReturn(true)

            // Initial value is available
            assertThat(isRunning()).isTrue()

            verify(keyguardUpdateMonitor, atLeastOnce())
                .registerCallback(updateMonitorCallback.capture())
            invokeOnCallback {
                it.onBiometricRunningStateChanged(false, BiometricSourceType.FINGERPRINT)
            }

            assertThat(isRunning()).isFalse()

            invokeOnCallback { it.onBiometricRunningStateChanged(true, BiometricSourceType.FACE) }

            assertThat(isRunning()).isFalse()

            updateMonitorCallback.value.onBiometricRunningStateChanged(
                true,
                BiometricSourceType.FINGERPRINT
            )
            assertThat(isRunning()).isTrue()
        }

    private fun invokeOnCallback(action: (KeyguardUpdateMonitorCallback) -> Unit) {
        updateMonitorCallback.allValues.forEach { action(it) }
    }

    @Test
    fun enabledFingerprintTypeProvidesTheCorrectOutputForSpfs() =
        testScope.runTest {
            whenever(authController.isSfpsSupported).thenReturn(true)
            whenever(authController.isUdfpsSupported).thenReturn(false)
            whenever(authController.isRearFpsSupported).thenReturn(false)

            val availableFpSensorType = collectLastValue(underTest.availableFpSensorType)
            assertThat(availableFpSensorType()).isEqualTo(BiometricType.SIDE_FINGERPRINT)
        }

    @Test
    fun enabledFingerprintTypeProvidesTheCorrectOutputForUdfps() =
        testScope.runTest {
            whenever(authController.isSfpsSupported).thenReturn(false)
            whenever(authController.isUdfpsSupported).thenReturn(true)
            whenever(authController.isRearFpsSupported).thenReturn(false)
            val availableFpSensorType = collectLastValue(underTest.availableFpSensorType)
            assertThat(availableFpSensorType()).isEqualTo(BiometricType.UNDER_DISPLAY_FINGERPRINT)
        }

    @Test
    fun enabledFingerprintTypeProvidesTheCorrectOutputForRearFps() =
        testScope.runTest {
            whenever(authController.isSfpsSupported).thenReturn(false)
            whenever(authController.isUdfpsSupported).thenReturn(false)
            whenever(authController.isRearFpsSupported).thenReturn(true)

            val availableFpSensorType = collectLastValue(underTest.availableFpSensorType)

            assertThat(availableFpSensorType()).isEqualTo(BiometricType.REAR_FINGERPRINT)
        }

    @Test
    fun enabledFingerprintTypeProvidesTheCorrectOutputAfterAllAuthenticatorsAreRegistered() =
        testScope.runTest {
            whenever(authController.isSfpsSupported).thenReturn(false)
            whenever(authController.isUdfpsSupported).thenReturn(false)
            whenever(authController.isRearFpsSupported).thenReturn(false)
            whenever(authController.areAllFingerprintAuthenticatorsRegistered()).thenReturn(false)

            val availableFpSensorType = collectLastValue(underTest.availableFpSensorType)
            runCurrent()

            val callback = ArgumentCaptor.forClass(AuthController.Callback::class.java)
            verify(authController).addCallback(callback.capture())
            assertThat(availableFpSensorType()).isNull()

            whenever(authController.isUdfpsSupported).thenReturn(true)
            callback.value.onAllAuthenticatorsRegistered(TYPE_FINGERPRINT)
            assertThat(availableFpSensorType()).isEqualTo(BiometricType.UNDER_DISPLAY_FINGERPRINT)
        }
}
