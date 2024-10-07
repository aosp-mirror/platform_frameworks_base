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
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.biometrics.data.repository.FaceSensorInfo
import com.android.systemui.biometrics.data.repository.FakeFacePropertyRepository
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.dump.DumpManager
import com.android.systemui.keyguard.data.repository.BiometricType.FACE
import com.android.systemui.keyguard.data.repository.BiometricType.REAR_FINGERPRINT
import com.android.systemui.keyguard.data.repository.BiometricType.SIDE_FINGERPRINT
import com.android.systemui.keyguard.data.repository.BiometricType.UNDER_DISPLAY_FINGERPRINT
import com.android.systemui.keyguard.shared.model.DevicePosture
import com.android.systemui.res.R
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.statusbar.policy.DevicePostureController
import com.android.systemui.testKosmos
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
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class BiometricSettingsRepositoryTest : SysuiTestCase() {
    private val kosmos = testKosmos()

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
    private lateinit var facePropertyRepository: FakeFacePropertyRepository
    private lateinit var fingerprintPropertyRepository: FakeFingerprintPropertyRepository
    private val mobileConnectionsRepository = kosmos.fakeMobileConnectionsRepository

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
        facePropertyRepository = FakeFacePropertyRepository()
        fingerprintPropertyRepository = FakeFingerprintPropertyRepository()
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
                facePropertyRepository = facePropertyRepository,
                fingerprintPropertyRepository = fingerprintPropertyRepository,
                mobileConnectionsRepository = mobileConnectionsRepository,
            )
        testScope.runCurrent()
        fingerprintPropertyRepository.setProperties(
            1,
            SensorStrength.STRONG,
            FingerprintSensorType.UDFPS_OPTICAL,
            emptyMap()
        )
        verify(lockPatternUtils).registerStrongAuthTracker(strongAuthTracker.capture())
        verify(authController, times(2)).addCallback(authControllerCallback.capture())
    }

    @Test
    fun fingerprintEnrollmentChange() =
        testScope.runTest {
            createBiometricSettingsRepository()
            biometricsAreEnabledBySettings()
            val fingerprintAllowed = collectLastValue(underTest.isFingerprintEnrolledAndEnabled)
            runCurrent()

            whenever(authController.isFingerprintEnrolled(anyInt())).thenReturn(true)
            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, PRIMARY_USER_ID, true)
            assertThat(fingerprintAllowed()).isTrue()

            whenever(authController.isFingerprintEnrolled(anyInt())).thenReturn(false)
            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, ANOTHER_USER_ID, false)
            assertThat(fingerprintAllowed()).isTrue()

            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, PRIMARY_USER_ID, false)
            assertThat(fingerprintAllowed()).isFalse()
        }

    @Test
    fun fingerprintEnabledStateChange() =
        testScope.runTest {
            createBiometricSettingsRepository()
            biometricsAreEnabledBySettings()
            val fingerprintAllowed = collectLastValue(underTest.isFingerprintEnrolledAndEnabled)
            runCurrent()

            // start state
            whenever(authController.isFingerprintEnrolled(anyInt())).thenReturn(true)
            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, PRIMARY_USER_ID, true)
            assertThat(fingerprintAllowed()).isTrue()

            // when biometrics are not enabled by settings
            biometricsAreNotEnabledBySettings()
            assertThat(fingerprintAllowed()).isFalse()

            // when biometrics are enabled by settings
            biometricsAreEnabledBySettings()
            assertThat(fingerprintAllowed()).isTrue()
        }

    @Test
    fun strongBiometricAllowedChange() =
        testScope.runTest {
            fingerprintIsEnrolled()
            doNotDisableKeyguardAuthFeatures()
            createBiometricSettingsRepository()
            biometricsAreEnabledBySettings()

            val strongBiometricAllowed by
                collectLastValue(underTest.isFingerprintAuthCurrentlyAllowed)
            runCurrent()

            onStrongAuthChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            assertThat(strongBiometricAllowed).isTrue()

            onStrongAuthChanged(STRONG_AUTH_REQUIRED_AFTER_BOOT, PRIMARY_USER_ID)
            assertThat(strongBiometricAllowed).isFalse()
        }

    @Test
    fun convenienceBiometricAllowedChange() =
        testScope.runTest {
            overrideResource(com.android.internal.R.bool.config_strongAuthRequiredOnBoot, false)
            deviceIsInPostureThatSupportsFaceAuth()
            faceAuthIsEnrolled()
            faceAuthIsNonStrongBiometric()
            createBiometricSettingsRepository()
            val convenienceFaceAuthAllowed by collectLastValue(underTest.isFaceAuthCurrentlyAllowed)
            doNotDisableKeyguardAuthFeatures()
            biometricsAreEnabledBySettings()

            onStrongAuthChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            onNonStrongAuthChanged(true, PRIMARY_USER_ID)
            runCurrent()
            assertThat(convenienceFaceAuthAllowed).isTrue()

            onNonStrongAuthChanged(false, ANOTHER_USER_ID)
            assertThat(convenienceFaceAuthAllowed).isTrue()

            onNonStrongAuthChanged(false, PRIMARY_USER_ID)
            assertThat(convenienceFaceAuthAllowed).isFalse()
            mContext.orCreateTestableResources.removeOverride(
                com.android.internal.R.bool.config_strongAuthRequiredOnBoot
            )
        }

    private fun faceAuthIsNonStrongBiometric() {
        facePropertyRepository.setSensorInfo(FaceSensorInfo(1, SensorStrength.CONVENIENCE))
    }

    private fun faceAuthIsStrongBiometric() {
        facePropertyRepository.setSensorInfo(FaceSensorInfo(1, SensorStrength.STRONG))
    }

    private fun deviceIsInPostureThatSupportsFaceAuth() {
        overrideResource(
            R.integer.config_face_auth_supported_posture,
            DevicePostureController.DEVICE_POSTURE_FLIPPED
        )
        devicePostureRepository.setCurrentPosture(DevicePosture.FLIPPED)
    }

    @Test
    fun whenStrongAuthRequiredAfterBoot_nonStrongBiometricNotAllowed() =
        testScope.runTest {
            overrideResource(com.android.internal.R.bool.config_strongAuthRequiredOnBoot, true)
            createBiometricSettingsRepository()
            faceAuthIsNonStrongBiometric()
            faceAuthIsEnrolled()
            doNotDisableKeyguardAuthFeatures()
            biometricsAreEnabledBySettings()

            val convenienceBiometricAllowed = collectLastValue(underTest.isFaceAuthCurrentlyAllowed)
            runCurrent()
            onNonStrongAuthChanged(true, PRIMARY_USER_ID)

            assertThat(convenienceBiometricAllowed()).isFalse()
            mContext.orCreateTestableResources.removeOverride(
                com.android.internal.R.bool.config_strongAuthRequiredOnBoot
            )
        }

    @Test
    fun whenStrongBiometricAuthIsNotAllowed_nonStrongBiometrics_alsoNotAllowed() =
        testScope.runTest {
            overrideResource(com.android.internal.R.bool.config_strongAuthRequiredOnBoot, false)
            faceAuthIsNonStrongBiometric()
            deviceIsInPostureThatSupportsFaceAuth()
            faceAuthIsEnrolled()
            createBiometricSettingsRepository()
            doNotDisableKeyguardAuthFeatures()
            biometricsAreEnabledBySettings()
            runCurrent()

            val convenienceBiometricAllowed by
                collectLastValue(underTest.isFaceAuthCurrentlyAllowed)

            onStrongAuthChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            onNonStrongAuthChanged(true, PRIMARY_USER_ID)
            runCurrent()
            assertThat(convenienceBiometricAllowed).isTrue()

            onStrongAuthChanged(STRONG_AUTH_REQUIRED_AFTER_TIMEOUT, PRIMARY_USER_ID)
            assertThat(convenienceBiometricAllowed).isFalse()
            mContext.orCreateTestableResources.removeOverride(
                com.android.internal.R.bool.config_strongAuthRequiredOnBoot
            )
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
            fingerprintIsEnrolled(PRIMARY_USER_ID)
            createBiometricSettingsRepository()
            biometricsAreEnabledBySettings()

            val fingerprintEnabledByDevicePolicy =
                collectLastValue(underTest.isFingerprintEnrolledAndEnabled)
            runCurrent()

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(any(), anyInt()))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT)
            broadcastDPMStateChange()
            assertThat(fingerprintEnabledByDevicePolicy()).isFalse()

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(any(), anyInt())).thenReturn(0)
            broadcastDPMStateChange()
            assertThat(fingerprintEnabledByDevicePolicy()).isTrue()
        }

    private fun fingerprintIsEnrolled(userId: Int = PRIMARY_USER_ID) {
        whenever(authController.isFingerprintEnrolled(userId)).thenReturn(true)
    }

    @Test
    fun faceEnrollmentChangeIsPropagatedForTheCurrentUser() =
        testScope.runTest {
            createBiometricSettingsRepository()
            val faceAuthAllowed = collectLastValue(underTest.isFaceAuthEnrolledAndEnabled)

            biometricsAreEnabledBySettings()

            doNotDisableKeyguardAuthFeatures(PRIMARY_USER_ID)

            runCurrent()

            enrollmentChange(FACE, PRIMARY_USER_ID, false)

            assertThat(faceAuthAllowed()).isFalse()

            enrollmentChange(REAR_FINGERPRINT, PRIMARY_USER_ID, true)

            assertThat(faceAuthAllowed()).isFalse()

            enrollmentChange(SIDE_FINGERPRINT, PRIMARY_USER_ID, true)

            assertThat(faceAuthAllowed()).isFalse()

            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, PRIMARY_USER_ID, true)

            assertThat(faceAuthAllowed()).isFalse()

            whenever(authController.isFaceAuthEnrolled(ANOTHER_USER_ID)).thenReturn(true)

            enrollmentChange(FACE, ANOTHER_USER_ID, true)

            assertThat(faceAuthAllowed()).isFalse()

            faceAuthIsEnrolled()

            enrollmentChange(FACE, PRIMARY_USER_ID, true)

            assertThat(faceAuthAllowed()).isTrue()
        }

    private fun biometricsAreEnabledBySettings(userId: Int = PRIMARY_USER_ID) {
        verify(biometricManager, atLeastOnce())
            .registerEnabledOnKeyguardCallback(biometricManagerCallback.capture())
        biometricManagerCallback.value.onChanged(true, userId)
    }

    private fun biometricsAreNotEnabledBySettings(userId: Int = PRIMARY_USER_ID) {
        verify(biometricManager, atLeastOnce())
            .registerEnabledOnKeyguardCallback(biometricManagerCallback.capture())
        biometricManagerCallback.value.onChanged(false, userId)
    }

    @Test
    fun faceEnrollmentStatusOfNewUserUponUserSwitch() =
        testScope.runTest {
            createBiometricSettingsRepository()
            runCurrent()
            clearInvocations(authController)

            whenever(authController.isFaceAuthEnrolled(PRIMARY_USER_ID)).thenReturn(false)
            whenever(authController.isFaceAuthEnrolled(ANOTHER_USER_ID)).thenReturn(true)
            val faceAuthAllowed = collectLastValue(underTest.isFaceAuthEnrolledAndEnabled)

            assertThat(faceAuthAllowed()).isFalse()
        }

    @Test
    fun faceEnrollmentChangesArePropagatedAfterUserSwitch() =
        testScope.runTest {
            createBiometricSettingsRepository()
            val faceAuthAllowed by collectLastValue(underTest.isFaceAuthEnrolledAndEnabled)

            verify(biometricManager)
                .registerEnabledOnKeyguardCallback(biometricManagerCallback.capture())

            userRepository.setSelectedUserInfo(ANOTHER_USER)
            doNotDisableKeyguardAuthFeatures(ANOTHER_USER_ID)
            biometricManagerCallback.value.onChanged(true, ANOTHER_USER_ID)
            onNonStrongAuthChanged(true, ANOTHER_USER_ID)
            whenever(authController.isFaceAuthEnrolled(ANOTHER_USER_ID)).thenReturn(true)
            enrollmentChange(FACE, ANOTHER_USER_ID, true)
            runCurrent()

            assertThat(faceAuthAllowed).isTrue()
        }

    @Test
    fun devicePolicyControlsFaceAuthenticationEnabledState() =
        testScope.runTest {
            faceAuthIsEnrolled()

            createBiometricSettingsRepository()
            verify(biometricManager)
                .registerEnabledOnKeyguardCallback(biometricManagerCallback.capture())

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(isNull(), eq(PRIMARY_USER_ID)))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT or KEYGUARD_DISABLE_FACE)

            val isFaceAuthAllowed = collectLastValue(underTest.isFaceAuthEnrolledAndEnabled)
            runCurrent()

            broadcastDPMStateChange()

            assertThat(isFaceAuthAllowed()).isFalse()

            biometricManagerCallback.value.onChanged(true, PRIMARY_USER_ID)
            runCurrent()
            assertThat(isFaceAuthAllowed()).isFalse()

            whenever(devicePolicyManager.getKeyguardDisabledFeatures(isNull(), eq(PRIMARY_USER_ID)))
                .thenReturn(KEYGUARD_DISABLE_FINGERPRINT)
            broadcastDPMStateChange()

            assertThat(isFaceAuthAllowed()).isTrue()
        }

    @Test
    fun anySimSecure_disablesFaceAuth() =
        testScope.runTest {
            faceAuthIsEnrolled()
            createBiometricSettingsRepository()

            biometricsAreEnabledBySettings()
            doNotDisableKeyguardAuthFeatures()
            mobileConnectionsRepository.isAnySimSecure.value = false
            runCurrent()

            val isFaceAuthEnabledAndEnrolled by
                collectLastValue(underTest.isFaceAuthEnrolledAndEnabled)

            assertThat(isFaceAuthEnabledAndEnrolled).isTrue()

            mobileConnectionsRepository.isAnySimSecure.value = true
            runCurrent()

            assertThat(isFaceAuthEnabledAndEnrolled).isFalse()
        }

    @Test
    fun anySimSecure_disablesFaceAuthToNotCurrentlyRun() =
        testScope.runTest {
            faceAuthIsEnrolled()

            createBiometricSettingsRepository()
            val isFaceAuthCurrentlyAllowed by collectLastValue(underTest.isFaceAuthCurrentlyAllowed)

            deviceIsInPostureThatSupportsFaceAuth()
            doNotDisableKeyguardAuthFeatures()
            faceAuthIsStrongBiometric()
            biometricsAreEnabledBySettings()
            mobileConnectionsRepository.isAnySimSecure.value = false

            onStrongAuthChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            onNonStrongAuthChanged(false, PRIMARY_USER_ID)
            assertThat(isFaceAuthCurrentlyAllowed).isTrue()

            mobileConnectionsRepository.isAnySimSecure.value = true
            assertThat(isFaceAuthCurrentlyAllowed).isFalse()
        }

    @Test
    fun biometricManagerControlsFaceAuthenticationEnabledStatus() =
        testScope.runTest {
            faceAuthIsEnrolled()

            createBiometricSettingsRepository()
            verify(biometricManager)
                .registerEnabledOnKeyguardCallback(biometricManagerCallback.capture())
            whenever(devicePolicyManager.getKeyguardDisabledFeatures(isNull(), eq(PRIMARY_USER_ID)))
                .thenReturn(0)
            broadcastDPMStateChange()
            val isFaceAuthAllowed = collectLastValue(underTest.isFaceAuthEnrolledAndEnabled)

            assertThat(isFaceAuthAllowed()).isFalse()

            // Value changes for another user
            biometricManagerCallback.value.onChanged(true, ANOTHER_USER_ID)

            assertThat(isFaceAuthAllowed()).isFalse()

            // Value changes for current user.
            biometricManagerCallback.value.onChanged(true, PRIMARY_USER_ID)

            assertThat(isFaceAuthAllowed()).isTrue()
        }

    private fun faceAuthIsEnrolled(userId: Int = PRIMARY_USER_ID) {
        whenever(authController.isFaceAuthEnrolled(userId)).thenReturn(true)
    }

    @Test
    fun userChange_isFingerprintEnrolledAndEnabledUpdated() =
        testScope.runTest {
            createBiometricSettingsRepository()
            whenever(authController.isFingerprintEnrolled(ANOTHER_USER_ID)).thenReturn(false)
            whenever(authController.isFingerprintEnrolled(PRIMARY_USER_ID)).thenReturn(true)

            verify(biometricManager)
                .registerEnabledOnKeyguardCallback(biometricManagerCallback.capture())
            val isFingerprintEnrolledAndEnabled =
                collectLastValue(underTest.isFingerprintEnrolledAndEnabled)
            biometricManagerCallback.value.onChanged(true, ANOTHER_USER_ID)
            runCurrent()
            userRepository.setSelectedUserInfo(ANOTHER_USER)
            runCurrent()
            assertThat(isFingerprintEnrolledAndEnabled()).isFalse()

            biometricManagerCallback.value.onChanged(true, PRIMARY_USER_ID)
            runCurrent()
            userRepository.setSelectedUserInfo(PRIMARY_USER)
            runCurrent()
            assertThat(isFingerprintEnrolledAndEnabled()).isTrue()
        }

    @Test
    fun userChange_biometricEnabledChange_handlesRaceCondition() =
        testScope.runTest {
            createBiometricSettingsRepository()
            whenever(authController.isFaceAuthEnrolled(ANOTHER_USER_ID)).thenReturn(true)

            verify(biometricManager)
                .registerEnabledOnKeyguardCallback(biometricManagerCallback.capture())
            val isFaceAuthAllowed = collectLastValue(underTest.isFaceAuthEnrolledAndEnabled)
            biometricManagerCallback.value.onChanged(true, ANOTHER_USER_ID)
            runCurrent()

            userRepository.setSelectedUserInfo(ANOTHER_USER)
            runCurrent()

            assertThat(isFaceAuthAllowed()).isTrue()
        }

    @Test
    fun biometricManagerCallbackIsRegisteredOnlyOnce() =
        testScope.runTest {
            createBiometricSettingsRepository()

            collectLastValue(underTest.isFaceAuthEnrolledAndEnabled)()
            collectLastValue(underTest.isFaceAuthEnrolledAndEnabled)()
            collectLastValue(underTest.isFaceAuthEnrolledAndEnabled)()

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

    @Test
    fun faceAuthCurrentlyAllowed_dependsOnStrongAuthBiometricSetting_ifFaceIsClass3() =
        testScope.runTest {
            createBiometricSettingsRepository()
            val isFaceAuthCurrentlyAllowed by collectLastValue(underTest.isFaceAuthCurrentlyAllowed)

            faceAuthIsEnrolled()
            enrollmentChange(FACE, PRIMARY_USER_ID, true)
            deviceIsInPostureThatSupportsFaceAuth()
            doNotDisableKeyguardAuthFeatures()
            faceAuthIsStrongBiometric()
            biometricsAreEnabledBySettings()

            onStrongAuthChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            onNonStrongAuthChanged(false, PRIMARY_USER_ID)

            assertThat(isFaceAuthCurrentlyAllowed).isTrue()

            onStrongAuthChanged(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN, PRIMARY_USER_ID)
            onNonStrongAuthChanged(true, PRIMARY_USER_ID)

            assertThat(isFaceAuthCurrentlyAllowed).isFalse()
        }

    @Test
    fun faceAuthCurrentlyAllowed_dependsOnNonStrongAuthBiometricSetting_ifFaceIsNotStrong() =
        testScope.runTest {
            createBiometricSettingsRepository()
            val isFaceAuthCurrentlyAllowed by collectLastValue(underTest.isFaceAuthCurrentlyAllowed)

            faceAuthIsEnrolled()
            enrollmentChange(FACE, PRIMARY_USER_ID, true)
            deviceIsInPostureThatSupportsFaceAuth()
            doNotDisableKeyguardAuthFeatures()
            faceAuthIsNonStrongBiometric()
            biometricsAreEnabledBySettings()

            onStrongAuthChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            onNonStrongAuthChanged(false, PRIMARY_USER_ID)

            assertThat(isFaceAuthCurrentlyAllowed).isFalse()

            onStrongAuthChanged(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN, PRIMARY_USER_ID)
            onNonStrongAuthChanged(true, PRIMARY_USER_ID)

            assertThat(isFaceAuthCurrentlyAllowed).isFalse()

            onStrongAuthChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            onNonStrongAuthChanged(true, PRIMARY_USER_ID)

            assertThat(isFaceAuthCurrentlyAllowed).isTrue()
        }

    @Test
    fun fpAuthCurrentlyAllowed_dependsOnNonStrongAuthBiometricSetting_ifFpIsNotStrong() =
        testScope.runTest {
            createBiometricSettingsRepository()
            biometricsAreEnabledBySettings()
            val isFingerprintCurrentlyAllowed by
                collectLastValue(underTest.isFingerprintAuthCurrentlyAllowed)

            fingerprintIsEnrolled(PRIMARY_USER_ID)
            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, PRIMARY_USER_ID, true)
            doNotDisableKeyguardAuthFeatures(PRIMARY_USER_ID)
            runCurrent()

            fingerprintPropertyRepository.setProperties(
                1,
                SensorStrength.STRONG,
                FingerprintSensorType.UDFPS_OPTICAL,
                emptyMap()
            )
            // Non strong auth is not allowed now, FP is marked strong
            onStrongAuthChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            onNonStrongAuthChanged(false, PRIMARY_USER_ID)

            assertThat(isFingerprintCurrentlyAllowed).isTrue()

            fingerprintPropertyRepository.setProperties(
                1,
                SensorStrength.CONVENIENCE,
                FingerprintSensorType.UDFPS_OPTICAL,
                emptyMap()
            )
            assertThat(isFingerprintCurrentlyAllowed).isFalse()

            fingerprintPropertyRepository.setProperties(
                1,
                SensorStrength.WEAK,
                FingerprintSensorType.UDFPS_OPTICAL,
                emptyMap()
            )
            assertThat(isFingerprintCurrentlyAllowed).isFalse()
        }

    @Test
    fun fpAuthCurrentlyAllowed_dependsOnStrongAuthBiometricSetting_ifFpIsStrong() =
        testScope.runTest {
            createBiometricSettingsRepository()
            biometricsAreEnabledBySettings()
            val isFingerprintCurrentlyAllowed by
                collectLastValue(underTest.isFingerprintAuthCurrentlyAllowed)

            fingerprintIsEnrolled(PRIMARY_USER_ID)
            enrollmentChange(UNDER_DISPLAY_FINGERPRINT, PRIMARY_USER_ID, true)
            doNotDisableKeyguardAuthFeatures(PRIMARY_USER_ID)
            runCurrent()

            fingerprintPropertyRepository.setProperties(
                1,
                SensorStrength.STRONG,
                FingerprintSensorType.UDFPS_OPTICAL,
                emptyMap()
            )
            // Non strong auth is not allowed now, FP is marked strong
            onStrongAuthChanged(STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN, PRIMARY_USER_ID)
            onNonStrongAuthChanged(true, PRIMARY_USER_ID)

            assertThat(isFingerprintCurrentlyAllowed).isFalse()

            onStrongAuthChanged(STRONG_AUTH_NOT_REQUIRED, PRIMARY_USER_ID)
            onNonStrongAuthChanged(false, PRIMARY_USER_ID)

            assertThat(isFingerprintCurrentlyAllowed).isTrue()
        }

    private fun enrollmentChange(biometricType: BiometricType, userId: Int, enabled: Boolean) {
        authControllerCallback.allValues.forEach {
            it.onEnrollmentsChanged(biometricType, userId, enabled)
        }
    }

    private fun doNotDisableKeyguardAuthFeatures(userId: Int = PRIMARY_USER_ID) {
        whenever(devicePolicyManager.getKeyguardDisabledFeatures(isNull(), eq(userId)))
            .thenReturn(0)
        broadcastDPMStateChange()
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
