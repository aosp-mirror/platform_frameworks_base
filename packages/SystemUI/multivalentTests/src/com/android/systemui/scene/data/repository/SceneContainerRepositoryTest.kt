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

package com.android.systemui.scene.data.repository

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.compose.animation.scene.ObservableTransitionState
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.EnableSceneContainer
import com.android.systemui.kosmos.testScope
import com.android.systemui.scene.sceneContainerConfig
import com.android.systemui.scene.sceneKeys
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
@EnableSceneContainer
class SceneContainerRepositoryTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope

    @Test
    fun allSceneKeys() {
        val underTest = kosmos.sceneContainerRepository
        assertThat(underTest.allSceneKeys())
            .isEqualTo(
                listOf(
                    Scenes.QuickSettings,
                    Scenes.Shade,
                    Scenes.Lockscreen,
                    Scenes.Bouncer,
                    Scenes.Gone,
                    Scenes.Communal,
                )
            )
    }

    @Test
    fun currentScene() =
        testScope.runTest {
            val underTest = kosmos.sceneContainerRepository
            val currentScene by collectLastValue(underTest.currentScene)
            assertThat(currentScene).isEqualTo(Scenes.Lockscreen)

            underTest.changeScene(Scenes.Shade)
            assertThat(currentScene).isEqualTo(Scenes.Shade)
        }

    @Test(expected = IllegalStateException::class)
    fun changeScene_noSuchSceneInContainer_throws() {
        kosmos.sceneKeys = listOf(Scenes.QuickSettings, Scenes.Lockscreen)
        val underTest = kosmos.sceneContainerRepository
        underTest.changeScene(Scenes.Shade)
    }

    @Test
    fun isVisible() =
        testScope.runTest {
            val underTest = kosmos.sceneContainerRepository
            val isVisible by collectLastValue(underTest.isVisible)
            assertThat(isVisible).isTrue()

            underTest.setVisible(false)
            assertThat(isVisible).isFalse()

            underTest.setVisible(true)
            assertThat(isVisible).isTrue()
        }

    @Test
    fun transitionState_defaultsToIdle() =
        testScope.runTest {
            val underTest = kosmos.sceneContainerRepository
            val transitionState by collectLastValue(underTest.transitionState)

            assertThat(transitionState)
                .isEqualTo(
                    ObservableTransitionState.Idle(kosmos.sceneContainerConfig.initialSceneKey)
                )
        }

    @Test
    fun transitionState_reflectsUpdates() =
        testScope.runTest {
            val underTest = kosmos.sceneContainerRepository
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(Scenes.Lockscreen)
                )
            underTest.setTransitionState(transitionState)
            val reflectedTransitionState by collectLastValue(underTest.transitionState)
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

            underTest.setTransitionState(null)
            assertThat(reflectedTransitionState)
                .isEqualTo(
                    ObservableTransitionState.Idle(kosmos.sceneContainerConfig.initialSceneKey)
                )
        }
}
