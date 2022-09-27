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

package com.android.settingslib.spa.framework

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.Intent.URI_INTENT_SCHEME
import android.content.UriMatcher
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.android.settingslib.spa.framework.common.SettingsPage
import com.android.settingslib.spa.framework.common.SpaEnvironment

/**
 * The content provider to return entry related data, which can be used for search and hierarchy.
 * One can query the provider result by:
 *   $ adb shell content query --uri content://<AuthorityPath>/<QueryPath>
 * For gallery, AuthorityPath = com.android.spa.gallery.provider
 * For SettingsGoogle, AuthorityPath = com.android.settings.spa.provider
 * Some examples:
 *   $ adb shell content query --uri content://<AuthorityPath>/page_debug
 *   $ adb shell content query --uri content://<AuthorityPath>/page_info
 *   $ adb shell content query --uri content://<AuthorityPath>/entry_info
 */
open class EntryProvider(spaEnvironment: SpaEnvironment) : ContentProvider() {
    private val entryRepository by spaEnvironment.entryRepository
    private val browseActivityClass = spaEnvironment.browseActivityClass

    /**
     * Enum to define all column names in provider.
     */
    enum class ColumnEnum(val id: String) {
        // Columns related to page
        PAGE_ID("pageId"),
        PAGE_NAME("pageName"),
        PAGE_ROUTE("pageRoute"),
        PAGE_INTENT_URI("pageIntent"),
        PAGE_ENTRY_COUNT("entryCount"),
        HAS_RUNTIME_PARAM("hasRuntimeParam"),
        PAGE_START_ADB("pageStartAdb"),

        // Columns related to entry
        ENTRY_ID("entryId"),
        ENTRY_NAME("entryName"),
        ENTRY_ROUTE("entryRoute"),
        ENTRY_TITLE("entryTitle"),
        ENTRY_SEARCH_KEYWORD("entrySearchKw"),
    }

    /**
     * Enum to define all queries supported in the provider.
     */
    enum class QueryEnum(
        val queryPath: String,
        val queryMatchCode: Int,
        val columnNames: List<ColumnEnum>
    ) {
        // For debug
        PAGE_DEBUG_QUERY(
            "page_debug", 1,
            listOf(ColumnEnum.PAGE_START_ADB)
        ),

        // page related queries.
        PAGE_INFO_QUERY(
            "page_info", 100,
            listOf(
                ColumnEnum.PAGE_ID,
                ColumnEnum.PAGE_NAME,
                ColumnEnum.PAGE_ROUTE,
                ColumnEnum.PAGE_INTENT_URI,
                ColumnEnum.PAGE_ENTRY_COUNT,
                ColumnEnum.HAS_RUNTIME_PARAM,
            )
        ),

        // entry related queries
        ENTRY_INFO_QUERY(
            "entry_info", 200,
            listOf(
                ColumnEnum.ENTRY_ID,
                ColumnEnum.ENTRY_NAME,
                ColumnEnum.ENTRY_ROUTE,
                ColumnEnum.ENTRY_TITLE,
                ColumnEnum.ENTRY_SEARCH_KEYWORD,
            )
        )
    }

    private val uriMatcher = UriMatcher(UriMatcher.NO_MATCH)
    private fun addUri(authority: String, query: QueryEnum) {
        uriMatcher.addURI(authority, query.queryPath, query.queryMatchCode)
    }

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
        return true
    }

    override fun attachInfo(context: Context?, info: ProviderInfo?) {
        if (info != null) {
            addUri(info.authority, QueryEnum.PAGE_DEBUG_QUERY)
            addUri(info.authority, QueryEnum.PAGE_INFO_QUERY)
            addUri(info.authority, QueryEnum.ENTRY_INFO_QUERY)
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
                QueryEnum.PAGE_DEBUG_QUERY.queryMatchCode -> queryPageDebug()
                QueryEnum.PAGE_INFO_QUERY.queryMatchCode -> queryPageInfo()
                QueryEnum.ENTRY_INFO_QUERY.queryMatchCode -> queryEntryInfo()
                else -> throw UnsupportedOperationException("Unknown Uri $uri")
            }
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: Exception) {
            Log.e("EntryProvider", "Provider querying exception:", e)
            null
        }
    }

    private fun queryPageDebug(): Cursor {
        val cursor = MatrixCursor(QueryEnum.PAGE_DEBUG_QUERY.getColumns())
        for (pageWithEntry in entryRepository.getAllPageWithEntry()) {
            val command = createBrowsePageAdbCommand(pageWithEntry.page)
            if (command != null) {
                cursor.newRow().add(ColumnEnum.PAGE_START_ADB.id, command)
            }
        }
        return cursor
    }

    private fun queryPageInfo(): Cursor {
        val cursor = MatrixCursor(QueryEnum.PAGE_INFO_QUERY.getColumns())
        for (pageWithEntry in entryRepository.getAllPageWithEntry()) {
            val page = pageWithEntry.page
            cursor.newRow()
                .add(ColumnEnum.PAGE_ID.id, page.id)
                .add(ColumnEnum.PAGE_NAME.id, page.displayName)
                .add(ColumnEnum.PAGE_ROUTE.id, page.buildRoute())
                .add(ColumnEnum.PAGE_ENTRY_COUNT.id, pageWithEntry.entries.size)
                .add(ColumnEnum.HAS_RUNTIME_PARAM.id, if (page.hasRuntimeParam()) 1 else 0)
                .add(
                    ColumnEnum.PAGE_INTENT_URI.id,
                    createBrowsePageIntent(page).toUri(URI_INTENT_SCHEME)
                )
        }
        return cursor
    }

    private fun queryEntryInfo(): Cursor {
        val cursor = MatrixCursor(QueryEnum.ENTRY_INFO_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            // We can add runtime arguments if necessary
            val searchData = entry.getSearchData()
            cursor.newRow()
                .add(ColumnEnum.ENTRY_ID.id, entry.id)
                .add(ColumnEnum.ENTRY_NAME.id, entry.displayName)
                .add(ColumnEnum.ENTRY_ROUTE.id, entry.buildRoute())
                .add(ColumnEnum.ENTRY_TITLE.id, searchData?.title ?: "")
                .add(
                    ColumnEnum.ENTRY_SEARCH_KEYWORD.id,
                    searchData?.keyword ?: emptyList<String>()
                )
        }
        return cursor
    }

    private fun createBrowsePageIntent(page: SettingsPage): Intent {
        if (context == null || page.hasRuntimeParam())
            return Intent()

        return Intent().setComponent(ComponentName(context!!, browseActivityClass)).apply {
            putExtra(BrowseActivity.KEY_DESTINATION, page.buildRoute())
        }
    }

    private fun createBrowsePageAdbCommand(page: SettingsPage): String? {
        if (context == null || page.hasRuntimeParam()) return null
        val packageName = context!!.packageName
        val activityName = browseActivityClass.name.replace(packageName, "")
        return "adb shell am start -n $packageName/$activityName" +
            " -e ${BrowseActivity.KEY_DESTINATION} ${page.buildRoute()}"
    }
}

fun EntryProvider.QueryEnum.getColumns(): Array<String> {
    return columnNames.map { it.id }.toTypedArray()
}

fun EntryProvider.QueryEnum.getIndex(name: EntryProvider.ColumnEnum): Int {
    return columnNames.indexOf(name)
}

fun Cursor.getString(query: EntryProvider.QueryEnum, columnName: EntryProvider.ColumnEnum): String {
    return this.getString(query.getIndex(columnName))
}

fun Cursor.getInt(query: EntryProvider.QueryEnum, columnName: EntryProvider.ColumnEnum): Int {
    return this.getInt(query.getIndex(columnName))
}

fun Cursor.getBoolean(
    query: EntryProvider.QueryEnum,
    columnName: EntryProvider.ColumnEnum
): Boolean {
    return this.getInt(query.getIndex(columnName)) == 1
}
