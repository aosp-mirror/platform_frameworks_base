package com.android.settingslib.spaprivileged.model.app

import android.content.pm.ApplicationInfo
import android.icu.text.CollationKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import kotlinx.coroutines.flow.Flow

data class AppEntry<T : AppRecord>(
    val record: T,
    val label: String,
    val labelCollationKey: CollationKey,
)

interface AppListModel<T : AppRecord> {
    fun getSpinnerOptions(): List<String> = emptyList()
    fun transform(userIdFlow: Flow<Int>, appListFlow: Flow<List<ApplicationInfo>>): Flow<List<T>>
    fun filter(userIdFlow: Flow<Int>, option: Int, recordListFlow: Flow<List<T>>): Flow<List<T>>

    suspend fun onFirstLoaded(recordList: List<T>) {}
    fun getComparator(option: Int): Comparator<AppEntry<T>> = compareBy(
        { it.labelCollationKey },
        { it.record.app.packageName },
        { it.record.app.uid },
    )

    @Composable
    fun getSummary(option: Int, record: T): State<String>?
}
