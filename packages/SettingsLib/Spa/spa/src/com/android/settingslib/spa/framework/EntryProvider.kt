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
 * Enum to define all column names in provider.
 */
enum class ColumnName(val id: String) {
    PAGE_NAME("pageName"),
    PAGE_ROUTE("pageRoute"),
    ENTRY_COUNT("entryCount"),
    HAS_RUNTIME_PARAM("hasRuntimeParam"),
    PAGE_START_ADB("pageStartAdb"),
}

data class QueryDefinition(
    val queryPath: String,
    val queryMatchCode: Int,
    val columnNames: List<ColumnName>,
) {
    fun getColumns(): Array<String> {
        return columnNames.map { it.id }.toTypedArray()
    }

    fun getIndex(name: ColumnName): Int {
        return columnNames.indexOf(name)
    }
}

open class EntryProvider(
    private val entryRepository: SettingsEntryRepository,
    private val browseActivityComponentName: String? = null,
) : ContentProvider() {

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
                PAGE_START_COMMAND_QUERY.queryPath,
                PAGE_START_COMMAND_QUERY.queryMatchCode
            )
            mMatcher!!.addURI(
                info.authority,
                PAGE_INFO_QUERY.queryPath,
                PAGE_INFO_QUERY.queryMatchCode
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
                PAGE_START_COMMAND_QUERY.queryMatchCode -> queryPageStartCommand()
                PAGE_INFO_QUERY.queryMatchCode -> queryPageInfo()
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
        val componentName = browseActivityComponentName ?: "[component-name]"
        val cursor = MatrixCursor(PAGE_START_COMMAND_QUERY.getColumns())
        for (pageWithEntry in entryRepository.getAllPageWithEntry()) {
            val page = pageWithEntry.page
            if (!page.hasRuntimeParam()) {
                cursor.newRow().add(
                    ColumnName.PAGE_START_ADB.id,
                    "adb shell am start -n $componentName" +
                        " -e ${BrowseActivity.KEY_DESTINATION} ${page.buildRoute()}"
                )
            }
        }
        return cursor
    }

    private fun queryPageInfo(): Cursor {
        val cursor = MatrixCursor(PAGE_INFO_QUERY.getColumns())
        for (pageWithEntry in entryRepository.getAllPageWithEntry()) {
            val page = pageWithEntry.page
            cursor.newRow().add(ColumnName.PAGE_NAME.id, page.name)
                .add(ColumnName.PAGE_ROUTE.id, page.buildRoute())
                .add(ColumnName.ENTRY_COUNT.id, pageWithEntry.entries.size)
                .add(ColumnName.HAS_RUNTIME_PARAM.id, if (page.hasRuntimeParam()) 1 else 0)
        }
        return cursor
    }

    companion object {
        val PAGE_START_COMMAND_QUERY = QueryDefinition(
            "page_start", 1,
            listOf(ColumnName.PAGE_START_ADB)
        )

        val PAGE_INFO_QUERY = QueryDefinition(
            "page_info", 2,
            listOf(
                ColumnName.PAGE_NAME,
                ColumnName.PAGE_ROUTE,
                ColumnName.ENTRY_COUNT,
                ColumnName.HAS_RUNTIME_PARAM,
            )
        )
    }
}
