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

import androidx.activity.ComponentActivity
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onChild
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.animation.scene.subjects.assertThat
import com.android.compose.test.assertSizeIsEqualTo
import com.android.compose.test.subjects.DpOffsetSubject
import com.android.compose.test.subjects.assertThat
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SceneTransitionLayoutTest {
    companion object {
        private val LayoutSize = 300.dp
    }

    private var currentScene by mutableStateOf(SceneA)
    private lateinit var layoutState: SceneTransitionLayoutState

    // We use createAndroidComposeRule() here and not createComposeRule() because we need an
    // activity for testBack().
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    /** The content under test. */
    @Composable
    private fun TestContent(enableInterruptions: Boolean = true) {
        layoutState =
            updateSceneTransitionLayoutState(
                currentScene,
                { currentScene = it },
                EmptyTestTransitions,
                enableInterruptions = enableInterruptions,
            )

        SceneTransitionLayout(
            state = layoutState,
            modifier = Modifier.size(LayoutSize),
        ) {
            scene(
                SceneA,
                userActions = mapOf(Back to SceneB),
            ) {
                Box(Modifier.fillMaxSize()) {
                    SharedFoo(size = 50.dp, childOffset = 0.dp, Modifier.align(Alignment.TopEnd))
                    Text("SceneA")
                }
            }
            scene(SceneB) {
                Box(Modifier.fillMaxSize()) {
                    SharedFoo(
                        size = 100.dp,
                        childOffset = 50.dp,
                        Modifier.align(Alignment.TopStart),
                    )
                    Text("SceneB")
                }
            }
            scene(SceneC) {
                Box(Modifier.fillMaxSize()) {
                    SharedFoo(
                        size = 150.dp,
                        childOffset = 100.dp,
                        Modifier.align(Alignment.BottomStart),
                    )
                    Text("SceneC")
                }
            }
        }
    }

    @Composable
    private fun SceneScope.SharedFoo(size: Dp, childOffset: Dp, modifier: Modifier = Modifier) {
        Element(TestElements.Foo, modifier.size(size).background(Color.Red)) {
            // Offset the single child of Foo by some animated shared offset.
            val offset by animateElementDpAsState(childOffset, TestValues.Value1)

            content {
                Box(
                    Modifier.offset {
                            val pxOffset = offset.roundToPx()
                            IntOffset(pxOffset, pxOffset)
                        }
                        .size(30.dp)
                        .background(Color.Blue)
                        .testTag(TestElements.Bar.debugName)
                )
            }
        }
    }

    @Test
    fun testOnlyCurrentSceneIsDisplayed() {
        rule.setContent { TestContent() }

        // Only scene A is displayed.
        rule.onNodeWithText("SceneA").assertIsDisplayed()
        rule.onNodeWithText("SceneB").assertDoesNotExist()
        rule.onNodeWithText("SceneC").assertDoesNotExist()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // Change to scene B. Only that scene is displayed.
        currentScene = SceneB
        rule.onNodeWithText("SceneA").assertDoesNotExist()
        rule.onNodeWithText("SceneB").assertIsDisplayed()
        rule.onNodeWithText("SceneC").assertDoesNotExist()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneB)
    }

    @Test
    fun testBack() {
        rule.setContent { TestContent() }

        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        rule.activity.onBackPressed()
        rule.waitForIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneB)
    }

    @Test
    fun testTransitionState() {
        rule.setContent { TestContent() }
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // We will advance the clock manually.
        rule.mainClock.autoAdvance = false

        // Change the current scene. Until composition is triggered, this won't change the layout
        // state.
        currentScene = SceneB
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // On the next frame, we will recompose because currentScene changed, which will start the
        // transition (i.e. it will change the transitionState to be a Transition) in a
        // LaunchedEffect.
        rule.mainClock.advanceTimeByFrame()
        val transition = assertThat(layoutState.transitionState).isTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(0f)

        // Then, on the next frame, the animator we started gets its initial value and clock
        // starting time. We are now at progress = 0f.
        rule.mainClock.advanceTimeByFrame()
        assertThat(transition).hasProgress(0f)

        // The test transition lasts 480ms. 240ms after the start of the transition, we are at
        // progress = 0.5f.
        rule.mainClock.advanceTimeBy(TestTransitionDuration / 2)
        assertThat(transition).hasProgress(0.5f)

        // (240-16) ms later, i.e. one frame before the transition is finished, we are at
        // progress=(480-16)/480.
        rule.mainClock.advanceTimeBy(TestTransitionDuration / 2 - 16)
        assertThat(transition).hasProgress((TestTransitionDuration - 16) / 480f)

        // one frame (16ms) later, the transition is finished and we are in the idle state in scene
        // B.
        rule.mainClock.advanceTimeByFrame()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneB)
    }

    @Test
    fun testSharedElement() {
        rule.setContent { TestContent(enableInterruptions = false) }

        // In scene A, the shared element SharedFoo() is at the top end of the layout and has a size
        // of 50.dp.
        var sharedFoo = rule.onNodeWithTag(TestElements.Foo.testTag, useUnmergedTree = true)
        sharedFoo.assertWidthIsEqualTo(50.dp)
        sharedFoo.assertHeightIsEqualTo(50.dp)
        sharedFoo.assertPositionInRootIsEqualTo(
            expectedTop = 0.dp,
            expectedLeft = LayoutSize - 50.dp,
        )

        // The shared offset of the single child of SharedFoo() is 0dp in scene A.
        assertThat(sharedFoo.onChild().offsetRelativeTo(sharedFoo)).isEqualTo(DpOffset(0.dp, 0.dp))

        // Pause animations to test the state mid-transition.
        rule.mainClock.autoAdvance = false

        // Go to scene B and let the animation start. See [testLayoutState()] and
        // [androidx.compose.ui.test.MainTestClock] to understand why we need to advance the clock
        // by 2 frames to be at the start of the animation.
        currentScene = SceneB
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()

        // Advance to the middle of the animation.
        rule.mainClock.advanceTimeBy(TestTransitionDuration / 2)

        // Foo is shared between Scene A and Scene B, and is therefore placed/drawn in Scene B given
        // that B has a higher zIndex than A.
        sharedFoo = rule.onNode(isElement(TestElements.Foo, SceneB))

        // In scene B, foo is at the top start (x = 0, y = 0) of the layout and has a size of
        // 100.dp. We pause at the middle of the transition, so it should now be 75.dp given that we
        // use a linear interpolator. Foo was at (x = layoutSize - 50dp, y = 0) in SceneA and is
        // going to (x = 0, y = 0), so the offset should now be half what it was.
        var transition = assertThat(layoutState.transitionState).isTransition()
        assertThat(transition).hasProgress(0.5f)
        sharedFoo.assertWidthIsEqualTo(75.dp)
        sharedFoo.assertHeightIsEqualTo(75.dp)
        sharedFoo.assertPositionInRootIsEqualTo(
            expectedTop = 0.dp,
            expectedLeft = (LayoutSize - 50.dp) / 2
        )

        // The shared offset of the single child of SharedFoo() is 50dp in scene B and 0dp in Scene
        // A, so it should be 25dp now.
        assertThat(sharedFoo.onChild().offsetRelativeTo(sharedFoo))
            .isWithin(DpOffsetSubject.DefaultTolerance)
            .of(DpOffset(25.dp, 25.dp))

        // Animate to scene C, let the animation start then go to the middle of the transition.
        currentScene = SceneC
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(TestTransitionDuration / 2)

        // In Scene C, foo is at the bottom start of the layout and has a size of 150.dp. The
        // transition scene B => scene C is using a FastOutSlowIn interpolator.
        val interpolatedProgress = FastOutSlowInEasing.transform(0.5f)
        val expectedTop = (LayoutSize - 150.dp) * interpolatedProgress
        val expectedLeft = 0.dp
        val expectedSize = 100.dp + (150.dp - 100.dp) * interpolatedProgress

        sharedFoo = rule.onNode(isElement(TestElements.Foo, SceneC))
        transition = assertThat(layoutState.transitionState).isTransition()
        assertThat(transition).hasProgress(interpolatedProgress)
        sharedFoo.assertWidthIsEqualTo(expectedSize)
        sharedFoo.assertHeightIsEqualTo(expectedSize)
        sharedFoo.assertPositionInRootIsEqualTo(expectedLeft, expectedTop)

        // The shared offset of the single child of SharedFoo() is 50dp in scene B and 100dp in
        // Scene C.
        val expectedOffset = 50.dp + (100.dp - 50.dp) * interpolatedProgress
        assertThat(sharedFoo.onChild().offsetRelativeTo(sharedFoo))
            .isWithin(DpOffsetSubject.DefaultTolerance)
            .of(DpOffset(expectedOffset, expectedOffset))

        // Wait for the transition to C to finish.
        rule.mainClock.advanceTimeBy(TestTransitionDuration)
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneC)

        // Go back to scene A. This should happen instantly (once the animation started, i.e. after
        // 2 frames) given that we use a snap() animation spec.
        currentScene = SceneA
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertThat(layoutState.transitionState).isIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
    }

    @Test
    fun layoutSizeIsAnimated() {
        val layoutTag = "layout"
        rule.testTransition(
            fromSceneContent = { Box(Modifier.size(200.dp, 100.dp)) },
            toSceneContent = { Box(Modifier.size(120.dp, 140.dp)) },
            transition = {
                // 4 frames of animation.
                spec = tween(4 * 16, easing = LinearEasing)
            },
            layoutModifier = Modifier.testTag(layoutTag),
        ) {
            before { rule.onNodeWithTag(layoutTag).assertSizeIsEqualTo(200.dp, 100.dp) }
            at(16) { rule.onNodeWithTag(layoutTag).assertSizeIsEqualTo(180.dp, 110.dp) }
            at(32) { rule.onNodeWithTag(layoutTag).assertSizeIsEqualTo(160.dp, 120.dp) }
            at(48) { rule.onNodeWithTag(layoutTag).assertSizeIsEqualTo(140.dp, 130.dp) }
            after { rule.onNodeWithTag(layoutTag).assertSizeIsEqualTo(120.dp, 140.dp) }
        }
    }

    @Test
    fun multipleTransitionsWillComposeMultipleScenes() {
        val duration = 10 * 16L

        var currentScene: SceneKey by mutableStateOf(SceneA)
        lateinit var state: SceneTransitionLayoutState
        rule.setContent {
            state =
                updateSceneTransitionLayoutState(
                    currentScene = currentScene,
                    onChangeScene = { currentScene = it },
                    transitions =
                        transitions {
                            from(SceneA, to = SceneB) {
                                spec = tween(duration.toInt(), easing = LinearEasing)
                            }
                            from(SceneB, to = SceneC) {
                                spec = tween(duration.toInt(), easing = LinearEasing)
                            }
                        }
                )

            SceneTransitionLayout(state) {
                scene(SceneA) { Box(Modifier.testTag("aRoot").fillMaxSize()) }
                scene(SceneB) { Box(Modifier.testTag("bRoot").fillMaxSize()) }
                scene(SceneC) { Box(Modifier.testTag("cRoot").fillMaxSize()) }
            }
        }

        // Initial state: only A is composed.
        rule.onNodeWithTag("aRoot").assertExists()
        rule.onNodeWithTag("bRoot").assertDoesNotExist()
        rule.onNodeWithTag("cRoot").assertDoesNotExist()

        // Pause the clock so we can manually advance it.
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false

        // Start A => B and go to the middle of the transition.
        currentScene = SceneB

        // We need to tick 2 frames after changing [currentScene] before the animation actually
        // starts.
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeBy(duration / 2)
        rule.waitForIdle()

        var transition = assertThat(state.transitionState).isTransition()
        assertThat(transition).hasProgress(0.5f)

        // A and B are composed.
        rule.onNodeWithTag("aRoot").assertExists()
        rule.onNodeWithTag("bRoot").assertExists()
        rule.onNodeWithTag("cRoot").assertDoesNotExist()

        // Start B => C.
        currentScene = SceneC
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()

        transition = assertThat(state.transitionState).isTransition()
        assertThat(transition).hasProgress(0f)

        // A, B and C are composed.
        rule.onNodeWithTag("aRoot").assertExists()
        rule.onNodeWithTag("bRoot").assertExists()
        rule.onNodeWithTag("cRoot").assertExists()

        // Let A => B finish.
        rule.mainClock.advanceTimeBy(duration / 2L)
        assertThat(transition).hasProgress(0.5f)
        rule.waitForIdle()

        // B and C are composed.
        rule.onNodeWithTag("aRoot").assertDoesNotExist()
        rule.onNodeWithTag("bRoot").assertExists()
        rule.onNodeWithTag("cRoot").assertExists()

        // Let B => C finish.
        rule.mainClock.advanceTimeBy(duration / 2L)
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        assertThat(state.transitionState).isIdle()

        // Only C is composed.
        rule.onNodeWithTag("aRoot").assertDoesNotExist()
        rule.onNodeWithTag("bRoot").assertDoesNotExist()
        rule.onNodeWithTag("cRoot").assertExists()
    }

    private fun SemanticsNodeInteraction.offsetRelativeTo(
        other: SemanticsNodeInteraction,
    ): DpOffset {
        val node = fetchSemanticsNode()
        val bounds = node.boundsInRoot
        val otherBounds = other.fetchSemanticsNode().boundsInRoot
        return with(node.layoutInfo.density) {
            DpOffset(
                x = (bounds.left - otherBounds.left).toDp(),
                y = (bounds.top - otherBounds.top).toDp(),
            )
        }
    }

    @Test
    fun userActionFromSceneAToSceneA_throwsNotSupported() {
        val exception: IllegalStateException =
            assertThrows(IllegalStateException::class.java) {
                rule.setContent {
                    SceneTransitionLayout(
                        state =
                            updateSceneTransitionLayoutState(
                                currentScene = currentScene,
                                onChangeScene = { currentScene = it },
                                transitions = EmptyTestTransitions
                            ),
                        modifier = Modifier.size(LayoutSize),
                    ) {
                        // from SceneA to SceneA
                        scene(SceneA, userActions = mapOf(Back to SceneA), content = {})
                    }
                }
            }

        assertThat(exception).hasMessageThat().contains(Back.toString())
        assertThat(exception).hasMessageThat().contains(SceneA.debugName)
    }

    @Test
    fun sceneKeyInScope() {
        val state = rule.runOnUiThread { MutableSceneTransitionLayoutState(SceneA) }

        var keyInA: SceneKey? = null
        var keyInB: SceneKey? = null
        var keyInC: SceneKey? = null
        rule.setContent {
            SceneTransitionLayout(state) {
                scene(SceneA) { keyInA = sceneKey }
                scene(SceneB) { keyInB = sceneKey }
                scene(SceneC) { keyInC = sceneKey }
            }
        }

        // Snap to B then C to compose these scenes at least once.
        rule.runOnUiThread { state.snapToScene(SceneB) }
        rule.waitForIdle()
        rule.runOnUiThread { state.snapToScene(SceneC) }
        rule.waitForIdle()

        assertThat(keyInA).isEqualTo(SceneA)
        assertThat(keyInB).isEqualTo(SceneB)
        assertThat(keyInC).isEqualTo(SceneC)
    }
}
