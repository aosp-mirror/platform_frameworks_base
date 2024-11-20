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
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.AutoTransitionTestAssertionScope
import com.android.compose.animation.scene.ContentScope
import com.android.compose.animation.scene.Default4FrameLinearTransition
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.MutableSceneTransitionLayoutState
import com.android.compose.animation.scene.SceneKey
import com.android.compose.animation.scene.TestElements
import com.android.compose.animation.scene.TestScenes
import com.android.compose.animation.scene.inScene
import com.android.compose.animation.scene.testTransition
import com.android.compose.animation.scene.transitions
import com.android.compose.test.assertSizeIsEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NestedSharedElementTest {
    @get:Rule val rule = createComposeRule()

    private object Scenes {
        val NestedSceneA = SceneKey("NestedSceneA")
        val NestedSceneB = SceneKey("NestedSceneB")
        val NestedNestedSceneA = SceneKey("NestedNestedSceneA")
        val NestedNestedSceneB = SceneKey("NestedNestedSceneB")
    }

    private val elementVariant1 = SharedElement(0.dp, 0.dp, 100.dp, 100.dp, Color.Red)
    private val elementVariant2 = SharedElement(40.dp, 80.dp, 60.dp, 20.dp, Color.Blue)
    private val elementVariant3 = SharedElement(80.dp, 40.dp, 140.dp, 180.dp, Color.Yellow)
    private val elementVariant4 = SharedElement(120.dp, 240.dp, 20.dp, 140.dp, Color.Green)

    private class SharedElement(
        val x: Dp,
        val y: Dp,
        val width: Dp,
        val height: Dp,
        val color: Color = Color.Black,
        val alpha: Float = 0.8f,
    )

    @Composable
    private fun ContentScope.SharedElement(element: SharedElement) {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.offset(element.x, element.y)
                    .element(TestElements.Foo)
                    .size(element.width, element.height)
                    .background(element.color)
                    .alpha(element.alpha)
            )
        }
    }

    private val contentWithSharedElement: @Composable ContentScope.() -> Unit = {
        SharedElement(elementVariant1)
    }

    private val nestedState: MutableSceneTransitionLayoutState =
        rule.runOnUiThread {
            MutableSceneTransitionLayoutState(
                Scenes.NestedSceneA,
                transitions {
                    from(
                        from = Scenes.NestedSceneA,
                        to = Scenes.NestedSceneB,
                        builder = Default4FrameLinearTransition,
                    )
                },
            )
        }

    private val nestedNestedState: MutableSceneTransitionLayoutState =
        rule.runOnUiThread {
            MutableSceneTransitionLayoutState(
                Scenes.NestedNestedSceneA,
                transitions {
                    from(
                        from = Scenes.NestedNestedSceneA,
                        to = Scenes.NestedNestedSceneB,
                        builder = Default4FrameLinearTransition,
                    )
                },
            )
        }

    private val nestedStlWithSharedElement: @Composable ContentScope.() -> Unit = {
        NestedSceneTransitionLayout(nestedState, modifier = Modifier) {
            scene(Scenes.NestedSceneA) { SharedElement(elementVariant2) }
            scene(Scenes.NestedSceneB) { SharedElement(elementVariant3) }
        }
    }

    private val nestedNestedStlWithSharedElement: @Composable ContentScope.() -> Unit = {
        NestedSceneTransitionLayout(nestedState, modifier = Modifier) {
            scene(Scenes.NestedSceneA) {
                NestedSceneTransitionLayout(state = nestedNestedState, modifier = Modifier) {
                    scene(Scenes.NestedNestedSceneA) { SharedElement(elementVariant4) }
                    scene(Scenes.NestedNestedSceneB) { SharedElement(elementVariant3) }
                }
            }
            scene(Scenes.NestedSceneB) { SharedElement(elementVariant2) }
        }
    }

    @Test
    fun nestedSharedElementTransition_fromNestedSTLtoParentSTL() {
        rule.testTransition(
            fromSceneContent = nestedStlWithSharedElement,
            toSceneContent = contentWithSharedElement,
        ) {
            before { onElement(TestElements.Foo).assertElementVariant(elementVariant2) }
            atAllFrames(4) {
                onElement(TestElements.Foo, TestScenes.SceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneB)
                    .assertBetweenElementVariants(elementVariant2, elementVariant1, this)
            }
            after { onElement(TestElements.Foo).assertElementVariant(elementVariant1) }
        }
    }

    @Test
    fun nestedSharedElementTransition_fromParentSTLtoNestedSTL() {
        rule.testTransition(
            fromSceneContent = contentWithSharedElement,
            toSceneContent = nestedStlWithSharedElement,
        ) {
            before { onElement(TestElements.Foo).assertElementVariant(elementVariant1) }
            atAllFrames(4) {
                onElement(TestElements.Foo, TestScenes.SceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneA)
                    .assertBetweenElementVariants(elementVariant1, elementVariant2, this)
            }
            after { onElement(TestElements.Foo).assertElementVariant(elementVariant2) }
        }
    }

    @Test
    fun nestedSharedElementTransition_fromParentSTLtoNestedNestedSTL() {
        rule.testTransition(
            fromSceneContent = contentWithSharedElement,
            toSceneContent = nestedNestedStlWithSharedElement,
        ) {
            before { onElement(TestElements.Foo).assertElementVariant(elementVariant1) }
            atAllFrames(4) {
                onElement(TestElements.Foo, TestScenes.SceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneA)
                    .assertBetweenElementVariants(elementVariant1, elementVariant4, this)
            }
            after { onElement(TestElements.Foo).assertElementVariant(elementVariant4) }
        }
    }

    @Test
    fun nestedSharedElementTransition_fromNestedNestedSTLtoNestedSTL() {
        rule.testTransition(
            fromSceneContent = nestedNestedStlWithSharedElement,
            toSceneContent = { Box(modifier = Modifier.fillMaxSize()) },
            changeState = { nestedState.setTargetScene(Scenes.NestedSceneB, this) },
        ) {
            before { onElement(TestElements.Foo).assertElementVariant(elementVariant4) }
            atAllFrames(4) {
                onElement(TestElements.Foo, Scenes.NestedSceneA).assertIsNotDisplayed()
                onElement(TestElements.Foo, Scenes.NestedNestedSceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, Scenes.NestedSceneB)
                    .assertBetweenElementVariants(elementVariant4, elementVariant2, this)
            }
            after { onElement(TestElements.Foo).assertElementVariant(elementVariant2) }
        }
    }

    @Test
    fun nestedSharedElement_sharedElementTransitionIsDisabled() {
        rule.testTransition(
            fromSceneContent = contentWithSharedElement,
            toSceneContent = nestedStlWithSharedElement,
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)

                // Disable the shared element animation.
                sharedElement(TestElements.Foo, enabled = false)

                // In SceneA, Foo leaves to the left edge.
                translate(TestElements.Foo.inScene(TestScenes.SceneA), Edge.Left, false)

                // We can't reference the element inside the NestedSTL as of today
            },
        ) {
            before { onElement(TestElements.Foo).assertElementVariant(elementVariant1) }
            atAllFrames(4) {
                onElement(TestElements.Foo, scene = TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(
                        interpolate(elementVariant1.x, 0.dp),
                        elementVariant1.y,
                    )
                    .assertSizeIsEqualTo(elementVariant1.width, elementVariant1.height)
            }
            after { onElement(TestElements.Foo).assertElementVariant(elementVariant2) }
        }
    }

    @Test
    fun nestedSharedElementTransition_transitionInsideNestedStl() {
        rule.testTransition(
            layoutModifier = Modifier.fillMaxSize(),
            fromSceneContent = nestedStlWithSharedElement,
            toSceneContent = contentWithSharedElement,
            changeState = { nestedState.setTargetScene(Scenes.NestedSceneB, animationScope = this) },
        ) {
            before { onElement(TestElements.Foo).assertElementVariant(elementVariant2) }
            atAllFrames(4) {
                onElement(TestElements.Foo, Scenes.NestedSceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, scene = Scenes.NestedSceneB)
                    .assertBetweenElementVariants(elementVariant2, elementVariant3, this)
            }
            after {
                onElement(TestElements.Foo, Scenes.NestedSceneA).assertIsNotDisplayed()
                onElement(TestElements.Foo).assertElementVariant(elementVariant3)
            }
        }
    }

    private fun SemanticsNodeInteraction.assertElementVariant(variant: SharedElement) {
        assertPositionInRootIsEqualTo(variant.x, variant.y)
        assertSizeIsEqualTo(variant.width, variant.height)
    }

    private fun SemanticsNodeInteraction.assertBetweenElementVariants(
        from: SharedElement,
        to: SharedElement,
        assertScope: AutoTransitionTestAssertionScope,
    ) {
        assertPositionInRootIsEqualTo(
            assertScope.interpolate(from.x, to.x),
            assertScope.interpolate(from.y, to.y),
        )
        assertSizeIsEqualTo(
            assertScope.interpolate(from.width, to.width),
            assertScope.interpolate(from.height, to.height),
        )
    }
}
