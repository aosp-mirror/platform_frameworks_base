/*
 * Copyright (C) 2020 The Android Open Source Project
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

import android.content.pm.PackageManager
import android.os.UserHandle
import androidx.annotation.WorkerThread
import com.android.systemui.dagger.qualifiers.Background
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton

private data class PermissionFlagKey(
    val permission: String,
    val packageName: String,
    val uid: Int
)

/**
 * Cache for PackageManager's PermissionFlags.
 *
 * After a specific `{permission, package, uid}` has been requested, updates to it will be tracked,
 * and changes to the uid will trigger new requests (in the background).
 */
@Singleton
class PermissionFlagsCache @Inject constructor(
    private val packageManager: PackageManager,
    @Background private val executor: Executor
) : PackageManager.OnPermissionsChangedListener {

    private val permissionFlagsCache =
            mutableMapOf<Int, MutableMap<PermissionFlagKey, Int>>()
    private var listening = false

    override fun onPermissionsChanged(uid: Int) {
        executor.execute {
            // Only track those that we've seen before
            val keys = permissionFlagsCache.get(uid)
            if (keys != null) {
                keys.mapValuesTo(keys) {
                    getFlags(it.key)
                }
            }
        }
    }

    /**
     * Retrieve permission flags from cache or PackageManager. There parameters will be passed
     * directly to [PackageManager].
     *
     * Calls to this method should be done from a background thread.
     */
    @WorkerThread
    fun getPermissionFlags(permission: String, packageName: String, uid: Int): Int {
        if (!listening) {
            listening = true
            packageManager.addOnPermissionsChangeListener(this)
        }
        val key = PermissionFlagKey(permission, packageName, uid)
        return permissionFlagsCache.getOrPut(uid, { mutableMapOf() }).get(key) ?: run {
            getFlags(key).also {
                permissionFlagsCache.get(uid)?.put(key, it)
            }
        }
    }

    private fun getFlags(key: PermissionFlagKey): Int {
        return packageManager.getPermissionFlags(key.permission, key.packageName,
                UserHandle.getUserHandleForUid(key.uid))
    }
}