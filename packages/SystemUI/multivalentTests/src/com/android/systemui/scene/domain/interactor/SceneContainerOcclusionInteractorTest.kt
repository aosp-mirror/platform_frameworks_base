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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.interactor

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.ObservableTransitionState.Transition.ShowOrHideOverlay
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.keyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.shared.model.Overlays
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.sceneDataSource
import com.android.systemui.shade.shared.flag.DualShade
import com.android.systemui.statusbar.domain.interactor.keyguardOcclusionInteractor
import com.android.systemui.testKosmos
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SceneContainerOcclusionInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardOcclusionInteractor = kosmos.keyguardOcclusionInteractor
    private val keyguardTransitionRepository = kosmos.fakeKeyguardTransitionRepository
    private val keyguardTransitionInteractor = kosmos.keyguardTransitionInteractor
    private val mutableTransitionState =
        MutableStateFlow<ObservableTransitionState>(
            ObservableTransitionState.Idle(Scenes.Lockscreen)
        )
    private val sceneInteractor =
        kosmos.sceneInteractor.apply { setTransitionState(mutableTransitionState) }
    private val sceneDataSource =
        kosmos.sceneDataSource.apply { changeScene(toScene = Scenes.Lockscreen) }

    private val underTest by lazy { kosmos.sceneContainerOcclusionInteractor }

    @Test
    @DisableFlags(DualShade.FLAG_NAME)
    fun invisibleDueToOcclusion_dualShadeDisabled() =
        testScope.runTest {
            val invisibleDueToOcclusion by collectLastValue(underTest.invisibleDueToOcclusion)
            val keyguardState by collectLastValue(keyguardTransitionInteractor.currentKeyguardState)

            // Assert that we have the desired preconditions:
            assertThat(keyguardState).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Lockscreen)
            assertThat(sceneInteractor.transitionState.value)
                .isEqualTo(ObservableTransitionState.Idle(Scenes.Lockscreen))
            assertWithMessage("Should start unoccluded").that(invisibleDueToOcclusion).isFalse()

            // Actual testing starts here:
            showOccludingActivity()
            assertWithMessage("Should become occluded when occluding activity is shown")
                .that(invisibleDueToOcclusion)
                .isTrue()

            transitionIntoAod {
                assertWithMessage("Should become unoccluded when transitioning into AOD")
                    .that(invisibleDueToOcclusion)
                    .isFalse()
            }
            assertWithMessage("Should stay unoccluded when in AOD")
                .that(invisibleDueToOcclusion)
                .isFalse()

            transitionOutOfAod {
                assertWithMessage("Should remain unoccluded while transitioning away from AOD")
                    .that(invisibleDueToOcclusion)
                    .isFalse()
            }
            assertWithMessage("Should become occluded now that no longer in AOD")
                .that(invisibleDueToOcclusion)
                .isTrue()

            expandShade {
                assertWithMessage("Should become unoccluded once shade begins to expand")
                    .that(invisibleDueToOcclusion)
                    .isFalse()
            }
            assertWithMessage("Should be unoccluded when shade is fully expanded")
                .that(invisibleDueToOcclusion)
                .isFalse()

            collapseShade {
                assertWithMessage("Should remain unoccluded while shade is collapsing")
                    .that(invisibleDueToOcclusion)
                    .isFalse()
            }
            assertWithMessage("Should become occluded now that shade is fully collapsed")
                .that(invisibleDueToOcclusion)
                .isTrue()

            hideOccludingActivity()
            assertWithMessage("Should become unoccluded once the occluding activity is hidden")
                .that(invisibleDueToOcclusion)
                .isFalse()
        }

    @Test
    @EnableFlags(DualShade.FLAG_NAME)
    fun invisibleDueToOcclusion_dualShadeEnabled() =
        testScope.runTest {
            val invisibleDueToOcclusion by collectLastValue(underTest.invisibleDueToOcclusion)
            val keyguardState by collectLastValue(keyguardTransitionInteractor.currentKeyguardState)

            // Assert that we have the desired preconditions:
            assertThat(keyguardState).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(sceneInteractor.currentScene.value).isEqualTo(Scenes.Lockscreen)
            assertThat(sceneInteractor.transitionState.value)
                .isEqualTo(ObservableTransitionState.Idle(Scenes.Lockscreen))
            assertWithMessage("Should start unoccluded").that(invisibleDueToOcclusion).isFalse()

            // Actual testing starts here:
            showOccludingActivity()
            assertWithMessage("Should become occluded when occluding activity is shown")
                .that(invisibleDueToOcclusion)
                .isTrue()

            transitionIntoAod {
                assertWithMessage("Should become unoccluded when transitioning into AOD")
                    .that(invisibleDueToOcclusion)
                    .isFalse()
            }
            assertWithMessage("Should stay unoccluded when in AOD")
                .that(invisibleDueToOcclusion)
                .isFalse()

            transitionOutOfAod {
                assertWithMessage("Should remain unoccluded while transitioning away from AOD")
                    .that(invisibleDueToOcclusion)
                    .isFalse()
            }
            assertWithMessage("Should become occluded now that no longer in AOD")
                .that(invisibleDueToOcclusion)
                .isTrue()

            expandDualShade {
                assertWithMessage("Should become unoccluded once shade begins to expand")
                    .that(invisibleDueToOcclusion)
                    .isFalse()
            }
            assertWithMessage("Should be unoccluded when shade is fully expanded")
                .that(invisibleDueToOcclusion)
                .isFalse()

            collapseDualShade {
                assertWithMessage("Should remain unoccluded while shade is collapsing")
                    .that(invisibleDueToOcclusion)
                    .isFalse()
            }
            assertWithMessage("Should become occluded now that shade is fully collapsed")
                .that(invisibleDueToOcclusion)
                .isTrue()

            hideOccludingActivity()
            assertWithMessage("Should become unoccluded once the occluding activity is hidden")
                .that(invisibleDueToOcclusion)
                .isFalse()
        }

    /** Simulates the appearance of a show-when-locked `Activity` in the foreground. */
    private fun TestScope.showOccludingActivity() {
        keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
            showWhenLockedActivityOnTop = true,
            taskInfo = mock(),
        )
        runCurrent()
    }

    /** Simulates the disappearance of a show-when-locked `Activity` from the foreground. */
    private fun TestScope.hideOccludingActivity() {
        keyguardOcclusionInteractor.setWmNotifiedShowWhenLockedActivityOnTop(
            showWhenLockedActivityOnTop = false
        )
        runCurrent()
    }

    /** Simulates a user-driven gradual expansion of the shade. */
    private fun TestScope.expandShade(assertMidTransition: () -> Unit = {}) {
        val progress = MutableStateFlow(0f)
        mutableTransitionState.value =
            ObservableTransitionState.Transition(
                fromScene = sceneDataSource.currentScene.value,
                toScene = Scenes.Shade,
                currentScene = flowOf(sceneDataSource.currentScene.value),
                progress = progress,
                isInitiatedByUserInput = true,
                isUserInputOngoing = flowOf(true),
            )
        runCurrent()

        progress.value = 0.5f
        runCurrent()
        assertMidTransition()

        progress.value = 1f
        runCurrent()

        mutableTransitionState.value = ObservableTransitionState.Idle(Scenes.Shade)
        runCurrent()
    }

    /** Simulates a user-driven gradual expansion of the dual shade (notifications). */
    private fun TestScope.expandDualShade(assertMidTransition: () -> Unit = {}) {
        val progress = MutableStateFlow(0f)
        mutableTransitionState.value =
            ShowOrHideOverlay(
                overlay = Overlays.NotificationsShade,
                fromContent = sceneDataSource.currentScene.value,
                toContent = Overlays.NotificationsShade,
                currentScene = sceneDataSource.currentScene.value,
                currentOverlays = sceneDataSource.currentOverlays,
                progress = progress,
                isInitiatedByUserInput = true,
                isUserInputOngoing = flowOf(true),
                previewProgress = flowOf(0f),
                isInPreviewStage = flowOf(false),
            )
        runCurrent()

        progress.value = 0.5f
        runCurrent()
        assertMidTransition()

        progress.value = 1f
        runCurrent()

        mutableTransitionState.value =
            ObservableTransitionState.Idle(
                sceneDataSource.currentScene.value,
                setOf(Overlays.NotificationsShade),
            )
        runCurrent()
    }

    /** Simulates a user-driven gradual collapse of the shade. */
    private fun TestScope.collapseShade(assertMidTransition: () -> Unit = {}) {
        val progress = MutableStateFlow(0f)
        mutableTransitionState.value =
            ObservableTransitionState.Transition(
                fromScene = Scenes.Shade,
                toScene = Scenes.Lockscreen,
                currentScene = flowOf(Scenes.Shade),
                progress = progress,
                isInitiatedByUserInput = true,
                isUserInputOngoing = flowOf(true),
            )
        runCurrent()

        progress.value = 0.5f
        runCurrent()
        assertMidTransition()

        progress.value = 1f
        runCurrent()

        mutableTransitionState.value = ObservableTransitionState.Idle(Scenes.Lockscreen)
        runCurrent()
    }

    /** Simulates a user-driven gradual collapse of the dual shade (notifications). */
    private fun TestScope.collapseDualShade(assertMidTransition: () -> Unit = {}) {
        val progress = MutableStateFlow(0f)
        mutableTransitionState.value =
            ShowOrHideOverlay(
                overlay = Overlays.NotificationsShade,
                fromContent = Overlays.NotificationsShade,
                toContent = Scenes.Lockscreen,
                currentScene = Scenes.Lockscreen,
                currentOverlays = flowOf(setOf(Overlays.NotificationsShade)),
                progress = progress,
                isInitiatedByUserInput = true,
                isUserInputOngoing = flowOf(true),
                previewProgress = flowOf(0f),
                isInPreviewStage = flowOf(false),
            )
        runCurrent()

        progress.value = 0.5f
        runCurrent()
        assertMidTransition()

        progress.value = 1f
        runCurrent()

        mutableTransitionState.value = ObservableTransitionState.Idle(Scenes.Lockscreen)
        runCurrent()
    }

    /** Simulates a transition into AOD. */
    private suspend fun TestScope.transitionIntoAod(assertMidTransition: () -> Unit = {}) {
        val currentKeyguardState = keyguardTransitionInteractor.getCurrentState()
        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                from = currentKeyguardState,
                to = KeyguardState.AOD,
                value = 0f,
                transitionState = TransitionState.STARTED,
            )
        )
        runCurrent()

        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                from = currentKeyguardState,
                to = KeyguardState.AOD,
                value = 0.5f,
                transitionState = TransitionState.RUNNING,
            )
        )
        runCurrent()
        assertMidTransition()

        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                from = currentKeyguardState,
                to = KeyguardState.AOD,
                value = 1f,
                transitionState = TransitionState.FINISHED,
            )
        )
        runCurrent()
    }

    /** Simulates a transition away from AOD. */
    private suspend fun TestScope.transitionOutOfAod(assertMidTransition: () -> Unit = {}) {
        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                value = 0f,
                transitionState = TransitionState.STARTED,
            )
        )
        runCurrent()

        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                value = 0.5f,
                transitionState = TransitionState.RUNNING,
            )
        )
        runCurrent()
        assertMidTransition()

        keyguardTransitionRepository.sendTransitionStep(
            TransitionStep(
                from = KeyguardState.AOD,
                to = KeyguardState.LOCKSCREEN,
                value = 1f,
                transitionState = TransitionState.FINISHED,
            )
        )
        runCurrent()
    }
}
