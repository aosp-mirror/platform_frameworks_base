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
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestElements
import com.android.compose.animation.scene.testTransition
import com.android.compose.test.assertSizeIsEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchoredSizeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun testAnchoredSizeEnter() {
        rule.testTransition(
            fromSceneContent = { Box(Modifier.size(100.dp, 100.dp).element(TestElements.Foo)) },
            toSceneContent = {
                Box(Modifier.size(50.dp, 50.dp).element(TestElements.Foo))
                Box(Modifier.size(200.dp, 60.dp).element(TestElements.Bar))
            },
            transition = {
                // Scale during 4 frames.
                spec = tween(16 * 4, easing = LinearEasing)
                anchoredSize(TestElements.Bar, TestElements.Foo)
            },
        ) {
            // Bar is entering. It starts at the same size as Foo in scene A in and scales to its
            // final size in scene B.
            before { onElement(TestElements.Bar).assertDoesNotExist() }
            at(0) { onElement(TestElements.Bar).assertSizeIsEqualTo(100.dp, 100.dp) }
            at(16) { onElement(TestElements.Bar).assertSizeIsEqualTo(125.dp, 90.dp) }
            at(32) { onElement(TestElements.Bar).assertSizeIsEqualTo(150.dp, 80.dp) }
            at(48) { onElement(TestElements.Bar).assertSizeIsEqualTo(175.dp, 70.dp) }
            at(64) { onElement(TestElements.Bar).assertSizeIsEqualTo(200.dp, 60.dp) }
            after { onElement(TestElements.Bar).assertSizeIsEqualTo(200.dp, 60.dp) }
        }
    }

    @Test
    fun testAnchoredSizeExit() {
        rule.testTransition(
            fromSceneContent = {
                Box(Modifier.size(100.dp, 100.dp).element(TestElements.Foo))
                Box(Modifier.size(100.dp, 100.dp).element(TestElements.Bar))
            },
            toSceneContent = { Box(Modifier.size(200.dp, 60.dp).element(TestElements.Foo)) },
            transition = {
                // Scale during 4 frames.
                spec = tween(16 * 4, easing = LinearEasing)
                anchoredSize(TestElements.Bar, TestElements.Foo)
            },
        ) {
            // Bar is leaving. It starts at 100dp x 100dp in scene A and is scaled to 200dp x 60dp,
            // the size of Foo in scene B.
            before { onElement(TestElements.Bar).assertSizeIsEqualTo(100.dp, 100.dp) }
            at(0) { onElement(TestElements.Bar).assertSizeIsEqualTo(100.dp, 100.dp) }
            at(16) { onElement(TestElements.Bar).assertSizeIsEqualTo(125.dp, 90.dp) }
            at(32) { onElement(TestElements.Bar).assertSizeIsEqualTo(150.dp, 80.dp) }
            at(48) { onElement(TestElements.Bar).assertSizeIsEqualTo(175.dp, 70.dp) }
            after { onElement(TestElements.Bar).assertDoesNotExist() }
        }
    }

    @Test
    fun testAnchoredWidthOnly() {
        rule.testTransition(
            fromSceneContent = { Box(Modifier.size(100.dp, 100.dp).element(TestElements.Foo)) },
            toSceneContent = {
                Box(Modifier.size(50.dp, 50.dp).element(TestElements.Foo))
                Box(Modifier.size(200.dp, 60.dp).element(TestElements.Bar))
            },
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)
                anchoredSize(TestElements.Bar, TestElements.Foo, anchorHeight = false)
            },
        ) {
            before { onElement(TestElements.Bar).assertDoesNotExist() }
            at(0) { onElement(TestElements.Bar).assertSizeIsEqualTo(100.dp, 60.dp) }
            at(16) { onElement(TestElements.Bar).assertSizeIsEqualTo(125.dp, 60.dp) }
            at(32) { onElement(TestElements.Bar).assertSizeIsEqualTo(150.dp, 60.dp) }
            at(48) { onElement(TestElements.Bar).assertSizeIsEqualTo(175.dp, 60.dp) }
            at(64) { onElement(TestElements.Bar).assertSizeIsEqualTo(200.dp, 60.dp) }
            after { onElement(TestElements.Bar).assertSizeIsEqualTo(200.dp, 60.dp) }
        }
    }

    @Test
    fun testAnchoredHeightOnly() {
        rule.testTransition(
            fromSceneContent = { Box(Modifier.size(100.dp, 100.dp).element(TestElements.Foo)) },
            toSceneContent = {
                Box(Modifier.size(50.dp, 50.dp).element(TestElements.Foo))
                Box(Modifier.size(200.dp, 60.dp).element(TestElements.Bar))
            },
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)
                anchoredSize(TestElements.Bar, TestElements.Foo, anchorWidth = false)
            },
        ) {
            before { onElement(TestElements.Bar).assertDoesNotExist() }
            at(0) { onElement(TestElements.Bar).assertSizeIsEqualTo(200.dp, 100.dp) }
            at(16) { onElement(TestElements.Bar).assertSizeIsEqualTo(200.dp, 90.dp) }
            at(32) { onElement(TestElements.Bar).assertSizeIsEqualTo(200.dp, 80.dp) }
            at(48) { onElement(TestElements.Bar).assertSizeIsEqualTo(200.dp, 70.dp) }
            at(64) { onElement(TestElements.Bar).assertSizeIsEqualTo(200.dp, 60.dp) }
            after { onElement(TestElements.Bar).assertSizeIsEqualTo(200.dp, 60.dp) }
        }
    }
}
