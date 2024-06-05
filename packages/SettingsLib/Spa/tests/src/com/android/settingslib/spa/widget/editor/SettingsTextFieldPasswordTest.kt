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
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsTextFieldPasswordTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    private val label = "label"
    private val value = "value"
    private val valueChanged = "Value Changed"
    private val visibilityIconTag = "Visibility Icon"

    @Test
    fun textFieldPassword_displayed() {
        composeTestRule.setContent {
            SettingsTextFieldPassword(
                value = value,
                label = label,
                onTextChange = {})
        }
        composeTestRule.onNodeWithText(label, substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun textFieldPassword_invisible() {
        composeTestRule.setContent {
            var value by remember { mutableStateOf(value) }
            SettingsTextFieldPassword(
                value = value,
                label = label,
                onTextChange = { value = it })
        }
        composeTestRule.onNodeWithText(value, substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun textFieldPassword_visible_inputValue() {
        composeTestRule.setContent {
            var value by remember { mutableStateOf(value) }
            SettingsTextFieldPassword(
                value = value,
                label = label,
                onTextChange = { value = it })
        }
        composeTestRule.onNodeWithTag(visibilityIconTag)
            .performClick()
        composeTestRule.onNodeWithText(label, substring = true)
            .performTextReplacement(valueChanged)
        composeTestRule.onNodeWithText(label, substring = true)
            .assertTextContains(valueChanged)
    }
}