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

import android.util.Slog
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.SystemConfig
import com.android.server.permission.access.appop.AppIdAppOpPolicy
import com.android.server.permission.access.appop.PackageAppOpPolicy
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.IndexedMap
import com.android.server.permission.access.permission.AppIdPermissionPolicy
import com.android.server.permission.access.permission.DevicePermissionPolicy
import com.android.server.permission.access.util.attributeInt
import com.android.server.permission.access.util.attributeInterned
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.getAttributeIntOrThrow
import com.android.server.permission.access.util.getAttributeValueOrThrow
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName
import com.android.server.pm.permission.PermissionAllowlist
import com.android.server.pm.pkg.PackageState

class AccessPolicy
private constructor(
    private val schemePolicies: IndexedMap<String, IndexedMap<String, SchemePolicy>>
) {
    @Suppress("UNCHECKED_CAST")
    constructor() :
        this(
            MutableIndexedMap<String, MutableIndexedMap<String, SchemePolicy>>().apply {
                fun addPolicy(policy: SchemePolicy) {
                    getOrPut(policy.subjectScheme) { MutableIndexedMap() }[policy.objectScheme] =
                        policy
                }
                addPolicy(AppIdPermissionPolicy())
                addPolicy(DevicePermissionPolicy())
                addPolicy(AppIdAppOpPolicy())
                addPolicy(PackageAppOpPolicy())
            } as IndexedMap<String, IndexedMap<String, SchemePolicy>>
        )

    fun getSchemePolicy(subjectScheme: String, objectScheme: String): SchemePolicy =
        checkNotNull(schemePolicies[subjectScheme]?.get(objectScheme)) {
            "Scheme policy for $subjectScheme and $objectScheme does not exist"
        }

    fun initialize(
        state: MutableAccessState,
        userIds: IntSet,
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        knownPackages: IntMap<Array<String>>,
        isLeanback: Boolean,
        configPermissions: Map<String, SystemConfig.PermissionEntry>,
        privilegedPermissionAllowlistPackages: IndexedListSet<String>,
        permissionAllowlist: PermissionAllowlist,
        implicitToSourcePermissions: IndexedMap<String, IndexedListSet<String>>
    ) {
        state.mutateExternalState().apply {
            mutateUserIds() += userIds
            setPackageStates(packageStates)
            setDisabledSystemPackageStates(disabledSystemPackageStates)
            packageStates.forEach { (_, packageState) ->
                mutateAppIdPackageNames()
                    .mutateOrPut(packageState.appId) { MutableIndexedListSet() }
                    .add(packageState.packageName)
            }
            setKnownPackages(knownPackages)
            setLeanback(isLeanback)
            setConfigPermissions(configPermissions)
            setPrivilegedPermissionAllowlistPackages(privilegedPermissionAllowlistPackages)
            setPermissionAllowlist(permissionAllowlist)
            setImplicitToSourcePermissions(implicitToSourcePermissions)
        }
        state.mutateUserStatesNoWrite().apply {
            userIds.forEachIndexed { _, userId -> this[userId] = MutableUserState() }
        }
    }

    fun GetStateScope.onStateMutated() {
        forEachSchemePolicy { with(it) { onStateMutated() } }
    }

    fun MutateStateScope.onInitialized() {
        forEachSchemePolicy { with(it) { onInitialized() } }
    }

    fun MutateStateScope.onUserAdded(userId: Int) {
        newState.mutateExternalState().mutateUserIds() += userId
        newState.mutateUserStatesNoWrite()[userId] = MutableUserState()
        forEachSchemePolicy { with(it) { onUserAdded(userId) } }
        newState.externalState.packageStates.forEach { (_, packageState) ->
            upgradePackageVersion(packageState, userId)
        }
    }

    fun MutateStateScope.onUserRemoved(userId: Int) {
        newState.mutateExternalState().mutateUserIds() -= userId
        newState.mutateUserStatesNoWrite() -= userId
        forEachSchemePolicy { with(it) { onUserRemoved(userId) } }
    }

    fun MutateStateScope.onStorageVolumeMounted(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        knownPackages: IntMap<Array<String>>,
        volumeUuid: String?,
        packageNames: List<String>,
        isSystemUpdated: Boolean
    ) {
        val addedAppIds = MutableIntSet()
        newState.mutateExternalState().apply {
            setPackageStates(packageStates)
            setDisabledSystemPackageStates(disabledSystemPackageStates)
            packageStates.forEach { (packageName, packageState) ->
                if (packageState.volumeUuid == volumeUuid) {
                    // The APK for a package on a mounted storage volume may still be unavailable
                    // due to APK being deleted, e.g. after an OTA.
                    check(
                        packageState.androidPackage == null || packageNames.contains(packageName)
                    ) {
                        "Package $packageName on storage volume $volumeUuid didn't receive" +
                            " onPackageAdded() before onStorageVolumeMounted()"
                    }
                    val appId = packageState.appId
                    mutateAppIdPackageNames().mutateOrPut(appId) {
                        addedAppIds += appId
                        MutableIndexedListSet()
                    } += packageName
                }
            }
            setKnownPackages(knownPackages)
        }
        addedAppIds.forEachIndexed { _, appId ->
            forEachSchemePolicy { with(it) { onAppIdAdded(appId) } }
        }
        forEachSchemePolicy {
            with(it) { onStorageVolumeMounted(volumeUuid, packageNames, isSystemUpdated) }
        }
        packageStates.forEach { (_, packageState) ->
            if (packageState.volumeUuid == volumeUuid) {
                newState.userStates.forEachIndexed { _, userId, _ ->
                    upgradePackageVersion(packageState, userId)
                }
            }
        }
    }

    fun MutateStateScope.onPackageAdded(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        knownPackages: IntMap<Array<String>>,
        packageName: String
    ) {
        val packageState = packageStates[packageName]
        checkNotNull(packageState) {
            "Added package $packageName isn't found in packageStates in onPackageAdded()"
        }
        val appId = packageState.appId
        var isAppIdAdded = false
        newState.mutateExternalState().apply {
            setPackageStates(packageStates)
            setDisabledSystemPackageStates(disabledSystemPackageStates)
            mutateAppIdPackageNames().mutateOrPut(appId) {
                isAppIdAdded = true
                MutableIndexedListSet()
            } += packageName
            setKnownPackages(knownPackages)
        }
        if (isAppIdAdded) {
            forEachSchemePolicy { with(it) { onAppIdAdded(appId) } }
        }
        forEachSchemePolicy { with(it) { onPackageAdded(packageState) } }
        newState.userStates.forEachIndexed { _, userId, _ ->
            upgradePackageVersion(packageState, userId)
        }
    }

    fun MutateStateScope.onPackageRemoved(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        knownPackages: IntMap<Array<String>>,
        packageName: String,
        appId: Int
    ) {
        check(packageName !in packageStates) {
            "Removed package $packageName is still in packageStates in onPackageRemoved()"
        }
        var isAppIdRemoved = false
        newState.mutateExternalState().apply {
            setPackageStates(packageStates)
            setDisabledSystemPackageStates(disabledSystemPackageStates)
            mutateAppIdPackageNames().mutate(appId)?.apply {
                this -= packageName
                if (isEmpty()) {
                    mutateAppIdPackageNames() -= appId
                    isAppIdRemoved = true
                }
            }
            setKnownPackages(knownPackages)
        }
        forEachSchemePolicy { with(it) { onPackageRemoved(packageName, appId) } }
        if (isAppIdRemoved) {
            forEachSchemePolicy { with(it) { onAppIdRemoved(appId) } }
        }
        newState.userStates.forEachIndexed { userStateIndex, _, userState ->
            if (packageName in userState.packageVersions) {
                newState.mutateUserStateAt(userStateIndex).mutatePackageVersions() -= packageName
            }
        }
    }

    fun MutateStateScope.onPackageInstalled(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        knownPackages: IntMap<Array<String>>,
        packageName: String,
        userId: Int
    ) {
        newState.mutateExternalState().apply {
            setPackageStates(packageStates)
            setDisabledSystemPackageStates(disabledSystemPackageStates)
            setKnownPackages(knownPackages)
        }
        val packageState = packageStates[packageName]
        checkNotNull(packageState) {
            "Installed package $packageName isn't found in packageStates in onPackageInstalled()"
        }
        forEachSchemePolicy { with(it) { onPackageInstalled(packageState, userId) } }
    }

    fun MutateStateScope.onPackageUninstalled(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        knownPackages: IntMap<Array<String>>,
        packageName: String,
        appId: Int,
        userId: Int
    ) {
        newState.mutateExternalState().apply {
            setPackageStates(packageStates)
            setDisabledSystemPackageStates(disabledSystemPackageStates)
            setKnownPackages(knownPackages)
        }
        forEachSchemePolicy { with(it) { onPackageUninstalled(packageName, appId, userId) } }
    }

    fun MutateStateScope.onSystemReady() {
        newState.mutateExternalState().setSystemReady(true)
        forEachSchemePolicy { with(it) { onSystemReady() } }
    }

    fun migrateSystemState(state: MutableAccessState) {
        forEachSchemePolicy { with(it) { migrateSystemState(state) } }
    }

    fun migrateUserState(state: MutableAccessState, userId: Int) {
        forEachSchemePolicy { with(it) { migrateUserState(state, userId) } }
    }

    private fun MutateStateScope.upgradePackageVersion(packageState: PackageState, userId: Int) {
        if (packageState.androidPackage == null) {
            return
        }

        val packageName = packageState.packageName
        // The version would be latest when the package is new to the system, e.g. newly
        // installed, first boot, or system apps added via OTA.
        val version = newState.userStates[userId]!!.packageVersions[packageName]
        when {
            version == null ->
                newState.mutateUserState(userId)!!.mutatePackageVersions()[packageName] =
                    VERSION_LATEST
            version < VERSION_LATEST -> {
                forEachSchemePolicy {
                    with(it) { upgradePackageState(packageState, userId, version) }
                }
                newState.mutateUserState(userId)!!.mutatePackageVersions()[packageName] =
                    VERSION_LATEST
            }
            version == VERSION_LATEST -> {}
            else ->
                Slog.w(
                    LOG_TAG,
                    "Unexpected version $version for package $packageName," +
                        "latest version is $VERSION_LATEST"
                )
        }
    }

    fun BinaryXmlPullParser.parseSystemState(state: MutableAccessState) {
        forEachTag {
            when (tagName) {
                TAG_ACCESS -> {
                    forEachTag { forEachSchemePolicy { with(it) { parseSystemState(state) } } }
                }
                else -> Slog.w(LOG_TAG, "Ignoring unknown tag $tagName when parsing system state")
            }
        }
    }

    fun BinaryXmlSerializer.serializeSystemState(state: AccessState) {
        tag(TAG_ACCESS) { forEachSchemePolicy { with(it) { serializeSystemState(state) } } }
    }

    fun BinaryXmlPullParser.parseUserState(state: MutableAccessState, userId: Int) {
        forEachTag {
            when (tagName) {
                TAG_ACCESS -> {
                    forEachTag {
                        when (tagName) {
                            TAG_PACKAGE_VERSIONS -> parsePackageVersions(state, userId)
                            TAG_DEFAULT_PERMISSION_GRANT ->
                                parseDefaultPermissionGrant(state, userId)
                            else -> {
                                forEachSchemePolicy { with(it) { parseUserState(state, userId) } }
                            }
                        }
                    }
                }
                else -> {
                    Slog.w(
                        LOG_TAG,
                        "Ignoring unknown tag $tagName when parsing user state for user $userId"
                    )
                }
            }
        }
    }

    private fun BinaryXmlPullParser.parsePackageVersions(state: MutableAccessState, userId: Int) {
        val userState = state.mutateUserState(userId, WriteMode.NONE)!!
        val packageVersions = userState.mutatePackageVersions()
        forEachTag {
            when (tagName) {
                TAG_PACKAGE -> parsePackageVersion(packageVersions)
                else -> Slog.w(LOG_TAG, "Ignoring unknown tag $name when parsing package versions")
            }
        }
        packageVersions.forEachReversedIndexed { packageVersionIndex, packageName, _ ->
            if (packageName !in state.externalState.packageStates) {
                Slog.w(LOG_TAG, "Dropping unknown $packageName when parsing package versions")
                packageVersions.removeAt(packageVersionIndex)
                userState.requestWriteMode(WriteMode.ASYNCHRONOUS)
            }
        }
    }

    private fun BinaryXmlPullParser.parsePackageVersion(
        packageVersions: MutableIndexedMap<String, Int>
    ) {
        val packageName = getAttributeValueOrThrow(ATTR_NAME).intern()
        val version = getAttributeIntOrThrow(ATTR_VERSION)
        packageVersions[packageName] = version
    }

    private fun BinaryXmlPullParser.parseDefaultPermissionGrant(
        state: MutableAccessState,
        userId: Int
    ) {
        val userState = state.mutateUserState(userId, WriteMode.NONE)!!
        val fingerprint = getAttributeValueOrThrow(ATTR_FINGERPRINT).intern()
        userState.setDefaultPermissionGrantFingerprint(fingerprint)
    }

    fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {
        tag(TAG_ACCESS) {
            serializePackageVersions(state.userStates[userId]!!.packageVersions)
            serializeDefaultPermissionGrantFingerprint(
                state.userStates[userId]!!.defaultPermissionGrantFingerprint
            )
            forEachSchemePolicy { with(it) { serializeUserState(state, userId) } }
        }
    }

    private fun BinaryXmlSerializer.serializePackageVersions(
        packageVersions: IndexedMap<String, Int>
    ) {
        tag(TAG_PACKAGE_VERSIONS) {
            packageVersions.forEachIndexed { _, packageName, version ->
                tag(TAG_PACKAGE) {
                    attributeInterned(ATTR_NAME, packageName)
                    attributeInt(ATTR_VERSION, version)
                }
            }
        }
    }

    private fun BinaryXmlSerializer.serializeDefaultPermissionGrantFingerprint(
        fingerprint: String?
    ) {
        if (fingerprint != null) {
            tag(TAG_DEFAULT_PERMISSION_GRANT) { attributeInterned(ATTR_FINGERPRINT, fingerprint) }
        }
    }

    private fun getSchemePolicy(subject: AccessUri, `object`: AccessUri): SchemePolicy =
        getSchemePolicy(subject.scheme, `object`.scheme)

    private inline fun forEachSchemePolicy(action: (SchemePolicy) -> Unit) {
        schemePolicies.forEachIndexed { _, _, objectSchemePolicies ->
            objectSchemePolicies.forEachIndexed { _, _, schemePolicy -> action(schemePolicy) }
        }
    }

    companion object {
        private val LOG_TAG = AccessPolicy::class.java.simpleName

        internal const val VERSION_LATEST = 15

        private const val TAG_ACCESS = "access"
        private const val TAG_DEFAULT_PERMISSION_GRANT = "default-permission-grant"
        private const val TAG_PACKAGE_VERSIONS = "package-versions"
        private const val TAG_PACKAGE = "package"

        private const val ATTR_FINGERPRINT = "fingerprint"
        private const val ATTR_NAME = "name"
        private const val ATTR_VERSION = "version"
    }
}

abstract class SchemePolicy {
    abstract val subjectScheme: String

    abstract val objectScheme: String

    open fun GetStateScope.onStateMutated() {}

    open fun MutateStateScope.onInitialized() {}

    open fun MutateStateScope.onUserAdded(userId: Int) {}

    open fun MutateStateScope.onUserRemoved(userId: Int) {}

    open fun MutateStateScope.onAppIdAdded(appId: Int) {}

    open fun MutateStateScope.onAppIdRemoved(appId: Int) {}

    open fun MutateStateScope.onStorageVolumeMounted(
        volumeUuid: String?,
        packageNames: List<String>,
        isSystemUpdated: Boolean,
    ) {}

    open fun MutateStateScope.onPackageAdded(packageState: PackageState) {}

    open fun MutateStateScope.onPackageRemoved(packageName: String, appId: Int) {}

    open fun MutateStateScope.onPackageInstalled(packageState: PackageState, userId: Int) {}

    open fun MutateStateScope.onPackageUninstalled(packageName: String, appId: Int, userId: Int) {}

    open fun MutateStateScope.onSystemReady() {}

    open fun migrateSystemState(state: MutableAccessState) {}

    open fun migrateUserState(state: MutableAccessState, userId: Int) {}

    open fun MutateStateScope.upgradePackageState(
        packageState: PackageState,
        userId: Int,
        version: Int
    ) {}

    open fun BinaryXmlPullParser.parseSystemState(state: MutableAccessState) {}

    open fun BinaryXmlSerializer.serializeSystemState(state: AccessState) {}

    open fun BinaryXmlPullParser.parseUserState(state: MutableAccessState, userId: Int) {}

    open fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {}
}
