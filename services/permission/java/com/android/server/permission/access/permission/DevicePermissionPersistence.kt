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
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.DevicePermissionFlags
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.MutableAppIdDevicePermissionFlags
import com.android.server.permission.access.MutableDevicePermissionFlags
import com.android.server.permission.access.WriteMode
import com.android.server.permission.access.immutable.IndexedMap
import com.android.server.permission.access.immutable.MutableIndexedMap
import com.android.server.permission.access.immutable.forEachIndexed
import com.android.server.permission.access.immutable.forEachReversedIndexed
import com.android.server.permission.access.immutable.set
import com.android.server.permission.access.util.andInv
import com.android.server.permission.access.util.attributeInt
import com.android.server.permission.access.util.attributeInterned
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.getAttributeIntOrThrow
import com.android.server.permission.access.util.getAttributeValueOrThrow
import com.android.server.permission.access.util.hasBits
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName

class DevicePermissionPersistence {
    fun BinaryXmlPullParser.parseUserState(state: MutableAccessState, userId: Int) {
        when (tagName) {
            TAG_APP_ID_DEVICE_PERMISSIONS -> parseAppIdDevicePermissions(state, userId)
            else -> {}
        }
    }

    private fun BinaryXmlPullParser.parseAppIdDevicePermissions(
        state: MutableAccessState,
        userId: Int
    ) {
        val userState = state.mutateUserState(userId, WriteMode.NONE)!!
        val appIdDevicePermissionFlags = userState.mutateAppIdDevicePermissionFlags()
        forEachTag {
            when (tagName) {
                TAG_APP_ID -> parseAppId(appIdDevicePermissionFlags)
                else -> Slog.w(LOG_TAG, "Ignoring unknown tag $name when parsing permission state")
            }
        }

        appIdDevicePermissionFlags.forEachReversedIndexed { appIdIndex, appId, _ ->
            if (appId !in state.externalState.appIdPackageNames) {
                Slog.w(LOG_TAG, "Dropping unknown app ID $appId when parsing permission state")
                appIdDevicePermissionFlags.removeAt(appIdIndex)
                userState.requestWriteMode(WriteMode.ASYNCHRONOUS)
            }
        }
    }

    private fun BinaryXmlPullParser.parseAppId(
        appIdPermissionFlags: MutableAppIdDevicePermissionFlags
    ) {
        val appId = getAttributeIntOrThrow(ATTR_ID)
        val devicePermissionFlags = MutableDevicePermissionFlags()
        appIdPermissionFlags[appId] = devicePermissionFlags
        forEachTag {
            when (tagName) {
                TAG_DEVICE -> parseDevice(devicePermissionFlags)
                else -> {
                    Slog.w(LOG_TAG, "Ignoring unknown tag $name when parsing permission state")
                }
            }
        }
    }

    private fun BinaryXmlPullParser.parseDevice(
        deviceIdPermissionFlags: MutableDevicePermissionFlags
    ) {
        val deviceId = getAttributeValueOrThrow(ATTR_ID)
        val permissionFlags = MutableIndexedMap<String, Int>()
        deviceIdPermissionFlags.put(deviceId, permissionFlags)
        forEachTag {
            when (tagName) {
                TAG_PERMISSION -> parsePermission(permissionFlags)
                else -> Slog.w(LOG_TAG, "Ignoring unknown tag $name when parsing permission state")
            }
        }
    }

    private fun BinaryXmlPullParser.parsePermission(
        permissionFlags: MutableIndexedMap<String, Int>
    ) {
        val name = getAttributeValueOrThrow(ATTR_NAME).intern()
        val flags = getAttributeIntOrThrow(ATTR_FLAGS)
        permissionFlags[name] = flags
    }

    fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {
        val appIdDevicePermissionFlags = state.userStates[userId]!!.appIdDevicePermissionFlags
        tag(TAG_APP_ID_DEVICE_PERMISSIONS) {
            appIdDevicePermissionFlags.forEachIndexed { _, appId, devicePermissionFlags ->
                serializeAppId(appId, devicePermissionFlags)
            }
        }
    }

    private fun BinaryXmlSerializer.serializeAppId(
        appId: Int,
        devicePermissionFlags: DevicePermissionFlags
    ) {
        tag(TAG_APP_ID) {
            attributeInt(ATTR_ID, appId)
            devicePermissionFlags.forEachIndexed { _, deviceId, permissionFlags ->
                serializeDevice(deviceId, permissionFlags)
            }
        }
    }

    private fun BinaryXmlSerializer.serializeDevice(
        deviceId: String,
        permissionFlags: IndexedMap<String, Int>
    ) {
        tag(TAG_DEVICE) {
            attributeInterned(ATTR_ID, deviceId)
            permissionFlags.forEachIndexed { _, name, flags -> serializePermission(name, flags) }
        }
    }

    private fun BinaryXmlSerializer.serializePermission(name: String, flags: Int) {
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
        private val LOG_TAG = DevicePermissionPersistence::class.java.simpleName

        private const val TAG_APP_ID_DEVICE_PERMISSIONS = "app-id-device-permissions"
        private const val TAG_APP_ID = "app-id"
        private const val TAG_DEVICE = "device"
        private const val TAG_PERMISSION = "permission"

        private const val ATTR_ID = "id"
        private const val ATTR_NAME = "name"
        private const val ATTR_FLAGS = "flags"
    }
}
