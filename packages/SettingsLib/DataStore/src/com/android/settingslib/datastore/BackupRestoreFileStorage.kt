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

package com.android.settingslib.datastore

import android.content.Context
import androidx.core.content.ContextCompat
import java.io.File

/**
 * A file-based storage with backup and restore support.
 *
 * [BackupRestoreFileArchiver] will handle the backup and restore based on file path for all
 * subclasses.
 *
 * @param context Context to retrieve data dir
 * @param storageFilePath Storage file path, which MUST be relative to the [Context.getDataDir]
 *   folder. This is used as the entity name for backup and restore.
 */
abstract class BackupRestoreFileStorage(
    val context: Context,
    val storageFilePath: String,
) : BackupRestoreStorage() {

    /** The absolute path of the file to backup. */
    open val backupFile: File
        get() = File(context.dataDirCompat, storageFilePath)

    /** The absolute path of the file to restore. */
    open val restoreFile: File
        get() = backupFile

    fun checkFilePaths() {
        if (storageFilePath.isEmpty() || storageFilePath[0] == File.separatorChar) {
            throw IllegalArgumentException("$storageFilePath is not valid path")
        }
        if (!backupFile.isAbsolute) {
            throw IllegalArgumentException("backupFile is not absolute")
        }
        if (!restoreFile.isAbsolute) {
            throw IllegalArgumentException("restoreFile is not absolute")
        }
    }

    /**
     * Callback before [backupFile] is backed up.
     *
     * @param file equals to [backupFile]
     */
    open fun prepareBackup(file: File) {}

    /**
     * Callback when [backupFile] is restored.
     *
     * @param file equals to [backupFile]
     */
    open fun onBackupFinished(file: File) {}

    /**
     * Callback when [restoreFile] is restored.
     *
     * @param file equals to [restoreFile]
     */
    open fun onRestoreFinished(file: File) {}

    final override fun createBackupRestoreEntities(): List<BackupRestoreEntity> = listOf()
}

internal val Context.dataDirCompat: File
    get() = ContextCompat.getDataDir(this)!!
