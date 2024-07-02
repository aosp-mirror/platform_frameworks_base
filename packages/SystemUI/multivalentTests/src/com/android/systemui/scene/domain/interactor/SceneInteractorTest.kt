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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.keyguard.data.repository.fakeDeviceEntryFingerprintAuthRepository
import com.android.systemui.keyguard.shared.model.SuccessFingerprintAuthenticationStatus
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.data.repository.Idle
import com.android.systemui.scene.data.repository.Transition
import com.android.systemui.scene.data.repository.sceneContainerRepository
import com.android.systemui.scene.data.repository.setSceneTransition
import com.android.systemui.scene.domain.resolver.homeSceneFamilyResolver
import com.android.systemui.scene.sceneContainerConfig
import com.android.systemui.scene.sceneKeys
import com.android.systemui.scene.shared.model.SceneFamilies
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.scene.shared.model.fakeSceneDataSource
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class SceneInteractorTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val fakeSceneDataSource = kosmos.fakeSceneDataSource

    private val underTest = kosmos.sceneInteractor

    @Test
    fun allSceneKeys() {
        assertThat(underTest.allSceneKeys()).isEqualTo(kosmos.sceneKeys)
    }

    @Test
    fun changeScene_toUnknownScene_doesNothing() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            val unknownScene = SceneKey("UNKNOWN")
            val previousScene = currentScene
            assertThat(previousScene).isNotEqualTo(unknownScene)
            underTest.changeScene(unknownScene, "reason")
            assertThat(currentScene).isEqualTo(previousScene)
        }

    @Test
    fun changeScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            underTest.changeScene(Scenes.Shade, "reason")
            assertThat(currentScene).isEqualTo(Scenes.Shade)
        }

    @Test
    fun changeScene_toGoneWhenUnl_doesNotThrow() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            underTest.changeScene(Scenes.Gone, "reason")
            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test(expected = IllegalStateException::class)
    fun changeScene_toGoneWhenStillLocked_throws() =
        testScope.runTest { underTest.changeScene(Scenes.Gone, "reason") }

    @Test
    fun changeScene_toGoneWhenTransitionToLockedFromGone() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            val transitionTo by collectLastValue(underTest.transitioningTo)
            kosmos.sceneContainerRepository.setTransitionState(
                flowOf(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Gone,
                        toScene = Scenes.Lockscreen,
                        currentScene = flowOf(Scenes.Lockscreen),
                        progress = flowOf(.5f),
                        isInitiatedByUserInput = true,
                        isUserInputOngoing = flowOf(false),
                    )
                )
            )
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)
            assertThat(transitionTo).isEqualTo(Scenes.Lockscreen)

            underTest.changeScene(Scenes.Gone, "simulate double tap power")
            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test
    fun changeScene_toHomeSceneFamily() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)

            underTest.changeScene(SceneFamilies.Home, "reason")
            runCurrent()

            assertThat(currentScene).isEqualTo(kosmos.homeSceneFamilyResolver.resolvedScene.value)
        }

    @Test
    fun snapToScene_toUnknownScene_doesNothing() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            val previousScene = currentScene
            val unknownScene = SceneKey("UNKNOWN")
            assertThat(previousScene).isNotEqualTo(unknownScene)
            underTest.snapToScene(unknownScene, "reason")
            assertThat(currentScene).isEqualTo(previousScene)
        }

    @Test
    fun snapToScene() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            underTest.snapToScene(Scenes.Shade, "reason")
            assertThat(currentScene).isEqualTo(Scenes.Shade)
        }

    @Test
    fun snapToScene_toGoneWhenUnl_doesNotThrow() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            kosmos.fakeDeviceEntryFingerprintAuthRepository.setAuthenticationStatus(
                SuccessFingerprintAuthenticationStatus(0, true)
            )
            runCurrent()

            underTest.snapToScene(Scenes.Gone, "reason")
            assertThat(currentScene).isEqualTo(Scenes.Gone)
        }

    @Test(expected = IllegalStateException::class)
    fun snapToScene_toGoneWhenStillLocked_throws() =
        testScope.runTest { underTest.snapToScene(Scenes.Gone, "reason") }

    @Test
    fun snapToScene_toHomeSceneFamily() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)

            underTest.snapToScene(SceneFamilies.Home, "reason")
            runCurrent()

            assertThat(currentScene).isEqualTo(kosmos.homeSceneFamilyResolver.resolvedScene.value)
        }

    @Test
    fun sceneChanged_inDataSource() =
        testScope.runTest {
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            fakeSceneDataSource.changeScene(Scenes.Shade)

            assertThat(currentScene).isEqualTo(Scenes.Shade)
        }

    @Test
    fun transitionState() =
        testScope.runTest {
            val sceneContainerRepository = kosmos.sceneContainerRepository
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.Lockscreen)
                )
            sceneContainerRepository.setTransitionState(transitionState)
            val reflectedTransitionState by
                collectLastValue(sceneContainerRepository.transitionState)
            assertThat(reflectedTransitionState).isEqualTo(transitionState.value)

            val progress = MutableStateFlow(1f)
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Lockscreen,
                    toScene = Scenes.Shade,
                    currentScene = flowOf(Scenes.Shade),
                    progress = progress,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(reflectedTransitionState).isEqualTo(transitionState.value)

            progress.value = 0.1f
            assertThat(reflectedTransitionState).isEqualTo(transitionState.value)

            progress.value = 0.9f
            assertThat(reflectedTransitionState).isEqualTo(transitionState.value)

            sceneContainerRepository.setTransitionState(null)
            assertThat(reflectedTransitionState)
                .isEqualTo(
                    ObservableTransitionState.Idle(kosmos.sceneContainerConfig.initialSceneKey)
                )
        }

    @Test
    fun transitioningTo() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(underTest.currentScene.value)
                )
            underTest.setTransitionState(transitionState)

            val transitionTo by collectLastValue(underTest.transitioningTo)
            assertThat(transitionTo).isNull()

            underTest.changeScene(Scenes.Shade, "reason")
            assertThat(transitionTo).isNull()

            val progress = MutableStateFlow(0f)
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = underTest.currentScene.value,
                    toScene = Scenes.Shade,
                    currentScene = flowOf(Scenes.Shade),
                    progress = progress,
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            assertThat(transitionTo).isEqualTo(Scenes.Shade)

            progress.value = 0.5f
            assertThat(transitionTo).isEqualTo(Scenes.Shade)

            progress.value = 1f
            assertThat(transitionTo).isEqualTo(Scenes.Shade)

            transitionState.value = ObservableTransitionState.Idle(Scenes.Shade)
            assertThat(transitionTo).isNull()
        }

    @Test
    fun isTransitionUserInputOngoing_idle_false() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.Shade)
                )
            val isTransitionUserInputOngoing by
                collectLastValue(underTest.isTransitionUserInputOngoing)
            underTest.setTransitionState(transitionState)

            assertThat(isTransitionUserInputOngoing).isFalse()
        }

    @Test
    fun isTransitionUserInputOngoing_transition_true() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Shade,
                        toScene = Scenes.Lockscreen,
                        currentScene = flowOf(Scenes.Shade),
                        progress = flowOf(0.5f),
                        isInitiatedByUserInput = true,
                        isUserInputOngoing = flowOf(true),
                    )
                )
            val isTransitionUserInputOngoing by
                collectLastValue(underTest.isTransitionUserInputOngoing)
            underTest.setTransitionState(transitionState)

            assertThat(isTransitionUserInputOngoing).isTrue()
        }

    @Test
    fun isTransitionUserInputOngoing_updateMidTransition_false() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Shade,
                        toScene = Scenes.Lockscreen,
                        currentScene = flowOf(Scenes.Shade),
                        progress = flowOf(0.5f),
                        isInitiatedByUserInput = true,
                        isUserInputOngoing = flowOf(true),
                    )
                )
            val isTransitionUserInputOngoing by
                collectLastValue(underTest.isTransitionUserInputOngoing)
            underTest.setTransitionState(transitionState)

            assertThat(isTransitionUserInputOngoing).isTrue()

            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = Scenes.Shade,
                    toScene = Scenes.Lockscreen,
                    currentScene = flowOf(Scenes.Lockscreen),
                    progress = flowOf(0.6f),
                    isInitiatedByUserInput = true,
                    isUserInputOngoing = flowOf(false),
                )

            assertThat(isTransitionUserInputOngoing).isFalse()
        }

    @Test
    fun isTransitionUserInputOngoing_updateOnIdle_false() =
        testScope.runTest {
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Transition(
                        fromScene = Scenes.Shade,
                        toScene = Scenes.Lockscreen,
                        currentScene = flowOf(Scenes.Shade),
                        progress = flowOf(0.5f),
                        isInitiatedByUserInput = true,
                        isUserInputOngoing = flowOf(true),
                    )
                )
            val isTransitionUserInputOngoing by
                collectLastValue(underTest.isTransitionUserInputOngoing)
            underTest.setTransitionState(transitionState)

            assertThat(isTransitionUserInputOngoing).isTrue()

            transitionState.value = ObservableTransitionState.Idle(currentScene = Scenes.Lockscreen)

            assertThat(isTransitionUserInputOngoing).isFalse()
        }

    @Test
    fun isVisible() =
        testScope.runTest {
            val isVisible by collectLastValue(underTest.isVisible)
            assertThat(isVisible).isTrue()

            underTest.setVisible(false, "reason")
            assertThat(isVisible).isFalse()

            underTest.setVisible(true, "reason")
            assertThat(isVisible).isTrue()
        }

    @Test
    fun isVisible_duringRemoteUserInteraction_forcedVisible() =
        testScope.runTest {
            underTest.setVisible(false, "reason")
            val isVisible by collectLastValue(underTest.isVisible)
            assertThat(isVisible).isFalse()
            underTest.onRemoteUserInteractionStarted("reason")
            assertThat(isVisible).isTrue()

            underTest.onUserInteractionFinished()

            assertThat(isVisible).isFalse()
        }

    @Test
    fun resolveSceneFamily_home() =
        testScope.runTest {
            assertThat(underTest.resolveSceneFamily(SceneFamilies.Home).first())
                .isEqualTo(kosmos.homeSceneFamilyResolver.resolvedScene.value)
        }

    @Test
    fun resolveSceneFamily_nonFamily() =
        testScope.runTest {
            val resolved = underTest.resolveSceneFamily(Scenes.Gone).toList()
            assertThat(resolved).containsExactly(Scenes.Gone).inOrder()
        }

    @Test
    fun transitionValue_test_idle() =
        testScope.runTest {
            val transitionValue by collectLastValue(underTest.transitionProgress(Scenes.Gone))

            kosmos.setSceneTransition(Idle(Scenes.Gone))
            assertThat(transitionValue).isEqualTo(1f)

            kosmos.setSceneTransition(Idle(Scenes.Lockscreen))
            assertThat(transitionValue).isEqualTo(0f)
        }

    @Test
    fun transitionValue_test_transitions() =
        testScope.runTest {
            val transitionValue by collectLastValue(underTest.transitionProgress(Scenes.Gone))
            val progress = MutableStateFlow(0f)

            kosmos.setSceneTransition(
                Transition(from = Scenes.Lockscreen, to = Scenes.Gone, progress = progress)
            )
            assertThat(transitionValue).isEqualTo(0f)

            progress.value = 0.4f
            assertThat(transitionValue).isEqualTo(0.4f)

            kosmos.setSceneTransition(
                Transition(from = Scenes.Gone, to = Scenes.Lockscreen, progress = progress)
            )
            progress.value = 0.7f
            assertThat(transitionValue).isEqualTo(0.3f)

            kosmos.setSceneTransition(
                Transition(from = Scenes.Lockscreen, to = Scenes.Shade, progress = progress)
            )
            progress.value = 0.9f
            assertThat(transitionValue).isEqualTo(0f)
        }
}
