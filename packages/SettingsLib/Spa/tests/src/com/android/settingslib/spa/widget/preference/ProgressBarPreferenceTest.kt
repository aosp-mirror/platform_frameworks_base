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
import androidx.compose.material.icons.automirrored.outlined.Launch
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProgressBarPreferenceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_displayed() {
        composeTestRule.setContent {
            ProgressBarPreference(object : ProgressBarPreferenceModel {
                override val title = "Title"
                override val progress = 0.2f
            })
        }
        composeTestRule.onNodeWithText("Title").assertIsDisplayed()
    }

    @Test
    fun data_displayed() {
        composeTestRule.setContent {
            ProgressBarWithDataPreference(
                model = object : ProgressBarPreferenceModel {
                    override val title = "Title"
                    override val progress = 0.2f
                    override val icon = Icons.AutoMirrored.Outlined.Launch
                },
                data = "Data",
            )
        }
        composeTestRule.onNodeWithText("Title").assertIsDisplayed()
        composeTestRule.onNodeWithText("Data").assertIsDisplayed()
    }

    @Test
    fun progressBar_displayed() {
        composeTestRule.setContent {
            ProgressBarPreference(object : ProgressBarPreferenceModel {
                override val title = "Title"
                override val progress = 0.2f
            })
        }

        fun progressEqualsTo(progress: Float): SemanticsMatcher =
            SemanticsMatcher.expectValue(
                ProgressBarRangeInfo,
                ProgressBarRangeInfo(progress, 0f..1f, 0)
            )
        composeTestRule.onNode(progressEqualsTo(0.2f)).assertIsDisplayed()
    }
}
