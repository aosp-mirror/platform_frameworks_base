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
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class SceneContainerRepositoryTest : SysuiTestCase() {

    @Test
    fun allSceneKeys() {
        val underTest = fakeSceneContainerRepository()
        assertThat(underTest.allSceneKeys("container1"))
            .isEqualTo(
                listOf(
                    SceneKey.QuickSettings,
                    SceneKey.Shade,
                    SceneKey.LockScreen,
                    SceneKey.Bouncer,
                    SceneKey.Gone,
                )
            )
    }

    @Test(expected = IllegalStateException::class)
    fun allSceneKeys_noSuchContainer_throws() {
        val underTest = fakeSceneContainerRepository()
        underTest.allSceneKeys("nonExistingContainer")
    }

    @Test
    fun currentScene() = runTest {
        val underTest = fakeSceneContainerRepository()
        val currentScene by collectLastValue(underTest.currentScene("container1"))
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

        underTest.setCurrentScene("container1", SceneModel(SceneKey.Shade))
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Shade))
    }

    @Test(expected = IllegalStateException::class)
    fun currentScene_noSuchContainer_throws() {
        val underTest = fakeSceneContainerRepository()
        underTest.currentScene("nonExistingContainer")
    }

    @Test(expected = IllegalStateException::class)
    fun setCurrentScene_noSuchContainer_throws() {
        val underTest = fakeSceneContainerRepository()
        underTest.setCurrentScene("nonExistingContainer", SceneModel(SceneKey.Shade))
    }

    @Test(expected = IllegalStateException::class)
    fun setCurrentScene_noSuchSceneInContainer_throws() {
        val underTest =
            fakeSceneContainerRepository(
                setOf(
                    fakeSceneContainerConfig("container1"),
                    fakeSceneContainerConfig(
                        "container2",
                        listOf(SceneKey.QuickSettings, SceneKey.LockScreen)
                    ),
                )
            )
        underTest.setCurrentScene("container2", SceneModel(SceneKey.Shade))
    }

    @Test
    fun isVisible() = runTest {
        val underTest = fakeSceneContainerRepository()
        val isVisible by collectLastValue(underTest.isVisible("container1"))
        assertThat(isVisible).isTrue()

        underTest.setVisible("container1", false)
        assertThat(isVisible).isFalse()

        underTest.setVisible("container1", true)
        assertThat(isVisible).isTrue()
    }

    @Test(expected = IllegalStateException::class)
    fun isVisible_noSuchContainer_throws() {
        val underTest = fakeSceneContainerRepository()
        underTest.isVisible("nonExistingContainer")
    }

    @Test(expected = IllegalStateException::class)
    fun setVisible_noSuchContainer_throws() {
        val underTest = fakeSceneContainerRepository()
        underTest.setVisible("nonExistingContainer", false)
    }

    @Test
    fun sceneTransitionProgress() = runTest {
        val underTest = fakeSceneContainerRepository()
        val sceneTransitionProgress by
            collectLastValue(underTest.sceneTransitionProgress("container1"))
        assertThat(sceneTransitionProgress).isEqualTo(1f)

        underTest.setSceneTransitionProgress("container1", 0.1f)
        assertThat(sceneTransitionProgress).isEqualTo(0.1f)

        underTest.setSceneTransitionProgress("container1", 0.9f)
        assertThat(sceneTransitionProgress).isEqualTo(0.9f)
    }

    @Test(expected = IllegalStateException::class)
    fun sceneTransitionProgress_noSuchContainer_throws() {
        val underTest = fakeSceneContainerRepository()
        underTest.sceneTransitionProgress("nonExistingContainer")
    }
}
