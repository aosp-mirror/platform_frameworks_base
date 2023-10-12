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
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestElements
import com.android.compose.animation.scene.testTransition
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnchoredTranslateTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun testAnchoredTranslateExit() {
        rule.testTransition(
            fromSceneContent = {
                Box(Modifier.offset(10.dp, 50.dp).element(TestElements.Foo))
                Box(Modifier.offset(20.dp, 40.dp).element(TestElements.Bar))
            },
            toSceneContent = { Box(Modifier.offset(30.dp, 10.dp).element(TestElements.Foo)) },
            transition = {
                // Anchor Bar to Foo, which is moving from (10dp, 50dp) to (30dp, 10dp).
                spec = tween(16 * 4, easing = LinearEasing)
                anchoredTranslate(TestElements.Bar, TestElements.Foo)
            },
        ) {
            // Bar moves by (20dp, -40dp), like Foo.
            before { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(20.dp, 40.dp) }
            at(0) { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(20.dp, 40.dp) }
            at(16) { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(25.dp, 30.dp) }
            at(32) { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(30.dp, 20.dp) }
            at(48) { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(35.dp, 10.dp) }
            after { onElement(TestElements.Bar).assertDoesNotExist() }
        }
    }

    @Test
    fun testAnchoredTranslateEnter() {
        rule.testTransition(
            fromSceneContent = { Box(Modifier.offset(10.dp, 50.dp).element(TestElements.Foo)) },
            toSceneContent = {
                Box(Modifier.offset(30.dp, 10.dp).element(TestElements.Foo))
                Box(Modifier.offset(20.dp, 40.dp).element(TestElements.Bar))
            },
            transition = {
                // Anchor Bar to Foo, which is moving from (10dp, 50dp) to (30dp, 10dp).
                spec = tween(16 * 4, easing = LinearEasing)
                anchoredTranslate(TestElements.Bar, TestElements.Foo)
            },
        ) {
            // Bar moves by (20dp, -40dp), like Foo.
            before { onElement(TestElements.Bar).assertDoesNotExist() }
            at(0) { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(0.dp, 80.dp) }
            at(16) { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(5.dp, 70.dp) }
            at(32) { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(10.dp, 60.dp) }
            at(48) { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(15.dp, 50.dp) }
            at(64) { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(20.dp, 40.dp) }
            after { onElement(TestElements.Bar).assertPositionInRootIsEqualTo(20.dp, 40.dp) }
        }
    }
}
