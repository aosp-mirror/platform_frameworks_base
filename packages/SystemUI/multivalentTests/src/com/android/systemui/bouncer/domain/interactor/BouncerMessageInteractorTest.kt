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

package com.android.systemui.bouncer.domain.interactor

import android.content.pm.UserInfo
import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardSecurityModel.SecurityMode.PIN
import com.android.keyguard.KeyguardSecurityModel.SecurityMode.Pattern
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.Flags
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FaceSensorInfo
import com.android.systemui.biometrics.data.repository.fakeFacePropertyRepository
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.bouncer.data.repository.BouncerMessageRepositoryImpl
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.bouncer.shared.model.BouncerMessageModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.domain.interactor.deviceEntryFingerprintAuthInteractor
import com.android.systemui.flags.SystemPropertiesHelper
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.shared.model.AuthenticationFlags
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R.string.kg_too_many_failed_attempts_countdown
import com.android.systemui.res.R.string.kg_trust_agent_disabled
import com.android.systemui.testKosmos
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.util.mockito.KotlinArgumentCaptor
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@TestableLooper.RunWithLooper(setAsMainLooper = true)
@RunWith(AndroidJUnit4::class)
class BouncerMessageInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val countDownTimerCallback = KotlinArgumentCaptor(CountDownTimerCallback::class.java)
    private val repository = BouncerMessageRepositoryImpl()
    private val biometricSettingsRepository = kosmos.fakeBiometricSettingsRepository
    private val testScope = kosmos.testScope
    @Mock private lateinit var updateMonitor: KeyguardUpdateMonitor
    @Mock private lateinit var securityModel: KeyguardSecurityModel
    @Mock private lateinit var countDownTimerUtil: CountDownTimerUtil
    @Mock private lateinit var systemPropertiesHelper: SystemPropertiesHelper

    private lateinit var underTest: BouncerMessageInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        kosmos.fakeUserRepository.setUserInfos(listOf(PRIMARY_USER))
        allowTestableLooperAsMainThread()
        whenever(securityModel.getSecurityMode(PRIMARY_USER_ID)).thenReturn(PIN)
        biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
        overrideResource(kg_trust_agent_disabled, "Trust agent is unavailable")
    }

    suspend fun TestScope.init(
        fingerprintAuthCurrentlyAllowed: Boolean = true,
    ) {
        kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
        mSetFlagsRule.enableFlags(Flags.FLAG_REVAMPED_BOUNCER_MESSAGES)
        underTest =
            BouncerMessageInteractor(
                repository = repository,
                userRepository = kosmos.fakeUserRepository,
                countDownTimerUtil = countDownTimerUtil,
                updateMonitor = updateMonitor,
                biometricSettingsRepository = biometricSettingsRepository,
                applicationScope = testScope.backgroundScope,
                trustRepository = kosmos.fakeTrustRepository,
                systemPropertiesHelper = systemPropertiesHelper,
                primaryBouncerInteractor = kosmos.primaryBouncerInteractor,
                facePropertyRepository = kosmos.fakeFacePropertyRepository,
                deviceEntryFingerprintAuthInteractor = kosmos.deviceEntryFingerprintAuthInteractor,
                faceAuthRepository = kosmos.fakeDeviceEntryFaceAuthRepository,
                securityModel = securityModel
            )
        biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(
            fingerprintAuthCurrentlyAllowed
        )
        kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(false)
        kosmos.fakeFingerprintPropertyRepository.supportsSideFps()
        kosmos.fakeKeyguardBouncerRepository.setPrimaryShow(true)
        runCurrent()
    }

    @Test
    fun initialMessage_pin() =
        testScope.runTest {
            init(fingerprintAuthCurrentlyAllowed = false)
            val bouncerMessage by collectLastValue(underTest.bouncerMessage)
            kosmos.fakeKeyguardBouncerRepository.setLastShownSecurityMode(
                KeyguardSecurityModel.SecurityMode.PIN
            )
            assertThat(bouncerMessage).isNotNull()
            assertThat(primaryResMessage(bouncerMessage)).isEqualTo("Enter PIN")
        }

    @Test
    fun onPrimaryAuthMethodChangeFromPinToPattern_initialMessageUpdates() =
        testScope.runTest {
            init(fingerprintAuthCurrentlyAllowed = false)
            val bouncerMessage by collectLastValue(underTest.bouncerMessage)
            whenever(securityModel.getSecurityMode(PRIMARY_USER_ID)).thenReturn(Pattern)
            kosmos.fakeKeyguardBouncerRepository.setLastShownSecurityMode(Pattern)
            assertThat(bouncerMessage).isNotNull()
            assertThat(primaryResMessage(bouncerMessage)).isEqualTo("Draw pattern")
        }

    @Test
    fun onIncorrectSecurityInput_providesTheAppropriateValueForBouncerMessage() =
        testScope.runTest {
            init()
            val bouncerMessage by collectLastValue(underTest.bouncerMessage)
            underTest.onPrimaryAuthIncorrectAttempt()

            assertThat(bouncerMessage).isNotNull()
            assertThat(primaryResMessage(bouncerMessage)).isEqualTo("Wrong PIN. Try again.")
        }

    @Test
    fun onUserStartsPrimaryAuthInput_clearsAllSetBouncerMessages() =
        testScope.runTest {
            init()
            val bouncerMessage by collectLastValue(underTest.bouncerMessage)
            underTest.onPrimaryAuthIncorrectAttempt()
            assertThat(primaryResMessage(bouncerMessage)).isEqualTo("Wrong PIN. Try again.")

            underTest.onPrimaryBouncerUserInput()

            assertThat(primaryResMessage(bouncerMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
        }

    @Test
    fun setCustomMessage_propagateValue() =
        testScope.runTest {
            init()
            val bouncerMessage by collectLastValue(underTest.bouncerMessage)

            underTest.setCustomMessage("not empty")

            assertThat(primaryResMessage(bouncerMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(bouncerMessage?.secondaryMessage?.message).isEqualTo("not empty")

            underTest.setCustomMessage(null)
            assertThat(primaryResMessage(bouncerMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(bouncerMessage?.secondaryMessage?.message).isNull()
        }

    @Test
    fun setFaceMessage_propagateValue() =
        testScope.runTest {
            init()
            val bouncerMessage by collectLastValue(underTest.bouncerMessage)

            underTest.setFaceAcquisitionMessage("not empty")

            assertThat(primaryResMessage(bouncerMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(bouncerMessage?.secondaryMessage?.message).isEqualTo("not empty")

            underTest.setFaceAcquisitionMessage(null)
            assertThat(primaryResMessage(bouncerMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(bouncerMessage?.secondaryMessage?.message).isNull()
        }

    @Test
    fun setFingerprintMessage_propagateValue() =
        testScope.runTest {
            init()
            val bouncerMessage by collectLastValue(underTest.bouncerMessage)

            underTest.setFingerprintAcquisitionMessage("not empty")

            assertThat(primaryResMessage(bouncerMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(bouncerMessage?.secondaryMessage?.message).isEqualTo("not empty")

            underTest.setFingerprintAcquisitionMessage(null)
            assertThat(primaryResMessage(bouncerMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(bouncerMessage?.secondaryMessage?.message).isNull()
        }

    @Test
    fun onPrimaryAuthLockout_startsTimerForSpecifiedNumberOfSeconds() =
        testScope.runTest {
            init()
            val bouncerMessage by collectLastValue(underTest.bouncerMessage)

            underTest.onPrimaryAuthLockedOut(3)

            verify(countDownTimerUtil)
                .startNewTimer(eq(3000L), eq(1000L), countDownTimerCallback.capture())

            countDownTimerCallback.value.onTick(2000L)

            val primaryMessage = bouncerMessage!!.message!!
            assertThat(primaryMessage.messageResId!!)
                .isEqualTo(kg_too_many_failed_attempts_countdown)
            assertThat(primaryMessage.formatterArgs).isEqualTo(mapOf(Pair("count", 2)))
        }

    @Test
    fun onPrimaryAuthLockout_timerComplete_resetsRepositoryMessages() =
        testScope.runTest {
            init()
            val bouncerMessage by collectLastValue(underTest.bouncerMessage)

            underTest.onPrimaryAuthLockedOut(3)

            verify(countDownTimerUtil)
                .startNewTimer(eq(3000L), eq(1000L), countDownTimerCallback.capture())

            countDownTimerCallback.value.onFinish()

            assertThat(primaryResMessage(bouncerMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(bouncerMessage?.secondaryMessage?.message).isNull()
        }

    @Test
    fun onFaceLockout_propagatesState() =
        testScope.runTest {
            init()
            val lockoutMessage by collectLastValue(underTest.bouncerMessage)
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            kosmos.fakeDeviceEntryFaceAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(primaryResMessage(lockoutMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(secondaryResMessage(lockoutMessage))
                .isEqualTo("Can’t unlock with face. Too many attempts.")

            kosmos.fakeDeviceEntryFaceAuthRepository.setLockedOut(false)
            runCurrent()

            assertThat(primaryResMessage(lockoutMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(lockoutMessage?.secondaryMessage?.message).isNull()
        }

    @Test
    fun onFaceLockoutStateChange_whenFaceIsNotEnrolled_isANoop() =
        testScope.runTest {
            init()
            val lockoutMessage by collectLastValue(underTest.bouncerMessage)

            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            runCurrent()
            kosmos.fakeDeviceEntryFaceAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(primaryResMessage(lockoutMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(lockoutMessage?.secondaryMessage?.message).isNull()
            assertThat(lockoutMessage?.secondaryMessage?.messageResId).isEqualTo(0)
        }

    @Test
    fun onFaceLockout_whenItIsClass3_propagatesState() =
        testScope.runTest {
            init()
            val lockoutMessage by collectLastValue(underTest.bouncerMessage)
            kosmos.fakeFacePropertyRepository.setSensorInfo(
                FaceSensorInfo(1, SensorStrength.STRONG)
            )
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            kosmos.fakeDeviceEntryFaceAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(primaryResMessage(lockoutMessage)).isEqualTo("Enter PIN")
            assertThat(secondaryResMessage(lockoutMessage))
                .isEqualTo("PIN is required after too many attempts")

            kosmos.fakeDeviceEntryFaceAuthRepository.setLockedOut(false)
            runCurrent()

            assertThat(primaryResMessage(lockoutMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(lockoutMessage?.secondaryMessage?.message).isNull()
        }

    @Test
    fun onFingerprintLockout_propagatesState() =
        testScope.runTest {
            init()
            val lockedOutMessage by collectLastValue(underTest.bouncerMessage)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(primaryResMessage(lockedOutMessage)).isEqualTo("Enter PIN")
            assertThat(secondaryResMessage(lockedOutMessage))
                .isEqualTo("PIN is required after too many attempts")

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(false)
            runCurrent()

            assertThat(primaryResMessage(lockedOutMessage))
                .isEqualTo("Unlock with PIN or fingerprint")
            assertThat(lockedOutMessage?.secondaryMessage?.message).isNull()
        }

    @Test
    fun onFingerprintLockoutStateChange_whenFingerprintIsNotEnrolled_isANoop() =
        testScope.runTest {
            init()
            val lockoutMessage by collectLastValue(underTest.bouncerMessage)

            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            runCurrent()
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(true)
            runCurrent()

            assertThat(primaryResMessage(lockoutMessage)).isEqualTo("Enter PIN")
            assertThat(lockoutMessage?.secondaryMessage?.message).isNull()
            assertThat(lockoutMessage?.secondaryMessage?.messageResId).isEqualTo(0)
        }

    @Test
    fun onUdfpsFingerprint_DoesNotShowFingerprintMessage() =
        testScope.runTest {
            init()
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setLockedOut(false)
            kosmos.fakeFingerprintPropertyRepository.supportsUdfps()
            val lockedOutMessage by collectLastValue(underTest.bouncerMessage)

            runCurrent()

            assertThat(primaryResMessage(lockedOutMessage)).isEqualTo("Enter PIN")
        }

    @Test
    fun onRestartForMainlineUpdate_shouldProvideRelevantMessage() =
        testScope.runTest {
            init()
            whenever(systemPropertiesHelper.get("sys.boot.reason.last"))
                .thenReturn("reboot,mainline_update")
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)

            verifyMessagesForAuthFlag(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    Pair("Enter PIN", "Device updated. Enter PIN to continue.")
            )
        }

    @Test
    fun onAuthFlagsChanged_withTrustNotManagedAndNoBiometrics_isANoop() =
        testScope.runTest {
            init()
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(false)
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            runCurrent()

            val defaultMessage = Pair("Enter PIN", null)

            verifyMessagesForAuthFlag(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    defaultMessage,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to
                    defaultMessage,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to
                    defaultMessage,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    defaultMessage,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    defaultMessage,
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to defaultMessage,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    defaultMessage,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    defaultMessage,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    Pair("Enter PIN", "For added security, device was locked by work policy")
            )
        }

    @Test
    fun authFlagsChanges_withTrustManaged_providesDifferentMessages() =
        testScope.runTest {
            init()

            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            runCurrent()

            kosmos.fakeTrustRepository.setCurrentUserTrustManaged(true)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(true)

            val defaultMessage = Pair("Enter PIN", null)

            verifyMessagesForAuthFlag(
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
    fun authFlagsChanges_withFaceEnrolled_providesDifferentMessages() =
        testScope.runTest {
            init()
            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(false)
            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)

            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            val defaultMessage = Pair("Enter PIN", null)

            verifyMessagesForAuthFlag(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED to defaultMessage,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to
                    defaultMessage,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to
                    defaultMessage,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    defaultMessage,
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
                        "Enter PIN",
                        "Added security required. Device wasn’t unlocked for a while."
                    ),
            )
        }

    @Test
    fun authFlagsChanges_withFingerprintEnrolled_providesDifferentMessages() =
        testScope.runTest {
            init()
            kosmos.fakeUserRepository.setSelectedUserInfo(PRIMARY_USER)
            kosmos.fakeTrustRepository.setCurrentUserTrustManaged(false)
            biometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)

            biometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
            runCurrent()

            verifyMessagesForAuthFlag(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED to
                    Pair("Unlock with PIN or fingerprint", null)
            )

            biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(false)
            runCurrent()

            verifyMessagesForAuthFlag(
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to
                    Pair("Enter PIN", null),
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    Pair("Enter PIN", null),
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
                        "Enter PIN",
                        "Added security required. Device wasn’t unlocked for a while."
                    ),
            )
        }

    private fun primaryResMessage(bouncerMessage: BouncerMessageModel?) =
        resString(bouncerMessage?.message?.messageResId)

    private fun secondaryResMessage(bouncerMessage: BouncerMessageModel?) =
        resString(bouncerMessage?.secondaryMessage?.messageResId)

    private fun resString(msgResId: Int?): String? =
        msgResId?.let { context.resources.getString(it) }

    private fun TestScope.verifyMessagesForAuthFlag(
        vararg authFlagToExpectedMessages: Pair<Int, Pair<String, String?>>
    ) {
        val authFlagsMessage by collectLastValue(underTest.bouncerMessage)

        authFlagToExpectedMessages.forEach { (flag, messagePair) ->
            biometricSettingsRepository.setAuthenticationFlags(
                AuthenticationFlags(PRIMARY_USER_ID, flag)
            )
            runCurrent()

            assertThat(primaryResMessage(authFlagsMessage)).isEqualTo(messagePair.first)
            if (messagePair.second == null) {
                assertThat(authFlagsMessage?.secondaryMessage?.messageResId).isEqualTo(0)
                assertThat(authFlagsMessage?.secondaryMessage?.message).isNull()
            } else {
                assertThat(authFlagsMessage?.secondaryMessage?.messageResId).isNotEqualTo(0)
                assertThat(secondaryResMessage(authFlagsMessage)).isEqualTo(messagePair.second)
            }
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
