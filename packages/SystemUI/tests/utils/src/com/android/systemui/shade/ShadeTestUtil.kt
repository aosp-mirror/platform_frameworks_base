/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.shade

import com.android.compose.animation.scene.ObservableTransitionState
import com.android.compose.animation.scene.SceneKey
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.shade.data.repository.FakeShadeRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert

/** Sets up shade state for tests for either value of the scene container flag. */
class ShadeTestUtil constructor(val delegate: ShadeTestUtilDelegate) {

    /** Sets both shade and QS expansion. One value must be zero or values must add up to 1f. */
    fun setShadeAndQsExpansion(shadeExpansion: Float, qsExpansion: Float) {
        Assert.assertTrue(
            "One expansion must be zero or both must add up to 1",
            shadeExpansion == 0f || qsExpansion == 0f || shadeExpansion + qsExpansion == 1f,
        )
        delegate.assertFlagValid()
        delegate.setShadeAndQsExpansionInternal(shadeExpansion, qsExpansion)
    }
}

/** Sets up shade state for tests for a specific value of the scene container flag. */
interface ShadeTestUtilDelegate {
    /** Sets both shade and QS expansion. One value must be zero or values must add up to 1f. */
    fun setShadeAndQsExpansionInternal(shadeExpansion: Float, qsExpansion: Float)

    /** Asserts that the scene container flag matches this implementation. */
    fun assertFlagValid()
}

/** Sets up shade state for tests when the scene container flag is disabled. */
class ShadeTestUtilLegacyImpl(val testScope: TestScope, val shadeRepository: FakeShadeRepository) :
    ShadeTestUtilDelegate {
    override fun setShadeAndQsExpansionInternal(shadeExpansion: Float, qsExpansion: Float) {
        shadeRepository.setLegacyShadeExpansion(shadeExpansion)
        shadeRepository.setQsExpansion(qsExpansion)
        testScope.runCurrent()
    }

    override fun assertFlagValid() {
        Assert.assertFalse(SceneContainerFlag.isEnabled)
    }
}

/** Sets up shade state for tests when the scene container flag is disabled. */
class ShadeTestUtilSceneImpl(val testScope: TestScope, val sceneInteractor: SceneInteractor) :
    ShadeTestUtilDelegate {
    override fun setShadeAndQsExpansionInternal(shadeExpansion: Float, qsExpansion: Float) {
        if (shadeExpansion == 0f) {
            setTransitionProgress(Scenes.Lockscreen, Scenes.QuickSettings, qsExpansion)
        } else if (qsExpansion == 0f) {
            setTransitionProgress(Scenes.Lockscreen, Scenes.Shade, shadeExpansion)
        } else {
            setTransitionProgress(Scenes.Shade, Scenes.QuickSettings, qsExpansion)
        }
    }

    private fun setTransitionProgress(from: SceneKey, to: SceneKey, progress: Float) {
        sceneInteractor.changeScene(from, "test")
        val transitionState =
            MutableStateFlow<ObservableTransitionState>(
                ObservableTransitionState.Transition(
                    fromScene = from,
                    toScene = to,
                    progress = MutableStateFlow(progress),
                    isInitiatedByUserInput = false,
                    isUserInputOngoing = flowOf(false),
                )
            )
        sceneInteractor.setTransitionState(transitionState)
        testScope.runCurrent()
    }

    override fun assertFlagValid() {
        Assert.assertTrue(SceneContainerFlag.isEnabled)
    }
}
