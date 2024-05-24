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

package com.android.compose.animation.scene

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ElementScenePickerTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun highestZIndexPicker() {
        val key = ElementKey("TestElement", scenePicker = HighestZIndexScenePicker)
        rule.testTransition(
            fromSceneContent = { Box(Modifier.element(key).size(10.dp)) },
            toSceneContent = { Box(Modifier.element(key).size(10.dp)) },
            transition = { spec = tween(4 * 16, easing = LinearEasing) },
            fromScene = TestScenes.SceneA,
            toScene = TestScenes.SceneB,
        ) {
            before {
                onElement(key, TestScenes.SceneA).assertIsDisplayed()
                onElement(key, TestScenes.SceneB).assertDoesNotExist()
            }
            at(32) {
                // Scene B has the highest index, so the element is placed only there.
                onElement(key, TestScenes.SceneA).assertExists().assertIsNotDisplayed()
                onElement(key, TestScenes.SceneB).assertIsDisplayed()
            }
            after {
                onElement(key, TestScenes.SceneA).assertDoesNotExist()
                onElement(key, TestScenes.SceneB).assertIsDisplayed()
            }
        }
    }

    @Test
    fun lowestZIndexPicker() {
        val key = ElementKey("TestElement", scenePicker = LowestZIndexScenePicker)
        rule.testTransition(
            fromSceneContent = { Box(Modifier.element(key).size(10.dp)) },
            toSceneContent = { Box(Modifier.element(key).size(10.dp)) },
            transition = { spec = tween(4 * 16, easing = LinearEasing) },
            fromScene = TestScenes.SceneA,
            toScene = TestScenes.SceneB,
        ) {
            before {
                onElement(key, TestScenes.SceneA).assertIsDisplayed()
                onElement(key, TestScenes.SceneB).assertDoesNotExist()
            }
            at(32) {
                // Scene A has the lowest index, so the element is placed only there.
                onElement(key, TestScenes.SceneA).assertIsDisplayed()
                onElement(key, TestScenes.SceneB).assertExists().assertIsNotDisplayed()
            }
            after {
                onElement(key, TestScenes.SceneA).assertDoesNotExist()
                onElement(key, TestScenes.SceneB).assertIsDisplayed()
            }
        }
    }
}
