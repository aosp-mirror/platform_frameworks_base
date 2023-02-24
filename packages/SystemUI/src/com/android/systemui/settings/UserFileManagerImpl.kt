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
import javax.inject.Inject

/**
 * Implementation for retrieving file paths for file storage of system and secondary users.
 * Files lie in {File Directory}/UserFileManager/{User Id} for secondary user.
 * For system user, we use the conventional {File Directory}
 */
@SysUISingleton
class UserFileManagerImpl @Inject constructor(
    // Context of system process and system user.
    val context: Context,
    val userManager: UserManager,
    val broadcastDispatcher: BroadcastDispatcher,
    @Background val backgroundExecutor: DelayableExecutor
) : UserFileManager, CoreStartable(context) {
    companion object {
        private const val FILES = "files"
        @VisibleForTesting internal const val SHARED_PREFS = "shared_prefs"
        @VisibleForTesting internal const val ID = "UserFileManager"
    }

   private val broadcastReceiver = object : BroadcastReceiver() {
        /**
         * Listen to Intent.ACTION_USER_REMOVED to clear user data.
         */
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_REMOVED) {
                clearDeletedUserData()
            }
        }
    }

    /**
     * Poll for user-specific directories to delete upon start up.
     */
    override fun start() {
        clearDeletedUserData()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_USER_REMOVED)
        }
        broadcastDispatcher.registerReceiver(broadcastReceiver, filter, backgroundExecutor)
    }

    /**
     * Return the file based on current user.
     */
    override fun getFile(fileName: String, userId: Int): File {
        return if (UserHandle(userId).isSystem) {
            Environment.buildPath(
                context.filesDir,
                fileName
            )
        } else {
            val secondaryFile = Environment.buildPath(
                context.filesDir,
                ID,
                userId.toString(),
                FILES,
                fileName
            )
            ensureParentDirExists(secondaryFile)
            secondaryFile
        }
    }

    /**
     * Get shared preferences from user.
     */
    override fun getSharedPreferences(
        fileName: String,
        @Context.PreferencesMode mode: Int,
        userId: Int
    ): SharedPreferences {
        if (UserHandle(userId).isSystem) {
            return context.getSharedPreferences(fileName, mode)
        }
        val secondaryUserDir = Environment.buildPath(
            context.filesDir,
            ID,
            userId.toString(),
            SHARED_PREFS,
            fileName
        )

        ensureParentDirExists(secondaryUserDir)
        return context.getSharedPreferences(secondaryUserDir, mode)
    }

    /**
     * Remove dirs for deleted users.
     */
    @VisibleForTesting
    internal fun clearDeletedUserData() {
        backgroundExecutor.execute {
            val file = Environment.buildPath(context.filesDir, ID)
            if (!file.exists()) return@execute
            val aliveUsers = userManager.aliveUsers.map { it.id.toString() }
            val dirsToDelete = file.list().filter { !aliveUsers.contains(it) }

            dirsToDelete.forEach { dir ->
                try {
                    val dirToDelete = Environment.buildPath(
                        file,
                        dir,
                    )
                    dirToDelete.deleteRecursively()
                } catch (e: Exception) {
                    Log.e(ID, "Deletion failed.", e)
                }
            }
        }
    }

    /**
     * Checks to see if parent dir of the file exists. If it does not, we create the parent dirs
     * recursively.
     */
    @VisibleForTesting
    internal fun ensureParentDirExists(file: File) {
        val parent = file.parentFile
        if (!parent.exists()) {
            if (!parent.mkdirs()) {
                Log.e(ID, "Could not create parent directory for file: ${file.absolutePath}")
            }
        }
    }
}
