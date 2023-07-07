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
import com.android.server.permission.access.appop.PackageAppOpPolicy
import com.android.server.permission.access.appop.UidAppOpPolicy
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.permission.UidPermissionPolicy
import com.android.server.permission.access.util.forEachTag
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
            addPolicy(UidPermissionPolicy())
            addPolicy(UidAppOpPolicy())
            addPolicy(PackageAppOpPolicy())
        }
    )

    fun getSchemePolicy(subjectScheme: String, objectScheme: String): SchemePolicy =
        checkNotNull(schemePolicies[subjectScheme]?.get(objectScheme)) {
            "Scheme policy for $subjectScheme and $objectScheme does not exist"
        }

    fun GetStateScope.getDecision(subject: AccessUri, `object`: AccessUri): Int =
        with(getSchemePolicy(subject, `object`)){ getDecision(subject, `object`) }

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
                        forEachSchemePolicy {
                            with(it) { parseUserState(state, userId) }
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

    fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {
        tag(TAG_ACCESS) {
            forEachSchemePolicy {
                with(it) { serializeUserState(state, userId) }
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

        private const val TAG_ACCESS = "access"
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

    open fun BinaryXmlPullParser.parseSystemState(state: AccessState) {}

    open fun BinaryXmlSerializer.serializeSystemState(state: AccessState) {}

    open fun BinaryXmlPullParser.parseUserState(state: AccessState, userId: Int) {}

    open fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {}
}
