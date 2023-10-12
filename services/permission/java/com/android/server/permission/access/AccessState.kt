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
import com.android.server.SystemConfig
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.permission.Permission
import com.android.server.pm.permission.PermissionAllowlist
import com.android.server.pm.pkg.PackageState

private typealias ExternalStateReference = MutableReference<ExternalState, MutableExternalState>

private typealias SystemStateReference = MutableReference<SystemState, MutableSystemState>

typealias UserStates = IntReferenceMap<UserState, MutableUserState>

typealias MutableUserStates = MutableIntReferenceMap<UserState, MutableUserState>

private typealias UserStatesReference = MutableReference<UserStates, MutableUserStates>

sealed class AccessState(
    internal val externalStateReference: ExternalStateReference,
    internal val systemStateReference: SystemStateReference,
    internal val userStatesReference: UserStatesReference
) : Immutable<MutableAccessState> {
    val externalState: ExternalState
        get() = externalStateReference.get()

    val systemState: SystemState
        get() = systemStateReference.get()

    val userStates: UserStates
        get() = userStatesReference.get()

    override fun toMutable(): MutableAccessState = MutableAccessState(this)
}

class MutableAccessState
private constructor(
    externalStateReference: ExternalStateReference,
    systemStateReference: SystemStateReference,
    userStatesReference: UserStatesReference
) : AccessState(externalStateReference, systemStateReference, userStatesReference) {
    constructor() :
        this(
            ExternalStateReference(MutableExternalState()),
            SystemStateReference(MutableSystemState()),
            UserStatesReference(MutableUserStates())
        )

    internal constructor(
        accessState: AccessState
    ) : this(
        accessState.externalStateReference.toImmutable(),
        accessState.systemStateReference.toImmutable(),
        accessState.userStatesReference.toImmutable()
    )

    fun mutateExternalState(): MutableExternalState = externalStateReference.mutate()

    fun mutateSystemState(writeMode: Int = WriteMode.ASYNCHRONOUS): MutableSystemState =
        systemStateReference.mutate().apply { requestWriteMode(writeMode) }

    fun mutateUserStatesNoWrite(): MutableUserStates = userStatesReference.mutate()

    fun mutateUserState(userId: Int, writeMode: Int = WriteMode.ASYNCHRONOUS): MutableUserState? =
        mutateUserStatesNoWrite().mutate(userId)?.apply { requestWriteMode(writeMode) }

    fun mutateUserStateAt(index: Int, writeMode: Int = WriteMode.ASYNCHRONOUS): MutableUserState =
        mutateUserStatesNoWrite().mutateAt(index).apply { requestWriteMode(writeMode) }
}

private typealias UserIdsReference = MutableReference<IntSet, MutableIntSet>

typealias AppIdPackageNames = IntReferenceMap<IndexedListSet<String>, MutableIndexedListSet<String>>

typealias MutableAppIdPackageNames =
    MutableIntReferenceMap<IndexedListSet<String>, MutableIndexedListSet<String>>

private typealias AppIdPackageNamesReference =
    MutableReference<AppIdPackageNames, MutableAppIdPackageNames>

sealed class ExternalState(
    val userIdsReference: UserIdsReference,
    packageStates: Map<String, PackageState>,
    disabledSystemPackageStates: Map<String, PackageState>,
    val appIdPackageNamesReference: AppIdPackageNamesReference,
    knownPackages: IntMap<Array<String>>,
    isLeanback: Boolean,
    configPermissions: Map<String, SystemConfig.PermissionEntry>,
    privilegedPermissionAllowlistPackages: IndexedListSet<String>,
    permissionAllowlist: PermissionAllowlist,
    implicitToSourcePermissions: IndexedMap<String, IndexedListSet<String>>,
    isSystemReady: Boolean
) : Immutable<MutableExternalState> {
    val userIds: IntSet
        get() = userIdsReference.get()

    var packageStates: Map<String, PackageState> = packageStates
        protected set

    var disabledSystemPackageStates: Map<String, PackageState> = disabledSystemPackageStates
        protected set

    val appIdPackageNames: AppIdPackageNames
        get() = appIdPackageNamesReference.get()

    var knownPackages: IntMap<Array<String>> = knownPackages
        protected set

    var isLeanback: Boolean = isLeanback
        protected set

    var configPermissions: Map<String, SystemConfig.PermissionEntry> = configPermissions
        protected set

    var privilegedPermissionAllowlistPackages: IndexedListSet<String> =
        privilegedPermissionAllowlistPackages
        protected set

    var permissionAllowlist: PermissionAllowlist = permissionAllowlist
        protected set

    var implicitToSourcePermissions: IndexedMap<String, IndexedListSet<String>> =
        implicitToSourcePermissions
        protected set

    var isSystemReady: Boolean = isSystemReady
        protected set

    override fun toMutable(): MutableExternalState = MutableExternalState(this)
}

class MutableExternalState
private constructor(
    userIdsReference: UserIdsReference,
    packageStates: Map<String, PackageState>,
    disabledSystemPackageStates: Map<String, PackageState>,
    appIdPackageNamesReference: AppIdPackageNamesReference,
    knownPackages: IntMap<Array<String>>,
    isLeanback: Boolean,
    configPermissions: Map<String, SystemConfig.PermissionEntry>,
    privilegedPermissionAllowlistPackages: IndexedListSet<String>,
    permissionAllowlist: PermissionAllowlist,
    implicitToSourcePermissions: IndexedMap<String, IndexedListSet<String>>,
    isSystemReady: Boolean
) :
    ExternalState(
        userIdsReference,
        packageStates,
        disabledSystemPackageStates,
        appIdPackageNamesReference,
        knownPackages,
        isLeanback,
        configPermissions,
        privilegedPermissionAllowlistPackages,
        permissionAllowlist,
        implicitToSourcePermissions,
        isSystemReady
    ) {
    constructor() :
        this(
            UserIdsReference(MutableIntSet()),
            emptyMap(),
            emptyMap(),
            AppIdPackageNamesReference(MutableAppIdPackageNames()),
            MutableIntMap(),
            false,
            emptyMap(),
            MutableIndexedListSet(),
            PermissionAllowlist(),
            MutableIndexedMap(),
            false
        )

    internal constructor(
        externalState: ExternalState
    ) : this(
        externalState.userIdsReference.toImmutable(),
        externalState.packageStates,
        externalState.disabledSystemPackageStates,
        externalState.appIdPackageNamesReference.toImmutable(),
        externalState.knownPackages,
        externalState.isLeanback,
        externalState.configPermissions,
        externalState.privilegedPermissionAllowlistPackages,
        externalState.permissionAllowlist,
        externalState.implicitToSourcePermissions,
        externalState.isSystemReady
    )

    fun mutateUserIds(): MutableIntSet = userIdsReference.mutate()

    @JvmName("setPackageStatesPublic")
    fun setPackageStates(packageStates: Map<String, PackageState>) {
        this.packageStates = packageStates
    }

    @JvmName("setDisabledSystemPackageStatesPublic")
    fun setDisabledSystemPackageStates(disabledSystemPackageStates: Map<String, PackageState>) {
        this.disabledSystemPackageStates = disabledSystemPackageStates
    }

    fun mutateAppIdPackageNames(): MutableAppIdPackageNames = appIdPackageNamesReference.mutate()

    @JvmName("setKnownPackagesPublic")
    fun setKnownPackages(knownPackages: IntMap<Array<String>>) {
        this.knownPackages = knownPackages
    }

    @JvmName("setLeanbackPublic")
    fun setLeanback(isLeanback: Boolean) {
        this.isLeanback = isLeanback
    }

    @JvmName("setConfigPermissionsPublic")
    fun setConfigPermissions(configPermissions: Map<String, SystemConfig.PermissionEntry>) {
        this.configPermissions = configPermissions
    }

    @JvmName("setPrivilegedPermissionAllowlistPackagesPublic")
    fun setPrivilegedPermissionAllowlistPackages(
        privilegedPermissionAllowlistPackages: IndexedListSet<String>
    ) {
        this.privilegedPermissionAllowlistPackages = privilegedPermissionAllowlistPackages
    }

    @JvmName("setPermissionAllowlistPublic")
    fun setPermissionAllowlist(permissionAllowlist: PermissionAllowlist) {
        this.permissionAllowlist = permissionAllowlist
    }

    @JvmName("setImplicitToSourcePermissionsPublic")
    fun setImplicitToSourcePermissions(
        implicitToSourcePermissions: IndexedMap<String, IndexedListSet<String>>
    ) {
        this.implicitToSourcePermissions = implicitToSourcePermissions
    }

    @JvmName("setSystemReadyPublic")
    fun setSystemReady(isSystemReady: Boolean) {
        this.isSystemReady = isSystemReady
    }
}

private typealias PermissionGroupsReference =
    MutableReference<
        IndexedMap<String, PermissionGroupInfo>, MutableIndexedMap<String, PermissionGroupInfo>
    >

private typealias PermissionTreesReference =
    MutableReference<IndexedMap<String, Permission>, MutableIndexedMap<String, Permission>>

private typealias PermissionsReference =
    MutableReference<IndexedMap<String, Permission>, MutableIndexedMap<String, Permission>>

sealed class SystemState(
    val permissionGroupsReference: PermissionGroupsReference,
    val permissionTreesReference: PermissionTreesReference,
    val permissionsReference: PermissionsReference,
    writeMode: Int
) : WritableState, Immutable<MutableSystemState> {
    val permissionGroups: IndexedMap<String, PermissionGroupInfo>
        get() = permissionGroupsReference.get()

    val permissionTrees: IndexedMap<String, Permission>
        get() = permissionTreesReference.get()

    val permissions: IndexedMap<String, Permission>
        get() = permissionsReference.get()

    override var writeMode: Int = writeMode
        protected set

    override fun toMutable(): MutableSystemState = MutableSystemState(this)
}

class MutableSystemState
private constructor(
    permissionGroupsReference: PermissionGroupsReference,
    permissionTreesReference: PermissionTreesReference,
    permissionsReference: PermissionsReference,
    writeMode: Int
) :
    SystemState(
        permissionGroupsReference,
        permissionTreesReference,
        permissionsReference,
        writeMode
    ),
    MutableWritableState {
    constructor() :
        this(
            PermissionGroupsReference(MutableIndexedMap()),
            PermissionTreesReference(MutableIndexedMap()),
            PermissionsReference(MutableIndexedMap()),
            WriteMode.NONE
        )

    internal constructor(
        systemState: SystemState
    ) : this(
        systemState.permissionGroupsReference.toImmutable(),
        systemState.permissionTreesReference.toImmutable(),
        systemState.permissionsReference.toImmutable(),
        WriteMode.NONE
    )

    fun mutatePermissionGroups(): MutableIndexedMap<String, PermissionGroupInfo> =
        permissionGroupsReference.mutate()

    fun mutatePermissionTrees(): MutableIndexedMap<String, Permission> =
        permissionTreesReference.mutate()

    fun mutatePermissions(): MutableIndexedMap<String, Permission> = permissionsReference.mutate()

    override fun requestWriteMode(writeMode: Int) {
        this.writeMode = maxOf(this.writeMode, writeMode)
    }
}

private typealias PackageVersionsReference =
    MutableReference<IndexedMap<String, Int>, MutableIndexedMap<String, Int>>

typealias AppIdPermissionFlags =
    IntReferenceMap<IndexedMap<String, Int>, MutableIndexedMap<String, Int>>

typealias MutableAppIdPermissionFlags =
    MutableIntReferenceMap<IndexedMap<String, Int>, MutableIndexedMap<String, Int>>

private typealias AppIdPermissionFlagsReference =
    MutableReference<AppIdPermissionFlags, MutableAppIdPermissionFlags>

typealias DevicePermissionFlags =
    IndexedReferenceMap<String, IndexedMap<String, Int>, MutableIndexedMap<String, Int>>

typealias MutableDevicePermissionFlags =
    MutableIndexedReferenceMap<String, IndexedMap<String, Int>, MutableIndexedMap<String, Int>>

typealias AppIdDevicePermissionFlags =
    IntReferenceMap<DevicePermissionFlags, MutableDevicePermissionFlags>

typealias MutableAppIdDevicePermissionFlags =
    MutableIntReferenceMap<DevicePermissionFlags, MutableDevicePermissionFlags>

private typealias AppIdDevicePermissionFlagsReference =
    MutableReference<AppIdDevicePermissionFlags, MutableAppIdDevicePermissionFlags>

typealias AppIdAppOpModes = IntReferenceMap<IndexedMap<String, Int>, MutableIndexedMap<String, Int>>

typealias MutableAppIdAppOpModes =
    MutableIntReferenceMap<IndexedMap<String, Int>, MutableIndexedMap<String, Int>>

private typealias AppIdAppOpModesReference =
    MutableReference<AppIdAppOpModes, MutableAppIdAppOpModes>

typealias PackageAppOpModes =
    IndexedReferenceMap<String, IndexedMap<String, Int>, MutableIndexedMap<String, Int>>

typealias MutablePackageAppOpModes =
    MutableIndexedReferenceMap<String, IndexedMap<String, Int>, MutableIndexedMap<String, Int>>

private typealias PackageAppOpModesReference =
    MutableReference<PackageAppOpModes, MutablePackageAppOpModes>

sealed class UserState(
    internal val packageVersionsReference: PackageVersionsReference,
    internal val appIdPermissionFlagsReference: AppIdPermissionFlagsReference,
    internal val appIdDevicePermissionFlagsReference: AppIdDevicePermissionFlagsReference,
    internal val appIdAppOpModesReference: AppIdAppOpModesReference,
    internal val packageAppOpModesReference: PackageAppOpModesReference,
    defaultPermissionGrantFingerprint: String?,
    writeMode: Int
) : WritableState, Immutable<MutableUserState> {
    val packageVersions: IndexedMap<String, Int>
        get() = packageVersionsReference.get()

    val appIdPermissionFlags: AppIdPermissionFlags
        get() = appIdPermissionFlagsReference.get()

    val appIdDevicePermissionFlags: AppIdDevicePermissionFlags
        get() = appIdDevicePermissionFlagsReference.get()

    val appIdAppOpModes: AppIdAppOpModes
        get() = appIdAppOpModesReference.get()

    val packageAppOpModes: PackageAppOpModes
        get() = packageAppOpModesReference.get()

    var defaultPermissionGrantFingerprint: String? = defaultPermissionGrantFingerprint
        protected set

    override var writeMode: Int = writeMode
        protected set

    override fun toMutable(): MutableUserState = MutableUserState(this)
}

class MutableUserState
private constructor(
    packageVersionsReference: PackageVersionsReference,
    appIdPermissionFlagsReference: AppIdPermissionFlagsReference,
    appIdDevicePermissionFlagsReference: AppIdDevicePermissionFlagsReference,
    appIdAppOpModesReference: AppIdAppOpModesReference,
    packageAppOpModesReference: PackageAppOpModesReference,
    defaultPermissionGrantFingerprint: String?,
    writeMode: Int
) :
    UserState(
        packageVersionsReference,
        appIdPermissionFlagsReference,
        appIdDevicePermissionFlagsReference,
        appIdAppOpModesReference,
        packageAppOpModesReference,
        defaultPermissionGrantFingerprint,
        writeMode
    ),
    MutableWritableState {
    constructor() :
        this(
            PackageVersionsReference(MutableIndexedMap<String, Int>()),
            AppIdPermissionFlagsReference(MutableAppIdPermissionFlags()),
            AppIdDevicePermissionFlagsReference(MutableAppIdDevicePermissionFlags()),
            AppIdAppOpModesReference(MutableAppIdAppOpModes()),
            PackageAppOpModesReference(MutablePackageAppOpModes()),
            null,
            WriteMode.NONE
        )

    internal constructor(
        userState: UserState
    ) : this(
        userState.packageVersionsReference.toImmutable(),
        userState.appIdPermissionFlagsReference.toImmutable(),
        userState.appIdDevicePermissionFlagsReference.toImmutable(),
        userState.appIdAppOpModesReference.toImmutable(),
        userState.packageAppOpModesReference.toImmutable(),
        userState.defaultPermissionGrantFingerprint,
        WriteMode.NONE
    )

    fun mutatePackageVersions(): MutableIndexedMap<String, Int> = packageVersionsReference.mutate()

    fun mutateAppIdPermissionFlags(): MutableAppIdPermissionFlags =
        appIdPermissionFlagsReference.mutate()

    fun mutateAppIdDevicePermissionFlags(): MutableAppIdDevicePermissionFlags =
        appIdDevicePermissionFlagsReference.mutate()

    fun mutateAppIdAppOpModes(): MutableAppIdAppOpModes = appIdAppOpModesReference.mutate()

    fun mutatePackageAppOpModes(): MutablePackageAppOpModes = packageAppOpModesReference.mutate()

    @JvmName("setDefaultPermissionGrantFingerprintPublic")
    fun setDefaultPermissionGrantFingerprint(defaultPermissionGrantFingerprint: String?) {
        this.defaultPermissionGrantFingerprint = defaultPermissionGrantFingerprint
    }

    override fun requestWriteMode(writeMode: Int) {
        this.writeMode = maxOf(this.writeMode, writeMode)
    }
}

object WriteMode {
    const val NONE = 0
    const val ASYNCHRONOUS = 1
    const val SYNCHRONOUS = 2
}

interface WritableState {
    val writeMode: Int
}

interface MutableWritableState : WritableState {
    fun requestWriteMode(writeMode: Int)
}

open class GetStateScope(val state: AccessState)

class MutateStateScope(val oldState: AccessState, val newState: MutableAccessState) :
    GetStateScope(newState)
