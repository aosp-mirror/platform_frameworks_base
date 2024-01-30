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

package com.android.settingslib.spa.widget.scaffold

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SignalCellularAlt
import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SuwScaffoldTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun suwScaffold_titleIsDisplayed() {
        composeTestRule.setContent {
            SuwScaffold(imageVector = Icons.Outlined.SignalCellularAlt, title = TITLE) {
                Text(text = "AAA")
                Text(text = "BBB")
            }
        }

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun suwScaffold_itemsAreDisplayed() {
        composeTestRule.setContent {
            SuwScaffold(imageVector = Icons.Outlined.SignalCellularAlt, title = TITLE) {
                Text(text = "AAA")
                Text(text = "BBB")
            }
        }

        composeTestRule.onNodeWithText("AAA").assertIsDisplayed()
        composeTestRule.onNodeWithText("BBB").assertIsDisplayed()
    }

    @Test
    fun suwScaffold_actionButtonDisplayed() {
        composeTestRule.setContent {
            SuwScaffold(
                imageVector = Icons.Outlined.SignalCellularAlt,
                title = TITLE,
                actionButton = BottomAppBarButton(TEXT) {},
            ) {}
        }

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed()
    }

    @Test
    fun suwScaffold_dismissButtonDisplayed() {
        composeTestRule.setContent {
            SuwScaffold(
                imageVector = Icons.Outlined.SignalCellularAlt,
                title = TITLE,
                dismissButton = BottomAppBarButton(TEXT) {},
            ) {}
        }

        composeTestRule.onNodeWithText(TEXT).assertIsDisplayed()
    }

    private companion object {
        const val TITLE = "Title"
        const val TEXT = "Text"
    }
}
