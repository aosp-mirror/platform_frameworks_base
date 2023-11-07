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

import android.content.Context
import android.content.pm.ApplicationInfo
import android.icu.text.CollationKey
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.widget.ui.SpinnerOption
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.AppEntry
import com.android.settingslib.spaprivileged.model.app.AppListData
import com.android.settingslib.spaprivileged.model.app.IAppListViewModel
import com.android.settingslib.spaprivileged.tests.testutils.TestAppListModel
import com.android.settingslib.spaprivileged.tests.testutils.TestAppRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class AppListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun whenHasOptions_firstOptionDisplayed() {
        setContent(options = listOf(OPTION_0, OPTION_1))

        composeTestRule.waitUntilExactlyOneExists(
            matcher = hasText(OPTION_0),
            timeoutMillis = 5_000,
        )
        composeTestRule.onNodeWithText(OPTION_1).assertDoesNotExist()
    }

    @Test
    fun whenHasOptions_couldSwitchOption() {
        setContent(options = listOf(OPTION_0, OPTION_1))

        composeTestRule.waitUntilExactlyOneExists(
            matcher = hasText(OPTION_0),
            timeoutMillis = 5_000,
        )
        composeTestRule.onNodeWithText(OPTION_0).performClick()
        composeTestRule.onNodeWithText(OPTION_1).performClick()

        composeTestRule.onNodeWithText(OPTION_1).assertIsDisplayed()
        composeTestRule.onNodeWithText(OPTION_0).assertDoesNotExist()
    }

    @Test
    fun whenNoApps() {
        setContent(appEntries = emptyList())

        composeTestRule.waitUntilExactlyOneExists(
            matcher = hasText(context.getString(R.string.no_applications)),
            timeoutMillis = 5_000,
        )
    }

    @Test
    fun couldShowAppItem() {
        setContent(appEntries = listOf(APP_ENTRY_A))

        composeTestRule.waitUntilExactlyOneExists(
            matcher = hasText(APP_ENTRY_A.label),
            timeoutMillis = 5_000,
        )
    }

    @Test
    fun couldShowHeader() {
        setContent(appEntries = listOf(APP_ENTRY_A), header = { Text(HEADER) })

        composeTestRule.waitUntilExactlyOneExists(
            matcher = hasText(HEADER),
            timeoutMillis = 5_000,
        )
    }

    @Test
    fun whenNotGrouped_groupTitleDoesNotExist() {
        setContent(appEntries = listOf(APP_ENTRY_A, APP_ENTRY_B), enableGrouping = false)

        composeTestRule.onNodeWithText(GROUP_A).assertDoesNotExist()
        composeTestRule.onNodeWithText(GROUP_B).assertDoesNotExist()
    }

    @Test
    fun whenGrouped_groupTitleDisplayed() {
        setContent(appEntries = listOf(APP_ENTRY_A, APP_ENTRY_B), enableGrouping = true)

        composeTestRule.waitUntilExactlyOneExists(
            matcher = hasText(GROUP_A),
            timeoutMillis = 5_000,
        )
        composeTestRule.onNodeWithText(GROUP_B).assertIsDisplayed()
    }

    private fun setContent(
        options: List<String> = emptyList(),
        appEntries: List<AppEntry<TestAppRecord>> = emptyList(),
        header: @Composable () -> Unit = {},
        enableGrouping: Boolean = false,
    ) {
        val appListInput = AppListInput(
            config = AppListConfig(
                userIds = listOf(USER_ID),
                showInstantApps = false,
                matchAnyUserForAdmin = false,
            ),
            listModel = TestAppListModel(enableGrouping = enableGrouping),
            state = AppListState(showSystem = { false }, searchQuery = { "" }),
            header = header,
            bottomPadding = 0.dp,
        )
        val listViewModel = object : IAppListViewModel<TestAppRecord> {
            override val optionFlow = MutableStateFlow<Int?>(null)
            override val spinnerOptionsFlow = flowOf(options.mapIndexed { index, option ->
                SpinnerOption(id = index, text = option)
            })
            override val appListDataFlow = flowOf(AppListData(appEntries, option = 0))
        }
        composeTestRule.setContent {
            appListInput.AppListImpl { listViewModel }
        }
    }

    private companion object {
        const val USER_ID = 0
        const val OPTION_0 = "Option 1"
        const val OPTION_1 = "Option 2"
        const val HEADER = "Header"
        const val GROUP_A = "Group A"
        const val GROUP_B = "Group B"
        val APP_ENTRY_A = AppEntry(
            record = TestAppRecord(
                app = ApplicationInfo().apply {
                    packageName = "package.name.a"
                },
                group = GROUP_A,
            ),
            label = "Label A",
            labelCollationKey = CollationKey("", byteArrayOf()),
        )
        val APP_ENTRY_B = AppEntry(
            record = TestAppRecord(
                app = ApplicationInfo().apply {
                    packageName = "package.name.b"
                },
                group = GROUP_B,
            ),
            label = "Label B",
            labelCollationKey = CollationKey("", byteArrayOf()),
        )
    }
}
