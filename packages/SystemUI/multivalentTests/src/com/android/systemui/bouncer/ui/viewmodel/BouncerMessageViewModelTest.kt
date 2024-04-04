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

package com.android.systemui.bouncer.ui.viewmodel

import android.content.pm.UserInfo
import android.hardware.biometrics.BiometricFaceConstants
import android.hardware.fingerprint.FingerprintManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Pattern
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel.Pin
import com.android.systemui.biometrics.data.repository.FaceSensorInfo
import com.android.systemui.biometrics.data.repository.fakeFacePropertyRepository
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.domain.interactor.bouncerInteractor
import com.android.systemui.bouncer.shared.flag.fakeComposeBouncerFlags
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.deviceentry.shared.model.ErrorFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.FailedFaceAuthenticationStatus
import com.android.systemui.deviceentry.shared.model.HelpFaceAuthenticationStatus
import com.android.systemui.flags.fakeSystemPropertiesHelper
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.shared.model.AuthenticationFlags
import com.android.systemui.keyguard.shared.model.ErrorFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.FailFingerprintAuthenticationStatus
import com.android.systemui.keyguard.shared.model.HelpFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.google.common.truth.Truth.assertThat
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class BouncerMessageViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val authenticationInteractor by lazy { kosmos.authenticationInteractor }
    private val bouncerInteractor by lazy { kosmos.bouncerInteractor }
    private lateinit var underTest: BouncerMessageViewModel

    @Before
    fun setUp() {
        kosmos.fakeUserRepository.setUserInfos(listOf(PRIMARY_USER))
        kosmos.fakeComposeBouncerFlags.composeBouncerEnabled = true
        underTest = kosmos.bouncerMessageViewModel
        overrideResource(R.string.kg_trust_agent_disabled, "Trust agent is unavailable")
        kosmos.fakeSystemPropertiesHelper.set(
            DeviceEntryInteractor.SYS_BOOT_REASON_PROP,
            "not mainline reboot"
        )
    }

    @Test
    fun message_defaultMessage_basedOnAuthMethod() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pin)
            kosmos.fakeFingerprintPropertyRepository.supportsSideFps()
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
            runCurrent()

            assertThat(message!!.text).isEqualTo("Unlock with PIN or fingerprint")

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pattern)
            runCurrent()
            assertThat(message!!.text).isEqualTo("Unlock with pattern or fingerprint")

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            runCurrent()
            assertThat(message!!.text).isEqualTo("Unlock with password or fingerprint")
        }

    @Test
    fun message() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pin)
            assertThat(message?.isUpdateAnimated).isTrue()

            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT) {
                bouncerInteractor.authenticate(WRONG_PIN)
            }

            val lockoutEndMs = authenticationInteractor.lockoutEndTimestamp ?: 0
            advanceTimeBy(lockoutEndMs - testScope.currentTime)
            assertThat(message?.isUpdateAnimated).isTrue()
        }

    @Test
    fun lockoutMessage() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            val lockoutSeconds = FakeAuthenticationRepository.LOCKOUT_DURATION_SECONDS
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(Pin)
            assertThat(kosmos.fakeAuthenticationRepository.lockoutEndTimestamp).isNull()
            runCurrent()

            repeat(FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT) { times ->
                bouncerInteractor.authenticate(WRONG_PIN)
                runCurrent()
                if (times == FakeAuthenticationRepository.MAX_FAILED_AUTH_TRIES_BEFORE_LOCKOUT) {
                    assertTryAgainMessage(message?.text, lockoutSeconds)
                    assertThat(message?.isUpdateAnimated).isFalse()
                } else {
                    assertThat(message?.text).isEqualTo("Wrong PIN. Try again.")
                    assertThat(message?.isUpdateAnimated).isTrue()
                }
            }

            repeat(FakeAuthenticationRepository.LOCKOUT_DURATION_SECONDS) { time ->
                advanceTimeBy(1.seconds)
                val remainingSeconds = lockoutSeconds - time - 1
                if (remainingSeconds > 0) {
                    assertTryAgainMessage(message?.text, remainingSeconds)
                }
            }
            assertThat(message?.text).isEqualTo("Enter PIN")
            assertThat(message?.isUpdateAnimated).isTrue()
        }

    @Test
    fun defaultMessage_mapsToDeviceEntryRestrictionReason_whenTrustAgentIsEnabled() =
        testScope.runTest {
            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(true)
            kosmos.fakeTrustRepository.setCurrentUserTrustManaged(false)
            runCurrent()

            val defaultMessage = Pair("Enter PIN", null)

            verifyMessagesForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED to defaultMessage,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    Pair("Enter PIN", "PIN is required after device restarts"),
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    Pair("Enter PIN", "Added security required. PIN not used for a while."),
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    Pair("Enter PIN", "For added security, device was locked by work policy"),
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to
                    Pair("Enter PIN", "Trust agent is unavailable"),
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    Pair("Enter PIN", "Trust agent is unavailable"),
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    Pair("Enter PIN", "PIN is required after lockdown"),
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    Pair("Enter PIN", "PIN required for additional security"),
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    Pair(
                        "Enter PIN",
                        "Added security required. Device wasn’t unlocked for a while."
                    ),
            )
        }

    @Test
    fun defaultMessage_mapsToDeviceEntryRestrictionReason_whenFingerprintIsAvailable() =
        testScope.runTest {
            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeFingerprintPropertyRepository.supportsSideFps()
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
            kosmos.fakeTrustRepository.setCurrentUserTrustManaged(false)
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            runCurrent()

            verifyMessagesForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED to
                    Pair("Unlock with PIN or fingerprint", null),
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to
                    Pair("Unlock with PIN or fingerprint", null),
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    Pair("Unlock with PIN or fingerprint", null),
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    Pair("Enter PIN", "PIN is required after device restarts"),
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    Pair("Enter PIN", "Added security required. PIN not used for a while."),
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    Pair("Enter PIN", "For added security, device was locked by work policy"),
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    Pair("Enter PIN", "PIN is required after lockdown"),
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    Pair("Enter PIN", "PIN required for additional security"),
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    Pair(
                        "Unlock with PIN or fingerprint",
                        "Added security required. Device wasn’t unlocked for a while."
                    ),
            )
        }

    @Test
    fun onFingerprintLockout_messageUpdated() =
        testScope.runTest {
            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeFingerprintPropertyRepository.supportsSideFps()
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)

            val lockedOutMessage by collectLastValue(underTest.message)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(lockedOutMessage?.text).isEqualTo("Enter PIN")
            assertThat(lockedOutMessage?.secondaryText)
                .isEqualTo("PIN is required after too many attempts")

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(false)
            runCurrent()

            assertThat(lockedOutMessage?.text).isEqualTo("Unlock with PIN or fingerprint")
            assertThat(lockedOutMessage?.secondaryText.isNullOrBlank()).isTrue()
        }

    @Test
    fun onUdfpsFingerprint_DoesNotShowFingerprintMessage() =
        testScope.runTest {
            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeFingerprintPropertyRepository.supportsUdfps()
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(false)
            val message by collectLastValue(underTest.message)

            runCurrent()

            assertThat(message?.text).isEqualTo("Enter PIN")
        }

    @Test
    fun onRestartForMainlineUpdate_shouldProvideRelevantMessage() =
        testScope.runTest {
            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeSystemPropertiesHelper.set("sys.boot.reason.last", "reboot,mainline_update")
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            runCurrent()

            verifyMessagesForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    Pair("Enter PIN", "Device updated. Enter PIN to continue.")
            )
        }

    @Test
    fun onFaceLockout_whenItIsClass3_shouldProvideRelevantMessage() =
        testScope.runTest {
            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            val lockoutMessage by collectLastValue(underTest.message)
            kosmos.fakeFacePropertyRepository.setSensorInfo(
                FaceSensorInfo(1, SensorStrength.STRONG)
            )
            kosmos.fakeDeviceEntryFaceAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(lockoutMessage?.text).isEqualTo("Enter PIN")
            assertThat(lockoutMessage?.secondaryText)
                .isEqualTo("PIN is required after too many attempts")

            kosmos.fakeDeviceEntryFaceAuthRepository.setLockedOut(false)
            runCurrent()

            assertThat(lockoutMessage?.text).isEqualTo("Enter PIN")
            assertThat(lockoutMessage?.secondaryText.isNullOrBlank()).isTrue()
        }

    @Test
    fun onFaceLockout_whenItIsNotStrong_shouldProvideRelevantMessage() =
        testScope.runTest {
            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            val lockoutMessage by collectLastValue(underTest.message)
            kosmos.fakeFacePropertyRepository.setSensorInfo(FaceSensorInfo(1, SensorStrength.WEAK))
            kosmos.fakeDeviceEntryFaceAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(lockoutMessage?.text).isEqualTo("Enter PIN")
            assertThat(lockoutMessage?.secondaryText)
                .isEqualTo("Can’t unlock with face. Too many attempts.")

            kosmos.fakeDeviceEntryFaceAuthRepository.setLockedOut(false)
            runCurrent()

            assertThat(lockoutMessage?.text).isEqualTo("Enter PIN")
            assertThat(lockoutMessage?.secondaryText.isNullOrBlank()).isTrue()
        }

    @Test
    fun setFingerprintMessage_propagateValue() =
        testScope.runTest {
            val bouncerMessage by collectLastValue(underTest.message)

            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
            kosmos.fakeFingerprintPropertyRepository.supportsSideFps()
            runCurrent()

            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                HelpFingerprintAuthenticationStatus(1, "some helpful message")
            )
            runCurrent()
            assertThat(bouncerMessage?.text).isEqualTo("Unlock with PIN or fingerprint")
            assertThat(bouncerMessage?.secondaryText).isEqualTo("some helpful message")

            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                FailFingerprintAuthenticationStatus
            )
            runCurrent()
            assertThat(bouncerMessage?.text).isEqualTo("Fingerprint not recognized")
            assertThat(bouncerMessage?.secondaryText).isEqualTo("Try again or enter PIN")

            kosmos.deviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                ErrorFingerprintAuthenticationStatus(
                    FingerprintManager.FINGERPRINT_ERROR_LOCKOUT,
                    "locked out"
                )
            )
            runCurrent()
            assertThat(bouncerMessage?.text).isEqualTo("Enter PIN")
            assertThat(bouncerMessage?.secondaryText)
                .isEqualTo("PIN is required after too many attempts")
        }

    @Test
    fun setFaceMessage_propagateValue() =
        testScope.runTest {
            val bouncerMessage by collectLastValue(underTest.message)

            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthCurrentlyAllowed(true)
            runCurrent()

            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
                HelpFaceAuthenticationStatus(1, "some helpful message")
            )
            runCurrent()
            assertThat(bouncerMessage?.text).isEqualTo("Enter PIN")
            assertThat(bouncerMessage?.secondaryText).isEqualTo("some helpful message")

            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
                ErrorFaceAuthenticationStatus(
                    BiometricFaceConstants.FACE_ERROR_TIMEOUT,
                    "Try again"
                )
            )
            runCurrent()
            assertThat(bouncerMessage?.text).isEqualTo("Enter PIN")
            assertThat(bouncerMessage?.secondaryText).isEqualTo("Try again")

            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
                FailedFaceAuthenticationStatus()
            )
            runCurrent()
            assertThat(bouncerMessage?.text).isEqualTo("Face not recognized")
            assertThat(bouncerMessage?.secondaryText).isEqualTo("Try again or enter PIN")

            kosmos.fakeDeviceEntryFaceAuthRepository.setAuthenticationStatus(
                ErrorFaceAuthenticationStatus(
                    BiometricFaceConstants.FACE_ERROR_LOCKOUT,
                    "locked out"
                )
            )
            runCurrent()
            assertThat(bouncerMessage?.text).isEqualTo("Enter PIN")
            assertThat(bouncerMessage?.secondaryText)
                .isEqualTo("Can’t unlock with face. Too many attempts.")
        }

    private fun TestScope.verifyMessagesForAuthFlags(
        vararg authFlagToMessagePair: Pair<Int, Pair<String, String?>>
    ) {
        val actualMessage by collectLastValue(underTest.message)

        authFlagToMessagePair.forEach { (flag, expectedMessagePair) ->
            kosmos.fakeBiometricSettingsRepository.setAuthenticationFlags(
                AuthenticationFlags(userId = PRIMARY_USER_ID, flag = flag)
            )
            runCurrent()

            assertThat(actualMessage?.text).isEqualTo(expectedMessagePair.first)

            if (expectedMessagePair.second == null) {
                assertThat(actualMessage?.secondaryText.isNullOrBlank()).isTrue()
            } else {
                assertThat(actualMessage?.secondaryText).isEqualTo(expectedMessagePair.second)
            }
        }
    }

    private fun assertTryAgainMessage(
        message: String?,
        time: Int,
    ) {
        assertThat(message).contains("Try again in $time second")
    }

    companion object {
        private val WRONG_PIN = FakeAuthenticationRepository.DEFAULT_PIN.map { it + 1 }
        private const val PRIMARY_USER_ID = 0
        private val PRIMARY_USER =
            UserInfo(
                /* id= */ PRIMARY_USER_ID,
                /* name= */ "primary user",
                /* flags= */ UserInfo.FLAG_PRIMARY
            )
    }
}
