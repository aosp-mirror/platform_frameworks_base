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

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.ObservableTransitionState
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.android.systemui.scene.shared.model.SceneTransitionModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class SceneContainerRepositoryTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)

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
                )
            )
    }

    @Test
    fun currentScene() = runTest {
        val underTest = utils.fakeSceneContainerRepository()
        val currentScene by collectLastValue(underTest.currentScene)
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Lockscreen))

        underTest.setCurrentScene(SceneModel(SceneKey.Shade))
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Shade))
    }

    @Test(expected = IllegalStateException::class)
    fun setCurrentScene_noSuchSceneInContainer_throws() {
        val underTest =
            utils.fakeSceneContainerRepository(
                utils.fakeSceneContainerConfig(listOf(SceneKey.QuickSettings, SceneKey.Lockscreen)),
            )
        underTest.setCurrentScene(SceneModel(SceneKey.Shade))
    }

    @Test
    fun isVisible() = runTest {
        val underTest = utils.fakeSceneContainerRepository()
        val isVisible by collectLastValue(underTest.isVisible)
        assertThat(isVisible).isTrue()

        underTest.setVisible(false)
        assertThat(isVisible).isFalse()

        underTest.setVisible(true)
        assertThat(isVisible).isTrue()
    }

    @Test
    fun transitionProgress() = runTest {
        val underTest = utils.fakeSceneContainerRepository()
        val sceneTransitionProgress by collectLastValue(underTest.transitionProgress)
        assertThat(sceneTransitionProgress).isEqualTo(1f)

        val transitionState =
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Idle(SceneKey.Lockscreen)
            )
        underTest.setTransitionState(transitionState)
        assertThat(sceneTransitionProgress).isEqualTo(1f)

        val progress = MutableStateFlow(1f)
        transitionState.value =
            ObservableTransitionState.Transition(
                fromScene = SceneKey.Lockscreen,
                toScene = SceneKey.Shade,
                progress = progress,
            )
        assertThat(sceneTransitionProgress).isEqualTo(1f)

        progress.value = 0.1f
        assertThat(sceneTransitionProgress).isEqualTo(0.1f)

        progress.value = 0.9f
        assertThat(sceneTransitionProgress).isEqualTo(0.9f)

        underTest.setTransitionState(null)
        assertThat(sceneTransitionProgress).isEqualTo(1f)
    }

    @Test
    fun setSceneTransition() = runTest {
        val underTest = utils.fakeSceneContainerRepository()
        val sceneTransition by collectLastValue(underTest.transitions)
        assertThat(sceneTransition).isNull()

        underTest.setSceneTransition(SceneKey.Lockscreen, SceneKey.QuickSettings)
        assertThat(sceneTransition)
            .isEqualTo(
                SceneTransitionModel(from = SceneKey.Lockscreen, to = SceneKey.QuickSettings)
            )
    }

    @Test(expected = IllegalStateException::class)
    fun setSceneTransition_noFromSceneInContainer_throws() {
        val underTest =
            utils.fakeSceneContainerRepository(
                utils.fakeSceneContainerConfig(listOf(SceneKey.QuickSettings, SceneKey.Lockscreen)),
            )
        underTest.setSceneTransition(SceneKey.Shade, SceneKey.Lockscreen)
    }

    @Test(expected = IllegalStateException::class)
    fun setSceneTransition_noToSceneInContainer_throws() {
        val underTest =
            utils.fakeSceneContainerRepository(
                utils.fakeSceneContainerConfig(listOf(SceneKey.QuickSettings, SceneKey.Lockscreen)),
            )
        underTest.setSceneTransition(SceneKey.Shade, SceneKey.Lockscreen)
    }
}
