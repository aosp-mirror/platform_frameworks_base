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
import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.pm.pkg.PackageState

class AppIdAppOpPolicy : BaseAppOpPolicy(AppIdAppOpPersistence()) {
    private val migration = AppIdAppOpMigration()

    private val upgrade = AppIdAppOpUpgrade(this)

    @Volatile
    private var onAppOpModeChangedListeners: IndexedListSet<OnAppOpModeChangedListener> =
        MutableIndexedListSet()
    private val onAppOpModeChangedListenersLock = Any()

    override val subjectScheme: String
        get() = UidUri.SCHEME

    override fun GetStateScope.onStateMutated() {
        onAppOpModeChangedListeners.forEachIndexed { _, it -> it.onStateMutated() }
    }

    override fun MutateStateScope.onAppIdRemoved(appId: Int) {
        newState.userStates.forEachIndexed { userStateIndex, _, userState ->
            val appIdIndex = userState.appIdAppOpModes.indexOfKey(appId)
            if (appIdIndex >= 0) {
                newState
                    .mutateUserStateAt(userStateIndex)
                    .mutateAppIdAppOpModes()
                    .removeAt(appIdIndex)
                // Skip notifying the change listeners since the app ID no longer exists.
            }
        }
    }

    fun GetStateScope.getAppOpModes(appId: Int, userId: Int): IndexedMap<String, Int>? =
        state.userStates[userId]?.appIdAppOpModes?.get(appId)

    fun MutateStateScope.removeAppOpModes(appId: Int, userId: Int): Boolean {
        val userStateIndex = newState.userStates.indexOfKey(userId)
        if (userStateIndex < 0) {
            return false
        }
        val appIdIndex =
            newState.userStates.valueAt(userStateIndex).appIdAppOpModes.indexOfKey(appId)
        if (appIdIndex < 0) {
            return false
        }
        newState.mutateUserStateAt(userStateIndex).mutateAppIdAppOpModes().removeAt(appIdIndex)
        return true
    }

    fun GetStateScope.getAppOpMode(appId: Int, userId: Int, appOpName: String): Int =
        state.userStates[userId]
            ?.appIdAppOpModes
            ?.get(appId)
            .getWithDefault(appOpName, AppOpsManager.opToDefaultMode(appOpName))

    fun MutateStateScope.setAppOpMode(
        appId: Int,
        userId: Int,
        appOpName: String,
        mode: Int
    ): Boolean {
        val defaultMode = AppOpsManager.opToDefaultMode(appOpName)
        val oldMode =
            newState.userStates[userId]!!
                .appIdAppOpModes[appId]
                .getWithDefault(appOpName, defaultMode)
        if (oldMode == mode) {
            return false
        }
        val appIdAppOpModes = newState.mutateUserState(userId)!!.mutateAppIdAppOpModes()
        val appOpModes = appIdAppOpModes.mutateOrPut(appId) { MutableIndexedMap() }
        appOpModes.putWithDefault(appOpName, mode, defaultMode)
        if (appOpModes.isEmpty()) {
            appIdAppOpModes -= appId
        }
        onAppOpModeChangedListeners.forEachIndexed { _, it ->
            it.onAppOpModeChanged(appId, userId, appOpName, oldMode, mode)
        }
        return true
    }

    fun addOnAppOpModeChangedListener(listener: OnAppOpModeChangedListener) {
        synchronized(onAppOpModeChangedListenersLock) {
            onAppOpModeChangedListeners = onAppOpModeChangedListeners + listener
        }
    }

    fun removeOnAppOpModeChangedListener(listener: OnAppOpModeChangedListener) {
        synchronized(onAppOpModeChangedListenersLock) {
            onAppOpModeChangedListeners = onAppOpModeChangedListeners - listener
        }
    }

    override fun migrateUserState(state: MutableAccessState, userId: Int) {
        with(migration) { migrateUserState(state, userId) }
    }

    override fun MutateStateScope.upgradePackageState(
        packageState: PackageState,
        userId: Int,
        version: Int,
    ) {
        with(upgrade) { upgradePackageState(packageState, userId, version) }
    }

    /** Listener for app op mode changes. */
    abstract class OnAppOpModeChangedListener {
        /**
         * Called when an app op mode change has been made to the upcoming new state.
         *
         * Implementations should keep this method fast to avoid stalling the locked state mutation,
         * and only call external code after [onStateMutated] when the new state has actually become
         * the current state visible to external code.
         */
        abstract fun onAppOpModeChanged(
            appId: Int,
            userId: Int,
            appOpName: String,
            oldMode: Int,
            newMode: Int
        )

        /**
         * Called when the upcoming new state has become the current state.
         *
         * Implementations should keep this method fast to avoid stalling the locked state mutation.
         */
        abstract fun onStateMutated()
    }
}
