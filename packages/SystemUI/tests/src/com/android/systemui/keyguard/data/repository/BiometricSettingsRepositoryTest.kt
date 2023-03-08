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
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FACE
import android.app.admin.DevicePolicyManager.KEYGUARD_DISABLE_FINGERPRINT
import android.content.Intent
import android.content.pm.UserInfo
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.IBiometricEnabledOnKeyguardCallback
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.data.repository.BiometricType.FACE
import com.android.systemui.keyguard.data.repository.BiometricType.REAR_FINGERPRINT
import com.android.systemui.keyguard.data.repository.BiometricType.SIDE_FINGERPRINT
import com.android.systemui.keyguard.data.repository.BiometricType.UNDER_DISPLAY_FINGERPRINT
import com.android.systemui.keyguard.shared.model.DevicePosture
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.argumentCaptor
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.isNull
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidTestingRunner::class)
class BiometricSettingsRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: BiometricSettingsRepository

    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var biometricManager: BiometricManager
    @Captor private lateinit var authControllerCallback: ArgumentCaptor<AuthController.Callback>
    @Captor
    private lateinit var biometricManagerCallback:
        ArgumentCaptor<IBiometricEnabledOnKeyguardCallback.Stub>
    private lateinit var userRepository: FakeUserRepository
    private lateinit var devicePostureRepository: FakeDevicePostureRepository

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
        devicePostureRepository = FakeDevicePostureRepository()
    }

    private suspend fun createBiometricSettingsRepository() {
        userRepository.setUserInfos(listOf(PRIMARY_USER, ANOTHER_USER))
        userRepository.setSelectedUserInfo(PRIMARY_USER)
        underTest =
            BiometricSettingsRepositoryImpl(
                context = context,
                lockPatternUtils = lockPatternUtils,
                broadcastDispatcher = fakeBroadcastDispatcher,
                authController = authController,
                userRepository = userRepository,
                devicePolicyManager = devicePolicyManager,
                scope = testScope.backgroundScope,
                backgroundDispatcher = testDispatcher,
                looper = testableLooper!!.looper,
                dumpManager = dumpManager,
                biometricManager = biometricManager,
                devicePostureRepository = devicePostureRepository,
            )
        testScope.runCurrent()
    }

    @Test
    fun fingerprintEnrollmentChange() =
        testScope.runTest {
            createBiometricSettingsRepository()
            val fingerprintEnrolled = collectLastValue(underTest.isFingerprintEnrolled)
            runCurrent()

            verify(authController).addCallback(authControllerCallback.capture())
            whenever(authController.isFingerprintEnrolled(anyInt())).thenReturn(true)
            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, PRIMARY_USER_ID, true)
            assertThat(fingerprintEnrolled()).isTrue()

            whenever(authController.isFingerprintEnrolled(anyInt())).thenReturn(false)
            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, ANOTHER_USER_ID, false)
            assertThat(fingerprintEnrolled()).isTrue()

            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, PRIMARY_USER_ID, false)
            assertThat(fingerprintEnrolled()).isFalse()
        }

    @Test
    fun strongBiometricAllowedChange() =
        testScope.runTest {
            createBiometricSettingsRepository()
            val strongBiometricAllowed = collectLastValue(underTest.isStrongBiometricAllowed)
            runCurrent()

            val captor = argumentCaptor<LockPatternUtils.StrongAuthTracker>()
            verify(lockPatternUtils).registerStrongAuthTracker(captor.capture())

            captor.value.stub.onStrongAuthRequiredChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            testableLooper?.processAllMessages() // StrongAuthTracker uses the TestableLooper
            assertThat(strongBiometricAllowed()).isTrue()

            captor.value.stub.onStrongAuthRequiredChanged(
                STRONG_AUTH_REQUIRED_AFTER_BOOT,
                PRIMARY_USER_ID
            )
            testableLooper?.processAllMessages() // StrongAuthTracker uses the TestableLooper
            assertThat(strongBiometricAllowed()).isFalse()
        }

    @Test
    fun fingerprintDisabledByDpmChange() =
        testScope.runTest {
            createBiometricSettingsRepository()
            val fingerprintEnabledByDevicePolicy =
                collectLastValue(underTest.isFingerprintEnabledByDevicePolicy)
            runCurrent()

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(any(), anyInt()))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT)
            broadcastDPMStateChange()
            assertThat(fingerprintEnabledByDevicePolicy()).isFalse()

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(any(), anyInt())).thenReturn(0)
            broadcastDPMStateChange()
            assertThat(fingerprintEnabledByDevicePolicy()).isTrue()
        }

    @Test
    fun faceEnrollmentChangeIsPropagatedForTheCurrentUser() =
        testScope.runTest {
            createBiometricSettingsRepository()
            runCurrent()
            clearInvocations(authController)

            whenever(authController.isFaceAuthEnrolled(PRIMARY_USER_ID)).thenReturn(false)
            val faceEnrolled = collectLastValue(underTest.isFaceEnrolled)

            assertThat(faceEnrolled()).isFalse()
            verify(authController).addCallback(authControllerCallback.capture())
            enrollmentChange(REAR_FINGERPRINT, PRIMARY_USER_ID, true)

            assertThat(faceEnrolled()).isFalse()

            enrollmentChange(SIDE_FINGERPRINT, PRIMARY_USER_ID, true)

            assertThat(faceEnrolled()).isFalse()

            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, PRIMARY_USER_ID, true)

            assertThat(faceEnrolled()).isFalse()

            enrollmentChange(FACE, ANOTHER_USER_ID, true)

            assertThat(faceEnrolled()).isFalse()

            enrollmentChange(FACE, PRIMARY_USER_ID, true)

            assertThat(faceEnrolled()).isTrue()
        }

    @Test
    fun faceEnrollmentStatusOfNewUserUponUserSwitch() =
        testScope.runTest {
            createBiometricSettingsRepository()
            runCurrent()
            clearInvocations(authController)

            whenever(authController.isFaceAuthEnrolled(PRIMARY_USER_ID)).thenReturn(false)
            whenever(authController.isFaceAuthEnrolled(ANOTHER_USER_ID)).thenReturn(true)
            val faceEnrolled = collectLastValue(underTest.isFaceEnrolled)

            assertThat(faceEnrolled()).isFalse()
        }

    @Test
    fun faceEnrollmentChangesArePropagatedAfterUserSwitch() =
        testScope.runTest {
            createBiometricSettingsRepository()

            userRepository.setSelectedUserInfo(ANOTHER_USER)
            runCurrent()
            clearInvocations(authController)

            val faceEnrolled = collectLastValue(underTest.isFaceEnrolled)
            runCurrent()

            verify(authController).addCallback(authControllerCallback.capture())

            enrollmentChange(FACE, ANOTHER_USER_ID, true)

            assertThat(faceEnrolled()).isTrue()
        }

    @Test
    fun devicePolicyControlsFaceAuthenticationEnabledState() =
        testScope.runTest {
            createBiometricSettingsRepository()
            verify(biometricManager)
                .registerEnabledOnKeyguardCallback(biometricManagerCallback.capture())

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(isNull(), eq(PRIMARY_USER_ID)))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT or KEYGUARD_DISABLE_FACE)

            val isFaceAuthEnabled = collectLastValue(underTest.isFaceAuthenticationEnabled)
            runCurrent()

            broadcastDPMStateChange()

            assertThat(isFaceAuthEnabled()).isFalse()

            biometricManagerCallback.value.onChanged(true, PRIMARY_USER_ID)
            runCurrent()
            assertThat(isFaceAuthEnabled()).isFalse()

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(isNull(), eq(PRIMARY_USER_ID)))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT)
            broadcastDPMStateChange()

            assertThat(isFaceAuthEnabled()).isTrue()
        }

    @Test
    fun biometricManagerControlsFaceAuthenticationEnabledStatus() =
        testScope.runTest {
            createBiometricSettingsRepository()
            verify(biometricManager)
                .registerEnabledOnKeyguardCallback(biometricManagerCallback.capture())

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(isNull(), eq(PRIMARY_USER_ID)))
                .thenReturn(0)
            broadcastDPMStateChange()

            biometricManagerCallback.value.onChanged(true, PRIMARY_USER_ID)
            val isFaceAuthEnabled = collectLastValue(underTest.isFaceAuthenticationEnabled)

            assertThat(isFaceAuthEnabled()).isTrue()

            biometricManagerCallback.value.onChanged(false, PRIMARY_USER_ID)

            assertThat(isFaceAuthEnabled()).isFalse()
        }

    @Test
    fun biometricManagerCallbackIsRegisteredOnlyOnce() =
        testScope.runTest {
            createBiometricSettingsRepository()

            collectLastValue(underTest.isFaceAuthenticationEnabled)()
            collectLastValue(underTest.isFaceAuthenticationEnabled)()
            collectLastValue(underTest.isFaceAuthenticationEnabled)()

            verify(biometricManager, times(1)).registerEnabledOnKeyguardCallback(any())
        }

    @Test
    fun faceAuthIsAlwaysSupportedIfSpecificPostureIsNotConfigured() =
        testScope.runTest {
            overrideResource(
                R.integer.config_face_auth_supported_posture,
                DevicePostureController.DEVICE_POSTURE_UNKNOWN
            )

            createBiometricSettingsRepository()

            assertThat(collectLastValue(underTest.isFaceAuthSupportedInCurrentPosture)()).isTrue()
        }

    @Test
    fun faceAuthIsSupportedOnlyWhenDevicePostureMatchesConfigValue() =
        testScope.runTest {
            overrideResource(
                R.integer.config_face_auth_supported_posture,
                DevicePostureController.DEVICE_POSTURE_FLIPPED
            )

            createBiometricSettingsRepository()

            val isFaceAuthSupported =
                collectLastValue(underTest.isFaceAuthSupportedInCurrentPosture)

            assertThat(isFaceAuthSupported()).isFalse()

            devicePostureRepository.setCurrentPosture(DevicePosture.CLOSED)
            assertThat(isFaceAuthSupported()).isFalse()

            devicePostureRepository.setCurrentPosture(DevicePosture.HALF_OPENED)
            assertThat(isFaceAuthSupported()).isFalse()

            devicePostureRepository.setCurrentPosture(DevicePosture.OPENED)
            assertThat(isFaceAuthSupported()).isFalse()

            devicePostureRepository.setCurrentPosture(DevicePosture.UNKNOWN)
            assertThat(isFaceAuthSupported()).isFalse()

            devicePostureRepository.setCurrentPosture(DevicePosture.FLIPPED)
            assertThat(isFaceAuthSupported()).isTrue()
        }

    private fun enrollmentChange(biometricType: BiometricType, userId: Int, enabled: Boolean) {
        authControllerCallback.value.onEnrollmentsChanged(biometricType, userId, enabled)
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

        private const val ANOTHER_USER_ID = 1
        private val ANOTHER_USER =
            UserInfo(
                /* id= */ ANOTHER_USER_ID,
                /* name= */ "another user",
                /* flags= */ UserInfo.FLAG_PRIMARY
            )
    }
}
