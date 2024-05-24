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

package com.android.compose.animation.scene.modifiers

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compose.animation.scene.TestElements
import com.android.compose.animation.scene.element
import com.android.compose.animation.scene.testTransition
import com.android.compose.test.assertSizeIsEqualTo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SizeTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun noResizeDuringTransitions() {
        // The tag for the parent of the shared Foo element.
        val parentTag = "parent"

        rule.testTransition(
            fromSceneContent = { Box(Modifier.element(TestElements.Foo).size(100.dp)) },
            toSceneContent = {
                // Don't resize the parent of Foo during transitions so that it's always the same
                // size as when there is no transition (200dp).
                Box(Modifier.noResizeDuringTransitions().testTag(parentTag)) {
                    Box(Modifier.element(TestElements.Foo).size(200.dp))
                }
            },
            transition = { spec = tween(durationMillis = 4 * 16, easing = LinearEasing) },
        ) {
            at(16) { rule.onNodeWithTag(parentTag).assertSizeIsEqualTo(200.dp, 200.dp) }
            at(32) { rule.onNodeWithTag(parentTag).assertSizeIsEqualTo(200.dp, 200.dp) }
            at(48) { rule.onNodeWithTag(parentTag).assertSizeIsEqualTo(200.dp, 200.dp) }
            after { rule.onNodeWithTag(parentTag).assertSizeIsEqualTo(200.dp, 200.dp) }
        }
    }
}
