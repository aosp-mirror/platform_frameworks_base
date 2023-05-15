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

package com.android.systemui.scene.ui.viewmodel

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.data.repository.fakeSceneContainerRepository
import com.android.systemui.scene.data.repository.fakeSceneKeys
import com.android.systemui.scene.domain.interactor.SceneInteractor
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
class SceneContainerViewModelTest : SysuiTestCase() {
    private val interactor =
        SceneInteractor(
            repository = fakeSceneContainerRepository(),
        )
    private val underTest =
        SceneContainerViewModel(
            interactor = interactor,
            containerName = "container1",
        )

    @Test
    fun isVisible() = runTest {
        val isVisible by collectLastValue(underTest.isVisible)
        assertThat(isVisible).isTrue()

        interactor.setVisible("container1", false)
        assertThat(isVisible).isFalse()

        interactor.setVisible("container1", true)
        assertThat(isVisible).isTrue()
    }

    @Test
    fun allSceneKeys() {
        assertThat(underTest.allSceneKeys).isEqualTo(fakeSceneKeys())
    }

    @Test
    fun sceneTransition() = runTest {
        val currentScene by collectLastValue(underTest.currentScene)
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.LockScreen))

        underTest.setCurrentScene(SceneModel(SceneKey.Shade))
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Shade))
    }
}
