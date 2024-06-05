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

package com.android.settingslib.spa.widget.preference

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_MODEL_TITLE = "TwoTargetButtonPreference"
private const val TEST_MODEL_SUMMARY = "TestSummary"
private const val TEST_BUTTON_ICON_DESCRIPTION = "TestButtonIconDescription"
private val TEST_BUTTON_ICON = Icons.Outlined.Delete

@RunWith(AndroidJUnit4::class)
class TwoTargetButtonPreferenceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_displayed() {
        composeTestRule.setContent {
            testTwoTargetButtonPreference()
        }

        composeTestRule.onNodeWithText(TEST_MODEL_TITLE).assertIsDisplayed()
    }

    @Test
    fun clickable_label_canBeClicked() {
        var clicked = false
        composeTestRule.setContent {
            testTwoTargetButtonPreference(onClick = { clicked = true })
        }

        composeTestRule.onNodeWithText(TEST_MODEL_TITLE).performClick()
        Truth.assertThat(clicked).isTrue()
    }

    @Test
    fun clickable_button_label_canBeClicked() {
        var clicked = false
        composeTestRule.setContent {
            testTwoTargetButtonPreference(onButtonClick = { clicked = true })
        }

        composeTestRule.onNodeWithContentDescription(TEST_BUTTON_ICON_DESCRIPTION).performClick()
        Truth.assertThat(clicked).isTrue()
    }
}

@Composable
private fun testTwoTargetButtonPreference(
    onClick: () -> Unit = {},
    onButtonClick: () -> Unit = {},
) {
    TwoTargetButtonPreference(
        title = TEST_MODEL_TITLE,
        summary = { TEST_MODEL_SUMMARY },
        onClick = onClick,
        buttonIcon = TEST_BUTTON_ICON,
        buttonIconDescription = TEST_BUTTON_ICON_DESCRIPTION,
        onButtonClick = onButtonClick
    )
}
