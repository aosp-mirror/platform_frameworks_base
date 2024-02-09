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

import android.permission.PermissionManager
import android.util.Slog
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.DevicePermissionUri
import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.SchemePolicy
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.andInv
import com.android.server.pm.pkg.PackageState

class DevicePermissionPolicy : SchemePolicy() {
    private val persistence = DevicePermissionPersistence()

    @Volatile
    private var listeners: IndexedListSet<OnDevicePermissionFlagsChangedListener> =
        MutableIndexedListSet()
    private val listenersLock = Any()

    override val subjectScheme: String
        get() = UidUri.SCHEME

    override val objectScheme: String
        get() = DevicePermissionUri.SCHEME

    override fun GetStateScope.onStateMutated() {
        listeners.forEachIndexed { _, it -> it.onStateMutated() }
    }

    override fun MutateStateScope.onAppIdRemoved(appId: Int) {
        newState.userStates.forEachIndexed { userStateIndex, _, userState ->
            if (appId in userState.appIdDevicePermissionFlags) {
                newState.mutateUserStateAt(userStateIndex).mutateAppIdDevicePermissionFlags() -=
                    appId
            }
        }
    }

    fun MutateStateScope.trimDevicePermissionStates(deviceIds: Set<String>) {
        newState.userStates.forEachIndexed { _, userId, userState ->
            userState.appIdDevicePermissionFlags.forEachReversedIndexed { _, appId, _ ->
                val appIdDevicePermissionFlags =
                    newState.mutateUserState(userId)!!.mutateAppIdDevicePermissionFlags()
                val devicePermissionFlags =
                    appIdDevicePermissionFlags.mutate(appId) ?: return@forEachReversedIndexed

                devicePermissionFlags.forEachReversedIndexed { _, deviceId, _ ->
                    if (deviceId !in deviceIds) {
                        devicePermissionFlags -= deviceId
                    }
                }
            }
        }
    }

    fun MutateStateScope.onDeviceIdRemoved(deviceId: String) {
        newState.userStates.forEachIndexed { _, userId, userState ->
            userState.appIdDevicePermissionFlags.forEachReversedIndexed { _, appId, _ ->
                val appIdDevicePermissionFlags =
                    newState.mutateUserState(userId)!!.mutateAppIdDevicePermissionFlags()
                val devicePermissionFlags =
                    appIdDevicePermissionFlags.mutate(appId) ?: return@forEachReversedIndexed
                devicePermissionFlags -= deviceId
            }
        }
    }

    override fun MutateStateScope.onStorageVolumeMounted(
        volumeUuid: String?,
        packageNames: List<String>,
        isSystemUpdated: Boolean
    ) {
        packageNames.forEachIndexed { _, packageName ->
            // The package may still be removed even if it was once notified as installed.
            val packageState =
                newState.externalState.packageStates[packageName] ?: return@forEachIndexed
            trimPermissionStates(packageState.appId)
        }
    }

    override fun MutateStateScope.onPackageAdded(packageState: PackageState) {
        trimPermissionStates(packageState.appId)
    }

    override fun MutateStateScope.onPackageRemoved(packageName: String, appId: Int) {
        if (appId in newState.externalState.appIdPackageNames) {
            trimPermissionStates(appId)
        }
    }

    override fun MutateStateScope.onPackageUninstalled(
        packageName: String,
        appId: Int,
        userId: Int
    ) {
        resetRuntimePermissions(packageName, userId)
    }

    /**
     * Reset permission states for all permissions requested by the given package, if no other
     * package (sharing the App ID) request these permissions.
     */
    fun MutateStateScope.resetRuntimePermissions(packageName: String, userId: Int) {
        // It's okay to skip resetting permissions for packages that are removed,
        // because their states will be trimmed in onPackageRemoved()/onAppIdRemoved()
        val packageState = newState.externalState.packageStates[packageName] ?: return
        val androidPackage = packageState.androidPackage ?: return
        val appId = packageState.appId
        // The user may happen removed due to DeletePackageHelper.removeUnusedPackagesLPw() calling
        // deletePackageX() asynchronously.
        val userState = newState.userStates[userId] ?: return
        val devicePermissionFlags = userState.appIdDevicePermissionFlags[appId] ?: return
        androidPackage.requestedPermissions.forEach { permissionName ->
            val isRequestedByOtherPackages =
                anyPackageInAppId(appId) {
                    it.packageName != packageName &&
                        permissionName in it.androidPackage!!.requestedPermissions
                }
            if (isRequestedByOtherPackages) {
                return@forEach
            }
            devicePermissionFlags.forEachIndexed { _, deviceId, _ ->
                setPermissionFlags(appId, deviceId, userId, permissionName, 0)
            }
        }
    }

    // Trims permission state for permissions not requested by the App ID anymore.
    private fun MutateStateScope.trimPermissionStates(appId: Int) {
        val requestedPermissions = MutableIndexedSet<String>()
        forEachPackageInAppId(appId) {
            requestedPermissions += it.androidPackage!!.requestedPermissions
        }
        newState.userStates.forEachIndexed { _, userId, userState ->
            userState.appIdDevicePermissionFlags[appId]?.forEachReversedIndexed {
                _,
                deviceId,
                permissionFlags ->
                permissionFlags.forEachReversedIndexed { _, permissionName, _ ->
                    if (permissionName !in requestedPermissions) {
                        setPermissionFlags(appId, deviceId, userId, permissionName, 0)
                    }
                }
            }
        }
    }

    private inline fun MutateStateScope.anyPackageInAppId(
        appId: Int,
        state: AccessState = newState,
        predicate: (PackageState) -> Boolean
    ): Boolean {
        val packageNames = state.externalState.appIdPackageNames[appId]!!
        return packageNames.anyIndexed { _, packageName ->
            val packageState = state.externalState.packageStates[packageName]!!
            packageState.androidPackage != null && predicate(packageState)
        }
    }

    private inline fun MutateStateScope.forEachPackageInAppId(
        appId: Int,
        state: AccessState = newState,
        action: (PackageState) -> Unit
    ) {
        val packageNames = state.externalState.appIdPackageNames[appId]!!
        packageNames.forEachIndexed { _, packageName ->
            val packageState = state.externalState.packageStates[packageName]!!
            if (packageState.androidPackage != null) {
                action(packageState)
            }
        }
    }

    override fun BinaryXmlPullParser.parseUserState(state: MutableAccessState, userId: Int) {
        with(persistence) { this@parseUserState.parseUserState(state, userId) }
    }

    override fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {
        with(persistence) { this@serializeUserState.serializeUserState(state, userId) }
    }

    fun GetStateScope.getPermissionFlags(
        appId: Int,
        deviceId: String,
        userId: Int,
        permissionName: String
    ): Int {
        val flags =
            state.userStates[userId]
                ?.appIdDevicePermissionFlags
                ?.get(appId)
                ?.get(deviceId)
                ?.getWithDefault(permissionName, 0)
                ?: 0
        if (PermissionManager.DEBUG_DEVICE_PERMISSIONS) {
            Slog.i(
                LOG_TAG,
                "getPermissionFlags: appId=$appId, userId=$userId," +
                    " deviceId=$deviceId, permissionName=$permissionName," +
                    " flags=${PermissionFlags.toString(flags)}"
            )
        }
        return flags
    }

    fun GetStateScope.getAllPermissionFlags(
        appId: Int,
        persistentDeviceId: String,
        userId: Int
    ): IndexedMap<String, Int>? =
        state.userStates[userId]
            ?.appIdDevicePermissionFlags
            ?.get(appId)
            ?.get(persistentDeviceId)

    fun MutateStateScope.setPermissionFlags(
        appId: Int,
        deviceId: String,
        userId: Int,
        permissionName: String,
        flags: Int
    ): Boolean =
        updatePermissionFlags(
            appId,
            deviceId,
            userId,
            permissionName,
            PermissionFlags.MASK_ALL,
            flags
        )

    private fun MutateStateScope.updatePermissionFlags(
        appId: Int,
        deviceId: String,
        userId: Int,
        permissionName: String,
        flagMask: Int,
        flagValues: Int
    ): Boolean {
        if (userId !in newState.userStates) {
            // Despite that we check UserManagerInternal.exists() in PermissionService, we may still
            // sometimes get race conditions between that check and the actual mutateState() call.
            // This should rarely happen but at least we should not crash.
            Slog.e(LOG_TAG, "Unable to update permission flags for missing user $userId")
            return false
        }
        val oldFlags =
            newState.userStates[userId]!!
                .appIdDevicePermissionFlags[appId]
                ?.get(deviceId)
                .getWithDefault(permissionName, 0)
        val newFlags = (oldFlags andInv flagMask) or (flagValues and flagMask)
        if (oldFlags == newFlags) {
            return false
        }
        val appIdDevicePermissionFlags =
            newState.mutateUserState(userId)!!.mutateAppIdDevicePermissionFlags()
        val devicePermissionFlags =
            appIdDevicePermissionFlags.mutateOrPut(appId) { MutableIndexedReferenceMap() }
        if (PermissionManager.DEBUG_DEVICE_PERMISSIONS) {
            Slog.i(
                LOG_TAG,
                "setPermissionFlags(): appId=$appId, userId=$userId," +
                    " deviceId=$deviceId, permissionName=$permissionName," +
                    " newFlags=${PermissionFlags.toString(newFlags)}"
            )
        }
        val permissionFlags = devicePermissionFlags.mutateOrPut(deviceId) { MutableIndexedMap() }
        permissionFlags.putWithDefault(permissionName, newFlags, 0)
        if (permissionFlags.isEmpty()) {
            devicePermissionFlags -= deviceId
            if (devicePermissionFlags.isEmpty()) {
                appIdDevicePermissionFlags -= appId
            }
        }
        listeners.forEachIndexed { _, it ->
            it.onDevicePermissionFlagsChanged(
                appId,
                userId,
                deviceId,
                permissionName,
                oldFlags,
                newFlags
            )
        }
        return true
    }

    fun addOnPermissionFlagsChangedListener(listener: OnDevicePermissionFlagsChangedListener) {
        synchronized(listenersLock) { listeners = listeners + listener }
    }

    companion object {
        private val LOG_TAG = DevicePermissionPolicy::class.java.simpleName
    }

    /** Listener for permission flags changes. */
    interface OnDevicePermissionFlagsChangedListener {
        /**
         * Called when a permission flags change has been made to the upcoming new state.
         *
         * Implementations should keep this method fast to avoid stalling the locked state mutation,
         * and only call external code after [onStateMutated] when the new state has actually become
         * the current state visible to external code.
         */
        fun onDevicePermissionFlagsChanged(
            appId: Int,
            userId: Int,
            deviceId: String,
            permissionName: String,
            oldFlags: Int,
            newFlags: Int
        )

        /**
         * Called when the upcoming new state has become the current state.
         *
         * Implementations should keep this method fast to avoid stalling the locked state mutation.
         */
        fun onStateMutated()
    }
}
