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

@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package com.android.systemui.scene.domain.interactor

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
class SceneInteractorTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val repository = utils.fakeSceneContainerRepository()
    private val underTest = utils.sceneInteractor(repository = repository)

    @Test
    fun allSceneKeys() {
        assertThat(underTest.allSceneKeys()).isEqualTo(utils.fakeSceneKeys())
    }

    @Test
    fun currentScene() = runTest {
        val currentScene by collectLastValue(underTest.currentScene)
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Lockscreen))

        underTest.setCurrentScene(SceneModel(SceneKey.Shade), "reason")
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Shade))
    }

    @Test
    fun sceneTransitionProgress() = runTest {
        val transitionProgress by collectLastValue(underTest.transitionProgress)
        assertThat(transitionProgress).isEqualTo(1f)

        val progress = MutableStateFlow(0.55f)
        repository.setTransitionState(
            MutableStateFlow(
                ObservableTransitionState.Transition(
                    fromScene = SceneKey.Lockscreen,
                    toScene = SceneKey.Shade,
                    progress = progress,
                ),
            )
        )
        assertThat(transitionProgress).isEqualTo(0.55f)
    }

    @Test
    fun isVisible() = runTest {
        val isVisible by collectLastValue(underTest.isVisible)
        assertThat(isVisible).isTrue()

        underTest.setVisible(false, "reason")
        assertThat(isVisible).isFalse()

        underTest.setVisible(true, "reason")
        assertThat(isVisible).isTrue()
    }

    @Test
    fun sceneTransitions() = runTest {
        val transitions by collectLastValue(underTest.transitions)
        assertThat(transitions).isNull()

        val initialSceneKey = underTest.currentScene.value.key
        underTest.setCurrentScene(SceneModel(SceneKey.Shade), "reason")
        assertThat(transitions)
            .isEqualTo(
                SceneTransitionModel(
                    from = initialSceneKey,
                    to = SceneKey.Shade,
                )
            )

        underTest.setCurrentScene(SceneModel(SceneKey.QuickSettings), "reason")
        assertThat(transitions)
            .isEqualTo(
                SceneTransitionModel(
                    from = SceneKey.Shade,
                    to = SceneKey.QuickSettings,
                )
            )
    }

    @Test
    fun remoteUserInput() = runTest {
        val remoteUserInput by collectLastValue(underTest.remoteUserInput)
        assertThat(remoteUserInput).isNull()

        for (input in SceneTestUtils.REMOTE_INPUT_DOWN_GESTURE) {
            underTest.onRemoteUserInput(input)
            assertThat(remoteUserInput).isEqualTo(input)
        }
    }
}
