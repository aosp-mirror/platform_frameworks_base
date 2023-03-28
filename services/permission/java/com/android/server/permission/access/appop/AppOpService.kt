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
import android.util.SparseIntArray
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.util.ArrayUtils
import com.android.server.appop.AppOpsCheckingServiceInterface
import com.android.server.permission.access.AccessCheckingService
import com.android.server.permission.access.AppOpUri
import com.android.server.permission.access.PackageUri
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.collection.IndexedMap
import com.android.server.permission.access.collection.IntBooleanMap
import com.android.server.permission.access.collection.IntMap
import com.android.server.permission.access.collection.forEachIndexed

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
    private lateinit var switchedOps: IntMap<IntArray>

    fun initialize() {
        // TODO(b/252883039): Wrong handler. Inject main thread handler here.
        handler = Handler(context.mainLooper)
        // TODO(b/252883039): Wrong lock object. Inject AppOpsService here.
        lock = Any()

        switchedOps = IntMap()
        for (switchedCode in 0 until AppOpsManager._NUM_OP) {
            val switchCode = AppOpsManager.opToSwitch(switchedCode)
            switchedOps.put(switchCode,
                ArrayUtils.appendInt(switchedOps.get(switchCode), switchedCode))
        }
    }

    @VisibleForTesting
    override fun writeState() {
        // TODO Not yet implemented
    }

    override fun readState() {
        // TODO Not yet implemented
    }

    @VisibleForTesting
    override fun shutdown() {
        // TODO Not yet implemented
    }

    override fun systemReady() {
        // TODO Not yet implemented
    }

    override fun getNonDefaultUidModes(uid: Int): SparseIntArray {
        return opNameMapToOpIntMap(getUidModes(uid))
    }

    override fun getNonDefaultPackageModes(packageName: String, userId: Int): SparseIntArray {
        return opNameMapToOpIntMap(getPackageModes(packageName, userId))
    }

    override fun getUidMode(uid: Int, op: Int): Int {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val opName = AppOpsManager.opToPublicName(op)
        return service.getState {
            with(uidPolicy) { getAppOpMode(appId, userId, opName) }
        }
    }

    private fun getUidModes(uid: Int): IndexedMap<String, Int>? {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        return service.getState {
            with(uidPolicy) { getAppOpModes(appId, userId) }
        }
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
    ): IndexedMap<String, Int>? =
        service.getState { with(packagePolicy) { getAppOpModes(packageName, userId) } }

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

    private fun opNameMapToOpIntMap(modes: IndexedMap<String, Int>?): SparseIntArray =
        if (modes == null) {
            SparseIntArray()
        } else {
            val opIntMap = SparseIntArray(modes.size)
            modes.forEachIndexed { _, opName, opMode ->
                opIntMap.put(AppOpsManager.strOpToOp(opName), opMode)
            }
            opIntMap
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

    override fun getForegroundOps(uid: Int): IntBooleanMap {
        return IntBooleanMap().apply {
            getUidModes(uid)?.forEachIndexed { _, code, mode ->
                if (mode == AppOpsManager.MODE_FOREGROUND) {
                    put(AppOpsManager.strOpToOp(code), true)
                }
            }
        }
    }

    override fun getForegroundOps(packageName: String, userId: Int): IntBooleanMap {
        return IntBooleanMap().apply {
            getPackageModes(packageName, userId)?.forEachIndexed { _, code, mode ->
                if (mode == AppOpsManager.MODE_FOREGROUND) {
                    put(AppOpsManager.strOpToOp(code), true)
                }
            }
        }
    }
}
