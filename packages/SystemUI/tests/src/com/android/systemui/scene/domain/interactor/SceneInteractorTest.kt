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
import com.android.systemui.scene.data.repository.fakeSceneContainerRepository
import com.android.systemui.scene.data.repository.fakeSceneKeys
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
class SceneInteractorTest : SysuiTestCase() {

    private val underTest =
        SceneInteractor(
            repository = fakeSceneContainerRepository(),
        )

    @Test
    fun allSceneKeys() {
        assertThat(underTest.allSceneKeys("container1")).isEqualTo(fakeSceneKeys())
    }

    @Test
    fun sceneTransitions() = runTest {
        val currentScene by collectLastValue(underTest.currentScene("container1"))
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

        underTest.setCurrentScene("container1", SceneModel(SceneKey.Shade))
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Shade))
    }

    @Test
    fun sceneTransitionProgress() = runTest {
        val progress by collectLastValue(underTest.sceneTransitionProgress("container1"))
        assertThat(progress).isEqualTo(1f)

        underTest.setSceneTransitionProgress("container1", 0.55f)
        assertThat(progress).isEqualTo(0.55f)
    }

    @Test
    fun isVisible() = runTest {
        val isVisible by collectLastValue(underTest.isVisible("container1"))
        assertThat(isVisible).isTrue()

        underTest.setVisible("container1", false)
        assertThat(isVisible).isFalse()

        underTest.setVisible("container1", true)
        assertThat(isVisible).isTrue()
    }
}
