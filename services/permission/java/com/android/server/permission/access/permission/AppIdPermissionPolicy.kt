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
import android.content.pm.SigningDetails
import android.os.Build
import android.permission.flags.Flags
import android.util.Slog
import com.android.internal.os.RoSystemProperties
import com.android.internal.pm.permission.CompatibilityPermissionInfo
import com.android.modules.utils.BinaryXmlPullParser
import com.android.modules.utils.BinaryXmlSerializer
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.MutableAccessState
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.PermissionUri
import com.android.server.permission.access.SchemePolicy
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.WriteMode
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.andInv
import com.android.server.permission.access.util.hasAnyBit
import com.android.server.permission.access.util.hasBits
import com.android.server.permission.access.util.isInternal
import com.android.server.pm.KnownPackages
import com.android.server.pm.parsing.PackageInfoUtils
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageState
import libcore.util.EmptyArray

class AppIdPermissionPolicy : SchemePolicy() {
    private val persistence = AppIdPermissionPersistence()

    private val migration = AppIdPermissionMigration()

    private val upgrade = AppIdPermissionUpgrade(this)

    @Volatile
    private var onPermissionFlagsChangedListeners:
        IndexedListSet<OnPermissionFlagsChangedListener> =
        MutableIndexedListSet()
    private val onPermissionFlagsChangedListenersLock = Any()

    private val privilegedPermissionAllowlistViolations = MutableIndexedSet<String>()

    /** Test-only switch to enforce signature permission allowlist even on debuggable builds. */
    @Volatile var isSignaturePermissionAllowlistForceEnforced = false

    override val subjectScheme: String
        get() = UidUri.SCHEME

    override val objectScheme: String
        get() = PermissionUri.SCHEME

    override fun GetStateScope.onStateMutated() {
        onPermissionFlagsChangedListeners.forEachIndexed { _, it -> it.onStateMutated() }
    }

    override fun MutateStateScope.onUserAdded(userId: Int) {
        newState.externalState.packageStates.forEach { (_, packageState) ->
            if (packageState.isApex) {
                return@forEach
            }
            evaluateAllPermissionStatesForPackageAndUser(packageState, userId, null)
        }
        newState.externalState.appIdPackageNames.forEachIndexed { _, appId, _ ->
            inheritImplicitPermissionStates(appId, userId)
        }
    }

    override fun MutateStateScope.onAppIdRemoved(appId: Int) {
        newState.userStates.forEachIndexed { userStateIndex, _, userState ->
            if (appId in userState.appIdPermissionFlags) {
                newState.mutateUserStateAt(userStateIndex).mutateAppIdPermissionFlags() -= appId
                // Skip notifying the change listeners since the app ID no longer exists.
            }
        }
    }

    override fun MutateStateScope.onStorageVolumeMounted(
        volumeUuid: String?,
        packageNames: List<String>,
        isSystemUpdated: Boolean
    ) {
        val changedPermissionNames = MutableIndexedSet<String>()
        packageNames.forEachIndexed { _, packageName ->
            // The package may still be removed even if it was once notified as installed.
            val packageState =
                newState.externalState.packageStates[packageName] ?: return@forEachIndexed
            adoptPermissions(packageState, changedPermissionNames)
            addPermissionGroups(packageState)
            addPermissions(packageState, changedPermissionNames)
            trimPermissions(packageState.packageName, changedPermissionNames)
            trimPermissionStates(packageState.appId)
            revokePermissionsOnPackageUpdate(packageState.appId)
        }
        changedPermissionNames.forEachIndexed { _, permissionName ->
            evaluatePermissionStateForAllPackages(permissionName, null)
        }

        packageNames.forEachIndexed { _, packageName ->
            val packageState =
                newState.externalState.packageStates[packageName] ?: return@forEachIndexed
            val installedPackageState = if (isSystemUpdated) packageState else null
            evaluateAllPermissionStatesForPackage(packageState, installedPackageState)
        }
        packageNames.forEachIndexed { _, packageName ->
            val packageState =
                newState.externalState.packageStates[packageName] ?: return@forEachIndexed
            newState.externalState.userIds.forEachIndexed { _, userId ->
                inheritImplicitPermissionStates(packageState.appId, userId)
            }
        }
    }

    override fun MutateStateScope.onPackageAdded(packageState: PackageState) {
        val changedPermissionNames = MutableIndexedSet<String>()
        adoptPermissions(packageState, changedPermissionNames)
        addPermissionGroups(packageState)
        addPermissions(packageState, changedPermissionNames)
        trimPermissions(packageState.packageName, changedPermissionNames)
        trimPermissionStates(packageState.appId)
        revokePermissionsOnPackageUpdate(packageState.appId)
        changedPermissionNames.forEachIndexed { _, permissionName ->
            evaluatePermissionStateForAllPackages(permissionName, null)
        }
        evaluateAllPermissionStatesForPackage(packageState, packageState)
        newState.externalState.userIds.forEachIndexed { _, userId ->
            inheritImplicitPermissionStates(packageState.appId, userId)
        }
    }

    override fun MutateStateScope.onPackageRemoved(packageName: String, appId: Int) {
        check(packageName !in newState.externalState.disabledSystemPackageStates) {
            "Package $packageName reported as removed before disabled system package is enabled"
        }

        val changedPermissionNames = MutableIndexedSet<String>()
        trimPermissions(packageName, changedPermissionNames)
        if (appId in newState.externalState.appIdPackageNames) {
            trimPermissionStates(appId)
        }
        changedPermissionNames.forEachIndexed { _, permissionName ->
            evaluatePermissionStateForAllPackages(permissionName, null)
        }
    }

    override fun MutateStateScope.onPackageInstalled(packageState: PackageState, userId: Int) {
        // Clear UPGRADE_EXEMPT for all permissions requested by this package since there's
        // an installer and the installer has made a decision.
        clearRestrictedPermissionImplicitExemption(packageState, userId)
    }

    private fun MutateStateScope.clearRestrictedPermissionImplicitExemption(
        packageState: PackageState,
        userId: Int
    ) {
        // System apps can always retain their UPGRADE_EXEMPT.
        if (packageState.isSystem) {
            return
        }
        val androidPackage = packageState.androidPackage ?: return
        val appId = packageState.appId
        androidPackage.requestedPermissions.forEach { permissionName ->
            val permission = newState.systemState.permissions[permissionName] ?: return@forEach
            if (!permission.isHardOrSoftRestricted) {
                return@forEach
            }
            val isRequestedBySystemPackage =
                anyPackageInAppId(appId) {
                    it.isSystem && permissionName in it.androidPackage!!.requestedPermissions
                }
            if (isRequestedBySystemPackage) {
                return@forEach
            }
            updatePermissionExemptFlags(
                appId,
                userId,
                permission,
                PermissionFlags.UPGRADE_EXEMPT,
                0
            )
        }
    }

    fun MutateStateScope.updatePermissionExemptFlags(
        appId: Int,
        userId: Int,
        permission: Permission,
        exemptFlagMask: Int,
        exemptFlagValues: Int
    ) {
        val permissionName = permission.name
        val oldFlags = getPermissionFlags(appId, userId, permissionName)
        var newFlags = (oldFlags andInv exemptFlagMask) or (exemptFlagValues and exemptFlagMask)
        if (oldFlags == newFlags) {
            return
        }
        val isExempt = newFlags.hasAnyBit(PermissionFlags.MASK_EXEMPT)
        if (permission.isHardRestricted && !isExempt) {
            newFlags = newFlags or PermissionFlags.RESTRICTION_REVOKED
            // If the permission was policy fixed as granted but it is no longer on any of the
            // allowlists we need to clear the policy fixed flag as allowlisting trumps policy i.e.
            // policy cannot grant a non grantable permission.
            if (PermissionFlags.isPermissionGranted(oldFlags)) {
                newFlags = newFlags andInv PermissionFlags.POLICY_FIXED
            }
        } else {
            newFlags = newFlags andInv PermissionFlags.RESTRICTION_REVOKED
        }
        val isSoftRestricted =
            if (permission.isSoftRestricted && !isExempt) {
                val targetSdkVersion = getAppIdTargetSdkVersion(appId, permissionName)
                !anyPackageInAppId(appId) {
                    permissionName in it.androidPackage!!.requestedPermissions &&
                        isSoftRestrictedPermissionExemptForPackage(
                            it,
                            targetSdkVersion,
                            permissionName
                        )
                }
            } else {
                false
            }
        newFlags =
            if (isSoftRestricted) {
                newFlags or PermissionFlags.SOFT_RESTRICTED
            } else {
                newFlags andInv PermissionFlags.SOFT_RESTRICTED
            }
        if (oldFlags == newFlags) {
            return
        }
        setPermissionFlags(appId, userId, permissionName, newFlags)
    }

    override fun MutateStateScope.onPackageUninstalled(
        packageName: String,
        appId: Int,
        userId: Int
    ) {
        resetRuntimePermissions(packageName, userId)
    }

    fun MutateStateScope.resetRuntimePermissions(packageName: String, userId: Int) {
        // It's okay to skip resetting permissions for packages that are removed,
        // because their states will be trimmed in onPackageRemoved()/onAppIdRemoved()
        val packageState = newState.externalState.packageStates[packageName] ?: return
        val androidPackage = packageState.androidPackage ?: return
        val appId = packageState.appId
        androidPackage.requestedPermissions.forEach { permissionName ->
            val permission = newState.systemState.permissions[permissionName] ?: return@forEach
            if (!permission.isRuntime || permission.isRemoved) {
                return@forEach
            }
            val isRequestedByOtherPackages =
                anyPackageInAppId(appId) {
                    it.packageName != packageName &&
                        permissionName in it.androidPackage!!.requestedPermissions
                }
            if (isRequestedByOtherPackages) {
                return@forEach
            }
            val oldFlags = getPermissionFlags(appId, userId, permissionName)
            if (oldFlags.hasAnyBit(SYSTEM_OR_POLICY_FIXED_MASK)) {
                return@forEach
            }
            var newFlags = oldFlags
            newFlags =
                if (
                    newFlags.hasBits(PermissionFlags.ROLE) ||
                        newFlags.hasBits(PermissionFlags.PREGRANT)
                ) {
                    newFlags or PermissionFlags.RUNTIME_GRANTED
                } else {
                    newFlags andInv PermissionFlags.RUNTIME_GRANTED
                }
            newFlags = newFlags andInv USER_SETTABLE_MASK
            if (newFlags.hasBits(PermissionFlags.LEGACY_GRANTED)) {
                newFlags = newFlags or PermissionFlags.IMPLICIT
            }
            setPermissionFlags(appId, userId, permissionName, newFlags)
        }
    }

    private fun MutateStateScope.adoptPermissions(
        packageState: PackageState,
        changedPermissionNames: MutableIndexedSet<String>
    ) {
        val `package` = packageState.androidPackage!!
        `package`.adoptPermissions.forEachIndexed { _, originalPackageName ->
            val packageName = `package`.packageName
            if (!canAdoptPermissions(packageName, originalPackageName)) {
                return@forEachIndexed
            }
            newState.systemState.permissions.forEachIndexed permissions@{
                permissionIndex,
                permissionName,
                oldPermission ->
                if (oldPermission.packageName != originalPackageName) {
                    return@permissions
                }
                @Suppress("DEPRECATION")
                val newPermissionInfo =
                    PermissionInfo().apply {
                        name = oldPermission.permissionInfo.name
                        this.packageName = packageName
                        protectionLevel = oldPermission.permissionInfo.protectionLevel
                    }
                // Different from the old implementation, which removes the GIDs upon permission
                // adoption, but adds them back on the next boot, we now just consistently keep the
                // GIDs.
                val newPermission =
                    oldPermission.copy(
                        permissionInfo = newPermissionInfo,
                        isReconciled = false,
                        appId = 0
                    )
                newState
                    .mutateSystemState()
                    .mutatePermissions()
                    .putAt(permissionIndex, newPermission)
                changedPermissionNames += permissionName
            }
        }
    }

    private fun MutateStateScope.canAdoptPermissions(
        packageName: String,
        originalPackageName: String
    ): Boolean {
        val originalPackageState =
            newState.externalState.packageStates[originalPackageName] ?: return false
        if (!originalPackageState.isSystem) {
            Slog.w(
                LOG_TAG,
                "Unable to adopt permissions from $originalPackageName to $packageName:" +
                    " original package not in system partition"
            )
            return false
        }
        if (originalPackageState.androidPackage != null) {
            Slog.w(
                LOG_TAG,
                "Unable to adopt permissions from $originalPackageName to $packageName:" +
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
            Slog.w(
                LOG_TAG,
                "Ignoring permission groups declared in package" +
                    " ${packageState.packageName}: instant apps cannot declare permission groups"
            )
            return
        }
        packageState.androidPackage!!.permissionGroups.forEachIndexed { _, parsedPermissionGroup ->
            val newPermissionGroup =
                PackageInfoUtils.generatePermissionGroupInfo(
                    parsedPermissionGroup,
                    PackageManager.GET_META_DATA.toLong()
                )!!
            // TODO: Clear permission state on group take-over?
            val permissionGroupName = newPermissionGroup.name
            val oldPermissionGroup = newState.systemState.permissionGroups[permissionGroupName]
            if (
                oldPermissionGroup != null &&
                    newPermissionGroup.packageName != oldPermissionGroup.packageName
            ) {
                val newPackageName = newPermissionGroup.packageName
                val oldPackageName = oldPermissionGroup.packageName
                // Different from the old implementation, which defines permission group on
                // a first-come-first-serve basis, and relies on system apps being scanned before
                // non-system apps, we now allow system apps to override permission groups similar
                // to permissions so that we no longer need to rely on the scan order.
                if (!packageState.isSystem) {
                    Slog.w(
                        LOG_TAG,
                        "Ignoring permission group $permissionGroupName declared in" +
                            " package $newPackageName: already declared in another" +
                            " package $oldPackageName"
                    )
                    return@forEachIndexed
                }
                if (newState.externalState.packageStates[oldPackageName]?.isSystem == true) {
                    Slog.w(
                        LOG_TAG,
                        "Ignoring permission group $permissionGroupName declared in" +
                            " system package $newPackageName: already declared in another" +
                            " system package $oldPackageName"
                    )
                    return@forEachIndexed
                }
                Slog.w(
                    LOG_TAG,
                    "Overriding permission group $permissionGroupName with" +
                        " new declaration in system package $newPackageName: originally" +
                        " declared in another package $oldPackageName"
                )
            }
            newState.mutateSystemState().mutatePermissionGroups()[permissionGroupName] =
                newPermissionGroup
        }
    }

    private fun MutateStateScope.addPermissions(
        packageState: PackageState,
        changedPermissionNames: MutableIndexedSet<String>
    ) {
        val androidPackage = packageState.androidPackage!!
        // This may not be the same package as the old permission because the old permission owner
        // can be different, hence using this somewhat strange name to prevent misuse.
        val oldNewPackage =
            oldState.externalState.packageStates[packageState.packageName]?.androidPackage
        val isPackageSigningChanged =
            oldNewPackage != null && androidPackage.signingDetails != oldNewPackage.signingDetails
        androidPackage.permissions.forEachIndexed { _, parsedPermission ->
            val newPermissionInfo =
                PackageInfoUtils.generatePermissionInfo(
                    parsedPermission,
                    PackageManager.GET_META_DATA.toLong()
                )!!
            val permissionName = newPermissionInfo.name
            val oldPermission =
                if (parsedPermission.isTree) {
                    newState.systemState.permissionTrees[permissionName]
                } else {
                    newState.systemState.permissions[permissionName]
                }

            // Different from the old implementation, which may add an (incomplete) signature
            // permission inside another package's permission tree, we now consistently ignore such
            // permissions.
            val permissionTree = findPermissionTree(permissionName)
            val newPackageName = newPermissionInfo.packageName
            if (permissionTree != null && newPackageName != permissionTree.packageName) {
                Slog.w(
                    LOG_TAG,
                    "Ignoring permission $permissionName declared in package" +
                        " $newPackageName: base permission tree ${permissionTree.name} is" +
                        " declared in another package ${permissionTree.packageName}"
                )
                return@forEachIndexed
            }

            if (oldPermission != null) {
                if (newPackageName != oldPermission.packageName) {
                    val oldPackageName = oldPermission.packageName
                    // Only allow system apps to redefine non-system permissions.
                    if (!packageState.isSystem) {
                        Slog.w(
                            LOG_TAG,
                            "Ignoring permission $permissionName declared in package" +
                                " $newPackageName: already declared in another package" +
                                " $oldPackageName"
                        )
                        return@forEachIndexed
                    }
                    if (newState.externalState.packageStates[oldPackageName]?.isSystem == true) {
                        Slog.w(
                            LOG_TAG,
                            "Ignoring permission $permissionName declared in system package" +
                                " $newPackageName: already declared in another system package" +
                                " $oldPackageName"
                        )
                        return@forEachIndexed
                    }
                    Slog.w(
                        LOG_TAG,
                        "Overriding permission $permissionName with new declaration in" +
                            " system package $newPackageName: originally declared in another" +
                            " package $oldPackageName"
                    )
                    // Remove permission state on owner change.
                    newState.externalState.userIds.forEachIndexed { _, userId ->
                        newState.externalState.appIdPackageNames.forEachIndexed { _, appId, _ ->
                            setPermissionFlags(appId, userId, permissionName, 0)
                        }
                    }
                } else if (oldPermission.isReconciled) {
                    val isPermissionGroupChanged =
                        newPermissionInfo.isRuntime &&
                            newPermissionInfo.group != null &&
                            newPermissionInfo.group != oldPermission.groupName
                    val isPermissionProtectionChanged =
                        (newPermissionInfo.isRuntime && !oldPermission.isRuntime) ||
                            (newPermissionInfo.isInternal && !oldPermission.isInternal)
                    if (isPermissionGroupChanged || isPermissionProtectionChanged) {
                        newState.externalState.userIds.forEachIndexed { _, userId ->
                            newState.externalState.appIdPackageNames.forEachIndexed { _, appId, _ ->
                                if (isPermissionGroupChanged) {
                                    // We might auto-grant permissions if any permission of
                                    // the group is already granted. Hence if the group of
                                    // a granted permission changes we need to revoke it to
                                    // avoid having permissions of the new group auto-granted.
                                    Slog.w(
                                        LOG_TAG,
                                        "Revoking runtime permission $permissionName for" +
                                            " appId $appId and userId $userId as the permission" +
                                            " group changed from ${oldPermission.groupName}" +
                                            " to ${newPermissionInfo.group}"
                                    )
                                }
                                if (isPermissionProtectionChanged) {
                                    Slog.w(
                                        LOG_TAG,
                                        "Revoking permission $permissionName for" +
                                            " appId $appId and userId $userId as the permission" +
                                            " protection changed."
                                    )
                                }
                                setPermissionFlags(appId, userId, permissionName, 0)
                            }
                        }
                    }
                }
            }

            var gids = EmptyArray.INT
            var areGidsPerUser = false
            if (!parsedPermission.isTree && packageState.isSystem) {
                newState.externalState.configPermissions[permissionName]?.let {
                    // PermissionEntry.gids may return null when parsing legacy config trying
                    // to work around an issue about upgrading from L platfrm. We can just
                    // ignore such entries now.
                    if (it.gids != null) {
                        gids = it.gids
                        areGidsPerUser = it.perUser
                    }
                }
            }
            val newPermission =
                Permission(
                    newPermissionInfo,
                    true,
                    Permission.TYPE_MANIFEST,
                    packageState.appId,
                    gids,
                    areGidsPerUser
                )

            if (parsedPermission.isTree) {
                newState.mutateSystemState().mutatePermissionTrees()[permissionName] = newPermission
            } else {
                newState.mutateSystemState().mutatePermissions()[permissionName] = newPermission
                val isPermissionChanged =
                    oldPermission == null ||
                        newPackageName != oldPermission.packageName ||
                        newPermission.protectionLevel != oldPermission.protectionLevel ||
                        (oldPermission.isReconciled &&
                            ((newPermission.isSignature && isPackageSigningChanged) ||
                                (newPermission.isKnownSigner &&
                                    newPermission.knownCerts != oldPermission.knownCerts) ||
                                (newPermission.isRuntime &&
                                    newPermission.groupName != null &&
                                    newPermission.groupName != oldPermission.groupName)))
                if (isPermissionChanged) {
                    changedPermissionNames += permissionName
                }
            }
        }
    }

    private fun MutateStateScope.trimPermissions(
        packageName: String,
        changedPermissionNames: MutableIndexedSet<String>
    ) {
        val packageState = newState.externalState.packageStates[packageName]
        val androidPackage = packageState?.androidPackage
        if (packageState != null && androidPackage == null) {
            return
        }
        val disabledSystemPackage =
            newState.externalState.disabledSystemPackageStates[packageName]?.androidPackage
        // Unlike in the previous implementation, we now also retain permission trees defined by
        // disabled system packages for consistency with permissions.
        newState.systemState.permissionTrees.forEachReversedIndexed {
            permissionTreeIndex,
            permissionTreeName,
            permissionTree ->
            if (
                permissionTree.packageName == packageName &&
                    (packageState == null ||
                        androidPackage!!.permissions.noneIndexed { _, it ->
                            it.isTree && it.name == permissionTreeName
                        }) &&
                    (disabledSystemPackage?.permissions?.anyIndexed { _, it ->
                        it.isTree && it.name == permissionTreeName
                    } != true)
            ) {
                newState.mutateSystemState().mutatePermissionTrees().removeAt(permissionTreeIndex)
            }
        }

        newState.systemState.permissions.forEachReversedIndexed {
            permissionIndex,
            permissionName,
            permission ->
            val updatedPermission = updatePermissionIfDynamic(permission)
            newState
                .mutateSystemState()
                .mutatePermissions()
                .putAt(permissionIndex, updatedPermission)
            if (
                updatedPermission.packageName == packageName &&
                    (packageState == null ||
                        androidPackage!!.permissions.noneIndexed { _, it ->
                            !it.isTree && it.name == permissionName
                        }) &&
                    (disabledSystemPackage?.permissions?.anyIndexed { _, it ->
                        !it.isTree && it.name == permissionName
                    } != true)
            ) {
                // Different from the old implementation where we keep the permission state if the
                // permission is declared by a disabled system package (ag/15189282), we now
                // shouldn't be notified when the updated system package is removed but the disabled
                // system package isn't re-enabled yet, so we don't need to maintain that brittle
                // special case either.
                newState.externalState.userIds.forEachIndexed { _, userId ->
                    newState.externalState.appIdPackageNames.forEachIndexed { _, appId, _ ->
                        setPermissionFlags(appId, userId, permissionName, 0)
                    }
                }
                newState.mutateSystemState().mutatePermissions().removeAt(permissionIndex)
                changedPermissionNames += permissionName
            }
        }
    }

    private fun MutateStateScope.updatePermissionIfDynamic(permission: Permission): Permission {
        if (!permission.isDynamic) {
            return permission
        }
        val permissionTree = findPermissionTree(permission.name) ?: return permission
        @Suppress("DEPRECATION")
        return permission.copy(
            permissionInfo =
                PermissionInfo(permission.permissionInfo).apply {
                    packageName = permissionTree.packageName
                },
            appId = permissionTree.appId,
            isReconciled = true
        )
    }

    private fun MutateStateScope.trimPermissionStates(appId: Int) {
        val requestedPermissions = MutableIndexedSet<String>()
        forEachPackageInAppId(appId) {
            // Note that we still trim the permission states requested by disabled system packages.
            // Because in the previous implementation:
            // despite revokeSharedUserPermissionsForLeavingPackageInternal() retains permissions
            // requested by disabled system packages, revokeUnusedSharedUserPermissionsLocked(),
            // which is call upon app update installation, didn't do such preservation.
            // Hence, permissions only requested by disabled system packages were still trimmed in
            // the previous implementation.
            requestedPermissions += it.androidPackage!!.requestedPermissions
        }
        newState.userStates.forEachIndexed { _, userId, userState ->
            userState.appIdPermissionFlags[appId]?.forEachReversedIndexed { _, permissionName, _ ->
                if (permissionName !in requestedPermissions) {
                    setPermissionFlags(appId, userId, permissionName, 0)
                }
            }
        }
    }

    private fun MutateStateScope.revokePermissionsOnPackageUpdate(appId: Int) {
        val hasOldPackage =
            appId in oldState.externalState.appIdPackageNames &&
                anyPackageInAppId(appId, oldState) { true }
        if (!hasOldPackage) {
            // Don't revoke anything if this isn't a package update, i.e. if information about the
            // old package isn't available. Notably, this also means skipping packages changed via
            // OTA, but the revocation here is also mostly for normal apps and there's no way to get
            // information about the package before OTA anyway.
            return
        }

        // If the app is updated, and has scoped storage permissions, then it is possible that the
        // app updated in an attempt to get unscoped storage. If so, revoke all storage permissions.
        val oldTargetSdkVersion = getAppIdTargetSdkVersion(appId, null, oldState)
        val newTargetSdkVersion = getAppIdTargetSdkVersion(appId, null, newState)
        @Suppress("ConvertTwoComparisonsToRangeCheck")
        val isTargetSdkVersionDowngraded =
            oldTargetSdkVersion >= Build.VERSION_CODES.Q &&
                newTargetSdkVersion < Build.VERSION_CODES.Q
        @Suppress("ConvertTwoComparisonsToRangeCheck")
        val isTargetSdkVersionUpgraded =
            oldTargetSdkVersion < Build.VERSION_CODES.Q &&
                newTargetSdkVersion >= Build.VERSION_CODES.Q
        val oldIsRequestLegacyExternalStorage =
            anyPackageInAppId(appId, oldState) {
                it.androidPackage!!.isRequestLegacyExternalStorage
            }
        val newIsRequestLegacyExternalStorage =
            anyPackageInAppId(appId, newState) {
                it.androidPackage!!.isRequestLegacyExternalStorage
            }
        val isNewlyRequestingLegacyExternalStorage =
            !isTargetSdkVersionUpgraded &&
                !oldIsRequestLegacyExternalStorage &&
                newIsRequestLegacyExternalStorage
        val shouldRevokeStorageAndMediaPermissions =
            isNewlyRequestingLegacyExternalStorage || isTargetSdkVersionDowngraded
        if (shouldRevokeStorageAndMediaPermissions) {
            newState.userStates.forEachIndexed { _, userId, userState ->
                userState.appIdPermissionFlags[appId]?.forEachReversedIndexed {
                    _,
                    permissionName,
                    oldFlags ->
                    // Do not revoke the permission during an upgrade if it's POLICY_FIXED or
                    // SYSTEM_FIXED. Otherwise the user cannot grant back the permission.
                    if (
                        permissionName in STORAGE_AND_MEDIA_PERMISSIONS &&
                            oldFlags.hasBits(PermissionFlags.RUNTIME_GRANTED) &&
                            !oldFlags.hasAnyBit(SYSTEM_OR_POLICY_FIXED_MASK)
                    ) {
                        Slog.v(
                            LOG_TAG,
                            "Revoking storage permission: $permissionName for appId: " +
                                " $appId and user: $userId"
                        )
                        val newFlags =
                            oldFlags andInv (PermissionFlags.RUNTIME_GRANTED or USER_SETTABLE_MASK)
                        setPermissionFlags(appId, userId, permissionName, newFlags)
                    }
                }
            }
        }
    }

    private fun MutateStateScope.evaluatePermissionStateForAllPackages(
        permissionName: String,
        installedPackageState: PackageState?
    ) {
        val externalState = newState.externalState
        externalState.userIds.forEachIndexed { _, userId ->
            externalState.appIdPackageNames.forEachIndexed { _, appId, _ ->
                val isPermissionRequested =
                    anyPackageInAppId(appId) {
                        permissionName in it.androidPackage!!.requestedPermissions
                    }
                if (isPermissionRequested) {
                    evaluatePermissionState(appId, userId, permissionName, installedPackageState)
                }
            }
        }
    }

    private fun MutateStateScope.evaluateAllPermissionStatesForPackage(
        packageState: PackageState,
        installedPackageState: PackageState?
    ) {
        newState.externalState.userIds.forEachIndexed { _, userId ->
            evaluateAllPermissionStatesForPackageAndUser(
                packageState,
                userId,
                installedPackageState
            )
        }
    }

    private fun MutateStateScope.evaluateAllPermissionStatesForPackageAndUser(
        packageState: PackageState,
        userId: Int,
        installedPackageState: PackageState?
    ) {
        packageState.androidPackage?.requestedPermissions?.forEach { permissionName ->
            evaluatePermissionState(
                packageState.appId,
                userId,
                permissionName,
                installedPackageState
            )
        }
    }

    private fun MutateStateScope.evaluatePermissionState(
        appId: Int,
        userId: Int,
        permissionName: String,
        installedPackageState: PackageState?
    ) {
        val packageNames = newState.externalState.appIdPackageNames[appId]!!
        // Repeatedly checking whether a permission is requested can actually be costly, so we cache
        // the result for this method which is frequently called during boot, instead of calling
        // anyPackageInAppId() and checking requested permissions multiple times.
        val requestingPackageStates = MutableIndexedList<PackageState>()
        var hasMissingPackage = false
        packageNames.forEachIndexed { _, packageName ->
            val packageState = newState.externalState.packageStates[packageName]!!
            val androidPackage = packageState.androidPackage
            if (androidPackage != null) {
                if (permissionName in androidPackage.requestedPermissions) {
                    requestingPackageStates += packageState
                }
            } else {
                hasMissingPackage = true
            }
        }
        if (packageNames.size == 1 && hasMissingPackage) {
            // For non-shared-user packages with missing androidPackage, skip evaluation.
            return
        }
        val permission = newState.systemState.permissions[permissionName]
        val oldFlags = getPermissionFlags(appId, userId, permissionName)
        if (permission == null) {
            if (oldFlags == 0) {
                // If the permission definition is missing and we don't have any permission states
                // for this permission, add the INSTALL_REVOKED flag to ensure that we don't
                // automatically grant the permission when it's defined
                setPermissionFlags(appId, userId, permissionName, PermissionFlags.INSTALL_REVOKED)
            }
            return
        }
        if (permission.isNormal) {
            val wasGranted = oldFlags.hasBits(PermissionFlags.INSTALL_GRANTED)
            if (!wasGranted) {
                val wasRevoked = oldFlags.hasBits(PermissionFlags.INSTALL_REVOKED)
                val isRequestedByInstalledPackage =
                    installedPackageState != null &&
                        permissionName in
                            installedPackageState.androidPackage!!.requestedPermissions
                val isRequestedBySystemPackage =
                    requestingPackageStates.anyIndexed { _, it -> it.isSystem }
                val isCompatibilityPermission =
                    requestingPackageStates.anyIndexed { _, it ->
                        isCompatibilityPermissionForPackage(it.androidPackage!!, permissionName)
                    }
                // If this is an existing, non-system package,
                // then we can't add any new permissions to it.
                // Except if this is a permission that was added to the platform
                var newFlags =
                    if (
                        !wasRevoked ||
                            isRequestedByInstalledPackage ||
                            isRequestedBySystemPackage ||
                            isCompatibilityPermission
                    ) {
                        PermissionFlags.INSTALL_GRANTED
                    } else {
                        PermissionFlags.INSTALL_REVOKED
                    }
                if (permission.isAppOp) {
                    newFlags =
                        newFlags or
                            (oldFlags and (PermissionFlags.ROLE or PermissionFlags.USER_SET))
                }
                setPermissionFlags(appId, userId, permissionName, newFlags)
            }
        } else if (permission.isSignature || permission.isInternal) {
            val wasProtectionGranted = oldFlags.hasBits(PermissionFlags.PROTECTION_GRANTED)
            var newFlags =
                if (hasMissingPackage && wasProtectionGranted) {
                    // Keep the non-runtime permission grants for shared UID with missing
                    // androidPackage
                    PermissionFlags.PROTECTION_GRANTED
                } else {
                    val mayGrantByPrivileged =
                        !permission.isPrivileged ||
                            requestingPackageStates.anyIndexed { _, it ->
                                checkPrivilegedPermissionAllowlistIfNeeded(it, permission)
                            }
                    val shouldGrantBySignature =
                        permission.isSignature &&
                            requestingPackageStates.anyIndexed { _, it ->
                                shouldGrantPermissionBySignature(it, permission)
                            }
                    val shouldGrantByProtectionFlags =
                        requestingPackageStates.anyIndexed { _, it ->
                            shouldGrantPermissionByProtectionFlags(it, permission)
                        }
                    if (
                        mayGrantByPrivileged &&
                            (shouldGrantBySignature || shouldGrantByProtectionFlags)
                    ) {
                        PermissionFlags.PROTECTION_GRANTED
                    } else {
                        0
                    }
                }
            if (permission.isAppOp) {
                newFlags =
                    newFlags or (oldFlags and (PermissionFlags.ROLE or PermissionFlags.USER_SET))
            }
            // Different from the old implementation, which seemingly allows granting an
            // unallowlisted privileged permission via development or role but revokes it upon next
            // reconciliation, we now properly allows that because the privileged protection flag
            // should only affect the other static flags, but not dynamic flags like development or
            // role. This may be useful in the case of an updated system app.
            if (permission.isDevelopment) {
                newFlags = newFlags or (oldFlags and PermissionFlags.RUNTIME_GRANTED)
            }
            if (permission.isRole) {
                newFlags =
                    newFlags or
                        (oldFlags and (PermissionFlags.ROLE or PermissionFlags.RUNTIME_GRANTED))
            }
            setPermissionFlags(appId, userId, permissionName, newFlags)
        } else if (permission.isRuntime) {
            var newFlags = oldFlags and PermissionFlags.MASK_RUNTIME
            val wasRevoked = newFlags != 0 && !PermissionFlags.isPermissionGranted(newFlags)
            val targetSdkVersion =
                requestingPackageStates.reduceIndexed(Build.VERSION_CODES.CUR_DEVELOPMENT) {
                    targetSdkVersion,
                    _,
                    packageState ->
                    targetSdkVersion.coerceAtMost(packageState.androidPackage!!.targetSdkVersion)
                }
            if (targetSdkVersion < Build.VERSION_CODES.M) {
                if (permission.isRuntimeOnly) {
                    // Different from the old implementation, which simply skips a runtime-only
                    // permission, we now only allow holding on to the restriction related flags,
                    // since such flags may only be set one-time in some cases, and disallow all
                    // other flags thus keeping it revoked.
                    newFlags = newFlags and PermissionFlags.MASK_EXEMPT
                } else {
                    newFlags = newFlags or PermissionFlags.LEGACY_GRANTED
                    if (wasRevoked) {
                        newFlags = newFlags or PermissionFlags.APP_OP_REVOKED
                    }
                    // Explicitly check against the old state to determine if this permission is
                    // new.
                    val isNewPermission =
                        getOldStatePermissionFlags(appId, userId, permissionName) == 0
                    if (isNewPermission) {
                        newFlags = newFlags or PermissionFlags.IMPLICIT
                    }
                }
            } else {
                val wasGrantedByLegacy = newFlags.hasBits(PermissionFlags.LEGACY_GRANTED)
                val hasImplicitFlag = newFlags.hasBits(PermissionFlags.IMPLICIT)
                if (wasGrantedByLegacy) {
                    newFlags = newFlags andInv PermissionFlags.LEGACY_GRANTED
                    if (!hasImplicitFlag) {
                        newFlags = newFlags or PermissionFlags.RUNTIME_GRANTED
                    }
                }
                val wasGrantedByImplicit = newFlags.hasBits(PermissionFlags.IMPLICIT_GRANTED)
                val isLeanbackNotificationsPermission =
                    newState.externalState.isLeanback && permissionName in NOTIFICATIONS_PERMISSIONS
                val isImplicitPermission =
                    requestingPackageStates.anyIndexed { _, it ->
                        permissionName in it.androidPackage!!.implicitPermissions
                    }
                val sourcePermissions =
                    newState.externalState.implicitToSourcePermissions[permissionName]
                val isAnySourcePermissionNonRuntime =
                    sourcePermissions?.anyIndexed { _, sourcePermissionName ->
                        val sourcePermission =
                            newState.systemState.permissions[sourcePermissionName]
                        checkNotNull(sourcePermission) {
                            "Unknown source permission $sourcePermissionName in split permissions"
                        }
                        !sourcePermission.isRuntime
                    }
                        ?: false
                val shouldGrantByImplicit =
                    isLeanbackNotificationsPermission ||
                        (isImplicitPermission && isAnySourcePermissionNonRuntime)
                if (shouldGrantByImplicit) {
                    newFlags = newFlags or PermissionFlags.IMPLICIT_GRANTED
                    if (wasRevoked) {
                        newFlags = newFlags or PermissionFlags.APP_OP_REVOKED
                    }
                } else {
                    newFlags = newFlags andInv PermissionFlags.IMPLICIT_GRANTED
                    if (
                        (wasGrantedByLegacy || wasGrantedByImplicit) &&
                            newFlags.hasBits(PermissionFlags.APP_OP_REVOKED)
                    ) {
                        // The permission was granted from a compatibility grant or an implicit
                        // grant, however this flag might still be set if the user denied this
                        // permission in the settings. Hence upon app upgrade and when this
                        // permission is no longer LEGACY_GRANTED or IMPLICIT_GRANTED and we revoke
                        // the permission, we want to remove this flag so that the app can request
                        // the permission again.
                        newFlags =
                            newFlags andInv
                                (PermissionFlags.RUNTIME_GRANTED or PermissionFlags.APP_OP_REVOKED)
                    }
                }
                if (!isImplicitPermission && hasImplicitFlag) {
                    newFlags = newFlags andInv PermissionFlags.IMPLICIT
                    var shouldRetainAsNearbyDevices = false
                    if (permissionName in NEARBY_DEVICES_PERMISSIONS) {
                        val accessBackgroundLocationFlags =
                            getPermissionFlags(
                                appId,
                                userId,
                                Manifest.permission.ACCESS_BACKGROUND_LOCATION
                            )
                        shouldRetainAsNearbyDevices =
                            PermissionFlags.isAppOpGranted(accessBackgroundLocationFlags) &&
                                !accessBackgroundLocationFlags.hasBits(PermissionFlags.IMPLICIT)
                    }
                    val shouldRetainByMask = newFlags.hasAnyBit(SYSTEM_OR_POLICY_FIXED_MASK)
                    if (shouldRetainAsNearbyDevices || shouldRetainByMask) {
                        if (wasGrantedByImplicit) {
                            newFlags = newFlags or PermissionFlags.RUNTIME_GRANTED
                        }
                    } else {
                        newFlags =
                            newFlags andInv
                                (PermissionFlags.RUNTIME_GRANTED or
                                    PermissionFlags.USER_SET or
                                    PermissionFlags.USER_FIXED)
                    }
                }
            }

            val wasExempt = newFlags.hasAnyBit(PermissionFlags.MASK_EXEMPT)
            val wasRestricted = newFlags.hasAnyBit(PermissionFlags.MASK_RESTRICTED)
            val isExempt =
                if (permission.isHardOrSoftRestricted && !wasExempt && !wasRestricted) {
                    // All restricted permissions start as exempt. If there's an installer for the
                    // package, we will drop this UPGRADE_EXEMPT flag when we receive the
                    // onPackageInstalled() callback and set up the INSTALLER_EXEMPT flags.
                    // UPGRADE_EXEMPT is chosen instead of other flags because it is the same flag
                    // that
                    // was assigned to pre-installed apps in RuntimePermissionsUpgradeController,
                    // and to
                    // apps with missing permission state.
                    // This way we make sure both pre-installed apps, and apps updated/installed
                    // after
                    // a rollback snapshot is taken, can get the allowlist for permissions that
                    // won't be
                    // allowlisted otherwise.
                    newFlags = newFlags or PermissionFlags.UPGRADE_EXEMPT
                    true
                } else {
                    wasExempt
                }
            newFlags =
                if (permission.isHardRestricted && !isExempt) {
                    newFlags or PermissionFlags.RESTRICTION_REVOKED
                } else {
                    newFlags andInv PermissionFlags.RESTRICTION_REVOKED
                }
            newFlags =
                if (
                    permission.isSoftRestricted &&
                        !isExempt &&
                        !requestingPackageStates.anyIndexed { _, it ->
                            isSoftRestrictedPermissionExemptForPackage(
                                it,
                                targetSdkVersion,
                                permissionName
                            )
                        }
                ) {
                    newFlags or PermissionFlags.SOFT_RESTRICTED
                } else {
                    newFlags andInv PermissionFlags.SOFT_RESTRICTED
                }
            setPermissionFlags(appId, userId, permissionName, newFlags)
        } else {
            Slog.e(
                LOG_TAG,
                "Unknown protection level ${permission.protectionLevel}" +
                    "for permission ${permission.name} while evaluating permission state" +
                    "for appId $appId and userId $userId"
            )
        }
    }

    private fun MutateStateScope.inheritImplicitPermissionStates(appId: Int, userId: Int) {
        val targetSdkVersion = getAppIdTargetSdkVersion(appId, null)
        val implicitPermissions = MutableIndexedSet<String>()
        forEachPackageInAppId(appId) {
            implicitPermissions += it.androidPackage!!.implicitPermissions
        }
        implicitPermissions.forEachIndexed implicitPermissions@{ _, implicitPermissionName ->
            val implicitPermission = newState.systemState.permissions[implicitPermissionName]
            checkNotNull(implicitPermission) {
                "Unknown implicit permission $implicitPermissionName in split permissions"
            }
            if (!implicitPermission.isRuntime) {
                return@implicitPermissions
            }
            // Explicitly check against the old state to determine if this permission is new.
            val isNewPermission =
                getOldStatePermissionFlags(appId, userId, implicitPermissionName) == 0
            if (!isNewPermission) {
                return@implicitPermissions
            }
            val sourcePermissions =
                newState.externalState.implicitToSourcePermissions[implicitPermissionName]
                    ?: return@implicitPermissions
            var newFlags = getPermissionFlags(appId, userId, implicitPermissionName)
            sourcePermissions.forEachIndexed sourcePermissions@{ _, sourcePermissionName ->
                val sourcePermission = newState.systemState.permissions[sourcePermissionName]
                checkNotNull(sourcePermission) {
                    "Unknown source permission $sourcePermissionName in split permissions"
                }
                val sourceFlags = getPermissionFlags(appId, userId, sourcePermissionName)
                val isSourceGranted = PermissionFlags.isPermissionGranted(sourceFlags)
                val isNewGranted = PermissionFlags.isPermissionGranted(newFlags)
                val isGrantingNewFromRevoke = isSourceGranted && !isNewGranted
                if (isSourceGranted == isNewGranted || isGrantingNewFromRevoke) {
                    if (isGrantingNewFromRevoke) {
                        newFlags = 0
                    }
                    newFlags = newFlags or (sourceFlags and PermissionFlags.MASK_RUNTIME)
                }
            }
            if (
                targetSdkVersion >= Build.VERSION_CODES.M &&
                    implicitPermissionName in NO_IMPLICIT_FLAG_PERMISSIONS
            ) {
                newFlags = newFlags andInv PermissionFlags.IMPLICIT
            } else {
                newFlags = newFlags or PermissionFlags.IMPLICIT
            }
            setPermissionFlags(appId, userId, implicitPermissionName, newFlags)
        }
    }

    private fun isCompatibilityPermissionForPackage(
        androidPackage: AndroidPackage,
        permissionName: String
    ): Boolean {
        for (compatibilityPermission in CompatibilityPermissionInfo.COMPAT_PERMS) {
            if (
                compatibilityPermission.name == permissionName &&
                    androidPackage.targetSdkVersion < compatibilityPermission.sdkVersion
            ) {
                Slog.i(
                    LOG_TAG,
                    "Auto-granting $permissionName to old package" +
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
        // Check if the package is allowed to use this signature permission.  A package is allowed
        // to use a signature permission if:
        // - it has the same set of signing certificates as the source package
        // - or its signing certificate was rotated from the source package's certificate
        // - or its signing certificate is a previous signing certificate of the defining
        //     package, and the defining package still trusts the old certificate for permissions
        // - or it shares a common signing certificate in its lineage with the defining package,
        //     and the defining package still trusts the old certificate for permissions
        // - or it shares the above relationships with the system package
        val packageSigningDetails = packageState.androidPackage!!.signingDetails
        val sourceSigningDetails =
            newState.externalState.packageStates[permission.packageName]
                ?.androidPackage
                ?.signingDetails
        val platformSigningDetails =
            newState.externalState.packageStates[PLATFORM_PACKAGE_NAME]!!
                .androidPackage!!
                .signingDetails
        val hasCommonSigner =
            sourceSigningDetails?.hasCommonSignerWithCapability(
                packageSigningDetails,
                SigningDetails.CertCapabilities.PERMISSION
            ) == true ||
                packageSigningDetails.hasAncestorOrSelf(platformSigningDetails) ||
                platformSigningDetails.checkCapability(
                    packageSigningDetails,
                    SigningDetails.CertCapabilities.PERMISSION
                )
        if (!Flags.signaturePermissionAllowlistEnabled()) {
            return hasCommonSigner
        }
        if (!hasCommonSigner) {
            return false
        }
        // A platform signature permission also needs to be allowlisted on non-debuggable builds.
        if (permission.packageName == PLATFORM_PACKAGE_NAME) {
            val isRequestedByFactoryApp =
                if (packageState.isSystem) {
                    // For updated system applications, a signature permission still needs to be
                    // allowlisted if it wasn't requested by the original application.
                    if (packageState.isUpdatedSystemApp) {
                        val disabledSystemPackage =
                            newState.externalState.disabledSystemPackageStates[
                                    packageState.packageName]
                                ?.androidPackage
                        disabledSystemPackage != null &&
                            permission.name in disabledSystemPackage.requestedPermissions
                    } else {
                        true
                    }
                } else {
                    false
                }
            if (
                !(isRequestedByFactoryApp ||
                    getSignaturePermissionAllowlistState(packageState, permission.name) == true)
            ) {
                Slog.w(
                    LOG_TAG,
                    "Signature permission ${permission.name} for package" +
                        " ${packageState.packageName} (${packageState.path}) not in" +
                        " signature permission allowlist"
                )
                if (!Build.isDebuggable() || isSignaturePermissionAllowlistForceEnforced) {
                    return false
                }
            }
        }
        return true
    }

    private fun MutateStateScope.getSignaturePermissionAllowlistState(
        packageState: PackageState,
        permissionName: String
    ): Boolean? {
        val permissionAllowlist = newState.externalState.permissionAllowlist
        val packageName = packageState.packageName
        return when {
            packageState.isVendor || packageState.isOdm ->
                permissionAllowlist.getVendorSignatureAppAllowlistState(packageName, permissionName)
            packageState.isProduct ->
                permissionAllowlist.getProductSignatureAppAllowlistState(
                    packageName,
                    permissionName
                )
            packageState.isSystemExt ->
                permissionAllowlist.getSystemExtSignatureAppAllowlistState(
                    packageName,
                    permissionName
                )
            else ->
                permissionAllowlist.getApexSignatureAppAllowlistState(packageName, permissionName)
                    ?: permissionAllowlist.getProductSignatureAppAllowlistState(
                        packageName,
                        permissionName
                    )
                    ?: permissionAllowlist.getVendorSignatureAppAllowlistState(
                        packageName,
                        permissionName
                    )
                    ?: permissionAllowlist.getSystemExtSignatureAppAllowlistState(
                        packageName,
                        permissionName
                    )
                    ?: permissionAllowlist.getSignatureAppAllowlistState(
                        packageName,
                        permissionName
                    )
        }
    }

    /**
     * We only check privileged permission allowlist for system privileged apps. Hence, for platform
     * or for normal apps, we return true to indicate that we don't need to check the allowlist and
     * will let follow-up checks to decide whether we should grant the permission.
     *
     * @return `true`, if the permission is allowlisted for system privileged apps, or if we
     *         don't need to check the allowlist (for platform or for normal apps).
     *         `false`, if the permission is not allowlisted for system privileged apps.
     */
    private fun MutateStateScope.checkPrivilegedPermissionAllowlistIfNeeded(
        packageState: PackageState,
        permission: Permission
    ): Boolean {
        if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_DISABLE) {
            return true
        }
        if (packageState.packageName == PLATFORM_PACKAGE_NAME) {
            return true
        }
        if (!(packageState.isSystem && packageState.isPrivileged)) {
            return true
        }
        if (
            permission.packageName !in newState.externalState.privilegedPermissionAllowlistPackages
        ) {
            return true
        }
        val allowlistState = getPrivilegedPermissionAllowlistState(packageState, permission.name)
        if (allowlistState != null) {
            return allowlistState
        }
        // Updated system apps do not need to be allowlisted
        if (packageState.isUpdatedSystemApp) {
            return true
        }
        // Only enforce the privileged permission allowlist on boot
        if (!newState.externalState.isSystemReady) {
            // Apps that are in updated apex's do not need to be allowlisted
            if (!packageState.isApkInUpdatedApex) {
                Slog.w(
                    LOG_TAG,
                    "Privileged permission ${permission.name} for package" +
                        " ${packageState.packageName} (${packageState.path}) not in" +
                        " privileged permission allowlist"
                )
                if (RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE) {
                    privilegedPermissionAllowlistViolations +=
                        "${packageState.packageName}" +
                            " (${packageState.path}): ${permission.name}"
                }
            }
        }
        return !RoSystemProperties.CONTROL_PRIVAPP_PERMISSIONS_ENFORCE
    }

    /**
     * Get the whether a privileged permission is explicitly allowed or denied for a package in the
     * allowlist, or `null` if it's not in the allowlist.
     */
    private fun MutateStateScope.getPrivilegedPermissionAllowlistState(
        packageState: PackageState,
        permissionName: String
    ): Boolean? {
        val permissionAllowlist = newState.externalState.permissionAllowlist
        val apexModuleName = packageState.apexModuleName
        val packageName = packageState.packageName
        return when {
            packageState.isVendor || packageState.isOdm ->
                permissionAllowlist.getVendorPrivilegedAppAllowlistState(
                    packageName,
                    permissionName
                )
            packageState.isProduct ->
                permissionAllowlist.getProductPrivilegedAppAllowlistState(
                    packageName,
                    permissionName
                )
            packageState.isSystemExt ->
                permissionAllowlist.getSystemExtPrivilegedAppAllowlistState(
                    packageName,
                    permissionName
                )
            apexModuleName != null -> {
                val nonApexAllowlistState =
                    permissionAllowlist.getPrivilegedAppAllowlistState(packageName, permissionName)
                if (nonApexAllowlistState != null) {
                    // TODO(andreionea): Remove check as soon as all apk-in-apex
                    // permission allowlists are migrated.
                    Slog.w(
                        LOG_TAG,
                        "Package $packageName is an APK in APEX but has permission" +
                            " allowlist on the system image, please bundle the allowlist in the" +
                            " $apexModuleName APEX instead"
                    )
                }
                val apexAllowlistState =
                    permissionAllowlist.getApexPrivilegedAppAllowlistState(
                        apexModuleName,
                        packageName,
                        permissionName
                    )
                apexAllowlistState ?: nonApexAllowlistState
            }
            else -> permissionAllowlist.getPrivilegedAppAllowlistState(packageName, permissionName)
        }
    }

    // See also SoftRestrictedPermissionPolicy.mayGrantPermission()
    // Note: we need the appIdTargetSdkVersion parameter here because we are OR-ing the exempt
    // status for all packages in a shared UID, but the storage soft restriction logic needs to NOT
    // exempt when the target SDK version is low, which is the opposite of what most of our code do,
    // and thus can't check the individual package's target SDK version and rely on the OR among
    // them.
    private fun isSoftRestrictedPermissionExemptForPackage(
        packageState: PackageState,
        appIdTargetSdkVersion: Int,
        permissionName: String
    ): Boolean =
        when (permissionName) {
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE ->
                appIdTargetSdkVersion >= Build.VERSION_CODES.Q
            else -> false
        }

    private fun MutateStateScope.getAppIdTargetSdkVersion(
        appId: Int,
        permissionName: String?,
        state: AccessState = newState
    ): Int =
        reducePackageInAppId(appId, Build.VERSION_CODES.CUR_DEVELOPMENT, state) {
            targetSdkVersion,
            packageState ->
            val androidPackage = packageState.androidPackage!!
            if (permissionName == null || permissionName in androidPackage.requestedPermissions) {
                targetSdkVersion.coerceAtMost(androidPackage.targetSdkVersion)
            } else {
                targetSdkVersion
            }
        }

    private inline fun MutateStateScope.anyPackageInAppId(
        appId: Int,
        state: AccessState = newState,
        predicate: (PackageState) -> Boolean
    ): Boolean {
        val packageNames = state.externalState.appIdPackageNames[appId]!!
        return packageNames.anyIndexed { _, packageName ->
            val packageState = state.externalState.packageStates[packageName]!!
            packageState.androidPackage != null && predicate(packageState)
        }
    }

    private inline fun MutateStateScope.forEachPackageInAppId(
        appId: Int,
        state: AccessState = newState,
        action: (PackageState) -> Unit
    ) {
        val packageNames = state.externalState.appIdPackageNames[appId]!!
        packageNames.forEachIndexed { _, packageName ->
            val packageState = state.externalState.packageStates[packageName]!!
            if (packageState.androidPackage != null) {
                action(packageState)
            }
        }
    }

    // Using Int instead of <T> to avoid autoboxing, since we only have the use case for Int.
    private inline fun MutateStateScope.reducePackageInAppId(
        appId: Int,
        initialValue: Int,
        state: AccessState = newState,
        accumulator: (Int, PackageState) -> Int
    ): Int {
        val packageNames = state.externalState.appIdPackageNames[appId]!!
        return packageNames.reduceIndexed(initialValue) { value, _, packageName ->
            val packageState = state.externalState.packageStates[packageName]!!
            if (packageState.androidPackage != null) {
                accumulator(value, packageState)
            } else {
                value
            }
        }
    }

    private fun MutateStateScope.shouldGrantPermissionByProtectionFlags(
        packageState: PackageState,
        permission: Permission
    ): Boolean {
        val androidPackage = packageState.androidPackage!!
        val knownPackages = newState.externalState.knownPackages
        val packageName = packageState.packageName
        if ((permission.isPrivileged || permission.isOem) && packageState.isSystem) {
            val shouldGrant =
                if (packageState.isUpdatedSystemApp) {
                    // For updated system applications, a privileged/oem permission
                    // is granted only if it had been defined by the original application.
                    val disabledSystemPackageState =
                        newState.externalState.disabledSystemPackageStates[packageState.packageName]
                    val disabledSystemPackage = disabledSystemPackageState?.androidPackage
                    disabledSystemPackage != null &&
                        permission.name in disabledSystemPackage.requestedPermissions &&
                        shouldGrantPrivilegedOrOemPermission(disabledSystemPackageState, permission)
                } else {
                    shouldGrantPrivilegedOrOemPermission(packageState, permission)
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
        if (
            permission.isInstaller &&
                (packageName in knownPackages[KnownPackages.PACKAGE_INSTALLER]!! ||
                    packageName in knownPackages[KnownPackages.PACKAGE_PERMISSION_CONTROLLER]!!)
        ) {
            // If this permission is to be granted to the system installer and
            // this app is an installer or permission controller, then it gets the permission.
            return true
        }
        if (
            permission.isVerifier && packageName in knownPackages[KnownPackages.PACKAGE_VERIFIER]!!
        ) {
            // If this permission is to be granted to the system verifier and
            // this app is a verifier, then it gets the permission.
            return true
        }
        if (permission.isPreInstalled && packageState.isSystem) {
            // Any pre-installed system app is allowed to get this permission.
            return true
        }
        if (
            permission.isKnownSigner &&
                androidPackage.signingDetails.hasAncestorOrSelfWithDigest(permission.knownCerts)
        ) {
            // If the permission is to be granted to a known signer then check if any of this
            // app's signing certificates are in the trusted certificate digest Set.
            return true
        }
        if (
            permission.isSetup && packageName in knownPackages[KnownPackages.PACKAGE_SETUP_WIZARD]!!
        ) {
            // If this permission is to be granted to the system setup wizard and
            // this app is a setup wizard, then it gets the permission.
            return true
        }
        if (
            permission.isSystemTextClassifier &&
                packageName in knownPackages[KnownPackages.PACKAGE_SYSTEM_TEXT_CLASSIFIER]!!
        ) {
            // Special permissions for the system default text classifier.
            return true
        }
        if (
            permission.isConfigurator &&
                packageName in knownPackages[KnownPackages.PACKAGE_CONFIGURATOR]!!
        ) {
            // Special permissions for the device configurator.
            return true
        }
        if (
            permission.isIncidentReportApprover &&
                packageName in knownPackages[KnownPackages.PACKAGE_INCIDENT_REPORT_APPROVER]!!
        ) {
            // If this permission is to be granted to the incident report approver and
            // this app is the incident report approver, then it gets the permission.
            return true
        }
        if (
            permission.isAppPredictor &&
                packageName in knownPackages[KnownPackages.PACKAGE_APP_PREDICTOR]!!
        ) {
            // Special permissions for the system app predictor.
            return true
        }
        if (
            permission.isCompanion &&
                packageName in knownPackages[KnownPackages.PACKAGE_COMPANION]!!
        ) {
            // Special permissions for the system companion device manager.
            return true
        }
        if (permission.isRecents && packageName in knownPackages[KnownPackages.PACKAGE_RECENTS]!!) {
            // Special permission for the recents app.
            return true
        }
        if (permission.isModule && packageState.apexModuleName != null) {
            // Special permission granted for APKs inside APEX modules.
            return true
        }
        return false
    }

    private fun MutateStateScope.shouldGrantPrivilegedOrOemPermission(
        packageState: PackageState,
        permission: Permission
    ): Boolean {
        val permissionName = permission.name
        val packageName = packageState.packageName
        when {
            permission.isPrivileged -> {
                if (packageState.isPrivileged) {
                    // In any case, don't grant a privileged permission to privileged vendor apps,
                    // if the permission's protectionLevel does not have the extra vendorPrivileged
                    // flag.
                    if (
                        (packageState.isVendor || packageState.isOdm) &&
                            !permission.isVendorPrivileged
                    ) {
                        Slog.w(
                            LOG_TAG,
                            "Permission $permissionName cannot be granted to privileged" +
                                " vendor (or odm) app $packageName because it isn't a" +
                                " vendorPrivileged permission"
                        )
                        return false
                    }
                    return true
                }
            }
            permission.isOem -> {
                if (packageState.isOem) {
                    val allowlistState =
                        newState.externalState.permissionAllowlist.getOemAppAllowlistState(
                            packageName,
                            permissionName
                        )
                    checkNotNull(allowlistState) {
                        "OEM permission $permissionName requested by package" +
                            " $packageName must be explicitly declared granted or not"
                    }
                    return allowlistState
                }
            }
        }
        return false
    }

    override fun MutateStateScope.onSystemReady() {
        if (!privilegedPermissionAllowlistViolations.isEmpty()) {
            throw IllegalStateException(
                "Signature|privileged permissions not in privileged" +
                    " permission allowlist: $privilegedPermissionAllowlistViolations"
            )
        }
    }

    override fun BinaryXmlPullParser.parseSystemState(state: MutableAccessState) {
        with(persistence) { this@parseSystemState.parseSystemState(state) }
    }

    override fun BinaryXmlSerializer.serializeSystemState(state: AccessState) {
        with(persistence) { this@serializeSystemState.serializeSystemState(state) }
    }

    override fun BinaryXmlPullParser.parseUserState(state: MutableAccessState, userId: Int) {
        with(persistence) { this@parseUserState.parseUserState(state, userId) }
    }

    override fun BinaryXmlSerializer.serializeUserState(state: AccessState, userId: Int) {
        with(persistence) { this@serializeUserState.serializeUserState(state, userId) }
    }

    fun GetStateScope.getPermissionTrees(): IndexedMap<String, Permission> =
        state.systemState.permissionTrees

    fun GetStateScope.findPermissionTree(permissionName: String): Permission? =
        state.systemState.permissionTrees.firstNotNullOfOrNullIndexed {
            _,
            permissionTreeName,
            permissionTree ->
            if (
                permissionName.startsWith(permissionTreeName) &&
                    permissionName.length > permissionTreeName.length &&
                    permissionName[permissionTreeName.length] == '.'
            ) {
                permissionTree
            } else {
                null
            }
        }

    fun MutateStateScope.addPermissionTree(permission: Permission) {
        newState.mutateSystemState().mutatePermissionTrees()[permission.name] = permission
    }

    /** returns all permission group definitions available in the system */
    fun GetStateScope.getPermissionGroups(): IndexedMap<String, PermissionGroupInfo> =
        state.systemState.permissionGroups

    /** returns all permission definitions available in the system */
    fun GetStateScope.getPermissions(): IndexedMap<String, Permission> =
        state.systemState.permissions

    fun MutateStateScope.addPermission(
        permission: Permission,
        isSynchronousWrite: Boolean = false
    ) {
        val writeMode = if (isSynchronousWrite) WriteMode.SYNCHRONOUS else WriteMode.ASYNCHRONOUS
        newState.mutateSystemState(writeMode).mutatePermissions()[permission.name] = permission
    }

    fun MutateStateScope.removePermission(permission: Permission) {
        newState.mutateSystemState().mutatePermissions() -= permission.name
    }

    fun GetStateScope.getUidPermissionFlags(appId: Int, userId: Int): IndexedMap<String, Int>? =
        state.userStates[userId]?.appIdPermissionFlags?.get(appId)

    fun GetStateScope.getPermissionFlags(appId: Int, userId: Int, permissionName: String): Int =
        getPermissionFlags(state, appId, userId, permissionName)

    private fun MutateStateScope.getOldStatePermissionFlags(
        appId: Int,
        userId: Int,
        permissionName: String
    ): Int = getPermissionFlags(oldState, appId, userId, permissionName)

    private fun getPermissionFlags(
        state: AccessState,
        appId: Int,
        userId: Int,
        permissionName: String
    ): Int =
        state.userStates[userId]?.appIdPermissionFlags?.get(appId).getWithDefault(permissionName, 0)

    fun GetStateScope.getAllPermissionFlags(appId: Int, userId: Int): IndexedMap<String, Int>? =
        state.userStates[userId]?.appIdPermissionFlags?.get(appId)

    fun MutateStateScope.setPermissionFlags(
        appId: Int,
        userId: Int,
        permissionName: String,
        flags: Int
    ): Boolean =
        updatePermissionFlags(appId, userId, permissionName, PermissionFlags.MASK_ALL, flags)

    fun MutateStateScope.updatePermissionFlags(
        appId: Int,
        userId: Int,
        permissionName: String,
        flagMask: Int,
        flagValues: Int
    ): Boolean {
        if (userId !in newState.userStates) {
            // Despite that we check UserManagerInternal.exists() in PermissionService, we may still
            // sometimes get race conditions between that check and the actual mutateState() call.
            // This should rarely happen but at least we should not crash.
            Slog.e(LOG_TAG, "Unable to update permission flags for missing user $userId")
            return false
        }
        val oldFlags =
            newState.userStates[userId]!!
                .appIdPermissionFlags[appId]
                .getWithDefault(permissionName, 0)
        val newFlags = (oldFlags andInv flagMask) or (flagValues and flagMask)
        if (oldFlags == newFlags) {
            return false
        }
        val appIdPermissionFlags = newState.mutateUserState(userId)!!.mutateAppIdPermissionFlags()
        val permissionFlags = appIdPermissionFlags.mutateOrPut(appId) { MutableIndexedMap() }
        permissionFlags.putWithDefault(permissionName, newFlags, 0)
        if (permissionFlags.isEmpty()) {
            appIdPermissionFlags -= appId
        }
        onPermissionFlagsChangedListeners.forEachIndexed { _, it ->
            it.onPermissionFlagsChanged(appId, userId, permissionName, oldFlags, newFlags)
        }
        return true
    }

    fun addOnPermissionFlagsChangedListener(listener: OnPermissionFlagsChangedListener) {
        synchronized(onPermissionFlagsChangedListenersLock) {
            onPermissionFlagsChangedListeners = onPermissionFlagsChangedListeners + listener
        }
    }

    fun removeOnPermissionFlagsChangedListener(listener: OnPermissionFlagsChangedListener) {
        synchronized(onPermissionFlagsChangedListenersLock) {
            onPermissionFlagsChangedListeners = onPermissionFlagsChangedListeners - listener
        }
    }

    override fun migrateSystemState(state: MutableAccessState) {
        migration.migrateSystemState(state)
    }

    override fun migrateUserState(state: MutableAccessState, userId: Int) {
        migration.migrateUserState(state, userId)
    }

    override fun MutateStateScope.upgradePackageState(
        packageState: PackageState,
        userId: Int,
        version: Int
    ) {
        with(upgrade) { upgradePackageState(packageState, userId, version) }
    }

    companion object {
        private val LOG_TAG = AppIdPermissionPolicy::class.java.simpleName

        private const val PLATFORM_PACKAGE_NAME = "android"

        // A set of permissions that we don't want to revoke when they are no longer implicit.
        private val NO_IMPLICIT_FLAG_PERMISSIONS =
            indexedSetOf(
                Manifest.permission.ACCESS_MEDIA_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
            )

        private val NEARBY_DEVICES_PERMISSIONS =
            indexedSetOf(
                Manifest.permission.BLUETOOTH_ADVERTISE,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.NEARBY_WIFI_DEVICES
            )

        private val NOTIFICATIONS_PERMISSIONS = indexedSetOf(Manifest.permission.POST_NOTIFICATIONS)

        private val STORAGE_AND_MEDIA_PERMISSIONS =
            indexedSetOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.ACCESS_MEDIA_LOCATION,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
            )

        /** Mask for all permission flags that can be set by the user */
        private const val USER_SETTABLE_MASK =
            PermissionFlags.USER_SET or
                PermissionFlags.USER_FIXED or
                PermissionFlags.APP_OP_REVOKED or
                PermissionFlags.ONE_TIME or
                PermissionFlags.HIBERNATION or
                PermissionFlags.USER_SELECTED

        /**
         * Mask for all permission flags that imply we shouldn't automatically modify the permission
         * grant state.
         */
        private const val SYSTEM_OR_POLICY_FIXED_MASK =
            PermissionFlags.SYSTEM_FIXED or PermissionFlags.POLICY_FIXED
    }

    /** Listener for permission flags changes. */
    interface OnPermissionFlagsChangedListener {
        /**
         * Called when a permission flags change has been made to the upcoming new state.
         *
         * Implementations should keep this method fast to avoid stalling the locked state mutation,
         * and only call external code after [onStateMutated] when the new state has actually become
         * the current state visible to external code.
         */
        fun onPermissionFlagsChanged(
            appId: Int,
            userId: Int,
            permissionName: String,
            oldFlags: Int,
            newFlags: Int
        )

        /**
         * Called when the upcoming new state has become the current state.
         *
         * Implementations should keep this method fast to avoid stalling the locked state mutation.
         */
        fun onStateMutated()
    }
}
