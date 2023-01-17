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

import android.util.Log
import com.android.server.LocalServices
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.pm.permission.PermissionMigrationHelper

/**
 * This class migrate legacy permissions to unified permission subsystem
 */
class UidPermissionMigration {
    internal fun migrateSystemState(state: AccessState) {
        val legacyPermissionsManager =
            LocalServices.getService(PermissionMigrationHelper::class.java)!!
        migratePermissions(state.systemState.permissions,
            legacyPermissionsManager.legacyPermissions)
        migratePermissions(state.systemState.permissionTrees,
            legacyPermissionsManager.legacyPermissionTrees, true)
    }

    private fun migratePermissions(
        permissions: IndexedMap<String, Permission>,
        legacyPermissions: Map<String, PermissionMigrationHelper.LegacyPermission>,
        isPermissionTree: Boolean = false
    ) {
        legacyPermissions.forEach { (_, legacyPermission) ->
            val permission = Permission(
                legacyPermission.permissionInfo, false, legacyPermission.type, 0
            )
            permissions[permission.name] = permission
            if (DEBUG_MIGRATION) {
                Log.v(LOG_TAG, "Migrated permission: ${permission.name}, type: " +
                    "${permission.type}, appId: ${permission.appId}, protectionLevel: " +
                    "${permission.protectionLevel}, tree: $isPermissionTree"
                )
            }
        }
    }

    internal fun migrateUserState(state: AccessState, userId: Int) {
        val legacyPermissionsManager =
            LocalServices.getService(PermissionMigrationHelper::class.java)!!
        val permissionStates = legacyPermissionsManager.getLegacyPermissionStates(userId)

        permissionStates.forEach { (appId, permissionStates) ->
            migratePermissionStates(appId, state, permissionStates, userId)
        }
    }

    private fun migratePermissionStates(
        appId: Int,
        state: AccessState,
        legacyPermissionStates: Map<String, PermissionMigrationHelper.LegacyPermissionState>,
        userId: Int
    ) {
        val permissionFlags =
            state.userStates[userId].uidPermissionFlags.getOrPut(appId) { IndexedMap() }

        legacyPermissionStates.forEach forEachPermission@ { (permissionName, permissionState) ->
            val permission = state.systemState.permissions[permissionName]
                ?: return@forEachPermission

            var flags = when {
                permission.isNormal -> if (permissionState.isGranted) {
                    PermissionFlags.INSTALL_GRANTED
                } else {
                    PermissionFlags.INSTALL_REVOKED
                }
                permission.isSignature || permission.isInternal ->
                    if (permissionState.isGranted) {
                        if (permission.isDevelopment || permission.isRole) {
                            PermissionFlags.PROTECTION_GRANTED or PermissionFlags.RUNTIME_GRANTED
                        } else {
                            PermissionFlags.PROTECTION_GRANTED
                        }
                    } else {
                        0
                    }
                permission.isRuntime ->
                    if (permissionState.isGranted) PermissionFlags.RUNTIME_GRANTED else 0
                else -> 0
            }
            flags = PermissionFlags.updateFlags(
                permission, flags, permissionState.flags, permissionState.flags
            )
            permissionFlags[permissionName] = flags

            if (DEBUG_MIGRATION) {
                val oldFlagString = PermissionFlags.apiFlagsToString(permissionState.flags)
                val newFlagString = PermissionFlags.toString(flags)
                val oldGrantState = permissionState.isGranted
                val newGrantState = PermissionFlags.isPermissionGranted(flags)
                val flagsMismatch = permissionState.flags != PermissionFlags.toApiFlags(flags)
                Log.v(
                    LOG_TAG, "Migrated appId: $appId, permission: " +
                        "${permission.name}, user: $userId, oldGrantState: $oldGrantState" +
                        ", oldFlags: $oldFlagString, newFlags: $newFlagString, grantMismatch: " +
                        "${oldGrantState != newGrantState}, flagsMismatch: $flagsMismatch"
                )
            }
        }
    }

    companion object {
        private val LOG_TAG = UidPermissionMigration::class.java.simpleName

        private const val DEBUG_MIGRATION = false
    }
}
