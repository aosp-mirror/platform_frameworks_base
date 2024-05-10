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

package com.android.server.permission.access.permission

import android.content.pm.PermissionInfo
import android.util.Slog
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.AppIdPermissionFlags
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.MutableAppIdPermissionFlags
import com.android.server.permission.access.WriteMode
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.andInv
import com.android.server.permission.access.util.attribute
import com.android.server.permission.access.util.attributeInt
import com.android.server.permission.access.util.attributeIntHex
import com.android.server.permission.access.util.attributeIntHexWithDefault
import com.android.server.permission.access.util.attributeInterned
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.getAttributeIntHexOrDefault
import com.android.server.permission.access.util.getAttributeIntHexOrThrow
import com.android.server.permission.access.util.getAttributeIntOrThrow
import com.android.server.permission.access.util.getAttributeValue
import com.android.server.permission.access.util.getAttributeValueOrThrow
import com.android.server.permission.access.util.hasBits
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName

class AppIdPermissionPersistence {
    fun BinaryXmlPullParser.parseSystemState(state: MutableAccessState) {
        when (tagName) {
            TAG_PERMISSION_TREES -> parsePermissions(state, true)
            TAG_PERMISSIONS -> parsePermissions(state, false)
            else -> {}
        }
    }

    private fun BinaryXmlPullParser.parsePermissions(
        state: MutableAccessState,
        isPermissionTree: Boolean
    ) {
        val systemState = state.mutateSystemState(WriteMode.NONE)
        val permissions =
            if (isPermissionTree) {
                systemState.mutatePermissionTrees()
            } else {
                systemState.mutatePermissions()
            }
        forEachTag {
            when (val tagName = tagName) {
                TAG_PERMISSION -> parsePermission(permissions)
                else -> Slog.w(LOG_TAG, "Ignoring unknown tag $tagName when parsing permissions")
            }
        }
        permissions.forEachReversedIndexed { permissionIndex, _, permission ->
            val packageName = permission.packageName
            val externalState = state.externalState
            if (
                packageName !in externalState.packageStates &&
                    packageName !in externalState.disabledSystemPackageStates
            ) {
                Slog.w(
                    LOG_TAG,
                    "Dropping permission ${permission.name} from unknown package" +
                        " $packageName when parsing permissions"
                )
                permissions.removeAt(permissionIndex)
                systemState.requestWriteMode(WriteMode.ASYNCHRONOUS)
            }
        }
    }

    private fun BinaryXmlPullParser.parsePermission(
        permissions: MutableIndexedMap<String, Permission>
    ) {
        val name = getAttributeValueOrThrow(ATTR_NAME).intern()
        @Suppress("DEPRECATION")
        val permissionInfo =
            PermissionInfo().apply {
                this.name = name
                packageName = getAttributeValueOrThrow(ATTR_PACKAGE_NAME).intern()
                protectionLevel = getAttributeIntHexOrThrow(ATTR_PROTECTION_LEVEL)
            }
        val type = getAttributeIntOrThrow(ATTR_TYPE)
        when (type) {
            Permission.TYPE_MANIFEST -> {}
            Permission.TYPE_CONFIG -> {
                Slog.w(LOG_TAG, "Ignoring unexpected config permission $name")
                return
            }
            Permission.TYPE_DYNAMIC -> {
                permissionInfo.apply {
                    icon = getAttributeIntHexOrDefault(ATTR_ICON, 0)
                    nonLocalizedLabel = getAttributeValue(ATTR_LABEL)
                }
            }
            else -> {
                Slog.w(LOG_TAG, "Ignoring permission $name with unknown type $type")
                return
            }
        }
        val permission = Permission(permissionInfo, false, type, 0)
        permissions[name] = permission
    }

    fun BinaryXmlSerializer.serializeSystemState(state: AccessState) {
        val systemState = state.systemState
        serializePermissions(TAG_PERMISSION_TREES, systemState.permissionTrees)
        serializePermissions(TAG_PERMISSIONS, systemState.permissions)
    }

    private fun BinaryXmlSerializer.serializePermissions(
        tagName: String,
        permissions: IndexedMap<String, Permission>
    ) {
        tag(tagName) { permissions.forEachIndexed { _, _, it -> serializePermission(it) } }
    }

    private fun BinaryXmlSerializer.serializePermission(permission: Permission) {
        val type = permission.type
        when (type) {
            Permission.TYPE_MANIFEST,
            Permission.TYPE_DYNAMIC -> {}
            Permission.TYPE_CONFIG -> return
            else -> {
                Slog.w(LOG_TAG, "Skipping serializing permission $name with unknown type $type")
                return
            }
        }
        tag(TAG_PERMISSION) {
            attributeInterned(ATTR_NAME, permission.name)
            attributeInterned(ATTR_PACKAGE_NAME, permission.packageName)
            attributeIntHex(ATTR_PROTECTION_LEVEL, permission.protectionLevel)
            attributeInt(ATTR_TYPE, type)
            if (type == Permission.TYPE_DYNAMIC) {
                val permissionInfo = permission.permissionInfo
                attributeIntHexWithDefault(ATTR_ICON, permissionInfo.icon, 0)
                permissionInfo.nonLocalizedLabel?.toString()?.let { attribute(ATTR_LABEL, it) }
            }
        }
    }

    fun BinaryXmlPullParser.parseUserState(state: MutableAccessState, userId: Int) {
        when (tagName) {
            TAG_APP_ID_PERMISSIONS -> parseAppIdPermissions(state, userId)
            else -> {}
        }
    }

    private fun BinaryXmlPullParser.parseAppIdPermissions(state: MutableAccessState, userId: Int) {
        val userState = state.mutateUserState(userId, WriteMode.NONE)!!
        val appIdPermissionFlags = userState.mutateAppIdPermissionFlags()
        forEachTag {
            when (tagName) {
                TAG_APP_ID -> parseAppId(appIdPermissionFlags)
                else -> Slog.w(LOG_TAG, "Ignoring unknown tag $name when parsing permission state")
            }
        }
        appIdPermissionFlags.forEachReversedIndexed { appIdIndex, appId, _ ->
            if (appId !in state.externalState.appIdPackageNames) {
                Slog.w(LOG_TAG, "Dropping unknown app ID $appId when parsing permission state")
                appIdPermissionFlags.removeAt(appIdIndex)
                userState.requestWriteMode(WriteMode.ASYNCHRONOUS)
            }
        }
    }

    private fun BinaryXmlPullParser.parseAppId(appIdPermissionFlags: MutableAppIdPermissionFlags) {
        val appId = getAttributeIntOrThrow(ATTR_ID)
        val permissionFlags = MutableIndexedMap<String, Int>()
        appIdPermissionFlags[appId] = permissionFlags
        forEachTag {
            when (tagName) {
                TAG_PERMISSION -> parseAppIdPermission(permissionFlags)
                else -> Slog.w(LOG_TAG, "Ignoring unknown tag $name when parsing permission state")
            }
        }
    }

    private fun BinaryXmlPullParser.parseAppIdPermission(
        permissionFlags: MutableIndexedMap<String, Int>
    ) {
        val name = getAttributeValueOrThrow(ATTR_NAME).intern()
        val flags = getAttributeIntOrThrow(ATTR_FLAGS)
        permissionFlags[name] = flags
    }

    fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {
        serializeAppIdPermissions(state.userStates[userId]!!.appIdPermissionFlags)
    }

    private fun BinaryXmlSerializer.serializeAppIdPermissions(
        appIdPermissionFlags: AppIdPermissionFlags
    ) {
        tag(TAG_APP_ID_PERMISSIONS) {
            appIdPermissionFlags.forEachIndexed { _, appId, permissionFlags ->
                serializeAppId(appId, permissionFlags)
            }
        }
    }

    private fun BinaryXmlSerializer.serializeAppId(
        appId: Int,
        permissionFlags: IndexedMap<String, Int>
    ) {
        tag(TAG_APP_ID) {
            attributeInt(ATTR_ID, appId)
            permissionFlags.forEachIndexed { _, name, flags ->
                serializeAppIdPermission(name, flags)
            }
        }
    }

    private fun BinaryXmlSerializer.serializeAppIdPermission(name: String, flags: Int) {
        tag(TAG_PERMISSION) {
            attributeInterned(ATTR_NAME, name)
            // Never serialize one-time permissions as granted.
            val serializedFlags =
                if (flags.hasBits(PermissionFlags.ONE_TIME)) {
                    flags andInv PermissionFlags.RUNTIME_GRANTED
                } else {
                    flags
                }
            attributeInt(ATTR_FLAGS, serializedFlags)
        }
    }

    companion object {
        private val LOG_TAG = AppIdPermissionPersistence::class.java.simpleName

        private const val TAG_APP_ID = "app-id"
        private const val TAG_APP_ID_PERMISSIONS = "app-id-permissions"
        private const val TAG_PERMISSION = "permission"
        private const val TAG_PERMISSIONS = "permissions"
        private const val TAG_PERMISSION_TREES = "permission-trees"

        private const val ATTR_FLAGS = "flags"
        private const val ATTR_ICON = "icon"
        private const val ATTR_ID = "id"
        private const val ATTR_LABEL = "label"
        private const val ATTR_NAME = "name"
        private const val ATTR_PACKAGE_NAME = "packageName"
        private const val ATTR_PROTECTION_LEVEL = "protectionLevel"
        private const val ATTR_TYPE = "type"
    }
}
