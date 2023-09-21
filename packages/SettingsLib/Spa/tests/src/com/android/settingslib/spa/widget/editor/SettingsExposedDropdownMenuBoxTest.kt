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
class SettingsExposedDropdownMenuBoxTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val options = listOf("item1", "item2", "item3")
    private val item2 = "item2"
    private val exposedDropdownMenuBoxLabel = "ExposedDropdownMenuBoxLabel"

    @Test
    fun exposedDropdownMenuBoxs_displayed() {
        composeTestRule.setContent {
            var selectedItem by remember { mutableStateOf(0) }
            SettingsExposedDropdownMenuBox(
                label = exposedDropdownMenuBoxLabel,
                options = options,
                selectedOptionIndex = selectedItem,
                enabled = true,
                onselectedOptionTextChange = { selectedItem = it })
        }
        composeTestRule.onNodeWithText(exposedDropdownMenuBoxLabel, substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun exposedDropdownMenuBoxs_expanded() {
        composeTestRule.setContent {
            var selectedItem by remember { mutableIntStateOf(0) }
            SettingsExposedDropdownMenuBox(
                label = exposedDropdownMenuBoxLabel,
                options = options,
                selectedOptionIndex = selectedItem,
                enabled = true,
                onselectedOptionTextChange = { selectedItem = it })
        }
        composeTestRule.onNodeWithText(item2, substring = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(exposedDropdownMenuBoxLabel, substring = true)
            .performClick()
        composeTestRule.onNodeWithText(item2, substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun exposedDropdownMenuBoxs_valueChanged() {
        composeTestRule.setContent {
            var selectedItem by remember { mutableIntStateOf(0) }
            SettingsExposedDropdownMenuBox(
                label = exposedDropdownMenuBoxLabel,
                options = options,
                selectedOptionIndex = selectedItem,
                enabled = true,
                onselectedOptionTextChange = { selectedItem = it })
        }
        composeTestRule.onNodeWithText(item2, substring = true)
            .assertDoesNotExist()
        composeTestRule.onNodeWithText(exposedDropdownMenuBoxLabel, substring = true)
            .performClick()
        composeTestRule.onNodeWithText(item2, substring = true)
            .performClick()
        composeTestRule.onNodeWithText(item2, substring = true)
            .assertIsDisplayed()
    }
}