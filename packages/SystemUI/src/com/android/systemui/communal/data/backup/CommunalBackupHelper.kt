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

import android.app.backup.BackupDataInputStream
import android.app.backup.BackupDataOutput
import android.app.backup.BackupHelper
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import android.util.Log
import com.android.systemui.Flags.communalHub
import com.android.systemui.communal.proto.toByteArray
import java.io.IOException

/** Helps with backup & restore of the communal hub widgets. */
class CommunalBackupHelper(
    private val userHandle: UserHandle,
    private val communalBackupUtils: CommunalBackupUtils,
) : BackupHelper {

    override fun performBackup(
        oldState: ParcelFileDescriptor?,
        data: BackupDataOutput?,
        newState: ParcelFileDescriptor?
    ) {
        if (!communalHub()) {
            Log.d(TAG, "Skipping backup. Communal not enabled")
            return
        }

        if (data == null) {
            Log.e(TAG, "Backup failed. Data is null")
            return
        }

        if (!userHandle.isSystem) {
            Log.d(TAG, "Backup skipped for non-system user")
            return
        }

        val state = communalBackupUtils.getCommunalHubState()
        Log.i(TAG, "Backing up communal state: $state")

        val bytes = state.toByteArray()
        try {
            data.writeEntityHeader(ENTITY_KEY, bytes.size)
            data.writeEntityData(bytes, bytes.size)
        } catch (e: IOException) {
            Log.e(TAG, "Backup failed while writing data: ${e.localizedMessage}")
            return
        }

        Log.i(TAG, "Backup complete")
    }

    override fun restoreEntity(data: BackupDataInputStream?) {
        if (data == null) {
            Log.e(TAG, "Restore failed. Data is null")
            return
        }

        if (!userHandle.isSystem) {
            Log.d(TAG, "Restore skipped for non-system user")
            return
        }

        if (data.key != ENTITY_KEY) {
            Log.d(TAG, "Restore skipped due to mismatching entity key")
            return
        }

        val dataSize = data.size()
        val bytes = ByteArray(dataSize)
        try {
            data.read(bytes, /* offset= */ 0, dataSize)
        } catch (e: IOException) {
            Log.e(TAG, "Restore failed while reading data: ${e.localizedMessage}")
            return
        }

        try {
            communalBackupUtils.writeBytesToDisk(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed while writing to disk: ${e.localizedMessage}")
            return
        }

        Log.i(TAG, "Restore complete")
    }

    override fun writeNewStateDescription(newState: ParcelFileDescriptor?) {
        // Do nothing because there is no partial backup
    }

    companion object {
        private const val TAG = "CommunalBackupHelper"

        const val ENTITY_KEY = "communal_hub_state"
    }
}
