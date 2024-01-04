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

package com.android.compose.animation.scene

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MovableElementScenePickerTest {
    @Test
    fun toSceneInScenes() {
        val picker = MovableElementScenePicker(scenes = setOf(TestScenes.SceneA, TestScenes.SceneB))
        assertThat(
                picker.sceneDuringTransition(
                    TestElements.Foo,
                    transition(from = TestScenes.SceneA, to = TestScenes.SceneB),
                    fromSceneZIndex = 0f,
                    toSceneZIndex = 1f,
                )
            )
            .isEqualTo(TestScenes.SceneB)
    }

    @Test
    fun toSceneNotInScenes() {
        val picker = MovableElementScenePicker(scenes = emptySet())
        assertThat(
                picker.sceneDuringTransition(
                    TestElements.Foo,
                    transition(from = TestScenes.SceneA, to = TestScenes.SceneB),
                    fromSceneZIndex = 0f,
                    toSceneZIndex = 1f,
                )
            )
            .isEqualTo(TestScenes.SceneA)
    }
}
