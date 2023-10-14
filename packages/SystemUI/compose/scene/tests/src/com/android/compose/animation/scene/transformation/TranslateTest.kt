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
class TranslateTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun testTranslateExit() {
        rule.testTransition(
            fromSceneContent = {
                // Foo is at (10dp, 50dp) and is exiting.
                Box(Modifier.offset(10.dp, 50.dp).element(TestElements.Foo))
            },
            toSceneContent = {},
            transition = {
                // Foo is translated by (20dp, -40dp) during 4 frames.
                spec = tween(16 * 4, easing = LinearEasing)
                translate(TestElements.Foo, x = 20.dp, y = (-40).dp)
            },
        ) {
            before { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(10.dp, 50.dp) }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(10.dp, 50.dp) }
            at(16) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(15.dp, 40.dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(20.dp, 30.dp) }
            at(48) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(25.dp, 20.dp) }
            after { onElement(TestElements.Foo).assertDoesNotExist() }
        }
    }

    @Test
    fun testTranslateEnter() {
        rule.testTransition(
            fromSceneContent = {},
            toSceneContent = {
                // Foo is entering to (10dp, 50dp)
                Box(Modifier.offset(10.dp, 50.dp).element(TestElements.Foo))
            },
            transition = {
                // Foo is translated from (10dp, 50) + (20dp, -40dp) during 4 frames.
                spec = tween(16 * 4, easing = LinearEasing)
                translate(TestElements.Foo, x = 20.dp, y = (-40).dp)
            },
        ) {
            before { onElement(TestElements.Foo).assertDoesNotExist() }
            at(0) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(30.dp, 10.dp) }
            at(16) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(25.dp, 20.dp) }
            at(32) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(20.dp, 30.dp) }
            at(48) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(15.dp, 40.dp) }
            at(64) { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(10.dp, 50.dp) }
            after { onElement(TestElements.Foo).assertPositionInRootIsEqualTo(10.dp, 50.dp) }
        }
    }
}
