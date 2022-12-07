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

package com.android.server.permission.access.permission

import android.Manifest
import android.content.pm.PackageManager
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.os.Build
import android.os.UserHandle
import android.util.Log
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.AccessUri
import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.PermissionUri
import com.android.server.permission.access.SchemePolicy
import com.android.server.permission.access.SystemState
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.UserState
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.data.Permission
import com.android.server.permission.access.external.AndroidPackage
import com.android.server.permission.access.external.CompatibilityPermissionInfo
import com.android.server.permission.access.external.KnownPackages
import com.android.server.permission.access.external.PackageInfoUtils
import com.android.server.permission.access.external.PackageState
import com.android.server.permission.access.external.RoSystemProperties
import com.android.server.permission.access.external.SigningDetails
import com.android.server.permission.access.util.hasAnyBit
import com.android.server.permission.access.util.hasBits

class UidPermissionPolicy : SchemePolicy() {
    private val persistence = UidPermissionPersistence()

    override val subjectScheme: String
        get() = UidUri.SCHEME

    override val objectScheme: String
        get() = PermissionUri.SCHEME

    override fun GetStateScope.getDecision(subject: AccessUri, `object`: AccessUri): Int {
        subject as UidUri
        `object` as PermissionUri
        return state.userStates[subject.userId]?.permissionFlags?.get(subject.appId)
            ?.get(`object`.permissionName) ?: 0
    }

    override fun MutateStateScope.setDecision(
        subject: AccessUri,
        `object`: AccessUri,
        decision: Int
    ) {
        subject as UidUri
        `object` as PermissionUri
        val uidFlags = newState.userStates.getOrPut(subject.userId) { UserState() }
            .permissionFlags.getOrPut(subject.appId) { IndexedMap() }
        uidFlags[`object`.permissionName] = decision
    }

    override fun MutateStateScope.onUserAdded(userId: Int) {
        newState.systemState.packageStates.forEachValueIndexed { _, packageState ->
            evaluateAllPermissionStatesForPackageAndUser(packageState, null, userId)
            grantImplicitPermissions(packageState, userId)
        }
    }

    override fun MutateStateScope.onAppIdAdded(appId: Int) {
        newState.userStates.forEachIndexed { _, _, userState ->
            userState.permissionFlags.getOrPut(appId) { IndexedMap() }
        }
    }

    override fun MutateStateScope.onAppIdRemoved(appId: Int) {
        newState.userStates.forEachIndexed { _, _, userState -> userState.permissionFlags -= appId }
    }

    override fun MutateStateScope.onPackageAdded(packageState: PackageState) {
        val changedPermissionNames = IndexedSet<String>()
        adoptPermissions(packageState, changedPermissionNames)
        addPermissionGroups(packageState)
        addPermissions(packageState, changedPermissionNames)
        // TODO: revokeStoragePermissionsIfScopeExpandedInternal()
        trimPermissions(packageState.packageName)
        changedPermissionNames.forEachIndexed { _, permissionName ->
            evaluatePermissionStateForAllPackages(permissionName, packageState)
        }

        evaluateAllPermissionStatesForPackage(packageState, packageState)
        newState.systemState.userIds.forEachIndexed { _, userId ->
            grantImplicitPermissions(packageState, userId)
        }

        // TODO: add trimPermissionStates() here for removing the permission states that are
        // no longer requested. (equivalent to revokeUnusedSharedUserPermissionsLocked())
    }

    private fun MutateStateScope.adoptPermissions(
        packageState: PackageState,
        changedPermissionNames: IndexedSet<String>
    ) {
        val `package` = packageState.androidPackage!!
        `package`.adoptPermissions.forEachIndexed { _, originalPackageName ->
            val packageName = `package`.packageName
            if (!canAdoptPermissions(packageName, originalPackageName)) {
                return@forEachIndexed
            }
            newState.systemState.permissions.let { permissions ->
                permissions.forEachIndexed permissions@ {
                    permissionIndex, permissionName, oldPermission ->
                    if (oldPermission.packageName != originalPackageName) {
                        return@permissions
                    }
                    @Suppress("DEPRECATION")
                    val newPermissionInfo = PermissionInfo().apply {
                        name = oldPermission.permissionInfo.name
                        this.packageName = packageName
                        protectionLevel = oldPermission.permissionInfo.protectionLevel
                    }
                    val newPermission = Permission(newPermissionInfo, false, oldPermission.type, 0)
                    changedPermissionNames += permissionName
                    permissions.setValueAt(permissionIndex, newPermission)
                }
            }
        }
    }

    private fun MutateStateScope.canAdoptPermissions(
        packageName: String,
        originalPackageName: String
    ): Boolean {
        val originalPackageState = newState.systemState.packageStates[originalPackageName]
            ?: return false
        if (!originalPackageState.isSystem) {
            Log.w(
                LOG_TAG, "Unable to adopt permissions from $originalPackageName to $packageName:" +
                    " original package not in system partition"
            )
            return false
        }
        if (originalPackageState.androidPackage != null) {
            Log.w(
                LOG_TAG, "Unable to adopt permissions from $originalPackageName to $packageName:" +
                    " original package still exists"
            )
            return false
        }
        return true
    }

    private fun MutateStateScope.addPermissionGroups(packageState: PackageState) {
        // Different from the old implementation, which decides whether the app is an instant app by
        // the install flags, now for consistent behavior we allow adding permission groups if the
        // app is non-instant in at least one user.
        val isInstantApp = packageState.userStates.allIndexed { _, _, it -> it.isInstantApp }
        if (isInstantApp) {
            Log.w(
                LOG_TAG, "Ignoring permission groups declared in package" +
                    " ${packageState.packageName}: instant apps cannot declare permission groups"
            )
            return
        }
        packageState.androidPackage!!.permissionGroups.forEachIndexed { _, parsedPermissionGroup ->
            val newPermissionGroup = PackageInfoUtils.generatePermissionGroupInfo(
                parsedPermissionGroup, PackageManager.GET_META_DATA.toLong()
            )
            // TODO: Clear permission state on group take-over?
            val permissionGroupName = newPermissionGroup.name
            val oldPermissionGroup = newState.systemState.permissionGroups[permissionGroupName]
            if (oldPermissionGroup != null &&
                newPermissionGroup.packageName != oldPermissionGroup.packageName) {
                Log.w(
                    LOG_TAG, "Ignoring permission group $permissionGroupName declared in package" +
                        " ${newPermissionGroup.packageName}: already declared in another package" +
                        " ${oldPermissionGroup.packageName}"
                )
                return@forEachIndexed
            }
            newState.systemState.permissionGroups[permissionGroupName] = newPermissionGroup
        }
    }

    private fun MutateStateScope.addPermissions(
        packageState: PackageState,
        changedPermissionNames: IndexedSet<String>
    ) {
        packageState.androidPackage!!.permissions.forEachIndexed { _, parsedPermission ->
            // TODO:
            // parsedPermission.flags = parsedPermission.flags andInv PermissionInfo.FLAG_INSTALLED
            // TODO: This seems actually unused.
            // if (packageState.androidPackage.targetSdkVersion > Build.VERSION_CODES.LOLLIPOP_MR1) {
            //    parsedPermission.setParsedPermissionGroup(
            //        newState.systemState.permissionGroup[parsedPermission.group]
            //    )
            // }
            val newPermissionInfo = PackageInfoUtils.generatePermissionInfo(
                parsedPermission, PackageManager.GET_META_DATA.toLong()
            )
            // TODO: newPermissionInfo.flags |= PermissionInfo.FLAG_INSTALLED
            val permissionName = newPermissionInfo.name
            val oldPermission = if (parsedPermission.isTree) {
                newState.systemState.permissionTrees[permissionName]
            } else {
                newState.systemState.permissions[permissionName]
            }
            // Different from the old implementation, which may add an (incomplete) signature
            // permission inside another package's permission tree, we now consistently ignore such
            // permissions.
            val permissionTree = getPermissionTree(permissionName)
            val newPackageName = newPermissionInfo.packageName
            if (permissionTree != null && newPackageName != permissionTree.packageName) {
                Log.w(
                    LOG_TAG, "Ignoring permission $permissionName declared in package" +
                        " $newPackageName: base permission tree ${permissionTree.name} is" +
                        " declared in another package ${permissionTree.packageName}"
                )
                return@forEachIndexed
            }
            val newPermission = if (oldPermission != null &&
                newPackageName != oldPermission.packageName) {
                val oldPackageName = oldPermission.packageName
                // Only allow system apps to redefine non-system permissions.
                if (!packageState.isSystem) {
                    Log.w(
                        LOG_TAG, "Ignoring permission $permissionName declared in package" +
                            " $newPackageName: already declared in another package" +
                            " $oldPackageName"
                    )
                    return@forEachIndexed
                }
                if (oldPermission.type == Permission.TYPE_CONFIG && !oldPermission.isReconciled) {
                    // It's a config permission and has no owner, take ownership now.
                    Permission(newPermissionInfo, true, Permission.TYPE_CONFIG, packageState.appId)
                } else if (newState.systemState.packageStates[oldPackageName]?.isSystem != true) {
                    Log.w(
                        LOG_TAG, "Overriding permission $permissionName with new declaration in" +
                            " system package $newPackageName: originally declared in another" +
                            " package $oldPackageName"
                    )
                    // Remove permission state on owner change.
                    newState.userStates.forEachValueIndexed { _, userState ->
                        userState.permissionFlags.forEachValueIndexed { _, permissionFlags ->
                            permissionFlags -= newPermissionInfo.name
                        }
                    }
                    // TODO: Notify re-evaluation of this permission.
                    Permission(
                        newPermissionInfo, true, Permission.TYPE_MANIFEST, packageState.appId
                    )
                } else {
                    Log.w(
                        LOG_TAG, "Ignoring permission $permissionName declared in system package" +
                            " $newPackageName: already declared in another system package" +
                            " $oldPackageName")
                    return@forEachIndexed
                }
            } else {
                // TODO: STOPSHIP: Clear permission state on type or group change.
                // Different from the old implementation, which doesn't update the permission
                // definition upon app update, but does update it on the next boot, we now
                // consistently update the permission definition upon app update.
                Permission(newPermissionInfo, true, Permission.TYPE_MANIFEST, packageState.appId)
            }

            changedPermissionNames += permissionName
            if (parsedPermission.isTree) {
                newState.systemState.permissionTrees[permissionName] = newPermission
            } else {
                newState.systemState.permissions[permissionName] = newPermission
            }
        }
    }

    private fun MutateStateScope.trimPermissions(packageName: String) {
        val packageState = newState.systemState.packageStates[packageName]
        val androidPackage = packageState?.androidPackage
        if (packageState != null && androidPackage == null) {
            return
        }

        newState.systemState.permissionTrees.removeAllIndexed {
            _, permissionTreeName, permissionTree ->
            permissionTree.packageName == packageName && (
                packageState == null || androidPackage!!.permissions.noneIndexed { _, it ->
                    it.isTree && it.name == permissionTreeName
                }
            )
        }

        newState.systemState.permissions.removeAllIndexed { i, permissionName, permission ->
            val updatedPermission = updatePermissionIfDynamic(permission)
            newState.systemState.permissions.setValueAt(i, updatedPermission)
            if (updatedPermission.packageName == packageName && (
                packageState == null || androidPackage!!.permissions.noneIndexed { _, it ->
                    !it.isTree && it.name == permissionName
                }
            )) {
                if (!isPermissionDeclaredByDisabledSystemPackage(permission)) {
                    newState.userStates.forEachIndexed { _, userId, userState ->
                        userState.permissionFlags.forEachKeyIndexed { _, appId ->
                            setPermissionFlags(
                                appId, permissionName, getPermissionFlags(
                                    appId, permissionName, userId
                                ) and PermissionFlags.INSTALL_REVOKED, userId
                            )
                        }
                    }
                }
                true
            } else {
                false
            }
        }
    }

    private fun MutateStateScope.isPermissionDeclaredByDisabledSystemPackage(
        permission: Permission
    ): Boolean {
        val disabledSystemPackage = newState.systemState
            .disabledSystemPackageStates[permission.packageName]?.androidPackage ?: return false
        return disabledSystemPackage.permissions.anyIndexed { _, it ->
            it.name == permission.name && it.protectionLevel == permission.protectionLevel
        }
    }

    private fun MutateStateScope.updatePermissionIfDynamic(permission: Permission): Permission {
        if (!permission.isDynamic) {
            return permission
        }
        val permissionTree = getPermissionTree(permission.name) ?: return permission
        @Suppress("DEPRECATION")
        return permission.copy(
            permissionInfo = PermissionInfo(permission.permissionInfo).apply {
                packageName = permissionTree.packageName
            }, appId = permissionTree.appId, isReconciled = true
        )
    }

    private fun MutateStateScope.getPermissionTree(permissionName: String): Permission? =
        newState.systemState.permissionTrees.firstNotNullOfOrNullIndexed {
            _, permissionTreeName, permissionTree ->
            if (permissionName.startsWith(permissionTreeName) &&
                permissionName.length > permissionTreeName.length &&
                permissionName[permissionTreeName.length] == '.') {
                permissionTree
            } else {
                null
            }
        }

    private fun MutateStateScope.evaluatePermissionStateForAllPackages(
        permissionName: String,
        installedPackageState: PackageState?
    ) {
        newState.systemState.userIds.forEachIndexed { _, userId ->
            oldState.userStates[userId]?.permissionFlags?.forEachIndexed {
                _, appId, permissionFlags ->
                if (permissionName in permissionFlags) {
                    evaluatePermissionState(appId, permissionName, installedPackageState, userId)
                }
            }
        }
    }

    private fun MutateStateScope.evaluateAllPermissionStatesForPackage(
        packageState: PackageState,
        installedPackageState: PackageState?
    ) {
        newState.systemState.userIds.forEachIndexed { _, userId ->
            evaluateAllPermissionStatesForPackageAndUser(
                packageState, installedPackageState, userId
            )
        }
    }

    private fun MutateStateScope.evaluateAllPermissionStatesForPackageAndUser(
        packageState: PackageState,
        installedPackageState: PackageState?,
        userId: Int
    ) {
        packageState.androidPackage?.requestedPermissions?.forEachIndexed { _, permissionName ->
            evaluatePermissionState(
                packageState.appId, permissionName, installedPackageState, userId
            )
        }
    }

    private fun MutateStateScope.evaluatePermissionState(
        appId: Int,
        permissionName: String,
        installedPackageState: PackageState?,
        userId: Int
    ) {
        val packageNames = newState.systemState.appIds[appId]
        val hasMissingPackage = packageNames.anyIndexed { _, packageName ->
            newState.systemState.packageStates[packageName]!!.androidPackage == null
        }
        if (packageNames.size == 1 && hasMissingPackage) {
            // For non-shared-user packages with missing androidPackage, skip evaluation.
            return
        }
        val permission = newState.systemState.permissions[permissionName] ?: return
        val oldFlags = getPermissionFlags(appId, permissionName, userId)
        if (permission.isNormal) {
            val wasGranted = oldFlags.hasBits(PermissionFlags.INSTALL_GRANTED)
            if (!wasGranted) {
                val wasRevoked = oldFlags.hasBits(PermissionFlags.INSTALL_REVOKED)
                val isRequestedByInstalledPackage = installedPackageState != null &&
                    permissionName in installedPackageState.androidPackage!!.requestedPermissions
                val isRequestedBySystemPackage = anyPackageInAppId(appId) {
                    it.isSystem && permissionName in it.androidPackage!!.requestedPermissions
                }
                val isCompatibilityPermission = anyPackageInAppId(appId) {
                    isCompatibilityPermissionForPackage(it.androidPackage!!, permissionName)
                }
                // If this is an existing, non-system package,
                // then we can't add any new permissions to it.
                // Except if this is a permission that was added to the platform
                val newFlags = if (!wasRevoked || isRequestedByInstalledPackage ||
                    isRequestedBySystemPackage || isCompatibilityPermission) {
                    PermissionFlags.INSTALL_GRANTED
                } else {
                    PermissionFlags.INSTALL_REVOKED
                }
                setPermissionFlags(appId, permissionName, newFlags, userId)
            }
        } else if (permission.isSignature || permission.isInternal) {
            val wasProtectionGranted = oldFlags.hasBits(PermissionFlags.PROTECTION_GRANTED)
            var newFlags = if (hasMissingPackage && wasProtectionGranted) {
                // Keep the non-runtime permission grants for shared UID with missing androidPackage
                PermissionFlags.PROTECTION_GRANTED
            } else {
                val mayGrantByPrivileged = !permission.isPrivileged || (
                    anyPackageInAppId(appId) {
                        checkPrivilegedPermissionAllowlist(it, permission)
                    }
                )
                val shouldGrantBySignature = permission.isSignature && (
                    anyPackageInAppId(appId) {
                        shouldGrantPermissionBySignature(it, permission)
                    }
                )
                val shouldGrantByProtectionFlags = anyPackageInAppId(appId) {
                    shouldGrantPermissionByProtectionFlags(it, permission)
                }
                if (mayGrantByPrivileged &&
                    (shouldGrantBySignature || shouldGrantByProtectionFlags)) {
                    PermissionFlags.PROTECTION_GRANTED
                } else {
                    0
                }
            }
            // Different from the old implementation, which seemingly allows granting an
            // unallowlisted privileged permission via development or role but revokes it upon next
            // reconciliation, we now properly allows that because the privileged protection flag
            // should only affect the other static flags, but not dynamic flags like development or
            // role. This may be useful in the case of an updated system app.
            if (permission.isDevelopment) {
                newFlags = newFlags or (oldFlags and PermissionFlags.OTHER_GRANTED)
            }
            if (permission.isRole) {
                newFlags = newFlags or (oldFlags and PermissionFlags.ROLE_GRANTED)
            }
            setPermissionFlags(appId, permissionName, newFlags, userId)
        } else if (permission.isRuntime) {
            // TODO: add runtime permissions
        } else {
            Log.e(LOG_TAG, "Unknown protection level ${permission.protectionLevel}" +
                "for permission ${permission.name} while evaluating permission state" +
                "for appId $appId and userId $userId")
        }

        // TODO: revokePermissionsNoLongerImplicitLocked() for runtime permissions
    }

    private fun MutateStateScope.grantImplicitPermissions(packageState: PackageState, userId: Int) {
        val appId = packageState.appId
        val androidPackage = packageState.androidPackage ?: return
        androidPackage.implicitPermissions.forEachIndexed implicitPermissions@ {
            _, implicitPermissionName ->
            val implicitPermission = newState.systemState.permissions[implicitPermissionName]
            checkNotNull(implicitPermission) {
                "Unknown implicit permission $implicitPermissionName in split permissions"
            }
            if (!implicitPermission.isRuntime) {
                return@implicitPermissions
            }
            // Explicitly check against the old state to determine if this permission is new.
            val isNewPermission = getPermissionFlags(
                appId, implicitPermissionName, userId, oldState
            ) == 0
            if (!isNewPermission) {
                return@implicitPermissions
            }
            val sourcePermissions = newState.systemState
                .implicitToSourcePermissions[implicitPermissionName] ?: return@implicitPermissions
            var newFlags = 0
            sourcePermissions.forEachIndexed sourcePermissions@ { _, sourcePermissionName ->
                val sourcePermission = newState.systemState.permissions[sourcePermissionName]
                checkNotNull(sourcePermission) {
                    "Unknown source permission $sourcePermissionName in split permissions"
                }
                val sourceFlags = getPermissionFlags(appId, sourcePermissionName, userId)
                val isSourceGranted = sourceFlags.hasAnyBit(PermissionFlags.MASK_GRANTED)
                val isNewGranted = newFlags.hasAnyBit(PermissionFlags.MASK_GRANTED)
                val isGrantingNewFromRevoke = isSourceGranted && !isNewGranted
                if (isSourceGranted == isNewGranted || isGrantingNewFromRevoke) {
                    if (isGrantingNewFromRevoke) {
                        newFlags = 0
                    }
                    newFlags = newFlags or (sourceFlags and PermissionFlags.MASK_RUNTIME)
                    if (!sourcePermission.isRuntime && isSourceGranted) {
                        newFlags = newFlags or PermissionFlags.OTHER_GRANTED
                    }
                }
            }
            newFlags = newFlags or PermissionFlags.IMPLICIT
            setPermissionFlags(appId, implicitPermissionName, newFlags, userId)
        }
    }

    private fun MutateStateScope.getPermissionFlags(
        appId: Int,
        permissionName: String,
        userId: Int,
        state: AccessState = newState
    ): Int = state.userStates[userId].permissionFlags[appId].getWithDefault(permissionName, 0)

    private fun MutateStateScope.setPermissionFlags(
        appId: Int,
        permissionName: String,
        flags: Int,
        userId: Int
    ) {
        newState.userStates[userId].permissionFlags[appId]!!
            .putWithDefault(permissionName, flags, 0)
    }

    private fun isCompatibilityPermissionForPackage(
        androidPackage: AndroidPackage,
        permissionName: String
    ): Boolean {
        for (compatibilityPermission in CompatibilityPermissionInfo.COMPAT_PERMS) {
            if (compatibilityPermission.name == permissionName &&
                androidPackage.targetSdkVersion < compatibilityPermission.sdkVersion) {
                Log.i(
                    LOG_TAG, "Auto-granting $permissionName to old package" +
                    " ${androidPackage.packageName}"
                )
                return true
            }
        }
        return false
    }

    private fun MutateStateScope.shouldGrantPermissionBySignature(
        packageState: PackageState,
        permission: Permission
    ): Boolean {
        // check if the package is allow to use this signature permission.  A package is allowed to
        // use a signature permission if:
        //     - it has the same set of signing certificates as the source package
        //     - or its signing certificate was rotated from the source package's certificate
        //     - or its signing certificate is a previous signing certificate of the defining
        //       package, and the defining package still trusts the old certificate for permissions
        //     - or it shares a common signing certificate in its lineage with the defining package,
        //       and the defining package still trusts the old certificate for permissions
        //     - or it shares the above relationships with the system package
        val sourceSigningDetails = newState.systemState
            .packageStates[permission.packageName]?.signingDetails
        val platformSigningDetails = newState.systemState
            .packageStates[PLATFORM_PACKAGE_NAME]!!.signingDetails
        return sourceSigningDetails?.hasCommonSignerWithCapability(packageState.signingDetails,
            SigningDetails.CertCapabilities.PERMISSION) == true ||
            packageState.signingDetails.hasAncestorOrSelf(platformSigningDetails) ||
            platformSigningDetails.checkCapability(packageState.signingDetails,
                    SigningDetails.CertCapabilities.PERMISSION)
    }

    private fun MutateStateScope.checkPrivilegedPermissionAllowlist(
        packageState: PackageState,
        permission: Permission
    ): Boolean {
        if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_DISABLE) {
            return true
        }
        if (packageState.packageName == PLATFORM_PACKAGE_NAME) {
            return true
        }
        val androidPackage = packageState.androidPackage!!
        if (!androidPackage.isPrivileged) {
            return true
        }
        if (permission.packageName !in
            newState.systemState.privilegedPermissionAllowlistSourcePackageNames) {
            return true
        }
        if (isInSystemConfigPrivAppPermissions(androidPackage, permission.name)) {
            return true
        }
        if (isInSystemConfigPrivAppDenyPermissions(androidPackage, permission.name)) {
            return false
        }
        // Updated system apps do not need to be allowlisted
        if (packageState.isUpdatedSystemApp) {
            return true
        }
        // TODO: Enforce the allowlist on boot
        return !RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE
    }

    private fun MutateStateScope.isInSystemConfigPrivAppPermissions(
        androidPackage: AndroidPackage,
        permissionName: String
    ): Boolean {
        val apexModuleName = androidPackage.apexModuleName
        val systemState = newState.systemState
        val packageName = androidPackage.packageName
        val permissionNames = when {
            androidPackage.isVendor -> systemState.vendorPrivAppPermissions[packageName]
            androidPackage.isProduct -> systemState.productPrivAppPermissions[packageName]
            androidPackage.isSystemExt -> systemState.systemExtPrivAppPermissions[packageName]
            apexModuleName != null -> {
                val apexPrivAppPermissions = systemState.apexPrivAppPermissions[apexModuleName]
                    ?.get(packageName)
                val privAppPermissions = systemState.privAppPermissions[packageName]
                when {
                    apexPrivAppPermissions == null -> privAppPermissions
                    privAppPermissions == null -> apexPrivAppPermissions
                    else -> apexPrivAppPermissions + privAppPermissions
                }
            }
            else -> systemState.privAppPermissions[packageName]
        }
        return permissionNames?.contains(permissionName) == true
    }

    private fun MutateStateScope.isInSystemConfigPrivAppDenyPermissions(
        androidPackage: AndroidPackage,
        permissionName: String
    ): Boolean {
        // Different from the previous implementation, which may incorrectly use the APEX package
        // name, we now use the APEX module name to be consistent with the allowlist.
        val apexModuleName = androidPackage.apexModuleName
        val systemState = newState.systemState
        val packageName = androidPackage.packageName
        val permissionNames = when {
            androidPackage.isVendor -> systemState.vendorPrivAppDenyPermissions[packageName]
            androidPackage.isProduct -> systemState.productPrivAppDenyPermissions[packageName]
            androidPackage.isSystemExt -> systemState.systemExtPrivAppDenyPermissions[packageName]
            // Different from the previous implementation, which ignores the regular priv app
            // denylist in this case, we now respect it as well to be consistent with the allowlist.
            apexModuleName != null -> {
                val apexPrivAppDenyPermissions = systemState
                    .apexPrivAppDenyPermissions[apexModuleName]?.get(packageName)
                val privAppDenyPermissions = systemState.privAppDenyPermissions[packageName]
                when {
                    apexPrivAppDenyPermissions == null -> privAppDenyPermissions
                    privAppDenyPermissions == null -> apexPrivAppDenyPermissions
                    else -> apexPrivAppDenyPermissions + privAppDenyPermissions
                }
            }
            else -> systemState.privAppDenyPermissions[packageName]
        }
        return permissionNames?.contains(permissionName) == true
    }

    private fun MutateStateScope.anyPackageInAppId(
        appId: Int,
        state: AccessState = newState,
        predicate: (PackageState) -> Boolean
    ): Boolean {
        val packageNames = state.systemState.appIds[appId]
        return packageNames.anyIndexed { _, packageName ->
            val packageState = state.systemState.packageStates[packageName]!!
            packageState.androidPackage != null && predicate(packageState)
        }
    }

    private fun MutateStateScope.shouldGrantPermissionByProtectionFlags(
        packageState: PackageState,
        permission: Permission
    ): Boolean {
        val androidPackage = packageState.androidPackage!!
        val knownPackages = newState.systemState.knownPackages
        val packageName = packageState.packageName
        if ((permission.isPrivileged || permission.isOem) && packageState.isSystem) {
            val shouldGrant = if (packageState.isUpdatedSystemApp) {
                // For updated system applications, a privileged/oem permission
                // is granted only if it had been defined by the original application.
                val disabledSystemPackage = newState.systemState
                    .disabledSystemPackageStates[packageState.packageName]?.androidPackage
                disabledSystemPackage != null &&
                    permission.name in disabledSystemPackage.requestedPermissions &&
                    shouldGrantPrivilegedOrOemPermission(disabledSystemPackage, permission)
            } else {
                shouldGrantPrivilegedOrOemPermission(androidPackage, permission)
            }
            if (shouldGrant) {
                return true
            }
        }
        if (permission.isPre23 && androidPackage.targetSdkVersion < Build.VERSION_CODES.M) {
            // If this was a previously normal/dangerous permission that got moved
            // to a system permission as part of the runtime permission redesign, then
            // we still want to blindly grant it to old apps.
            return true
        }
        if (permission.isInstaller && (
            packageName in knownPackages[KnownPackages.PACKAGE_INSTALLER] ||
                packageName in knownPackages[KnownPackages.PACKAGE_PERMISSION_CONTROLLER]
        )) {
            // If this permission is to be granted to the system installer and
            // this app is an installer or permission controller, then it gets the permission.
            return true
        }
        if (permission.isVerifier &&
            packageName in knownPackages[KnownPackages.PACKAGE_VERIFIER]) {
            // If this permission is to be granted to the system verifier and
            // this app is a verifier, then it gets the permission.
            return true
        }
        if (permission.isPreInstalled && packageState.isSystem) {
            // Any pre-installed system app is allowed to get this permission.
            return true
        }
        if (permission.isKnownSigner &&
            packageState.signingDetails.hasAncestorOrSelfWithDigest(permission.knownCerts)) {
            // If the permission is to be granted to a known signer then check if any of this
            // app's signing certificates are in the trusted certificate digest Set.
            return true
        }
        if (permission.isSetup &&
            packageName in knownPackages[KnownPackages.PACKAGE_SETUP_WIZARD]) {
            // If this permission is to be granted to the system setup wizard and
            // this app is a setup wizard, then it gets the permission.
            return true
        }
        if (permission.isSystemTextClassifier &&
            packageName in knownPackages[KnownPackages.PACKAGE_SYSTEM_TEXT_CLASSIFIER]) {
            // Special permissions for the system default text classifier.
            return true
        }
        if (permission.isConfigurator &&
            packageName in knownPackages[KnownPackages.PACKAGE_CONFIGURATOR]) {
            // Special permissions for the device configurator.
            return true
        }
        if (permission.isIncidentReportApprover &&
            packageName in knownPackages[KnownPackages.PACKAGE_INCIDENT_REPORT_APPROVER]) {
            // If this permission is to be granted to the incident report approver and
            // this app is the incident report approver, then it gets the permission.
            return true
        }
        if (permission.isAppPredictor &&
            packageName in knownPackages[KnownPackages.PACKAGE_APP_PREDICTOR]) {
            // Special permissions for the system app predictor.
            return true
        }
        if (permission.isCompanion &&
            packageName in knownPackages[KnownPackages.PACKAGE_COMPANION]) {
            // Special permissions for the system companion device manager.
            return true
        }
        if (permission.isRetailDemo &&
            packageName in knownPackages[KnownPackages.PACKAGE_RETAIL_DEMO] &&
            isDeviceOrProfileOwnerUid(packageState.appId)) {
            // Special permission granted only to the OEM specified retail demo app.
            // Note that the original code was passing app ID as UID, so this behavior is kept
            // unchanged.
            return true
        }
        if (permission.isRecents &&
            packageName in knownPackages[KnownPackages.PACKAGE_RECENTS]) {
            // Special permission for the recents app.
            return true
        }
        return false
    }

    private fun MutateStateScope.shouldGrantPrivilegedOrOemPermission(
        androidPackage: AndroidPackage,
        permission: Permission
    ): Boolean {
        val permissionName = permission.name
        val packageName = androidPackage.packageName
        when {
            permission.isPrivileged -> {
                if (androidPackage.isPrivileged) {
                    // In any case, don't grant a privileged permission to privileged vendor apps,
                    // if the permission's protectionLevel does not have the extra vendorPrivileged
                    // flag.
                    if (androidPackage.isVendor && !permission.isVendorPrivileged) {
                        Log.w(
                            LOG_TAG, "Permission $permissionName cannot be granted to privileged" +
                            " vendor app $packageName because it isn't a vendorPrivileged" +
                            " permission"
                        )
                        return false
                    }
                    return true
                }
            }
            permission.isOem -> {
                if (androidPackage.isOem) {
                    val isOemAllowlisted = newState.systemState
                        .oemPermissions[packageName]?.get(permissionName)
                    checkNotNull(isOemAllowlisted) {
                        "OEM permission $permissionName requested by package" +
                            " $packageName must be explicitly declared granted or not"
                    }
                    return isOemAllowlisted
                }
            }
        }
        return false
    }

    private fun MutateStateScope.isDeviceOrProfileOwnerUid(uid: Int): Boolean {
        val userId = UserHandle.getUserId(uid)
        val ownerPackageName = newState.systemState.deviceAndProfileOwners[userId] ?: return false
        val ownerPackageState = newState.systemState.packageStates[ownerPackageName] ?: return false
        val ownerUid = UserHandle.getUid(userId, ownerPackageState.appId)
        return uid == ownerUid
    }

    override fun MutateStateScope.onPackageRemoved(packageState: PackageState) {
        // TODO
    }

    override fun BinaryXmlPullParser.parseSystemState(systemState: SystemState) {
        with(persistence) { this@parseSystemState.parseSystemState(systemState) }
    }

    override fun BinaryXmlSerializer.serializeSystemState(systemState: SystemState) {
        with(persistence) { this@serializeSystemState.serializeSystemState(systemState) }
    }

    fun GetStateScope.getPermissionGroup(permissionGroupName: String): PermissionGroupInfo? =
        state.systemState.permissionGroups[permissionGroupName]

    fun GetStateScope.getPermission(permissionName: String): Permission? =
        state.systemState.permissions[permissionName]

    companion object {
        private val LOG_TAG = UidPermissionPolicy::class.java.simpleName

        private const val PLATFORM_PACKAGE_NAME = "android"

        // A set of permissions that we don't want to revoke when they are no longer implicit.
        private val RETAIN_IMPLICIT_GRANT_PERMISSIONS = indexedSetOf(
            Manifest.permission.ACCESS_MEDIA_LOCATION,
            Manifest.permission.ACTIVITY_RECOGNITION,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
    }
}
