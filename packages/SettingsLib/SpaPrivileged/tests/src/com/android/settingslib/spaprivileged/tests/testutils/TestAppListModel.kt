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

package com.android.settingslib.spaprivileged.tests.testutils

import android.content.pm.ApplicationInfo
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.framework.util.asyncMapItem
import com.android.settingslib.spaprivileged.model.app.AppListModel
import com.android.settingslib.spaprivileged.model.app.AppRecord
import kotlinx.coroutines.flow.Flow

data class TestAppRecord(
    override val app: ApplicationInfo,
    val group: String? = null,
) : AppRecord

class TestAppListModel(
    private val options: List<String> = emptyList(),
    private val enableGrouping: Boolean = false,
) : AppListModel<TestAppRecord> {
    override fun getSpinnerOptions() = options

    override fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>) =
        appListFlow.asyncMapItem { TestAppRecord(it) }

    @Composable
    override fun getSummary(option: Int, record: TestAppRecord) = null

    override fun filter(
        userIdFlow: Flow<Int>,
        option: Int,
        recordListFlow: Flow<List<TestAppRecord>>,
    ) = recordListFlow

    override fun getGroupTitle(option: Int, record: TestAppRecord) =
        if (enableGrouping) record.group else null
}
