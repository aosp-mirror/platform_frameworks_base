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

package com.android.server.permission.access.appop

import android.app.AppOpsManager
import android.os.Handler
import android.os.UserHandle
import android.util.ArrayMap
import android.util.SparseArray
import android.util.SparseBooleanArray
import android.util.SparseIntArray
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.util.ArrayUtils
import com.android.server.appop.AppOpsCheckingServiceInterface
import com.android.server.permission.access.AccessCheckingService
import com.android.server.permission.access.AppOpUri
import com.android.server.permission.access.PackageUri
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports

class AppOpService(
    private val service: AccessCheckingService
) : AppOpsCheckingServiceInterface {
    private val packagePolicy = service.getSchemePolicy(PackageUri.SCHEME, AppOpUri.SCHEME)
        as PackageAppOpPolicy
    private val uidPolicy = service.getSchemePolicy(UidUri.SCHEME, AppOpUri.SCHEME)
        as AppIdAppOpPolicy

    private val context = service.context
    private lateinit var handler: Handler
    private lateinit var lock: Any
    private lateinit var switchedOps: SparseArray<IntArray>

    fun initialize() {
        // TODO(b/252883039): Wrong handler. Inject main thread handler here.
        handler = Handler(context.mainLooper)
        // TODO(b/252883039): Wrong lock object. Inject AppOpsService here.
        lock = Any()

        switchedOps = SparseArray()
        for (switchedCode in 0 until AppOpsManager._NUM_OP) {
            val switchCode = AppOpsManager.opToSwitch(switchedCode)
            switchedOps.put(switchCode,
                ArrayUtils.appendInt(switchedOps.get(switchCode), switchedCode))
        }
    }

    @VisibleForTesting
    override fun writeState() {
        // Not implemented because writes are handled automatically.
    }

    override fun readState() {
        // Not implemented because reads are handled automatically.
    }

    @VisibleForTesting
    override fun shutdown() {
        // Not implemented because writes are handled automatically.
    }

    override fun systemReady() {
        // Not implemented because upgrades are handled automatically.
    }

    override fun getNonDefaultUidModes(uid: Int): SparseIntArray {
        return opNameMapToOpSparseArray(getUidModes(uid))
    }

    override fun getNonDefaultPackageModes(packageName: String, userId: Int): SparseIntArray {
        return opNameMapToOpSparseArray(getPackageModes(packageName, userId))
    }

    override fun getUidMode(uid: Int, op: Int): Int {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val opName = AppOpsManager.opToPublicName(op)
        return service.getState {
            with(uidPolicy) { getAppOpMode(appId, userId, opName) }
        }
    }

    private fun getUidModes(uid: Int): ArrayMap<String, Int>? {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        return service.getState {
            with(uidPolicy) { getAppOpModes(appId, userId) }
        }?.map
    }

    override fun setUidMode(uid: Int, op: Int, mode: Int): Boolean {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val opName = AppOpsManager.opToPublicName(op)
        var wasChanged = false
        service.mutateState {
            wasChanged = with(uidPolicy) { setAppOpMode(appId, userId, opName, mode) }
        }
        return wasChanged
    }

    override fun getPackageMode(packageName: String, op: Int, userId: Int): Int {
        val opName = AppOpsManager.opToPublicName(op)
        return service.getState {
            with(packagePolicy) { getAppOpMode(packageName, userId, opName) }
        }
    }

    private fun getPackageModes(
        packageName: String,
        userId: Int
    ): ArrayMap<String, Int>? =
        service.getState { with(packagePolicy) { getAppOpModes(packageName, userId) } }?.map

    override fun setPackageMode(packageName: String, op: Int, mode: Int, userId: Int) {
        val opName = AppOpsManager.opToPublicName(op)
        service.mutateState {
            with(packagePolicy) { setAppOpMode(packageName, userId, opName, mode) }
        }
    }

    override fun removeUid(uid: Int) {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        service.mutateState {
            with(uidPolicy) { removeAppOpModes(appId, userId) }
        }
    }

    override fun removePackage(packageName: String, userId: Int): Boolean {
        var wasChanged = false
        service.mutateState {
            wasChanged = with (packagePolicy) { removeAppOpModes(packageName, userId) }
        }
        return wasChanged
    }

    private fun opNameMapToOpSparseArray(modes: ArrayMap<String, Int>?): SparseIntArray =
        if (modes == null) {
            SparseIntArray()
        } else {
            val opSparseArray = SparseIntArray(modes.size)
            modes.forEachIndexed { _, opName, opMode ->
                opSparseArray.put(AppOpsManager.strOpToOp(opName), opMode)
            }
            opSparseArray
        }

    override fun areUidModesDefault(uid: Int): Boolean {
        val modes = getUidModes(uid)
        return modes == null || modes.isEmpty()
    }

    override fun arePackageModesDefault(packageName: String, userId: Int): Boolean {
        val modes = service.getState { getPackageModes(packageName, userId) }
        return modes == null || modes.isEmpty()
    }

    override fun clearAllModes() {
        // We don't need to implement this because it's only called in AppOpsService#readState
        // and we have our own persistence.
    }

    override fun getForegroundOps(uid: Int): SparseBooleanArray {
        return SparseBooleanArray().apply {
            getUidModes(uid)?.forEachIndexed { _, op, mode ->
                if (mode == AppOpsManager.MODE_FOREGROUND) {
                    this[AppOpsManager.strOpToOp(op)] = true
                }
            }
        }
    }

    override fun getForegroundOps(packageName: String, userId: Int): SparseBooleanArray {
        return SparseBooleanArray().apply {
            getPackageModes(packageName, userId)?.forEachIndexed { _, op, mode ->
                if (mode == AppOpsManager.MODE_FOREGROUND) {
                    this[AppOpsManager.strOpToOp(op)] = true
                }
            }
        }
    }
}
