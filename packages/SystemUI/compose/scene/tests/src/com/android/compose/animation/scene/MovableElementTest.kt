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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasParent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.test.assertSizeIsEqualTo
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MovableElementTest {
    @get:Rule val rule = createComposeRule()

    /** An element that displays a counter that is incremented whenever this element is clicked. */
    @Composable
    private fun Counter(modifier: Modifier = Modifier) {
        var count by remember { mutableIntStateOf(0) }
        Box(modifier.fillMaxSize().clickable { count++ }) { Text("count: $count") }
    }

    @Composable
    private fun SceneScope.MovableCounter(key: ElementKey, modifier: Modifier) {
        MovableElement(key, modifier) { Counter() }
    }

    @Test
    fun modifierElementIsDuplicatedDuringTransitions() {
        rule.testTransition(
            fromSceneContent = {
                Box(Modifier.element(TestElements.Foo).size(50.dp)) { Counter() }
            },
            toSceneContent = { Box(Modifier.element(TestElements.Foo).size(100.dp)) { Counter() } },
            transition = { spec = tween(durationMillis = 16 * 4, easing = LinearEasing) },
            fromScene = TestScenes.SceneA,
            toScene = TestScenes.SceneB,
        ) {
            before {
                // Click 3 times on the counter.
                rule.onNodeWithText("count: 0").assertIsDisplayed().performClick()
                rule.onNodeWithText("count: 1").assertIsDisplayed().performClick()
                rule.onNodeWithText("count: 2").assertIsDisplayed().performClick()
                rule
                    .onNodeWithText("count: 3")
                    .assertIsDisplayed()
                    .assertSizeIsEqualTo(50.dp, 50.dp)

                // There are no other counters.
                assertThat(
                        rule
                            .onAllNodesWithText("count: ", substring = true)
                            .fetchSemanticsNodes()
                            .size
                    )
                    .isEqualTo(1)
            }

            at(32) {
                // In the middle of the transition, there are 2 copies of the counter: the previous
                // one from scene A (equal to 3) and the new one from scene B (equal to 0).
                rule
                    .onNode(
                        hasText("count: 3") and
                            hasParent(isElement(TestElements.Foo, scene = TestScenes.SceneA))
                    )
                    .assertIsDisplayed()
                    .assertSizeIsEqualTo(75.dp, 75.dp)

                rule
                    .onNode(
                        hasText("count: 0") and
                            hasParent(isElement(TestElements.Foo, scene = TestScenes.SceneB))
                    )
                    .assertIsDisplayed()
                    .assertSizeIsEqualTo(75.dp, 75.dp)

                // There are exactly 2 counters.
                assertThat(
                        rule
                            .onAllNodesWithText("count: ", substring = true)
                            .fetchSemanticsNodes()
                            .size
                    )
                    .isEqualTo(2)
            }

            after {
                // At the end of the transition, only the counter from scene B is composed.
                rule
                    .onNodeWithText("count: 0")
                    .assertIsDisplayed()
                    .assertSizeIsEqualTo(100.dp, 100.dp)

                // There are no other counters.
                assertThat(
                        rule
                            .onAllNodesWithText("count: ", substring = true)
                            .fetchSemanticsNodes()
                            .size
                    )
                    .isEqualTo(1)
            }
        }
    }

    @Test
    fun movableElementIsMovedAndComposedOnlyOnce() {
        rule.testTransition(
            fromSceneContent = { MovableCounter(TestElements.Foo, Modifier.size(50.dp)) },
            toSceneContent = { MovableCounter(TestElements.Foo, Modifier.size(100.dp)) },
            transition = {
                spec = tween(durationMillis = 16 * 4, easing = LinearEasing)
                sharedElement(
                    TestElements.Foo,
                    scenePicker =
                        object : SharedElementScenePicker {
                            override fun sceneDuringTransition(
                                element: ElementKey,
                                fromScene: SceneKey,
                                toScene: SceneKey,
                                progress: () -> Float,
                                fromSceneZIndex: Float,
                                toSceneZIndex: Float
                            ): SceneKey {
                                assertThat(fromScene).isEqualTo(TestScenes.SceneA)
                                assertThat(toScene).isEqualTo(TestScenes.SceneB)
                                assertThat(fromSceneZIndex).isEqualTo(0)
                                assertThat(toSceneZIndex).isEqualTo(1)

                                // Compose Foo in Scene A if progress < 0.65f, otherwise compose it
                                // in Scene B.
                                return if (progress() < 0.65f) {
                                    TestScenes.SceneA
                                } else {
                                    TestScenes.SceneB
                                }
                            }
                        }
                )
            },
            fromScene = TestScenes.SceneA,
            toScene = TestScenes.SceneB,
        ) {
            before {
                // Click 3 times on the counter.
                rule.onNodeWithText("count: 0").assertIsDisplayed().performClick()
                rule.onNodeWithText("count: 1").assertIsDisplayed().performClick()
                rule.onNodeWithText("count: 2").assertIsDisplayed().performClick()
                rule
                    .onNodeWithText("count: 3")
                    .assertIsDisplayed()
                    .assertSizeIsEqualTo(50.dp, 50.dp)

                // There are no other counters.
                assertThat(
                        rule
                            .onAllNodesWithText("count: ", substring = true)
                            .fetchSemanticsNodes()
                            .size
                    )
                    .isEqualTo(1)
            }

            at(32) {
                // During the transition, there is a single counter that is moved, with the current
                // value. Given that progress = 0.5f, it is currently composed in SceneA.
                rule
                    .onNode(
                        hasText("count: 3") and
                            hasParent(isElement(TestElements.Foo, scene = TestScenes.SceneA))
                    )
                    .assertIsDisplayed()
                    .assertSizeIsEqualTo(75.dp, 75.dp)

                // There are no other counters.
                assertThat(
                        rule
                            .onAllNodesWithText("count: ", substring = true)
                            .fetchSemanticsNodes()
                            .size
                    )
                    .isEqualTo(1)
            }

            at(48) {
                // During the transition, there is a single counter that is moved, with the current
                // value. Given that progress = 0.75f, it is currently composed in SceneB.
                rule
                    .onNode(
                        hasText("count: 3") and
                            hasParent(isElement(TestElements.Foo, scene = TestScenes.SceneB))
                    )
                    .assertIsDisplayed()

                // There are no other counters.
                assertThat(
                        rule
                            .onAllNodesWithText("count: ", substring = true)
                            .fetchSemanticsNodes()
                            .size
                    )
                    .isEqualTo(1)
            }

            after {
                // At the end of the transition, the counter still has the current value.
                rule
                    .onNodeWithText("count: 3")
                    .assertIsDisplayed()
                    .assertSizeIsEqualTo(100.dp, 100.dp)

                // There are no other counters.
                assertThat(
                        rule
                            .onAllNodesWithText("count: ", substring = true)
                            .fetchSemanticsNodes()
                            .size
                    )
                    .isEqualTo(1)
            }
        }
    }
}
