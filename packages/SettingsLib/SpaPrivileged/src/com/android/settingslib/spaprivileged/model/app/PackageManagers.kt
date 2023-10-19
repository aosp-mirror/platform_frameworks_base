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
import com.android.settingslib.spa.framework.util.asyncFilter

interface IPackageManagers {
    fun getPackageInfoAsUser(packageName: String, userId: Int): PackageInfo?
    fun getApplicationInfoAsUser(packageName: String, userId: Int): ApplicationInfo?

    /** Checks whether a package is installed for a given user. */
    fun isPackageInstalledAsUser(packageName: String, userId: Int): Boolean
    fun ApplicationInfo.hasRequestPermission(permission: String): Boolean

    /** Checks whether a permission is currently granted to the application. */
    fun ApplicationInfo.hasGrantPermission(permission: String): Boolean

    suspend fun getAppOpPermissionPackages(userId: Int, permission: String): Set<String>
    fun getPackageInfoAsUser(packageName: String, flags: Long, userId: Int): PackageInfo?
}

object PackageManagers : IPackageManagers by PackageManagersImpl(PackageManagerWrapperImpl)

internal interface PackageManagerWrapper {
    fun getPackageInfoAsUserCached(
        packageName: String,
        flags: Long,
        userId: Int,
    ): PackageInfo?
}

internal object PackageManagerWrapperImpl : PackageManagerWrapper {
    override fun getPackageInfoAsUserCached(
        packageName: String,
        flags: Long,
        userId: Int,
    ): PackageInfo? = PackageManager.getPackageInfoAsUserCached(packageName, flags, userId)
}

internal class PackageManagersImpl(
    private val packageManagerWrapper: PackageManagerWrapper,
) : IPackageManagers {
    private val iPackageManager by lazy { AppGlobals.getPackageManager() }

    override fun getPackageInfoAsUser(packageName: String, userId: Int): PackageInfo? =
        getPackageInfoAsUser(packageName, 0, userId)

    override fun getApplicationInfoAsUser(packageName: String, userId: Int): ApplicationInfo? =
        PackageManager.getApplicationInfoAsUserCached(packageName, 0, userId)

    override fun isPackageInstalledAsUser(packageName: String, userId: Int): Boolean =
        getApplicationInfoAsUser(packageName, userId)?.hasFlag(ApplicationInfo.FLAG_INSTALLED)
            ?: false

    override fun ApplicationInfo.hasRequestPermission(permission: String): Boolean {
        val packageInfo =
            getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS.toLong(), userId)
        return packageInfo?.requestedPermissions?.let {
            permission in it
        } ?: false
    }

    override fun ApplicationInfo.hasGrantPermission(permission: String): Boolean {
        val packageInfo =
            getPackageInfoAsUser(packageName, PackageManager.GET_PERMISSIONS.toLong(), userId)
        val index = packageInfo?.requestedPermissions?.indexOf(permission) ?: return false
        return index >= 0 &&
            checkNotNull(packageInfo.requestedPermissionsFlags)[index]
                .hasFlag(REQUESTED_PERMISSION_GRANTED)
    }

    override suspend fun getAppOpPermissionPackages(userId: Int, permission: String): Set<String> =
        iPackageManager.getAppOpPermissionPackages(permission, userId).asIterable().asyncFilter {
            iPackageManager.isPackageAvailable(it, userId)
        }.toSet()

    override fun getPackageInfoAsUser(packageName: String, flags: Long, userId: Int): PackageInfo? =
        packageManagerWrapper.getPackageInfoAsUserCached(packageName, flags, userId)

    private fun Int.hasFlag(flag: Int) = (this and flag) > 0
}
