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

package com.android.settingslib.spa.search

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.util.SESSION_SEARCH
import com.android.settingslib.spa.framework.util.createIntent

private const val TAG = "SpaSearchProvider"

/**
 * The content provider to return entry related data, which can be used for search and hierarchy.
 * One can query the provider result by:
 *   $ adb shell content query --uri content://<AuthorityPath>/<QueryPath>
 * For gallery, AuthorityPath = com.android.spa.gallery.search.provider
 * For Settings, AuthorityPath = com.android.settings.spa.search.provider
 * Some examples:
 *   $ adb shell content query --uri content://<AuthorityPath>/search_static_data
 *   $ adb shell content query --uri content://<AuthorityPath>/search_dynamic_data
 *   $ adb shell content query --uri content://<AuthorityPath>/search_immutable_status
 *   $ adb shell content query --uri content://<AuthorityPath>/search_mutable_status
 *   $ adb shell content query --uri content://<AuthorityPath>/search_static_row
 *   $ adb shell content query --uri content://<AuthorityPath>/search_dynamic_row
 */
class SpaSearchProvider : ContentProvider() {
    private val spaEnvironment get() = SpaEnvironmentFactory.instance
    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)

    private val queryMatchCode = mapOf(
        SEARCH_STATIC_DATA to 301,
        SEARCH_DYNAMIC_DATA to 302,
        SEARCH_MUTABLE_STATUS to 303,
        SEARCH_IMMUTABLE_STATUS to 304,
        SEARCH_STATIC_ROW to 305,
        SEARCH_DYNAMIC_ROW to 306
    )

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        TODO("Implement this to handle requests to delete one or more rows")
    }

    override fun getType(uri: Uri): String? {
        TODO(
            "Implement this to handle requests for the MIME type of the data" +
                "at the given URI"
        )
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        TODO("Implement this to handle requests to insert a new row.")
    }

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<String>?
    ): Int {
        TODO("Implement this to handle requests to update one or more rows.")
    }

    override fun onCreate(): Boolean {
        Log.d(TAG, "onCreate")
        return true
    }

    override fun attachInfo(context: Context?, info: ProviderInfo?) {
        if (info != null) {
            for (entry in queryMatchCode) {
                uriMatcher.addURI(info.authority, entry.key, entry.value)
            }
        }
        super.attachInfo(context, info)
    }

    override fun query(
        uri: Uri,
        projection: Array<String>?,
        selection: String?,
        selectionArgs: Array<String>?,
        sortOrder: String?
    ): Cursor? {
        return try {
            when (uriMatcher.match(uri)) {
                queryMatchCode[SEARCH_STATIC_DATA] -> querySearchStaticData()
                queryMatchCode[SEARCH_DYNAMIC_DATA] -> querySearchDynamicData()
                queryMatchCode[SEARCH_MUTABLE_STATUS] ->
                    querySearchMutableStatusData()
                queryMatchCode[SEARCH_IMMUTABLE_STATUS] ->
                    querySearchImmutableStatusData()
                queryMatchCode[SEARCH_STATIC_ROW] -> querySearchStaticRow()
                queryMatchCode[SEARCH_DYNAMIC_ROW] -> querySearchDynamicRow()
                else -> throw UnsupportedOperationException("Unknown Uri $uri")
            }
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Provider querying exception:", e)
            null
        }
    }

    @VisibleForTesting
    internal fun querySearchImmutableStatusData(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.SEARCH_IMMUTABLE_STATUS_DATA_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            if (!entry.isAllowSearch || entry.hasMutableStatus) continue
            fetchStatusData(entry, cursor)
        }
        return cursor
    }

    @VisibleForTesting
    internal fun querySearchMutableStatusData(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.SEARCH_MUTABLE_STATUS_DATA_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            if (!entry.isAllowSearch || !entry.hasMutableStatus) continue
            fetchStatusData(entry, cursor)
        }
        return cursor
    }

    @VisibleForTesting
    internal fun querySearchStaticData(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.SEARCH_STATIC_DATA_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            if (!entry.isAllowSearch || entry.isSearchDataDynamic) continue
            fetchSearchData(entry, cursor)
        }
        return cursor
    }

    @VisibleForTesting
    internal fun querySearchDynamicData(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.SEARCH_DYNAMIC_DATA_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            if (!entry.isAllowSearch || !entry.isSearchDataDynamic) continue
            fetchSearchData(entry, cursor)
        }
        return cursor
    }

    @VisibleForTesting
    fun querySearchStaticRow(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.SEARCH_STATIC_ROW_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            if (!entry.isAllowSearch || entry.isSearchDataDynamic || entry.hasMutableStatus)
                continue
            fetchSearchRow(entry, cursor)
        }
        return cursor
    }

    @VisibleForTesting
    fun querySearchDynamicRow(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.SEARCH_DYNAMIC_ROW_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            if (!entry.isAllowSearch || (!entry.isSearchDataDynamic && !entry.hasMutableStatus))
                continue
            fetchSearchRow(entry, cursor)
        }
        return cursor
    }


    private fun fetchSearchData(entry: SettingsEntry, cursor: MatrixCursor) {
        val entryRepository by spaEnvironment.entryRepository

        // Fetch search data. We can add runtime arguments later if necessary
        val searchData = entry.getSearchData() ?: return
        val intent = entry.createIntent(SESSION_SEARCH)
        val row = cursor.newRow().add(ColumnEnum.ENTRY_ID.id, entry.id)
            .add(ColumnEnum.ENTRY_LABEL.id, entry.label)
            .add(ColumnEnum.SEARCH_TITLE.id, searchData.title)
            .add(ColumnEnum.SEARCH_KEYWORD.id, searchData.keyword)
            .add(
                ColumnEnum.SEARCH_PATH.id,
                entryRepository.getEntryPathWithTitle(entry.id, searchData.title)
            )
        intent?.let {
            row.add(ColumnEnum.INTENT_TARGET_PACKAGE.id, spaEnvironment.appContext.packageName)
                .add(ColumnEnum.INTENT_TARGET_CLASS.id, spaEnvironment.browseActivityClass?.name)
                .add(ColumnEnum.INTENT_EXTRAS.id, marshall(intent.extras))
        }
    }

    private fun fetchStatusData(entry: SettingsEntry, cursor: MatrixCursor) {
        // Fetch status data. We can add runtime arguments later if necessary
        val statusData = entry.getStatusData() ?: return
        cursor.newRow()
            .add(ColumnEnum.ENTRY_ID.id, entry.id)
            .add(ColumnEnum.ENTRY_LABEL.id, entry.label)
            .add(ColumnEnum.ENTRY_DISABLED.id, statusData.isDisabled)
    }

    private fun fetchSearchRow(entry: SettingsEntry, cursor: MatrixCursor) {
        val entryRepository by spaEnvironment.entryRepository

        // Fetch search data. We can add runtime arguments later if necessary
        val searchData = entry.getSearchData() ?: return
        val intent = entry.createIntent(SESSION_SEARCH)
        val row = cursor.newRow().add(ColumnEnum.ENTRY_ID.id, entry.id)
            .add(ColumnEnum.ENTRY_LABEL.id, entry.label)
            .add(ColumnEnum.SEARCH_TITLE.id, searchData.title)
            .add(ColumnEnum.SEARCH_KEYWORD.id, searchData.keyword)
            .add(
                ColumnEnum.SEARCH_PATH.id,
                entryRepository.getEntryPathWithTitle(entry.id, searchData.title)
            )
        intent?.let {
            row.add(ColumnEnum.INTENT_TARGET_PACKAGE.id, spaEnvironment.appContext.packageName)
                .add(ColumnEnum.INTENT_TARGET_CLASS.id, spaEnvironment.browseActivityClass?.name)
                .add(ColumnEnum.INTENT_EXTRAS.id, marshall(intent.extras))
        }
        // Fetch status data. We can add runtime arguments later if necessary
        val statusData = entry.getStatusData() ?: return
        row.add(ColumnEnum.ENTRY_DISABLED.id, statusData.isDisabled)
    }

    private fun QueryEnum.getColumns(): Array<String> {
        return columnNames.map { it.id }.toTypedArray()
    }

    private fun marshall(parcelable: Parcelable?): ByteArray? {
        if (parcelable == null) return null
        val parcel = Parcel.obtain()
        parcelable.writeToParcel(parcel, 0)
        val bytes = parcel.marshall()
        parcel.recycle()
        return bytes
    }
}
