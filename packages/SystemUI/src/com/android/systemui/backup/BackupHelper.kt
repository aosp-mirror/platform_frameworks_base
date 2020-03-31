/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.backup

import android.app.backup.BackupAgentHelper
import android.app.backup.BackupDataInputStream
import android.app.backup.BackupDataOutput
import android.app.backup.FileBackupHelper
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import android.util.Log
import com.android.systemui.controls.controller.AuxiliaryPersistenceWrapper
import com.android.systemui.controls.controller.ControlsFavoritePersistenceWrapper

/**
 * Helper for backing up elements in SystemUI
 *
 * This helper is invoked by BackupManager whenever a backup or restore is required in SystemUI.
 * The helper can be used to back up any element that is stored in [Context.getFilesDir].
 *
 * After restoring is done, a [ACTION_RESTORE_FINISHED] intent will be send to SystemUI user 0,
 * indicating that restoring is finished for a given user.
 */
class BackupHelper : BackupAgentHelper() {

    companion object {
        private const val TAG = "BackupHelper"
        internal const val CONTROLS = ControlsFavoritePersistenceWrapper.FILE_NAME
        private const val NO_OVERWRITE_FILES_BACKUP_KEY = "systemui.files_no_overwrite"
        val controlsDataLock = Any()
        const val ACTION_RESTORE_FINISHED = "com.android.systemui.backup.RESTORE_FINISHED"
        private const val PERMISSION_SELF = "com.android.systemui.permission.SELF"
    }

    override fun onCreate() {
        super.onCreate()
        // The map in mapOf is guaranteed to be order preserving
        val controlsMap = mapOf(CONTROLS to getPPControlsFile(this))
        NoOverwriteFileBackupHelper(controlsDataLock, this, controlsMap).also {
            addHelper(NO_OVERWRITE_FILES_BACKUP_KEY, it)
        }
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        val intent = Intent(ACTION_RESTORE_FINISHED).apply {
            `package` = packageName
            putExtra(Intent.EXTRA_USER_ID, userId)
            flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY
        }
        sendBroadcastAsUser(intent, UserHandle.SYSTEM, PERMISSION_SELF)
    }

    /**
     * Helper class for restoring files ONLY if they are not present.
     *
     * A [Map] between filenames and actions (functions) is passed to indicate post processing
     * actions to be taken after each file is restored.
     *
     * @property lock a lock to hold while backing up and restoring the files.
     * @property context the context of the [BackupAgent]
     * @property fileNamesAndPostProcess a map from the filenames to back up and the post processing
     *                                   actions to take
     */
    private class NoOverwriteFileBackupHelper(
        val lock: Any,
        val context: Context,
        val fileNamesAndPostProcess: Map<String, () -> Unit>
    ) : FileBackupHelper(context, *fileNamesAndPostProcess.keys.toTypedArray()) {

        override fun restoreEntity(data: BackupDataInputStream) {
            val file = Environment.buildPath(context.filesDir, data.key)
            if (file.exists()) {
                Log.w(TAG, "File " + data.key + " already exists. Skipping restore.")
                return
            }
            synchronized(lock) {
                super.restoreEntity(data)
                fileNamesAndPostProcess.get(data.key)?.invoke()
            }
        }

        override fun performBackup(
            oldState: ParcelFileDescriptor?,
            data: BackupDataOutput?,
            newState: ParcelFileDescriptor?
        ) {
            synchronized(lock) {
                super.performBackup(oldState, data, newState)
            }
        }
    }
}
private fun getPPControlsFile(context: Context): () -> Unit {
    return {
        val filesDir = context.filesDir
        val file = Environment.buildPath(filesDir, BackupHelper.CONTROLS)
        if (file.exists()) {
            val dest = Environment.buildPath(filesDir,
                AuxiliaryPersistenceWrapper.AUXILIARY_FILE_NAME)
            file.copyTo(dest)
            val jobScheduler = context.getSystemService(JobScheduler::class.java)
            jobScheduler?.schedule(
                AuxiliaryPersistenceWrapper.DeletionJobService.getJobForContext(context))
        }
    }
}