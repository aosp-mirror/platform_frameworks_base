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

package com.android.settingslib.spa.widget.editor

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.testutils.hasRole
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsDropdownCheckBoxTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun dropdownCheckBox_displayed() {
        val item1 = SettingsDropdownCheckOption("item1")
        val item2 = SettingsDropdownCheckOption("item2")
        val item3 = SettingsDropdownCheckOption("item3")
        composeTestRule.setContent {
            SettingsDropdownCheckBox(
                label = LABEL,
                options = listOf(item1, item2, item3),
            )
        }

        composeTestRule.onNodeWithText(LABEL).assertIsDisplayed()
    }

    @Test
    fun dropdownCheckBox_expanded() {
        val item1 = SettingsDropdownCheckOption("item1")
        val item2 = SettingsDropdownCheckOption("item2")
        val item3 = SettingsDropdownCheckOption("item3")
        composeTestRule.setContent {
            SettingsDropdownCheckBox(
                label = LABEL,
                options = listOf(item1, item2, item3),
            )
        }
        composeTestRule.onOption(item3).assertDoesNotExist()

        composeTestRule.onNodeWithText(LABEL).performClick()

        composeTestRule.onOption(item3).assertIsDisplayed()
    }

    @Test
    fun dropdownCheckBox_valueAdded() {
        val item1 = SettingsDropdownCheckOption("item1")
        val item2 = SettingsDropdownCheckOption("item2")
        val item3 = SettingsDropdownCheckOption("item3")
        composeTestRule.setContent {
            SettingsDropdownCheckBox(
                label = LABEL,
                options = listOf(item1, item2, item3),
            )
        }
        composeTestRule.onDropdownBox(item3.text).assertDoesNotExist()

        composeTestRule.onNodeWithText(LABEL).performClick()
        composeTestRule.onOption(item3).performClick()

        composeTestRule.onDropdownBox(item3.text).assertIsDisplayed()
        assertThat(item3.selected.value).isTrue()
    }

    @Test
    fun dropdownCheckBox_valueDeleted() {
        val item1 = SettingsDropdownCheckOption("item1")
        val item2 = SettingsDropdownCheckOption("item2", selected = mutableStateOf(true))
        val item3 = SettingsDropdownCheckOption("item3")
        composeTestRule.setContent {
            SettingsDropdownCheckBox(
                label = LABEL,
                options = listOf(item1, item2, item3),
            )
        }
        composeTestRule.onDropdownBox(item2.text).assertIsDisplayed()

        composeTestRule.onNodeWithText(LABEL).performClick()
        composeTestRule.onOption(item2).performClick()

        composeTestRule.onDropdownBox(item2.text).assertDoesNotExist()
        assertThat(item2.selected.value).isFalse()
    }

    @Test
    fun dropdownCheckBox_withSelectAll() {
        val selectAll = SettingsDropdownCheckOption("All", isSelectAll = true)
        val item1 = SettingsDropdownCheckOption("item1")
        val item2 = SettingsDropdownCheckOption("item2")
        composeTestRule.setContent {
            SettingsDropdownCheckBox(
                label = LABEL,
                options = listOf(selectAll, item1, item2),
            )
        }

        composeTestRule.onNodeWithText(LABEL).performClick()
        composeTestRule.onOption(selectAll).performClick()

        composeTestRule.onDropdownBox(selectAll.text).assertIsDisplayed()
        composeTestRule.onDropdownBox(item1.text).assertDoesNotExist()
        composeTestRule.onDropdownBox(item2.text).assertDoesNotExist()
        assertThat(item1.selected.value).isTrue()
        assertThat(item2.selected.value).isTrue()
    }

    private companion object {
        const val LABEL = "Label"
    }
}

private fun ComposeContentTestRule.onDropdownBox(text: String) =
    onNode(hasRole(Role.DropdownList) and hasText(text))

private fun ComposeContentTestRule.onOption(option: SettingsDropdownCheckOption) =
    onNode(hasAnyAncestor(isPopup()) and hasText(option.text))
