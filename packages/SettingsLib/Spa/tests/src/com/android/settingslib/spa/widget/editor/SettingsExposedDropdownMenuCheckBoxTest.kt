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

package com.android.settingslib.spa.widget.editor

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsExposedDropdownMenuCheckBoxTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val item1 = "item1"
    private val item2 = "item2"
    private val item3 = "item3"
    private val options = listOf(item1, item2, item3)
    private val selectedOptionsState1 = mutableStateListOf(0, 1)
    private val exposedDropdownMenuCheckBoxLabel = "ExposedDropdownMenuCheckBoxLabel"

    @Test
    fun exposedDropdownMenuCheckBox_displayed() {
        composeTestRule.setContent {
            SettingsExposedDropdownMenuCheckBox(
                label = exposedDropdownMenuCheckBoxLabel,
                options = options,
                selectedOptionsState = remember { selectedOptionsState1 },
                enabled = true,
            ) {}
        }
        composeTestRule.onNodeWithText(
            exposedDropdownMenuCheckBoxLabel, substring = true
        ).assertIsDisplayed()
    }

    @Test
    fun exposedDropdownMenuCheckBox_expanded() {
        composeTestRule.setContent {
            SettingsExposedDropdownMenuCheckBox(
                label = exposedDropdownMenuCheckBoxLabel,
                options = options,
                selectedOptionsState = remember { selectedOptionsState1 },
                enabled = true,
            ) {}
        }
        composeTestRule.onNodeWithText(item3, substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(exposedDropdownMenuCheckBoxLabel, substring = true)
            .performClick()
        composeTestRule.onNodeWithText(item3, substring = true).assertIsDisplayed()
    }

    @Test
    fun exposedDropdownMenuCheckBox_valueAdded() {
        composeTestRule.setContent {
            SettingsExposedDropdownMenuCheckBox(
                label = exposedDropdownMenuCheckBoxLabel,
                options = options,
                selectedOptionsState = remember { selectedOptionsState1 },
                enabled = true,
            ) {}
        }
        composeTestRule.onNodeWithText(item3, substring = true).assertDoesNotExist()
        composeTestRule.onNodeWithText(exposedDropdownMenuCheckBoxLabel, substring = true)
            .performClick()
        composeTestRule.onNodeWithText(item3, substring = true).performClick()
        composeTestRule.onFocusedText(item3).assertIsDisplayed()
    }

    @Test
    fun exposedDropdownMenuCheckBox_valueDeleted() {
        composeTestRule.setContent {
            SettingsExposedDropdownMenuCheckBox(
                label = exposedDropdownMenuCheckBoxLabel,
                options = options,
                selectedOptionsState = remember { selectedOptionsState1 },
                enabled = true,
            ) {}
        }
        composeTestRule.onNodeWithText(item2, substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText(exposedDropdownMenuCheckBoxLabel, substring = true)
            .performClick()
        composeTestRule.onNotFocusedText(item2).performClick()
        composeTestRule.onFocusedText(item2).assertDoesNotExist()
    }
}

fun ComposeContentTestRule.onFocusedText(text: String): SemanticsNodeInteraction =
    onNode(isFocused() and hasText(text, substring = true))

fun ComposeContentTestRule.onNotFocusedText(text: String): SemanticsNodeInteraction =
    onNode(!isFocused() and hasText(text, substring = true))