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

package com.android.settingslib.spa.widget.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.widget.preference.Preference
import com.android.settingslib.spa.widget.preference.PreferenceModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryTest {

    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun categoryTitle() {
        composeTestRule.setContent { CategoryTitle(title = "CategoryTitle") }

        composeTestRule.onNodeWithText("CategoryTitle").assertIsDisplayed()
    }

    @Test
    fun category_hasContent_titleDisplayed() {
        composeTestRule.setContent {
            Category(title = "CategoryTitle") {
                Preference(
                    remember {
                        object : PreferenceModel {
                            override val title = "Some Preference"
                            override val summary = { "Some summary" }
                        }
                    }
                )
            }
        }

        composeTestRule.onNodeWithText("CategoryTitle").assertIsDisplayed()
    }

    @Test
    fun category_noContent_titleNotDisplayed() {
        composeTestRule.setContent { Category(title = "CategoryTitle") {} }

        composeTestRule.onNodeWithText("CategoryTitle").assertDoesNotExist()
    }

    @Test
    fun lazyCategory_content_displayed() {
        composeTestRule.setContent { TestLazyCategory() }

        composeTestRule.onNodeWithText("text").assertExists()
    }

    @Test
    fun lazyCategory_title_displayed() {
        composeTestRule.setContent { TestLazyCategory() }

        composeTestRule.onNodeWithText("LazyCategory 0").assertExists()
        composeTestRule.onNodeWithText("LazyCategory 1").assertDoesNotExist()
    }
}

@Composable
private fun TestLazyCategory() {
    val list: List<PreferenceModel> =
        listOf(
            object : PreferenceModel {
                override val title = "title"
            },
            object : PreferenceModel {
                override val title = "title"
            },
        )
    Column(Modifier.height(200.dp)) {
        LazyCategory(
            list = list,
            entry = { index: Int -> @Composable { Preference(list[index]) } },
            title = { index: Int -> if (index == 0) "LazyCategory $index" else null },
        ) {
            Text("text")
        }
    }
}
