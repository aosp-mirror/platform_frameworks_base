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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.subjects.assertThat
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
        val layoutState = rule.runOnUiThread { MutableSceneTransitionLayoutState(SceneA) }
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

        val transition = assertThat(layoutState.transitionState).isTransition()
        assertThat(transition).hasFromScene(SceneA)
        assertThat(transition).hasToScene(SceneB)
        assertThat(transition).hasProgress(0.4f)

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

    private fun backEvent(progress: Float = 0f): BackEventCompat {
        return BackEventCompat(
            touchX = 0f,
            touchY = 0f,
            progress = progress,
            swipeEdge = BackEventCompat.EDGE_LEFT,
        )
    }
}
