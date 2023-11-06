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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TwoTargetSwitchPreferenceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_displayed() {
        composeTestRule.setContent {
            TestTwoTargetSwitchPreference(changeable = true)
        }

        composeTestRule.onNodeWithText("TwoTargetSwitchPreference").assertIsDisplayed()
    }

    @Test
    fun toggleable_initialStateIsCorrect() {
        composeTestRule.setContent {
            TestTwoTargetSwitchPreference(changeable = true)
        }

        composeTestRule.onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun toggleable_changeable_withEffect() {
        composeTestRule.setContent {
            TestTwoTargetSwitchPreference(changeable = true)
        }

        composeTestRule.onNode(isToggleable()).performClick()
        composeTestRule.onNode(isToggleable()).assertIsOn()
    }

    @Test
    fun toggleable_notChangeable_noEffect() {
        composeTestRule.setContent {
            TestTwoTargetSwitchPreference(changeable = false)
        }

        composeTestRule.onNode(isToggleable()).performClick()
        composeTestRule.onNode(isToggleable()).assertIsOff()
    }

    @Test
    fun clickable_canBeClick() {
        var clicked = false
        composeTestRule.setContent {
            TestTwoTargetSwitchPreference(changeable = false) {
                clicked = true
            }
        }

        composeTestRule.onNodeWithText("TwoTargetSwitchPreference").performClick()
        assertThat(clicked).isTrue()
    }
}

@Composable
private fun TestTwoTargetSwitchPreference(
    changeable: Boolean,
    onClick: () -> Unit = {},
) {
    var checked by rememberSaveable { mutableStateOf(false) }
    TwoTargetSwitchPreference(
        model = remember {
            object : SwitchPreferenceModel {
                override val title = "TwoTargetSwitchPreference"
                override val checked = { checked }
                override val changeable = { changeable }
                override val onCheckedChange = { newChecked: Boolean -> checked = newChecked }
            }
        },
        onClick = onClick,
    )
}
