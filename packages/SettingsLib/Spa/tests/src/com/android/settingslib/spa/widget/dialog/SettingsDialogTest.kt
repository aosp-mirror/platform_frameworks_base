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

package com.android.settingslib.spa.widget.dialog

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.onDialogText
import com.android.settingslib.spa.widget.ui.SettingsDialogItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_displayed() {
        composeTestRule.setContent {
            SettingsDialog(title = TITLE, onDismissRequest = {}) {}
        }

        composeTestRule.onDialogText(TITLE).assertIsDisplayed()
    }

    @Test
    fun text_displayed() {
        composeTestRule.setContent {
            SettingsDialog(title = "", onDismissRequest = {}) {
                SettingsDialogItem(text = TEXT)
            }
        }

        composeTestRule.onDialogText(TEXT).assertIsDisplayed()
    }

    private companion object {
        const val TITLE = "Title"
        const val TEXT = "Text"
    }
}
