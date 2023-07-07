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

package com.android.systemui.keyguard.bouncer.data.repository

import android.content.pm.UserInfo
import android.hardware.biometrics.BiometricSourceType
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN
import com.android.internal.widget.LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardSecurityModel.SecurityMode.PIN
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.keyguard.KeyguardUpdateMonitorCallback
import com.android.systemui.R
import com.android.systemui.R.string.keyguard_enter_pin
import com.android.systemui.R.string.kg_prompt_after_dpm_lock
import com.android.systemui.R.string.kg_prompt_after_user_lockdown_pin
import com.android.systemui.R.string.kg_prompt_auth_timeout
import com.android.systemui.R.string.kg_prompt_pin_auth_timeout
import com.android.systemui.R.string.kg_prompt_reason_restart_pin
import com.android.systemui.R.string.kg_prompt_unattended_update
import com.android.systemui.R.string.kg_trust_agent_disabled
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.bouncer.data.factory.BouncerMessageFactory
import com.android.systemui.keyguard.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.keyguard.bouncer.shared.model.Message
import com.android.systemui.keyguard.data.repository.FakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.FakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.FakeTrustRepository
import com.android.systemui.keyguard.shared.model.AuthenticationFlags
import com.android.systemui.user.data.repository.FakeUserRepository
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
@Ignore("b/236891644")
class BouncerMessageRepositoryTest : SysuiTestCase() {

    @Mock private lateinit var updateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var securityModel: KeyguardSecurityModel
    @Captor
    private lateinit var updateMonitorCallback: ArgumentCaptor<KeyguardUpdateMonitorCallback>

    private lateinit var underTest: BouncerMessageRepository
    private lateinit var trustRepository: FakeTrustRepository
    private lateinit var biometricSettingsRepository: FakeBiometricSettingsRepository
    private lateinit var userRepository: FakeUserRepository
    private lateinit var fingerprintRepository: FakeDeviceEntryFingerprintAuthRepository
    private lateinit var testScope: TestScope

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        trustRepository = FakeTrustRepository()
        biometricSettingsRepository = FakeBiometricSettingsRepository()
        userRepository = FakeUserRepository()
        userRepository.setUserInfos(listOf(PRIMARY_USER))
        fingerprintRepository = FakeDeviceEntryFingerprintAuthRepository()
        testScope = TestScope()

        whenever(updateMonitor.isFingerprintAllowedInBouncer).thenReturn(false)
        whenever(securityModel.getSecurityMode(PRIMARY_USER_ID)).thenReturn(PIN)
        underTest =
            BouncerMessageRepositoryImpl(
                trustRepository = trustRepository,
                biometricSettingsRepository = biometricSettingsRepository,
                updateMonitor = updateMonitor,
                bouncerMessageFactory = BouncerMessageFactory(updateMonitor, securityModel),
                userRepository = userRepository,
                fingerprintAuthRepository = fingerprintRepository
            )
    }

    @Test
    fun setCustomMessage_propagatesState() =
        testScope.runTest {
            underTest.setCustomMessage(message("not empty"))

            val customMessage = collectLastValue(underTest.customMessage)

            assertThat(customMessage()).isEqualTo(message("not empty"))
        }

    @Test
    fun setFaceMessage_propagatesState() =
        testScope.runTest {
            underTest.setFaceAcquisitionMessage(message("not empty"))

            val faceAcquisitionMessage = collectLastValue(underTest.faceAcquisitionMessage)

            assertThat(faceAcquisitionMessage()).isEqualTo(message("not empty"))
        }

    @Test
    fun setFpMessage_propagatesState() =
        testScope.runTest {
            underTest.setFingerprintAcquisitionMessage(message("not empty"))

            val fpAcquisitionMsg = collectLastValue(underTest.fingerprintAcquisitionMessage)

            assertThat(fpAcquisitionMsg()).isEqualTo(message("not empty"))
        }

    @Test
    fun setPrimaryAuthMessage_propagatesState() =
        testScope.runTest {
            underTest.setPrimaryAuthMessage(message("not empty"))

            val primaryAuthMessage = collectLastValue(underTest.primaryAuthMessage)

            assertThat(primaryAuthMessage()).isEqualTo(message("not empty"))
        }

    @Test
    fun biometricAuthMessage_propagatesBiometricAuthMessages() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(PRIMARY_USER)
            val biometricAuthMessage = collectLastValue(underTest.biometricAuthMessage)
            runCurrent()

            verify(updateMonitor).registerCallback(updateMonitorCallback.capture())

            updateMonitorCallback.value.onBiometricAuthFailed(BiometricSourceType.FINGERPRINT)

            assertThat(biometricAuthMessage())
                .isEqualTo(message(R.string.kg_fp_not_recognized, R.string.kg_bio_try_again_or_pin))

            updateMonitorCallback.value.onBiometricAuthFailed(BiometricSourceType.FACE)

            assertThat(biometricAuthMessage())
                .isEqualTo(
                    message(R.string.bouncer_face_not_recognized, R.string.kg_bio_try_again_or_pin)
                )

            updateMonitorCallback.value.onBiometricAcquired(BiometricSourceType.FACE, 0)

            assertThat(biometricAuthMessage()).isNull()
        }

    @Test
    fun onFaceLockout_propagatesState() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(PRIMARY_USER)
            val lockoutMessage = collectLastValue(underTest.biometricLockedOutMessage)
            runCurrent()
            verify(updateMonitor).registerCallback(updateMonitorCallback.capture())

            whenever(updateMonitor.isFaceLockedOut).thenReturn(true)
            updateMonitorCallback.value.onLockedOutStateChanged(BiometricSourceType.FACE)

            assertThat(lockoutMessage())
                .isEqualTo(message(keyguard_enter_pin, R.string.kg_face_locked_out))

            whenever(updateMonitor.isFaceLockedOut).thenReturn(false)
            updateMonitorCallback.value.onLockedOutStateChanged(BiometricSourceType.FACE)
            assertThat(lockoutMessage()).isNull()
        }

    @Test
    fun onFingerprintLockout_propagatesState() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(PRIMARY_USER)
            val lockedOutMessage = collectLastValue(underTest.biometricLockedOutMessage)
            runCurrent()

            fingerprintRepository.setLockedOut(true)

            assertThat(lockedOutMessage())
                .isEqualTo(message(keyguard_enter_pin, R.string.kg_fp_locked_out))

            fingerprintRepository.setLockedOut(false)
            assertThat(lockedOutMessage()).isNull()
        }

    @Test
    fun onAuthFlagsChanged_withTrustNotManagedAndNoBiometrics_isANoop() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(PRIMARY_USER)
            trustRepository.setCurrentUserTrustManaged(false)
            biometricSettingsRepository.setFaceEnrolled(false)
            biometricSettingsRepository.setFingerprintEnrolled(false)

            verifyMessagesForAuthFlag(
                STRONG_AUTH_NOT_REQUIRED to null,
                STRONG_AUTH_REQUIRED_AFTER_BOOT to null,
                SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to null,
                STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to null,
                STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to null,
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to null,
                STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to null,
                SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to null,
                STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to null,
                STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    Pair(keyguard_enter_pin, kg_prompt_after_dpm_lock),
            )
        }

    @Test
    fun authFlagsChanges_withTrustManaged_providesDifferentMessages() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(PRIMARY_USER)
            biometricSettingsRepository.setFaceEnrolled(false)
            biometricSettingsRepository.setFingerprintEnrolled(false)

            trustRepository.setCurrentUserTrustManaged(true)

            verifyMessagesForAuthFlag(
                STRONG_AUTH_NOT_REQUIRED to null,
                STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to null,
                STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    Pair(keyguard_enter_pin, kg_prompt_reason_restart_pin),
                STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    Pair(keyguard_enter_pin, kg_prompt_pin_auth_timeout),
                STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    Pair(keyguard_enter_pin, kg_prompt_after_dpm_lock),
                SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to
                    Pair(keyguard_enter_pin, kg_trust_agent_disabled),
                SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    Pair(keyguard_enter_pin, kg_trust_agent_disabled),
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    Pair(keyguard_enter_pin, kg_prompt_after_user_lockdown_pin),
                STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    Pair(keyguard_enter_pin, kg_prompt_unattended_update),
                STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    Pair(keyguard_enter_pin, kg_prompt_auth_timeout),
            )
        }

    @Test
    fun authFlagsChanges_withFaceEnrolled_providesDifferentMessages() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(PRIMARY_USER)
            trustRepository.setCurrentUserTrustManaged(false)
            biometricSettingsRepository.setFingerprintEnrolled(false)

            biometricSettingsRepository.setIsFaceAuthEnabled(true)
            biometricSettingsRepository.setFaceEnrolled(true)

            verifyMessagesForAuthFlag(
                STRONG_AUTH_NOT_REQUIRED to null,
                STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to null,
                SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to null,
                SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to null,
                STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    Pair(keyguard_enter_pin, kg_prompt_reason_restart_pin),
                STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    Pair(keyguard_enter_pin, kg_prompt_pin_auth_timeout),
                STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    Pair(keyguard_enter_pin, kg_prompt_after_dpm_lock),
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    Pair(keyguard_enter_pin, kg_prompt_after_user_lockdown_pin),
                STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    Pair(keyguard_enter_pin, kg_prompt_unattended_update),
                STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    Pair(keyguard_enter_pin, kg_prompt_auth_timeout),
            )
        }

    @Test
    fun authFlagsChanges_withFingerprintEnrolled_providesDifferentMessages() =
        testScope.runTest {
            userRepository.setSelectedUserInfo(PRIMARY_USER)
            trustRepository.setCurrentUserTrustManaged(false)
            biometricSettingsRepository.setIsFaceAuthEnabled(false)
            biometricSettingsRepository.setFaceEnrolled(false)

            biometricSettingsRepository.setFingerprintEnrolled(true)
            biometricSettingsRepository.setFingerprintEnabledByDevicePolicy(true)

            verifyMessagesForAuthFlag(
                STRONG_AUTH_NOT_REQUIRED to null,
                STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to null,
                SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to null,
                SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to null,
                STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    Pair(keyguard_enter_pin, kg_prompt_reason_restart_pin),
                STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    Pair(keyguard_enter_pin, kg_prompt_pin_auth_timeout),
                STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    Pair(keyguard_enter_pin, kg_prompt_after_dpm_lock),
                STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    Pair(keyguard_enter_pin, kg_prompt_after_user_lockdown_pin),
                STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    Pair(keyguard_enter_pin, kg_prompt_unattended_update),
                STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    Pair(keyguard_enter_pin, kg_prompt_auth_timeout),
            )
        }

    private fun TestScope.verifyMessagesForAuthFlag(
        vararg authFlagToExpectedMessages: Pair<Int, Pair<Int, Int>?>
    ) {
        val authFlagsMessage = collectLastValue(underTest.authFlagsMessage)

        authFlagToExpectedMessages.forEach { (flag, messagePair) ->
            biometricSettingsRepository.setAuthenticationFlags(
                AuthenticationFlags(PRIMARY_USER_ID, flag)
            )

            assertThat(authFlagsMessage())
                .isEqualTo(messagePair?.let { message(it.first, it.second) })
        }
    }

    private fun message(primaryResId: Int, secondaryResId: Int): BouncerMessageModel {
        return BouncerMessageModel(
            message = Message(messageResId = primaryResId),
            secondaryMessage = Message(messageResId = secondaryResId)
        )
    }
    private fun message(value: String): BouncerMessageModel {
        return BouncerMessageModel(message = Message(message = value))
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
