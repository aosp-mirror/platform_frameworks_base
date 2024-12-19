/*
 * Copyright 2023 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene

import android.provider.Settings
import android.telephony.TelephonyManager
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.Swipe
import com.android.compose.animation.scene.UserActionResult
import com.android.internal.R
import com.android.internal.util.emergencyAffordanceManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.ui.viewmodel.PasswordBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.bouncerSceneContentViewModel
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.KeyguardViewMediator
import com.android.systemui.keyguard.ui.viewmodel.lockscreenUserActionsViewModel
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectLastValue
import com.android.systemui.kosmos.currentValue
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.testScope
import com.android.systemui.kosmos.verifyCurrent
import com.android.systemui.lifecycle.activateIn
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.domain.startable.sceneContainerStartable
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.shade.ui.viewmodel.shadeSceneContentViewModel
import com.android.systemui.shade.ui.viewmodel.shadeUserActionsViewModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.telephony.data.repository.fakeTelephonyRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.settings.data.repository.userAwareSecureSettingsRepository
import com.android.telecom.mockTelecomManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test cases for the Scene Framework.
 *
 * **Principles**
 * * All test cases here should be done from the perspective of the view-models of the system.
 * * Focus on happy paths, let smaller unit tests focus on failure cases.
 * * These are _integration_ tests and, as such, are larger and harder to maintain than unit tests.
 *   Therefore, when adding or modifying test cases, consider whether what you're testing is better
 *   covered by a more granular unit test.
 * * Please reuse the helper methods in this class (for example, [putDeviceToSleep] or
 *   [emulateUserDrivenTransition]).
 * * All tests start with the device locked and with a PIN auth method. The class offers useful
 *   methods like [setAuthMethod], [unlockDevice], [lockDevice], etc. to help you set up a starting
 *   state that makes more sense for your test case.
 * * All helper methods in this class make assertions that are meant to make sure that they're only
 *   being used when the state is as required (e.g. cannot unlock an already unlocked device, cannot
 *   put to sleep a device that's already asleep, etc.).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
@RunWithLooper
@EnableSceneContainer
class SceneFrameworkIntegrationTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private var bouncerSceneJob: Job? = null

    @Before
    fun setUp() =
        kosmos.run {
            overrideResource(R.bool.config_enable_emergency_call_while_sim_locked, true)
            whenever(mockTelecomManager.isInCall).thenReturn(false)
            whenever(emergencyAffordanceManager.needsEmergencyAffordance()).thenReturn(true)

            fakeFeatureFlagsClassic.apply { set(Flags.NEW_NETWORK_SLICE_UI, false) }

            fakeMobileConnectionsRepository.isAnySimSecure.value = false

            fakeTelephonyRepository.apply {
                setHasTelephonyRadio(true)
                setCallState(TelephonyManager.CALL_STATE_IDLE)
                setIsInCall(false)
            }

            sceneContainerStartable.start()

            lockscreenUserActionsViewModel.activateIn(testScope)
            shadeSceneContentViewModel.activateIn(testScope)
            shadeUserActionsViewModel.activateIn(testScope)
            bouncerSceneContentViewModel.activateIn(testScope)
            sceneContainerViewModel.activateIn(testScope)

            assertWithMessage("Initial scene key mismatch!")
                .that(currentValue(sceneContainerViewModel.currentScene))
                .isEqualTo(sceneContainerConfig.initialSceneKey)
            assertWithMessage("Initial scene container visibility mismatch!")
                .that(currentValue { sceneContainerViewModel.isVisible })
                .isTrue()
        }

    @Test fun startsInLockscreenScene() = kosmos.runTest { assertCurrentScene(Scenes.Lockscreen) }

    @Test
    fun clickLockButtonAndEnterCorrectPin_unlocksDevice() =
        kosmos.runTest {
            emulateUserDrivenTransition(Scenes.Bouncer)

            fakeSceneDataSource.pause()
            enterPin()
            emulatePendingTransitionProgress(expectedVisible = false)
            assertCurrentScene(Scenes.Gone)
        }

    @Test
    fun swipeUpOnLockscreen_enterCorrectPin_unlocksDevice() =
        kosmos.runTest {
            val actions by collectLastValue(kosmos.lockscreenUserActionsViewModel.actions)
            val upDestinationSceneKey =
                (actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Bouncer)
            emulateUserDrivenTransition(to = upDestinationSceneKey)

            fakeSceneDataSource.pause()
            enterPin()
            emulatePendingTransitionProgress(expectedVisible = false)
            assertCurrentScene(Scenes.Gone)
        }

    @Test
    fun swipeUpOnLockscreen_withAuthMethodSwipe_dismissesLockscreen() =
        kosmos.runTest {
            setAuthMethod(AuthenticationMethodModel.None, enableLockscreen = true)

            val actions by collectLastValue(lockscreenUserActionsViewModel.actions)
            val upDestinationSceneKey =
                (actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Gone)
            emulateUserDrivenTransition(to = upDestinationSceneKey)
        }

    @Test
    fun swipeUpOnShadeScene_withAuthMethodSwipe_lockscreenNotDismissed_goesToLockscreen() =
        kosmos.runTest {
            val actions by collectLastValue(shadeUserActionsViewModel.actions)
            setAuthMethod(AuthenticationMethodModel.None, enableLockscreen = true)
            assertCurrentScene(Scenes.Lockscreen)

            // Emulate a user swipe to the shade scene.
            emulateUserDrivenTransition(to = Scenes.Shade)
            assertCurrentScene(Scenes.Shade)

            val upDestinationSceneKey =
                (actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Lockscreen)
            emulateUserDrivenTransition(to = Scenes.Lockscreen)
        }

    @Test
    fun swipeUpOnShadeScene_withAuthMethodSwipe_lockscreenDismissed_goesToGone() =
        kosmos.runTest {
            val actions by collectLastValue(shadeUserActionsViewModel.actions)
            val canSwipeToEnter by collectLastValue(deviceEntryInteractor.canSwipeToEnter)

            setAuthMethod(AuthenticationMethodModel.None, enableLockscreen = true)

            assertThat(canSwipeToEnter).isTrue()
            assertCurrentScene(Scenes.Lockscreen)

            // Emulate a user swipe to dismiss the lockscreen.
            emulateUserDrivenTransition(to = Scenes.Gone)
            assertCurrentScene(Scenes.Gone)

            // Emulate a user swipe to the shade scene.
            emulateUserDrivenTransition(to = Scenes.Shade)
            assertCurrentScene(Scenes.Shade)

            val upDestinationSceneKey =
                (actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Gone)
            emulateUserDrivenTransition(to = Scenes.Gone)
        }

    @Test
    fun withAuthMethodNone_deviceWakeUp_skipsLockscreen() =
        kosmos.runTest {
            setAuthMethod(AuthenticationMethodModel.None, enableLockscreen = false)
            putDeviceToSleep()
            assertCurrentScene(Scenes.Lockscreen)

            wakeUpDevice()
            assertCurrentScene(Scenes.Gone)
        }

    @Test
    fun withAuthMethodSwipe_deviceWakeUp_doesNotSkipLockscreen() =
        kosmos.runTest {
            setAuthMethod(AuthenticationMethodModel.None, enableLockscreen = true)
            putDeviceToSleep()
            assertCurrentScene(Scenes.Lockscreen)

            wakeUpDevice()
            assertCurrentScene(Scenes.Lockscreen)
        }

    @Test
    fun lockDeviceLocksDevice() =
        kosmos.runTest {
            unlockDevice()
            assertCurrentScene(Scenes.Gone)

            lockDevice()
            assertCurrentScene(Scenes.Lockscreen)
        }

    @Test
    fun deviceGoesToSleep_switchesToLockscreen() =
        kosmos.runTest {
            unlockDevice()
            assertCurrentScene(Scenes.Gone)

            putDeviceToSleep()
            assertCurrentScene(Scenes.Lockscreen)
        }

    @Test
    fun deviceGoesToSleep_wakeUp_unlock() =
        kosmos.runTest {
            unlockDevice()
            assertCurrentScene(Scenes.Gone)
            putDeviceToSleep()
            assertCurrentScene(Scenes.Lockscreen)
            wakeUpDevice()
            assertCurrentScene(Scenes.Lockscreen)

            unlockDevice()
            assertCurrentScene(Scenes.Gone)
        }

    @Test
    fun swipeUpOnLockscreenWhileUnlocked_dismissesLockscreen() =
        kosmos.runTest {
            unlockDevice()
            val actions by collectLastValue(lockscreenUserActionsViewModel.actions)
            val upDestinationSceneKey =
                (actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun deviceGoesToSleep_withLockTimeout_staysOnLockscreen() =
        kosmos.runTest {
            unlockDevice()
            assertCurrentScene(Scenes.Gone)
            putDeviceToSleep()
            assertCurrentScene(Scenes.Lockscreen)

            // Pretend like the timeout elapsed and now lock the device.
            lockDevice()
            assertCurrentScene(Scenes.Lockscreen)
        }

    @Test
    fun dismissingIme_whileOnPasswordBouncer_navigatesToLockscreen() =
        kosmos.runTest {
            setAuthMethod(AuthenticationMethodModel.Password)
            val actions by collectLastValue(lockscreenUserActionsViewModel.actions)
            val upDestinationSceneKey =
                (actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Bouncer)
            emulateUserDrivenTransition(to = upDestinationSceneKey)

            fakeSceneDataSource.pause()
            dismissIme()

            emulatePendingTransitionProgress()
            assertCurrentScene(Scenes.Lockscreen)
        }

    @Test
    fun bouncerActionButtonClick_opensEmergencyServicesDialer() =
        kosmos.runTest {
            setAuthMethod(AuthenticationMethodModel.Password)
            val actions by collectLastValue(lockscreenUserActionsViewModel.actions)
            val upDestinationSceneKey =
                (actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Bouncer)
            emulateUserDrivenTransition(to = upDestinationSceneKey)

            val bouncerActionButton by collectLastValue(bouncerSceneContentViewModel.actionButton)
            assertWithMessage("Bouncer action button not visible")
                .that(bouncerActionButton)
                .isNotNull()
            kosmos.bouncerSceneContentViewModel.onActionButtonClicked(bouncerActionButton!!)

            // TODO(b/369765704): Assert that an activity was started once we use ActivityStarter.
        }

    @Test
    fun bouncerActionButtonClick_duringCall_returnsToCall() =
        kosmos.runTest {
            setAuthMethod(AuthenticationMethodModel.Password)
            startPhoneCall()
            val actions by collectLastValue(lockscreenUserActionsViewModel.actions)
            val upDestinationSceneKey =
                (actions?.get(Swipe.Up) as? UserActionResult.ChangeScene)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Bouncer)
            emulateUserDrivenTransition(to = upDestinationSceneKey)

            val bouncerActionButton by collectLastValue(bouncerSceneContentViewModel.actionButton)
            assertWithMessage("Bouncer action button not visible during call")
                .that(bouncerActionButton)
                .isNotNull()
            kosmos.bouncerSceneContentViewModel.onActionButtonClicked(bouncerActionButton!!)

            verifyCurrent(mockTelecomManager).showInCallScreen(any())
        }

    @Test
    fun showBouncer_whenLockedSimIntroduced() =
        kosmos.runTest {
            setAuthMethod(AuthenticationMethodModel.None)
            introduceLockedSim()
            assertCurrentScene(Scenes.Bouncer)
        }

    @Test
    fun goesToGone_whenSimUnlocked_whileDeviceUnlocked() =
        kosmos.runTest {
            fakeSceneDataSource.pause()
            introduceLockedSim()
            emulatePendingTransitionProgress(expectedVisible = true)
            enterSimPin(
                authMethodAfterSimUnlock = AuthenticationMethodModel.None,
                enableLockscreen = false,
            )

            assertCurrentScene(Scenes.Gone)
        }

    @Test
    fun showLockscreen_whenSimUnlocked_whileDeviceLocked() =
        kosmos.runTest {
            fakeSceneDataSource.pause()
            introduceLockedSim()
            emulatePendingTransitionProgress(expectedVisible = true)
            enterSimPin(authMethodAfterSimUnlock = AuthenticationMethodModel.Pin)
            assertCurrentScene(Scenes.Lockscreen)
        }

    /**
     * Asserts that the current scene in the view-model matches what's expected.
     *
     * Note that this doesn't assert what the current scene is in the UI.
     */
    private fun Kosmos.assertCurrentScene(expected: SceneKey) {
        assertWithMessage("Current scene mismatch!")
            .that(currentValue(sceneContainerViewModel.currentScene))
            .isEqualTo(expected)
    }

    /**
     * Returns the [SceneKey] of the current scene as displayed in the UI.
     *
     * This can be different than the value in [SceneContainerViewModel.currentScene], by design, as
     * the UI must gradually transition between scenes.
     */
    private fun Kosmos.getCurrentSceneInUi(): SceneKey {
        return when (val state = currentValue(transitionState)) {
            is ObservableTransitionState.Idle -> state.currentScene
            is ObservableTransitionState.Transition.ChangeScene -> state.fromScene
            is ObservableTransitionState.Transition.ShowOrHideOverlay -> state.currentScene
            is ObservableTransitionState.Transition.ReplaceOverlay -> state.currentScene
        }
    }

    /** Updates the current authentication method and related states in the data layer. */
    private fun Kosmos.setAuthMethod(
        authMethod: AuthenticationMethodModel,
        enableLockscreen: Boolean = true,
    ) {
        if (authMethod.isSecure) {
            assert(enableLockscreen) {
                "Lockscreen cannot be disabled with a secure authentication method."
            }
        }
        // Set the lockscreen enabled bit _before_ set the auth method as the code picks up on the
        // lockscreen enabled bit _after_ the auth method is changed and the lockscreen enabled bit
        // is not an observable that can trigger a new evaluation.
        fakeDeviceEntryRepository.setLockscreenEnabled(enableLockscreen)
        fakeAuthenticationRepository.setAuthenticationMethod(authMethod)
    }

    /** Emulates a phone call in progress. */
    private fun Kosmos.startPhoneCall() {
        whenever(mockTelecomManager.isInCall).thenReturn(true)
        fakeTelephonyRepository.apply {
            setHasTelephonyRadio(true)
            setIsInCall(true)
            setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        }
    }

    /**
     * Emulates a gradual transition to the currently pending scene that's sitting in the
     * [fakeSceneDataSource]. This emits a series of progress updates to the [transitionState] and
     * finishes by committing the pending scene as the current scene.
     *
     * In order to use this, the [fakeSceneDataSource] must be paused before this method is called.
     */
    private fun Kosmos.emulatePendingTransitionProgress(expectedVisible: Boolean = true) {
        assertWithMessage("The FakeSceneDataSource has to be paused for this to do anything.")
            .that(fakeSceneDataSource.isPaused)
            .isTrue()

        val to = fakeSceneDataSource.pendingScene ?: return
        val from = getCurrentSceneInUi()

        if (to == from) {
            return
        }

        // Begin to transition.
        val progressFlow = MutableStateFlow(0f)
        transitionState.value =
            ObservableTransitionState.Transition(
                fromScene = getCurrentSceneInUi(),
                toScene = to,
                currentScene = flowOf(to),
                progress = progressFlow,
                isInitiatedByUserInput = false,
                isUserInputOngoing = flowOf(false),
            )

        // Report progress of transition.
        while (currentValue(progressFlow) < 1f) {
            progressFlow.value += 0.2f
        }

        // End the transition and report the change.
        transitionState.value = ObservableTransitionState.Idle(to)

        fakeSceneDataSource.unpause(force = true)

        assertWithMessage("Visibility mismatch after scene transition from $from to $to!")
            .that(currentValue { sceneContainerViewModel.isVisible })
            .isEqualTo(expectedVisible)
        assertThat(currentValue(sceneContainerViewModel.currentScene)).isEqualTo(to)

        bouncerSceneJob =
            if (to == Scenes.Bouncer) {
                testScope.backgroundScope.launch {
                    bouncerSceneContentViewModel.authMethodViewModel.collect {
                        // Do nothing. Need this to turn this otherwise cold flow, hot.
                    }
                }
            } else {
                bouncerSceneJob?.cancel()
                null
            }
    }

    /**
     * Emulates a fire-and-forget user action (a fling or back, not a pointer-tracking swipe) that
     * causes a scene change to the [to] scene.
     *
     * This also includes the emulation of the resulting UI transition that culminates with the UI
     * catching up with the requested scene change (see [emulatePendingTransitionProgress]).
     *
     * @param to The scene to transition to.
     */
    private fun Kosmos.emulateUserDrivenTransition(to: SceneKey?) {
        checkNotNull(to)

        fakeSceneDataSource.pause()
        sceneInteractor.changeScene(to, "reason")

        emulatePendingTransitionProgress(expectedVisible = to != Scenes.Gone)
    }

    /**
     * Locks the device.
     *
     * Asserts the device to be lockable (e.g. that the current authentication is secure).
     *
     * Internally emulates a power button press that puts the device to sleep, followed by another
     * power button press that wakes up the device but is then expected to be in the locked state.
     */
    private suspend fun Kosmos.lockDevice() {
        val authMethod = authenticationInteractor.getAuthenticationMethod()
        assertWithMessage("The authentication method of $authMethod is not secure, cannot lock!")
            .that(authMethod.isSecure)
            .isTrue()

        powerInteractor.setAsleepForTest()
        testScope.advanceTimeBy(
            kosmos.userAwareSecureSettingsRepository
                .getInt(
                    Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT,
                    KeyguardViewMediator.KEYGUARD_LOCK_AFTER_DELAY_DEFAULT,
                )
                .toLong()
        )

        powerInteractor.setAwakeForTest()
    }

    /** Unlocks the device by entering the correct PIN. Ends up in the Gone scene. */
    private fun Kosmos.unlockDevice() {
        assertWithMessage("Cannot unlock a device that's already unlocked!")
            .that(currentValue(deviceEntryInteractor.isUnlocked))
            .isFalse()

        emulateUserDrivenTransition(Scenes.Bouncer)
        fakeSceneDataSource.pause()
        enterPin()

        emulatePendingTransitionProgress(expectedVisible = false)
    }

    /**
     * Enters the correct PIN in the bouncer UI.
     *
     * Asserts that the current scene is [Scenes.Bouncer] and that the current bouncer UI is a PIN
     * before proceeding.
     *
     * Does not assert that the device is locked or unlocked.
     */
    private fun Kosmos.enterPin() {
        assertWithMessage("Cannot enter PIN when not on the Bouncer scene!")
            .that(getCurrentSceneInUi())
            .isEqualTo(Scenes.Bouncer)
        val authMethodViewModel by
            collectLastValue(bouncerSceneContentViewModel.authMethodViewModel)
        assertWithMessage("Cannot enter PIN when not using a PIN authentication method!")
            .that(authMethodViewModel)
            .isInstanceOf(PinBouncerViewModel::class.java)

        val pinBouncerViewModel = authMethodViewModel as PinBouncerViewModel
        FakeAuthenticationRepository.DEFAULT_PIN.forEach { digit ->
            pinBouncerViewModel.onPinButtonClicked(digit)
        }
        pinBouncerViewModel.onAuthenticateButtonClicked()
    }

    /**
     * Enters the correct PIN in the sim bouncer UI.
     *
     * Asserts that the current scene is [Scenes.Bouncer] and that the current bouncer UI is a PIN
     * before proceeding.
     *
     * Does not assert that the device is locked or unlocked.
     */
    private fun Kosmos.enterSimPin(
        authMethodAfterSimUnlock: AuthenticationMethodModel = AuthenticationMethodModel.None,
        enableLockscreen: Boolean = true,
    ) {
        assertWithMessage("Cannot enter PIN when not on the Bouncer scene!")
            .that(getCurrentSceneInUi())
            .isEqualTo(Scenes.Bouncer)
        val authMethodViewModel by
            collectLastValue(bouncerSceneContentViewModel.authMethodViewModel)
        assertWithMessage("Cannot enter PIN when not using a PIN authentication method!")
            .that(authMethodViewModel)
            .isInstanceOf(PinBouncerViewModel::class.java)

        val pinBouncerViewModel = authMethodViewModel as PinBouncerViewModel
        FakeAuthenticationRepository.DEFAULT_PIN.forEach { digit ->
            pinBouncerViewModel.onPinButtonClicked(digit)
        }
        pinBouncerViewModel.onAuthenticateButtonClicked()
        fakeMobileConnectionsRepository.isAnySimSecure.value = false

        setAuthMethod(authMethodAfterSimUnlock, enableLockscreen)
    }

    /** Changes device wakefulness state from asleep to awake, going through intermediary states. */
    private fun Kosmos.wakeUpDevice() {
        val wakefulnessModel = currentValue(powerInteractor.detailedWakefulness)
        assertWithMessage("Cannot wake up device as it's already awake!")
            .that(wakefulnessModel.isAwake())
            .isFalse()

        powerInteractor.setAwakeForTest()
    }

    /** Changes device wakefulness state from awake to asleep, going through intermediary states. */
    private suspend fun Kosmos.putDeviceToSleep(waitForLock: Boolean = true) {
        val wakefulnessModel = currentValue(powerInteractor.detailedWakefulness)
        assertWithMessage("Cannot put device to sleep as it's already asleep!")
            .that(wakefulnessModel.isAwake())
            .isTrue()

        powerInteractor.setAsleepForTest()
        if (waitForLock) {
            testScope.advanceTimeBy(
                kosmos.userAwareSecureSettingsRepository
                    .getInt(
                        Settings.Secure.LOCK_SCREEN_LOCK_AFTER_TIMEOUT,
                        KeyguardViewMediator.KEYGUARD_LOCK_AFTER_DELAY_DEFAULT,
                    )
                    .toLong()
            )
        }
    }

    /** Emulates the dismissal of the IME (soft keyboard). */
    private fun Kosmos.dismissIme() {
        (currentValue(bouncerSceneContentViewModel.authMethodViewModel)
                as? PasswordBouncerViewModel)
            ?.let { it.onImeDismissed() }
    }

    private fun Kosmos.introduceLockedSim() {
        setAuthMethod(AuthenticationMethodModel.Sim)
        fakeMobileConnectionsRepository.isAnySimSecure.value = true
    }
}
