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

import android.app.backup.BackupAgentHelper
import android.app.backup.BackupDataInputStream
import android.app.backup.BackupDataOutput
import android.app.backup.BackupHelper
import android.os.ParcelFileDescriptor
import android.util.Log
import com.google.common.io.ByteStreams
import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream

internal const val LOG_TAG = "BackupRestoreStorage"

/**
 * Storage with backup and restore support. Subclass must implement either [Observable] or
 * [KeyedObservable] interface.
 *
 * The storage is identified by a unique string [name] and data set is split into entities
 * ([BackupRestoreEntity]).
 */
abstract class BackupRestoreStorage : BackupHelper {
    /**
     * A unique string used to disambiguate the various storages within backup agent.
     *
     * It will be used as the `keyPrefix` of [BackupAgentHelper.addHelper].
     */
    abstract val name: String

    private val entities: List<BackupRestoreEntity> by lazy { createBackupRestoreEntities() }

    /** Entities to back up and restore. */
    abstract fun createBackupRestoreEntities(): List<BackupRestoreEntity>

    /** Default codec used to encode/decode the entity data. */
    open fun defaultCodec(): BackupCodec = BackupZipCodec.BEST_COMPRESSION

    override fun performBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput,
        newState: ParcelFileDescriptor,
    ) {
        val backupContext = BackupContext(oldState, data, newState)
        if (!enableBackup(backupContext)) {
            Log.i(LOG_TAG, "[$name] Backup disabled")
            return
        }
        Log.i(LOG_TAG, "[$name] Backup start")
        for (entity in entities) {
            val key = entity.key
            val outputStream = ByteArrayOutputStream()
            val codec = entity.codec() ?: defaultCodec()
            val result =
                try {
                    entity.backup(backupContext, wrapBackupOutputStream(codec, outputStream))
                } catch (exception: Exception) {
                    Log.e(LOG_TAG, "[$name] Fail to backup entity $key", exception)
                    continue
                }
            when (result) {
                EntityBackupResult.UPDATE -> {
                    val payload = outputStream.toByteArray()
                    val size = payload.size
                    data.writeEntityHeader(key, size)
                    data.writeEntityData(payload, size)
                    Log.i(LOG_TAG, "[$name] Backup entity $key: $size bytes")
                }
                EntityBackupResult.INTACT -> {
                    Log.i(LOG_TAG, "[$name] Backup entity $key intact")
                }
                EntityBackupResult.DELETE -> {
                    data.writeEntityHeader(key, -1)
                    Log.i(LOG_TAG, "[$name] Backup entity $key deleted")
                }
            }
        }
        Log.i(LOG_TAG, "[$name] Backup end")
    }

    /** Returns if backup is enabled. */
    open fun enableBackup(backupContext: BackupContext): Boolean = true

    open fun wrapBackupOutputStream(codec: BackupCodec, outputStream: OutputStream): OutputStream {
        // write a codec id header for safe restore
        outputStream.write(codec.id.toInt())
        return codec.encode(outputStream)
    }

    override fun restoreEntity(data: BackupDataInputStream) {
        val key = data.key
        if (!enableRestore()) {
            Log.i(LOG_TAG, "[$name] Restore disabled, ignore entity $key")
            return
        }
        val entity = entities.firstOrNull { it.key == key }
        if (entity == null) {
            Log.w(LOG_TAG, "[$name] Cannot find handler for entity $key")
            return
        }
        Log.i(LOG_TAG, "[$name] Restore $key: ${data.size()} bytes")
        val restoreContext = RestoreContext(key)
        val codec = entity.codec() ?: defaultCodec()
        try {
            entity.restore(
                restoreContext,
                wrapRestoreInputStream(codec, LimitedNoCloseInputStream(data))
            )
        } catch (exception: Exception) {
            Log.e(LOG_TAG, "[$name] Fail to restore entity $key", exception)
        }
    }

    /** Returns if restore is enabled. */
    open fun enableRestore(): Boolean = true

    open fun wrapRestoreInputStream(
        codec: BackupCodec,
        inputStream: InputStream,
    ): InputStream {
        // read the codec id first to check if it is expected codec
        val id = inputStream.read()
        val expectedId = codec.id.toInt()
        if (id == expectedId) return codec.decode(inputStream)
        Log.i(LOG_TAG, "Expect codec id $expectedId but got $id")
        return BackupCodec.fromId(id.toByte()).decode(inputStream)
    }

    override fun writeNewStateDescription(newState: ParcelFileDescriptor) {}
}

/**
 * Wrapper of [BackupDataInputStream], limiting the number of bytes that can be read and make
 * [close] no-op.
 */
class LimitedNoCloseInputStream(inputStream: BackupDataInputStream) :
    FilterInputStream(ByteStreams.limit(inputStream, inputStream.size().toLong())) {
    override fun close() {
        // do not close original input stream
    }
}
