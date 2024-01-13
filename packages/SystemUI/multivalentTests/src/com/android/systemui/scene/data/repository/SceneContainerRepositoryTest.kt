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
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.sceneKeys
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SceneContainerRepositoryTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this).apply { fakeSceneContainerFlags.enabled = true }
    private val testScope = utils.testScope

    @Test
    fun allSceneKeys() {
        val underTest = utils.fakeSceneContainerRepository()
        assertThat(underTest.allSceneKeys())
            .isEqualTo(
                listOf(
                    SceneKey.QuickSettings,
                    SceneKey.Shade,
                    SceneKey.Lockscreen,
                    SceneKey.Bouncer,
                    SceneKey.Gone,
                    SceneKey.Communal,
                )
            )
    }

    @Test
    fun desiredScene() =
        testScope.runTest {
            val underTest = utils.fakeSceneContainerRepository()
            val currentScene by collectLastValue(underTest.desiredScene)
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Lockscreen))

            underTest.setDesiredScene(SceneModel(SceneKey.Shade))
            assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Shade))
        }

    @Test(expected = IllegalStateException::class)
    fun setDesiredScene_noSuchSceneInContainer_throws() {
        utils.kosmos.sceneKeys = listOf(SceneKey.QuickSettings, SceneKey.Lockscreen)
        val underTest = utils.fakeSceneContainerRepository()
        underTest.setDesiredScene(SceneModel(SceneKey.Shade))
    }

    @Test
    fun isVisible() =
        testScope.runTest {
            val underTest = utils.fakeSceneContainerRepository()
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
            val underTest = utils.fakeSceneContainerRepository()
            val transitionState by collectLastValue(underTest.transitionState)

            assertThat(transitionState)
                .isEqualTo(
                    ObservableTransitionState.Idle(utils.fakeSceneContainerConfig().initialSceneKey)
                )
        }

    @Test
    fun transitionState_reflectsUpdates() =
        testScope.runTest {
            val underTest = utils.fakeSceneContainerRepository()
            val transitionState =
                MutableStateFlow<ObservableTransitionState>(
                    ObservableTransitionState.Idle(SceneKey.Lockscreen)
                )
            underTest.setTransitionState(transitionState)
            val reflectedTransitionState by collectLastValue(underTest.transitionState)
            assertThat(reflectedTransitionState).isEqualTo(transitionState.value)

            val progress = MutableStateFlow(1f)
            transitionState.value =
                ObservableTransitionState.Transition(
                    fromScene = SceneKey.Lockscreen,
                    toScene = SceneKey.Shade,
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
                    ObservableTransitionState.Idle(utils.fakeSceneContainerConfig().initialSceneKey)
                )
        }
}
