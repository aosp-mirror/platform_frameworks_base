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

import android.util.Slog
import com.android.server.LocalServices
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.PackageVersionMigration
import com.android.server.pm.permission.PermissionMigrationHelper

/** This class migrate legacy permissions to unified permission subsystem */
class AppIdPermissionMigration {
    internal fun migrateSystemState(state: MutableAccessState) {
        val legacyPermissionsManager =
            LocalServices.getService(PermissionMigrationHelper::class.java)!!
        if (!legacyPermissionsManager.hasLegacyPermission()) {
            return
        }

        migratePermissions(
            state.mutateSystemState().mutatePermissions(),
            legacyPermissionsManager.legacyPermissions
        )
        migratePermissions(
            state.mutateSystemState().mutatePermissionTrees(),
            legacyPermissionsManager.legacyPermissionTrees,
            true
        )
    }

    private fun migratePermissions(
        permissions: MutableIndexedMap<String, Permission>,
        legacyPermissions: Map<String, PermissionMigrationHelper.LegacyPermission>,
        isPermissionTree: Boolean = false
    ) {
        legacyPermissions.forEach { (_, legacyPermission) ->
            val permission =
                Permission(legacyPermission.permissionInfo, false, legacyPermission.type, 0)
            permissions[permission.name] = permission
            if (DEBUG_MIGRATION) {
                Slog.v(
                    LOG_TAG,
                    "Migrated permission: ${permission.name}, type: " +
                        "${permission.type}, appId: ${permission.appId}, protectionLevel: " +
                        "${permission.protectionLevel}, tree: $isPermissionTree"
                )
            }
        }
    }

    internal fun migrateUserState(state: MutableAccessState, userId: Int) {
        val permissionMigrationHelper =
            LocalServices.getService(PermissionMigrationHelper::class.java)!!
        if (!permissionMigrationHelper.hasLegacyPermissionState(userId)) {
            return
        }

        val legacyAppIdPermissionStates =
            permissionMigrationHelper.getLegacyPermissionStates(userId)
        val version = PackageVersionMigration.getVersion(userId)

        val userState = state.mutateUserState(userId)!!
        val appIdPermissionFlags = userState.mutateAppIdPermissionFlags()
        legacyAppIdPermissionStates.forEach { (appId, legacyPermissionStates) ->
            val packageNames = state.externalState.appIdPackageNames[appId]
            if (packageNames == null) {
                Slog.w(LOG_TAG, "Dropping unknown app ID $appId when migrating permission state")
                return@forEach
            }

            val permissionFlags = MutableIndexedMap<String, Int>()
            appIdPermissionFlags[appId] = permissionFlags
            legacyPermissionStates.forEach forEachPermission@{
                (permissionName, legacyPermissionState) ->
                val permission = state.systemState.permissions[permissionName]
                if (permission == null) {
                    Slog.w(
                        LOG_TAG,
                        "Dropping unknown permission $permissionName for app ID $appId" +
                            " when migrating permission state"
                    )
                    return@forEachPermission
                }
                permissionFlags[permissionName] =
                    migratePermissionFlags(permission, legacyPermissionState, appId, userId)
            }

            val packageVersions = userState.mutatePackageVersions()
            packageNames.forEachIndexed { _, packageName -> packageVersions[packageName] = version }
        }
    }

    private fun migratePermissionFlags(
        permission: Permission,
        legacyPermissionState: PermissionMigrationHelper.LegacyPermissionState,
        appId: Int,
        userId: Int
    ): Int {
        var flags =
            when {
                permission.isNormal ->
                    if (legacyPermissionState.isGranted) {
                        PermissionFlags.INSTALL_GRANTED
                    } else {
                        PermissionFlags.INSTALL_REVOKED
                    }
                permission.isSignature || permission.isInternal ->
                    if (legacyPermissionState.isGranted) {
                        if (permission.isDevelopment || permission.isRole) {
                            PermissionFlags.PROTECTION_GRANTED or PermissionFlags.RUNTIME_GRANTED
                        } else {
                            PermissionFlags.PROTECTION_GRANTED
                        }
                    } else {
                        0
                    }
                permission.isRuntime ->
                    if (legacyPermissionState.isGranted) PermissionFlags.RUNTIME_GRANTED else 0
                else -> 0
            }
        flags =
            PermissionFlags.updateFlags(
                permission,
                flags,
                legacyPermissionState.flags,
                legacyPermissionState.flags
            )
        if (DEBUG_MIGRATION) {
            val oldFlagString = PermissionFlags.apiFlagsToString(legacyPermissionState.flags)
            val newFlagString = PermissionFlags.toString(flags)
            val oldGrantState = legacyPermissionState.isGranted
            val newGrantState = PermissionFlags.isPermissionGranted(flags)
            val flagsMismatch = legacyPermissionState.flags != PermissionFlags.toApiFlags(flags)
            Slog.v(
                LOG_TAG,
                "Migrated appId: $appId, permission: " +
                    "${permission.name}, user: $userId, oldGrantState: $oldGrantState" +
                    ", oldFlags: $oldFlagString, newFlags: $newFlagString, grantMismatch: " +
                    "${oldGrantState != newGrantState}, flagsMismatch: $flagsMismatch"
            )
        }
        return flags
    }

    companion object {
        private val LOG_TAG = AppIdPermissionMigration::class.java.simpleName

        private const val DEBUG_MIGRATION = false
    }
}
