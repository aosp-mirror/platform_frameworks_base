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

package com.android.server.permission.access.permission

import android.Manifest
import android.os.Build
import android.util.Slog
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.andInv
import com.android.server.permission.access.util.hasAnyBit
import com.android.server.permission.access.util.hasBits
import com.android.server.pm.pkg.PackageState

class AppIdPermissionUpgrade(private val policy: AppIdPermissionPolicy) {
    /**
     * Upgrade the package permissions, if needed.
     *
     * @param version package version
     *
     * @see [com.android.server.permission.access.util.PackageVersionMigration.getVersion]
     */
    fun MutateStateScope.upgradePackageState(
        packageState: PackageState,
        userId: Int,
        version: Int
    ) {
        val packageName = packageState.packageName
        if (version <= 3) {
            Slog.v(
                LOG_TAG, "Allowlisting and upgrading background location permission for " +
                    "package: $packageName, version: $version, user:$userId"
            )
            allowlistRestrictedPermissions(packageState, userId)
            upgradeBackgroundLocationPermission(packageState, userId)
        }
        if (version <= 10) {
            Slog.v(
                LOG_TAG, "Upgrading access media location permission for package: $packageName" +
                    ", version: $version, user: $userId"
            )
            upgradeAccessMediaLocationPermission(packageState, userId)
        }
        // TODO Enable isAtLeastT check, when moving subsystem to mainline.
        if (version <= 12 /*&& SdkLevel.isAtLeastT()*/) {
            Slog.v(
                LOG_TAG, "Upgrading scoped permissions for package: $packageName" +
                    ", version: $version, user: $userId"
            )
            upgradeAuralVisualMediaPermissions(packageState, userId)
        }
        // TODO Enable isAtLeastU check, when moving subsystem to mainline.
        if (version <= 14 /*&& SdkLevel.isAtLeastU()*/) {
            Slog.v(
                LOG_TAG, "Upgrading visual media permission for package: $packageName" +
                    ", version: $version, user: $userId"
            )
            upgradeUserSelectedVisualMediaPermission(packageState, userId)
        }
        // Add a new upgrade step: if (packageVersion <= LATEST_VERSION) { .... }
        // Also increase LATEST_VERSION
    }

    private fun MutateStateScope.allowlistRestrictedPermissions(
        packageState: PackageState,
        userId: Int
    ) {
        packageState.androidPackage!!.requestedPermissions.forEach { permissionName ->
            if (permissionName in LEGACY_RESTRICTED_PERMISSIONS) {
                with(policy) {
                    updatePermissionFlags(
                        packageState.appId, userId, permissionName,
                        PermissionFlags.UPGRADE_EXEMPT, PermissionFlags.UPGRADE_EXEMPT
                    )
                }
            }
        }
    }

    private fun MutateStateScope.upgradeBackgroundLocationPermission(
        packageState: PackageState,
        userId: Int
    ) {
        if (Manifest.permission.ACCESS_BACKGROUND_LOCATION in
            packageState.androidPackage!!.requestedPermissions) {
            val appId = packageState.appId
            val accessFineLocationFlags = with(policy) {
                getPermissionFlags(appId, userId, Manifest.permission.ACCESS_FINE_LOCATION)
            }
            val accessCoarseLocationFlags = with(policy) {
                getPermissionFlags(appId, userId, Manifest.permission.ACCESS_COARSE_LOCATION)
            }
            val isForegroundLocationGranted =
                PermissionFlags.isAppOpGranted(accessFineLocationFlags) ||
                    PermissionFlags.isAppOpGranted(accessCoarseLocationFlags)
            if (isForegroundLocationGranted) {
                grantRuntimePermission(
                    packageState, userId, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                )
            }
        }
    }

    private fun MutateStateScope.upgradeAccessMediaLocationPermission(
        packageState: PackageState,
        userId: Int
    ) {
        if (Manifest.permission.ACCESS_MEDIA_LOCATION in
            packageState.androidPackage!!.requestedPermissions) {
            val flags = with(policy) {
                getPermissionFlags(
                    packageState.appId, userId, Manifest.permission.READ_EXTERNAL_STORAGE
                )
            }
            if (PermissionFlags.isAppOpGranted(flags)) {
                grantRuntimePermission(
                    packageState, userId, Manifest.permission.ACCESS_MEDIA_LOCATION
                )
            }
        }
    }

    /**
     * Upgrade permissions based on storage permissions grant
     */
    private fun MutateStateScope.upgradeAuralVisualMediaPermissions(
        packageState: PackageState,
        userId: Int
    ) {
        val androidPackage = packageState.androidPackage!!
        if (androidPackage.targetSdkVersion < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val requestedPermissionNames = androidPackage.requestedPermissions
        val isStorageUserGranted = STORAGE_PERMISSIONS.anyIndexed { _, permissionName ->
            if (permissionName !in requestedPermissionNames) {
                return@anyIndexed false
            }
            val flags = with(policy) {
                getPermissionFlags(packageState.appId, userId, permissionName)
            }
            PermissionFlags.isAppOpGranted(flags) && flags.hasBits(PermissionFlags.USER_SET)
        }
        if (isStorageUserGranted) {
            AURAL_VISUAL_MEDIA_PERMISSIONS.forEachIndexed { _, permissionName ->
                if (permissionName in requestedPermissionNames) {
                    grantRuntimePermission(packageState, userId, permissionName)
                }
            }
        }
    }

    /**
     * Upgrade permission based on the grant in [Manifest.permission_group.READ_MEDIA_VISUAL]
     */
    private fun MutateStateScope.upgradeUserSelectedVisualMediaPermission(
        packageState: PackageState,
        userId: Int
    ) {
        val androidPackage = packageState.androidPackage!!
        if (androidPackage.targetSdkVersion < Build.VERSION_CODES.TIRAMISU) {
            return
        }
        val requestedPermissionNames = androidPackage.requestedPermissions
        val isVisualMediaUserGranted = VISUAL_MEDIA_PERMISSIONS.anyIndexed { _, permissionName ->
            if (permissionName !in requestedPermissionNames) {
                return@anyIndexed false
            }
            val flags = with(policy) {
                getPermissionFlags(packageState.appId, userId, permissionName)
            }
            PermissionFlags.isAppOpGranted(flags) && flags.hasBits(PermissionFlags.USER_SET)
        }
        if (isVisualMediaUserGranted) {
            if (Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED in requestedPermissionNames) {
                grantRuntimePermission(
                    packageState, userId, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )
            }
        }
    }

    private fun MutateStateScope.grantRuntimePermission(
        packageState: PackageState,
        userId: Int,
        permissionName: String
    ) {
        Slog.v(
            LOG_TAG, "Granting runtime permission for package: ${packageState.packageName}, " +
                "permission: $permissionName, userId: $userId"
        )
        val permission = newState.systemState.permissions[permissionName]!!
        if (packageState.getUserStateOrDefault(userId).isInstantApp && !permission.isInstant) {
            return
        }

        val appId = packageState.appId
        var flags = with(policy) { getPermissionFlags(appId, userId, permissionName) }
        if (flags.hasAnyBit(MASK_ANY_FIXED)) {
            Slog.v(
                LOG_TAG,
                "Not allowed to grant $permissionName to package ${packageState.packageName}"
            )
            return
        }

        flags = flags or PermissionFlags.RUNTIME_GRANTED
        flags = flags andInv (
            PermissionFlags.APP_OP_REVOKED or
            PermissionFlags.IMPLICIT or
            PermissionFlags.LEGACY_GRANTED or
            PermissionFlags.HIBERNATION or
            PermissionFlags.ONE_TIME
        )
        with(policy) { setPermissionFlags(appId, userId, permissionName, flags) }
    }

    companion object {
        private val LOG_TAG = AppIdPermissionUpgrade::class.java.simpleName

        private const val MASK_ANY_FIXED =
            PermissionFlags.USER_SET or PermissionFlags.USER_FIXED or
            PermissionFlags.POLICY_FIXED or PermissionFlags.SYSTEM_FIXED

        private val LEGACY_RESTRICTED_PERMISSIONS = indexedSetOf(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.SEND_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.RECEIVE_WAP_PUSH,
            Manifest.permission.RECEIVE_MMS,
            Manifest.permission.READ_CELL_BROADCASTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.WRITE_CALL_LOG,
            Manifest.permission.PROCESS_OUTGOING_CALLS
        )

        private val STORAGE_PERMISSIONS = indexedSetOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        private val AURAL_VISUAL_MEDIA_PERMISSIONS = indexedSetOf(
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )
        // Visual media permissions in T
        private val VISUAL_MEDIA_PERMISSIONS = indexedSetOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.ACCESS_MEDIA_LOCATION
        )
    }
}
