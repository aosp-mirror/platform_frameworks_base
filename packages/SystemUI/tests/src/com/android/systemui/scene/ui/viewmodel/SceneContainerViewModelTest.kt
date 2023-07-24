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

import android.view.MotionEvent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.scene.SceneTestUtils
import com.android.systemui.scene.shared.model.RemoteUserInput
import com.android.systemui.scene.shared.model.RemoteUserInputAction
import com.android.systemui.scene.shared.model.SceneKey
import com.android.systemui.scene.shared.model.SceneModel
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class SceneContainerViewModelTest : SysuiTestCase() {

    private val utils = SceneTestUtils(this)
    private val interactor = utils.sceneInteractor()
    private val underTest =
        SceneContainerViewModel(
            interactor = interactor,
            containerName = SceneTestUtils.CONTAINER_1,
        )

    @Test
    fun isVisible() = runTest {
        val isVisible by collectLastValue(underTest.isVisible)
        assertThat(isVisible).isTrue()

        interactor.setVisible(SceneTestUtils.CONTAINER_1, false)
        assertThat(isVisible).isFalse()

        interactor.setVisible(SceneTestUtils.CONTAINER_1, true)
        assertThat(isVisible).isTrue()
    }

    @Test
    fun allSceneKeys() {
        assertThat(underTest.allSceneKeys).isEqualTo(utils.fakeSceneKeys())
    }

    @Test
    fun sceneTransition() = runTest {
        val currentScene by collectLastValue(underTest.currentScene)
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Lockscreen))

        underTest.setCurrentScene(SceneModel(SceneKey.Shade))
        assertThat(currentScene).isEqualTo(SceneModel(SceneKey.Shade))
    }

    @Test
    fun onRemoteUserInput() = runTest {
        val remoteUserInput by collectLastValue(underTest.remoteUserInput)
        assertThat(remoteUserInput).isNull()

        val inputs =
            SceneTestUtils.REMOTE_INPUT_DOWN_GESTURE.map { remoteUserInputToMotionEvent(it) }

        inputs.forEachIndexed { index, input ->
            underTest.onRemoteUserInput(input)
            assertThat(remoteUserInput).isEqualTo(SceneTestUtils.REMOTE_INPUT_DOWN_GESTURE[index])
        }
    }

    private fun TestScope.remoteUserInputToMotionEvent(input: RemoteUserInput): MotionEvent {
        return MotionEvent.obtain(
            currentTime,
            currentTime,
            when (input.action) {
                RemoteUserInputAction.DOWN -> MotionEvent.ACTION_DOWN
                RemoteUserInputAction.MOVE -> MotionEvent.ACTION_MOVE
                RemoteUserInputAction.UP -> MotionEvent.ACTION_UP
                RemoteUserInputAction.CANCEL -> MotionEvent.ACTION_CANCEL
                RemoteUserInputAction.UNKNOWN -> MotionEvent.ACTION_OUTSIDE
            },
            input.x,
            input.y,
            0
        )
    }
}
