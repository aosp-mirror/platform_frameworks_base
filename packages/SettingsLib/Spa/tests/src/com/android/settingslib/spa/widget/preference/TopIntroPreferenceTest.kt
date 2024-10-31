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

package com.android.settingslib.spa.widget.preference

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.test.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TopIntroPreferenceTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun content_collapsed_displayed() {
        composeTestRule.setContent {
            TopIntroPreference(
                object : TopIntroPreferenceModel {
                    override val text = TEXT
                    override val expandText = EXPAND_TEXT
                    override val collapseText = COLLAPSE_TEXT
                    override val labelText = R.string.test_top_intro_preference_label
                }
            )
        }

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed()
        composeTestRule.onNodeWithText(EXPAND_TEXT).assertIsDisplayed()
    }

    @Test
    fun content_expended_displayed() {
        composeTestRule.setContent {
            TopIntroPreference(
                object : TopIntroPreferenceModel {
                    override val text = TEXT
                    override val expandText = EXPAND_TEXT
                    override val collapseText = COLLAPSE_TEXT
                    override val labelText = R.string.test_top_intro_preference_label
                }
            )
        }

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed()
        composeTestRule.onNodeWithText(EXPAND_TEXT).assertIsDisplayed().performClick()
        composeTestRule.onNodeWithText(COLLAPSE_TEXT).assertIsDisplayed()
        composeTestRule.onNodeWithText(LABEL_TEXT).assertIsDisplayed()
    }

    private companion object {
        const val TEXT = "Text"
        const val EXPAND_TEXT = "Expand"
        const val COLLAPSE_TEXT = "Collapse"
        const val LABEL_TEXT = "Label"
    }
}
