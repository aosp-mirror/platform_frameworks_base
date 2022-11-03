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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.settingslib.spa.framework.compose.stateOf
import com.android.settingslib.spa.framework.compose.toState
import com.android.settingslib.spa.framework.util.asyncMapItem
import com.android.settingslib.spaprivileged.R
import com.android.settingslib.spaprivileged.model.app.AppEntry
import com.android.settingslib.spaprivileged.model.app.AppListConfig
import com.android.settingslib.spaprivileged.model.app.AppListData
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import kotlinx.coroutines.flow.Flow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppListTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    private var context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun whenNoApps() {
        setContent(appEntries = emptyList())

        composeTestRule.onNodeWithText(context.getString(R.string.no_applications))
            .assertIsDisplayed()
    }

    @Test
    fun couldShowAppItem() {
        setContent(appEntries = listOf(APP_ENTRY))

        composeTestRule.onNodeWithText(APP_ENTRY.label).assertIsDisplayed()
    }

    @Test
    fun couldShowHeader() {
        setContent(header = { Text(HEADER) }, appEntries = listOf(APP_ENTRY))

        composeTestRule.onNodeWithText(HEADER).assertIsDisplayed()
    }

    private fun setContent(
        header: @Composable () -> Unit = {},
        appEntries: List<AppEntry<TestAppRecord>>,
    ) {
        composeTestRule.setContent {
            AppList(
                config = AppListConfig(userId = USER_ID, showInstantApps = false),
                listModel = TestAppListModel(),
                state = AppListState(
                    showSystem = false.toState(),
                    option = 0.toState(),
                    searchQuery = "".toState(),
                ),
                header = header,
                appItem = { AppListItem(it) {} },
                bottomPadding = 0.dp,
                appListDataSupplier = {
                    stateOf(AppListData(appEntries, option = 0))
                }
            )
        }
    }

    private companion object {
        const val USER_ID = 0
        const val HEADER = "Header"
        val APP_ENTRY = AppEntry(
            record = TestAppRecord(ApplicationInfo()),
            label = "AAA",
            labelCollationKey = CollationKey("", byteArrayOf()),
        )
    }
}

private data class TestAppRecord(override val app: ApplicationInfo) : AppRecord

private class TestAppListModel : AppListModel<TestAppRecord> {
    override fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>) =
        appListFlow.asyncMapItem { TestAppRecord(it) }

    @Composable
    override fun getSummary(option: Int, record: TestAppRecord) = null

    override fun filter(
        userIdFlow: Flow<Int>,
        option: Int,
        recordListFlow: Flow<List<TestAppRecord>>,
    ) = recordListFlow
}
