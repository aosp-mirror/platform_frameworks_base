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

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.onDialogText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ListPreferenceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_displayed() {
        composeTestRule.setContent {
            ListPreference(remember {
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
    fun summary_showSelectedText() {
        composeTestRule.setContent {
            ListPreference(remember {
                object : ListPreferenceModel {
                    override val title = TITLE
                    override val options = listOf(ListPreferenceOption(id = 1, text = "A"))
                    override val selectedId = mutableIntStateOf(1)
                    override val onIdSelected: (Int) -> Unit = {}
                }
            })
        }

        composeTestRule.onNodeWithText("A").assertIsDisplayed()
    }

    @Test
    fun click_optionsIsEmpty_notShowDialog() {
        composeTestRule.setContent {
            ListPreference(remember {
                object : ListPreferenceModel {
                    override val title = TITLE
                    override val options = emptyList<ListPreferenceOption>()
                    override val selectedId = mutableIntStateOf(0)
                    override val onIdSelected: (Int) -> Unit = {}
                }
            })
        }

        composeTestRule.onNodeWithText(TITLE).performClick()

        composeTestRule.onDialogText(TITLE).assertDoesNotExist()
    }

    @Test
    fun click_notEnabled_notShowDialog() {
        composeTestRule.setContent {
            ListPreference(remember {
                object : ListPreferenceModel {
                    override val title = TITLE
                    override val enabled = { false }
                    override val options = listOf(ListPreferenceOption(id = 1, text = "A"))
                    override val selectedId = mutableIntStateOf(1)
                    override val onIdSelected: (Int) -> Unit = {}
                }
            })
        }

        composeTestRule.onNodeWithText(TITLE).performClick()

        composeTestRule.onDialogText(TITLE).assertDoesNotExist()
    }

    @Test
    fun click_optionsNotEmpty_showDialog() {
        composeTestRule.setContent {
            ListPreference(remember {
                object : ListPreferenceModel {
                    override val title = TITLE
                    override val options = listOf(ListPreferenceOption(id = 1, text = "A"))
                    override val selectedId = mutableIntStateOf(1)
                    override val onIdSelected: (Int) -> Unit = {}
                }
            })
        }

        composeTestRule.onNodeWithText(TITLE).performClick()

        composeTestRule.onDialogText(TITLE).assertIsDisplayed()
    }

    @Test
    fun select() {
        val selectedId = mutableIntStateOf(1)
        composeTestRule.setContent {
            ListPreference(remember {
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

        composeTestRule.onNodeWithText(TITLE).performClick()
        composeTestRule.onDialogText("B").performClick()

        composeTestRule.onNodeWithText("B").assertIsDisplayed()
    }

    @Test
    fun select_dialogOpenThenDisable_itemAlsoDisabled() {
        val selectedId = mutableIntStateOf(1)
        val enabledState = mutableStateOf(true)
        composeTestRule.setContent {
            ListPreference(remember {
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

        composeTestRule.onNodeWithText(TITLE).performClick()
        enabledState.value = false

        composeTestRule.onDialogText("B").assertIsDisplayed().assertIsNotEnabled()
    }

    private companion object {
        const val TITLE = "Title"
    }
}
