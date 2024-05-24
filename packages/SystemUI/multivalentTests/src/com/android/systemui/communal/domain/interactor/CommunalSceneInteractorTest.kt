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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.communal.data.repository.communalSceneRepository
import com.android.systemui.communal.domain.model.CommunalTransitionProgressModel
import com.android.systemui.communal.shared.model.CommunalScenes
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CommunalSceneInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    private val repository = kosmos.communalSceneRepository
    private val underTest by lazy { kosmos.communalSceneInteractor }

    @Test
    fun changeScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(CommunalScenes.Blank)

            underTest.changeScene(CommunalScenes.Communal)
            assertThat(currentScene).isEqualTo(CommunalScenes.Communal)
        }

    @Test
    fun snapToScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(CommunalScenes.Blank)

            underTest.snapToScene(CommunalScenes.Communal)
            assertThat(currentScene).isEqualTo(CommunalScenes.Communal)
        }

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
}
