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

import android.content.Context
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CopyableBodyTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun text_isDisplayed() {
        composeTestRule.setContent {
            CopyableBody(TEXT)
        }

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed()
    }

    @Test
    fun onLongPress_contextMenuDisplayed() {
        composeTestRule.setContent {
            CopyableBody(TEXT)
        }

        composeTestRule.onNodeWithText(TEXT).performTouchInput {
            longClick()
        }

        composeTestRule.onNodeWithText(context.getString(android.R.string.copy)).assertIsDisplayed()
    }

    @Test
    fun onCopy_saveToClipboard() {
        var clipboardManager: ClipboardManager? = null
        composeTestRule.setContent {
            clipboardManager = LocalClipboardManager.current
            CopyableBody(TEXT)
        }

        composeTestRule.onNodeWithText(TEXT).performTouchInput {
            longClick()
        }
        composeTestRule.onNodeWithText(context.getString(android.R.string.copy)).performClick()

        assertThat(clipboardManager?.getText()?.text).isEqualTo(TEXT)
    }

    private companion object {
        const val TEXT = "Text"
    }
}
