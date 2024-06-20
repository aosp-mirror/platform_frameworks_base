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
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.StatusBarState
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.scene.domain.interactor.sceneInteractor
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.shadeRepository
import com.android.systemui.shade.shared.model.ShadeMode
import com.android.systemui.testKosmos
import com.google.common.truth.Truth
import com.google.common.truth.Truth.assertThat
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
class ShadeInteractorSceneContainerImplTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val configurationRepository = kosmos.fakeConfigurationRepository
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val sceneInteractor = kosmos.sceneInteractor
    private val shadeRepository = kosmos.shadeRepository

    private val underTest = kosmos.shadeInteractorSceneContainerImpl

    @Test
    fun qsExpansionWhenInSplitShadeAndQsExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.qsExpansion)

            // WHEN split shade is enabled and QS is expanded
            overrideResource(R.bool.config_use_split_notification_shade, true)
            configurationRepository.onAnyConfigurationChange()
            runCurrent()
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.QuickSettings,
                        toScene = Scenes.Shade,
                        currentScene = flowOf(Scenes.Shade),
                        progress = MutableStateFlow(.3f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)

            // THEN legacy shade expansion is passed through
            Truth.assertThat(actual).isEqualTo(.3f)
        }

    @Test
    fun qsExpansionWhenNotInSplitShadeAndQsExpanded() =
        testScope.runTest {
            val actual by collectLastValue(underTest.qsExpansion)

            // WHEN split shade is not enabled and QS is expanded
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            overrideResource(R.bool.config_use_split_notification_shade, false)
            configurationRepository.onAnyConfigurationChange()
            runCurrent()
            val progress = MutableStateFlow(.3f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.QuickSettings,
                        toScene = Scenes.Shade,
                        currentScene = flowOf(Scenes.Shade),
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN shade expansion is zero
            Truth.assertThat(actual).isEqualTo(.7f)
        }

    @Test
    fun qsFullscreen_falseWhenTransitioning() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isQsFullscreen)

            // WHEN scene transition active
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.QuickSettings,
                        toScene = Scenes.Shade,
                        currentScene = flowOf(Scenes.Shade),
                        progress = MutableStateFlow(.3f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN QS is not fullscreen
            Truth.assertThat(actual).isFalse()
        }

    @Test
    fun qsFullscreen_falseWhenIdleNotQS() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isQsFullscreen)

            // WHEN Idle but not on QuickSettings scene
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.Shade)
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN QS is not fullscreen
            Truth.assertThat(actual).isFalse()
        }

    @Test
    fun qsFullscreen_trueWhenIdleQS() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isQsFullscreen)

            // WHEN Idle on QuickSettings scene
            keyguardRepository.setStatusBarState(StatusBarState.SHADE)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.QuickSettings)
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN QS is fullscreen
            Truth.assertThat(actual).isTrue()
        }

    @Test
    fun lockscreenShadeExpansion_idle_onScene() =
        testScope.runTest {
            // GIVEN an expansion flow based on transitions to and from a scene
            val key = Scenes.Shade
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, key)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is idle on the scene
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 1
            Truth.assertThat(expansionAmount).isEqualTo(1f)
        }

    @Test
    fun lockscreenShadeExpansion_idle_onDifferentScene() =
        testScope.runTest {
            // GIVEN an expansion flow based on transitions to and from a scene
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, Scenes.Shade)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is idle on a different scene
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.Lockscreen)
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)
        }

    @Test
    fun lockscreenShadeExpansion_transitioning_toScene() =
        testScope.runTest {
            // GIVEN an expansion flow based on transitions to and from a scene
            val key = Scenes.QuickSettings
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, key)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Lockscreen,
                        toScene = key,
                        currentScene = flowOf(key),
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN expansion matches the progress
            Truth.assertThat(expansionAmount).isEqualTo(.4f)

            // WHEN transition completes
            progress.value = 1f

            // THEN expansion is 1
            Truth.assertThat(expansionAmount).isEqualTo(1f)
        }

    @Test
    fun lockscreenShadeExpansion_transitioning_fromScene() =
        testScope.runTest {
            // GIVEN an expansion flow based on transitions to and from a scene
            val key = Scenes.QuickSettings
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, key)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = key,
                        toScene = Scenes.Lockscreen,
                        currentScene = flowOf(Scenes.Lockscreen),
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 1
            Truth.assertThat(expansionAmount).isEqualTo(1f)

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN expansion reflects the progress
            Truth.assertThat(expansionAmount).isEqualTo(.6f)

            // WHEN transition completes
            progress.value = 1f

            // THEN expansion is 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)
        }

    fun isQsBypassingShade_goneToQs() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isQsBypassingShade)

            // WHEN transitioning from QS directly to Gone
            configurationRepository.onAnyConfigurationChange()
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Gone,
                        toScene = Scenes.QuickSettings,
                        currentScene = flowOf(Scenes.QuickSettings),
                        progress = MutableStateFlow(.1f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN qs is bypassing shade
            Truth.assertThat(actual).isTrue()
        }

    fun isQsBypassingShade_shadeToQs() =
        testScope.runTest {
            val actual by collectLastValue(underTest.isQsBypassingShade)

            // WHEN transitioning from QS to Shade
            configurationRepository.onAnyConfigurationChange()
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Shade,
                        toScene = Scenes.QuickSettings,
                        currentScene = flowOf(Scenes.QuickSettings),
                        progress = MutableStateFlow(.1f),
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)
            runCurrent()

            // THEN qs is not bypassing shade
            Truth.assertThat(actual).isFalse()
        }

    @Test
    fun lockscreenShadeExpansion_transitioning_toAndFromDifferentScenes() =
        testScope.runTest {
            // GIVEN an expansion flow based on transitions to and from a scene
            val expansion = underTest.sceneBasedExpansion(sceneInteractor, Scenes.QuickSettings)
            val expansionAmount by collectLastValue(expansion)

            // WHEN transition state is starting to between different scenes
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Lockscreen,
                        toScene = Scenes.Shade,
                        currentScene = flowOf(Scenes.Shade),
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN expansion is 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)

            // WHEN transition state is partially complete
            progress.value = .4f

            // THEN expansion is still 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)

            // WHEN transition completes
            progress.value = 1f

            // THEN expansion is still 0
            Truth.assertThat(expansionAmount).isEqualTo(0f)
        }

    @Test
    fun userInteracting_idle() =
        testScope.runTest {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = Scenes.Shade
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is idle
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(ObservableTransitionState.Idle(key))
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()
        }

    @Test
    fun userInteracting_transitioning_toScene_programmatic() =
        testScope.runTest {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = Scenes.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Lockscreen,
                        toScene = key,
                        currentScene = flowOf(key),
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()
        }

    @Test
    fun userInteracting_transitioning_toScene_userInputDriven() =
        testScope.runTest {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = Scenes.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Lockscreen,
                        toScene = key,
                        currentScene = flowOf(key),
                        progress = progress,
                        isInitiatedByUserInput = true,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()
        }

    @Test
    fun userInteracting_transitioning_fromScene_programmatic() =
        testScope.runTest {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = Scenes.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = key,
                        toScene = Scenes.Lockscreen,
                        currentScene = flowOf(Scenes.Lockscreen),
                        progress = progress,
                        isInitiatedByUserInput = false,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()
        }

    @Test
    fun userInteracting_transitioning_fromScene_userInputDriven() =
        testScope.runTest {
            // GIVEN an interacting flow based on transitions to and from a scene
            val key = Scenes.QuickSettings
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, key)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to move to the scene
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = key,
                        toScene = Scenes.Lockscreen,
                        currentScene = flowOf(Scenes.Lockscreen),
                        progress = progress,
                        isInitiatedByUserInput = true,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()

            // WHEN transition state is partially to the scene
            progress.value = .4f

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()

            // WHEN transition completes
            progress.value = 1f

            // THEN interacting is true
            Truth.assertThat(interacting).isTrue()
        }

    @Test
    fun userInteracting_transitioning_toAndFromDifferentScenes() =
        testScope.runTest {
            // GIVEN an interacting flow based on transitions to and from a scene
            val interactingFlow = underTest.sceneBasedInteracting(sceneInteractor, Scenes.Shade)
            val interacting by collectLastValue(interactingFlow)

            // WHEN transition state is starting to between different scenes
            val progress = MutableStateFlow(0f)
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Lockscreen,
                        toScene = Scenes.QuickSettings,
                        currentScene = flowOf(Scenes.QuickSettings),
                        progress = MutableStateFlow(0f),
                        isInitiatedByUserInput = true,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            sceneInteractor.setTransitionState(transitionState)

            // THEN interacting is false
            Truth.assertThat(interacting).isFalse()
        }

    @Test
    fun shadeMode() =
        testScope.runTest {
            val shadeMode by collectLastValue(underTest.shadeMode)

            shadeRepository.setShadeMode(ShadeMode.Split)
            assertThat(shadeMode).isEqualTo(ShadeMode.Split)

            shadeRepository.setShadeMode(ShadeMode.Single)
            assertThat(shadeMode).isEqualTo(ShadeMode.Single)

            shadeRepository.setShadeMode(ShadeMode.Split)
            assertThat(shadeMode).isEqualTo(ShadeMode.Split)
        }
}
