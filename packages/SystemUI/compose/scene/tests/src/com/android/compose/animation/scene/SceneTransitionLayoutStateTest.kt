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

package com.android.compose.animation.scene

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneTransitionLayoutStateTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun isTransitioningTo_idle() {
        val state = SceneTransitionLayoutState(TestScenes.SceneA)

        assertThat(state.isTransitioning()).isFalse()
        assertThat(state.isTransitioning(from = TestScenes.SceneA)).isFalse()
        assertThat(state.isTransitioning(to = TestScenes.SceneB)).isFalse()
        assertThat(state.isTransitioning(from = TestScenes.SceneA, to = TestScenes.SceneB))
            .isFalse()
    }

    @Test
    fun isTransitioningTo_transition() {
        val state = SceneTransitionLayoutStateImpl(TestScenes.SceneA, SceneTransitions.Empty)
        state.startTransition(transition(from = TestScenes.SceneA, to = TestScenes.SceneB))

        assertThat(state.isTransitioning()).isTrue()
        assertThat(state.isTransitioning(from = TestScenes.SceneA)).isTrue()
        assertThat(state.isTransitioning(from = TestScenes.SceneB)).isFalse()
        assertThat(state.isTransitioning(to = TestScenes.SceneB)).isTrue()
        assertThat(state.isTransitioning(to = TestScenes.SceneA)).isFalse()
        assertThat(state.isTransitioning(from = TestScenes.SceneA, to = TestScenes.SceneB)).isTrue()
    }

    private fun transition(from: SceneKey, to: SceneKey): TransitionState.Transition {
        return object : TransitionState.Transition(from, to) {
            override val currentScene: SceneKey = from
            override val progress: Float = 0f
            override val isInitiatedByUserInput: Boolean = false
            override val isUserInputOngoing: Boolean = false
        }
    }
}
