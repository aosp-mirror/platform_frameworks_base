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

package com.android.settingslib.spa.widget.banner

import android.content.Context
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Error
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsCollapsibleBannerTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun settingsCollapsibleBanner_titleDisplayed() {
        setContent()

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun settingsCollapsibleBanner_BannerCountDisplayed() {
        setContent()

        composeTestRule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun settingsCollapsibleBanner_initial_BannerTextNotExists() {
        setContent()

        composeTestRule.onNodeWithText(Banner_TEXT).assertDoesNotExist()
    }

    @Test
    fun settingsCollapsibleBanner_afterExpand_BannerTextDisplayed() {
        setContent()

        composeTestRule.onNodeWithText(TITLE).performClick()

        composeTestRule.onNodeWithText(Banner_TEXT).assertIsDisplayed()
    }

    @Test
    fun settingsCollapsibleBanner_dismiss() {
        setContent()
        composeTestRule.onNodeWithText(TITLE).performClick()

        composeTestRule.onNodeWithContentDescription(
            context.getString(androidx.compose.material3.R.string.m3c_snackbar_dismiss)
        ).performClick()

        composeTestRule.onNodeWithText(Banner_TEXT).isNotDisplayed()
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
    }

    private fun setContent() {
        composeTestRule.setContent {
            var isVisible by rememberSaveable { mutableStateOf(true) }
            SettingsCollapsibleBanner(
                title = TITLE,
                imageVector = Icons.Outlined.Error,
                models = listOf(
                    BannerModel(
                        title = "",
                        text = Banner_TEXT,
                        isVisible = { isVisible },
                        onDismiss = { isVisible = false },
                    )
                ),
            )
        }
    }

    private companion object {
        const val TITLE = "Title"
        const val Banner_TEXT = "Banner Text"
    }
}
