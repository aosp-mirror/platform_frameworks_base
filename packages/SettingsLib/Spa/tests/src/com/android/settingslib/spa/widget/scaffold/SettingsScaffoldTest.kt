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

import android.app.Activity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.kotlin.verify

@RunWith(AndroidJUnit4::class)
class SettingsScaffoldTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock
    private lateinit var activity: Activity

    @Test
    fun settingsScaffold_titleIsDisplayed() {
        composeTestRule.setContent {
            SettingsScaffold(title = TITLE) {
                Text(text = "AAA")
                Text(text = "BBB")
            }
        }

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun settingsScaffold_itemsAreDisplayed() {
        composeTestRule.setContent {
            SettingsScaffold(title = TITLE) {
                Text(text = "AAA")
                Text(text = "BBB")
            }
        }

        composeTestRule.onNodeWithText("AAA").assertIsDisplayed()
        composeTestRule.onNodeWithText("BBB").assertIsDisplayed()
    }

    @Test
    fun settingsScaffold_noHorizontalPadding() {
        lateinit var actualPaddingValues: PaddingValues

        composeTestRule.setContent {
            SettingsScaffold(title = TITLE) { paddingValues ->
                SideEffect {
                    actualPaddingValues = paddingValues
                }
            }
        }

        assertThat(actualPaddingValues.calculateLeftPadding(LayoutDirection.Ltr)).isEqualTo(0.dp)
        assertThat(actualPaddingValues.calculateLeftPadding(LayoutDirection.Rtl)).isEqualTo(0.dp)
        assertThat(actualPaddingValues.calculateRightPadding(LayoutDirection.Ltr)).isEqualTo(0.dp)
        assertThat(actualPaddingValues.calculateRightPadding(LayoutDirection.Rtl)).isEqualTo(0.dp)
    }

    @Test
    fun activityTitle() {
        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides activity) {
                ActivityTitle(title = TITLE)
            }
        }

        verify(activity).title = TITLE
    }

    private companion object {
        const val TITLE = "Title"
    }
}
