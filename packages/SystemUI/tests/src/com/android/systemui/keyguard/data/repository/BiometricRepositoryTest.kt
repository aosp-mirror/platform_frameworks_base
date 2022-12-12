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
 *
 */

package com.android.systemui.keyguard.data.repository

import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.content.pm.UserInfo
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class BiometricRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: BiometricRepository

    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    private lateinit var userRepository: FakeUserRepository

    private lateinit var testDispatcher: TestDispatcher
    private lateinit var testScope: TestScope
    private var testableLooper: TestableLooper? = null

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testableLooper = TestableLooper.get(this)
        testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        userRepository = FakeUserRepository()
    }

    private suspend fun createBiometricRepository() {
        userRepository.setUserInfos(listOf(PRIMARY_USER))
        userRepository.setSelectedUserInfo(PRIMARY_USER)
        underTest =
            BiometricRepositoryImpl(
                context = context,
                lockPatternUtils = lockPatternUtils,
                broadcastDispatcher = fakeBroadcastDispatcher,
                authController = authController,
                userRepository = userRepository,
                devicePolicyManager = devicePolicyManager,
                scope = testScope.backgroundScope,
                backgroundDispatcher = testDispatcher,
                looper = testableLooper!!.looper,
            )
    }

    @Test
    fun fingerprintEnrollmentChange() =
        testScope.runTest {
            createBiometricRepository()
            val fingerprintEnabledByDevicePolicy = collectLastValue(underTest.isFingerprintEnrolled)
            runCurrent()

            val captor = argumentCaptor<AuthController.Callback>()
            verify(authController).addCallback(captor.capture())
            whenever(authController.isFingerprintEnrolled(anyInt())).thenReturn(true)
            captor.value.onEnrollmentsChanged(
                BiometricType.UNDER_DISPLAY_FINGERPRINT,
                PRIMARY_USER_ID,
                true
            )
            assertThat(fingerprintEnabledByDevicePolicy()).isTrue()

            whenever(authController.isFingerprintEnrolled(anyInt())).thenReturn(false)
            captor.value.onEnrollmentsChanged(
                BiometricType.UNDER_DISPLAY_FINGERPRINT,
                PRIMARY_USER_ID,
                false
            )
            assertThat(fingerprintEnabledByDevicePolicy()).isFalse()
        }

    @Test
    fun strongBiometricAllowedChange() =
        testScope.runTest {
            createBiometricRepository()
            val strongBiometricAllowed = collectLastValue(underTest.isStrongBiometricAllowed)
            runCurrent()

            val captor = argumentCaptor<LockPatternUtils.StrongAuthTracker>()
            verify(lockPatternUtils).registerStrongAuthTracker(captor.capture())

            captor.value
                .getStub()
                .onStrongAuthRequiredChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            testableLooper?.processAllMessages() // StrongAuthTracker uses the TestableLooper
            assertThat(strongBiometricAllowed()).isTrue()

            captor.value
                .getStub()
                .onStrongAuthRequiredChanged(STRONG_AUTH_REQUIRED_AFTER_BOOT, PRIMARY_USER_ID)
            testableLooper?.processAllMessages() // StrongAuthTracker uses the TestableLooper
            assertThat(strongBiometricAllowed()).isFalse()
        }

    @Test
    fun fingerprintDisabledByDpmChange() =
        testScope.runTest {
            createBiometricRepository()
            val fingerprintEnabledByDevicePolicy =
                collectLastValue(underTest.isFingerprintEnabledByDevicePolicy)
            runCurrent()

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(any(), anyInt()))
                .thenReturn(DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT)
            broadcastDPMStateChange()
            assertThat(fingerprintEnabledByDevicePolicy()).isFalse()

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(any(), anyInt())).thenReturn(0)
            broadcastDPMStateChange()
            assertThat(fingerprintEnabledByDevicePolicy()).isTrue()
        }

    private fun broadcastDPMStateChange() {
        fakeBroadcastDispatcher.registeredReceivers.forEach { receiver ->
            receiver.onReceive(
                context,
                Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED)
            )
        }
    }

    companion object {
        private const val PRIMARY_USER_ID = 0
        private val PRIMARY_USER =
            UserInfo(
                /* id= */ PRIMARY_USER_ID,
                /* name= */ "primary user",
                /* flags= */ UserInfo.FLAG_PRIMARY
            )
    }
}
