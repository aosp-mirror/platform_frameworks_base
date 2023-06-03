/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.notetask

import android.app.role.RoleManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.util.Log
import javax.inject.Inject

internal class NoteTaskInfoResolver
@Inject
constructor(
    private val context: Context,
    private val roleManager: RoleManager,
    private val packageManager: PackageManager,
) {
    fun resolveInfo(): NoteTaskInfo? {
        // TODO(b/267634412): Select UserHandle depending on where the user initiated note-taking.
        val user = context.user
        val packageName = roleManager.getRoleHoldersAsUser(ROLE_NOTES, user).firstOrNull()

        if (packageName.isNullOrEmpty()) return null

        return NoteTaskInfo(packageName, packageManager.getUidOf(packageName, user))
    }

    /** Package name and kernel user-ID of a note-taking app. */
    data class NoteTaskInfo(val packageName: String, val uid: Int)

    companion object {
        private val TAG = NoteTaskInfoResolver::class.simpleName.orEmpty()

        private val EMPTY_APPLICATION_INFO_FLAGS = PackageManager.ApplicationInfoFlags.of(0)!!

        /**
         * Returns the kernel user-ID of [packageName] for a [user]. Returns zero if the app cannot
         * be found.
         */
        private fun PackageManager.getUidOf(packageName: String, user: UserHandle): Int {
            val applicationInfo =
                try {
                    getApplicationInfoAsUser(packageName, EMPTY_APPLICATION_INFO_FLAGS, user)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.e(TAG, "Couldn't find notes app UID", e)
                    return 0
                }
            return applicationInfo.uid
        }

        // TODO(b/265912743): Use RoleManager.NOTES_ROLE instead.
        const val ROLE_NOTES = "android.app.role.NOTES"
    }
}
