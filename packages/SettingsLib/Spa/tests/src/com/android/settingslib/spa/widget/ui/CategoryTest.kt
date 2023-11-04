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

package com.android.settingslib.spa.widget.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun categoryTitle() {
        composeTestRule.setContent {
            CategoryTitle(title = "CategoryTitle")
        }

        composeTestRule.onNodeWithText("CategoryTitle").assertIsDisplayed()
    }

    @Test
    fun category_hasContent_titleDisplayed() {
        composeTestRule.setContent {
            Category(title = "CategoryTitle") {
                Preference(remember {
                    object : PreferenceModel {
                        override val title = "Some Preference"
                        override val summary = { "Some summary" }
                    }
                })
            }
        }

        composeTestRule.onNodeWithText("CategoryTitle").assertIsDisplayed()
    }

    @Test
    fun category_noContent_titleNotDisplayed() {
        composeTestRule.setContent {
            Category(title = "CategoryTitle") {}
        }

        composeTestRule.onNodeWithText("CategoryTitle").assertDoesNotExist()
    }
}
