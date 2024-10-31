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

import android.testing.TestableLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.biometrics.data.repository.fakeFingerprintPropertyRepository
import com.android.systemui.bouncer.data.repository.keyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.biometricSettingsRepository
import com.android.systemui.keyguard.data.repository.deviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFaceAuthRepository
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.fakeTrustRepository
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneBackInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.domain.startable.sceneContainerStartable
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.sysuiStatusBarStateController
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
@TestableLooper.RunWithLooper
class DeviceEntryInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val faceAuthRepository by lazy { kosmos.fakeDeviceEntryFaceAuthRepository }
    private val trustRepository by lazy { kosmos.fakeTrustRepository }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val authenticationInteractor by lazy { kosmos.authenticationInteractor }
    private val sceneBackInteractor by lazy { kosmos.sceneBackInteractor }
    private val sceneContainerStartable by lazy { kosmos.sceneContainerStartable }
    private val sysuiStatusBarStateController by lazy { kosmos.sysuiStatusBarStateController }
    private lateinit var underTest: DeviceEntryInteractor

    @Before
    fun setUp() {
        sceneContainerStartable.start()
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
            kosmos.fakeDeviceEntryRepository.apply { setLockscreenEnabled(false) }
            runCurrent()

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
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )

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
            runCurrent()
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.isAuthenticationRequired()).isTrue()
        }

    @Test
    fun isAuthenticationRequired_lockedAndNotSecured_false() =
        testScope.runTest {
            runCurrent()
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndSecured_false() =
        testScope.runTest {
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Password
            )

            assertThat(underTest.isAuthenticationRequired()).isFalse()
        }

    @Test
    fun isAuthenticationRequired_unlockedAndNotSecured_false() =
        testScope.runTest {
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
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
            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
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
    fun showOrUnlockDevice_noAlternateBouncer_switchesToBouncerScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            switchToScene(Scenes.Lockscreen)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            kosmos.fakeFingerprintPropertyRepository.supportsRearFps() // altBouncer unsupported
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            runCurrent()

            underTest.attemptDeviceEntry()

            assertThat(currentScene).isEqualTo(Scenes.Bouncer)
        }

    @Test
    fun showOrUnlockDevice_showsAlternateBouncer_staysOnLockscreenScene() =
        testScope.runTest {
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            switchToScene(Scenes.Lockscreen)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.Pin
            )
            givenCanShowAlternateBouncer()
            runCurrent()

            underTest.attemptDeviceEntry()

            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
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
    fun isDeviceEntered_unlockedWhileOnShade_emitsTrue() =
        testScope.runTest {
            val isDeviceEntered by collectLastValue(underTest.isDeviceEntered)
            assertThat(isDeviceEntered).isFalse()
            val currentScene by collectLastValue(sceneInteractor.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            // Navigate to shade and bouncer:
            switchToScene(Scenes.Shade)
            assertThat(currentScene).isEqualTo(Scenes.Shade)
            // Simulating a "leave it open when the keyguard is hidden" which means the bouncer will
            // be
            // shown and successful authentication should take the user back to where they are, the
            // shade scene.
            sysuiStatusBarStateController.setLeaveOpenOnKeyguardHide(true)
            switchToScene(Scenes.Bouncer)
            assertThat(currentScene).isEqualTo(Scenes.Bouncer)

            assertThat(isDeviceEntered).isFalse()
            // Authenticate with PIN to unlock and dismiss the lockscreen:
            authenticationInteractor.authenticate(FakeAuthenticationRepository.DEFAULT_PIN)
            runCurrent()

            assertThat(isDeviceEntered).isTrue()
        }

    private fun TestScope.switchToScene(sceneKey: SceneKey) {
        sceneInteractor.changeScene(sceneKey, "reason")
        sceneInteractor.setTransitionState(flowOf(ObservableTransitionState.Idle(sceneKey)))
        runCurrent()
    }

    private suspend fun givenCanShowAlternateBouncer() {
        val canShowAlternateBouncer by
            testScope.collectLastValue(kosmos.alternateBouncerInteractor.canShowAlternateBouncer)
        kosmos.fakeFingerprintPropertyRepository.supportsUdfps()
        kosmos.fakeKeyguardTransitionRepository.sendTransitionSteps(
            from = KeyguardState.GONE,
            to = KeyguardState.LOCKSCREEN,
            testScheduler = testScope.testScheduler,
        )
        kosmos.deviceEntryFingerprintAuthRepository.setLockedOut(false)
        kosmos.biometricSettingsRepository.setIsFingerprintAuthCurrentlyAllowed(true)
        kosmos.fakeKeyguardRepository.setKeyguardDismissible(false)
        kosmos.keyguardBouncerRepository.setPrimaryShow(false)
        assertThat(canShowAlternateBouncer).isTrue()
    }
}
