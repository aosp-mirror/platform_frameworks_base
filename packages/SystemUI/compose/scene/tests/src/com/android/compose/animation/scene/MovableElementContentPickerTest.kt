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
import com.android.compose.test.transition
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MovableElementContentPickerTest {
    @Test
    fun toSceneInScenes() {
        val picker =
            MovableElementContentPicker(contents = setOf(TestScenes.SceneA, TestScenes.SceneB))
        assertThat(
                picker.contentDuringTransition(
                    TestElements.Foo,
                    transition(from = TestScenes.SceneA, to = TestScenes.SceneB),
                    fromContentZIndex = 0f,
                    toContentZIndex = 1f,
                )
            )
            .isEqualTo(TestScenes.SceneB)
    }

    @Test
    fun fromSceneInScenes() {
        val picker = MovableElementContentPicker(contents = setOf(TestScenes.SceneA))
        assertThat(
                picker.contentDuringTransition(
                    TestElements.Foo,
                    transition(from = TestScenes.SceneA, to = TestScenes.SceneB),
                    fromContentZIndex = 0f,
                    toContentZIndex = 1f,
                )
            )
            .isEqualTo(TestScenes.SceneA)
    }

    @Test
    fun noneInScenes() {
        val picker = MovableElementContentPicker(contents = emptySet())
        assertThrows(IllegalStateException::class.java) {
            picker.contentDuringTransition(
                TestElements.Foo,
                transition(from = TestScenes.SceneA, to = TestScenes.SceneB),
                fromContentZIndex = 0f,
                toContentZIndex = 1f,
            )
        }
    }
}
