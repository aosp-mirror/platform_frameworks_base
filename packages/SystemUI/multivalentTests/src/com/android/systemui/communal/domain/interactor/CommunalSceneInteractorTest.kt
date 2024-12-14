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

package com.android.systemui.communal.domain.interactor

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.platform.test.flag.junit.FlagsParameterization
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.Flags.FLAG_SCENE_CONTAINER
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityTransitionAnimator
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.domain.interactor.CommunalSceneInteractor.OnSceneAboutToChangeListener
import com.android.systemui.communal.domain.model.CommunalTransitionProgressModel
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.andSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.initialSceneKey
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import platform.test.runner.parameterized.ParameterizedAndroidJunit4
import platform.test.runner.parameterized.Parameters

@SmallTest
@RunWith(ParameterizedAndroidJunit4::class)
class CommunalSceneInteractorTest(flags: FlagsParameterization) : SysuiTestCase() {

    companion object {
        @JvmStatic
        @Parameters(name = "{0}")
        fun getParams(): List<FlagsParameterization> {
            return FlagsParameterization.allCombinationsOf().andSceneContainer()
        }
    }

    init {
        mSetFlagsRule.setFlagsParameterization(flags)
    }

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val repository = kosmos.communalSceneRepository
    private val underTest by lazy { kosmos.communalSceneInteractor }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun changeScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(CommunalScenes.Blank)

            underTest.changeScene(CommunalScenes.Communal, "test")
            assertThat(currentScene).isEqualTo(CommunalScenes.Communal)
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun changeScene_callsSceneStateProcessor() =
        testScope.runTest {
            val callback: OnSceneAboutToChangeListener = mock()
            underTest.registerSceneStateProcessor(callback)

            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(CommunalScenes.Blank)
            verify(callback, never()).onSceneAboutToChange(any(), anyOrNull())

            underTest.changeScene(CommunalScenes.Communal, "test")
            assertThat(currentScene).isEqualTo(CommunalScenes.Communal)
            verify(callback).onSceneAboutToChange(CommunalScenes.Communal, null)
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun changeScene_doesNotCallSceneStateProcessorForDuplicateState() =
        testScope.runTest {
            val callback: OnSceneAboutToChangeListener = mock()
            underTest.registerSceneStateProcessor(callback)

            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(CommunalScenes.Blank)

            underTest.changeScene(CommunalScenes.Blank, "test")
            assertThat(currentScene).isEqualTo(CommunalScenes.Blank)

            verify(callback, never()).onSceneAboutToChange(any(), anyOrNull())
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun snapToScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(CommunalScenes.Blank)

            underTest.snapToScene(CommunalScenes.Communal, "test")
            assertThat(currentScene).isEqualTo(CommunalScenes.Communal)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun snapToSceneWithDelay() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(CommunalScenes.Blank)
            underTest.snapToScene(
                CommunalScenes.Communal,
                "test",
                ActivityTransitionAnimator.TIMINGS.totalDuration
            )
            assertThat(currentScene).isEqualTo(CommunalScenes.Blank)
            advanceTimeBy(ActivityTransitionAnimator.TIMINGS.totalDuration)
            assertThat(currentScene).isEqualTo(CommunalScenes.Communal)
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun transitionProgress_fullProgress() =
        testScope.runTest {
            val transitionProgress by
                collectLastValue(underTest.transitionProgressToScene(CommunalScenes.Blank))
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Idle(CommunalScenes.Blank))

            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(CommunalScenes.Communal)
                )
            underTest.setTransitionState(transitionState)

            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Idle(CommunalScenes.Communal))
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun transitionProgress_transitioningAwayFromTrackedScene() =
        testScope.runTest {
            val transitionProgress by
                collectLastValue(underTest.transitionProgressToScene(CommunalScenes.Blank))

            val progress = MutableStateFlow(0f)
            underTest.setTransitionState(
                MutableStateFlow(
                    ObservableTransitionState.Transition(
                        fromScene = CommunalScenes.Blank,
                        toScene = CommunalScenes.Communal,
                        currentScene = flowOf(CommunalScenes.Communal),
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            )

            // Partially transition.
            progress.value = .4f

            // This is a transition we don't care about the progress of.
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.OtherTransition)

            // Transition is at full progress.
            progress.value = 1f
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.OtherTransition)

            // Transition finishes.
            underTest.setTransitionState(
                MutableStateFlow(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Idle(CommunalScenes.Communal))
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun transitionProgress_transitioningToTrackedScene() =
        testScope.runTest {
            val transitionProgress by
                collectLastValue(underTest.transitionProgressToScene(CommunalScenes.Communal))

            val progress = MutableStateFlow(0f)
            underTest.setTransitionState(
                MutableStateFlow(
                    ObservableTransitionState.Transition(
                        fromScene = CommunalScenes.Blank,
                        toScene = CommunalScenes.Communal,
                        currentScene = flowOf(CommunalScenes.Communal),
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            )

            // Partially transition.
            progress.value = .4f
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Transition(0.4f))

            // Transition is at full progress.
            progress.value = 1f
            assertThat(transitionProgress).isEqualTo(CommunalTransitionProgressModel.Transition(1f))

            // Transition finishes.
            underTest.setTransitionState(
                MutableStateFlow(ObservableTransitionState.Idle(CommunalScenes.Communal))
            )
            assertThat(transitionProgress)
                .isEqualTo(CommunalTransitionProgressModel.Idle(CommunalScenes.Communal))
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun isIdleOnCommunal() =
        testScope.runTest {
            // isIdleOnCommunal is false when not on communal.
            val isIdleOnCommunal by collectLastValue(underTest.isIdleOnCommunal)
            assertThat(isIdleOnCommunal).isEqualTo(false)

            val transitionState: MutableStateFlow<ObservableTransitionState> =
                MutableStateFlow(ObservableTransitionState.Idle(CommunalScenes.Communal))

            // Transition to communal.
            repository.setTransitionState(transitionState)
            assertThat(isIdleOnCommunal).isEqualTo(true)

            // Start transition away from communal.
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = CommunalScenes.Communal,
                    toScene = CommunalScenes.Blank,
                    currentScene = flowOf(CommunalScenes.Blank),
                    progress = flowOf(0f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isIdleOnCommunal).isEqualTo(false)
        }

    @DisableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun isCommunalVisible() =
        testScope.runTest {
            // isCommunalVisible is false when not on communal.
            val isCommunalVisible by collectLastValue(underTest.isCommunalVisible)
            assertThat(isCommunalVisible).isEqualTo(false)

            val transitionState: MutableStateFlow<ObservableTransitionState> =
                MutableStateFlow(
                    ObservableTransitionState.Transition(
                        fromScene = CommunalScenes.Blank,
                        toScene = CommunalScenes.Communal,
                        currentScene = flowOf(CommunalScenes.Communal),
                        progress = flowOf(0f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )

            // Start transition to communal.
            repository.setTransitionState(transitionState)
            assertThat(isCommunalVisible).isEqualTo(true)

            // Finish transition to communal
            transitionState.value = ObservableTransitionState.Idle(CommunalScenes.Communal)
            assertThat(isCommunalVisible).isEqualTo(true)

            // Start transition away from communal.
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = CommunalScenes.Communal,
                    toScene = CommunalScenes.Blank,
                    currentScene = flowOf(CommunalScenes.Blank),
                    progress = flowOf(1.0f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isCommunalVisible).isEqualTo(true)
        }

    @EnableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun changeScene_legacyCommunalScene_mapToStfScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)

            // Verify that the current scene is the initial scene
            assertThat(currentScene).isEqualTo(kosmos.initialSceneKey)

            // Change to legacy communal scene
            underTest.changeScene(CommunalScenes.Communal, loggingReason = "test")

            // Verify that scene changed to communal scene in STF
            assertThat(currentScene).isEqualTo(Scenes.Communal)

            // Now change to legacy blank scene
            underTest.changeScene(CommunalScenes.Blank, loggingReason = "test")

            // Verify that scene changed to lock screen scene in STF
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @EnableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun changeScene_stfScenes() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)

            // Verify that the current scene is the initial scene
            assertThat(currentScene).isEqualTo(kosmos.initialSceneKey)

            // Change to communal scene
            underTest.changeScene(Scenes.Communal, loggingReason = "test")

            // Verify changed to communal scene
            assertThat(currentScene).isEqualTo(Scenes.Communal)

            // Now change to lockscreen scene
            underTest.changeScene(Scenes.Lockscreen, loggingReason = "test")

            // Verify changed to lockscreen scene
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @EnableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun snapToScene_legacyCommunalScene_mapToStfScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)

            // Verify that the current scene is the initial scene
            assertThat(currentScene).isEqualTo(kosmos.initialSceneKey)

            // Snap to legacy communal scene
            underTest.snapToScene(CommunalScenes.Communal, loggingReason = "test")

            // Verify that scene changed to communal scene in STF
            assertThat(currentScene).isEqualTo(Scenes.Communal)

            // Now snap to legacy blank scene
            underTest.snapToScene(CommunalScenes.Blank, loggingReason = "test")

            // Verify that scene changed to lock screen scene in STF
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @EnableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun snapToScene_stfScenes() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)

            // Verify that the current scene is the initial scene
            assertThat(currentScene).isEqualTo(kosmos.initialSceneKey)

            // Snap to communal scene
            underTest.snapToScene(Scenes.Communal, loggingReason = "test")

            // Verify changed to communal scene
            assertThat(currentScene).isEqualTo(Scenes.Communal)

            // Now snap to lockscreen scene
            underTest.snapToScene(Scenes.Lockscreen, loggingReason = "test")

            // Verify changed to lockscreen scene
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
        }

    @EnableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun isIdleOnCommunal_sceneContainerEnabled() =
        testScope.runTest {
            val transitionState: MutableStateFlow<ObservableTransitionState> =
                MutableStateFlow(ObservableTransitionState.Idle(Scenes.Lockscreen))
            underTest.setTransitionState(transitionState)

            // isIdleOnCommunal is initially false
            val isIdleOnCommunal by collectLastValue(underTest.isIdleOnCommunal)
            assertThat(isIdleOnCommunal).isEqualTo(false)

            // Start transition to communal.
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Lockscreen,
                    toScene = Scenes.Communal,
                    currentScene = flowOf(Scenes.Lockscreen),
                    progress = flowOf(0.1f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isIdleOnCommunal).isEqualTo(false)

            // Finish transition to communal
            transitionState.value = ObservableTransitionState.Idle(Scenes.Communal)
            assertThat(isIdleOnCommunal).isEqualTo(true)

            // Start transition away from communal
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Communal,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Communal),
                    progress = flowOf(0.1f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isIdleOnCommunal).isEqualTo(false)

            // Finish transition to lock screen
            transitionState.value = ObservableTransitionState.Idle(Scenes.Lockscreen)
            assertThat(isIdleOnCommunal).isEqualTo(false)
        }

    @EnableFlags(FLAG_SCENE_CONTAINER)
    @Test
    fun isCommunalVisible_sceneContainerEnabled() =
        testScope.runTest {
            val transitionState: MutableStateFlow<ObservableTransitionState> =
                MutableStateFlow(ObservableTransitionState.Idle(Scenes.Lockscreen))
            underTest.setTransitionState(transitionState)

            // isCommunalVisible is initially false
            val isCommunalVisible by collectLastValue(underTest.isCommunalVisible)
            assertThat(isCommunalVisible).isEqualTo(false)

            // Start transition to communal.
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Lockscreen,
                    toScene = Scenes.Communal,
                    currentScene = flowOf(Scenes.Lockscreen),
                    progress = flowOf(0.1f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isCommunalVisible).isEqualTo(true)

            // Half-way transition to communal.
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Lockscreen,
                    toScene = Scenes.Communal,
                    currentScene = flowOf(Scenes.Lockscreen),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isCommunalVisible).isEqualTo(true)

            // Finish transition to communal
            transitionState.value = ObservableTransitionState.Idle(Scenes.Communal)
            assertThat(isCommunalVisible).isEqualTo(true)

            // Start transition away from communal
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Communal,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Communal),
                    progress = flowOf(0.1f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isCommunalVisible).isEqualTo(true)

            // Half-way transition away from communal
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Communal,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Communal),
                    progress = flowOf(0.5f),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(isCommunalVisible).isEqualTo(true)

            // Finish transition to lock screen
            transitionState.value = ObservableTransitionState.Idle(Scenes.Lockscreen)
            assertThat(isCommunalVisible).isEqualTo(false)
        }
}
