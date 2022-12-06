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

import android.content.pm.PermissionGroupInfo
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.data.Permission
import com.android.server.permission.access.external.PackageState

class AccessState private constructor(
    val systemState: SystemState,
    val userStates: IntMap<UserState>
) {
    constructor() : this(SystemState(), IntMap())

    fun copy(): AccessState = AccessState(systemState.copy(), userStates.copy { it.copy() })
}

class SystemState private constructor(
    val userIds: IntSet,
    val packageStates: IndexedMap<String, PackageState>,
    val disabledSystemPackageStates: IndexedMap<String, PackageState>,
    val appIds: IntMap<IndexedListSet<String>>,
    // A map of KnownPackagesInt to a set of known package names
    val knownPackages: IntMap<IndexedListSet<String>>,
    // A map of userId to packageName
    val deviceAndProfileOwners: IntMap<String>,
    // A map of packageName to (A map of oem permission name to whether it's granted)
    val oemPermissions: IndexedMap<String, IndexedMap<String, Boolean>>,
    val privilegedPermissionAllowlistSourcePackageNames: IndexedListSet<String>,
    // A map of packageName to a set of vendor priv app permission names
    val vendorPrivAppPermissions: Map<String, Set<String>>,
    val productPrivAppPermissions: Map<String, Set<String>>,
    val systemExtPrivAppPermissions: Map<String, Set<String>>,
    val privAppPermissions: Map<String, Set<String>>,
    val apexPrivAppPermissions: Map<String, Map<String, Set<String>>>,
    val vendorPrivAppDenyPermissions: Map<String, Set<String>>,
    val productPrivAppDenyPermissions: Map<String, Set<String>>,
    val systemExtPrivAppDenyPermissions: Map<String, Set<String>>,
    val apexPrivAppDenyPermissions: Map<String, Map<String, Set<String>>>,
    val privAppDenyPermissions: Map<String, Set<String>>,
    val implicitToSourcePermissions: Map<String, Set<String>>,
    val permissionGroups: IndexedMap<String, PermissionGroupInfo>,
    val permissionTrees: IndexedMap<String, Permission>,
    val permissions: IndexedMap<String, Permission>
) : WritableState() {
    constructor() : this(
        IntSet(), IndexedMap(), IndexedMap(), IntMap(), IntMap(), IntMap(), IndexedMap(),
        IndexedListSet(), IndexedMap(), IndexedMap(), IndexedMap(), IndexedMap(), IndexedMap(),
        IndexedMap(), IndexedMap(), IndexedMap(), IndexedMap(), IndexedMap(), IndexedMap(),
        IndexedMap(), IndexedMap(), IndexedMap()
    )

    fun copy(): SystemState =
        SystemState(
            userIds.copy(),
            packageStates.copy { it },
            disabledSystemPackageStates.copy { it },
            appIds.copy { it.copy() },
            knownPackages.copy { it.copy() },
            deviceAndProfileOwners.copy { it },
            oemPermissions.copy { it.copy { it } },
            privilegedPermissionAllowlistSourcePackageNames.copy(),
            vendorPrivAppPermissions,
            productPrivAppPermissions,
            systemExtPrivAppPermissions,
            privAppPermissions,
            apexPrivAppPermissions,
            vendorPrivAppDenyPermissions,
            productPrivAppDenyPermissions,
            systemExtPrivAppDenyPermissions,
            apexPrivAppDenyPermissions,
            privAppDenyPermissions,
            implicitToSourcePermissions,
            permissionGroups.copy { it },
            permissionTrees.copy { it },
            permissions.copy { it }
        )
}

class UserState private constructor(
    // A map of (appId to a map of (permissionName to permissionFlags))
    val permissionFlags: IntMap<IndexedMap<String, Int>>,
    val uidAppOpModes: IntMap<IndexedMap<String, Int>>,
    val packageAppOpModes: IndexedMap<String, IndexedMap<String, Int>>
) : WritableState() {
    constructor() : this(IntMap(), IntMap(), IndexedMap())

    fun copy(): UserState = UserState(permissionFlags.copy { it.copy { it } },
        uidAppOpModes.copy { it.copy { it } }, packageAppOpModes.copy { it.copy { it } })
}

object WriteMode {
    const val NONE = 0
    const val SYNC = 1
    const val ASYNC = 2
}

abstract class WritableState {
    var writeMode: Int = WriteMode.NONE
        private set

    fun requestWrite(sync: Boolean = false) {
        if (sync) {
            writeMode = WriteMode.SYNC
        } else {
            if (writeMode != WriteMode.SYNC) {
                writeMode = WriteMode.ASYNC
            }
        }
    }
}

class GetStateScope(
    val state: AccessState
)

class MutateStateScope(
    val oldState: AccessState,
    val newState: AccessState
)
