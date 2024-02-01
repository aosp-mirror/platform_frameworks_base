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

package com.android.settingslib.spa.widget.card

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsCardTest {
    @get:Rule val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun settingsCard_titleDisplayed() {
        composeTestRule.setContent {
            SettingsCard(
                CardModel(
                    title = TITLE,
                    text = "",
                )
            )
        }

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun settingsCard_textDisplayed() {
        composeTestRule.setContent {
            SettingsCard(
                CardModel(
                    title = "",
                    text = TEXT,
                )
            )
        }

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed()
    }

    @Test
    fun settingsCard_buttonDisplayed() {
        composeTestRule.setContent {
            SettingsCard(
                CardModel(
                    title = "",
                    text = "",
                    buttons = listOf(CardButton(text = TEXT) {}),
                )
            )
        }

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed()
    }

    @Test
    fun settingsCard_buttonCanBeClicked() {
        var buttonClicked = false
        composeTestRule.setContent {
            SettingsCard(
                CardModel(
                    title = "",
                    text = "",
                    buttons = listOf(CardButton(text = TEXT) { buttonClicked = true }),
                )
            )
        }

        composeTestRule.onNodeWithText(TEXT).performClick()

        assertThat(buttonClicked).isTrue()
    }

    @Test
    fun settingsCard_buttonHaveContentDescription() {
        composeTestRule.setContent {
            SettingsCard(
                CardModel(
                    title = "",
                    text = "",
                    buttons = listOf(CardButton(
                        text = TEXT,
                        contentDescription = CONTENT_DESCRIPTION,
                        ) {}
                    ),
                )
            )
        }

        composeTestRule.onNodeWithContentDescription(CONTENT_DESCRIPTION).assertIsDisplayed()
    }

    @Test
    fun settingsCard_dismiss() {
        composeTestRule.setContent {
            var isVisible by remember { mutableStateOf(true) }
            SettingsCard(
                CardModel(
                    title = TITLE,
                    text = "",
                    isVisible = { isVisible },
                    onDismiss = { isVisible = false },
                )
            )
        }

        composeTestRule.onNodeWithContentDescription(
            context.getString(androidx.compose.material3.R.string.m3c_snackbar_dismiss)
        ).performClick()

        composeTestRule.onNodeWithText(TEXT).isNotDisplayed()
    }

    private companion object {
        const val TITLE = "Title"
        const val TEXT = "Text"
        const val CONTENT_DESCRIPTION = "content-description"
    }
}
