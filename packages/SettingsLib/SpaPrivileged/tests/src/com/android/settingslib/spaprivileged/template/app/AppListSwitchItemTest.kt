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

package com.android.settingslib.spaprivileged.template.app

import android.content.pm.ApplicationInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spaprivileged.model.app.AppRecord
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppListSwitchItemTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun appLabel_displayed() {
        composeTestRule.setContent {
            ITEM_MODEL.AppListSwitchItem(
                onClick = {},
                checked = stateOf(null),
                changeable = stateOf(false),
                onCheckedChange = {},
            )
        }

        composeTestRule.onNodeWithText(LABEL).assertIsDisplayed()
    }

    @Test
    fun summary_displayed() {
        composeTestRule.setContent {
            ITEM_MODEL.AppListSwitchItem(
                onClick = {},
                checked = stateOf(null),
                changeable = stateOf(false),
                onCheckedChange = {},
            )
        }

        composeTestRule.onNodeWithText(SUMMARY).assertIsDisplayed()
    }

    @Test
    fun title_onClick() {
        var titleClicked = false
        composeTestRule.setContent {
            ITEM_MODEL.AppListSwitchItem(
                onClick = { titleClicked = true },
                checked = stateOf(false),
                changeable = stateOf(false),
                onCheckedChange = {},
            )
        }

        composeTestRule.onNodeWithText(LABEL).performClick()

        assertThat(titleClicked).isTrue()
    }

    @Test
    fun switch_checkIsNull() {
        composeTestRule.setContent {
            ITEM_MODEL.AppListSwitchItem(
                onClick = {},
                checked = stateOf(null),
                changeable = stateOf(false),
                onCheckedChange = {},
            )
        }

        composeTestRule.onNode(isToggleable()).assertDoesNotExist()
    }

    @Test
    fun switch_checked() {
        composeTestRule.setContent {
            ITEM_MODEL.AppListSwitchItem(
                onClick = {},
                checked = stateOf(true),
                changeable = stateOf(false),
                onCheckedChange = {},
            )
        }

        composeTestRule.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun switch_notChecked() {
        composeTestRule.setContent {
            ITEM_MODEL.AppListSwitchItem(
                onClick = {},
                checked = stateOf(false),
                changeable = stateOf(false),
                onCheckedChange = {},
            )
        }

        composeTestRule.onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun switch_changeable() {
        composeTestRule.setContent {
            ITEM_MODEL.AppListSwitchItem(
                onClick = {},
                checked = stateOf(false),
                changeable = stateOf(true),
                onCheckedChange = {},
            )
        }

        composeTestRule.onNode(isToggleable()).assertIsEnabled()
    }

    @Test
    fun switch_notChangeable() {
        composeTestRule.setContent {
            ITEM_MODEL.AppListSwitchItem(
                onClick = {},
                checked = stateOf(false),
                changeable = stateOf(false),
                onCheckedChange = {},
            )
        }

        composeTestRule.onNode(isToggleable()).assertIsNotEnabled()
    }

    @Test
    fun switch_onClick() {
        var switchClicked = false
        composeTestRule.setContent {
            ITEM_MODEL.AppListSwitchItem(
                onClick = {},
                checked = stateOf(false),
                changeable = stateOf(true),
                onCheckedChange = { switchClicked = true },
            )
        }

        composeTestRule.onNode(isToggleable()).performClick()

        assertThat(switchClicked).isTrue()
    }

    private companion object {
        const val PACKAGE_NAME = "package.name"
        const val LABEL = "Label"
        const val SUMMARY = "Summary"
        val APP = ApplicationInfo().apply {
            packageName = PACKAGE_NAME
        }
        val ITEM_MODEL = AppListItemModel(
            record = object : AppRecord {
                override val app = APP
            },
            label = LABEL,
            summary = stateOf(SUMMARY),
        )
    }
}
