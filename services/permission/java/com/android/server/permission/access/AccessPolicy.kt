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

import android.util.Log
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.SystemConfig
import com.android.server.permission.access.appop.AppIdAppOpPolicy
import com.android.server.permission.access.appop.PackageAppOpPolicy
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.permission.AppIdPermissionPolicy
import com.android.server.permission.access.util.attributeInt
import com.android.server.permission.access.util.attributeInterned
import com.android.server.permission.access.util.forEachTag
import com.android.server.permission.access.util.getAttributeIntOrThrow
import com.android.server.permission.access.util.getAttributeValueOrThrow
import com.android.server.permission.access.util.tag
import com.android.server.permission.access.util.tagName
import com.android.server.pm.permission.PermissionAllowlist
import com.android.server.pm.pkg.PackageState

class AccessPolicy private constructor(
    private val schemePolicies: IndexedMap<String, IndexedMap<String, SchemePolicy>>
) {
    constructor() : this(
        IndexedMap<String, IndexedMap<String, SchemePolicy>>().apply {
            fun addPolicy(policy: SchemePolicy) =
                getOrPut(policy.subjectScheme) { IndexedMap() }.put(policy.objectScheme, policy)
            addPolicy(AppIdPermissionPolicy())
            addPolicy(AppIdAppOpPolicy())
            addPolicy(PackageAppOpPolicy())
        }
    )

    fun getSchemePolicy(subjectScheme: String, objectScheme: String): SchemePolicy =
        checkNotNull(schemePolicies[subjectScheme]?.get(objectScheme)) {
            "Scheme policy for $subjectScheme and $objectScheme does not exist"
        }

    fun GetStateScope.getDecision(subject: AccessUri, `object`: AccessUri): Int =
        with(getSchemePolicy(subject, `object`)) { getDecision(subject, `object`) }

    fun MutateStateScope.setDecision(subject: AccessUri, `object`: AccessUri, decision: Int) {
        with(getSchemePolicy(subject, `object`)) { setDecision(subject, `object`, decision) }
    }

    fun initialize(
        state: AccessState,
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
        state.systemState.apply {
            this.userIds += userIds
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
            packageStates.forEach { (_, packageState) ->
                appIds.getOrPut(packageState.appId) { IndexedListSet() }
                    .add(packageState.packageName)
            }
            this.knownPackages = knownPackages
            this.isLeanback = isLeanback
            this.configPermissions = configPermissions
            this.privilegedPermissionAllowlistPackages = privilegedPermissionAllowlistPackages
            this.permissionAllowlist = permissionAllowlist
            this.implicitToSourcePermissions = implicitToSourcePermissions
        }
        state.userStates.apply {
            userIds.forEachIndexed { _, userId ->
                this[userId] = UserState()
            }
        }
    }

    fun GetStateScope.onStateMutated() {
        forEachSchemePolicy {
            with(it) { onStateMutated() }
        }
    }

    fun MutateStateScope.onInitialized() {
        forEachSchemePolicy {
            with(it) { onInitialized() }
        }
    }

    fun MutateStateScope.onUserAdded(userId: Int) {
        newState.systemState.userIds += userId
        newState.userStates[userId] = UserState()
        forEachSchemePolicy {
            with(it) { onUserAdded(userId) }
        }
        newState.systemState.packageStates.forEach { (_, packageState) ->
            upgradePackageVersion(packageState, userId)
        }
    }

    fun MutateStateScope.onUserRemoved(userId: Int) {
        newState.systemState.userIds -= userId
        newState.userStates -= userId
        forEachSchemePolicy {
            with(it) { onUserRemoved(userId) }
        }
    }

    fun MutateStateScope.onStorageVolumeMounted(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        knownPackages: IntMap<Array<String>>,
        volumeUuid: String?,
        isSystemUpdated: Boolean
    ) {
        val addedAppIds = IntSet()
        newState.systemState.apply {
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
            packageStates.forEach { (packageName, packageState) ->
                if (packageState.volumeUuid == volumeUuid) {
                    val appId = packageState.appId
                    appIds.getOrPut(appId) {
                        addedAppIds += appId
                        IndexedListSet()
                    } += packageName
                }
            }
            this.knownPackages = knownPackages
        }
        addedAppIds.forEachIndexed { _, appId ->
            forEachSchemePolicy {
                with(it) { onAppIdAdded(appId) }
            }
        }
        forEachSchemePolicy {
            with(it) { onStorageVolumeMounted(volumeUuid, isSystemUpdated) }
        }
        packageStates.forEach { (_, packageState) ->
            if (packageState.volumeUuid == volumeUuid) {
                newState.systemState.userIds.forEachIndexed { _, userId ->
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
        // TODO(zhanghai): STOPSHIP: Remove check before feature enable.
        checkNotNull(packageState) {
            "Added package $packageName isn't found in packageStates in onPackageAdded()"
        }
        val appId = packageState.appId
        var isAppIdAdded = false
        newState.systemState.apply {
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
            appIds.getOrPut(appId) {
                isAppIdAdded = true
                IndexedListSet()
            } += packageName
            this.knownPackages = knownPackages
        }
        if (isAppIdAdded) {
            forEachSchemePolicy {
                with(it) { onAppIdAdded(appId) }
            }
        }
        forEachSchemePolicy {
            with(it) { onPackageAdded(packageState) }
        }
        newState.systemState.userIds.forEachIndexed { _, userId ->
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
        // TODO(zhanghai): STOPSHIP: Remove check before feature enable.
        check(packageName !in packageStates) {
            "Removed package $packageName is still in packageStates in onPackageRemoved()"
        }
        var isAppIdRemoved = false
        newState.systemState.apply {
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
            appIds[appId]?.apply {
                this -= packageName
                if (isEmpty()) {
                    appIds -= appId
                    isAppIdRemoved = true
                }
            }
            this.knownPackages = knownPackages
        }
        forEachSchemePolicy {
            with(it) { onPackageRemoved(packageName, appId) }
        }
        if (isAppIdRemoved) {
            forEachSchemePolicy {
                with(it) { onAppIdRemoved(appId) }
            }
        }
        newState.userStates.forEachIndexed { _, _, userState ->
            userState.packageVersions -= packageName
            userState.requestWrite()
        }
    }

    fun MutateStateScope.onPackageInstalled(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        knownPackages: IntMap<Array<String>>,
        packageName: String,
        userId: Int
    ) {
        newState.systemState.apply {
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
            this.knownPackages = knownPackages
        }
        val packageState = packageStates[packageName]
        // TODO(zhanghai): STOPSHIP: Remove check before feature enable.
        checkNotNull(packageState) {
            "Installed package $packageName isn't found in packageStates in onPackageInstalled()"
        }
        forEachSchemePolicy {
            with(it) { onPackageInstalled(packageState, userId) }
        }
    }

    fun MutateStateScope.onPackageUninstalled(
        packageStates: Map<String, PackageState>,
        disabledSystemPackageStates: Map<String, PackageState>,
        knownPackages: IntMap<Array<String>>,
        packageName: String,
        appId: Int,
        userId: Int
    ) {
        newState.systemState.apply {
            this.packageStates = packageStates
            this.disabledSystemPackageStates = disabledSystemPackageStates
            this.knownPackages = knownPackages
        }
        forEachSchemePolicy {
            with(it) { onPackageUninstalled(packageName, appId, userId) }
        }
    }

    fun MutateStateScope.onSystemReady() {
        newState.systemState.isSystemReady = true
        forEachSchemePolicy {
            with(it) { onSystemReady() }
        }
    }

    fun migrateSystemState(state: AccessState) {
        forEachSchemePolicy {
            with(it) { migrateSystemState(state) }
        }
    }

    fun migrateUserState(state: AccessState, userId: Int) {
        forEachSchemePolicy {
            with(it) { migrateUserState(state, userId) }
        }
    }

    private fun MutateStateScope.upgradePackageVersion(packageState: PackageState, userId: Int) {
        if (packageState.androidPackage == null) {
            return
        }

        val packageName = packageState.packageName
        // The version would be latest when the package is new to the system, e.g. newly
        // installed, first boot, or system apps added via OTA.
        val version = newState.userStates[userId].packageVersions[packageName]
        when {
            version == null -> {
                newState.userStates[userId].apply {
                    packageVersions[packageName] = VERSION_LATEST
                    requestWrite()
                }
            }
            version < VERSION_LATEST -> {
                forEachSchemePolicy {
                    with(it) { upgradePackageState(packageState, userId, version) }
                }
                newState.userStates[userId].apply {
                    packageVersions[packageName] = VERSION_LATEST
                    requestWrite()
                }
            }
            version == VERSION_LATEST -> {}
            else -> Log.w(
                LOG_TAG, "Unexpected version $version for package $packageName," +
                    "latest version is $VERSION_LATEST"
            )
        }
    }

    fun BinaryXmlPullParser.parseSystemState(state: AccessState) {
        forEachTag {
            when (tagName) {
                TAG_ACCESS -> {
                    forEachTag {
                        forEachSchemePolicy {
                            with(it) { parseSystemState(state) }
                        }
                    }
                }
                else -> Log.w(LOG_TAG, "Ignoring unknown tag $tagName when parsing system state")
            }
        }
    }

    fun BinaryXmlSerializer.serializeSystemState(state: AccessState) {
        tag(TAG_ACCESS) {
            forEachSchemePolicy {
                with(it) { serializeSystemState(state) }
            }
        }
    }

    fun BinaryXmlPullParser.parseUserState(state: AccessState, userId: Int) {
        forEachTag {
            when (tagName) {
                TAG_ACCESS -> {
                    forEachTag {
                        when (tagName) {
                            TAG_PACKAGE_VERSIONS -> parsePackageVersions(state, userId)
                            else -> {
                                forEachSchemePolicy {
                                    with(it) { parseUserState(state, userId) }
                                }
                            }
                        }
                    }
                }
                else -> {
                    Log.w(
                        LOG_TAG,
                        "Ignoring unknown tag $tagName when parsing user state for user $userId"
                    )
                }
            }
        }
    }

    private fun BinaryXmlPullParser.parsePackageVersions(state: AccessState, userId: Int) {
        val userState = state.userStates[userId]
        forEachTag {
            when (tagName) {
                TAG_PACKAGE -> parsePackageVersion(userState)
                else -> Log.w(
                    LOG_TAG,
                    "Ignoring unknown tag $name when parsing package versions for user $userId"
                )
            }
        }
        userState.packageVersions.retainAllIndexed { _, packageName, _ ->
            val hasPackage = packageName in state.systemState.packageStates
            if (!hasPackage) {
                Log.w(
                    LOG_TAG,
                    "Dropping unknown $packageName when parsing package versions for user $userId"
                )
            }
            hasPackage
        }
    }

    private fun BinaryXmlPullParser.parsePackageVersion(userState: UserState) {
        val packageName = getAttributeValueOrThrow(ATTR_NAME).intern()
        val version = getAttributeIntOrThrow(ATTR_VERSION)
        userState.packageVersions[packageName] = version
    }

    fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {
        tag(TAG_ACCESS) {
            forEachSchemePolicy {
                with(it) { serializeUserState(state, userId) }
            }

            serializeVersions(state.userStates[userId])
        }
    }

    private fun BinaryXmlSerializer.serializeVersions(userState: UserState) {
        tag(TAG_PACKAGE_VERSIONS) {
            userState.packageVersions.forEachIndexed { _, packageName, version ->
                tag(TAG_PACKAGE) {
                    attributeInterned(ATTR_NAME, packageName)
                    attributeInt(ATTR_VERSION, version)
                }
            }
        }
    }

    private fun getSchemePolicy(subject: AccessUri, `object`: AccessUri): SchemePolicy =
        getSchemePolicy(subject.scheme, `object`.scheme)

    private inline fun forEachSchemePolicy(action: (SchemePolicy) -> Unit) {
        schemePolicies.forEachValueIndexed { _, objectSchemePolicies ->
            objectSchemePolicies.forEachValueIndexed { _, schemePolicy ->
                action(schemePolicy)
            }
        }
    }

    companion object {
        private val LOG_TAG = AccessPolicy::class.java.simpleName

        internal const val VERSION_LATEST = 14

        private const val TAG_ACCESS = "access"
        private const val TAG_PACKAGE_VERSIONS = "package-versions"
        private const val TAG_PACKAGE = "package"

        private const val ATTR_NAME = "name"
        private const val ATTR_VERSION = "version"
    }
}

abstract class SchemePolicy {
    abstract val subjectScheme: String

    abstract val objectScheme: String

    abstract fun GetStateScope.getDecision(subject: AccessUri, `object`: AccessUri): Int

    abstract fun MutateStateScope.setDecision(
        subject: AccessUri,
        `object`: AccessUri,
        decision: Int
    )

    open fun GetStateScope.onStateMutated() {}

    open fun MutateStateScope.onInitialized() {}

    open fun MutateStateScope.onUserAdded(userId: Int) {}

    open fun MutateStateScope.onUserRemoved(userId: Int) {}

    open fun MutateStateScope.onAppIdAdded(appId: Int) {}

    open fun MutateStateScope.onAppIdRemoved(appId: Int) {}

    open fun MutateStateScope.onStorageVolumeMounted(
        volumeUuid: String?,
        isSystemUpdated: Boolean
    ) {}

    open fun MutateStateScope.onPackageAdded(packageState: PackageState) {}

    open fun MutateStateScope.onPackageRemoved(packageName: String, appId: Int) {}

    open fun MutateStateScope.onPackageInstalled(packageState: PackageState, userId: Int) {}

    open fun MutateStateScope.onPackageUninstalled(packageName: String, appId: Int, userId: Int) {}

    open fun MutateStateScope.onSystemReady() {}

    open fun migrateSystemState(state: AccessState) {}

    open fun migrateUserState(state: AccessState, userId: Int) {}

    open fun MutateStateScope.upgradePackageState(
        packageState: PackageState,
        userId: Int,
        version: Int
    ) {}

    open fun BinaryXmlPullParser.parseSystemState(state: AccessState) {}

    open fun BinaryXmlSerializer.serializeSystemState(state: AccessState) {}

    open fun BinaryXmlPullParser.parseUserState(state: AccessState, userId: Int) {}

    open fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {}
}
