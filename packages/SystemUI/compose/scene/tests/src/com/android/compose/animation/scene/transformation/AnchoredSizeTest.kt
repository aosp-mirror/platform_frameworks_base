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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.SceneScope
import com.android.compose.animation.scene.TestElements
import com.android.compose.animation.scene.TransitionBuilder
import com.android.compose.animation.scene.TransitionRecordingSpec
import com.android.compose.animation.scene.featureOfElement
import com.android.compose.animation.scene.recordTransition
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import platform.test.motion.compose.ComposeFeatureCaptures
import platform.test.motion.compose.createComposeMotionTestRule
import platform.test.motion.testing.createGoldenPathManager

@RunWith(AndroidJUnit4::class)
class AnchoredSizeTest {
    private val goldenPaths =
        createGoldenPathManager("frameworks/base/packages/SystemUI/compose/scene/tests/goldens")

    @get:Rule val motionRule = createComposeMotionTestRule(goldenPaths)

    @Test
    fun testAnchoredSizeEnter() {
        assertBarSizeMatchesGolden(
            fromSceneContent = { Box(Modifier.size(100.dp, 100.dp).element(TestElements.Foo)) },
            toSceneContent = {
                Box(Modifier.size(50.dp, 50.dp).element(TestElements.Foo))
                Box(Modifier.size(200.dp, 60.dp).element(TestElements.Bar))
            },
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)
                anchoredSize(TestElements.Bar, TestElements.Foo)
            }
        )
    }

    @Test
    fun testAnchoredSizeExit() {
        assertBarSizeMatchesGolden(
            fromSceneContent = {
                Box(Modifier.size(100.dp, 100.dp).element(TestElements.Foo))
                Box(Modifier.size(100.dp, 100.dp).element(TestElements.Bar))
            },
            toSceneContent = { Box(Modifier.size(200.dp, 60.dp).element(TestElements.Foo)) },
            transition = {
                // Scale during 4 frames.
                spec = tween(16 * 4, easing = LinearEasing)
                anchoredSize(TestElements.Bar, TestElements.Foo)
            }
        )
    }

    @Test
    fun testAnchoredWidthOnly() {
        assertBarSizeMatchesGolden(
            fromSceneContent = { Box(Modifier.size(100.dp, 100.dp).element(TestElements.Foo)) },
            toSceneContent = {
                Box(Modifier.size(50.dp, 50.dp).element(TestElements.Foo))
                Box(Modifier.size(200.dp, 60.dp).element(TestElements.Bar))
            },
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)
                anchoredSize(TestElements.Bar, TestElements.Foo, anchorHeight = false)
            },
        )
    }

    @Test
    fun testAnchoredHeightOnly() {
        assertBarSizeMatchesGolden(
            fromSceneContent = { Box(Modifier.size(100.dp, 100.dp).element(TestElements.Foo)) },
            toSceneContent = {
                Box(Modifier.size(50.dp, 50.dp).element(TestElements.Foo))
                Box(Modifier.size(200.dp, 60.dp).element(TestElements.Bar))
            },
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)
                anchoredSize(TestElements.Bar, TestElements.Foo, anchorWidth = false)
            }
        )
    }

    private fun assertBarSizeMatchesGolden(
        fromSceneContent: @Composable SceneScope.() -> Unit,
        toSceneContent: @Composable SceneScope.() -> Unit,
        transition: TransitionBuilder.() -> Unit,
    ) {
        val recordingSpec =
            TransitionRecordingSpec(recordAfter = true) {
                featureOfElement(TestElements.Bar, ComposeFeatureCaptures.dpSize)
            }

        val motion =
            motionRule.recordTransition(fromSceneContent, toSceneContent, transition, recordingSpec)

        motionRule.assertThat(motion).timeSeriesMatchesGolden()
    }
}
