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

package com.android.settingslib.spa.widget.preference

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
class SliderPreferenceTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun title_displayed() {
        composeTestRule.setContent {
            SliderPreference(object : SliderPreferenceModel {
                override val title = "Slider"
                override val initValue = 40
            })
        }

        composeTestRule.onNodeWithText("Slider").assertIsDisplayed()
    }

    @Test
    fun slider_displayed() {
        composeTestRule.setContent {
            SliderPreference(object : SliderPreferenceModel {
                override val title = "Slider"
                override val initValue = 40
            })
        }

        fun progressEqualsTo(progress: Float): SemanticsMatcher =
            SemanticsMatcher.expectValue(
                ProgressBarRangeInfo,
                ProgressBarRangeInfo(progress, 0f..100f, 0)
            )
        composeTestRule.onNode(progressEqualsTo(40f)).assertIsDisplayed()
    }
}
