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
 * Implementation for retrieving file paths for file storage of system and secondary users. Files
 * lie in {File Directory}/UserFileManager/{User Id} for secondary user. For system user, we use the
 * conventional {File Directory}
 */
@SysUISingleton
class UserFileManagerImpl
@Inject
constructor(
    // Context of system process and system user.
    private val context: Context,
    val userManager: UserManager,
    val broadcastDispatcher: BroadcastDispatcher,
    @Background val backgroundExecutor: DelayableExecutor
) : UserFileManager, CoreStartable {
    companion object {
        private const val FILES = "files"
        const val SHARED_PREFS = "shared_prefs"
        @VisibleForTesting internal const val ID = "UserFileManager"

        /** Returns `true` if the given user ID is that for the primary/system user. */
        fun isPrimaryUser(userId: Int): Boolean {
            return UserHandle(userId).isSystem
        }

        /**
         * Returns a [File] pointing to the correct path for a secondary user ID.
         *
         * Note that there is no check for the type of user. This should only be called for
         * secondary users, never for the system user. For that, make sure to call [isPrimaryUser].
         *
         * Note also that there is no guarantee that the parent directory structure for the file
         * exists on disk. For that, call [ensureParentDirExists].
         *
         * @param context The context
         * @param fileName The name of the file
         * @param directoryName The name of the directory that would contain the file
         * @param userId The ID of the user to build a file path for
         */
        fun secondaryUserFile(
            context: Context,
            fileName: String,
            directoryName: String,
            userId: Int,
        ): File {
            return Environment.buildPath(
                context.filesDir,
                ID,
                userId.toString(),
                directoryName,
                fileName,
            )
        }

        /**
         * Checks to see if parent dir of the file exists. If it does not, we create the parent dirs
         * recursively.
         */
        fun ensureParentDirExists(file: File) {
            val parent = file.parentFile
            if (!parent.exists()) {
                if (!parent.mkdirs()) {
                    Log.e(ID, "Could not create parent directory for file: ${file.absolutePath}")
                }
            }
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

    /** Return the file based on current user. */
    override fun getFile(fileName: String, userId: Int): File {
        return if (isPrimaryUser(userId)) {
            Environment.buildPath(context.filesDir, fileName)
        } else {
            val secondaryFile =
                secondaryUserFile(
                    context = context,
                    userId = userId,
                    directoryName = FILES,
                    fileName = fileName,
                )
            ensureParentDirExists(secondaryFile)
            secondaryFile
        }
    }

    /** Get shared preferences from user. */
    override fun getSharedPreferences(
        fileName: String,
        @Context.PreferencesMode mode: Int,
        userId: Int
    ): SharedPreferences {
        if (isPrimaryUser(userId)) {
            return context.getSharedPreferences(fileName, mode)
        }

        val secondaryUserDir =
            secondaryUserFile(
                context = context,
                fileName = fileName,
                directoryName = SHARED_PREFS,
                userId = userId,
            )

        ensureParentDirExists(secondaryUserDir)
        return context.getSharedPreferences(secondaryUserDir, mode)
    }

    /** Remove dirs for deleted users. */
    @VisibleForTesting
    internal fun clearDeletedUserData() {
        backgroundExecutor.execute {
            val file = Environment.buildPath(context.filesDir, ID)
            if (!file.exists()) return@execute
            val aliveUsers = userManager.aliveUsers.map { it.id.toString() }
            val dirsToDelete = file.list().filter { !aliveUsers.contains(it) }

            dirsToDelete.forEach { dir ->
                try {
                    val dirToDelete =
                        Environment.buildPath(
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
}
