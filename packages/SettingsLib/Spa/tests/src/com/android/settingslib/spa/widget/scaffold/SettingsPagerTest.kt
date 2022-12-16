/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.settingslib.spa.widget.scaffold

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.widget.ui.SettingsTitle
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsPagerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun twoPage_initialState() {
        setTwoPagesContent()

        composeTestRule.onNodeWithText("Personal").assertIsSelected()
        composeTestRule.onNodeWithText("Page 0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Work").assertIsNotSelected()
        composeTestRule.onNodeWithText("Page 1").assertDoesNotExist()
    }

    @Test
    fun twoPage_afterSwitch() {
        setTwoPagesContent()

        composeTestRule.onNodeWithText("Work").performClick()

        composeTestRule.onNodeWithText("Personal").assertIsNotSelected()
        composeTestRule.onNodeWithText("Page 0").assertIsNotDisplayed()
        composeTestRule.onNodeWithText("Work").assertIsSelected()
        composeTestRule.onNodeWithText("Page 1").assertIsDisplayed()
    }

    @Test
    fun onePage_initialState() {
        composeTestRule.setContent {
            SettingsPager(listOf("Personal")) {
                SettingsTitle(title = "Page $it")
            }
        }

        composeTestRule.onNodeWithText("Personal").assertDoesNotExist()
        composeTestRule.onNodeWithText("Page 0").assertIsDisplayed()
        composeTestRule.onNodeWithText("Page 1").assertDoesNotExist()
    }

    private fun setTwoPagesContent() {
        composeTestRule.setContent {
            SettingsPager(listOf("Personal", "Work")) {
                SettingsTitle(title = "Page $it")
            }
        }
    }
}
