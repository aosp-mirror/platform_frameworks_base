/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.communal.data.backup

import android.content.Context
import androidx.annotation.WorkerThread
import com.android.systemui.communal.data.db.CommunalDatabase
import com.android.systemui.communal.nano.CommunalHubState
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/** Utilities for communal backup and restore. */
class CommunalBackupUtils(
    private val context: Context,
) {

    /**
     * Retrieves a communal hub state protobuf that represents the current state of the communal
     * database.
     */
    @WorkerThread
    fun getCommunalHubState(): CommunalHubState {
        val database = CommunalDatabase.getInstance(context)
        val widgetsFromDb = runBlocking { database.communalWidgetDao().getWidgets().first() }
        val widgetsState = mutableListOf<CommunalHubState.CommunalWidgetItem>()
        widgetsFromDb.keys.forEach { rankItem ->
            widgetsState.add(
                CommunalHubState.CommunalWidgetItem().apply {
                    rank = rankItem.rank
                    widgetId = widgetsFromDb[rankItem]!!.widgetId
                    componentName = widgetsFromDb[rankItem]?.componentName
                }
            )
        }
        return CommunalHubState().apply { widgets = widgetsState.toTypedArray() }
    }

    /**
     * Writes [data] to disk as a file as [FILE_NAME], overwriting existing content if any.
     *
     * @throws FileNotFoundException if the file exists but is a directory rather than a regular
     *   file, does not exist but cannot be created, or cannot be opened for any other reason.
     * @throws SecurityException if write access is denied.
     * @throws IOException if writing fails.
     */
    @WorkerThread
    fun writeBytesToDisk(data: ByteArray) {
        val output = FileOutputStream(getFile())
        output.write(data)
        output.close()
    }

    /**
     * Reads bytes from [FILE_NAME], and throws if file does not exist.
     *
     * @throws FileNotFoundException if file does not exist.
     * @throws SecurityException if read access is denied.
     * @throws IOException if reading fails.
     */
    @WorkerThread
    fun readBytesFromDisk(): ByteArray {
        val input = FileInputStream(getFile())
        val bytes = input.readAllBytes()
        input.close()

        return bytes
    }

    /**
     * Removes the bytes written to disk at [FILE_NAME].
     *
     * @return True if and only if the file is successfully deleted
     * @throws SecurityException if permission is denied
     */
    @WorkerThread
    fun clear(): Boolean {
        return getFile().delete()
    }

    /** Whether [FILE_NAME] exists. */
    @WorkerThread
    fun fileExists(): Boolean {
        return getFile().exists()
    }

    @WorkerThread
    private fun getFile(): File {
        return File(context.filesDir, FILE_NAME)
    }

    companion object {
        private const val FILE_NAME = "communal_restore"
    }
}
