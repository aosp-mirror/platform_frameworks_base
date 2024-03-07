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

import android.app.backup.BackupDataInputStream
import android.content.Context
import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.CheckedInputStream

/**
 * File archiver to handle backup and restore for all the [BackupRestoreFileStorage] subclasses.
 *
 * Compared with [android.app.backup.FileBackupHelper], this class supports forward-compatibility
 * like the [com.google.android.libraries.backup.PersistentBackupAgentHelper]: the app does not need
 * to know the list of files in advance at restore time.
 */
internal class BackupRestoreFileArchiver(
    private val context: Context,
    private val fileStorages: List<BackupRestoreFileStorage>,
) : BackupRestoreStorage() {
    override val name: String
        get() = "file_archiver"

    override fun createBackupRestoreEntities(): List<BackupRestoreEntity> =
        fileStorages.map { it.toBackupRestoreEntity() }

    override fun wrapBackupOutputStream(codec: BackupCodec, outputStream: OutputStream) =
        outputStream

    override fun wrapRestoreInputStream(codec: BackupCodec, inputStream: InputStream) = inputStream

    override fun restoreEntity(data: BackupDataInputStream) {
        val key = data.key
        val fileStorage = fileStorages.firstOrNull { it.storageFilePath == key }
        val file =
            if (fileStorage != null) {
                if (!fileStorage.enableRestore()) {
                    Log.i(LOG_TAG, "[$name] $key restore disabled")
                    return
                }
                fileStorage.restoreFile
            } else { // forward-compatibility
                Log.i(LOG_TAG, "Restore unknown file $key")
                File(context.dataDirCompat, key)
            }
        Log.i(LOG_TAG, "[$name] Restore ${data.size()} bytes for $key to $file")
        val inputStream = LimitedNoCloseInputStream(data)
        checksum.reset()
        val checkedInputStream = CheckedInputStream(inputStream, checksum)
        try {
            val codec = BackupCodec.fromId(checkedInputStream.read().toByte())
            if (fileStorage != null && fileStorage.defaultCodec().id != codec.id) {
                Log.i(
                    LOG_TAG,
                    "[$name] $key different codec: ${codec.id}, ${fileStorage.defaultCodec().id}"
                )
            }
            file.parentFile?.mkdirs() // ensure parent folders are created
            val wrappedInputStream = codec.decode(checkedInputStream)
            val bytesCopied = file.outputStream().use { wrappedInputStream.copyTo(it) }
            Log.i(LOG_TAG, "[$name] $key restore $bytesCopied bytes with ${codec.name}")
            fileStorage?.onRestoreFinished(file)
            entityStates[key] = checksum.value
        } catch (e: Exception) {
            Log.e(LOG_TAG, "[$name] Fail to restore $key", e)
        }
    }

    override fun onRestoreFinished() {
        fileStorages.forEach { it.onRestoreFinished() }
    }
}

private fun BackupRestoreFileStorage.toBackupRestoreEntity() =
    object : BackupRestoreEntity {
        override val key: String
            get() = storageFilePath

        override fun backup(
            backupContext: BackupContext,
            outputStream: OutputStream,
        ): EntityBackupResult {
            if (!enableBackup(backupContext)) {
                Log.i(LOG_TAG, "[$name] $key backup disabled")
                return EntityBackupResult.INTACT
            }
            val file = backupFile
            prepareBackup(file)
            if (!file.exists()) {
                Log.i(LOG_TAG, "[$name] $key not exist")
                return EntityBackupResult.DELETE
            }
            val codec = codec() ?: defaultCodec()
            // MUST close to flush the data
            wrapBackupOutputStream(codec, outputStream).use { stream ->
                val bytesCopied = file.inputStream().use { it.copyTo(stream) }
                Log.i(LOG_TAG, "[$name] $key backup $bytesCopied bytes with ${codec.name}")
            }
            onBackupFinished(file)
            return EntityBackupResult.UPDATE
        }

        override fun restore(restoreContext: RestoreContext, inputStream: InputStream) {
            // no-op, BackupRestoreFileArchiver#restoreEntity will restore files
        }
    }
