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

import android.app.backup.BackupAgent
import android.app.backup.BackupDataOutput
import android.app.backup.BackupHelper
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Context for backup.
 *
 * @see BackupHelper.performBackup
 * @see BackupDataOutput
 */
class BackupContext
internal constructor(
    /** An open, read/write BackupDataOutput pointing to the backup data destination. */
    private val data: BackupDataOutput,
) {
    /**
     * The quota in bytes for the application's current backup operation.
     *
     * @see [BackupDataOutput.getQuota]
     */
    val quota: Long
        @RequiresApi(Build.VERSION_CODES.O) get() = data.quota

    /**
     * Additional information about the backup transport.
     *
     * See [BackupAgent] for supported flags.
     *
     * @see [BackupDataOutput.getTransportFlags]
     */
    val transportFlags: Int
        @RequiresApi(Build.VERSION_CODES.P) get() = data.transportFlags
}

/**
 * Context for restore.
 *
 * @param key Entity key
 */
class RestoreContext internal constructor(val key: String)
