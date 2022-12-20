/*
 * Copyright (C) 2022 The Android Open Source Project
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
import android.app.ActivityManager
import android.compat.annotation.ChangeId
import android.compat.annotation.EnabledAfter
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PackageManagerInternal
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.permission.SplitPermissionInfoParcelable
import android.metrics.LogMaker
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Message
import android.os.Process
import android.os.RemoteCallbackList
import android.os.RemoteException
import android.os.ServiceManager
import android.os.UserHandle
import android.os.UserManager
import android.permission.IOnPermissionsChangeListener
import android.permission.PermissionManager
import android.provider.Settings
import android.util.DebugUtils
import android.util.IntArray as GrowingIntArray
import android.util.Log
import com.android.internal.compat.IPlatformCompat
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.Preconditions
import com.android.server.FgThread
import com.android.server.LocalManagerRegistry
import com.android.server.LocalServices
import com.android.server.ServiceThread
import com.android.server.SystemConfig
import com.android.server.permission.access.AccessCheckingService
import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.PermissionUri
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.andInv
import com.android.server.permission.access.util.hasAnyBit
import com.android.server.permission.access.util.hasBits
import com.android.server.permission.access.util.withClearedCallingIdentity
import com.android.server.pm.KnownPackages
import com.android.server.pm.PackageManagerLocal
import com.android.server.pm.UserManagerInternal
import com.android.server.pm.UserManagerService
import com.android.server.pm.parsing.pkg.AndroidPackageUtils
import com.android.server.pm.permission.LegacyPermission
import com.android.server.pm.permission.LegacyPermissionSettings
import com.android.server.pm.permission.LegacyPermissionState
import com.android.server.pm.permission.PermissionManagerServiceInterface
import com.android.server.pm.permission.PermissionManagerServiceInternal
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageState
import com.android.server.policy.SoftRestrictedPermissionPolicy
import libcore.util.EmptyArray
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * Modern implementation of [PermissionManagerServiceInterface].
 */
class PermissionService(
    private val service: AccessCheckingService
) : PermissionManagerServiceInterface {
    private val policy =
        service.getSchemePolicy(UidUri.SCHEME, PermissionUri.SCHEME) as UidPermissionPolicy

    private val context = service.context
    private lateinit var metricsLogger: MetricsLogger
    private lateinit var packageManagerInternal: PackageManagerInternal
    private lateinit var packageManagerLocal: PackageManagerLocal
    private lateinit var platformCompat: IPlatformCompat
    private lateinit var systemConfig: SystemConfig
    private lateinit var userManagerInternal: UserManagerInternal
    private lateinit var userManagerService: UserManagerService

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var onPermissionsChangeListeners: OnPermissionsChangeListeners
    private lateinit var onPermissionFlagsChangedListener: OnPermissionFlagsChangedListener

    private val mountedStorageVolumes = IndexedSet<String?>()

    fun initialize() {
        metricsLogger = MetricsLogger()
        packageManagerInternal = LocalServices.getService(PackageManagerInternal::class.java)
        packageManagerLocal =
            LocalManagerRegistry.getManagerOrThrow(PackageManagerLocal::class.java)
        platformCompat = IPlatformCompat.Stub.asInterface(
            ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE)
        )
        systemConfig = SystemConfig.getInstance()
        userManagerInternal = LocalServices.getService(UserManagerInternal::class.java)
        userManagerService = UserManagerService.getInstance()

        handlerThread = ServiceThread(LOG_TAG, Process.THREAD_PRIORITY_BACKGROUND, true)
        handler = Handler(handlerThread.looper)
        onPermissionsChangeListeners = OnPermissionsChangeListeners(FgThread.get().looper)
        onPermissionFlagsChangedListener = OnPermissionFlagsChangedListener()
        policy.addOnPermissionFlagsChangedListener(onPermissionFlagsChangedListener)
    }

    override fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo> {
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            val callingUid = Binder.getCallingUid()
            if (snapshot.isUidInstantApp(callingUid)) {
                return emptyList()
            }

            val permissionGroups = service.getState {
                with(policy) { getPermissionGroups() }
            }

            return permissionGroups.mapNotNullIndexed { _, _, permissionGroup ->
                if (snapshot.isPackageVisibleToUid(permissionGroup.packageName, callingUid)) {
                    permissionGroup.generatePermissionGroupInfo(flags)
                } else {
                    null
                }
            }
        }
    }

    override fun getPermissionGroupInfo(
        permissionGroupName: String,
        flags: Int
    ): PermissionGroupInfo? {
        val permissionGroup: PermissionGroupInfo
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            val callingUid = Binder.getCallingUid()
            if (snapshot.isUidInstantApp(callingUid)) {
                return null
            }

            permissionGroup = service.getState {
                with(policy) { getPermissionGroups()[permissionGroupName] }
            } ?: return null

            val isPermissionGroupVisible =
                snapshot.isPackageVisibleToUid(permissionGroup.packageName, callingUid)
            if (!isPermissionGroupVisible) {
                return null
            }
        }

        return permissionGroup.generatePermissionGroupInfo(flags)
    }

    /**
     * Generate a new [PermissionGroupInfo] from [PermissionGroupInfo] and adjust it accordingly.
     */
    private fun PermissionGroupInfo.generatePermissionGroupInfo(flags: Int): PermissionGroupInfo =
        @Suppress("DEPRECATION")
        PermissionGroupInfo(this).apply {
            if (!flags.hasBits(PackageManager.GET_META_DATA)) {
                metaData = null
            }
        }

    override fun getPermissionInfo(
        permissionName: String,
        flags: Int,
        opPackageName: String
    ): PermissionInfo? {
        val permission: Permission
        val targetSdkVersion: Int
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            val callingUid = Binder.getCallingUid()
            if (snapshot.isUidInstantApp(callingUid)) {
                return null
            }

            permission = service.getState {
                with(policy) { getPermissions()[permissionName] }
            } ?: return null

            val isPermissionVisible =
                snapshot.isPackageVisibleToUid(permission.packageName, callingUid)
            if (!isPermissionVisible) {
                return null
            }

            val opPackage = snapshot.getPackageState(opPackageName)?.androidPackage
            targetSdkVersion = when {
                // System sees all flags.
                isRootOrSystemOrShell(callingUid) -> Build.VERSION_CODES.CUR_DEVELOPMENT
                opPackage != null -> opPackage.targetSdkVersion
                else -> Build.VERSION_CODES.CUR_DEVELOPMENT
            }
        }

        return permission.generatePermissionInfo(flags, targetSdkVersion)
    }

    /**
     * Generate a new [PermissionInfo] from [Permission] and adjust it accordingly.
     */
    private fun Permission.generatePermissionInfo(
        flags: Int,
        targetSdkVersion: Int = Build.VERSION_CODES.CUR_DEVELOPMENT
    ): PermissionInfo =
        @Suppress("DEPRECATION")
        PermissionInfo(permissionInfo).apply {
            if (!flags.hasBits(PackageManager.GET_META_DATA)) {
                metaData = null
            }
            if (targetSdkVersion < Build.VERSION_CODES.O) {
                val protection = protection
                // Signature permission's protection flags are always reported.
                if (protection != PermissionInfo.PROTECTION_SIGNATURE) {
                    protectionLevel = protection
                }
            }
        }

    override fun queryPermissionsByGroup(
        permissionGroupName: String?,
        flags: Int
    ): List<PermissionInfo>? {
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            val callingUid = Binder.getCallingUid()
            if (snapshot.isUidInstantApp(callingUid)) {
                return null
            }

            val permissions: IndexedMap<String, Permission>
            service.getState {
                if (permissionGroupName != null) {
                    val permissionGroup =
                        with(policy) { getPermissionGroups()[permissionGroupName] } ?: return null

                    if (!snapshot.isPackageVisibleToUid(permissionGroup.packageName, callingUid)) {
                        return null
                    }
                }

                permissions = with(policy) { getPermissions() }
            }

            return permissions.mapNotNullIndexed { _, _, permission ->
                if (permission.groupName == permissionGroupName &&
                    snapshot.isPackageVisibleToUid(permission.packageName, callingUid)
                ) {
                    permission.generatePermissionInfo(flags)
                } else {
                    null
                }
            }
        }
    }

    override fun getAllPermissionsWithProtection(protection: Int): List<PermissionInfo> {
        TODO("Not yet implemented")
    }

    override fun getAllPermissionsWithProtectionFlags(protectionFlags: Int): List<PermissionInfo> {
        TODO("Not yet implemented")
    }

    override fun getPermissionGids(permissionName: String, userId: Int): IntArray {
        val permission = service.getState {
            with(policy) { getPermissions()[permissionName] }
        } ?: return EmptyArray.INT
        return permission.getGidsForUser(userId)
    }

    override fun addPermission(permissionInfo: PermissionInfo, async: Boolean): Boolean {
        TODO("Not yet implemented")
    }

    override fun removePermission(permissionName: String) {
        TODO("Not yet implemented")
    }

    override fun checkUidPermission(uid: Int, permissionName: String): Int {
        val userId = UserHandle.getUserId(uid)
        if (!userManagerInternal.exists(userId)) {
            return PackageManager.PERMISSION_DENIED
        }

        // PackageManagerInternal.getPackage(int) already checks package visibility and enforces
        // that instant apps can't see shared UIDs. Note that on the contrary,
        // Note that PackageManagerInternal.getPackage(String) doesn't perform any checks.
        val androidPackage = packageManagerInternal.getPackage(uid)
        if (androidPackage != null) {
            // Note that PackageManagerInternal.getPackageStateInternal() is not filtered.
            val packageState =
                packageManagerInternal.getPackageStateInternal(androidPackage.packageName)
            if (packageState == null) {
                Log.e(
                    LOG_TAG, "checkUidPermission: PackageState not found for AndroidPackage" +
                        " $androidPackage"
                )
                return PackageManager.PERMISSION_DENIED
            }
            val isPermissionGranted = service.getState {
                isPermissionGranted(packageState, userId, permissionName)
            }
            return if (isPermissionGranted) {
                PackageManager.PERMISSION_GRANTED
            } else {
                PackageManager.PERMISSION_DENIED
            }
        }

        return if (isSystemUidPermissionGranted(uid, permissionName)) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }
    }

    /**
     * Internal implementation that should only be called by [checkUidPermission].
     */
    private fun isSystemUidPermissionGranted(uid: Int, permissionName: String): Boolean {
        val uidPermissions = systemConfig.systemPermissions[uid] ?: return false
        if (permissionName in uidPermissions) {
            return true
        }

        val fullerPermissionName = FULLER_PERMISSIONS[permissionName]
        if (fullerPermissionName != null && fullerPermissionName in uidPermissions) {
            return true
        }

        return false
    }

    override fun checkPermission(packageName: String, permissionName: String, userId: Int): Int {
        if (!userManagerInternal.exists(userId)) {
            return PackageManager.PERMISSION_DENIED
        }

        val packageState = packageManagerLocal.withFilteredSnapshot(Binder.getCallingUid(), userId)
            .use { it.getPackageState(packageName) } ?: return PackageManager.PERMISSION_DENIED

        val isPermissionGranted = service.getState {
            isPermissionGranted(packageState, userId, permissionName)
        }
        return if (isPermissionGranted) {
            PackageManager.PERMISSION_GRANTED
        } else {
            PackageManager.PERMISSION_DENIED
        }
    }

    /**
     * Check whether a permission is granted, without any validation on caller.
     *
     * This method should always be called for checking whether a permission is granted, instead of
     * reading permission flags directly from the policy.
     */
    private fun GetStateScope.isPermissionGranted(
        packageState: PackageState,
        userId: Int,
        permissionName: String
    ): Boolean {
        val appId = packageState.appId
        // Note that instant apps can't have shared UIDs, so we only need to check the current
        // package state.
        val isInstantApp = packageState.getUserStateOrDefault(userId).isInstantApp
        if (isSinglePermissionGranted(appId, userId, isInstantApp, permissionName)) {
            return true
        }

        val fullerPermissionName = FULLER_PERMISSIONS[permissionName]
        if (fullerPermissionName != null &&
            isSinglePermissionGranted(appId, userId, isInstantApp, fullerPermissionName)) {
            return true
        }

        return false
    }

    /**
     * Internal implementation that should only be called by [isPermissionGranted].
     */
    private fun GetStateScope.isSinglePermissionGranted(
        appId: Int,
        userId: Int,
        isInstantApp: Boolean,
        permissionName: String
    ): Boolean {
        val flags = with(policy) { getPermissionFlags(appId, userId, permissionName) }
        if (!PermissionFlags.isPermissionGranted(flags)) {
            return false
        }

        if (isInstantApp) {
            val permission = with(policy) { getPermissions()[permissionName] } ?: return false
            if (!permission.isInstant) {
                return false
            }
        }

        return true
    }

    override fun getGrantedPermissions(packageName: String, userId: Int): Set<String> {
        requireNotNull(packageName) { "packageName cannot be null" }
        Preconditions.checkArgumentNonnegative(userId, "userId")

        val packageState = packageManagerLocal.withUnfilteredSnapshot()
            .use { it.getPackageState(packageName) }
        if (packageState == null) {
            Log.w(LOG_TAG, "getGrantedPermissions: Unknown package $packageName")
            return emptySet()
        }

        service.getState {
            val permissionFlags = with(policy) { getUidPermissionFlags(packageState.appId, userId) }
                ?: return emptySet()

            return permissionFlags.mapNotNullIndexedToSet { _, permissionName, _ ->
                if (isPermissionGranted(packageState, userId, permissionName)) {
                    permissionName
                } else {
                    null
                }
            }
        }
    }

    override fun getGidsForUid(uid: Int): IntArray {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val globalGids = systemConfig.globalGids
        service.getState {
            // Different from the old implementation, which returns an empty array when the
            // permission state is not found, now we always return at least global GIDs. This is
            // more consistent with the pre-S-refactor behavior. This is also because we are now
            // actively trimming the per-UID objects when empty.
            val permissionFlags = with(policy) { getUidPermissionFlags(appId, userId) }
                ?: return globalGids.clone()

            val gids = GrowingIntArray.wrap(globalGids)
            permissionFlags.forEachIndexed { _, permissionName, flags ->
                if (!PermissionFlags.isPermissionGranted(flags)) {
                    return@forEachIndexed
                }

                val permission = with(policy) { getPermissions()[permissionName] }
                    ?: return@forEachIndexed
                val permissionGids = permission.getGidsForUser(userId)
                if (permissionGids.isEmpty()) {
                    return@forEachIndexed
                }
                gids.addAll(permissionGids)
            }
            return gids.toArray()
        }
    }

    override fun grantRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        setRuntimePermissionGranted(packageName, userId, permissionName, isGranted = true)
    }

    override fun revokeRuntimePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
        reason: String?
    ) {
        setRuntimePermissionGranted(
            packageName, userId, permissionName, isGranted = false, revokeReason = reason
        )
    }

    override fun revokePostNotificationPermissionWithoutKillForTest(
        packageName: String,
        userId: Int
    ) {
        setRuntimePermissionGranted(
            packageName, userId, Manifest.permission.POST_NOTIFICATIONS, isGranted = false,
            skipKillUid = true
        )
    }

    /**
     * Shared internal implementation that should only be called by [grantRuntimePermission],
     * [revokeRuntimePermission] and [revokePostNotificationPermissionWithoutKillForTest].
     */
    private fun setRuntimePermissionGranted(
        packageName: String,
        userId: Int,
        permissionName: String,
        isGranted: Boolean,
        skipKillUid: Boolean = false,
        revokeReason: String? = null
    ) {
        val methodName = if (isGranted) "grantRuntimePermission" else "revokeRuntimePermission"
        val callingUid = Binder.getCallingUid()
        val isDebugEnabled = if (isGranted) {
            PermissionManager.DEBUG_TRACE_GRANTS
        } else {
            PermissionManager.DEBUG_TRACE_PERMISSION_UPDATES
        }
        if (isDebugEnabled &&
            PermissionManager.shouldTraceGrant(packageName, permissionName, userId)) {
            val callingUidName = packageManagerInternal.getNameForUid(callingUid)
            Log.i(
                LOG_TAG, "$methodName(packageName = $packageName," +
                    " permissionName = $permissionName" +
                    (if (isGranted) "" else "skipKillUid = $skipKillUid, reason = $revokeReason") +
                    ", userId = $userId," + " callingUid = $callingUidName ($callingUid))",
                RuntimeException()
            )
        }

        enforceCallingOrSelfCrossUserPermission(
            userId, enforceFullPermission = true, enforceShellRestriction = true, methodName
        )
        val enforcedPermissionName = if (isGranted) {
            Manifest.permission.GRANT_RUNTIME_PERMISSIONS
        } else {
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
        }
        context.enforceCallingOrSelfPermission(enforcedPermissionName, methodName)

        if (!userManagerInternal.exists(userId)) {
            Log.w(LOG_TAG, "$methodName: Unknown user $userId")
            return
        }

        val packageState: PackageState?
        val permissionControllerPackageName = packageManagerInternal.getKnownPackageNames(
            KnownPackages.PACKAGE_PERMISSION_CONTROLLER, UserHandle.USER_SYSTEM
        ).first()
        val permissionControllerPackageState: PackageState?
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            packageState = snapshot.filtered(callingUid, userId)
                .use { it.getPackageState(packageName) }
            permissionControllerPackageState =
                snapshot.getPackageState(permissionControllerPackageName)
        }
        val androidPackage = packageState?.androidPackage
        // Different from the old implementation, which returns when package doesn't exist but
        // throws when package exists but isn't visible, we now return in both cases to avoid
        // leaking the package existence.
        if (androidPackage == null) {
            Log.w(LOG_TAG, "$methodName: Unknown package $packageName")
            return
        }

        val canManageRolePermission = isRootOrSystem(callingUid) ||
            UserHandle.getAppId(callingUid) == permissionControllerPackageState!!.appId
        val overridePolicyFixed = context.checkCallingOrSelfPermission(
            Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY
        ) == PackageManager.PERMISSION_GRANTED

        service.mutateState {
            with(onPermissionFlagsChangedListener) {
                if (skipKillUid) {
                    skipKillRuntimePermissionRevokedUids()
                }
                if (revokeReason != null) {
                    addKillRuntimePermissionRevokedUidsReason(revokeReason)
                }
            }

            setRuntimePermissionGranted(
                packageState, userId, permissionName, isGranted, canManageRolePermission,
                overridePolicyFixed, reportError = true, methodName
            )
        }
    }

    private fun grantRequestedRuntimePermissions(
        packageState: PackageState,
        userId: Int,
        permissionNames: List<String>
    ) {
        service.mutateState {
            permissionNames.forEachIndexed { _, permissionName ->
                setRuntimePermissionGranted(
                    packageState, userId, permissionName, isGranted = true,
                    canManageRolePermission = false, overridePolicyFixed = false,
                    reportError = false, "grantRequestedRuntimePermissions"
                )
            }
        }
    }

    /**
     * Set whether a runtime permission is granted, without any validation on caller.
     */
    private fun MutateStateScope.setRuntimePermissionGranted(
        packageState: PackageState,
        userId: Int,
        permissionName: String,
        isGranted: Boolean,
        canManageRolePermission: Boolean,
        overridePolicyFixed: Boolean,
        reportError: Boolean,
        methodName: String
    ) {
        val permission = with(policy) { getPermissions()[permissionName] }
        if (permission == null) {
            if (reportError) {
                throw IllegalArgumentException("Unknown permission $permissionName")
            }
            return
        }

        val androidPackage = packageState.androidPackage!!
        val packageName = packageState.packageName
        when {
            permission.isDevelopment -> {}
            permission.isRole -> {
                if (!canManageRolePermission) {
                    if (reportError) {
                        throw SecurityException("Permission $permissionName is managed by role")
                    }
                    return
                }
            }
            permission.isRuntime -> {
                if (androidPackage.targetSdkVersion < Build.VERSION_CODES.M) {
                    // If a permission review is required for legacy apps we represent
                    // their permissions as always granted
                    return
                }
                if (isGranted && packageState.getUserStateOrDefault(userId).isInstantApp &&
                    !permission.isInstant) {
                    if (reportError) {
                        throw SecurityException(
                            "Cannot grant non-instant permission $permissionName to package" +
                                " $packageName"
                        )
                    }
                    return
                }
            }
            else -> {
                if (reportError) {
                    throw SecurityException(
                        "Permission $permissionName requested by package $packageName is not a" +
                            " changeable permission type"
                    )
                }
                return
            }
        }

        val appId = packageState.appId
        val oldFlags = with(policy) { getPermissionFlags(appId, userId, permissionName) }

        if (permissionName !in androidPackage.requestedPermissions && oldFlags == 0) {
            if (reportError) {
                throw SecurityException(
                    "Permission $permissionName isn't requested by package $packageName"
                )
            }
            return
        }

        if (oldFlags.hasBits(PermissionFlags.SYSTEM_FIXED)) {
            if (reportError) {
                Log.e(
                    LOG_TAG, "$methodName: Cannot change system fixed permission $permissionName" +
                        " for package $packageName"
                )
            }
            return
        }

        if (oldFlags.hasBits(PermissionFlags.POLICY_FIXED) && !overridePolicyFixed) {
            if (reportError) {
                Log.e(
                    LOG_TAG, "$methodName: Cannot change policy fixed permission $permissionName" +
                        " for package $packageName"
                )
            }
            return
        }

        if (isGranted && oldFlags.hasBits(PermissionFlags.RESTRICTION_REVOKED)) {
            if (reportError) {
                Log.e(
                    LOG_TAG, "$methodName: Cannot grant hard-restricted non-exempt permission" +
                        " $permissionName to package $packageName"
                )
            }
            return
        }

        if (isGranted && oldFlags.hasBits(PermissionFlags.SOFT_RESTRICTED)) {
            // TODO: Refactor SoftRestrictedPermissionPolicy.
            val softRestrictedPermissionPolicy = SoftRestrictedPermissionPolicy.forPermission(
                context, AndroidPackageUtils.generateAppInfoWithoutState(androidPackage),
                androidPackage, UserHandle.of(userId), permissionName
            )
            if (!softRestrictedPermissionPolicy.mayGrantPermission()) {
                if (reportError) {
                    Log.e(
                        LOG_TAG, "$methodName: Cannot grant soft-restricted non-exempt permission" +
                            " $permissionName to package $packageName"
                    )
                }
                return
            }
        }

        val newFlags = PermissionFlags.updateRuntimePermissionGranted(oldFlags, isGranted)
        if (oldFlags == newFlags) {
            return
        }

        with(policy) { setPermissionFlags(appId, userId, permissionName, newFlags) }

        if (permission.isRuntime) {
            val action = if (isGranted) {
                MetricsProto.MetricsEvent.ACTION_PERMISSION_GRANTED
            } else {
                MetricsProto.MetricsEvent.ACTION_PERMISSION_REVOKED
            }
            val log = LogMaker(action).apply {
                setPackageName(packageName)
                addTaggedData(MetricsProto.MetricsEvent.FIELD_PERMISSION, permissionName)
            }
            metricsLogger.write(log)
        }
    }

    override fun getPermissionFlags(packageName: String, permissionName: String, userId: Int): Int {
        enforceCallingOrSelfCrossUserPermission(
            userId, enforceFullPermission = true, enforceShellRestriction = false,
            "getPermissionFlags"
        )
        enforceCallingOrSelfAnyPermission(
            "getPermissionFlags", Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
            Manifest.permission.GET_RUNTIME_PERMISSIONS
        )

        if (!userManagerInternal.exists(userId)) {
            Log.w(LOG_TAG, "getPermissionFlags: Unknown user $userId")
            return 0
        }

        val packageState = packageManagerLocal.withFilteredSnapshot()
            .use { it.getPackageState(packageName) }
        if (packageState == null) {
            Log.w(LOG_TAG, "getPermissionFlags: Unknown package $packageName")
            return 0
        }

        service.getState {
            val permission = with(policy) { getPermissions()[permissionName] }
            if (permission == null) {
                Log.w(LOG_TAG, "getPermissionFlags: Unknown permission $permissionName")
                return 0
            }

            val flags =
                with(policy) { getPermissionFlags(packageState.appId, userId, permissionName) }
            return PermissionFlags.toApiFlags(flags)
        }
    }

    override fun isPermissionRevokedByPolicy(
        packageName: String,
        permissionName: String,
        userId: Int
    ): Boolean {
        enforceCallingOrSelfCrossUserPermission(
            userId, enforceFullPermission = true, enforceShellRestriction = false,
            "isPermissionRevokedByPolicy"
        )

        if (!userManagerInternal.exists(userId)) {
            Log.w(LOG_TAG, "isPermissionRevokedByPolicy: Unknown user $userId")
            return false
        }

        val packageState = packageManagerLocal.withFilteredSnapshot(Binder.getCallingUid(), userId)
            .use { it.getPackageState(packageName) } ?: return false

        service.getState {
            if (isPermissionGranted(packageState, userId, permissionName)) {
                return false
            }

            val flags = with(policy) {
                getPermissionFlags(packageState.appId, userId, permissionName)
            }
            return flags.hasBits(PermissionFlags.POLICY_FIXED)
        }
    }

    override fun isPermissionsReviewRequired(packageName: String, userId: Int): Boolean {
        requireNotNull(packageName) { "packageName cannot be null" }
        // TODO(b/173235285): Some caller may pass USER_ALL as userId.
        // Preconditions.checkArgumentNonnegative(userId, "userId")

        val packageState = packageManagerLocal.withUnfilteredSnapshot()
            .use { it.getPackageState(packageName) } ?: return false

        val permissionFlags = service.getState {
            with(policy) { getUidPermissionFlags(packageState.appId, userId) }
        } ?: return false
        return permissionFlags.anyIndexed { _, _, it -> it.hasBits(REVIEW_REQUIRED_FLAGS) }
    }

    override fun shouldShowRequestPermissionRationale(
        packageName: String,
        permissionName: String,
        userId: Int
    ): Boolean {
        enforceCallingOrSelfCrossUserPermission(
            userId, enforceFullPermission = true, enforceShellRestriction = false,
            "shouldShowRequestPermissionRationale"
        )

        if (!userManagerInternal.exists(userId)) {
            Log.w(LOG_TAG, "shouldShowRequestPermissionRationale: Unknown user $userId")
            return false
        }

        val callingUid = Binder.getCallingUid()
        val packageState = packageManagerLocal.withFilteredSnapshot(callingUid, userId)
            .use { it.getPackageState(packageName) } ?: return false
        val appId = packageState.appId
        if (UserHandle.getAppId(callingUid) != appId) {
            return false
        }

        val flags: Int
        service.getState {
            if (isPermissionGranted(packageState, userId, permissionName)) {
                return false
            }

            flags = with(policy) { getPermissionFlags(appId, userId, permissionName) }
        }
        if (flags.hasAnyBit(UNREQUESTABLE_MASK)) {
            return false
        }

        if (permissionName == Manifest.permission.ACCESS_BACKGROUND_LOCATION) {
            val isBackgroundRationaleChangeEnabled = Binder::class.withClearedCallingIdentity {
                try {
                    platformCompat.isChangeEnabledByPackageName(
                        BACKGROUND_RATIONALE_CHANGE_ID, packageName, userId
                    )
                } catch (e: RemoteException) {
                    Log.e(LOG_TAG, "shouldShowRequestPermissionRationale: Unable to check if" +
                        " compatibility change is enabled", e)
                    false
                }
            }
            if (isBackgroundRationaleChangeEnabled) {
                return true
            }
        }

        return flags.hasBits(PermissionFlags.USER_SET)
    }

    override fun updatePermissionFlags(
        packageName: String,
        permissionName: String,
        flagMask: Int,
        flagValues: Int,
        enforceAdjustPolicyPermission: Boolean,
        userId: Int
    ) {
        val callingUid = Binder.getCallingUid()
        if (PermissionManager.DEBUG_TRACE_PERMISSION_UPDATES &&
            PermissionManager.shouldTraceGrant(packageName, permissionName, userId)) {
            val flagMaskString = DebugUtils.flagsToString(
                PackageManager::class.java, "FLAG_PERMISSION_", flagMask.toLong()
            )
            val flagValuesString = DebugUtils.flagsToString(
                PackageManager::class.java, "FLAG_PERMISSION_", flagValues.toLong()
            )
            val callingUidName = packageManagerInternal.getNameForUid(callingUid)
            Log.i(
                LOG_TAG, "updatePermissionFlags(packageName = $packageName," +
                    " permissionName = $permissionName, flagMask = $flagMaskString," +
                    " flagValues = $flagValuesString, userId = $userId," +
                    " callingUid = $callingUidName ($callingUid))", RuntimeException()
            )
        }

        enforceCallingOrSelfCrossUserPermission(
            userId, enforceFullPermission = true, enforceShellRestriction = true,
            "updatePermissionFlags"
        )
        enforceCallingOrSelfAnyPermission(
            "updatePermissionFlags", Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
        )

        // Different from the old implementation, which implicitly didn't allow modifying the
        // POLICY_FIXED flag if the caller is system or root UID, now we do allow that since system
        // and root UIDs are supposed to have all permissions including
        // ADJUST_RUNTIME_PERMISSIONS_POLICY.
        if (!isRootOrSystem(callingUid)) {
            if (flagMask.hasBits(PackageManager.FLAG_PERMISSION_POLICY_FIXED)) {
                if (enforceAdjustPolicyPermission) {
                    context.enforceCallingOrSelfPermission(
                        Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY,
                        "Need ${Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY} to change" +
                            " policy flags"
                    )
                } else {
                    val targetSdkVersion = packageManagerInternal.getUidTargetSdkVersion(callingUid)
                    require(targetSdkVersion < Build.VERSION_CODES.Q) {
                        "${Manifest.permission.ADJUST_RUNTIME_PERMISSIONS_POLICY} needs to be" +
                            " checked for packages targeting ${Build.VERSION_CODES.Q} or later" +
                            " when changing policy flags"
                    }
                }
            }
        }

        if (!userManagerInternal.exists(userId)) {
            Log.w(LOG_TAG, "updatePermissionFlags: Unknown user $userId")
            return
        }

        // Using PackageManagerInternal instead of PackageManagerLocal for now due to need to access
        // shared user packages.
        // TODO: We probably shouldn't check the share user packages, since the package name is
        //  explicitly provided and grantRuntimePermission() isn't checking shared user packages
        //  anyway.
        val packageState = packageManagerInternal.getPackageStateInternal(packageName)
        val androidPackage = packageState?.androidPackage
        // Different from the old implementation, which returns when package doesn't exist but
        // throws when package exists but isn't visible, we now return in both cases to avoid
        // leaking the package existence.
        if (androidPackage == null ||
            packageManagerInternal.filterAppAccess(packageName, callingUid, userId, false)) {
            Log.w(LOG_TAG, "updatePermissionFlags: Unknown package $packageName")
            return
        }

        val isPermissionRequested = if (permissionName in androidPackage.requestedPermissions) {
            // Fast path, the current package has requested the permission.
            true
        } else {
            // Slow path, go through all shared user packages.
            val sharedUserPackageNames =
                packageManagerInternal.getSharedUserPackagesForPackage(packageName, userId)
            sharedUserPackageNames.any { sharedUserPackageName ->
                val sharedUserPackage = packageManagerInternal.getPackage(sharedUserPackageName)
                sharedUserPackage != null &&
                    permissionName in sharedUserPackage.requestedPermissions
            }
        }

        val appId = packageState.appId
        service.mutateState {
            updatePermissionFlags(
                appId, userId, permissionName, flagMask, flagValues,
                reportErrorForUnknownPermission = true, isPermissionRequested,
                "updatePermissionFlags", packageName
            )
        }
    }

    override fun updatePermissionFlagsForAllApps(flagMask: Int, flagValues: Int, userId: Int) {
        val callingUid = Binder.getCallingUid()
        if (PermissionManager.DEBUG_TRACE_PERMISSION_UPDATES) {
            val flagMaskString = DebugUtils.flagsToString(
                PackageManager::class.java, "FLAG_PERMISSION_", flagMask.toLong()
            )
            val flagValuesString = DebugUtils.flagsToString(
                PackageManager::class.java, "FLAG_PERMISSION_", flagValues.toLong()
            )
            val callingUidName = packageManagerInternal.getNameForUid(callingUid)
            Log.i(
                LOG_TAG, "updatePermissionFlagsForAllApps(flagMask = $flagMaskString," +
                    " flagValues = $flagValuesString, userId = $userId," +
                    " callingUid = $callingUidName ($callingUid))", RuntimeException()
            )
        }

        enforceCallingOrSelfCrossUserPermission(
            userId, enforceFullPermission = true, enforceShellRestriction = true,
            "updatePermissionFlagsForAllApps"
        )
        enforceCallingOrSelfAnyPermission(
            "updatePermissionFlagsForAllApps", Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
        )

        if (!userManagerInternal.exists(userId)) {
            Log.w(LOG_TAG, "updatePermissionFlagsForAllApps: Unknown user $userId")
            return
        }

        val packageStates = packageManagerLocal.withUnfilteredSnapshot()
            .use { it.packageStates }
        service.mutateState {
            packageStates.forEach { (packageName, packageState) ->
                val androidPackage = packageState.androidPackage ?: return@forEach
                androidPackage.requestedPermissions.forEach { permissionName ->
                    // Different from the old implementation, which only sanitized the SYSTEM_FIXED
                    // flag, we now properly sanitize all flags as in updatePermissionFlags().
                    updatePermissionFlags(
                        packageState.appId, userId, permissionName, flagMask, flagValues,
                        reportErrorForUnknownPermission = false, isPermissionRequested = true,
                        "updatePermissionFlagsForAllApps", packageName
                    )
                }
            }
        }
    }

    /**
     * Shared internal implementation that should only be called by [updatePermissionFlags] and
     * [updatePermissionFlagsForAllApps].
     */
    private fun MutateStateScope.updatePermissionFlags(
        appId: Int,
        userId: Int,
        permissionName: String,
        flagMask: Int,
        flagValues: Int,
        reportErrorForUnknownPermission: Boolean,
        isPermissionRequested: Boolean,
        methodName: String,
        packageName: String
    ) {
        // Different from the old implementation, which only allowed the system UID to modify the
        // following flags, we now allow the root UID as well since both should have all
        // permissions.
        // Only the system can change these flags and nothing else.
        val callingUid = Binder.getCallingUid()
        @Suppress("NAME_SHADOWING")
        var flagMask = flagMask
        @Suppress("NAME_SHADOWING")
        var flagValues = flagValues
        if (!isRootOrSystem(callingUid)) {
            // Different from the old implementation, which allowed non-system UIDs to remove (but
            // not add) permission restriction flags, we now consistently ignore them altogether.
            val ignoredMask = PackageManager.FLAG_PERMISSION_SYSTEM_FIXED or
                PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT or
                // REVIEW_REQUIRED can be set on any permission by the shell, or by any app for the
                // NOTIFICATIONS permissions specifically.
                if (isShell(callingUid) || permissionName in NOTIFICATIONS_PERMISSIONS) {
                    0
                } else {
                    PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED
                } or PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT or
                PackageManager.FLAG_PERMISSION_RESTRICTION_INSTALLER_EXEMPT or
                PackageManager.FLAG_PERMISSION_RESTRICTION_UPGRADE_EXEMPT or
                PackageManager.FLAG_PERMISSION_APPLY_RESTRICTION
            flagMask = flagMask andInv ignoredMask
            flagValues = flagValues andInv ignoredMask
        }

        val permission = with(policy) { getPermissions()[permissionName] }
        if (permission == null) {
            if (reportErrorForUnknownPermission) {
                throw IllegalArgumentException("Unknown permission $permissionName")
            }
            return
        }

        val oldFlags = with(policy) { getPermissionFlags(appId, userId, permissionName) }
        if (!isPermissionRequested && oldFlags == 0) {
            Log.w(
                LOG_TAG, "$methodName: Permission $permissionName isn't requested by package" +
                    " $packageName"
            )
            return
        }

        val newFlags = PermissionFlags.updateFlags(permission, oldFlags, flagMask, flagValues)
        with(policy) { setPermissionFlags(appId, userId, permissionName, newFlags) }
    }

    override fun addAllowlistedRestrictedPermission(
        packageName: String,
        permissionName: String,
        flags: Int,
        userId: Int
    ): Boolean {
        TODO("Not yet implemented")
    }

    override fun getAllowlistedRestrictedPermissions(
        packageName: String,
        flags: Int,
        userId: Int
    ): MutableList<String> {
        TODO("Not yet implemented")
    }

    override fun removeAllowlistedRestrictedPermission(
        packageName: String,
        permissionName: String,
        flags: Int,
        userId: Int
    ): Boolean {
        TODO("Not yet implemented")
    }

    override fun resetRuntimePermissions(androidPackage: AndroidPackage, userId: Int) {
        TODO("Not yet implemented")
    }

    override fun resetRuntimePermissionsForUser(userId: Int) {
        TODO("Not yet implemented")
    }

    override fun addOnPermissionsChangeListener(listener: IOnPermissionsChangeListener) {
        onPermissionsChangeListeners.addListener(listener)
    }

    override fun removeOnPermissionsChangeListener(listener: IOnPermissionsChangeListener) {
        onPermissionsChangeListeners.removeListener(listener)
    }

    override fun addOnRuntimePermissionStateChangedListener(
        listener: PermissionManagerServiceInternal.OnRuntimePermissionStateChangedListener
    ) {
        // TODO: Should be removed once we remove PermissionPolicyService.
    }

    override fun removeOnRuntimePermissionStateChangedListener(
        listener: PermissionManagerServiceInternal.OnRuntimePermissionStateChangedListener
    ) {
        // TODO: Should be removed once we remove PermissionPolicyService.
    }

    override fun getSplitPermissions(): List<SplitPermissionInfoParcelable> {
        return PermissionManager.splitPermissionInfoListToParcelableList(
            systemConfig.splitPermissions
        )
    }

    override fun getAppOpPermissionPackages(permissionName: String): Array<String> {
        TODO("Not yet implemented")
    }

    override fun getAllAppOpPermissionPackages(): Map<String, Set<String>> {
        val appOpPermissionPackageNames = IndexedMap<String, IndexedSet<String>>()
        val permissions = service.getState { with(policy) { getPermissions() } }
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            snapshot.packageStates.forEach packageStates@{ (_, packageState) ->
                val androidPackage = packageState.androidPackage ?: return@packageStates
                androidPackage.requestedPermissions.forEach requestedPermissions@{ permissionName ->
                    val permission = permissions[permissionName] ?: return@requestedPermissions
                    if (permission.isAppOp) {
                        val packageNames = appOpPermissionPackageNames
                            .getOrPut(permissionName) { IndexedSet() }
                        packageNames += androidPackage.packageName
                    }
                }
            }
        }
        return appOpPermissionPackageNames
    }

    override fun backupRuntimePermissions(userId: Int): ByteArray? {
        TODO("Not yet implemented")
    }

    override fun restoreRuntimePermissions(backup: ByteArray, userId: Int) {
        TODO("Not yet implemented")
    }

    override fun restoreDelayedRuntimePermissions(packageName: String, userId: Int) {
        TODO("Not yet implemented")
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>?) {
        TODO("Not yet implemented")
    }

    override fun getPermissionTEMP(
        permissionName: String
    ): com.android.server.pm.permission.Permission? {
        TODO("Not yet implemented")
    }

    override fun getLegacyPermissions(): List<LegacyPermission> {
        TODO("Not yet implemented")
    }

    override fun readLegacyPermissionsTEMP(legacyPermissionSettings: LegacyPermissionSettings) {
        // Package settings has been read when this method is called.
        service.initialize()
        TODO("Not yet implemented")
    }

    override fun writeLegacyPermissionsTEMP(legacyPermissionSettings: LegacyPermissionSettings) {
        TODO("Not yet implemented")
    }

    override fun getLegacyPermissionState(appId: Int): LegacyPermissionState {
        TODO("Not yet implemented")
    }

    override fun readLegacyPermissionStateTEMP() {
        TODO("Not yet implemented")
    }

    override fun writeLegacyPermissionStateTEMP() {
        TODO("Not yet implemented")
    }

    override fun onSystemReady() {
        TODO("Not yet implemented")
    }

    override fun onUserCreated(userId: Int) {
        service.onUserAdded(userId)
    }

    override fun onUserRemoved(userId: Int) {
        service.onUserRemoved(userId)
    }

    override fun onStorageVolumeMounted(volumeUuid: String, fingerprintChanged: Boolean) {
        service.onStorageVolumeMounted(volumeUuid, fingerprintChanged)
        synchronized(mountedStorageVolumes) {
            mountedStorageVolumes += volumeUuid
        }
    }

    override fun onPackageAdded(
        androidPackage: AndroidPackage,
        isInstantApp: Boolean,
        oldPackage: AndroidPackage?
    ) {
        synchronized(mountedStorageVolumes) {
            if (androidPackage.volumeUuid !in mountedStorageVolumes) {
                // Wait for the storage volume to be mounted and batch the state mutation there.
                return
            }
        }
        service.onPackageAdded(androidPackage.packageName)
    }

    override fun onPackageRemoved(androidPackage: AndroidPackage) {
        // This may not be a full removal so ignored - we'll figure out full removal in
        // onPackageUninstalled().
    }

    override fun onPackageInstalled(
        androidPackage: AndroidPackage,
        previousAppId: Int,
        params: PermissionManagerServiceInternal.PackageInstalledParams,
        userId: Int
    ) {
        synchronized(mountedStorageVolumes) {
            if (androidPackage.volumeUuid !in mountedStorageVolumes) {
                // Wait for the storage volume to be mounted and batch the state mutation there.
                // PackageInstalledParams won't exist when packages are being scanned instead of
                // being installed by an installer.
                return
            }
        }
        val userIds = if (userId == UserHandle.USER_ALL) {
            userManagerService.userIdsIncludingPreCreated
        } else {
            intArrayOf(userId)
        }
        @Suppress("NAME_SHADOWING")
        userIds.forEach { userId ->
            service.onPackageInstalled(androidPackage.packageName, userId)
            // TODO: Remove when this callback receives packageState directly.
            val packageState =
                packageManagerInternal.getPackageStateInternal(androidPackage.packageName)!!
            // TODO: Add allowlisting
            grantRequestedRuntimePermissions(packageState, userId, params.grantedPermissions)
        }
    }

    override fun onPackageUninstalled(
        packageName: String,
        appId: Int,
        androidPackage: AndroidPackage?,
        sharedUserPkgs: List<AndroidPackage>,
        userId: Int
    ) {
        val userIds = if (userId == UserHandle.USER_ALL) {
            userManagerService.userIdsIncludingPreCreated
        } else {
            intArrayOf(userId)
        }
        userIds.forEach { service.onPackageUninstalled(packageName, appId, it) }
        val packageState = packageManagerInternal.packageStates[packageName]
        if (packageState == null) {
            service.onPackageRemoved(packageName, appId)
        }
    }

    /**
     * Check whether a UID is root or system.
     */
    private fun isRootOrSystem(uid: Int) =
        when (UserHandle.getAppId(uid)) {
            Process.ROOT_UID, Process.SYSTEM_UID -> true
            else -> false
        }

    /**
     * Check whether a UID is shell.
     */
    private fun isShell(uid: Int) = UserHandle.getAppId(uid) == Process.SHELL_UID

    /**
     * Check whether a UID is root, system or shell.
     */
    private fun isRootOrSystemOrShell(uid: Int) = isRootOrSystem(uid) || isShell(uid)

    /**
     * This method should typically only be used when granting or revoking permissions, since the
     * app may immediately restart after this call.
     *
     * If you're doing surgery on app code/data, use [PackageFreezer] to guard your work against
     * the app being relaunched.
     */
    private fun killUid(uid: Int, reason: String) {
        val activityManager = ActivityManager.getService()
        if (activityManager != null) {
            val appId = UserHandle.getAppId(uid)
            val userId = UserHandle.getUserId(uid)
            val identity = Binder.clearCallingIdentity()
            try {
                activityManager.killUidForPermissionChange(appId, userId, reason)
            } catch (e: RemoteException) {
                /* ignore - same process */
            } finally {
                Binder.restoreCallingIdentity(identity)
            }
        }
    }

    /**
     * @see PackageManagerLocal.withFilteredSnapshot
     */
    private fun PackageManagerLocal.withFilteredSnapshot(
        callingUid: Int,
        userId: Int
    ): PackageManagerLocal.FilteredSnapshot =
        withFilteredSnapshot(callingUid, UserHandle.of(userId))

    /**
     * Get the [PackageState] for a package name.
     *
     * This is for parity with [PackageManagerLocal.FilteredSnapshot.getPackageState] which is more
     * efficient than [PackageManagerLocal.FilteredSnapshot.getPackageStates], so that we can always
     * prefer using `getPackageState()` without worrying about whether the snapshot is filtered.
     */
    private fun PackageManagerLocal.UnfilteredSnapshot.getPackageState(
        packageName: String
    ): PackageState? = packageStates[packageName]

    /**
     * Check whether a UID belongs to an instant app.
     */
    private fun PackageManagerLocal.UnfilteredSnapshot.isUidInstantApp(uid: Int): Boolean =
        // Unfortunately we don't have the API for getting the owner UID of an isolated UID or the
        // API for getting the SharedUserApi object for an app ID yet, so for now we just keep
        // calling the old API.
        packageManagerInternal.getInstantAppPackageName(uid) != null

    /**
     * Check whether a package is visible to a UID within the same user as the UID.
     */
    private fun PackageManagerLocal.UnfilteredSnapshot.isPackageVisibleToUid(
        packageName: String,
        uid: Int
    ): Boolean = isPackageVisibleToUid(packageName, UserHandle.getUserId(uid), uid)

    /**
     * Check whether a package in a particular user is visible to a UID.
     */
    private fun PackageManagerLocal.UnfilteredSnapshot.isPackageVisibleToUid(
        packageName: String,
        userId: Int,
        uid: Int
    ): Boolean = filtered(uid, userId).use { it.getPackageState(packageName) != null }

    /**
     * @see PackageManagerLocal.UnfilteredSnapshot.filtered
     */
    private fun PackageManagerLocal.UnfilteredSnapshot.filtered(
        callingUid: Int,
        userId: Int
    ): PackageManagerLocal.FilteredSnapshot = filtered(callingUid, UserHandle.of(userId))

    /**
     * If neither you nor the calling process of an IPC you are handling has been granted the
     * permission for accessing a particular [userId], throw a [SecurityException].
     *
     * @see Context.enforceCallingOrSelfPermission
     * @see UserManager.DISALLOW_DEBUGGING_FEATURES
     */
    private fun enforceCallingOrSelfCrossUserPermission(
        userId: Int,
        enforceFullPermission: Boolean,
        enforceShellRestriction: Boolean,
        message: String?
    ) {
        require(userId >= 0) { "userId $userId is invalid" }
        val callingUid = Binder.getCallingUid()
        val callingUserId = UserHandle.getUserId(callingUid)
        if (userId != callingUserId) {
            val permissionName = if (enforceFullPermission) {
                Manifest.permission.INTERACT_ACROSS_USERS_FULL
            } else {
                Manifest.permission.INTERACT_ACROSS_USERS
            }
            if (context.checkCallingOrSelfPermission(permissionName) !=
                PackageManager.PERMISSION_GRANTED) {
                val exceptionMessage = buildString {
                    if (message != null) {
                        append(message)
                        append(": ")
                    }
                    append("Neither user ")
                    append(Binder.getCallingUid())
                    append(" nor current process has ")
                    append(permissionName)
                    append(" to access user ")
                    append(userId)
                }
                throw SecurityException(exceptionMessage)
            }
        }
        if (enforceShellRestriction && isShell(callingUid)) {
            val isShellRestricted = userManagerInternal.hasUserRestriction(
                UserManager.DISALLOW_DEBUGGING_FEATURES, userId
            )
            if (isShellRestricted) {
                val exceptionMessage = buildString {
                    if (message != null) {
                        append(message)
                        append(": ")
                    }
                    append("Shell is disallowed to access user ")
                    append(userId)
                }
                throw SecurityException(exceptionMessage)
            }
        }
    }

    /**
     * If neither you nor the calling process of an IPC you are handling has been granted any of the
     * permissions, throw a [SecurityException].
     *
     * @see Context.enforceCallingOrSelfPermission
     */
    private fun enforceCallingOrSelfAnyPermission(
        message: String?,
        vararg permissionNames: String
    ) {
        val hasAnyPermission = permissionNames.any { permissionName ->
            context.checkCallingOrSelfPermission(permissionName) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (!hasAnyPermission) {
            val exceptionMessage = buildString {
                if (message != null) {
                    append(message)
                    append(": ")
                }
                append("Neither user ")
                append(Binder.getCallingUid())
                append(" nor current process has any of ")
                permissionNames.joinTo(this, ", ")
            }
            throw SecurityException(exceptionMessage)
        }
    }

    /**
     * Callback invoked when interesting actions have been taken on a permission.
     */
    private inner class OnPermissionFlagsChangedListener :
        UidPermissionPolicy.OnPermissionFlagsChangedListener() {
        private val runtimePermissionChangedUids = IntSet()
        // Mapping from UID to whether only notifications permissions are revoked.
        private val runtimePermissionRevokedUids = IntBooleanMap()
        private val gidsChangedUids = IntSet()

        private var isKillRuntimePermissionRevokedUidsSkipped = false
        private val killRuntimePermissionRevokedUidsReasons = IndexedSet<String>()

        fun MutateStateScope.skipKillRuntimePermissionRevokedUids() {
            isKillRuntimePermissionRevokedUidsSkipped = true
        }

        fun MutateStateScope.addKillRuntimePermissionRevokedUidsReason(reason: String) {
            killRuntimePermissionRevokedUidsReasons += reason
        }

        override fun onPermissionFlagsChanged(
            appId: Int,
            userId: Int,
            permissionName: String,
            oldFlags: Int,
            newFlags: Int
        ) {
            val uid = UserHandle.getUid(userId, appId)
            val permission = service.getState {
                with(policy) { getPermissions()[permissionName] }
            } ?: return
            val wasPermissionGranted = PermissionFlags.isPermissionGranted(oldFlags)
            val isPermissionGranted = PermissionFlags.isPermissionGranted(newFlags)

            if (permission.isRuntime) {
                // Different from the old implementation, which notifies the listeners when the
                // permission flags have changed for a non-runtime permission, now we no longer do
                // that because permission flags are only for runtime permissions and the listeners
                // aren't being notified of non-runtime permission grant state changes anyway.
                runtimePermissionChangedUids += uid
                if (wasPermissionGranted && !isPermissionGranted) {
                    runtimePermissionRevokedUids[uid] =
                        permissionName in NOTIFICATIONS_PERMISSIONS &&
                            runtimePermissionRevokedUids.getWithDefault(uid, true)
                }
            }

            if (permission.hasGids && !wasPermissionGranted && isPermissionGranted) {
                gidsChangedUids += uid
            }
        }

        override fun onStateMutated() {
            runtimePermissionChangedUids.forEachIndexed { _, uid ->
                onPermissionsChangeListeners.onPermissionsChanged(uid)
            }
            runtimePermissionChangedUids.clear()

            if (!isKillRuntimePermissionRevokedUidsSkipped) {
                val reason = if (killRuntimePermissionRevokedUidsReasons.isNotEmpty()) {
                    killRuntimePermissionRevokedUidsReasons.joinToString(", ")
                } else {
                    PermissionManager.KILL_APP_REASON_PERMISSIONS_REVOKED
                }
                runtimePermissionRevokedUids.forEachIndexed {
                    _, uid, areOnlyNotificationsPermissionsRevoked ->
                    handler.post {
                        if (areOnlyNotificationsPermissionsRevoked &&
                            isAppBackupAndRestoreRunning(uid)
                        ) {
                            return@post
                        }
                        killUid(uid, reason)
                    }
                }
            }
            runtimePermissionRevokedUids.clear()

            gidsChangedUids.forEachIndexed { _, uid ->
                handler.post { killUid(uid, PermissionManager.KILL_APP_REASON_GIDS_CHANGED) }
            }
            gidsChangedUids.clear()

            isKillRuntimePermissionRevokedUidsSkipped = false
            killRuntimePermissionRevokedUidsReasons.clear()
        }

        private fun isAppBackupAndRestoreRunning(uid: Int): Boolean {
            if (checkUidPermission(uid, Manifest.permission.BACKUP) !=
                PackageManager.PERMISSION_GRANTED) {
                return false
            }
            return try {
                val contentResolver = context.contentResolver
                val userId = UserHandle.getUserId(uid)
                val isInSetup = Settings.Secure.getIntForUser(
                    contentResolver, Settings.Secure.USER_SETUP_COMPLETE, userId
                ) == 0
                val isInDeferredSetup = Settings.Secure.getIntForUser(
                    contentResolver, Settings.Secure.USER_SETUP_PERSONALIZATION_STATE, userId
                ) == Settings.Secure.USER_SETUP_PERSONALIZATION_STARTED
                isInSetup || isInDeferredSetup
            } catch (e: Settings.SettingNotFoundException) {
                Log.w(LOG_TAG, "Failed to check if the user is in restore: $e")
                false
            }
        }
    }

    private class OnPermissionsChangeListeners(looper: Looper) : Handler(looper) {
        private val listeners = RemoteCallbackList<IOnPermissionsChangeListener>()

        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_ON_PERMISSIONS_CHANGED -> {
                    val uid = msg.arg1
                    handleOnPermissionsChanged(uid)
                }
            }
        }

        private fun handleOnPermissionsChanged(uid: Int) {
            listeners.broadcast { listener ->
                try {
                    listener.onPermissionsChanged(uid)
                } catch (e: RemoteException) {
                    Log.e(LOG_TAG, "Error when calling OnPermissionsChangeListener", e)
                }
            }
        }

        fun addListener(listener: IOnPermissionsChangeListener) {
            listeners.register(listener)
        }

        fun removeListener(listener: IOnPermissionsChangeListener) {
            listeners.unregister(listener)
        }

        fun onPermissionsChanged(uid: Int) {
            if (listeners.registeredCallbackCount > 0) {
                obtainMessage(MSG_ON_PERMISSIONS_CHANGED, uid, 0).sendToTarget()
            }
        }

        companion object {
            private const val MSG_ON_PERMISSIONS_CHANGED = 1
        }
    }

    companion object {
        private val LOG_TAG = PermissionService::class.java.simpleName

        /**
         * This change makes it so that apps are told to show rationale for asking for background
         * location access every time they request.
         */
        @ChangeId
        @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.Q)
        private val BACKGROUND_RATIONALE_CHANGE_ID = 147316723L

        private val FULLER_PERMISSIONS = IndexedMap<String, String>().apply {
            this[Manifest.permission.ACCESS_COARSE_LOCATION] =
                Manifest.permission.ACCESS_FINE_LOCATION
            this[Manifest.permission.INTERACT_ACROSS_USERS] =
                Manifest.permission.INTERACT_ACROSS_USERS_FULL
        }

        private val NOTIFICATIONS_PERMISSIONS = indexedSetOf(
            Manifest.permission.POST_NOTIFICATIONS
        )

        private const val REVIEW_REQUIRED_FLAGS = PermissionFlags.LEGACY_GRANTED or
            PermissionFlags.IMPLICIT
        private const val UNREQUESTABLE_MASK = PermissionFlags.RESTRICTION_REVOKED or
            PermissionFlags.SYSTEM_FIXED or PermissionFlags.POLICY_FIXED or
            PermissionFlags.USER_FIXED
    }
}
