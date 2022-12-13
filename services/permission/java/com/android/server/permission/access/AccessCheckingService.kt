/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.permission.access

import android.content.Context
import com.android.internal.annotations.Keep
import com.android.server.LocalManagerRegistry
import com.android.server.LocalServices
import com.android.server.SystemService
import com.android.server.appop.AppOpsServiceInterface
import com.android.server.permission.access.appop.AppOpService
import com.android.server.permission.access.collection.IntSet
import com.android.server.permission.access.permission.PermissionService
import com.android.server.pm.PackageManagerLocal
import com.android.server.pm.UserManagerService
import com.android.server.pm.permission.PermissionManagerServiceInterface
import com.android.server.pm.permission.PermissionManagerServiceInternal
import com.android.server.pm.pkg.PackageState

@Keep
class AccessCheckingService(context: Context) : SystemService(context) {
    @Volatile
    private lateinit var state: AccessState
    private val stateLock = Any()

    private val policy = AccessPolicy()

    private val persistence = AccessPersistence(policy)

    private lateinit var appOpService: AppOpService
    private lateinit var permissionService: PermissionService

    private lateinit var packageManagerLocal: PackageManagerLocal
    private lateinit var userManagerService: UserManagerService

    override fun onStart() {
        appOpService = AppOpService(this)
        permissionService = PermissionService(this)

        LocalServices.addService(AppOpsServiceInterface::class.java, appOpService)
        LocalServices.addService(PermissionManagerServiceInterface::class.java, permissionService)
    }

    fun initialize() {
        packageManagerLocal =
            LocalManagerRegistry.getManagerOrThrow(PackageManagerLocal::class.java)
        userManagerService = UserManagerService.getInstance()

        val userIds = IntSet(userManagerService.userIdsIncludingPreCreated)
        val packageStates = packageManagerLocal.packageStates

        val state = AccessState()
        policy.initialize(state, userIds, packageStates)
        persistence.read(state)
        this.state = state

        appOpService.initialize()
        permissionService.initialize()
    }

    fun getDecision(subject: AccessUri, `object`: AccessUri): Int =
        getState {
            with(policy) { getDecision(subject, `object`) }
        }

    fun setDecision(subject: AccessUri, `object`: AccessUri, decision: Int) {
        mutateState {
            with(policy) { setDecision(subject, `object`, decision) }
        }
    }

    internal fun onUserAdded(userId: Int) {
        mutateState {
            with(policy) { onUserAdded(userId) }
        }
    }

    internal fun onUserRemoved(userId: Int) {
        mutateState {
            with(policy) { onUserRemoved(userId) }
        }
    }

    internal fun onStorageVolumeMounted(volumeUuid: String?, isSystemUpdated: Boolean) {
        val packageStates = packageManagerLocal.packageStates
        mutateState {
            with(policy) { onStorageVolumeMounted(packageStates, volumeUuid, isSystemUpdated) }
        }
    }

    internal fun onPackageAdded(packageName: String) {
        val packageStates = packageManagerLocal.packageStates
        mutateState {
            with(policy) { onPackageAdded(packageStates, packageName) }
        }
    }

    internal fun onPackageRemoved(packageName: String, appId: Int) {
        val packageStates = packageManagerLocal.packageStates
        mutateState {
            with(policy) { onPackageRemoved(packageStates, packageName, appId) }
        }
    }

    internal fun onPackageInstalled(
        packageName: String,
        params: PermissionManagerServiceInternal.PackageInstalledParams,
        userId: Int
    ) {
        val packageStates = packageManagerLocal.packageStates
        mutateState {
            with(policy) { onPackageInstalled(packageStates, packageName, params, userId) }
        }
    }

    internal fun onPackageUninstalled(packageName: String, appId: Int, userId: Int) {
        val packageStates = packageManagerLocal.packageStates
        mutateState {
            with(policy) { onPackageUninstalled(packageStates, packageName, appId, userId) }
        }
    }

    private val PackageManagerLocal.packageStates: Map<String, PackageState>
        get() = withUnfilteredSnapshot().use { it.packageStates }

    internal inline fun <T> getState(action: GetStateScope.() -> T): T =
        GetStateScope(state).action()

    internal inline fun mutateState(action: MutateStateScope.() -> Unit) {
        synchronized(stateLock) {
            val oldState = state
            val newState = oldState.copy()
            MutateStateScope(oldState, newState).action()
            persistence.write(newState)
            state = newState
        }
    }

    internal fun getSchemePolicy(subjectScheme: String, objectScheme: String): SchemePolicy =
        policy.getSchemePolicy(subjectScheme, objectScheme)
}
