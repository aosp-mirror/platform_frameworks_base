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
import android.content.pm.PackageManager
import android.content.pm.PackageManagerInternal
import android.os.SystemProperties
import android.os.UserHandle
import com.android.internal.annotations.Keep
import com.android.server.LocalManagerRegistry
import com.android.server.LocalServices
import com.android.server.SystemConfig
import com.android.server.SystemService
import com.android.server.appop.AppOpsCheckingServiceInterface
import com.android.server.permission.PermissionManagerLocal
import com.android.server.permission.access.appop.AppOpService
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.permission.PermissionManagerLocalImpl
import com.android.server.permission.access.permission.PermissionService
import com.android.server.pm.KnownPackages
import com.android.server.pm.PackageManagerLocal
import com.android.server.pm.UserManagerService
import com.android.server.pm.permission.PermissionManagerServiceInterface
import com.android.server.pm.pkg.PackageState
import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.InvocationKind
import kotlin.contracts.contract

@Keep
class AccessCheckingService(context: Context) : SystemService(context) {
    @Volatile private lateinit var state: AccessState
    private val stateLock = Any()

    private val policy = AccessPolicy()

    private val persistence = AccessPersistence(policy)

    private lateinit var appOpService: AppOpService
    private lateinit var permissionService: PermissionService

    private lateinit var packageManagerInternal: PackageManagerInternal
    private lateinit var packageManagerLocal: PackageManagerLocal
    private lateinit var userManagerService: UserManagerService
    private lateinit var systemConfig: SystemConfig

    override fun onStart() {
        appOpService = AppOpService(this)
        permissionService = PermissionService(this)

        LocalServices.addService(AppOpsCheckingServiceInterface::class.java, appOpService)
        LocalServices.addService(PermissionManagerServiceInterface::class.java, permissionService)

        LocalManagerRegistry.addManager(
            PermissionManagerLocal::class.java,
            PermissionManagerLocalImpl(this)
        )
    }

    fun initialize() {
        packageManagerInternal = LocalServices.getService(PackageManagerInternal::class.java)
        packageManagerLocal =
            LocalManagerRegistry.getManagerOrThrow(PackageManagerLocal::class.java)
        userManagerService = UserManagerService.getInstance()
        systemConfig = SystemConfig.getInstance()

        val userIds = MutableIntSet(userManagerService.userIdsIncludingPreCreated)
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        val knownPackages = packageManagerInternal.knownPackages
        val isLeanback = systemConfig.isLeanback
        val configPermissions = systemConfig.permissions
        val privilegedPermissionAllowlistPackages =
            systemConfig.privilegedPermissionAllowlistPackages
        val permissionAllowlist = systemConfig.permissionAllowlist
        val implicitToSourcePermissions = systemConfig.implicitToSourcePermissions

        val state = MutableAccessState()
        policy.initialize(
            state,
            userIds,
            packageStates,
            disabledSystemPackageStates,
            knownPackages,
            isLeanback,
            configPermissions,
            privilegedPermissionAllowlistPackages,
            permissionAllowlist,
            implicitToSourcePermissions
        )
        persistence.initialize()
        persistence.read(state)
        this.state = state

        appOpService.initialize()
        permissionService.initialize()
    }

    private val SystemConfig.isLeanback: Boolean
        get() = PackageManager.FEATURE_LEANBACK in availableFeatures

    private val SystemConfig.privilegedPermissionAllowlistPackages: IndexedListSet<String>
        get() =
            MutableIndexedListSet<String>().apply {
                this += "android"
                if (PackageManager.FEATURE_AUTOMOTIVE in availableFeatures) {
                    // Note that SystemProperties.get(String, String) forces returning an empty
                    // string
                    // even if we pass null for the def parameter.
                    val carServicePackage =
                        SystemProperties.get("ro.android.car.carservice.package")
                    if (carServicePackage.isNotEmpty()) {
                        this += carServicePackage
                    }
                }
            }

    private val SystemConfig.implicitToSourcePermissions: IndexedMap<String, IndexedListSet<String>>
        @Suppress("UNCHECKED_CAST")
        get() =
            MutableIndexedMap<String, MutableIndexedListSet<String>>().apply {
                splitPermissions.forEach { splitPermissionInfo ->
                    val sourcePermissionName = splitPermissionInfo.splitPermission
                    splitPermissionInfo.newPermissions.forEach { implicitPermissionName ->
                        getOrPut(implicitPermissionName) { MutableIndexedListSet() } +=
                            sourcePermissionName
                    }
                }
            } as IndexedMap<String, IndexedListSet<String>>

    internal fun onUserAdded(userId: Int) {
        mutateState { with(policy) { onUserAdded(userId) } }
    }

    internal fun onUserRemoved(userId: Int) {
        mutateState { with(policy) { onUserRemoved(userId) } }
    }

    internal fun onStorageVolumeMounted(
        volumeUuid: String?,
        packageNames: List<String>,
        isSystemUpdated: Boolean
    ) {
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        val knownPackages = packageManagerInternal.knownPackages
        mutateState {
            with(policy) {
                onStorageVolumeMounted(
                    packageStates,
                    disabledSystemPackageStates,
                    knownPackages,
                    volumeUuid,
                    packageNames,
                    isSystemUpdated
                )
            }
        }
    }

    internal fun onPackageAdded(packageName: String) {
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        val knownPackages = packageManagerInternal.knownPackages
        mutateState {
            with(policy) {
                onPackageAdded(
                    packageStates,
                    disabledSystemPackageStates,
                    knownPackages,
                    packageName
                )
            }
        }
    }

    internal fun onPackageRemoved(packageName: String, appId: Int) {
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        val knownPackages = packageManagerInternal.knownPackages
        mutateState {
            with(policy) {
                onPackageRemoved(
                    packageStates,
                    disabledSystemPackageStates,
                    knownPackages,
                    packageName,
                    appId
                )
            }
        }
    }

    internal fun onPackageInstalled(packageName: String, userId: Int) {
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        val knownPackages = packageManagerInternal.knownPackages
        mutateState {
            with(policy) {
                onPackageInstalled(
                    packageStates,
                    disabledSystemPackageStates,
                    knownPackages,
                    packageName,
                    userId
                )
            }
        }
    }

    internal fun onPackageUninstalled(packageName: String, appId: Int, userId: Int) {
        val (packageStates, disabledSystemPackageStates) = packageManagerLocal.allPackageStates
        val knownPackages = packageManagerInternal.knownPackages
        mutateState {
            with(policy) {
                onPackageUninstalled(
                    packageStates,
                    disabledSystemPackageStates,
                    knownPackages,
                    packageName,
                    appId,
                    userId
                )
            }
        }
    }

    internal fun onSystemReady() {
        mutateState { with(policy) { onSystemReady() } }
    }

    private val PackageManagerLocal.allPackageStates:
        Pair<Map<String, PackageState>, Map<String, PackageState>>
        get() = withUnfilteredSnapshot().use { it.packageStates to it.disabledSystemPackageStates }

    private val PackageManagerInternal.knownPackages: IntMap<Array<String>>
        get() =
            MutableIntMap<Array<String>>().apply {
                this[KnownPackages.PACKAGE_INSTALLER] = getKnownPackageNames(
                    KnownPackages.PACKAGE_INSTALLER, UserHandle.USER_SYSTEM
                )
                this[KnownPackages.PACKAGE_PERMISSION_CONTROLLER] = getKnownPackageNames(
                    KnownPackages.PACKAGE_PERMISSION_CONTROLLER, UserHandle.USER_SYSTEM
                )
                this[KnownPackages.PACKAGE_VERIFIER] = getKnownPackageNames(
                    KnownPackages.PACKAGE_VERIFIER, UserHandle.USER_SYSTEM
                )
                this[KnownPackages.PACKAGE_SETUP_WIZARD] = getKnownPackageNames(
                    KnownPackages.PACKAGE_SETUP_WIZARD, UserHandle.USER_SYSTEM
                )
                this[KnownPackages.PACKAGE_SYSTEM_TEXT_CLASSIFIER] = getKnownPackageNames(
                    KnownPackages.PACKAGE_SYSTEM_TEXT_CLASSIFIER, UserHandle.USER_SYSTEM
                )
                this[KnownPackages.PACKAGE_CONFIGURATOR] = getKnownPackageNames(
                    KnownPackages.PACKAGE_CONFIGURATOR, UserHandle.USER_SYSTEM
                )
                this[KnownPackages.PACKAGE_INCIDENT_REPORT_APPROVER] = getKnownPackageNames(
                    KnownPackages.PACKAGE_INCIDENT_REPORT_APPROVER, UserHandle.USER_SYSTEM
                )
                this[KnownPackages.PACKAGE_APP_PREDICTOR] = getKnownPackageNames(
                    KnownPackages.PACKAGE_APP_PREDICTOR, UserHandle.USER_SYSTEM
                )
                this[KnownPackages.PACKAGE_COMPANION] = getKnownPackageNames(
                    KnownPackages.PACKAGE_COMPANION, UserHandle.USER_SYSTEM
                )
                this[KnownPackages.PACKAGE_RETAIL_DEMO] = getKnownPackageNames(
                    KnownPackages.PACKAGE_RETAIL_DEMO, UserHandle.USER_SYSTEM
                )
                this[KnownPackages.PACKAGE_RECENTS] = getKnownPackageNames(
                    KnownPackages.PACKAGE_RECENTS, UserHandle.USER_SYSTEM
                )
            }

    @OptIn(ExperimentalContracts::class)
    internal inline fun <T> getState(action: GetStateScope.() -> T): T {
        contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
        return GetStateScope(state).action()
    }

    @OptIn(ExperimentalContracts::class)
    internal inline fun mutateState(crossinline action: MutateStateScope.() -> Unit) {
        contract { callsInPlace(action, InvocationKind.EXACTLY_ONCE) }
        synchronized(stateLock) {
            val oldState = state
            val newState = oldState.toMutable()
            MutateStateScope(oldState, newState).action()
            persistence.write(newState)
            state = newState
            with(policy) { GetStateScope(newState).onStateMutated() }
        }
    }

    internal fun getSchemePolicy(subjectScheme: String, objectScheme: String): SchemePolicy =
        policy.getSchemePolicy(subjectScheme, objectScheme)
}
