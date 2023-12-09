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
class ScaleSizeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun testScaleSize() {
        rule.testTransition(
            fromSceneContent = {},
            toSceneContent = { Box(Modifier.size(100.dp).element(TestElements.Foo)) },
            transition = {
                // Scale during 4 frames.
                spec = tween(16 * 4, easing = LinearEasing)
                scaleSize(TestElements.Foo, width = 2f, height = 0.5f)
            },
        ) {
            // Foo is entering, is 100dp x 100dp at rest and is scaled by (2, 0.5) during the
            // transition so it starts at 200dp x 50dp.
            before { onElement(TestElements.Foo).assertDoesNotExist() }
            at(0) { onElement(TestElements.Foo).assertSizeIsEqualTo(200.dp, 50.dp) }
            at(16) { onElement(TestElements.Foo).assertSizeIsEqualTo(175.dp, 62.5.dp) }
            at(32) { onElement(TestElements.Foo).assertSizeIsEqualTo(150.dp, 75.dp) }
            at(48) { onElement(TestElements.Foo).assertSizeIsEqualTo(125.dp, 87.5.dp) }
            at(64) { onElement(TestElements.Foo).assertSizeIsEqualTo(100.dp, 100.dp) }
            after { onElement(TestElements.Foo).assertSizeIsEqualTo(100.dp, 100.dp) }
        }
    }
}
