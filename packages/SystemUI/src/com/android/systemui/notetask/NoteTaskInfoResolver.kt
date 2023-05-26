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

@file:OptIn(InternalNoteTaskApi::class)

package com.android.systemui.notetask

import android.app.role.RoleManager
import android.app.role.RoleManager.ROLE_NOTES
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ApplicationInfoFlags
import android.os.UserHandle
import android.util.Log
import com.android.systemui.notetask.NoteTaskRoleManagerExt.getDefaultRoleHolderAsUser
import javax.inject.Inject

class NoteTaskInfoResolver
@Inject
constructor(
    private val roleManager: RoleManager,
    private val packageManager: PackageManager,
) {

    fun resolveInfo(
        entryPoint: NoteTaskEntryPoint? = null,
        isKeyguardLocked: Boolean = false,
        user: UserHandle,
    ): NoteTaskInfo? {
        val packageName = roleManager.getDefaultRoleHolderAsUser(ROLE_NOTES, user)

        if (packageName.isNullOrEmpty()) return null

        return NoteTaskInfo(
            packageName = packageName,
            uid = packageManager.getUidOf(packageName, user),
            user = user,
            entryPoint = entryPoint,
            isKeyguardLocked = isKeyguardLocked,
        )
    }

    companion object {
        private val TAG = NoteTaskInfoResolver::class.simpleName.orEmpty()

        private val EMPTY_APPLICATION_INFO_FLAGS = ApplicationInfoFlags.of(0)!!

        /**
         * Returns the kernel user-ID of [packageName] for a [user]. Returns zero if the app cannot
         * be found.
         */
        private fun PackageManager.getUidOf(packageName: String, user: UserHandle): Int =
            try {
                getApplicationInfoAsUser(packageName, EMPTY_APPLICATION_INFO_FLAGS, user).uid
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "Couldn't find notes app UID", e)
                0
            }
    }
}
