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

package com.android.systemui.shade.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeAnimationInteractorSceneContainerImplTest : SysuiTestCase() {
    val kosmos = testKosmos()
    val testScope = kosmos.testScope
    val sceneInteractor = kosmos.sceneInteractor

    val underTest = kosmos.shadeAnimationInteractorSceneContainerImpl

    @Test
    fun isAnyCloseAnimationRunning_qsToShade() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isAnyCloseAnimationRunning)

            // WHEN transitioning from QS to Shade
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.QuickSettings,
                        toScene = Scenes.Shade,
                        currentScene = flowOf(Scenes.Shade),
                        progress = MutableStateFlow(.1f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN qs is animating closed
            Truth.assertThat(actual).isFalse()
        }

    @Test
    fun isAnyCloseAnimationRunning_qsToGone_userInputNotOngoing() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isAnyCloseAnimationRunning)

            // WHEN transitioning from QS to Gone lwith no ongoing user input
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.QuickSettings,
                        toScene = Scenes.Gone,
                        currentScene = flowOf(Scenes.Gone),
                        progress = MutableStateFlow(.1f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN qs is animating closed
            Truth.assertThat(actual).isTrue()
        }

    @Test
    fun isAnyCloseAnimationRunning_qsToGone_userInputOngoing() =
        testScope.runTest() {
            val actual by collectLastValue(underTest.isAnyCloseAnimationRunning)

            // WHEN transitioning from QS to Gone with user input ongoing
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.QuickSettings,
                        toScene = Scenes.Gone,
                        currentScene = flowOf(Scenes.QuickSettings),
                        progress = MutableStateFlow(.1f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(true),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN qs is not animating closed
            Truth.assertThat(actual).isFalse()
        }

    @Test
    fun updateIsLaunchingActivity() =
        testScope.runTest {
            Truth.assertThat(underTest.isLaunchingActivity.value).isEqualTo(false)

            underTest.setIsLaunchingActivity(true)
            Truth.assertThat(underTest.isLaunchingActivity.value).isEqualTo(true)
        }
}
