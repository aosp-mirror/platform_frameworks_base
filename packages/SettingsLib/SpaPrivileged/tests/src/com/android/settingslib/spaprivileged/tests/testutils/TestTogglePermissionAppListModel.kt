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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.android.settingslib.spaprivileged.template.app.TogglePermissionAppListModel
import com.android.settingslib.spaprivileged.test.R
import kotlinx.coroutines.flow.Flow

class TestTogglePermissionAppListModel(
    isAllowed: Boolean? = null,
    private val isChangeable: Boolean = false,
) : TogglePermissionAppListModel<TestAppRecord> {
    override val pageTitleResId = R.string.test_permission_title
    override val switchTitleResId = R.string.test_permission_switch_title
    override val footerResId = R.string.test_permission_footer

    private var isAllowed by mutableStateOf(isAllowed)

    override fun transformItem(app: ApplicationInfo) = TestAppRecord(app = app)

    override fun filter(userIdFlow: Flow<Int>, recordListFlow: Flow<List<TestAppRecord>>) =
        recordListFlow

    @Composable
    override fun isAllowed(record: TestAppRecord) = { isAllowed }

    override fun isChangeable(record: TestAppRecord) = isChangeable

    override fun setAllowed(record: TestAppRecord, newAllowed: Boolean) {
        isAllowed = newAllowed
    }
}
