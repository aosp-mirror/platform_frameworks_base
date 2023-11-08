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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.Edge
import com.android.compose.animation.scene.TestElements
import com.android.compose.animation.scene.TransitionTestBuilder
import com.android.compose.animation.scene.testTransition
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class EdgeTranslateTest {

    @get:Rule val rule = createComposeRule()

    private fun testEdgeTranslate(
        edge: Edge,
        startsOutsideLayoutBounds: Boolean,
        builder: TransitionTestBuilder.() -> Unit,
    ) {
        rule.testTransition(
            // The layout under test is 300dp x 300dp.
            layoutModifier = Modifier.size(300.dp),
            fromSceneContent = { Box(Modifier.fillMaxSize()) },
            toSceneContent = {
                // Foo is 100dp x 100dp in the center of the layout, so at offset = (100dp, 100dp)
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.size(100.dp).element(TestElements.Foo).align(Alignment.Center))
                }
            },
            transition = {
                spec = tween(16 * 4, easing = LinearEasing)
                translate(TestElements.Foo, edge, startsOutsideLayoutBounds)
            },
            builder = builder,
        )
    }

    @Test
    fun testEntersFromTop_startsOutsideLayoutBounds() {
        testEdgeTranslate(Edge.Top, startsOutsideLayoutBounds = true) {
            before { onElement(TestElements.Foo).assertDoesNotExist() }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, (-100).dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 0.dp) }
            at(64) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
        }
    }

    @Test
    fun testEntersFromTop_startsInsideLayoutBounds() {
        testEdgeTranslate(Edge.Top, startsOutsideLayoutBounds = false) {
            before { onElement(TestElements.Foo).assertDoesNotExist() }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 0.dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 50.dp) }
            at(64) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
        }
    }

    @Test
    fun testEntersFromBottom_startsOutsideLayoutBounds() {
        testEdgeTranslate(Edge.Bottom, startsOutsideLayoutBounds = true) {
            before { onElement(TestElements.Foo).assertDoesNotExist() }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 300.dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 200.dp) }
            at(64) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
        }
    }

    @Test
    fun testEntersFromBottom_startsInsideLayoutBounds() {
        testEdgeTranslate(Edge.Bottom, startsOutsideLayoutBounds = false) {
            before { onElement(TestElements.Foo).assertDoesNotExist() }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 200.dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 150.dp) }
            at(64) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
        }
    }

    @Test
    fun testEntersFromLeft_startsOutsideLayoutBounds() {
        testEdgeTranslate(Edge.Left, startsOutsideLayoutBounds = true) {
            before { onElement(TestElements.Foo).assertDoesNotExist() }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo((-100).dp, 100.dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(0.dp, 100.dp) }
            at(64) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
        }
    }

    @Test
    fun testEntersFromLeft_startsInsideLayoutBounds() {
        testEdgeTranslate(Edge.Left, startsOutsideLayoutBounds = false) {
            before { onElement(TestElements.Foo).assertDoesNotExist() }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(0.dp, 100.dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(50.dp, 100.dp) }
            at(64) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
        }
    }

    @Test
    fun testEntersFromRight_startsOutsideLayoutBounds() {
        testEdgeTranslate(Edge.Right, startsOutsideLayoutBounds = true) {
            before { onElement(TestElements.Foo).assertDoesNotExist() }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(300.dp, 100.dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(200.dp, 100.dp) }
            at(64) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
        }
    }

    @Test
    fun testEntersFromRight_startsInsideLayoutBounds() {
        testEdgeTranslate(Edge.Right, startsOutsideLayoutBounds = false) {
            before { onElement(TestElements.Foo).assertDoesNotExist() }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(200.dp, 100.dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(150.dp, 100.dp) }
            at(64) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(100.dp, 100.dp) }
        }
    }
}
