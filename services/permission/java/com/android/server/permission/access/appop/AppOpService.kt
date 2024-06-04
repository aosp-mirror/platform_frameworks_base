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
import android.companion.virtual.VirtualDeviceManager
import android.os.Binder
import android.os.Handler
import android.os.UserHandle
import android.permission.PermissionManager
import android.permission.flags.Flags
import android.util.ArrayMap
import android.util.ArraySet
import android.util.LongSparseArray
import android.util.Slog
import android.util.SparseArray
import android.util.SparseBooleanArray
import android.util.SparseIntArray
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.util.IntPair
import com.android.server.appop.AppOpsCheckingServiceInterface
import com.android.server.appop.AppOpsCheckingServiceInterface.AppOpsModeChangedListener
import com.android.server.permission.access.AccessCheckingService
import com.android.server.permission.access.AppOpUri
import com.android.server.permission.access.DevicePermissionUri
import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.PackageUri
import com.android.server.permission.access.PermissionUri
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.appop.AppOpModes.MODE_ALLOWED
import com.android.server.permission.access.appop.AppOpModes.MODE_FOREGROUND
import com.android.server.permission.access.appop.AppOpModes.MODE_IGNORED
import com.android.server.permission.access.collection.forEachIndexed
import com.android.server.permission.access.collection.set
import com.android.server.permission.access.permission.AppIdPermissionPolicy
import com.android.server.permission.access.permission.DevicePermissionPolicy
import com.android.server.permission.access.permission.PermissionFlags
import com.android.server.permission.access.permission.PermissionService

class AppOpService(private val service: AccessCheckingService) : AppOpsCheckingServiceInterface {
    private val packagePolicy =
        service.getSchemePolicy(PackageUri.SCHEME, AppOpUri.SCHEME) as PackageAppOpPolicy
    private val appIdPolicy =
        service.getSchemePolicy(UidUri.SCHEME, AppOpUri.SCHEME) as AppIdAppOpPolicy
    private val permissionPolicy =
        service.getSchemePolicy(UidUri.SCHEME, PermissionUri.SCHEME) as AppIdPermissionPolicy
    private val devicePermissionPolicy =
        service.getSchemePolicy(UidUri.SCHEME, DevicePermissionUri.SCHEME) as DevicePermissionPolicy

    private val context = service.context

    // Maps appop code to its runtime permission
    private val runtimeAppOpToPermissionNames = SparseArray<String>()

    // Maps runtime permission to its appop codes
    private val runtimePermissionNameToAppOp = ArrayMap<String, Int>()

    private var foregroundableOps = SparseBooleanArray()

    /* Maps foreground permissions to their background permission. Background permissions aren't
    required to be runtime */
    private val foregroundToBackgroundPermissionName = ArrayMap<String, String>()

    /* Maps background permissions to their foreground permissions. Background permissions aren't
    required to be runtime */
    private val backgroundToForegroundPermissionNames = ArrayMap<String, ArraySet<String>>()

    private lateinit var handler: Handler

    @Volatile private var listeners = ArraySet<AppOpsModeChangedListener>()
    private val listenersLock = Any()

    fun initialize() {
        // TODO(b/252883039): Wrong handler. Inject main thread handler here.
        handler = Handler(context.mainLooper)

        appIdPolicy.addOnAppOpModeChangedListener(OnAppIdAppOpModeChangedListener())
        packagePolicy.addOnAppOpModeChangedListener(OnPackageAppOpModeChangedListener())
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
        if (Flags.runtimePermissionAppopsMappingEnabled()) {
            createPermissionAppOpMapping()
            val permissionListener = OnPermissionFlagsChangedListener()
            permissionPolicy.addOnPermissionFlagsChangedListener(permissionListener)
            devicePermissionPolicy.addOnPermissionFlagsChangedListener(permissionListener)
        }
    }

    private fun createPermissionAppOpMapping() {
        val permissions = service.getState { with(permissionPolicy) { getPermissions() } }

        for (appOpCode in 0 until AppOpsManager._NUM_OP) {
            AppOpsManager.opToPermission(appOpCode)?.let { permissionName ->
                // Multiple ops might map to a single permission but only one is considered the
                // runtime appop calculations.
                if (appOpCode == AppOpsManager.permissionToOpCode(permissionName)) {
                    val permission = permissions[permissionName]!!
                    if (permission.isRuntime) {
                        runtimePermissionNameToAppOp[permissionName] = appOpCode
                        runtimeAppOpToPermissionNames[appOpCode] = permissionName
                        permission.permissionInfo.backgroundPermission?.let {
                            backgroundPermissionName ->
                            // Note: background permission may not be runtime,
                            // e.g. microphone/camera.
                            foregroundableOps[appOpCode] = true
                            foregroundToBackgroundPermissionName[permissionName] =
                                backgroundPermissionName
                            backgroundToForegroundPermissionNames
                                .getOrPut(backgroundPermissionName, ::ArraySet)
                                .add(permissionName)
                        }
                    }
                }
            }
        }
    }

    override fun getNonDefaultUidModes(uid: Int, deviceId: String): SparseIntArray {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        service.getState {
            val modes =
                with(appIdPolicy) { opNameMapToOpSparseArray(getAppOpModes(appId, userId)?.map) }
            if (Flags.runtimePermissionAppopsMappingEnabled()) {
                runtimePermissionNameToAppOp.forEachIndexed { _, permissionName, appOpCode ->
                    val mode =
                        getUidModeFromPermissionState(appId, userId, permissionName, deviceId)
                    if (mode != AppOpsManager.opToDefaultMode(appOpCode)) {
                        modes[appOpCode] = mode
                    }
                }
            }

            return modes
        }
    }

    override fun getNonDefaultPackageModes(packageName: String, userId: Int): SparseIntArray {
        return opNameMapToOpSparseArray(getPackageModes(packageName, userId))
    }

    override fun getUidMode(uid: Int, deviceId: String, op: Int): Int {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val opName = AppOpsManager.opToPublicName(op)
        val permissionName = runtimeAppOpToPermissionNames[op]

        return if (!Flags.runtimePermissionAppopsMappingEnabled() || permissionName == null) {
            service.getState { with(appIdPolicy) { getAppOpMode(appId, userId, opName) } }
        } else {
            service.getState {
                getUidModeFromPermissionState(appId, userId, permissionName, deviceId)
            }
        }
    }

    private fun getUidModes(uid: Int): ArrayMap<String, Int>? {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        return service.getState { with(appIdPolicy) { getAppOpModes(appId, userId) } }?.map
    }

    private fun GetStateScope.getUidModeFromPermissionState(
        appId: Int,
        userId: Int,
        permissionName: String,
        deviceId: String
    ): Int {
        val checkDevicePermissionFlags =
            deviceId != VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT &&
                permissionName in PermissionManager.DEVICE_AWARE_PERMISSIONS
        val permissionFlags =
            if (checkDevicePermissionFlags) {
                with(devicePermissionPolicy) {
                    getPermissionFlags(appId, deviceId, userId, permissionName)
                }
            } else {
                with(permissionPolicy) { getPermissionFlags(appId, userId, permissionName) }
            }
        val backgroundPermissionName = foregroundToBackgroundPermissionName[permissionName]
        val backgroundPermissionFlags =
            if (backgroundPermissionName != null) {
                if (checkDevicePermissionFlags) {
                    with(devicePermissionPolicy) {
                        getPermissionFlags(appId, deviceId, userId, backgroundPermissionName)
                    }
                } else {
                    with(permissionPolicy) {
                        getPermissionFlags(appId, userId, backgroundPermissionName)
                    }
                }
            } else {
                PermissionFlags.RUNTIME_GRANTED
            }
        val result = evaluateModeFromPermissionFlags(permissionFlags, backgroundPermissionFlags)
        if (result != MODE_IGNORED) {
            return result
        }

        val fullerPermissionName =
            PermissionService.getFullerPermission(permissionName) ?: return result
        return getUidModeFromPermissionState(appId, userId, fullerPermissionName, deviceId)
    }

    private fun evaluateModeFromPermissionFlags(
        foregroundFlags: Int,
        backgroundFlags: Int = PermissionFlags.RUNTIME_GRANTED
    ): Int =
        if (PermissionFlags.isAppOpGranted(foregroundFlags)) {
            if (PermissionFlags.isAppOpGranted(backgroundFlags)) {
                MODE_ALLOWED
            } else {
                MODE_FOREGROUND
            }
        } else {
            MODE_IGNORED
        }

    override fun setUidMode(uid: Int, deviceId: String, code: Int, mode: Int): Boolean {
        if (
            Flags.runtimePermissionAppopsMappingEnabled() && code in runtimeAppOpToPermissionNames
        ) {
            Slog.w(
                LOG_TAG,
                "Cannot set UID mode for runtime permission app op, " +
                    " callingUid = ${Binder.getCallingUid()}, " +
                    " uid = $uid," +
                    " code = ${AppOpsManager.opToName(code)}," +
                    " mode = ${AppOpsManager.modeToName(mode)}",
                RuntimeException()
            )
            return false
        }

        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val appOpName = AppOpsManager.opToPublicName(code)
        var wasChanged: Boolean
        service.mutateState {
            wasChanged = with(appIdPolicy) { setAppOpMode(appId, userId, appOpName, mode) }
        }
        return wasChanged
    }

    override fun getPackageMode(packageName: String, op: Int, userId: Int): Int {
        val opName = AppOpsManager.opToPublicName(op)
        return service.getState {
            with(packagePolicy) { getAppOpMode(packageName, userId, opName) }
        }
    }

    private fun getPackageModes(packageName: String, userId: Int): ArrayMap<String, Int>? =
        service.getState { with(packagePolicy) { getAppOpModes(packageName, userId) } }?.map

    override fun setPackageMode(packageName: String, appOpCode: Int, mode: Int, userId: Int) {
        val appOpName = AppOpsManager.opToPublicName(appOpCode)

        if (
            Flags.runtimePermissionAppopsMappingEnabled() &&
                appOpCode in runtimeAppOpToPermissionNames
        ) {
            Slog.w(
                LOG_TAG,
                "(packageName=$packageName, userId=$userId)'s appop state" +
                    " for runtime op $appOpName should not be set directly.",
                RuntimeException()
            )
            return
        }
        service.mutateState {
            with(packagePolicy) { setAppOpMode(packageName, userId, appOpName, mode) }
        }
    }

    override fun removeUid(uid: Int) {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        service.mutateState { with(appIdPolicy) { removeAppOpModes(appId, userId) } }
    }

    override fun removePackage(packageName: String, userId: Int): Boolean {
        var wasChanged: Boolean
        service.mutateState {
            wasChanged = with(packagePolicy) { removeAppOpModes(packageName, userId) }
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

    override fun clearAllModes() {
        // We don't need to implement this because it's only called in AppOpsService#readState
        // and we have our own persistence.
    }

    override fun getForegroundOps(uid: Int, deviceId: String): SparseBooleanArray {
        return SparseBooleanArray().apply {
            getUidModes(uid)?.forEachIndexed { _, op, mode ->
                if (mode == AppOpsManager.MODE_FOREGROUND) {
                    this[AppOpsManager.strOpToOp(op)] = true
                }
            }
            if (Flags.runtimePermissionAppopsMappingEnabled()) {
                foregroundableOps.forEachIndexed { _, op, _ ->
                    if (getUidMode(uid, deviceId, op) == AppOpsManager.MODE_FOREGROUND) {
                        this[op] = true
                    }
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
            if (Flags.runtimePermissionAppopsMappingEnabled()) {
                foregroundableOps.forEachIndexed { _, op, _ ->
                    if (getPackageMode(packageName, op, userId) == AppOpsManager.MODE_FOREGROUND) {
                        this[op] = true
                    }
                }
            }
        }
    }

    override fun addAppOpsModeChangedListener(listener: AppOpsModeChangedListener): Boolean {
        synchronized(listenersLock) {
            val newListeners = ArraySet(listeners)
            val result = newListeners.add(listener)
            listeners = newListeners
            return result
        }
    }

    override fun removeAppOpsModeChangedListener(listener: AppOpsModeChangedListener): Boolean {
        synchronized(listenersLock) {
            val newListeners = ArraySet(listeners)
            val result = newListeners.remove(listener)
            listeners = newListeners
            return result
        }
    }

    private inner class OnAppIdAppOpModeChangedListener :
        AppIdAppOpPolicy.OnAppOpModeChangedListener() {
        // (uid, appOpCode) -> newMode
        private val pendingChanges = LongSparseArray<Int>()

        override fun onAppOpModeChanged(
            appId: Int,
            userId: Int,
            appOpName: String,
            oldMode: Int,
            newMode: Int
        ) {
            val uid = UserHandle.getUid(userId, appId)
            val appOpCode = AppOpsManager.strOpToOp(appOpName)
            val key = IntPair.of(uid, appOpCode)

            pendingChanges[key] = newMode
        }

        override fun onStateMutated() {
            val listenersLocal = listeners
            pendingChanges.forEachIndexed { _, key, mode ->
                listenersLocal.forEachIndexed { _, listener ->
                    val uid = IntPair.first(key)
                    val appOpCode = IntPair.second(key)

                    listener.onUidModeChanged(
                        uid,
                        appOpCode,
                        mode,
                        VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT
                    )
                }
            }

            pendingChanges.clear()
        }
    }

    private inner class OnPackageAppOpModeChangedListener :
        PackageAppOpPolicy.OnAppOpModeChangedListener() {
        // (packageName, userId, appOpCode) -> newMode
        private val pendingChanges = ArrayMap<Triple<String, Int, Int>, Int>()

        override fun onAppOpModeChanged(
            packageName: String,
            userId: Int,
            appOpName: String,
            oldMode: Int,
            newMode: Int
        ) {
            val appOpCode = AppOpsManager.strOpToOp(appOpName)
            val key = Triple(packageName, userId, appOpCode)

            pendingChanges[key] = newMode
        }

        override fun onStateMutated() {
            val listenersLocal = listeners
            pendingChanges.forEachIndexed { _, key, mode ->
                listenersLocal.forEachIndexed { _, listener ->
                    val packageName = key.first
                    val userId = key.second
                    val appOpCode = key.third

                    listener.onPackageModeChanged(packageName, userId, appOpCode, mode)
                }
            }

            pendingChanges.clear()
        }
    }

    private inner class OnPermissionFlagsChangedListener :
        AppIdPermissionPolicy.OnPermissionFlagsChangedListener,
        DevicePermissionPolicy.OnDevicePermissionFlagsChangedListener {
        // (uid, deviceId, appOpCode) -> newMode
        private val pendingChanges = ArrayMap<Triple<Int, String, Int>, Int>()

        override fun onPermissionFlagsChanged(
            appId: Int,
            userId: Int,
            permissionName: String,
            oldFlags: Int,
            newFlags: Int
        ) {
            onDevicePermissionFlagsChanged(
                appId,
                userId,
                VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT,
                permissionName,
                oldFlags,
                newFlags
            )
        }

        override fun onDevicePermissionFlagsChanged(
            appId: Int,
            userId: Int,
            deviceId: String,
            permissionName: String,
            oldFlags: Int,
            newFlags: Int
        ) {
            backgroundToForegroundPermissionNames[permissionName]?.let { foregroundPermissions ->
                // This is a background permission; there may be multiple foreground permissions
                // affected.
                foregroundPermissions.forEachIndexed { _, foregroundPermissionName ->
                    runtimePermissionNameToAppOp[foregroundPermissionName]?.let { appOpCode ->
                        val foregroundPermissionFlags =
                            getPermissionFlags(appId, userId, foregroundPermissionName)
                        addPendingChangedModeIfNeeded(
                            appId,
                            userId,
                            deviceId,
                            appOpCode,
                            foregroundPermissionFlags,
                            oldFlags,
                            foregroundPermissionFlags,
                            newFlags
                        )
                    }
                }
            }
                ?: foregroundToBackgroundPermissionName[permissionName]?.let { backgroundPermission
                    ->
                    runtimePermissionNameToAppOp[permissionName]?.let { appOpCode ->
                        val backgroundPermissionFlags =
                            getPermissionFlags(appId, userId, backgroundPermission)
                        addPendingChangedModeIfNeeded(
                            appId,
                            userId,
                            deviceId,
                            appOpCode,
                            oldFlags,
                            backgroundPermissionFlags,
                            newFlags,
                            backgroundPermissionFlags
                        )
                    }
                }
                    ?: runtimePermissionNameToAppOp[permissionName]?.let { appOpCode ->
                    addPendingChangedModeIfNeeded(
                        appId,
                        userId,
                        deviceId,
                        appOpCode,
                        oldFlags,
                        PermissionFlags.RUNTIME_GRANTED,
                        newFlags,
                        PermissionFlags.RUNTIME_GRANTED
                    )
                }
        }

        private fun getPermissionFlags(appId: Int, userId: Int, permissionName: String): Int =
            service.getState {
                with(permissionPolicy) { getPermissionFlags(appId, userId, permissionName) }
            }

        private fun addPendingChangedModeIfNeeded(
            appId: Int,
            userId: Int,
            deviceId: String,
            appOpCode: Int,
            oldForegroundFlags: Int,
            oldBackgroundFlags: Int,
            newForegroundFlags: Int,
            newBackgroundFlags: Int,
        ) {
            val oldMode = evaluateModeFromPermissionFlags(oldForegroundFlags, oldBackgroundFlags)
            val newMode = evaluateModeFromPermissionFlags(newForegroundFlags, newBackgroundFlags)

            if (oldMode != newMode) {
                val uid = UserHandle.getUid(userId, appId)
                pendingChanges[Triple(uid, deviceId, appOpCode)] = newMode
            }
        }

        override fun onStateMutated() {
            val listenersLocal = listeners
            pendingChanges.forEachIndexed { _, key, mode ->
                listenersLocal.forEachIndexed { _, listener ->
                    val uid = key.first
                    val deviceId = key.second
                    val appOpCode = key.third

                    listener.onUidModeChanged(uid, appOpCode, mode, deviceId)
                }
            }

            pendingChanges.clear()
        }
    }

    companion object {
        private val LOG_TAG = AppOpService::class.java.simpleName
    }
}
