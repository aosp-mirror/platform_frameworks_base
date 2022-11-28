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
import android.content.Intent
import android.content.UriMatcher
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.settingslib.spa.framework.common.ColumnEnum
import com.android.settingslib.spa.framework.common.QueryEnum
import com.android.settingslib.spa.framework.common.SettingsEntry
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.common.addUri
import com.android.settingslib.spa.framework.common.getColumns

private const val TAG = "SpaSearchProvider"

/**
 * The content provider to return entry related data, which can be used for search and hierarchy.
 * One can query the provider result by:
 *   $ adb shell content query --uri content://<AuthorityPath>/<QueryPath>
 * For gallery, AuthorityPath = com.android.spa.gallery.provider
 * For Settings, AuthorityPath = com.android.settings.spa.provider
 * Some examples:
 *   $ adb shell content query --uri content://<AuthorityPath>/search_static
 *   $ adb shell content query --uri content://<AuthorityPath>/search_dynamic
 *   $ adb shell content query --uri content://<AuthorityPath>/search_mutable_status
 *   $ adb shell content query --uri content://<AuthorityPath>/search_immutable_status
 */
class SpaSearchProvider : ContentProvider() {
    private val spaEnvironment get() = SpaEnvironmentFactory.instance
    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)

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
            QueryEnum.SEARCH_STATIC_DATA_QUERY.addUri(uriMatcher, info.authority)
            QueryEnum.SEARCH_DYNAMIC_DATA_QUERY.addUri(uriMatcher, info.authority)
            QueryEnum.SEARCH_MUTABLE_STATUS_DATA_QUERY.addUri(uriMatcher, info.authority)
            QueryEnum.SEARCH_IMMUTABLE_STATUS_DATA_QUERY.addUri(uriMatcher, info.authority)
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
                QueryEnum.SEARCH_STATIC_DATA_QUERY.queryMatchCode -> querySearchStaticData()
                QueryEnum.SEARCH_DYNAMIC_DATA_QUERY.queryMatchCode -> querySearchDynamicData()
                QueryEnum.SEARCH_MUTABLE_STATUS_DATA_QUERY.queryMatchCode ->
                    querySearchMutableStatusData()
                QueryEnum.SEARCH_IMMUTABLE_STATUS_DATA_QUERY.queryMatchCode ->
                    querySearchImmutableStatusData()
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
    fun querySearchImmutableStatusData(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.SEARCH_IMMUTABLE_STATUS_DATA_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            if (!entry.isAllowSearch || entry.hasMutableStatus) continue
            fetchStatusData(entry, cursor)
        }
        return cursor
    }

    @VisibleForTesting
    fun querySearchMutableStatusData(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.SEARCH_MUTABLE_STATUS_DATA_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            if (!entry.isAllowSearch || !entry.hasMutableStatus) continue
            fetchStatusData(entry, cursor)
        }
        return cursor
    }

    @VisibleForTesting
    fun querySearchStaticData(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.SEARCH_STATIC_DATA_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            if (!entry.isAllowSearch || entry.isSearchDataDynamic) continue
            fetchSearchData(entry, cursor)
        }
        return cursor
    }

    @VisibleForTesting
    fun querySearchDynamicData(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.SEARCH_DYNAMIC_DATA_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            if (!entry.isAllowSearch || !entry.isSearchDataDynamic) continue
            fetchSearchData(entry, cursor)
        }
        return cursor
    }

    private fun fetchSearchData(entry: SettingsEntry, cursor: MatrixCursor) {
        val entryRepository by spaEnvironment.entryRepository
        val browseActivityClass = spaEnvironment.browseActivityClass

        // Fetch search data. We can add runtime arguments later if necessary
        val searchData = entry.getSearchData() ?: return
        val intent = entry.containerPage()
            .createBrowseIntent(context, browseActivityClass, entry.id)
            ?: Intent()
        cursor.newRow()
            .add(ColumnEnum.ENTRY_ID.id, entry.id)
            .add(ColumnEnum.ENTRY_INTENT_URI.id, intent.toUri(Intent.URI_INTENT_SCHEME))
            .add(ColumnEnum.SEARCH_TITLE.id, searchData.title)
            .add(ColumnEnum.SEARCH_KEYWORD.id, searchData.keyword)
            .add(ColumnEnum.SEARCH_PATH.id,
                entryRepository.getEntryPathWithTitle(entry.id, searchData.title))
    }

    private fun fetchStatusData(entry: SettingsEntry, cursor: MatrixCursor) {
        // Fetch status data. We can add runtime arguments later if necessary
        val statusData = entry.getStatusData() ?: return
        cursor.newRow()
            .add(ColumnEnum.ENTRY_ID.id, entry.id)
            .add(ColumnEnum.SEARCH_STATUS_DISABLED.id, statusData.isDisabled)
    }
}
