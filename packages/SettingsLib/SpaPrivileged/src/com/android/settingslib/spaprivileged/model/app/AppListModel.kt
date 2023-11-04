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

package com.android.settingslib.spaprivileged.model.app

import android.content.pm.ApplicationInfo
import android.icu.text.CollationKey
import androidx.compose.runtime.Composable
import com.android.settingslib.spa.widget.ui.SpinnerOption
import com.android.settingslib.spaprivileged.template.app.AppListItem
import com.android.settingslib.spaprivileged.template.app.AppListItemModel
import kotlinx.coroutines.flow.Flow

data class AppEntry<T : AppRecord>(
    val record: T,
    val label: String,
    val labelCollationKey: CollationKey,
)

/**
 * Implement this interface to build an App List.
 */
interface AppListModel<T : AppRecord> {
    /**
     * Returns the spinner options available to the App List.
     *
     * Default no spinner will be shown.
     */
    fun getSpinnerOptions(recordList: List<T>): List<SpinnerOption> = emptyList()

    /**
     * Loads the extra info for the App List, and generates the [AppRecord] List.
     */
    fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>): Flow<List<T>>

    /**
     * Filters the [AppRecord] list.
     *
     * @return the [AppRecord] list which will be displayed.
     */
    fun filter(userIdFlow: Flow<Int>, option: Int, recordListFlow: Flow<List<T>>): Flow<List<T>> =
        recordListFlow

    /**
     * This function is called when the App List's loading is finished and displayed to the user.
     *
     * Could do some pre-cache here.
     *
     * @return true to enable pre-fetching app labels.
     */
    suspend fun onFirstLoaded(recordList: List<T>) = false

    /**
     * Gets the comparator to sort the App List.
     *
     * Default sorting is based on the app label.
     */
    fun getComparator(option: Int): Comparator<AppEntry<T>> = compareBy(
        { it.labelCollationKey },
        { it.record.app.packageName },
        { it.record.app.uid },
    )

    /**
     * Gets the group title of this item.
     *
     * Note: Items should be sorted by group in [getComparator] first, this [getGroupTitle] will not
     * change the list order.
     */
    fun getGroupTitle(option: Int, record: T): String? = null

    /**
     * Gets the summary for the given app record.
     *
     * @return null if no summary should be displayed.
     */
    @Composable
    fun getSummary(option: Int, record: T): (() -> String)? = null

    @Composable
    fun AppListItemModel<T>.AppItem() {
        AppListItem {}
    }
}
