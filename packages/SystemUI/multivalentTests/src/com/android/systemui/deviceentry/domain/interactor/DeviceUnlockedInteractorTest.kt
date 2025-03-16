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

package com.android.systemui.deviceentry.domain.interactor

import android.content.pm.UserInfo
import android.os.PowerManager
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason
import com.android.systemui.deviceentry.shared.model.DeviceUnlockSource
import com.android.systemui.flags.fakeSystemPropertiesHelper
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.shared.model.AuthenticationFlags
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.android.systemui.user.data.model.SelectionStatus
import com.android.systemui.user.data.repository.fakeUserRepository
import com.android.systemui.user.domain.interactor.selectedUserInteractor
import com.android.systemui.util.settings.fakeSettings
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceUnlockedInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val authenticationRepository = kosmos.fakeAuthenticationRepository

    val underTest = kosmos.deviceUnlockedInteractor

    @Before
    fun setup() {
        kosmos.fakeUserRepository.setUserInfos(listOf(primaryUser, secondaryUser))
    }

    @Test
    fun deviceUnlockStatus_whenUnlockedAndAuthMethodIsNone_isTrue() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource).isNull()
        }

    @Test
    fun deviceUnlockStatus_whenUnlockedAndAuthMethodIsPin_isTrue() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.Fingerprint)
        }

    @Test
    fun deviceUnlockStatus_whenUnlockedAndAuthMethodIsPinAndInLockdown_isFalse() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)
            val isInLockdown by collectLastValue(underTest.isInLockdown)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            kosmos.fakeBiometricSettingsRepository.setAuthenticationFlags(
                AuthenticationFlags(
                    userId = 1,
                    flag =
                        LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN,
                )
            )
            runCurrent()
            assertThat(isInLockdown).isTrue()

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
            assertThat(deviceUnlockStatus?.deviceUnlockSource).isNull()
        }

    @Test
    fun deviceUnlockStatus_whenUnlockedAndAuthMethodIsSim_isFalse() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Sim)
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_whenLockedAndAuthMethodIsNone_isTrue() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
        }

    @Test
    fun deviceUnlockStatus_whenLockedAndAuthMethodIsPin_isFalse() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Pin)

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_whenLockedAndAuthMethodIsSim_isFalse() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            authenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.Sim)

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_whenFaceIsAuthenticatedWhileAwakeWithBypass_isTrue() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)
            kosmos.powerInteractor.setAwakeForTest()

            kosmos.fakeDeviceEntryFaceAuthRepository.isAuthenticated.value = true
            kosmos.fakeDeviceEntryRepository.setBypassEnabled(true)
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.FaceWithBypass)
        }

    @Test
    fun deviceUnlockStatus_whenFaceIsAuthenticatedWithoutBypass_providesThatInfo() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            kosmos.fakeDeviceEntryFaceAuthRepository.isAuthenticated.value = true
            kosmos.fakeDeviceEntryRepository.setBypassEnabled(false)
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.FaceWithoutBypass)
        }

    @Test
    fun deviceUnlockStatus_whenFingerprintIsAuthenticated_providesThatInfo() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.Fingerprint)
        }

    @Test
    fun deviceUnlockStatus_whenUnlockedByTrustAgent_providesThatInfo() =
        testScope.runTest {
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)
            kosmos.fakeUserRepository.setSelectedUserInfo(
                primaryUser,
                SelectionStatus.SELECTION_COMPLETE,
            )

            kosmos.fakeTrustRepository.setCurrentUserTrusted(true)
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
            assertThat(deviceUnlockStatus?.deviceUnlockSource)
                .isEqualTo(DeviceUnlockSource.TrustAgent)
        }

    @Test
    fun deviceUnlockStatus_isResetToFalse_whenDeviceGoesToSleep() =
        testScope.runTest {
            setLockAfterScreenTimeout(0)
            kosmos.fakeAuthenticationRepository.powerButtonInstantlyLocks = false
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()

            kosmos.powerInteractor.setAsleepForTest()
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_isResetToFalse_whenDeviceGoesToSleep_afterDelay() =
        testScope.runTest {
            val delay = 5000
            setLockAfterScreenTimeout(delay)
            kosmos.fakeAuthenticationRepository.powerButtonInstantlyLocks = false
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()

            kosmos.powerInteractor.setAsleepForTest()
            runCurrent()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()

            advanceTimeBy(delay.toLong())
            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_isResetToFalse_whenDeviceGoesToSleep_powerButtonLocksInstantly() =
        testScope.runTest {
            setLockAfterScreenTimeout(5000)
            kosmos.fakeAuthenticationRepository.powerButtonInstantlyLocks = true
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()

            kosmos.powerInteractor.setAsleepForTest(
                sleepReason = PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON
            )
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_becomesUnlocked_whenFingerprintUnlocked_whileDeviceAsleep() =
        testScope.runTest {
            setLockAfterScreenTimeout(0)
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)
            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()

            kosmos.powerInteractor.setAsleepForTest()
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
        }

    @Test
    fun deviceEntryRestrictionReason_whenFaceOrFingerprintOrTrust_alwaysNull() =
        testScope.runTest {
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(false)
            runCurrent()

            verifyRestrictionReasonsForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to null,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to null,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to null,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to null,
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to null,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    null,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    null,
            )
        }

    @Test
    fun deviceEntryRestrictionReason_whenFaceOrFingerprintOrTrust_whenLockdown() =
        testScope.runTest {
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(false)
            runCurrent()

            verifyRestrictionReasonsForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    DeviceEntryRestrictionReason.UserLockdown,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    DeviceEntryRestrictionReason.PolicyLockdown,
            )
        }

    @Test
    fun deviceEntryRestrictionReason_whenFaceIsEnrolledAndEnabled_mapsToAuthFlagsState() =
        testScope.runTest {
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(false)
            kosmos.fakeSystemPropertiesHelper.set(
                DeviceUnlockedInteractor.SYS_BOOT_REASON_PROP,
                "not mainline reboot",
            )
            runCurrent()

            verifyRestrictionReasonsForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    DeviceEntryRestrictionReason.DeviceNotUnlockedSinceReboot,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST to
                    DeviceEntryRestrictionReason.AdaptiveAuthRequest,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to
                    DeviceEntryRestrictionReason.BouncerLockedOut,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    DeviceEntryRestrictionReason.SecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    DeviceEntryRestrictionReason.UserLockdown,
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    DeviceEntryRestrictionReason.NonStrongBiometricsSecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    DeviceEntryRestrictionReason.UnattendedUpdate,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    DeviceEntryRestrictionReason.PolicyLockdown,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to null,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    null,
            )
        }

    @Test
    fun deviceEntryRestrictionReason_whenFingerprintIsEnrolledAndEnabled_mapsToAuthFlagsState() =
        testScope.runTest {
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(false)
            kosmos.fakeSystemPropertiesHelper.set(
                DeviceUnlockedInteractor.SYS_BOOT_REASON_PROP,
                "not mainline reboot",
            )
            runCurrent()

            verifyRestrictionReasonsForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    DeviceEntryRestrictionReason.DeviceNotUnlockedSinceReboot,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST to
                    DeviceEntryRestrictionReason.AdaptiveAuthRequest,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to
                    DeviceEntryRestrictionReason.BouncerLockedOut,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    DeviceEntryRestrictionReason.SecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    DeviceEntryRestrictionReason.UserLockdown,
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    DeviceEntryRestrictionReason.NonStrongBiometricsSecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    DeviceEntryRestrictionReason.UnattendedUpdate,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    DeviceEntryRestrictionReason.PolicyLockdown,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to null,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    null,
            )
        }

    @Test
    fun deviceEntryRestrictionReason_whenTrustAgentIsEnabled_mapsToAuthFlagsState() =
        testScope.runTest {
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(true)
            kosmos.fakeTrustRepository.setCurrentUserTrustManaged(false)
            kosmos.fakeSystemPropertiesHelper.set(
                DeviceUnlockedInteractor.SYS_BOOT_REASON_PROP,
                "not mainline reboot",
            )
            runCurrent()

            verifyRestrictionReasonsForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    DeviceEntryRestrictionReason.DeviceNotUnlockedSinceReboot,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST to
                    DeviceEntryRestrictionReason.AdaptiveAuthRequest,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to
                    DeviceEntryRestrictionReason.BouncerLockedOut,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    DeviceEntryRestrictionReason.SecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    DeviceEntryRestrictionReason.UserLockdown,
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    DeviceEntryRestrictionReason.NonStrongBiometricsSecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    DeviceEntryRestrictionReason.UnattendedUpdate,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    DeviceEntryRestrictionReason.PolicyLockdown,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to
                    DeviceEntryRestrictionReason.TrustAgentDisabled,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    DeviceEntryRestrictionReason.TrustAgentDisabled,
            )
        }

    @Test
    fun deviceEntryRestrictionReason_whenDeviceRebootedForMainlineUpdate_mapsToTheCorrectReason() =
        testScope.runTest {
            val deviceEntryRestrictionReason by
                collectLastValue(underTest.deviceEntryRestrictionReason)
            kosmos.fakeSystemPropertiesHelper.set(
                DeviceUnlockedInteractor.SYS_BOOT_REASON_PROP,
                DeviceUnlockedInteractor.REBOOT_MAINLINE_UPDATE,
            )
            kosmos.fakeBiometricSettingsRepository.setAuthenticationFlags(
                AuthenticationFlags(
                    userId = 1,
                    flag = LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT,
                )
            )
            runCurrent()

            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(false)
            runCurrent()

            assertThat(deviceEntryRestrictionReason).isNull()

            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            runCurrent()

            assertThat(deviceEntryRestrictionReason)
                .isEqualTo(DeviceEntryRestrictionReason.DeviceNotUnlockedSinceMainlineUpdate)

            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(false)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(true)
            runCurrent()

            assertThat(deviceEntryRestrictionReason)
                .isEqualTo(DeviceEntryRestrictionReason.DeviceNotUnlockedSinceMainlineUpdate)

            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(true)
            runCurrent()

            assertThat(deviceEntryRestrictionReason)
                .isEqualTo(DeviceEntryRestrictionReason.DeviceNotUnlockedSinceMainlineUpdate)
        }

    @Test
    fun deviceUnlockStatus_locksImmediately_whenDreamStarts_noTimeout() =
        testScope.runTest {
            setLockAfterScreenTimeout(0)
            val isUnlocked by collectLastValue(underTest.deviceUnlockStatus.map { it.isUnlocked })
            unlockDevice()

            startDreaming()

            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_locksWithDelay_afterDreamStarts_withTimeout() =
        testScope.runTest {
            val delay = 5000
            setLockAfterScreenTimeout(delay)
            val isUnlocked by collectLastValue(underTest.deviceUnlockStatus.map { it.isUnlocked })
            unlockDevice()

            startDreaming()
            assertThat(isUnlocked).isTrue()

            advanceTimeBy(delay - 1L)
            assertThat(isUnlocked).isTrue()

            advanceTimeBy(1L)
            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_doesNotLockWithDelay_whenDreamStopsBeforeTimeout() =
        testScope.runTest {
            val delay = 5000
            setLockAfterScreenTimeout(delay)
            val isUnlocked by collectLastValue(underTest.deviceUnlockStatus.map { it.isUnlocked })
            unlockDevice()

            startDreaming()
            assertThat(isUnlocked).isTrue()

            advanceTimeBy(delay - 1L)
            assertThat(isUnlocked).isTrue()

            stopDreaming()
            assertThat(isUnlocked).isTrue()

            advanceTimeBy(1L)
            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun deviceUnlockStatus_doesNotLock_whenDreamStarts_ifNotInteractive() =
        testScope.runTest {
            setLockAfterScreenTimeout(0)
            val isUnlocked by collectLastValue(underTest.deviceUnlockStatus.map { it.isUnlocked })
            unlockDevice()

            startDreaming()

            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun lockNow() =
        testScope.runTest {
            setLockAfterScreenTimeout(5000)
            val isUnlocked by collectLastValue(underTest.deviceUnlockStatus.map { it.isUnlocked })
            unlockDevice()
            assertThat(isUnlocked).isTrue()

            underTest.lockNow()
            runCurrent()

            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun deviceUnlockStatus_isResetToFalse_whenDeviceGoesToSleep_fromSleepButton() =
        testScope.runTest {
            setLockAfterScreenTimeout(5000)
            kosmos.fakeAuthenticationRepository.powerButtonInstantlyLocks = false
            val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            assertThat(deviceUnlockStatus?.isUnlocked).isTrue()

            kosmos.powerInteractor.setAsleepForTest(
                sleepReason = PowerManager.GO_TO_SLEEP_REASON_SLEEP_BUTTON
            )
            runCurrent()

            assertThat(deviceUnlockStatus?.isUnlocked).isFalse()
        }

    private fun TestScope.unlockDevice() {
        val deviceUnlockStatus by collectLastValue(underTest.deviceUnlockStatus)

        kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
            SuccessFingerprintAuthenticationStatus(0, true)
        )
        assertThat(deviceUnlockStatus?.isUnlocked).isTrue()
        kosmos.sceneInteractor.changeScene(Scenes.Gone, "reason")
        runCurrent()
    }

    private fun setLockAfterScreenTimeout(timeoutMs: Int) {
        kosmos.fakeSettings.putIntForUser(
            Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT,
            timeoutMs,
            kosmos.selectedUserInteractor.getSelectedUserId(),
        )
    }

    private fun TestScope.startDreaming() {
        kosmos.fakeKeyguardRepository.setDreaming(true)
        runCurrent()
    }

    private fun TestScope.stopDreaming() {
        kosmos.fakeKeyguardRepository.setDreaming(false)
        runCurrent()
    }

    private fun TestScope.verifyRestrictionReasonsForAuthFlags(
        vararg authFlagToDeviceEntryRestriction: Pair<Int, DeviceEntryRestrictionReason?>
    ) {
        val deviceEntryRestrictionReason by collectLastValue(underTest.deviceEntryRestrictionReason)

        authFlagToDeviceEntryRestriction.forEach { (flag, expectedReason) ->
            kosmos.fakeBiometricSettingsRepository.setAuthenticationFlags(
                AuthenticationFlags(userId = 1, flag = flag)
            )
            runCurrent()

            if (expectedReason == null) {
                assertThat(deviceEntryRestrictionReason).isNull()
            } else {
                assertThat(deviceEntryRestrictionReason).isEqualTo(expectedReason)
            }
        }
    }

    companion object {
        private const val primaryUserId = 1
        private val primaryUser = UserInfo(primaryUserId, "test user", UserInfo.FLAG_PRIMARY)

        private val secondaryUser = UserInfo(2, "secondary user", 0)
    }
}
