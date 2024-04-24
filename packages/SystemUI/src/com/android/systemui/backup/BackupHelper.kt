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
import com.android.app.tracing.traceSection
import com.android.systemui.backup.BackupHelper.Companion.ACTION_RESTORE_FINISHED
import com.android.systemui.communal.data.backup.CommunalBackupHelper
import com.android.systemui.communal.data.backup.CommunalBackupUtils
import com.android.systemui.communal.domain.backup.CommunalPrefsBackupHelper
import com.android.systemui.controls.controller.AuxiliaryPersistenceWrapper
import com.android.systemui.controls.controller.ControlsFavoritePersistenceWrapper
import com.android.systemui.keyguard.domain.backup.KeyguardQuickAffordanceBackupHelper
import com.android.systemui.people.widget.PeopleBackupHelper
import com.android.systemui.res.R
import com.android.systemui.settings.UserFileManagerImpl

/**
 * Helper for backing up elements in SystemUI
 *
 * This helper is invoked by BackupManager whenever a backup or restore is required in SystemUI. The
 * helper can be used to back up any element that is stored in [Context.getFilesDir] or
 * [Context.getSharedPreferences].
 *
 * After restoring is done, a [ACTION_RESTORE_FINISHED] intent will be send to SystemUI user 0,
 * indicating that restoring is finished for a given user.
 */
open class BackupHelper : BackupAgentHelper() {

    companion object {
        const val TAG = "BackupHelper"
        internal const val CONTROLS = ControlsFavoritePersistenceWrapper.FILE_NAME
        private const val NO_OVERWRITE_FILES_BACKUP_KEY = "systemui.files_no_overwrite"
        private const val PEOPLE_TILES_BACKUP_KEY = "systemui.people.shared_preferences"
        private const val KEYGUARD_QUICK_AFFORDANCES_BACKUP_KEY =
            "systemui.keyguard.quickaffordance.shared_preferences"
        private const val COMMUNAL_PREFS_BACKUP_KEY =
            "systemui.communal.shared_preferences"
        private const val COMMUNAL_STATE_BACKUP_KEY = "systemui.communal_state"
        val controlsDataLock = Any()
        const val ACTION_RESTORE_FINISHED = "com.android.systemui.backup.RESTORE_FINISHED"
        const val PERMISSION_SELF = "com.android.systemui.permission.SELF"
    }

    override fun onCreate(userHandle: UserHandle, operationType: Int) {
        super.onCreate()

        addControlsHelper(userHandle.identifier)

        val keys = PeopleBackupHelper.getFilesToBackup()
        addHelper(
            PEOPLE_TILES_BACKUP_KEY,
            PeopleBackupHelper(this, userHandle, keys.toTypedArray())
        )
        addHelper(
            KEYGUARD_QUICK_AFFORDANCES_BACKUP_KEY,
            KeyguardQuickAffordanceBackupHelper(
                context = this,
                userId = userHandle.identifier,
            ),
        )
        if (communalEnabled()) {
            addHelper(
                COMMUNAL_PREFS_BACKUP_KEY,
                CommunalPrefsBackupHelper(
                    context = this,
                    userId = userHandle.identifier,
                )
            )
            addHelper(
                COMMUNAL_STATE_BACKUP_KEY,
                CommunalBackupHelper(userHandle, CommunalBackupUtils(context = this)),
            )
        }
    }

    override fun onRestoreFinished() {
        super.onRestoreFinished()
        val intent =
            Intent(ACTION_RESTORE_FINISHED).apply {
                `package` = packageName
                putExtra(Intent.EXTRA_USER_ID, userId)
                flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY
            }
        sendBroadcastAsUser(intent, UserHandle.SYSTEM, PERMISSION_SELF)
    }

    private fun addControlsHelper(userId: Int) {
        val file = UserFileManagerImpl.createFile(
            userId = userId,
            fileName = CONTROLS,
        )
        // The map in mapOf is guaranteed to be order preserving
        val controlsMap = mapOf(file.getPath() to getPPControlsFile(this, userId))
        NoOverwriteFileBackupHelper(controlsDataLock, this, controlsMap).also {
            addHelper(NO_OVERWRITE_FILES_BACKUP_KEY, it)
        }
    }

    private fun communalEnabled(): Boolean {
        return resources.getBoolean(R.bool.config_communalServiceEnabled)
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
     * ```
     *                                   actions to take
     * ```
     */
    private class NoOverwriteFileBackupHelper(
        val lock: Any,
        val context: Context,
        val fileNamesAndPostProcess: Map<String, () -> Unit>
    ) : FileBackupHelper(context, *fileNamesAndPostProcess.keys.toTypedArray()) {

        override fun restoreEntity(data: BackupDataInputStream) {
            Log.d(TAG, "Starting restore for ${data.key} for user ${context.userId}")
            val file = Environment.buildPath(context.filesDir, data.key)
            if (file.exists()) {
                Log.w(TAG, "File " + data.key + " already exists. Skipping restore.")
                return
            }
            synchronized(lock) {
                traceSection("File restore: ${data.key}") {
                    super.restoreEntity(data)
                }
                Log.d(TAG, "Finishing restore for ${data.key} for user ${context.userId}. " +
                        "Starting postProcess.")
                traceSection("Postprocess: ${data.key}") {
                    fileNamesAndPostProcess.get(data.key)?.invoke()
                }
                Log.d(TAG, "Finishing postprocess for ${data.key} for user ${context.userId}.")
            }
        }

        override fun performBackup(
            oldState: ParcelFileDescriptor?,
            data: BackupDataOutput?,
            newState: ParcelFileDescriptor?
        ) {
            synchronized(lock) { super.performBackup(oldState, data, newState) }
        }
    }
}

private fun getPPControlsFile(context: Context, userId: Int): () -> Unit {
    return {
        val file = UserFileManagerImpl.createFile(
            userId = userId,
            fileName = BackupHelper.CONTROLS,
        )
        if (file.exists()) {
            val dest = UserFileManagerImpl.createFile(
                userId = userId,
                fileName = AuxiliaryPersistenceWrapper.AUXILIARY_FILE_NAME,
            )
            file.copyTo(dest)
            val jobScheduler = context.getSystemService(JobScheduler::class.java)
            jobScheduler?.schedule(
                AuxiliaryPersistenceWrapper.DeletionJobService.getJobForContext(context, userId)
            )
        }
    }
}
