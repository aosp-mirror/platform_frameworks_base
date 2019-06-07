/*
 * Copyright (C) 2019 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.appops

import android.content.Context
import android.content.pm.PackageManager
import android.os.UserHandle
import android.util.ArrayMap
import com.android.internal.annotations.VisibleForTesting

private data class PermissionFlag(val flag: Int, val timestamp: Long)

private data class PermissionFlagKey(
    val permission: String,
    val packageName: String,
    val user: UserHandle
)

internal const val CACHE_EXPIRATION = 10000L

/**
 * Cache for PackageManager's PermissionFlags.
 *
 * Flags older than [CACHE_EXPIRATION] will be retrieved again.
 */
internal open class PermissionFlagsCache(context: Context) {
    private val packageManager = context.packageManager
    private val permissionFlagsCache = ArrayMap<PermissionFlagKey, PermissionFlag>()

    /**
     * Retrieve permission flags from cache or PackageManager. There parameters will be passed
     * directly to [PackageManager].
     *
     * Calls to this method should be done from a background thread.
     */
    fun getPermissionFlags(permission: String, packageName: String, user: UserHandle): Int {
        val key = PermissionFlagKey(permission, packageName, user)
        val now = getCurrentTime()
        val value = permissionFlagsCache.getOrPut(key) {
            PermissionFlag(getFlags(key), now)
        }
        if (now - value.timestamp > CACHE_EXPIRATION) {
            val newValue = PermissionFlag(getFlags(key), now)
            permissionFlagsCache.put(key, newValue)
            return newValue.flag
        } else {
            return value.flag
        }
    }

    private fun getFlags(key: PermissionFlagKey) =
            packageManager.getPermissionFlags(key.permission, key.packageName, key.user)

    @VisibleForTesting
    protected open fun getCurrentTime() = System.currentTimeMillis()
}