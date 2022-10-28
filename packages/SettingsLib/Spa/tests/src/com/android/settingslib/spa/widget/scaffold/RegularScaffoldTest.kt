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

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RegularScaffoldTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun regularScaffold_titleIsDisplayed() {
        composeTestRule.setContent {
            RegularScaffold(title = TITLE) {
                Text(text = "AAA")
                Text(text = "BBB")
            }
        }

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun regularScaffold_itemsAreDisplayed() {
        composeTestRule.setContent {
            RegularScaffold(title = TITLE) {
                Text(text = "AAA")
                Text(text = "BBB")
            }
        }

        composeTestRule.onNodeWithText("AAA").assertIsDisplayed()
        composeTestRule.onNodeWithText("BBB").assertIsDisplayed()
    }

    private companion object {
        const val TITLE = "title"
    }
}
