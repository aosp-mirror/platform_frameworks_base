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

package com.android.compose.animation.scene.testing

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.Scale
import com.android.compose.animation.scene.TestElements
import com.android.compose.animation.scene.TestScenes.SceneA
import com.android.compose.animation.scene.TestScenes.SceneB
import com.android.compose.animation.scene.testTransition
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementStateAccessTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun testElementStateAccess() {
        rule.testTransition(
            fromSceneContent = { Box(Modifier.element(TestElements.Foo).size(50.dp)) },
            toSceneContent = { Box(Modifier) },
            transition = {
                spec = tween(durationMillis = 16 * 4, easing = LinearEasing)
                fade(TestElements.Foo)
                scaleDraw(TestElements.Foo, scaleX = 0f, scaleY = 0f)
            },
            fromScene = SceneA,
            toScene = SceneB,
        ) {
            before {
                val semanticNode = onElement(TestElements.Foo).fetchSemanticsNode()
                assertThat(semanticNode.lastAlphaForTesting).isEqualTo(1f)
                assertThat(semanticNode.lastScaleForTesting).isEqualTo(Scale(1f, 1f))
            }

            at(32) {
                val semanticNode = onElement(TestElements.Foo).fetchSemanticsNode()
                assertThat(semanticNode.lastAlphaForTesting).isEqualTo(0.5f)
                assertThat(semanticNode.lastScaleForTesting).isEqualTo(Scale(0.5f, 0.5f))
            }

            at(64) {
                val semanticNode = onElement(TestElements.Foo).fetchSemanticsNode()
                assertThat(semanticNode.lastAlphaForTesting).isEqualTo(0f)
                assertThat(semanticNode.lastScaleForTesting).isEqualTo(Scale(0f, 0f))
            }
            after { onElement(TestElements.Foo).assertDoesNotExist() }
        }
    }
}
