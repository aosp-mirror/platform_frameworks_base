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

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.isNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.ElementKey
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.MutableSceneTransitionLayoutStateForTests
import com.android.compose.animation.scene.Scale
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.SceneTransitionLayout
import com.android.compose.animation.scene.SceneTransitions
import com.android.compose.animation.scene.TestScenes
import com.android.compose.animation.scene.testNestedTransition
import com.android.compose.animation.scene.testing.lastAlphaForTesting
import com.android.compose.animation.scene.testing.lastScaleForTesting
import com.android.compose.animation.scene.transitions
import com.android.compose.test.assertSizeIsEqualTo
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NestedElementTransformationTest {
    @get:Rule val rule = createComposeRule()

    private object Scenes {
        val NestedSceneA = SceneKey("NestedSceneA")
        val NestedSceneB = SceneKey("NestedSceneB")
        val NestedNestedSceneA = SceneKey("NestedNestedSceneA")
        val NestedNestedSceneB = SceneKey("NestedNestedSceneB")
    }

    // Variants are named: nestingDepth + sceneNameSuffix
    private val elementVariant0A =
        TestElement(ElementKey("0A"), 0.dp, 0.dp, 100.dp, 100.dp, Color.Red)
    private val elementVariant0B =
        TestElement(ElementKey("0B"), 100.dp, 100.dp, 20.dp, 20.dp, Color.Cyan)
    private val elementVariant1A =
        TestElement(ElementKey("1A"), 40.dp, 80.dp, 60.dp, 20.dp, Color.Blue)
    private val elementVariant1B =
        TestElement(ElementKey("1B"), 80.dp, 40.dp, 140.dp, 180.dp, Color.Yellow)
    private val elementVariant2A =
        TestElement(ElementKey("2A"), 120.dp, 240.dp, 20.dp, 140.dp, Color.Green)
    private val elementVariant2B =
        TestElement(ElementKey("2B"), 200.dp, 320.dp, 40.dp, 70.dp, Color.Magenta)

    private class TestElement(
        val key: ElementKey,
        val x: Dp,
        val y: Dp,
        val width: Dp,
        val height: Dp,
        val color: Color = Color.Black,
        val alpha: Float = 0.8f,
    )

    @Composable
    private fun ContentScope.TestElement(element: TestElement) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.offset(element.x, element.y)
                    .element(element.key)
                    .size(element.width, element.height)
                    .background(element.color)
                    .alpha(element.alpha)
            )
        }
    }

    private fun createState(
        startScene: SceneKey,
        transitions: SceneTransitions = SceneTransitions.Empty,
    ): MutableSceneTransitionLayoutState {
        return rule.runOnUiThread {
            MutableSceneTransitionLayoutStateForTests(startScene, transitions)
        }
    }

    private val threeNestedStls:
        @Composable
        (states: List<MutableSceneTransitionLayoutState>) -> Unit =
        { states ->
            SceneTransitionLayout(states[0]) {
                scene(TestScenes.SceneA, content = { TestElement(elementVariant0A) })
                scene(
                    TestScenes.SceneB,
                    content = {
                        Box(Modifier.fillMaxSize()) {
                            TestElement(elementVariant0B)
                            NestedSceneTransitionLayout(states[1], modifier = Modifier) {
                                scene(Scenes.NestedSceneA) {
                                    Box(Modifier.fillMaxSize()) {
                                        TestElement(elementVariant1A)
                                        NestedSceneTransitionLayout(
                                            state = states[2],
                                            modifier = Modifier,
                                        ) {
                                            scene(Scenes.NestedNestedSceneA) {
                                                TestElement(elementVariant2A)
                                            }
                                            scene(Scenes.NestedNestedSceneB) {
                                                TestElement(elementVariant2B)
                                            }
                                        }
                                    }
                                }
                                scene(Scenes.NestedSceneB) { TestElement(elementVariant1B) }
                            }
                        }
                    },
                )
            }
        }

    @Test
    fun transitionInNestedNestedStl_transitionsOut() {
        rule.testNestedTransition(
            states =
                listOf(
                    createState(TestScenes.SceneB),
                    createState(Scenes.NestedSceneA),
                    createState(
                        Scenes.NestedNestedSceneA,
                        transitions {
                            from(from = Scenes.NestedNestedSceneA, to = Scenes.NestedNestedSceneB) {
                                spec = tween(16 * 4, easing = LinearEasing)
                                translate(elementVariant2A.key, x = 100.dp, y = 50.dp)
                                scaleSize(elementVariant2A.key, width = 2f, height = 0.5f)
                                scaleDraw(elementVariant2A.key, scaleX = 4f, scaleY = 0.25f)
                                fade(elementVariant2A.key)
                            }
                        },
                    ),
                ),
            transitionLayout = threeNestedStls,
            changeState = { it[2].setTargetScene(Scenes.NestedNestedSceneB, this) },
        ) {
            before { onElement(elementVariant2A.key).assertElementVariant(elementVariant2A) }
            atAllFrames(4) {
                onElement(elementVariant2A.key)
                    .assertPositionInRootIsEqualTo(
                        interpolate(elementVariant2A.x, elementVariant2A.x + 100.dp),
                        interpolate(elementVariant2A.y, elementVariant2A.y + 50.dp),
                    )
                    .assertSizeIsEqualTo(
                        interpolate(elementVariant2A.width, elementVariant2A.width * 2f),
                        interpolate(elementVariant2A.height, elementVariant2A.height * 0.5f),
                    )
                val semanticNode = onElement(elementVariant2A.key).fetchSemanticsNode()
                assertThat(semanticNode.lastAlphaForTesting).isEqualTo(interpolate(1f, 0f))
                assertThat(semanticNode.lastScaleForTesting)
                    .isEqualTo(interpolate(Scale(1f, 1f), Scale(4f, 0.25f)))
            }
            after { onElement(elementVariant2A.key).isNotDisplayed() }
        }
    }

    @Test
    fun transitionInNestedNestedStl_transitionsIn() {
        rule.testNestedTransition(
            states =
                listOf(
                    createState(TestScenes.SceneB),
                    createState(Scenes.NestedSceneA),
                    createState(
                        Scenes.NestedNestedSceneB,
                        transitions {
                            from(from = Scenes.NestedNestedSceneB) {
                                spec = tween(16 * 4, easing = LinearEasing)
                                translate(elementVariant2A.key, x = 100.dp, y = 50.dp)
                                scaleSize(elementVariant2A.key, width = 2f, height = 0.5f)
                            }
                        },
                    ),
                ),
            transitionLayout = threeNestedStls,
            changeState = { it[2].setTargetScene(Scenes.NestedNestedSceneA, this) },
        ) {
            before { onElement(elementVariant2A.key).isNotDisplayed() }
            atAllFrames(4) {
                onElement(elementVariant2A.key)
                    .assertPositionInRootIsEqualTo(
                        interpolate(elementVariant2A.x + 100.dp, elementVariant2A.x),
                        interpolate(elementVariant2A.y + 50.dp, elementVariant2A.y),
                    )
                    .assertSizeIsEqualTo(
                        interpolate(elementVariant2A.width * 2f, elementVariant2A.width),
                        interpolate(elementVariant2A.height * 0.5f, elementVariant2A.height),
                    )
            }
            after { onElement(elementVariant2A.key).assertElementVariant(elementVariant2A) }
        }
    }

    @Test
    fun transitionInNestedStl_elementInNestedNestedStl_transitionsIn() {
        rule.testNestedTransition(
            states =
                listOf(
                    createState(TestScenes.SceneB),
                    createState(
                        Scenes.NestedSceneB,
                        transitions {
                            from(from = Scenes.NestedSceneB, to = Scenes.NestedSceneA) {
                                spec = tween(16 * 4, easing = LinearEasing)
                                translate(elementVariant2A.key, x = 100.dp, y = 50.dp)
                                scaleSize(elementVariant2A.key, width = 2f, height = 0.5f)
                            }
                        },
                    ),
                    createState(Scenes.NestedNestedSceneA),
                ),
            transitionLayout = threeNestedStls,
            changeState = { it[1].setTargetScene(Scenes.NestedSceneA, this) },
        ) {
            before { onElement(elementVariant2A.key).isNotDisplayed() }
            atAllFrames(4) {
                onElement(elementVariant2A.key)
                    .assertPositionInRootIsEqualTo(
                        interpolate(elementVariant2A.x + 100.dp, elementVariant2A.x),
                        interpolate(elementVariant2A.y + 50.dp, elementVariant2A.y),
                    )
                    .assertSizeIsEqualTo(
                        interpolate(elementVariant2A.width * 2f, elementVariant2A.width),
                        interpolate(elementVariant2A.height * 0.5f, elementVariant2A.height),
                    )
            }
            after { onElement(elementVariant2A.key).assertElementVariant(elementVariant2A) }
        }
    }

    @Test
    fun transitionInRootStl_elementsInAllLayers_transitionInAndOut() {
        rule.testNestedTransition(
            states =
                listOf(
                    createState(
                        TestScenes.SceneB,
                        transitions {
                            to(to = TestScenes.SceneA) {
                                spec = tween(16 * 4, easing = LinearEasing)

                                // transitions out
                                translate(elementVariant2A.key, x = 100.dp, y = 50.dp)
                                scaleSize(elementVariant2A.key, width = 2f, height = 0.5f)

                                // transitions out
                                translate(elementVariant0B.key, x = 200.dp, y = 20.dp)
                                scaleSize(elementVariant0B.key, width = 3f, height = 0.2f)

                                // transitions out
                                translate(elementVariant1A.key, x = 300.dp, y = 10.dp)
                                scaleSize(elementVariant1A.key, width = 4f, height = 0.1f)

                                // transitions in
                                translate(elementVariant0A.key, x = 400.dp, y = 40.dp)
                                scaleSize(elementVariant0A.key, width = 0.5f, height = 2f)
                            }
                        },
                    ),
                    createState(Scenes.NestedSceneA),
                    createState(Scenes.NestedNestedSceneA),
                ),
            transitionLayout = threeNestedStls,
            changeState = { it[0].setTargetScene(TestScenes.SceneA, this) },
        ) {
            before {
                onElement(elementVariant2A.key).assertElementVariant(elementVariant2A)
                onElement(elementVariant0B.key).assertElementVariant(elementVariant0B)
                onElement(elementVariant1A.key).assertElementVariant(elementVariant1A)
                onElement(elementVariant0A.key).isNotDisplayed()
            }
            atAllFrames(4) {
                onElement(elementVariant2A.key)
                    .assertPositionInRootIsEqualTo(
                        interpolate(elementVariant2A.x, elementVariant2A.x + 100.dp),
                        interpolate(elementVariant2A.y, elementVariant2A.y + 50.dp),
                    )
                    .assertSizeIsEqualTo(
                        interpolate(elementVariant2A.width, elementVariant2A.width * 2f),
                        interpolate(elementVariant2A.height, elementVariant2A.height * 0.5f),
                    )

                onElement(elementVariant0B.key)
                    .assertPositionInRootIsEqualTo(
                        interpolate(elementVariant0B.x, elementVariant0B.x + 200.dp),
                        interpolate(elementVariant0B.y, elementVariant0B.y + 20.dp),
                    )
                    .assertSizeIsEqualTo(
                        interpolate(elementVariant0B.width, elementVariant0B.width * 3f),
                        interpolate(elementVariant0B.height, elementVariant0B.height * 0.2f),
                    )

                onElement(elementVariant1A.key)
                    .assertPositionInRootIsEqualTo(
                        interpolate(elementVariant1A.x, elementVariant1A.x + 300.dp),
                        interpolate(elementVariant1A.y, elementVariant1A.y + 10.dp),
                    )
                    .assertSizeIsEqualTo(
                        interpolate(elementVariant1A.width, elementVariant1A.width * 4f),
                        interpolate(elementVariant1A.height, elementVariant1A.height * 0.1f),
                    )

                onElement(elementVariant0A.key)
                    .assertPositionInRootIsEqualTo(
                        interpolate(elementVariant0A.x + 400.dp, elementVariant0A.x),
                        interpolate(elementVariant0A.y + 40.dp, elementVariant0A.y),
                    )
                    .assertSizeIsEqualTo(
                        interpolate(elementVariant0A.width * 0.5f, elementVariant0A.width),
                        interpolate(elementVariant0A.height * 2f, elementVariant0A.height),
                    )
            }
            after {
                onElement(elementVariant2A.key).isNotDisplayed()
                onElement(elementVariant0B.key).isNotDisplayed()
                onElement(elementVariant1A.key).isNotDisplayed()
                onElement(elementVariant0A.key).assertElementVariant(elementVariant0A)
            }
        }
    }

    @Test
    fun transitionInMultipleStls_rootIsTakingControl() {
        rule.testNestedTransition(
            states =
                listOf(
                    createState(
                        TestScenes.SceneB,
                        transitions {
                            to(to = TestScenes.SceneA) {
                                spec = tween(16 * 4, easing = LinearEasing)
                                translate(elementVariant2A.key, x = 100.dp, y = 50.dp)
                            }
                        },
                    ),
                    createState(
                        Scenes.NestedSceneA,
                        transitions {
                            to(to = Scenes.NestedSceneB) {
                                spec = tween(16 * 4, easing = LinearEasing)
                                translate(elementVariant2A.key, x = 200.dp, y = 150.dp)
                            }
                        },
                    ),
                    createState(
                        Scenes.NestedNestedSceneA,
                        transitions {
                            to(to = Scenes.NestedNestedSceneB) {
                                spec = tween(16 * 4, easing = LinearEasing)
                                translate(elementVariant2A.key, x = 300.dp, y = 250.dp)
                            }
                        },
                    ),
                ),
            transitionLayout = threeNestedStls,
            changeState = {
                it[2].setTargetScene(Scenes.NestedNestedSceneB, this)
                it[1].setTargetScene(Scenes.NestedSceneB, this)
                it[0].setTargetScene(TestScenes.SceneA, this)
            },
        ) {
            before { onElement(elementVariant2A.key).assertElementVariant(elementVariant2A) }
            atAllFrames(4) {
                onElement(elementVariant2A.key)
                    .assertPositionInRootIsEqualTo(
                        interpolate(elementVariant2A.x, elementVariant2A.x + 100.dp),
                        interpolate(elementVariant2A.y, elementVariant2A.y + 50.dp),
                    )
            }
            after { onElement(elementVariant2A.key).isNotDisplayed() }
        }
    }

    private fun SemanticsNodeInteraction.assertElementVariant(variant: TestElement) {
        assertPositionInRootIsEqualTo(variant.x, variant.y)
        assertSizeIsEqualTo(variant.width, variant.height)
    }
}
