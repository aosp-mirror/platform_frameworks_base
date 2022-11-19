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

import android.content.Context
import androidx.appcompat.R
import androidx.compose.ui.test.assertIsDisplayed
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
class MoreOptionsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun moreOptionsAction_collapseAtBegin() {
        composeTestRule.setContent {
            MoreOptionsAction {
                MenuItem(text = ITEM_TEXT) {}
            }
        }

        composeTestRule.onNodeWithText(ITEM_TEXT).assertDoesNotExist()
    }

    @Test
    fun moreOptionsAction_canExpand() {
        composeTestRule.setContent {
            MoreOptionsAction {
                MenuItem(text = ITEM_TEXT) {}
            }
        }
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.abc_action_menu_overflow_description)
        ).performClick()

        composeTestRule.onNodeWithText(ITEM_TEXT).assertIsDisplayed()
    }

    @Test
    fun moreOptionsAction_itemClicked() {
        var menuItemClicked = false

        composeTestRule.setContent {
            MoreOptionsAction {
                MenuItem(text = ITEM_TEXT) {
                    menuItemClicked = true
                }
            }
        }
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.abc_action_menu_overflow_description)
        ).performClick()
        composeTestRule.onNodeWithText(ITEM_TEXT).performClick()

        assertThat(menuItemClicked).isTrue()
    }

    private companion object {
        const val ITEM_TEXT = "item text"
    }
}
