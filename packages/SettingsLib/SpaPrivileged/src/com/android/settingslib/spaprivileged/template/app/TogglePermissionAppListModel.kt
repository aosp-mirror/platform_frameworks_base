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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import com.android.settingslib.spaprivileged.framework.app.AppRecord

interface TogglePermissionAppListModel<T : AppRecord> {
    val pageTitleResId: Int
    val switchTitleResId: Int
    val footerResId: Int

    fun transformItem(app: ApplicationInfo): T

    @Composable
    fun isAllowed(record: T): State<Boolean?>

    fun isChangeable(record: T): Boolean
    fun setAllowed(record: T, newAllowed: Boolean)
}

interface TogglePermissionAppListModelFactory {
    fun createModel(
        permission: String,
        context: Context,
    ): TogglePermissionAppListModel<out AppRecord>
}
