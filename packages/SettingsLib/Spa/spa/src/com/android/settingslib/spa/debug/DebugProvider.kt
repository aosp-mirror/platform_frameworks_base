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

package com.android.settingslib.spa.debug

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
import com.android.settingslib.spa.framework.common.SpaEnvironmentFactory
import com.android.settingslib.spa.framework.util.KEY_DESTINATION
import com.android.settingslib.spa.framework.util.KEY_HIGHLIGHT_ENTRY
import com.android.settingslib.spa.framework.util.KEY_SESSION_SOURCE_NAME
import com.android.settingslib.spa.framework.util.SESSION_BROWSE
import com.android.settingslib.spa.framework.util.SESSION_SEARCH
import com.android.settingslib.spa.framework.util.createIntent

private const val TAG = "DebugProvider"

/**
 * The content provider to return debug data.
 * One can query the provider result by:
 *   $ adb shell content query --uri content://<AuthorityPath>/<QueryPath>
 * For gallery, AuthorityPath = com.android.spa.gallery.debug
 * Some examples:
 *   $ adb shell content query --uri content://<AuthorityPath>/page_debug
 *   $ adb shell content query --uri content://<AuthorityPath>/entry_debug
 *   $ adb shell content query --uri content://<AuthorityPath>/page_info
 *   $ adb shell content query --uri content://<AuthorityPath>/entry_info
 */
class DebugProvider : ContentProvider() {
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
            QueryEnum.PAGE_DEBUG_QUERY.addUri(uriMatcher, info.authority)
            QueryEnum.ENTRY_DEBUG_QUERY.addUri(uriMatcher, info.authority)
            QueryEnum.PAGE_INFO_QUERY.addUri(uriMatcher, info.authority)
            QueryEnum.ENTRY_INFO_QUERY.addUri(uriMatcher, info.authority)
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
                QueryEnum.ENTRY_DEBUG_QUERY.queryMatchCode -> queryEntryDebug()
                QueryEnum.PAGE_INFO_QUERY.queryMatchCode -> queryPageInfo()
                QueryEnum.ENTRY_INFO_QUERY.queryMatchCode -> queryEntryInfo()
                else -> throw UnsupportedOperationException("Unknown Uri $uri")
            }
        } catch (e: UnsupportedOperationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Provider querying exception:", e)
            null
        }
    }

    private fun queryPageDebug(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.PAGE_DEBUG_QUERY.getColumns())
        for (pageWithEntry in entryRepository.getAllPageWithEntry()) {
            val page = pageWithEntry.page
            if (!page.isBrowsable()) continue
            val command = createBrowseAdbCommand(
                destination = page.buildRoute(),
                sessionName = SESSION_BROWSE
            )
            if (command != null) {
                cursor.newRow().add(ColumnEnum.PAGE_START_ADB.id, command)
            }
        }
        return cursor
    }

    private fun queryEntryDebug(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.ENTRY_DEBUG_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            val page = entry.containerPage()
            if (!page.isBrowsable()) continue
            val command = createBrowseAdbCommand(
                destination = page.buildRoute(),
                entryId = entry.id,
                sessionName = SESSION_SEARCH
            )
            if (command != null) {
                cursor.newRow().add(ColumnEnum.ENTRY_START_ADB.id, command)
            }
        }
        return cursor
    }

    private fun queryPageInfo(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.PAGE_INFO_QUERY.getColumns())
        for (pageWithEntry in entryRepository.getAllPageWithEntry()) {
            val page = pageWithEntry.page
            val intent = page.createIntent(SESSION_BROWSE) ?: Intent()
            cursor.newRow()
                .add(ColumnEnum.PAGE_ID.id, page.id)
                .add(ColumnEnum.PAGE_NAME.id, page.displayName)
                .add(ColumnEnum.PAGE_ROUTE.id, page.buildRoute())
                .add(ColumnEnum.PAGE_INTENT_URI.id, intent.toUri(URI_INTENT_SCHEME))
                .add(ColumnEnum.PAGE_ENTRY_COUNT.id, pageWithEntry.entries.size)
                .add(ColumnEnum.HAS_RUNTIME_PARAM.id, if (page.hasRuntimeParam()) 1 else 0)
        }
        return cursor
    }

    private fun queryEntryInfo(): Cursor {
        val entryRepository by spaEnvironment.entryRepository
        val cursor = MatrixCursor(QueryEnum.ENTRY_INFO_QUERY.getColumns())
        for (entry in entryRepository.getAllEntries()) {
            val intent = entry.createIntent(SESSION_SEARCH) ?: Intent()
            cursor.newRow()
                .add(ColumnEnum.ENTRY_ID.id, entry.id)
                .add(ColumnEnum.ENTRY_NAME.id, entry.displayName)
                .add(ColumnEnum.ENTRY_ROUTE.id, entry.containerPage().buildRoute())
                .add(ColumnEnum.ENTRY_INTENT_URI.id, intent.toUri(URI_INTENT_SCHEME))
                .add(
                    ColumnEnum.ENTRY_HIERARCHY_PATH.id,
                    entryRepository.getEntryPathWithDisplayName(entry.id)
                )
        }
        return cursor
    }
}

private fun createBrowseAdbCommand(
    destination: String? = null,
    entryId: String? = null,
    sessionName: String? = null,
): String? {
    val context = SpaEnvironmentFactory.instance.appContext
    val browseActivityClass = SpaEnvironmentFactory.instance.browseActivityClass ?: return null
    val packageName = context.packageName
    val activityName = browseActivityClass.name.replace(packageName, "")
    val destinationParam =
        if (destination != null) " -e $KEY_DESTINATION $destination" else ""
    val highlightParam =
        if (entryId != null) " -e $KEY_HIGHLIGHT_ENTRY $entryId" else ""
    val sessionParam =
        if (sessionName != null) " -e $KEY_SESSION_SOURCE_NAME $sessionName" else ""
    return "adb shell am start -n $packageName/$activityName" +
        "$destinationParam$highlightParam$sessionParam"
}
