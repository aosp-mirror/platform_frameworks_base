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

import android.app.backup.BackupDataOutput
import android.app.backup.BackupHelper
import androidx.annotation.BinderThread
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Entity for back up and restore.
 *
 * Note that backup/restore callback is invoked on the binder thread.
 */
interface BackupRestoreEntity {
    /**
     * Key of the entity.
     *
     * The key string must be unique within the data set. Note that it is invalid if the first
     * character is \uFF00 or higher.
     *
     * @see BackupDataOutput.writeEntityHeader
     */
    val key: String

    /**
     * Codec used to encode/decode the backup data.
     *
     * When it is null, the [BackupRestoreStorage.defaultCodec] will be used.
     */
    fun codec(): BackupCodec? = null

    /**
     * Backs up the entity.
     *
     * Back up data in predictable order (e.g. use `TreeMap` instead of `HashMap`), otherwise data
     * will be backed up needlessly.
     *
     * @param backupContext context for backup
     * @param outputStream output stream to back up data
     * @return backup result
     */
    @BinderThread
    @Throws(IOException::class)
    fun backup(backupContext: BackupContext, outputStream: OutputStream): EntityBackupResult

    /**
     * Restores the entity.
     *
     * @param restoreContext context for restore
     * @param inputStream An open input stream from which the backup data can be read.
     * @see BackupHelper.restoreEntity
     */
    @BinderThread
    @Throws(IOException::class)
    fun restore(restoreContext: RestoreContext, inputStream: InputStream)
}

/** Result of the backup operation. */
enum class EntityBackupResult {
    /** Update the entity. */
    UPDATE,
    /** Leave the entity intact. */
    INTACT,
    /** Delete the entity. */
    DELETE,
}
