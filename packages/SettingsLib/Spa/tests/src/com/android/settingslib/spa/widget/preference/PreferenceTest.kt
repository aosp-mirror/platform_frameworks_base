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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PreferenceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_displayed() {
        composeTestRule.setContent {
            Preference(object : PreferenceModel {
                override val title = TITLE
            })
        }

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun longSummary_notSingleLine_atLeastTwoLinesHeight() {
        var lineHeightDp: Dp = Dp.Unspecified

        composeTestRule.setContent {
            Box(Modifier.width(BOX_WIDTH)) {
                Preference(object : PreferenceModel {
                    override val title = TITLE
                    override val summary = { LONG_SUMMARY }
                })
            }
            lineHeightDp = with(LocalDensity.current) {
                MaterialTheme.typography.bodyMedium.lineHeight.toDp()
            }
        }

        composeTestRule.onNodeWithText(LONG_SUMMARY).assertHeightIsAtLeast(lineHeightDp.times(2))
    }

    @Test
    fun longSummary_notSingleLine_onlyOneLineHeight() {
        var lineHeightDp: Dp = Dp.Unspecified

        composeTestRule.setContent {
            Box(Modifier.width(BOX_WIDTH)) {
                Preference(
                    model = object : PreferenceModel {
                        override val title = TITLE
                        override val summary = { LONG_SUMMARY }
                    },
                    singleLineSummary = true,
                )
            }
            lineHeightDp = with(LocalDensity.current) {
                MaterialTheme.typography.bodyMedium.lineHeight.toDp()
            }
        }

        try {
            // There is no assertHeightIsAtMost, so use the assertHeightIsAtLeast and catch the
            // expected exception.
            composeTestRule.onRoot().assertHeightIsAtLeast(lineHeightDp.times(5))
        } catch (e: AssertionError) {
            assertThat(e).hasMessageThat().contains("height")
            return
        }
        fail("Expect AssertionError")
    }

    @Test
    fun click_enabled_withEffect() {
        composeTestRule.setContent {
            var count by remember { mutableStateOf(0) }
            Preference(object : PreferenceModel {
                override val title = TITLE
                override val summary = { count.toString() }
                override val onClick: (() -> Unit) = { count++ }
            })
        }

        composeTestRule.onNodeWithText(TITLE).performClick()
        composeTestRule.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun click_disabled_noEffect() {
        composeTestRule.setContent {
            var count by remember { mutableStateOf(0) }
            Preference(object : PreferenceModel {
                override val title = TITLE
                override val summary = { count.toString() }
                override val enabled = { false }
                override val onClick: (() -> Unit) = { count++ }
            })
        }

        composeTestRule.onNodeWithText(TITLE).performClick()
        composeTestRule.onNodeWithText("0").assertIsDisplayed()
    }

    companion object {
        private const val TITLE = "Title"
        private const val LONG_SUMMARY =
            "Long long long long long long long long long long long long long long long summary"
        private val BOX_WIDTH = 100.dp
    }
}
