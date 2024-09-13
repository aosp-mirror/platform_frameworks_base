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

import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestOverlays.OverlayA
import com.android.compose.animation.scene.TestOverlays.OverlayB
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.TestScenes.SceneC
import com.android.compose.animation.scene.subjects.assertThat
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PredictiveBackHandlerTest {
    // We use createAndroidComposeRule() here and not createComposeRule() because we need an
    // activity for testBack().
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun testBack() {
        val layoutState = rule.runOnUiThread { MutableSceneTransitionLayoutState(SceneA) }
        rule.setContent {
            SceneTransitionLayout(layoutState) {
                scene(SceneA, mapOf(Back to SceneB)) { Box(Modifier.fillMaxSize()) }
                scene(SceneB) { Box(Modifier.fillMaxSize()) }
            }
        }

        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneB)
    }

    @Test
    fun testPredictiveBack() {
        val transitionFrames = 2
        val layoutState =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(
                    SceneA,
                    transitions =
                        transitions {
                            from(SceneA, to = SceneB) {
                                spec =
                                    tween(
                                        durationMillis = transitionFrames * 16,
                                        easing = LinearEasing,
                                    )
                            }
                        },
                )
            }
        rule.setContent {
            SceneTransitionLayout(layoutState) {
                scene(SceneA, mapOf(Back to SceneB)) { Box(Modifier.fillMaxSize()) }
                scene(SceneB) { Box(Modifier.fillMaxSize()) }
            }
        }

        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // Start back.
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(backEvent())
            dispatcher.dispatchOnBackProgressed(backEvent(progress = 0.4f))
        }

        val transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(0.4f)
        assertThat(transition).isNotInPreviewStage()

        // Cancel it.
        rule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).isIdle()

        rule.mainClock.autoAdvance = false

        // Start again and commit it.
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(backEvent())
            dispatcher.dispatchOnBackProgressed(backEvent(progress = 0.4f))
            dispatcher.onBackPressed()
        }
        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        val transition2 = assertThat(layoutState.transitionState).isSceneTransition()
        // verify that transition picks up progress from preview
        assertThat(transition2).hasProgress(0.4f, tolerance = 0.0001f)

        rule.mainClock.advanceTimeByFrame()
        rule.waitForIdle()
        // verify that transition is half way between preview-end-state (0.4f) and target-state (1f)
        // after one frame
        assertThat(transition2).hasProgress(0.7f, tolerance = 0.0001f)

        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneB)
        assertThat(layoutState.transitionState).isIdle()
    }

    @Test
    fun testPredictiveBackWithPreview() {
        val layoutState =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(
                    SceneA,
                    transitions = transitions { from(SceneA, to = SceneB, preview = {}) },
                )
            }
        rule.setContent {
            SceneTransitionLayout(layoutState) {
                scene(SceneA, mapOf(Back to SceneB)) { Box(Modifier.fillMaxSize()) }
                scene(SceneB) { Box(Modifier.fillMaxSize()) }
            }
        }

        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // Start back.
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(backEvent())
            dispatcher.dispatchOnBackProgressed(backEvent(progress = 0.4f))
        }

        val transition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasPreviewProgress(0.4f)
        assertThat(transition).hasProgress(0f)
        assertThat(transition).isInPreviewStage()

        // Cancel it.
        rule.runOnUiThread { dispatcher.dispatchOnBackCancelled() }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)
        assertThat(layoutState.transitionState).isIdle()

        // Start again and commit it.
        rule.runOnUiThread {
            dispatcher.dispatchOnBackStarted(backEvent())
            dispatcher.dispatchOnBackProgressed(backEvent(progress = 0.4f))
            dispatcher.onBackPressed()
        }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneB)
        assertThat(layoutState.transitionState).isIdle()
    }

    @Test
    fun interruptedPredictiveBackDoesNotCallCanChangeScene() {
        var canChangeSceneCalled = false
        val layoutState =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(
                    SceneA,
                    canChangeScene = {
                        canChangeSceneCalled = true
                        true
                    },
                )
            }

        lateinit var coroutineScope: CoroutineScope
        rule.setContent {
            coroutineScope = rememberCoroutineScope()
            SceneTransitionLayout(layoutState) {
                scene(SceneA, mapOf(Back to SceneB)) { Box(Modifier.fillMaxSize()) }
                scene(SceneB) { Box(Modifier.fillMaxSize()) }
                scene(SceneC) { Box(Modifier.fillMaxSize()) }
            }
        }

        assertThat(layoutState.transitionState).hasCurrentScene(SceneA)

        // Start back.
        val dispatcher = rule.activity.onBackPressedDispatcher
        rule.runOnUiThread { dispatcher.dispatchOnBackStarted(backEvent()) }

        val predictiveTransition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(predictiveTransition).hasFromScene(SceneA)
        assertThat(predictiveTransition).hasToScene(SceneB)

        // Start a new transition to C.
        rule.runOnUiThread { layoutState.setTargetScene(SceneC, coroutineScope) }
        val newTransition = assertThat(layoutState.transitionState).isSceneTransition()
        assertThat(newTransition).hasFromScene(SceneA)
        assertThat(newTransition).hasToScene(SceneC)

        // Commit the back gesture. It shouldn't call canChangeScene given that the back transition
        // was interrupted.
        rule.runOnUiThread { dispatcher.onBackPressed() }
        rule.waitForIdle()
        assertThat(layoutState.transitionState).hasCurrentScene(SceneC)
        assertThat(layoutState.transitionState).isIdle()
        assertThat(predictiveTransition).hasCurrentScene(SceneA)
        assertThat(canChangeSceneCalled).isFalse()
    }

    @Test
    fun backDismissesOverlayWithHighestZIndexByDefault() {
        val state =
            rule.runOnUiThread {
                MutableSceneTransitionLayoutState(
                    SceneA,
                    initialOverlays = setOf(OverlayA, OverlayB),
                )
            }

        rule.setContent {
            SceneTransitionLayout(state, Modifier.size(200.dp)) {
                scene(SceneA) { Box(Modifier.fillMaxSize()) }
                overlay(OverlayA) { Box(Modifier.fillMaxSize()) }
                overlay(OverlayB) { Box(Modifier.fillMaxSize()) }
            }
        }

        // Initial state.
        rule.onNode(hasTestTag(SceneA.testTag)).assertIsDisplayed()
        rule.onNode(hasTestTag(OverlayA.testTag)).assertIsDisplayed()
        rule.onNode(hasTestTag(OverlayB.testTag)).assertIsDisplayed()

        // Press back. This should hide overlay B because it has a higher zIndex than overlay A.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNode(hasTestTag(SceneA.testTag)).assertIsDisplayed()
        rule.onNode(hasTestTag(OverlayA.testTag)).assertIsDisplayed()
        rule.onNode(hasTestTag(OverlayB.testTag)).assertDoesNotExist()

        // Press back again. This should hide overlay A.
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.onNode(hasTestTag(SceneA.testTag)).assertIsDisplayed()
        rule.onNode(hasTestTag(OverlayA.testTag)).assertDoesNotExist()
        rule.onNode(hasTestTag(OverlayB.testTag)).assertDoesNotExist()
    }

    private fun backEvent(progress: Float = 0f): BackEventCompat {
        return BackEventCompat(
            touchX = 0f,
            touchY = 0f,
            progress = progress,
            swipeEdge = BackEventCompat.EDGE_LEFT,
        )
    }
}
