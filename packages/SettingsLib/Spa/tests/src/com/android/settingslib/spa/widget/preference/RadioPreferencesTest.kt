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

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RadioPreferencesTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_displayed() {
        composeTestRule.setContent {
            RadioPreferences(remember {
                object : ListPreferenceModel {
                    override val title = TITLE
                    override val options = emptyList<ListPreferenceOption>()
                    override val selectedId = mutableIntStateOf(0)
                    override val onIdSelected: (Int) -> Unit = {}
                }
            })
        }
        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun item_displayed() {
        val selectedId = mutableIntStateOf(1)
        composeTestRule.setContent {
            RadioPreferences(remember {
                object : ListPreferenceModel {
                    override val title = TITLE
                    override val options = listOf(
                        ListPreferenceOption(id = 1, text = "A"),
                        ListPreferenceOption(id = 2, text = "B"),
                    )
                    override val selectedId = selectedId
                    override val onIdSelected = { id: Int -> selectedId.intValue = id }
                }
            })
        }
        composeTestRule.onNodeWithText("A").assertIsDisplayed()
        composeTestRule.onNodeWithText("B").assertIsDisplayed()
    }

    @Test
    fun item_selectable() {
        val selectedId = mutableIntStateOf(1)
        val enabledState = mutableStateOf(true)
        composeTestRule.setContent {
            RadioPreferences(remember {
                object : ListPreferenceModel {
                    override val title = TITLE
                    override val enabled = { enabledState.value }
                    override val options = listOf(
                        ListPreferenceOption(id = 1, text = "A"),
                        ListPreferenceOption(id = 2, text = "B"),
                    )
                    override val selectedId = selectedId
                    override val onIdSelected = { id: Int -> selectedId.intValue = id }
                }
            })
        }
        composeTestRule.onNodeWithText("A").assertIsSelectable()
        composeTestRule.onNodeWithText("B").assertIsSelectable()
    }

    @Test
    fun item_single_selected() {
        val selectedId = mutableIntStateOf(1)
        val enabledState = mutableStateOf(true)
        composeTestRule.setContent {
            RadioPreferences(remember {
                object : ListPreferenceModel {
                    override val title = TITLE
                    override val enabled = { enabledState.value }
                    override val options = listOf(
                        ListPreferenceOption(id = 1, text = "A"),
                        ListPreferenceOption(id = 2, text = "B"),
                    )
                    override val selectedId = selectedId
                    override val onIdSelected = { id: Int -> selectedId.intValue = id }
                }
            })
        }
        composeTestRule.onNodeWithText("B").assertIsSelectable()
        composeTestRule.onNodeWithText("B").performClick()
        composeTestRule.onNodeWithText("B").assertIsSelected()
        composeTestRule.onNodeWithText("A").assertIsNotSelected()
        composeTestRule.onNodeWithText("A").performClick()
        composeTestRule.onNodeWithText("A").assertIsSelected()
        composeTestRule.onNodeWithText("B").assertIsNotSelected()
    }

    @Test
    fun select_itemDisabled() {
        val selectedId = mutableIntStateOf(1)
        val enabledState = mutableStateOf(true)
        composeTestRule.setContent {
            RadioPreferences(remember {
                object : ListPreferenceModel {
                    override val title = TITLE
                    override val enabled = { enabledState.value }
                    override val options = listOf(
                        ListPreferenceOption(id = 1, text = "A"),
                        ListPreferenceOption(id = 2, text = "B"),
                    )
                    override val selectedId = selectedId
                    override val onIdSelected = { id: Int -> selectedId.intValue = id }
                }
            })
        }
        enabledState.value = false
        composeTestRule.onNodeWithText("A").assertIsDisplayed().assertIsNotEnabled()
        composeTestRule.onNodeWithText("B").assertIsDisplayed().assertIsNotEnabled()
    }

    private companion object {
        const val TITLE = "Title"
    }
}