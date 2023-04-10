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

package com.android.systemui.settings

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.Environment
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.android.systemui.CoreStartable
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.util.concurrency.DelayableExecutor
import java.io.File
import java.io.FilenameFilter
import javax.inject.Inject

/**
 * Implementation for retrieving file paths for file storage of system and secondary users. For
 * non-system users, files will be prepended by a special prefix containing the user id.
 */
@SysUISingleton
class UserFileManagerImpl
@Inject
constructor(
    private val context: Context,
    val userManager: UserManager,
    val broadcastDispatcher: BroadcastDispatcher,
    @Background val backgroundExecutor: DelayableExecutor
) : UserFileManager, CoreStartable {
    companion object {
        private const val PREFIX = "__USER_"
        private const val TAG = "UserFileManagerImpl"
        const val ROOT_DIR = "UserFileManager"
        const val FILES = "files"
        const val SHARED_PREFS = "shared_prefs"

        /**
         * Returns a File object with a relative path, built from the userId for non-system users
         */
        fun createFile(fileName: String, userId: Int): File {
            return if (isSystemUser(userId)) {
                File(fileName)
            } else {
                File(getFilePrefix(userId) + fileName)
            }
        }

        fun createLegacyFile(context: Context, dir: String, fileName: String, userId: Int): File? {
            return if (isSystemUser(userId)) {
                null
            } else {
                return Environment.buildPath(
                    context.filesDir,
                    ROOT_DIR,
                    userId.toString(),
                    dir,
                    fileName
                )
            }
        }

        fun getFilePrefix(userId: Int): String {
            return PREFIX + userId.toString() + "_"
        }

        /** Returns `true` if the given user ID is that for the system user. */
        private fun isSystemUser(userId: Int): Boolean {
            return UserHandle(userId).isSystem
        }
    }

    private val broadcastReceiver =
        object : BroadcastReceiver() {
            /** Listen to Intent.ACTION_USER_REMOVED to clear user data. */
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_USER_REMOVED) {
                    clearDeletedUserData()
                }
            }
        }

    /** Poll for user-specific directories to delete upon start up. */
    override fun start() {
        clearDeletedUserData()
        val filter = IntentFilter().apply { addAction(Intent.ACTION_USER_REMOVED) }
        broadcastDispatcher.registerReceiver(broadcastReceiver, filter, backgroundExecutor)
    }

    /**
     * Return the file based on current user. Files for all users will exist in [context.filesDir],
     * but non system user files will be prepended with [getFilePrefix].
     */
    override fun getFile(fileName: String, userId: Int): File {
        val file = File(context.filesDir, createFile(fileName, userId).path)
        createLegacyFile(context, FILES, fileName, userId)?.run { migrate(file, this) }
        return file
    }

    /**
     * Get shared preferences from user. Files for all users will exist in the shared_prefs dir, but
     * non system user files will be prepended with [getFilePrefix].
     */
    override fun getSharedPreferences(
        fileName: String,
        @Context.PreferencesMode mode: Int,
        userId: Int
    ): SharedPreferences {
        val file = createFile(fileName, userId)
        createLegacyFile(context, SHARED_PREFS, "$fileName.xml", userId)?.run {
            val path = Environment.buildPath(context.dataDir, SHARED_PREFS, "${file.path}.xml")
            migrate(path, this)
        }
        return context.getSharedPreferences(file.path, mode)
    }

    /** Remove files for deleted users. */
    @VisibleForTesting
    internal fun clearDeletedUserData() {
        backgroundExecutor.execute {
            deleteFiles(context.filesDir)
            deleteFiles(File(context.dataDir, SHARED_PREFS))
        }
    }

    private fun migrate(dest: File, source: File) {
        if (source.exists()) {
            try {
                val parent = source.getParentFile()
                source.renameTo(dest)

                deleteParentDirsIfEmpty(parent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename and delete ${source.path}", e)
            }
        }
    }

    private fun deleteParentDirsIfEmpty(dir: File?) {
        if (dir != null && dir.listFiles().size == 0) {
            val priorParent = dir.parentFile
            val isRoot = dir.name == ROOT_DIR
            dir.delete()

            if (!isRoot) {
                deleteParentDirsIfEmpty(priorParent)
            }
        }
    }

    private fun deleteFiles(parent: File) {
        val aliveUserFilePrefix = userManager.aliveUsers.map { getFilePrefix(it.id) }
        val filesToDelete =
            parent.listFiles(
                FilenameFilter { _, name ->
                    name.startsWith(PREFIX) &&
                        aliveUserFilePrefix.filter { name.startsWith(it) }.isEmpty()
                }
            )

        // This can happen in test environments
        if (filesToDelete == null) {
            Log.i(TAG, "Empty directory: ${parent.path}")
        } else {
            filesToDelete.forEach { file ->
                Log.i(TAG, "Deleting file: ${file.path}")
                try {
                    file.delete()
                } catch (e: Exception) {
                    Log.e(TAG, "Deletion failed.", e)
                }
            }
        }
    }
}
