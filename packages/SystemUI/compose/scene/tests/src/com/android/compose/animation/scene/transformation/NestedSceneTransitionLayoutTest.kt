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

package com.android.compose.animation.scene.transformation

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.MutableSceneTransitionLayoutStateForTests
import com.android.compose.animation.scene.SceneTransitionLayoutForTesting
import com.android.compose.animation.scene.SceneTransitionLayoutImpl
import com.android.compose.animation.scene.TestScenes
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NestedSceneTransitionLayoutTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun nestedStls_testZIndex() {
        var nullableLayoutImpl: SceneTransitionLayoutImpl? = null

        rule.setContent {
            SceneTransitionLayoutForTesting(
                state = MutableSceneTransitionLayoutStateForTests(TestScenes.SceneA)
            ) {
                scene(TestScenes.SceneA) {
                    NestedSceneTransitionLayoutForTesting(
                        MutableSceneTransitionLayoutStateForTests(TestScenes.SceneD),
                        Modifier,
                        onLayoutImpl = null,
                    ) {
                        scene(TestScenes.SceneC) {}
                        scene(TestScenes.SceneD) {
                            NestedSceneTransitionLayoutForTesting(
                                MutableSceneTransitionLayoutStateForTests(TestScenes.SceneE),
                                Modifier,
                                onLayoutImpl = { nullableLayoutImpl = it },
                            ) {
                                scene(TestScenes.SceneE) {}
                            }
                        }
                    }
                }
                scene(TestScenes.SceneB) {}
            }

            assertThat(nullableLayoutImpl?.content(TestScenes.SceneA)?.globalZIndex)
                .isEqualTo(1_000_000_000_000_000)
            assertThat(nullableLayoutImpl?.content(TestScenes.SceneB)?.globalZIndex)
                .isEqualTo(2_000_000_000_000_000)
            assertThat(nullableLayoutImpl?.content(TestScenes.SceneC)?.globalZIndex)
                .isEqualTo(1_001_000_000_000_000)
            assertThat(nullableLayoutImpl?.content(TestScenes.SceneD)?.globalZIndex)
                .isEqualTo(1_002_000_000_000_000)
            assertThat(nullableLayoutImpl?.content(TestScenes.SceneE)?.globalZIndex)
                .isEqualTo(1_002_001_000_000_000)
        }
    }
}
