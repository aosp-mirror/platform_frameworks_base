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

package com.android.systemui.deviceentry.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.SceneKey
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason.AdaptiveAuthRequest
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason.BouncerLockedOut
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason.DeviceNotUnlockedSinceReboot
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason.NonStrongBiometricsSecurityTimeout
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason.PolicyLockdown
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason.SecurityTimeout
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason.TrustAgentDisabled
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason.UnattendedUpdate
import com.android.systemui.deviceentry.shared.model.DeviceEntryRestrictionReason.UserLockdown
import com.android.systemui.flags.fakeSystemPropertiesHelper
import com.android.systemui.keyguard.data.repository.fakeBiometricSettingsRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.shared.model.AuthenticationFlags
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.flag.fakeSceneContainerFlags
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class DeviceEntryInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val faceAuthRepository by lazy { kosmos.fakeDeviceEntryFaceAuthRepository }
    private val trustRepository by lazy { kosmos.fakeTrustRepository }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val authenticationInteractor by lazy { kosmos.authenticationInteractor }
    private lateinit var underTest: DeviceEntryInteractor

    @Before
    fun setUp() {
        kosmos.fakeSceneContainerFlags.enabled = true
        underTest = kosmos.deviceEntryInteractor
    }

    @Test
    fun canSwipeToEnter_startsNull() =
        testScope.runTest {
            val values by collectValues(underTest.canSwipeToEnter)
            assertThat(values[0]).isNull()
        }

    @Test
    fun isUnlocked_whenAuthMethodIsNoneAndLockscreenDisabled_isTrue() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            kosmos.fakeDeviceEntryRepository.apply {
                setLockscreenEnabled(false)

                // Toggle isUnlocked, twice.
                //
                // This is done because the underTest.isUnlocked flow doesn't receive values from
                // just changing the state above; the actual isUnlocked state needs to change to
                // cause the logic under test to "pick up" the current state again.
                //
                // It is done twice to make sure that we don't actually change the isUnlocked state
                // from what it originally was.
                setUnlocked(!isUnlocked.value)
                runCurrent()
                setUnlocked(!isUnlocked.value)
                runCurrent()
            }

            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun isUnlocked_whenAuthMethodIsNoneAndLockscreenEnabled_isTrue() =
        testScope.runTest {
            setupSwipeDeviceEntryMethod()

            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isTrue()
        }

    @Test
    fun isUnlocked_whenAuthMethodIsSimAndUnlocked_isFalse() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Sim
            )
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)

            val isUnlocked by collectLastValue(underTest.isUnlocked)
            assertThat(isUnlocked).isFalse()
        }

    @Test
    fun isDeviceEntered_onLockscreenWithSwipe_isFalse() =
        testScope.runTest {
            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            setupSwipeDeviceEntryMethod()
            switchToScene(Scenes.Lockscreen)

            assertThat(isDeviceEntered).isFalse()
        }

    @Test
    fun isDeviceEntered_onShadeBeforeDismissingLockscreenWithSwipe_isFalse() =
        testScope.runTest {
            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            setupSwipeDeviceEntryMethod()
            switchToScene(Scenes.Lockscreen)
            runCurrent()
            switchToScene(Scenes.Shade)

            assertThat(isDeviceEntered).isFalse()
        }

    @Test
    fun isDeviceEntered_afterDismissingLockscreenWithSwipe_isTrue() =
        testScope.runTest {
            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            setupSwipeDeviceEntryMethod()
            switchToScene(Scenes.Lockscreen)
            runCurrent()
            switchToScene(Scenes.Gone)

            assertThat(isDeviceEntered).isTrue()
        }

    @Test
    fun isDeviceEntered_onShadeAfterDismissingLockscreenWithSwipe_isTrue() =
        testScope.runTest {
            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            setupSwipeDeviceEntryMethod()
            switchToScene(Scenes.Lockscreen)
            runCurrent()
            switchToScene(Scenes.Gone)
            runCurrent()
            switchToScene(Scenes.Shade)

            assertThat(isDeviceEntered).isTrue()
        }

    @Test
    fun isDeviceEntered_onBouncer_isFalse() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pattern
            )
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            switchToScene(Scenes.Lockscreen)
            runCurrent()
            switchToScene(Scenes.Bouncer)

            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            assertThat(isDeviceEntered).isFalse()
        }

    @Test
    fun canSwipeToEnter_onLockscreenWithSwipe_isTrue() =
        testScope.runTest {
            setupSwipeDeviceEntryMethod()
            switchToScene(Scenes.Lockscreen)

            val canSwipeToEnter by collectLastValue(underTest.canSwipeToEnter)
            assertThat(canSwipeToEnter).isTrue()
        }

    @Test
    fun canSwipeToEnter_onLockscreenWithPin_isFalse() =
        testScope.runTest {
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            switchToScene(Scenes.Lockscreen)

            val canSwipeToEnter by collectLastValue(underTest.canSwipeToEnter)
            assertThat(canSwipeToEnter).isFalse()
        }

    @Test
    fun canSwipeToEnter_afterLockscreenDismissedInSwipeMode_isFalse() =
        testScope.runTest {
            setupSwipeDeviceEntryMethod()
            switchToScene(Scenes.Lockscreen)
            runCurrent()
            switchToScene(Scenes.Gone)

            val canSwipeToEnter by collectLastValue(underTest.canSwipeToEnter)
            assertThat(canSwipeToEnter).isFalse()
        }

    private fun setupSwipeDeviceEntryMethod() {
        kosmos.fakeAuthenticationRepository.setAuthenticationMethod(AuthenticationMethodModel.None)
        kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
    }

    @Test
    fun canSwipeToEnter_whenTrustedByTrustManager_isTrue() =
        testScope.runTest {
            val canSwipeToEnter by collectLastValue(underTest.canSwipeToEnter)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            switchToScene(Scenes.Lockscreen)
            assertThat(canSwipeToEnter).isFalse()

            trustRepository.setCurrentUserTrusted(true)
            faceAuthRepository.isAuthenticated.value = false
            runCurrent()

            assertThat(canSwipeToEnter).isTrue()
        }

    @Test
    fun canSwipeToEnter_whenAuthenticatedByFace_isTrue() =
        testScope.runTest {
            val canSwipeToEnter by collectLastValue(underTest.canSwipeToEnter)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )
            switchToScene(Scenes.Lockscreen)
            assertThat(canSwipeToEnter).isFalse()

            faceAuthRepository.isAuthenticated.value = true
            runCurrent()
            trustRepository.setCurrentUserTrusted(false)

            assertThat(canSwipeToEnter).isTrue()
        }

    @Test
    fun isAuthenticationRequired_lockedAndSecured_true() =
        testScope.runTest {
            kosmos.fakeDeviceEntryRepository.setUnlocked(false)
            runCurrent()
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.isAuthenticationRequired()).isTrue()
        }

    @Test
    fun isAuthenticationRequired_lockedAndNotSecured_false() =
        testScope.runTest {
            kosmos.fakeDeviceEntryRepository.setUnlocked(false)
            runCurrent()
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndSecured_false() =
        testScope.runTest {
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            runCurrent()
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndNotSecured_false() =
        testScope.runTest {
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            runCurrent()
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isBypassEnabled_enabledInRepository_true() =
        testScope.runTest {
            kosmos.fakeDeviceEntryRepository.setBypassEnabled(true)
            assertThat(underTest.isBypassEnabled.value).isTrue()
        }

    @Test
    fun showOrUnlockDevice_notLocked_switchesToGoneScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            switchToScene(Scenes.Lockscreen)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryRepository.setUnlocked(true)
            runCurrent()

            underTest.attemptDeviceEntry()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun showOrUnlockDevice_authMethodNotSecure_switchesToGoneScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            switchToScene(Scenes.Lockscreen)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            runCurrent()

            underTest.attemptDeviceEntry()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun showOrUnlockDevice_authMethodSwipe_switchesToGoneScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            switchToScene(Scenes.Lockscreen)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            runCurrent()

            underTest.attemptDeviceEntry()

            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun isBypassEnabled_disabledInRepository_false() =
        testScope.runTest {
            kosmos.fakeDeviceEntryRepository.setBypassEnabled(false)
            assertThat(underTest.isBypassEnabled.value).isFalse()
        }

    @Test
    fun successfulAuthenticationChallengeAttempt_updatesIsUnlockedState() =
        testScope.runTest {
            val isUnlocked by collectLastValue(underTest.isUnlocked)
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            assertThat(isUnlocked).isFalse()

            authenticationInteractor.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)

            assertThat(isUnlocked).isTrue()
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
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to null,
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to null,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    null,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    null,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to null
            )
        }

    @Test
    fun deviceEntryRestrictionReason_whenFaceIsEnrolledAndEnabled_mapsToAuthFlagsState() =
        testScope.runTest {
            kosmos.fakeBiometricSettingsRepository.setIsFaceAuthEnrolledAndEnabled(true)
            kosmos.fakeBiometricSettingsRepository.setIsFingerprintAuthEnrolledAndEnabled(false)
            kosmos.fakeTrustRepository.setTrustUsuallyManaged(false)
            kosmos.fakeSystemPropertiesHelper.set(
                DeviceEntryInteractor.SYS_BOOT_REASON_PROP,
                "not mainline reboot"
            )
            runCurrent()

            verifyRestrictionReasonsForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    DeviceNotUnlockedSinceReboot,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST to
                    AdaptiveAuthRequest,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to
                    BouncerLockedOut,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    SecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    UserLockdown,
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    NonStrongBiometricsSecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    UnattendedUpdate,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    PolicyLockdown,
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
                DeviceEntryInteractor.SYS_BOOT_REASON_PROP,
                "not mainline reboot"
            )
            runCurrent()

            verifyRestrictionReasonsForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    DeviceNotUnlockedSinceReboot,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST to
                    AdaptiveAuthRequest,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to
                    BouncerLockedOut,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    SecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    UserLockdown,
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    NonStrongBiometricsSecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    UnattendedUpdate,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    PolicyLockdown,
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
                DeviceEntryInteractor.SYS_BOOT_REASON_PROP,
                "not mainline reboot"
            )
            runCurrent()

            verifyRestrictionReasonsForAuthFlags(
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT to
                    DeviceNotUnlockedSinceReboot,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_ADAPTIVE_AUTH_REQUEST to
                    AdaptiveAuthRequest,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_LOCKOUT to
                    BouncerLockedOut,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_TIMEOUT to
                    SecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_USER_LOCKDOWN to
                    UserLockdown,
                LockPatternUtils.StrongAuthTracker
                    .STRONG_AUTH_REQUIRED_AFTER_NON_STRONG_BIOMETRICS_TIMEOUT to
                    NonStrongBiometricsSecurityTimeout,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_FOR_UNATTENDED_UPDATE to
                    UnattendedUpdate,
                LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_DPM_LOCK_NOW to
                    PolicyLockdown,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_USER_REQUEST to
                    TrustAgentDisabled,
                LockPatternUtils.StrongAuthTracker.SOME_AUTH_REQUIRED_AFTER_TRUSTAGENT_EXPIRED to
                    TrustAgentDisabled,
            )
        }

    @Test
    fun deviceEntryRestrictionReason_whenDeviceRebootedForMainlineUpdate_mapsToTheCorrectReason() =
        testScope.runTest {
            val deviceEntryRestrictionReason by
                collectLastValue(underTest.deviceEntryRestrictionReason)
            kosmos.fakeSystemPropertiesHelper.set(
                DeviceEntryInteractor.SYS_BOOT_REASON_PROP,
                DeviceEntryInteractor.REBOOT_MAINLINE_UPDATE
            )
            kosmos.fakeBiometricSettingsRepository.setAuthenticationFlags(
                AuthenticationFlags(
                    userId = 1,
                    flag = LockPatternUtils.StrongAuthTracker.STRONG_AUTH_REQUIRED_AFTER_BOOT
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

    private fun switchToScene(sceneKey: SceneKey) {
        sceneInteractor.changeScene(sceneKey, "reason")
    }
}
