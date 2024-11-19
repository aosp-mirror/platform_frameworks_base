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
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
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
            scene(Scenes.NestedSceneB) {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier.offset(50.dp, 10.dp)
                            .element(TestElements.Foo)
                            .size(60.dp, 40.dp)
                            .background(Color.Blue)
                            .alpha(0.8f)
                    )
                }
            }
            scene(Scenes.NestedSceneA) {
                Box(Modifier.fillMaxSize()) {
                    // Foo is at (10, 50) with a size of (20, 80).
                    Box(
                        Modifier.offset(10.dp, 50.dp)
                            .element(TestElements.Foo)
                            .size(20.dp, 80.dp)
                            .background(Color.Red)
                            .alpha(0.8f)
                    )
                }
            }
        }
    }

    private val nestedNestedStlWithSharedElement: @Composable ContentScope.() -> Unit = {
        NestedSceneTransitionLayout(nestedState, modifier = Modifier) {
            scene(Scenes.NestedSceneA) {
                NestedSceneTransitionLayout(state = nestedNestedState, modifier = Modifier) {
                    scene(Scenes.NestedNestedSceneA) {
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.offset(130.dp, 90.dp)
                                    .element(TestElements.Foo)
                                    .size(100.dp, 80.dp)
                                    .background(Color.DarkGray)
                                    .alpha(0.8f)
                            )
                        }
                    }
                    scene(Scenes.NestedNestedSceneB) {
                        Box(Modifier.fillMaxSize()) {
                            Box(
                                Modifier.offset(50.dp, 10.dp)
                                    .element(TestElements.Foo)
                                    .size(60.dp, 40.dp)
                                    .background(Color.Blue)
                                    .alpha(0.8f)
                            )
                        }
                    }
                }
            }
            scene(Scenes.NestedSceneB) {
                Box(Modifier.fillMaxSize()) {
                    // Foo is at (10, 50) with a size of (20, 80).
                    Box(
                        Modifier.offset(10.dp, 50.dp)
                            .element(TestElements.Foo)
                            .size(20.dp, 80.dp)
                            .background(Color.Red)
                            .alpha(0.8f)
                    )
                }
            }
        }
    }

    private val contentWithSharedElement: @Composable ContentScope.() -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Box(
                Modifier.offset(50.dp, 70.dp)
                    .element(TestElements.Foo)
                    .size(10.dp, 40.dp)
                    .background(Color.Magenta)
                    .alpha(0.8f)
            )
        }
    }

    @Test
    fun nestedSharedElementTransition_fromNestedSTLtoParentSTL() {
        rule.testTransition(
            fromSceneContent = nestedStlWithSharedElement,
            toSceneContent = contentWithSharedElement,
        ) {
            before {
                onElement(TestElements.Foo).assertPositionInRootIsEqualTo(10.dp, 50.dp)
                onElement(TestElements.Foo).assertSizeIsEqualTo(20.dp, 80.dp)
            }
            at(0) {
                // Shared elements are by default placed and drawn only in the scene with highest
                // zIndex.
                onElement(TestElements.Foo, TestScenes.SceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneB)
                    .assertPositionInRootIsEqualTo(10.dp, 50.dp)
                    .assertSizeIsEqualTo(20.dp, 80.dp)
            }
            at(16) {
                onElement(TestElements.Foo, TestScenes.SceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneB)
                    .assertPositionInRootIsEqualTo(20.dp, 55.dp)
                    .assertSizeIsEqualTo(17.5.dp, 70.dp)
            }
            at(32) {
                onElement(TestElements.Foo, TestScenes.SceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneB)
                    .assertPositionInRootIsEqualTo(30.dp, 60.dp)
                    .assertSizeIsEqualTo(15.dp, 60.dp)
            }
            at(48) {
                onElement(TestElements.Foo, TestScenes.SceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneB)
                    .assertPositionInRootIsEqualTo(40.dp, 65.dp)
                    .assertSizeIsEqualTo(12.5.dp, 50.dp)
            }
            after {
                onElement(TestElements.Foo).assertPositionInRootIsEqualTo(50.dp, 70.dp)
                onElement(TestElements.Foo).assertSizeIsEqualTo(10.dp, 40.dp)
            }
        }
    }

    @Test
    fun nestedSharedElementTransition_fromParentSTLtoNestedSTL() {
        rule.testTransition(
            fromSceneContent = contentWithSharedElement,
            toSceneContent = nestedStlWithSharedElement,
        ) {
            before {
                onElement(TestElements.Foo).assertPositionInRootIsEqualTo(50.dp, 70.dp)
                onElement(TestElements.Foo).assertSizeIsEqualTo(10.dp, 40.dp)
            }
            at(0) {
                // Shared elements placed in NestedSTLs are by default drawn only in the STL with
                // the lowest nestingDepth and the scene in which the element is placed directly.
                onElement(TestElements.Foo, TestScenes.SceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(50.dp, 70.dp)
                    .assertSizeIsEqualTo(10.dp, 40.dp)
            }
            at(16) {
                onElement(TestElements.Foo, TestScenes.SceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(40.dp, 65.dp)
                    .assertSizeIsEqualTo(12.5.dp, 50.dp)
            }
            at(32) {
                onElement(TestElements.Foo, TestScenes.SceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(30.dp, 60.dp)
                    .assertSizeIsEqualTo(15.dp, 60.dp)
            }
            at(48) {
                onElement(TestElements.Foo, TestScenes.SceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(20.dp, 55.dp)
                    .assertSizeIsEqualTo(17.5.dp, 70.dp)
            }
            after {
                onElement(TestElements.Foo).assertPositionInRootIsEqualTo(10.dp, 50.dp)
                onElement(TestElements.Foo).assertSizeIsEqualTo(20.dp, 80.dp)
            }
        }
    }

    @Test
    fun nestedSharedElementTransition_fromParentSTLtoNestedNestedSTL() {
        rule.testTransition(
            fromSceneContent = contentWithSharedElement,
            toSceneContent = nestedNestedStlWithSharedElement,
        ) {
            before {
                onElement(TestElements.Foo).assertPositionInRootIsEqualTo(50.dp, 70.dp)
                onElement(TestElements.Foo).assertSizeIsEqualTo(10.dp, 40.dp)
            }
            at(0) {
                // Shared elements placed in NestedSTLs are by default drawn only in the STL with
                // the lowest nestingDepth and the scene in which the element is placed directly.
                onElement(TestElements.Foo, TestScenes.SceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(50.dp, 70.dp)
                    .assertSizeIsEqualTo(10.dp, 40.dp)
            }
            at(16) {
                onElement(TestElements.Foo, TestScenes.SceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(70.dp, 75.dp)
                    .assertSizeIsEqualTo(32.5.dp, 50.dp)
            }
            at(32) {
                onElement(TestElements.Foo, TestScenes.SceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(90.dp, 80.dp)
                    .assertSizeIsEqualTo(55.dp, 60.dp)
            }
            at(48) {
                onElement(TestElements.Foo, TestScenes.SceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(110.dp, 85.dp)
                    .assertSizeIsEqualTo(77.5.dp, 70.dp)
            }
            after {
                onElement(TestElements.Foo).assertPositionInRootIsEqualTo(130.dp, 90.dp)
                onElement(TestElements.Foo).assertSizeIsEqualTo(100.dp, 80.dp)
            }
        }
    }

    @Test
    fun nestedSharedElementTransition_fromNestedNestedSTLtoNestedSTL() {
        rule.testTransition(
            fromSceneContent = nestedNestedStlWithSharedElement,
            toSceneContent = { Box(modifier = Modifier.fillMaxSize()) },
            changeState = { nestedState.setTargetScene(Scenes.NestedSceneB, this) },
        ) {
            before {
                onElement(TestElements.Foo)
                    .assertPositionInRootIsEqualTo(130.dp, 90.dp)
                    .assertSizeIsEqualTo(100.dp, 80.dp)
            }
            at(0) {
                // Shared elements placed in NestedSTLs are by default drawn only in the STL with
                // the lowest nestingDepth and the scene in which the element is placed directly.
                onElement(TestElements.Foo, Scenes.NestedSceneA).assertIsNotDisplayed()
                onElement(TestElements.Foo, Scenes.NestedNestedSceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, Scenes.NestedSceneB)
                    .assertPositionInRootIsEqualTo(130.dp, 90.dp)
                    .assertSizeIsEqualTo(100.dp, 80.dp)
            }
            at(16) {
                onElement(TestElements.Foo, Scenes.NestedSceneA).assertIsNotDisplayed()
                onElement(TestElements.Foo, Scenes.NestedNestedSceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, Scenes.NestedSceneB)
                    .assertPositionInRootIsEqualTo(100.dp, 80.dp)
                    .assertSizeIsEqualTo(80.dp, 80.dp)
            }
            at(32) {
                onElement(TestElements.Foo, Scenes.NestedSceneA).assertIsNotDisplayed()
                onElement(TestElements.Foo, Scenes.NestedNestedSceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, Scenes.NestedSceneB)
                    .assertPositionInRootIsEqualTo(70.dp, 70.dp)
                    .assertSizeIsEqualTo(60.dp, 80.dp)
            }
            at(48) {
                onElement(TestElements.Foo, Scenes.NestedSceneA).assertIsNotDisplayed()
                onElement(TestElements.Foo, Scenes.NestedNestedSceneA).assertIsNotDisplayed()

                onElement(TestElements.Foo, Scenes.NestedSceneB)
                    .assertPositionInRootIsEqualTo(40.dp, 60.dp)
                    .assertSizeIsEqualTo(40.dp, 80.dp)
            }
            after {
                onElement(TestElements.Foo).assertPositionInRootIsEqualTo(10.dp, 50.dp)
                onElement(TestElements.Foo).assertSizeIsEqualTo(20.dp, 80.dp)
            }
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
            before { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(50.dp, 70.dp) }
            at(0) {
                onElement(TestElements.Foo, scene = TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(50.dp, 70.dp)
                    .assertSizeIsEqualTo(10.dp, 40.dp)
            }
            at(16) {
                onElement(TestElements.Foo, scene = TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(37.5.dp, 70.dp)
            }
            at(32) {
                onElement(TestElements.Foo, scene = TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(25.dp, 70.dp)
            }
            at(48) {
                onElement(TestElements.Foo, scene = TestScenes.SceneA)
                    .assertPositionInRootIsEqualTo(12.5.dp, 70.dp)
            }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(10.dp, 50.dp) }
        }
    }

    @Test
    fun nestedSharedElementTransition_transitionInsideNestedStl() {
        rule.testTransition(
            layoutModifier = Modifier.fillMaxSize().background(Color.Gray),
            fromSceneContent = nestedStlWithSharedElement,
            toSceneContent = contentWithSharedElement,
            changeState = { nestedState.setTargetScene(Scenes.NestedSceneB, animationScope = this) },
        ) {
            before {
                onElement(TestElements.Foo)
                    .assertPositionInRootIsEqualTo(10.dp, 50.dp)
                    .assertSizeIsEqualTo(20.dp, 80.dp)
            }
            at(0) {
                onElement(TestElements.Foo, Scenes.NestedSceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, scene = Scenes.NestedSceneA)
                    .assertPositionInRootIsEqualTo(10.dp, 50.dp)
                    .assertSizeIsEqualTo(20.dp, 80.dp)
            }
            at(16) {
                onElement(TestElements.Foo, Scenes.NestedSceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, scene = Scenes.NestedSceneA)
                    .assertPositionInRootIsEqualTo(20.dp, 40.dp)
                    .assertSizeIsEqualTo(30.dp, 70.dp)
            }
            at(32) {
                onElement(TestElements.Foo, Scenes.NestedSceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, scene = Scenes.NestedSceneA)
                    .assertPositionInRootIsEqualTo(30.dp, 30.dp)
                    .assertSizeIsEqualTo(40.dp, 60.dp)
            }
            at(48) {
                onElement(TestElements.Foo, Scenes.NestedSceneB).assertIsNotDisplayed()

                onElement(TestElements.Foo, scene = Scenes.NestedSceneA)
                    .assertPositionInRootIsEqualTo(40.dp, 20.dp)
                    .assertSizeIsEqualTo(50.dp, 50.dp)
            }
            after {
                onElement(TestElements.Foo, Scenes.NestedSceneA).assertIsNotDisplayed()
                onElement(TestElements.Foo)
                    .assertPositionInRootIsEqualTo(50.dp, 10.dp)
                    .assertSizeIsEqualTo(60.dp, 40.dp)
            }
        }
    }
}
