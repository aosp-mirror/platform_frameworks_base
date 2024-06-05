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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsOutlinedTextFieldsTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val outlinedTextFieldLabel = "OutlinedTextField Enabled"
    private val enabledValue = "Enabled Value"
    private val disabledValue = "Disabled Value"
    private val valueChanged = "Value Changed"

    @Test
    fun outlinedTextField_displayed() {
        composeTestRule.setContent {
            SettingsOutlinedTextField(
                value = enabledValue,
                label = outlinedTextFieldLabel,
                enabled = true,
                onTextChange = {})
        }
        composeTestRule.onNodeWithText(outlinedTextFieldLabel, substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun outlinedTextFields_enabled() {
        composeTestRule.setContent {
            SettingsOutlinedTextField(
                value = enabledValue,
                label = outlinedTextFieldLabel,
                enabled = true,
                onTextChange = {})
        }
        composeTestRule.onNodeWithText(outlinedTextFieldLabel, substring = true)
            .assertIsEnabled()
    }

    @Test
    fun outlinedTextFields_disabled() {
        composeTestRule.setContent {
            SettingsOutlinedTextField(
                value = disabledValue,
                label = outlinedTextFieldLabel,
                enabled = false,
                onTextChange = {})
        }
        composeTestRule.onNodeWithText(outlinedTextFieldLabel, substring = true)
            .assertIsNotEnabled()
    }

    @Test
    fun outlinedTextFields_inputValue() {
        composeTestRule.setContent {
            var value by remember { mutableStateOf(enabledValue) }
            SettingsOutlinedTextField(
                value = value,
                label = outlinedTextFieldLabel,
                enabled = true,
                onTextChange = { value = it })
        }
        composeTestRule.onNodeWithText(outlinedTextFieldLabel, substring = true)
            .performTextReplacement(valueChanged)
        composeTestRule.onNodeWithText(outlinedTextFieldLabel, substring = true)
            .assertTextContains(valueChanged)
    }
}
