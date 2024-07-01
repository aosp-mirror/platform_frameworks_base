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

import android.telecom.TelecomManager
import android.telephony.TelephonyManager
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.Swipe
import com.android.internal.R
import com.android.internal.util.EmergencyAffordanceManager
import com.android.internal.util.emergencyAffordanceManager
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.FakeAuthenticationRepository
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.domain.interactor.authenticationInteractor
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.domain.interactor.BouncerActionButtonInteractor
import com.android.systemui.bouncer.domain.interactor.bouncerActionButtonInteractor
import com.android.systemui.bouncer.ui.viewmodel.BouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PasswordBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.PinBouncerViewModel
import com.android.systemui.bouncer.ui.viewmodel.bouncerViewModel
import com.android.systemui.classifier.domain.interactor.falsingInteractor
import com.android.systemui.communal.domain.interactor.communalInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.flags.Flags
import com.android.systemui.flags.fakeFeatureFlagsClassic
import com.android.systemui.keyguard.ui.viewmodel.KeyguardTouchHandlingViewModel
import com.android.systemui.keyguard.ui.viewmodel.LockscreenSceneViewModel
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAsleepForTest
import com.android.systemui.power.domain.interactor.PowerInteractor.Companion.setAwakeForTest
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.qs.ui.adapter.fakeQSSceneAdapter
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.domain.resolver.homeSceneFamilyResolver
import com.android.systemui.scene.domain.startable.sceneContainerStartable
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.scene.ui.viewmodel.SceneContainerViewModel
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.shade.ui.viewmodel.ShadeSceneViewModel
import com.android.systemui.shade.ui.viewmodel.shadeSceneViewModel
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.notificationsPlaceholderViewModel
import com.android.systemui.statusbar.pipeline.mobile.data.repository.FakeMobileConnectionsRepository
import com.android.systemui.statusbar.pipeline.mobile.data.repository.fakeMobileConnectionsRepository
import com.android.systemui.telephony.data.repository.fakeTelephonyRepository
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.android.telecom.telecomManager
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

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
    private val testScope = kosmos.testScope
    private val sceneContainerConfig by lazy { kosmos.sceneContainerConfig }
    private val sceneInteractor by lazy { kosmos.sceneInteractor }
    private val authenticationInteractor by lazy { kosmos.authenticationInteractor }
    private val deviceEntryInteractor by lazy { kosmos.deviceEntryInteractor }
    private val communalInteractor by lazy { kosmos.communalInteractor }

    private val transitionState by lazy {
        MutableStateFlow<ObservableTransitionState>(
            ObservableTransitionState.Idle(sceneContainerConfig.initialSceneKey)
        )
    }
    private val sceneContainerViewModel by lazy {
        SceneContainerViewModel(
                sceneInteractor = sceneInteractor,
                falsingInteractor = kosmos.falsingInteractor,
                powerInteractor = kosmos.powerInteractor,
                scenes = kosmos.scenes,
            )
            .apply { setTransitionState(transitionState) }
    }

    private lateinit var mobileConnectionsRepository: FakeMobileConnectionsRepository
    private lateinit var bouncerActionButtonInteractor: BouncerActionButtonInteractor
    private lateinit var bouncerViewModel: BouncerViewModel

    private val lockscreenSceneViewModel by lazy {
        LockscreenSceneViewModel(
            applicationScope = testScope.backgroundScope,
            deviceEntryInteractor = deviceEntryInteractor,
            communalInteractor = communalInteractor,
            touchHandling =
                KeyguardTouchHandlingViewModel(
                    interactor = mock(),
                ),
            notifications = kosmos.notificationsPlaceholderViewModel,
            shadeInteractor = kosmos.shadeInteractor,
        )
    }

    private lateinit var shadeSceneViewModel: ShadeSceneViewModel

    private val powerInteractor by lazy { kosmos.powerInteractor }

    private var bouncerSceneJob: Job? = null

    private val qsFlexiglassAdapter = kosmos.fakeQSSceneAdapter

    private lateinit var emergencyAffordanceManager: EmergencyAffordanceManager
    private lateinit var telecomManager: TelecomManager
    private val fakeSceneDataSource = kosmos.fakeSceneDataSource

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        overrideResource(R.bool.config_enable_emergency_call_while_sim_locked, true)
        telecomManager = checkNotNull(kosmos.telecomManager)
        whenever(telecomManager.isInCall).thenReturn(false)
        emergencyAffordanceManager = kosmos.emergencyAffordanceManager
        whenever(emergencyAffordanceManager.needsEmergencyAffordance()).thenReturn(true)

        kosmos.fakeFeatureFlagsClassic.apply {
            set(Flags.NEW_NETWORK_SLICE_UI, false)
            set(Flags.REFACTOR_GETCURRENTUSER, true)
        }

        mobileConnectionsRepository = kosmos.fakeMobileConnectionsRepository
        mobileConnectionsRepository.isAnySimSecure.value = false

        kosmos.fakeTelephonyRepository.apply {
            setHasTelephonyRadio(true)
            setCallState(TelephonyManager.CALL_STATE_IDLE)
            setIsInCall(false)
        }

        bouncerActionButtonInteractor = kosmos.bouncerActionButtonInteractor
        bouncerViewModel = kosmos.bouncerViewModel

        shadeSceneViewModel = kosmos.shadeSceneViewModel

        val startable = kosmos.sceneContainerStartable
        startable.start()

        assertWithMessage("Initial scene key mismatch!")
            .that(sceneContainerViewModel.currentScene.value)
            .isEqualTo(sceneContainerConfig.initialSceneKey)
        assertWithMessage("Initial scene container visibility mismatch!")
            .that(sceneContainerViewModel.isVisible.value)
            .isTrue()
    }

    @Test
    fun startsInLockscreenScene() = testScope.runTest { assertCurrentScene(Scenes.Lockscreen) }

    @Test
    fun clickLockButtonAndEnterCorrectPin_unlocksDevice() =
        testScope.runTest {
            emulateUserDrivenTransition(Scenes.Bouncer)

            fakeSceneDataSource.pause()
            enterPin()
            emulatePendingTransitionProgress(
                expectedVisible = false,
            )
            assertCurrentScene(Scenes.Gone)
        }

    @Test
    fun swipeUpOnLockscreen_enterCorrectPin_unlocksDevice() =
        testScope.runTest {
            val destinationScenes by collectLastValue(lockscreenSceneViewModel.destinationScenes)
            val upDestinationSceneKey = destinationScenes?.get(Swipe.Up)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Bouncer)
            emulateUserDrivenTransition(
                to = upDestinationSceneKey,
            )

            fakeSceneDataSource.pause()
            enterPin()
            emulatePendingTransitionProgress(
                expectedVisible = false,
            )
            assertCurrentScene(Scenes.Gone)
        }

    @Test
    fun swipeUpOnLockscreen_withAuthMethodSwipe_dismissesLockscreen() =
        testScope.runTest {
            setAuthMethod(AuthenticationMethodModel.None, enableLockscreen = true)

            val destinationScenes by collectLastValue(lockscreenSceneViewModel.destinationScenes)
            val upDestinationSceneKey = destinationScenes?.get(Swipe.Up)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Gone)
            emulateUserDrivenTransition(
                to = upDestinationSceneKey,
            )
        }

    @Test
    fun swipeUpOnShadeScene_withAuthMethodSwipe_lockscreenNotDismissed_goesToLockscreen() =
        testScope.runTest {
            val destinationScenes by collectLastValue(shadeSceneViewModel.destinationScenes)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)
            setAuthMethod(AuthenticationMethodModel.None, enableLockscreen = true)
            assertCurrentScene(Scenes.Lockscreen)

            // Emulate a user swipe to the shade scene.
            emulateUserDrivenTransition(to = Scenes.Shade)
            assertCurrentScene(Scenes.Shade)

            val upDestinationSceneKey = destinationScenes?.get(Swipe.Up)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(SceneFamilies.Home)
            assertThat(homeScene).isEqualTo(Scenes.Lockscreen)
            emulateUserDrivenTransition(
                to = homeScene,
            )
        }

    @Test
    fun swipeUpOnShadeScene_withAuthMethodSwipe_lockscreenDismissed_goesToGone() =
        testScope.runTest {
            val destinationScenes by collectLastValue(shadeSceneViewModel.destinationScenes)
            val canSwipeToEnter by collectLastValue(deviceEntryInteractor.canSwipeToEnter)
            val homeScene by collectLastValue(kosmos.homeSceneFamilyResolver.resolvedScene)

            setAuthMethod(AuthenticationMethodModel.None, enableLockscreen = true)

            assertThat(canSwipeToEnter).isTrue()
            assertCurrentScene(Scenes.Lockscreen)

            // Emulate a user swipe to dismiss the lockscreen.
            emulateUserDrivenTransition(to = Scenes.Gone)
            assertCurrentScene(Scenes.Gone)

            // Emulate a user swipe to the shade scene.
            emulateUserDrivenTransition(to = Scenes.Shade)
            assertCurrentScene(Scenes.Shade)

            val upDestinationSceneKey = destinationScenes?.get(Swipe.Up)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(SceneFamilies.Home)
            assertThat(homeScene).isEqualTo(Scenes.Gone)
            emulateUserDrivenTransition(
                to = homeScene,
            )
        }

    @Test
    fun withAuthMethodNone_deviceWakeUp_skipsLockscreen() =
        testScope.runTest {
            setAuthMethod(AuthenticationMethodModel.None, enableLockscreen = false)
            putDeviceToSleep(instantlyLockDevice = false)
            assertCurrentScene(Scenes.Lockscreen)

            wakeUpDevice()
            assertCurrentScene(Scenes.Gone)
        }

    @Test
    fun withAuthMethodSwipe_deviceWakeUp_doesNotSkipLockscreen() =
        testScope.runTest {
            setAuthMethod(AuthenticationMethodModel.None, enableLockscreen = true)
            putDeviceToSleep(instantlyLockDevice = false)
            assertCurrentScene(Scenes.Lockscreen)

            wakeUpDevice()
            assertCurrentScene(Scenes.Lockscreen)
        }

    @Test
    fun deviceGoesToSleep_switchesToLockscreen() =
        testScope.runTest {
            unlockDevice()
            assertCurrentScene(Scenes.Gone)

            putDeviceToSleep()
            assertCurrentScene(Scenes.Lockscreen)
        }

    @Test
    fun deviceGoesToSleep_wakeUp_unlock() =
        testScope.runTest {
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
        testScope.runTest {
            unlockDevice()
            val destinationScenes by collectLastValue(lockscreenSceneViewModel.destinationScenes)
            val upDestinationSceneKey = destinationScenes?.get(Swipe.Up)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Gone)
        }

    @Test
    fun deviceGoesToSleep_withLockTimeout_staysOnLockscreen() =
        testScope.runTest {
            unlockDevice()
            assertCurrentScene(Scenes.Gone)
            putDeviceToSleep(instantlyLockDevice = false)
            assertCurrentScene(Scenes.Lockscreen)

            // Pretend like the timeout elapsed and now lock the device.
            lockDevice()
            assertCurrentScene(Scenes.Lockscreen)
        }

    @Test
    fun dismissingIme_whileOnPasswordBouncer_navigatesToLockscreen() =
        testScope.runTest {
            setAuthMethod(AuthenticationMethodModel.Password)
            val destinationScenes by collectLastValue(lockscreenSceneViewModel.destinationScenes)
            val upDestinationSceneKey = destinationScenes?.get(Swipe.Up)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Bouncer)
            emulateUserDrivenTransition(
                to = upDestinationSceneKey,
            )

            fakeSceneDataSource.pause()
            dismissIme()

            emulatePendingTransitionProgress()
            assertCurrentScene(Scenes.Lockscreen)
        }

    @Test
    fun bouncerActionButtonClick_opensEmergencyServicesDialer() =
        testScope.runTest {
            setAuthMethod(AuthenticationMethodModel.Password)
            val destinationScenes by collectLastValue(lockscreenSceneViewModel.destinationScenes)
            val upDestinationSceneKey = destinationScenes?.get(Swipe.Up)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Bouncer)
            emulateUserDrivenTransition(to = upDestinationSceneKey)

            val bouncerActionButton by collectLastValue(bouncerViewModel.actionButton)
            assertWithMessage("Bouncer action button not visible")
                .that(bouncerActionButton)
                .isNotNull()
            bouncerActionButton?.onClick?.invoke()
            runCurrent()

            // TODO(b/298026988): Assert that an activity was started once we use ActivityStarter.
        }

    @Test
    fun bouncerActionButtonClick_duringCall_returnsToCall() =
        testScope.runTest {
            setAuthMethod(AuthenticationMethodModel.Password)
            startPhoneCall()
            val destinationScenes by collectLastValue(lockscreenSceneViewModel.destinationScenes)
            val upDestinationSceneKey = destinationScenes?.get(Swipe.Up)?.toScene
            assertThat(upDestinationSceneKey).isEqualTo(Scenes.Bouncer)
            emulateUserDrivenTransition(to = upDestinationSceneKey)

            val bouncerActionButton by collectLastValue(bouncerViewModel.actionButton)
            assertWithMessage("Bouncer action button not visible during call")
                .that(bouncerActionButton)
                .isNotNull()
            bouncerActionButton?.onClick?.invoke()
            runCurrent()

            verify(telecomManager).showInCallScreen(any())
        }

    @Test
    fun showBouncer_whenLockedSimIntroduced() =
        testScope.runTest {
            setAuthMethod(AuthenticationMethodModel.None)
            introduceLockedSim()
            assertCurrentScene(Scenes.Bouncer)
        }

    @Test
    fun goesToGone_whenSimUnlocked_whileDeviceUnlocked() =
        testScope.runTest {
            fakeSceneDataSource.pause()
            introduceLockedSim()
            emulatePendingTransitionProgress(expectedVisible = true)
            enterSimPin(
                authMethodAfterSimUnlock = AuthenticationMethodModel.None,
                enableLockscreen = false
            )

            assertCurrentScene(Scenes.Gone)
        }

    @Test
    fun showLockscreen_whenSimUnlocked_whileDeviceLocked() =
        testScope.runTest {
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
    private fun TestScope.assertCurrentScene(expected: SceneKey) {
        runCurrent()
        assertWithMessage("Current scene mismatch!")
            .that(sceneContainerViewModel.currentScene.value)
            .isEqualTo(expected)
    }

    /**
     * Returns the [SceneKey] of the current scene as displayed in the UI.
     *
     * This can be different than the value in [SceneContainerViewModel.currentScene], by design, as
     * the UI must gradually transition between scenes.
     */
    private fun getCurrentSceneInUi(): SceneKey {
        return when (val state = transitionState.value) {
            is ObservableTransitionState.Idle -> state.currentScene
            is ObservableTransitionState.Transition -> state.fromScene
        }
    }

    /** Updates the current authentication method and related states in the data layer. */
    private fun TestScope.setAuthMethod(
        authMethod: AuthenticationMethodModel,
        enableLockscreen: Boolean = true
    ) {
        if (authMethod.isSecure) {
            assert(enableLockscreen) {
                "Lockscreen cannot be disabled with a secure authentication method."
            }
        }
        // Set the lockscreen enabled bit _before_ set the auth method as the code picks up on the
        // lockscreen enabled bit _after_ the auth method is changed and the lockscreen enabled bit
        // is not an observable that can trigger a new evaluation.
        kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(enableLockscreen)
        kosmos.fakeAuthenticationRepository.setAuthenticationMethod(authMethod)
        runCurrent()
    }

    /** Emulates a phone call in progress. */
    private fun TestScope.startPhoneCall() {
        whenever(telecomManager.isInCall).thenReturn(true)
        kosmos.fakeTelephonyRepository.apply {
            setHasTelephonyRadio(true)
            setIsInCall(true)
            setCallState(TelephonyManager.CALL_STATE_OFFHOOK)
        }
        runCurrent()
    }

    /**
     * Emulates a gradual transition to the currently pending scene that's sitting in the
     * [fakeSceneDataSource]. This emits a series of progress updates to the [transitionState] and
     * finishes by committing the pending scene as the current scene.
     *
     * In order to use this, the [fakeSceneDataSource] must be paused before this method is called.
     */
    private fun TestScope.emulatePendingTransitionProgress(
        expectedVisible: Boolean = true,
    ) {
        val isVisible by collectLastValue(sceneContainerViewModel.isVisible)
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
        runCurrent()

        // Report progress of transition.
        while (progressFlow.value < 1f) {
            progressFlow.value += 0.2f
            runCurrent()
        }

        // End the transition and report the change.
        transitionState.value = ObservableTransitionState.Idle(to)

        fakeSceneDataSource.unpause(force = true)
        runCurrent()

        assertWithMessage("Visibility mismatch after scene transition from $from to $to!")
            .that(isVisible)
            .isEqualTo(expectedVisible)
        assertThat(sceneContainerViewModel.currentScene.value).isEqualTo(to)

        bouncerSceneJob =
            if (to == Scenes.Bouncer) {
                testScope.backgroundScope.launch {
                    bouncerViewModel.authMethodViewModel.collect {
                        // Do nothing. Need this to turn this otherwise cold flow, hot.
                    }
                }
            } else {
                bouncerSceneJob?.cancel()
                null
            }
        runCurrent()
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
    private fun TestScope.emulateUserDrivenTransition(
        to: SceneKey?,
    ) {
        checkNotNull(to)

        fakeSceneDataSource.pause()
        sceneInteractor.changeScene(to, "reason")

        emulatePendingTransitionProgress(
            expectedVisible = to != Scenes.Gone,
        )
    }

    /**
     * Locks the device immediately (without delay).
     *
     * Asserts the device to be lockable (e.g. that the current authentication is secure).
     *
     * Not to be confused with [putDeviceToSleep], which may also instantly lock the device.
     */
    private suspend fun TestScope.lockDevice() {
        val authMethod = authenticationInteractor.getAuthenticationMethod()
        assertWithMessage("The authentication method of $authMethod is not secure, cannot lock!")
            .that(authMethod.isSecure)
            .isTrue()

        runCurrent()
    }

    /** Unlocks the device by entering the correct PIN. Ends up in the Gone scene. */
    private fun TestScope.unlockDevice() {
        assertWithMessage("Cannot unlock a device that's already unlocked!")
            .that(deviceEntryInteractor.isUnlocked.value)
            .isFalse()

        emulateUserDrivenTransition(Scenes.Bouncer)
        fakeSceneDataSource.pause()
        enterPin()

        emulatePendingTransitionProgress(
            expectedVisible = false,
        )
    }

    /**
     * Enters the correct PIN in the bouncer UI.
     *
     * Asserts that the current scene is [Scenes.Bouncer] and that the current bouncer UI is a PIN
     * before proceeding.
     *
     * Does not assert that the device is locked or unlocked.
     */
    private fun TestScope.enterPin() {
        assertWithMessage("Cannot enter PIN when not on the Bouncer scene!")
            .that(getCurrentSceneInUi())
            .isEqualTo(Scenes.Bouncer)
        val authMethodViewModel by collectLastValue(bouncerViewModel.authMethodViewModel)
        assertWithMessage("Cannot enter PIN when not using a PIN authentication method!")
            .that(authMethodViewModel)
            .isInstanceOf(PinBouncerViewModel::class.java)

        val pinBouncerViewModel = authMethodViewModel as PinBouncerViewModel
        FakeAuthenticationRepository.DEFAULT_PIN.forEach { digit ->
            pinBouncerViewModel.onPinButtonClicked(digit)
        }
        pinBouncerViewModel.onAuthenticateButtonClicked()
        runCurrent()
    }

    /**
     * Enters the correct PIN in the sim bouncer UI.
     *
     * Asserts that the current scene is [Scenes.Bouncer] and that the current bouncer UI is a PIN
     * before proceeding.
     *
     * Does not assert that the device is locked or unlocked.
     */
    private fun TestScope.enterSimPin(
        authMethodAfterSimUnlock: AuthenticationMethodModel = AuthenticationMethodModel.None,
        enableLockscreen: Boolean = true,
    ) {
        assertWithMessage("Cannot enter PIN when not on the Bouncer scene!")
            .that(getCurrentSceneInUi())
            .isEqualTo(Scenes.Bouncer)
        val authMethodViewModel by collectLastValue(bouncerViewModel.authMethodViewModel)
        assertWithMessage("Cannot enter PIN when not using a PIN authentication method!")
            .that(authMethodViewModel)
            .isInstanceOf(PinBouncerViewModel::class.java)

        val pinBouncerViewModel = authMethodViewModel as PinBouncerViewModel
        FakeAuthenticationRepository.DEFAULT_PIN.forEach { digit ->
            pinBouncerViewModel.onPinButtonClicked(digit)
        }
        pinBouncerViewModel.onAuthenticateButtonClicked()
        kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = false
        runCurrent()

        setAuthMethod(authMethodAfterSimUnlock, enableLockscreen)
        runCurrent()
    }

    /** Changes device wakefulness state from asleep to awake, going through intermediary states. */
    private fun TestScope.wakeUpDevice() {
        val wakefulnessModel = powerInteractor.detailedWakefulness.value
        assertWithMessage("Cannot wake up device as it's already awake!")
            .that(wakefulnessModel.isAwake())
            .isFalse()

        powerInteractor.setAwakeForTest()
        runCurrent()
    }

    /** Changes device wakefulness state from awake to asleep, going through intermediary states. */
    private suspend fun TestScope.putDeviceToSleep(
        instantlyLockDevice: Boolean = true,
    ) {
        val wakefulnessModel = powerInteractor.detailedWakefulness.value
        assertWithMessage("Cannot put device to sleep as it's already asleep!")
            .that(wakefulnessModel.isAwake())
            .isTrue()

        powerInteractor.setAsleepForTest()
        runCurrent()

        if (instantlyLockDevice) {
            lockDevice()
        }
    }

    /** Emulates the dismissal of the IME (soft keyboard). */
    private fun TestScope.dismissIme() {
        (bouncerViewModel.authMethodViewModel.value as? PasswordBouncerViewModel)?.let {
            it.onImeDismissed()
            runCurrent()
        }
    }

    private fun TestScope.introduceLockedSim() {
        setAuthMethod(AuthenticationMethodModel.Sim)
        kosmos.fakeMobileConnectionsRepository.isAnySimSecure.value = true
        runCurrent()
    }
}
