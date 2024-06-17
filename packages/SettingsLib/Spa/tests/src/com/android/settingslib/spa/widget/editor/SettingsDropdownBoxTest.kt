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

package com.android.settingslib.spa.widget.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDropdownBoxTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dropdownMenuBox_displayed() {
        composeTestRule.setContent {
            var selectedItem by remember { mutableStateOf(0) }
            SettingsDropdownBox(
                label = LABEL,
                options = options,
                selectedOptionIndex = selectedItem,
            ) { selectedItem = it }
        }

        composeTestRule.onNodeWithText(LABEL).assertIsDisplayed()
    }

    @Test
    fun dropdownMenuBox_enabled_expanded() {
        composeTestRule.setContent {
            var selectedItem by remember { mutableIntStateOf(0) }
            SettingsDropdownBox(
                label = LABEL,
                options = options,
                selectedOptionIndex = selectedItem
            ) { selectedItem = it }
        }
        composeTestRule.onNodeWithText(ITEM2).assertDoesNotExist()

        composeTestRule.onNodeWithText(LABEL).performClick()

        composeTestRule.onNodeWithText(ITEM2).assertIsDisplayed()
    }

    @Test
    fun dropdownMenuBox_notEnabled_notExpanded() {
        composeTestRule.setContent {
            var selectedItem by remember { mutableIntStateOf(0) }
            SettingsDropdownBox(
                label = LABEL,
                options = options,
                enabled = false,
                selectedOptionIndex = selectedItem
            ) { selectedItem = it }
        }
        composeTestRule.onNodeWithText(ITEM2).assertDoesNotExist()

        composeTestRule.onNodeWithText(LABEL).performClick()

        composeTestRule.onNodeWithText(ITEM2).assertDoesNotExist()
    }

    @Test
    fun dropdownMenuBox_valueChanged() {
        composeTestRule.setContent {
            var selectedItem by remember { mutableIntStateOf(0) }
            SettingsDropdownBox(
                label = LABEL,
                options = options,
                selectedOptionIndex = selectedItem
            ) { selectedItem = it }
        }
        composeTestRule.onNodeWithText(ITEM2).assertDoesNotExist()

        composeTestRule.onNodeWithText(LABEL).performClick()
        composeTestRule.onNodeWithText(ITEM2).performClick()

        composeTestRule.onNodeWithText(ITEM2).assertIsDisplayed()
    }
    private companion object {
        const val LABEL = "Label"
        const val ITEM2 = "item2"
        val options = listOf("item1", ITEM2, "item3")
    }
}
