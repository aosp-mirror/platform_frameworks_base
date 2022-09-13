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

package com.android.settingslib.spaprivileged.model.app

import android.app.AppGlobals
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED
import android.content.pm.PackageManager
import android.util.Log
import com.android.settingslib.spa.framework.util.asyncFilter

private const val TAG = "PackageManagers"

object PackageManagers {
    private val iPackageManager by lazy { AppGlobals.getPackageManager() }

    fun getPackageInfoAsUser(packageName: String, userId: Int): PackageInfo? =
        getPackageInfoAsUser(packageName, 0, userId)

    fun getApplicationInfoAsUser(packageName: String, userId: Int): ApplicationInfo =
        PackageManager.getApplicationInfoAsUserCached(packageName, 0, userId)

    fun ApplicationInfo.hasRequestPermission(permission: String): Boolean {
        val packageInfo = getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId)
        return packageInfo?.requestedPermissions?.let {
            permission in it
        } ?: false
    }

    fun ApplicationInfo.hasGrantPermission(permission: String): Boolean {
        val packageInfo = getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS, userId)
            ?: return false
        val index = packageInfo.requestedPermissions.indexOf(permission)
        return index >= 0 &&
            packageInfo.requestedPermissionsFlags[index].hasFlag(REQUESTED_PERMISSION_GRANTED)
    }

    suspend fun getAppOpPermissionPackages(userId: Int, permission: String): Set<String> =
        iPackageManager.getAppOpPermissionPackages(permission, userId).asIterable().asyncFilter {
            iPackageManager.isPackageAvailable(it, userId)
        }.toSet()

    private fun getPackageInfoAsUser(packageName: String, flags: Int, userId: Int): PackageInfo? =
        try {
            PackageManager.getPackageInfoAsUserCached(packageName, flags.toLong(), userId)
        } catch (e: PackageManager.NameNotFoundException) {
            Log.w(TAG, "getPackageInfoAsUserCached() failed", e)
            null
        }

    private fun Int.hasFlag(flag: Int) = (this and flag) > 0
}
