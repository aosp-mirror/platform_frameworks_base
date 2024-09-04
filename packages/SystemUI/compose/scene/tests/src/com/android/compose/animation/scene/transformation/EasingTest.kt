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

import androidx.compose.animation.core.CubicBezierEasing
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
class EasingTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun testFractionRangeEasing() {
        val easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
        rule.testTransition(
            fromSceneContent = { Box(Modifier.size(100.dp).element(TestElements.Foo)) },
            toSceneContent = { Box(Modifier.size(100.dp).element(TestElements.Bar)) },
            transition = {
                // Scale during 4 frames.
                spec = tween(16 * 4, easing = LinearEasing)
                fractionRange(easing = easing) {
                    scaleSize(TestElements.Foo, width = 0f, height = 0f)
                    scaleSize(TestElements.Bar, width = 0f, height = 0f)
                }
            },
        ) {
            // Foo is entering, is 100dp x 100dp at rest and is scaled by (2, 0.5) during the
            // transition so it starts at 200dp x 50dp.
            before { onElement(TestElements.Bar).assertDoesNotExist() }
            at(0) {
                onElement(TestElements.Foo).assertSizeIsEqualTo(100.dp, 100.dp)
                onElement(TestElements.Bar).assertSizeIsEqualTo(0.dp, 0.dp)
            }
            at(16) {
                // 25% linear progress is mapped to 68.5% eased progress
                onElement(TestElements.Foo).assertSizeIsEqualTo(31.5.dp, 31.5.dp)
                onElement(TestElements.Bar).assertSizeIsEqualTo(68.5.dp, 68.5.dp)
            }
            at(32) {
                // 50% linear progress is mapped to 89.5% eased progress
                onElement(TestElements.Foo).assertSizeIsEqualTo(10.5.dp, 10.5.dp)
                onElement(TestElements.Bar).assertSizeIsEqualTo(89.5.dp, 89.5.dp)
            }
            at(48) {
                // 75% linear progress is mapped to 97.8% eased progress
                onElement(TestElements.Foo).assertSizeIsEqualTo(2.2.dp, 2.2.dp)
                onElement(TestElements.Bar).assertSizeIsEqualTo(97.8.dp, 97.8.dp)
            }
            after {
                onElement(TestElements.Foo).assertDoesNotExist()
                onElement(TestElements.Bar).assertSizeIsEqualTo(100.dp, 100.dp)
            }
        }
    }

    @Test
    fun testTimestampRangeEasing() {
        val easing = CubicBezierEasing(0.1f, 0.1f, 0f, 1f)
        rule.testTransition(
            fromSceneContent = { Box(Modifier.size(100.dp).element(TestElements.Foo)) },
            toSceneContent = { Box(Modifier.size(100.dp).element(TestElements.Bar)) },
            transition = {
                // Scale during 4 frames.
                spec = tween(16 * 4, easing = LinearEasing)
                timestampRange(easing = easing) {
                    scaleSize(TestElements.Foo, width = 0f, height = 0f)
                    scaleSize(TestElements.Bar, width = 0f, height = 0f)
                }
            },
        ) {
            // Foo is entering, is 100dp x 100dp at rest and is scaled by (2, 0.5) during the
            // transition so it starts at 200dp x 50dp.
            before { onElement(TestElements.Bar).assertDoesNotExist() }
            at(0) {
                onElement(TestElements.Foo).assertSizeIsEqualTo(100.dp, 100.dp)
                onElement(TestElements.Bar).assertSizeIsEqualTo(0.dp, 0.dp)
            }
            at(16) {
                // 25% linear progress is mapped to 68.5% eased progress
                onElement(TestElements.Foo).assertSizeIsEqualTo(31.5.dp, 31.5.dp)
                onElement(TestElements.Bar).assertSizeIsEqualTo(68.5.dp, 68.5.dp)
            }
            at(32) {
                // 50% linear progress is mapped to 89.5% eased progress
                onElement(TestElements.Foo).assertSizeIsEqualTo(10.5.dp, 10.5.dp)
                onElement(TestElements.Bar).assertSizeIsEqualTo(89.5.dp, 89.5.dp)
            }
            at(48) {
                // 75% linear progress is mapped to 97.8% eased progress
                onElement(TestElements.Foo).assertSizeIsEqualTo(2.2.dp, 2.2.dp)
                onElement(TestElements.Bar).assertSizeIsEqualTo(97.8.dp, 97.8.dp)
            }
            after {
                onElement(TestElements.Foo).assertDoesNotExist()
                onElement(TestElements.Bar).assertSizeIsEqualTo(100.dp, 100.dp)
            }
        }
    }
}
