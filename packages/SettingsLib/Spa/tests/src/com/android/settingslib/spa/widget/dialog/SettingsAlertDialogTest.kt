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

package com.android.settingslib.spa.widget.dialog

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.onDialogText
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsAlertDialogTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_whenDialogNotOpen_notDisplayed() {
        composeTestRule.setContent {
            rememberAlertDialogPresenter(title = TITLE)
        }

        composeTestRule.onDialogText(TITLE).assertDoesNotExist()
    }

    @Test
    fun title_displayed() {
        setAndOpenDialog {
            rememberAlertDialogPresenter(title = TITLE)
        }

        composeTestRule.onDialogText(TITLE).assertIsDisplayed()
    }

    @Test
    fun text_displayed() {
        setAndOpenDialog {
            rememberAlertDialogPresenter(text = { Text(TEXT) })
        }

        composeTestRule.onDialogText(TEXT).assertIsDisplayed()
    }

    @Test
    fun confirmButton_displayed() {
        setAndOpenDialog {
            rememberAlertDialogPresenter(confirmButton = AlertDialogButton(CONFIRM_TEXT))
        }

        composeTestRule.onDialogText(CONFIRM_TEXT).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun confirmButton_disabled() {
        setAndOpenDialog {
            rememberAlertDialogPresenter(
                confirmButton = AlertDialogButton(text = CONFIRM_TEXT, enabled = false)
            )
        }

        composeTestRule.onDialogText(CONFIRM_TEXT).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun confirmButton_clickable() {
        var confirmButtonClicked = false
        setAndOpenDialog {
            rememberAlertDialogPresenter(confirmButton = AlertDialogButton(CONFIRM_TEXT) {
                confirmButtonClicked = true
            })
        }

        composeTestRule.onDialogText(CONFIRM_TEXT).performClick()

        assertThat(confirmButtonClicked).isTrue()
    }

    @Test
    fun dismissButton_displayed() {
        setAndOpenDialog {
            rememberAlertDialogPresenter(dismissButton = AlertDialogButton(DISMISS_TEXT))
        }

        composeTestRule.onDialogText(DISMISS_TEXT).assertIsDisplayed().assertIsEnabled()
    }

    @Test
    fun dismissButton_disabled() {
        setAndOpenDialog {
            rememberAlertDialogPresenter(
                dismissButton = AlertDialogButton(text = DISMISS_TEXT, enabled = false)
            )
        }

        composeTestRule.onDialogText(DISMISS_TEXT).assertIsDisplayed().assertIsNotEnabled()
    }

    @Test
    fun dismissButton_clickable() {
        var dismissButtonClicked = false
        setAndOpenDialog {
            rememberAlertDialogPresenter(dismissButton = AlertDialogButton(DISMISS_TEXT) {
                dismissButtonClicked = true
            })
        }

        composeTestRule.onDialogText(DISMISS_TEXT).performClick()

        assertThat(dismissButtonClicked).isTrue()
    }

    private fun setAndOpenDialog(dialog: @Composable () -> AlertDialogPresenter) {
        composeTestRule.setContent {
            val dialogPresenter = dialog()
            LaunchedEffect(Unit) {
                dialogPresenter.open()
            }
        }
    }

    private companion object {
        const val CONFIRM_TEXT = "Confirm"
        const val DISMISS_TEXT = "Dismiss"
        const val TITLE = "Title"
        const val TEXT = "Text"
    }
}
