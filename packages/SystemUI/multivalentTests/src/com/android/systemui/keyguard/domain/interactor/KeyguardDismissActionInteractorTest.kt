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
 *
 */

package com.android.systemui.keyguard.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.authentication.data.repository.fakeAuthenticationRepository
import com.android.systemui.authentication.shared.model.AuthenticationMethodModel
import com.android.systemui.bouncer.data.repository.fakeKeyguardBouncerRepository
import com.android.systemui.bouncer.domain.interactor.alternateBouncerInteractor
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.deviceentry.data.repository.fakeDeviceEntryRepository
import com.android.systemui.deviceentry.domain.interactor.deviceEntryInteractor
import com.android.systemui.deviceentry.domain.interactor.deviceUnlockedInteractor
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.DismissAction
import com.android.systemui.keyguard.shared.model.KeyguardDone
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.power.data.repository.fakePowerRepository
import com.android.systemui.power.domain.interactor.powerInteractor
import com.android.systemui.power.shared.model.WakeSleepReason
import com.android.systemui.power.shared.model.WakefulnessState
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.Transition
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@EnableSceneContainer
@RunWith(AndroidJUnit4::class)
class KeyguardDismissActionInteractorTest : SysuiTestCase() {
    val kosmos = testKosmos()

    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val testScope = kosmos.testScope

    private lateinit var dismissInteractor: KeyguardDismissInteractor
    private lateinit var underTest: KeyguardDismissActionInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)

        dismissInteractor = kosmos.keyguardDismissInteractor
        underTest =
            KeyguardDismissActionInteractor(
                repository = keyguardRepository,
                transitionInteractor = kosmos.keyguardTransitionInteractor,
                dismissInteractor = dismissInteractor,
                applicationScope = testScope.backgroundScope,
                deviceUnlockedInteractor = { kosmos.deviceUnlockedInteractor },
                powerInteractor = kosmos.powerInteractor,
                alternateBouncerInteractor = kosmos.alternateBouncerInteractor,
                shadeInteractor = { kosmos.shadeInteractor },
                keyguardInteractor = { kosmos.keyguardInteractor },
                sceneInteractor = { kosmos.sceneInteractor },
            )
    }

    @Test
    fun updateDismissAction_onRepoChange() =
        testScope.runTest {
            val dismissAction by collectLastValue(underTest.dismissAction)

            val newDismissAction =
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "",
                    willAnimateOnLockscreen = true,
                )
            keyguardRepository.setDismissAction(newDismissAction)
            assertThat(dismissAction).isEqualTo(newDismissAction)
        }

    @Test
    fun messageUpdate() =
        testScope.runTest {
            val message by collectLastValue(underTest.message)
            keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            assertThat(message).isEqualTo("message")
        }

    @Test
    fun runDismissAnimationOnKeyguard_defaultStateFalse() =
        testScope.runTest { assertThat(underTest.runDismissAnimationOnKeyguard()).isFalse() }

    @Test
    fun runDismissAnimationOnKeyguardUpdates() =
        testScope.runTest {
            val animate by collectLastValue(underTest.willAnimateDismissActionOnLockscreen)
            keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            assertThat(animate).isEqualTo(true)

            keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = false,
                )
            )
            assertThat(animate).isEqualTo(false)
        }

    @Test
    fun executeDismissAction_dismissKeyguardRequestWithImmediateDismissAction_biometricAuthed() =
        testScope.runTest {
            val executeDismissAction by collectLastValue(underTest.executeDismissAction)

            val onDismissAction = { KeyguardDone.IMMEDIATE }
            keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = onDismissAction,
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            kosmos.fakeKeyguardBouncerRepository.setKeyguardAuthenticatedBiometrics(true)
            assertThat(executeDismissAction).isEqualTo(onDismissAction)
        }

    @Test
    fun executeDismissAction_dismissKeyguardRequestWithoutImmediateDismissAction() =
        testScope.runTest {
            val executeDismissAction by collectLastValue(underTest.executeDismissAction)

            // WHEN a keyguard action will run after the keyguard is gone
            val onDismissAction = {}
            keyguardRepository.setDismissAction(
                DismissAction.RunAfterKeyguardGone(
                    dismissAction = onDismissAction,
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            assertThat(executeDismissAction).isNull()

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            kosmos.setSceneTransition(Idle(Scenes.Gone))
            kosmos.sceneInteractor.changeScene(Scenes.Gone, "")

            assertThat(executeDismissAction).isNotNull()
        }

    @Test
    fun resetDismissAction() =
        testScope.runTest {
            kosmos.setSceneTransition(Idle(Scenes.Bouncer))
            val resetDismissAction by collectLastValue(underTest.resetDismissAction)
            keyguardRepository.setDismissAction(
                DismissAction.RunAfterKeyguardGone(
                    dismissAction = {},
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            assertThat(resetDismissAction).isNull()
            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))
            assertThat(resetDismissAction).isEqualTo(Unit)
        }

    @Test
    fun doNotResetDismissActionOnUnlockedShade() =
        testScope.runTest {
            kosmos.setSceneTransition(Idle(Scenes.Bouncer))
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            val resetDismissAction by collectLastValue(underTest.resetDismissAction)
            keyguardRepository.setDismissAction(
                DismissAction.RunAfterKeyguardGone(
                    dismissAction = {},
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            assertThat(resetDismissAction).isNull()

            kosmos.setSceneTransition(
                Transition(from = Scenes.Bouncer, to = Scenes.Shade, progress = flowOf(1f))
            )
            assertThat(resetDismissAction).isNull()
        }

    @Test
    fun resetDismissAction_onBouncer_OnAsleep() =
        testScope.runTest {
            kosmos.setSceneTransition(Idle(Scenes.Bouncer))
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            val resetDismissAction by collectLastValue(underTest.resetDismissAction)
            keyguardRepository.setDismissAction(
                DismissAction.RunAfterKeyguardGone(
                    dismissAction = {},
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            )
            assertThat(resetDismissAction).isNull()
            kosmos.fakePowerRepository.updateWakefulness(
                rawState = WakefulnessState.ASLEEP,
                lastWakeReason = WakeSleepReason.POWER_BUTTON,
                lastSleepReason = WakeSleepReason.TIMEOUT,
                powerButtonLaunchGestureTriggered = false,
            )
            assertThat(resetDismissAction).isEqualTo(Unit)
        }

    @Test
    fun setDismissAction_callsCancelRunnableOnPreviousDismissAction() =
        testScope.runTest {
            val dismissAction by collectLastValue(underTest.dismissAction)
            var previousDismissActionCancelCalled = false
            keyguardRepository.setDismissAction(
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = { previousDismissActionCancelCalled = true },
                    message = "",
                    willAnimateOnLockscreen = true,
                )
            )

            val newDismissAction =
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.IMMEDIATE },
                    onCancelAction = {},
                    message = "",
                    willAnimateOnLockscreen = true,
                )
            underTest.setDismissAction(newDismissAction)

            // THEN previous dismiss action got its onCancel called
            assertThat(previousDismissActionCancelCalled).isTrue()

            // THEN dismiss action is updated
            assertThat(dismissAction).isEqualTo(newDismissAction)
        }

    @Test
    fun handleDismissAction() =
        testScope.runTest {
            val dismissAction by collectLastValue(underTest.dismissAction)
            underTest.handleDismissAction()
            assertThat(dismissAction).isEqualTo(DismissAction.None)
        }

    @Test
    fun setKeyguardDone() =
        testScope.runTest {
            val keyguardDoneTiming by collectLastValue(dismissInteractor.keyguardDone)
            runCurrent()

            underTest.setKeyguardDone(KeyguardDone.LATER)
            assertThat(keyguardDoneTiming).isEqualTo(KeyguardDone.LATER)

            underTest.setKeyguardDone(KeyguardDone.IMMEDIATE)
            assertThat(keyguardDoneTiming).isEqualTo(KeyguardDone.IMMEDIATE)
        }

    @Test
    @EnableSceneContainer
    fun dismissAction_executesBeforeItsReset_sceneContainerOn_swipeAuth_fromQsScene() =
        testScope.runTest {
            val canSwipeToEnter by collectLastValue(kosmos.deviceEntryInteractor.canSwipeToEnter)
            val currentScene by collectLastValue(kosmos.sceneInteractor.currentScene)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(currentScene!!)
                )
            kosmos.sceneInteractor.setTransitionState(transitionState)
            val executeDismissAction by collectLastValue(underTest.executeDismissAction)
            val resetDismissAction by collectLastValue(underTest.resetDismissAction)
            assertThat(executeDismissAction).isNull()
            assertThat(resetDismissAction).isNull()
            kosmos.fakeAuthenticationRepository.setAuthenticationMethod(
                AuthenticationMethodModel.None
            )
            kosmos.fakeDeviceEntryRepository.setLockscreenEnabled(true)
            assertThat(canSwipeToEnter).isTrue()
            kosmos.sceneInteractor.changeScene(Scenes.QuickSettings, "")
            transitionState.value = ObservableTransitionState.Idle(Scenes.QuickSettings)
            assertThat(currentScene).isEqualTo(Scenes.QuickSettings)

            assertThat(executeDismissAction).isNull()
            assertThat(resetDismissAction).isNull()

            val dismissAction =
                DismissAction.RunImmediately(
                    onDismissAction = { KeyguardDone.LATER },
                    onCancelAction = {},
                    message = "message",
                    willAnimateOnLockscreen = true,
                )
            underTest.setDismissAction(dismissAction)
            // Should still be null because the transition to Gone has not yet happened.
            assertThat(executeDismissAction).isNull()
            assertThat(resetDismissAction).isNull()

            transitionState.value =
                ObservableTransitionState.Transition.ChangeScene(
                    fromScene = Scenes.QuickSettings,
                    toScene = Scenes.Gone,
                    currentScene = flowOf(Scenes.QuickSettings),
                    currentOverlays = emptySet(),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(false),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            runCurrent()
            assertThat(executeDismissAction).isNull()
            assertThat(resetDismissAction).isNull()

            transitionState.value =
                ObservableTransitionState.Transition.ChangeScene(
                    fromScene = Scenes.QuickSettings,
                    toScene = Scenes.Gone,
                    currentScene = flowOf(Scenes.Gone),
                    currentOverlays = emptySet(),
                    progress = flowOf(1f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(false),
                    previewProgress = flowOf(0f),
                    isInPreviewStage = flowOf(false),
                )
            kosmos.sceneInteractor.changeScene(Scenes.Gone, "")
            assertThat(currentScene).isEqualTo(Scenes.Gone)
            runCurrent()
            assertThat(executeDismissAction).isNotNull()
            assertThat(resetDismissAction).isNull()
        }
}
