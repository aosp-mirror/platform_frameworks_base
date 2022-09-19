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

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.UriMatcher
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log
import com.android.settingslib.spa.framework.common.SettingsEntryRepository

/**
 * The content provider to return entry related data, which can be used for search and hierarchy.
 * One can query the provider result by:
 *   $ adb shell content query --uri content://<AuthorityPath>/<QueryPath>
 * For gallery, AuthorityPath = com.android.spa.gallery.provider
 * Some examples:
 *   $ adb shell content query --uri content://<AuthorityPath>/page_start
 *   $ adb shell content query --uri content://<AuthorityPath>/page_info
 */
open class EntryProvider(
    private val entryRepository: SettingsEntryRepository,
    private val browseActivityComponentName: String? = null,
) : ContentProvider() {

    /**
     * Enum to define all column names in provider.
     */
    enum class ColumnEnum(val id: String) {
        PAGE_ID("pageId"),
        PAGE_NAME("pageName"),
        PAGE_ROUTE("pageRoute"),
        ENTRY_COUNT("entryCount"),
        HAS_RUNTIME_PARAM("hasRuntimeParam"),
        PAGE_START_ADB("pageStartAdb"),
    }

    /**
     * Enum to define all queries supported in the provider.
     */
    enum class QueryEnum(
        val queryPath: String,
        val queryMatchCode: Int,
        val columnNames: List<ColumnEnum>
    ) {
        PAGE_START_COMMAND(
            "page_start", 1,
            listOf(ColumnEnum.PAGE_START_ADB)
        ),
        PAGE_INFO_QUERY(
            "page_info", 2,
            listOf(
                ColumnEnum.PAGE_ID,
                ColumnEnum.PAGE_NAME,
                ColumnEnum.PAGE_ROUTE,
                ColumnEnum.ENTRY_COUNT,
                ColumnEnum.HAS_RUNTIME_PARAM,
            )
        ),
    }

    private var mMatcher: UriMatcher? = null

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
        mMatcher = UriMatcher(UriMatcher.NO_MATCH)
        if (info != null) {
            mMatcher!!.addURI(
                info.authority,
                QueryEnum.PAGE_START_COMMAND.queryPath,
                QueryEnum.PAGE_START_COMMAND.queryMatchCode
            )
            mMatcher!!.addURI(
                info.authority,
                QueryEnum.PAGE_INFO_QUERY.queryPath,
                QueryEnum.PAGE_INFO_QUERY.queryMatchCode
            )
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
            when (mMatcher!!.match(uri)) {
                QueryEnum.PAGE_START_COMMAND.queryMatchCode -> queryPageStartCommand()
                QueryEnum.PAGE_INFO_QUERY.queryMatchCode -> queryPageInfo()
                else -> throw UnsupportedOperationException("Unknown Uri $uri")
            }
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: Exception) {
            Log.e("EntryProvider", "Provider querying exception:", e)
            null
        }
    }

    private fun queryPageStartCommand(): Cursor {
        val componentName = browseActivityComponentName ?: "<activity-component-name>"
        val cursor = MatrixCursor(QueryEnum.PAGE_START_COMMAND.getColumns())
        for (pageWithEntry in entryRepository.getAllPageWithEntry()) {
            val page = pageWithEntry.page
            if (!page.hasRuntimeParam()) {
                cursor.newRow().add(
                    ColumnEnum.PAGE_START_ADB.id,
                    "adb shell am start -n $componentName" +
                        " -e ${BrowseActivity.KEY_DESTINATION} ${page.buildRoute()}"
                )
            }
        }
        return cursor
    }

    private fun queryPageInfo(): Cursor {
        val cursor = MatrixCursor(QueryEnum.PAGE_INFO_QUERY.getColumns())
        for (pageWithEntry in entryRepository.getAllPageWithEntry()) {
            val page = pageWithEntry.page
            cursor.newRow()
                .add(ColumnEnum.PAGE_ID.id, page.id())
                .add(ColumnEnum.PAGE_NAME.id, page.displayName)
                .add(ColumnEnum.PAGE_ROUTE.id, page.buildRoute())
                .add(ColumnEnum.ENTRY_COUNT.id, pageWithEntry.entries.size)
                .add(ColumnEnum.HAS_RUNTIME_PARAM.id, if (page.hasRuntimeParam()) 1 else 0)
        }
        return cursor
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
