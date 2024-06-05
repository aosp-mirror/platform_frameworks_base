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

import android.content.Context
import androidx.appcompat.R
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SearchScaffoldTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun initialState_titleIsDisplayed() {
        composeTestRule.setContent {
            SearchScaffold(title = TITLE) { _, _ -> }
        }

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
    }

    @Test
    fun initialState_clearButtonNotExist() {
        setContent()

        onClearButton().assertDoesNotExist()
    }

    @Test
    fun initialState_searchQueryIsEmpty() {
        val searchQuery = setContent()

        assertThat(searchQuery()).isEqualTo("")
    }

    @Test
    fun canEnterSearchMode() {
        val searchQuery = setContent()

        clickSearchButton()

        composeTestRule.onNodeWithText(TITLE).assertDoesNotExist()
        onSearchHint().assertIsDisplayed()
        onClearButton().assertDoesNotExist()
        assertThat(searchQuery()).isEqualTo("")
    }

    @Test
    fun canExitSearchMode() {
        val searchQuery = setContent()

        clickSearchButton()
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.abc_toolbar_collapse_description)
        ).performClick()

        composeTestRule.onNodeWithText(TITLE).assertIsDisplayed()
        onSearchHint().assertDoesNotExist()
        onClearButton().assertDoesNotExist()
        assertThat(searchQuery()).isEqualTo("")
    }

    @Test
    fun canEnterSearchQuery() {
        val searchQuery = setContent()

        clickSearchButton()
        onSearchHint().performTextInput(QUERY)

        onClearButton().assertIsDisplayed()
        assertThat(searchQuery()).isEqualTo(QUERY)
    }

    @Test
    fun canClearSearchQuery() {
        val searchQuery = setContent()

        clickSearchButton()
        onSearchHint().performTextInput(QUERY)
        onClearButton().performClick()

        onClearButton().assertDoesNotExist()
        assertThat(searchQuery()).isEqualTo("")
    }

    private fun setContent(): () -> String {
        lateinit var actualSearchQuery: () -> String
        composeTestRule.setContent {
            SearchScaffold(title = TITLE) { _, searchQuery ->
                SideEffect {
                    actualSearchQuery = searchQuery
                }
            }
        }
        return actualSearchQuery
    }

    private fun clickSearchButton() {
        composeTestRule.onNodeWithContentDescription(
            context.getString(R.string.search_menu_title)
        ).performClick()
    }

    private fun onSearchHint() = composeTestRule.onNodeWithText(
        context.getString(R.string.abc_search_hint)
    )

    private fun onClearButton() = composeTestRule.onNodeWithContentDescription(
        context.getString(R.string.abc_searchview_description_clear)
    )

    private companion object {
        const val TITLE = "title"
        const val QUERY = "query"
    }
}
