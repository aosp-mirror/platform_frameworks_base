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
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN
import com.android.systemui.R
import com.android.systemui.RoboPilotTest
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
@RoboPilotTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class BiometricSettingsRepositoryTest : SysuiTestCase() {
    private lateinit var underTest: BiometricSettingsRepository

    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var devicePolicyManager: DevicePolicyManager
    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var biometricManager: BiometricManager
    @Captor
    private lateinit var strongAuthTracker: ArgumentCaptor<LockPatternUtils.StrongAuthTracker>
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
                biometricManager = biometricManager,
                devicePostureRepository = devicePostureRepository,
                dumpManager = dumpManager,
            )
        testScope.runCurrent()
        verify(lockPatternUtils).registerStrongAuthTracker(strongAuthTracker.capture())
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

            onStrongAuthChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            assertThat(strongBiometricAllowed()).isTrue()

            onStrongAuthChanged(STRONG_AUTH_REQUIRED_AFTER_BOOT, PRIMARY_USER_ID)
            assertThat(strongBiometricAllowed()).isFalse()
        }

    @Test
    fun convenienceBiometricAllowedChange() =
        testScope.runTest {
            createBiometricSettingsRepository()
            val convenienceBiometricAllowed =
                collectLastValue(underTest.isNonStrongBiometricAllowed)
            runCurrent()

            onNonStrongAuthChanged(true, PRIMARY_USER_ID)
            assertThat(convenienceBiometricAllowed()).isTrue()

            onNonStrongAuthChanged(false, ANOTHER_USER_ID)
            assertThat(convenienceBiometricAllowed()).isTrue()

            onNonStrongAuthChanged(false, PRIMARY_USER_ID)
            assertThat(convenienceBiometricAllowed()).isFalse()
        }

    private fun onStrongAuthChanged(flags: Int, userId: Int) {
        strongAuthTracker.value.stub.onStrongAuthRequiredChanged(flags, userId)
        testableLooper?.processAllMessages() // StrongAuthTracker uses the TestableLooper
    }

    private fun onNonStrongAuthChanged(allowed: Boolean, userId: Int) {
        strongAuthTracker.value.stub.onIsNonStrongBiometricAllowedChanged(allowed, userId)
        testableLooper?.processAllMessages() // StrongAuthTracker uses the TestableLooper
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

            whenever(authController.isFaceAuthEnrolled(ANOTHER_USER_ID)).thenReturn(true)

            enrollmentChange(FACE, ANOTHER_USER_ID, true)

            assertThat(faceEnrolled()).isFalse()

            whenever(authController.isFaceAuthEnrolled(PRIMARY_USER_ID)).thenReturn(true)

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

            whenever(authController.isFaceAuthEnrolled(ANOTHER_USER_ID)).thenReturn(true)
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
            val isFaceAuthEnabled = collectLastValue(underTest.isFaceAuthenticationEnabled)

            assertThat(isFaceAuthEnabled()).isFalse()

            // Value changes for another user
            biometricManagerCallback.value.onChanged(true, ANOTHER_USER_ID)

            assertThat(isFaceAuthEnabled()).isFalse()

            // Value changes for current user.
            biometricManagerCallback.value.onChanged(true, PRIMARY_USER_ID)

            assertThat(isFaceAuthEnabled()).isTrue()
        }

    @Test
    fun userChange_biometricEnabledChange_handlesRaceCondition() =
        testScope.runTest {
            createBiometricSettingsRepository()
            verify(biometricManager)
                .registerEnabledOnKeyguardCallback(biometricManagerCallback.capture())
            val isFaceAuthEnabled = collectLastValue(underTest.isFaceAuthenticationEnabled)
            biometricManagerCallback.value.onChanged(true, ANOTHER_USER_ID)
            runCurrent()

            userRepository.setSelectedUserInfo(ANOTHER_USER)
            runCurrent()

            assertThat(isFaceAuthEnabled()).isTrue()
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

    @Test
    fun userInLockdownUsesAuthFlagsToDetermineValue() =
        testScope.runTest {
            createBiometricSettingsRepository()

            val isUserInLockdown = collectLastValue(underTest.isCurrentUserInLockdown)
            // has default value.
            assertThat(isUserInLockdown()).isFalse()

            // change strong auth flags for another user.
            // Combine with one more flag to check if we do the bitwise and
            val inLockdown =
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN or STRONG_AUTH_REQUIRED_AFTER_TIMEOUT
            onStrongAuthChanged(inLockdown, ANOTHER_USER_ID)

            // Still false.
            assertThat(isUserInLockdown()).isFalse()

            // change strong auth flags for current user.
            onStrongAuthChanged(inLockdown, PRIMARY_USER_ID)

            assertThat(isUserInLockdown()).isTrue()
        }

    @Test
    fun authFlagChangesForCurrentUserArePropagated() =
        testScope.runTest {
            createBiometricSettingsRepository()

            val authFlags = collectLastValue(underTest.authenticationFlags)
            // has default value.
            val defaultStrongAuthValue = STRONG_AUTH_REQUIRED_AFTER_BOOT
            assertThat(authFlags()!!.flag).isEqualTo(defaultStrongAuthValue)

            // change strong auth flags for another user.
            // Combine with one more flag to check if we do the bitwise and
            val inLockdown =
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN or STRONG_AUTH_REQUIRED_AFTER_TIMEOUT
            onStrongAuthChanged(inLockdown, ANOTHER_USER_ID)

            // Still false.
            assertThat(authFlags()!!.flag).isEqualTo(defaultStrongAuthValue)

            // change strong auth flags for current user.
            onStrongAuthChanged(inLockdown, PRIMARY_USER_ID)

            assertThat(authFlags()!!.flag).isEqualTo(inLockdown)

            onStrongAuthChanged(STRONG_AUTH_REQUIRED_AFTER_TIMEOUT, ANOTHER_USER_ID)

            assertThat(authFlags()!!.flag).isEqualTo(inLockdown)

            userRepository.setSelectedUserInfo(ANOTHER_USER)
            assertThat(authFlags()!!.flag).isEqualTo(STRONG_AUTH_REQUIRED_AFTER_TIMEOUT)
        }

    private fun enrollmentChange(biometricType: BiometricType, userId: Int, enabled: Boolean) {
        authControllerCallback.value.onEnrollmentsChanged(biometricType, userId, enabled)
    }

    private fun broadcastDPMStateChange() {
        fakeBroadcastDispatcher.sendIntentToMatchingReceiversOnly(
            context,
            Intent(DevicePolicyManager.ACTION_DEVICE_POLICY_MANAGER_STATE_CHANGED),
        )
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
