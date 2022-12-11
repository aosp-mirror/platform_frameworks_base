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
import com.android.server.SystemConfig
import com.android.server.SystemService
import com.android.server.appop.AppOpsCheckingServiceInterface
import com.android.server.permission.access.appop.AppOpService
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.permission.PermissionService
import com.android.server.pm.PackageManagerLocal
import com.android.server.pm.UserManagerService
import com.android.server.pm.permission.PermissionManagerServiceInterface
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
    private lateinit var systemConfig: SystemConfig

    override fun onStart() {
        appOpService = AppOpService(this)
        permissionService = PermissionService(this)

        LocalServices.addService(AppOpsCheckingServiceInterface::class.java, appOpService)
        LocalServices.addService(PermissionManagerServiceInterface::class.java, permissionService)
    }

    fun initialize() {
        packageManagerLocal =
            LocalManagerRegistry.getManagerOrThrow(PackageManagerLocal::class.java)
        userManagerService = UserManagerService.getInstance()
        systemConfig = SystemConfig.getInstance()

        val userIds = IntSet(userManagerService.userIdsIncludingPreCreated)
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        val permissionAllowlist = systemConfig.permissionAllowlist

        val state = AccessState()
        policy.initialize(
            state, userIds, packageStates, disabledSystemPackageStates, permissionAllowlist
        )
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
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        mutateState {
            with(policy) {
                onStorageVolumeMounted(
                    packageStates, disabledSystemPackageStates, volumeUuid, isSystemUpdated
                )
            }
        }
    }

    internal fun onPackageAdded(packageName: String) {
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        mutateState {
            with(policy) { onPackageAdded(packageStates, disabledSystemPackageStates, packageName) }
        }
    }

    internal fun onPackageRemoved(packageName: String, appId: Int) {
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        mutateState {
            with(policy) {
                onPackageRemoved(packageStates, disabledSystemPackageStates, packageName, appId)
            }
        }
    }

    internal fun onPackageInstalled(packageName: String, userId: Int) {
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        mutateState {
            with(policy) {
                onPackageInstalled(packageStates, disabledSystemPackageStates, packageName, userId)
            }
        }
    }

    internal fun onPackageUninstalled(packageName: String, appId: Int, userId: Int) {
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        mutateState {
            with(policy) {
                onPackageUninstalled(
                    packageStates, disabledSystemPackageStates, packageName, appId, userId
                )
            }
        }
    }

    private val PackageManagerLocal.allPackageStates:
        Pair<Map<String, PackageState>, Map<String, PackageState>>
        get() = withUnfilteredSnapshot().use { it.packageStates to it.disabledSystemPackageStates }

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
