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
import android.app.AppOpsManager
import android.companion.virtual.VirtualDeviceManager
import android.compat.annotation.ChangeId
import android.compat.annotation.EnabledAfter
import android.content.Context
import android.content.pm.PackageInstaller
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
import android.permission.PermissionControllerManager
import android.permission.PermissionManager
import android.permission.PermissionManager.PermissionState
import android.permission.flags.Flags
import android.provider.Settings
import android.util.ArrayMap
import android.util.ArraySet
import android.util.DebugUtils
import android.util.IndentingPrintWriter
import android.util.IntArray as GrowingIntArray
import android.util.Slog
import android.util.SparseBooleanArray
import com.android.internal.annotations.GuardedBy
import com.android.internal.compat.IPlatformCompat
import com.android.internal.logging.MetricsLogger
import com.android.internal.logging.nano.MetricsProto
import com.android.internal.util.DumpUtils
import com.android.internal.util.Preconditions
import com.android.server.FgThread
import com.android.server.LocalManagerRegistry
import com.android.server.LocalServices
import com.android.server.PermissionThread
import com.android.server.ServiceThread
import com.android.server.SystemConfig
import com.android.server.companion.virtual.VirtualDeviceManagerInternal
import com.android.server.permission.access.AccessCheckingService
import com.android.server.permission.access.AccessState
import com.android.server.permission.access.AppOpUri
import com.android.server.permission.access.DevicePermissionUri
import com.android.server.permission.access.GetStateScope
import com.android.server.permission.access.MutateStateScope
import com.android.server.permission.access.PermissionUri
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.appop.AppIdAppOpPolicy
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.immutable.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.andInv
import com.android.server.permission.access.util.hasAnyBit
import com.android.server.permission.access.util.hasBits
import com.android.server.permission.access.util.withClearedCallingIdentity
import com.android.server.pm.KnownPackages
import com.android.server.pm.PackageInstallerService
import com.android.server.pm.PackageManagerLocal
import com.android.server.pm.UserManagerInternal
import com.android.server.pm.UserManagerService
import com.android.server.pm.permission.LegacyPermission
import com.android.server.pm.permission.LegacyPermissionSettings
import com.android.server.pm.permission.LegacyPermissionState
import com.android.server.pm.permission.Permission as LegacyPermission2
import com.android.server.pm.permission.PermissionManagerServiceInterface
import com.android.server.pm.permission.PermissionManagerServiceInternal
import com.android.server.pm.pkg.AndroidPackage
import com.android.server.pm.pkg.PackageState
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import libcore.util.EmptyArray

/** Modern implementation of [PermissionManagerServiceInterface]. */
class PermissionService(private val service: AccessCheckingService) :
    PermissionManagerServiceInterface {
    private val policy =
        service.getSchemePolicy(UidUri.SCHEME, PermissionUri.SCHEME) as AppIdPermissionPolicy

    private val devicePolicy =
        service.getSchemePolicy(UidUri.SCHEME, DevicePermissionUri.SCHEME) as DevicePermissionPolicy

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

    private val storageVolumeLock = Any()
    @GuardedBy("storageVolumeLock") private val mountedStorageVolumes = ArraySet<String?>()
    @GuardedBy("storageVolumeLock")
    private val storageVolumePackageNames = ArrayMap<String?, MutableList<String>>()

    private var virtualDeviceManagerInternal: VirtualDeviceManagerInternal? = null

    private lateinit var permissionControllerManager: PermissionControllerManager

    /**
     * A permission backup might contain apps that are not installed. In this case we delay the
     * restoration until the app is installed.
     *
     * This array (`userId -> noDelayedBackupLeft`) is `true` for all the users where there is **no
     * more** delayed backup left.
     */
    private val isDelayedPermissionBackupFinished = SparseBooleanArray()

    fun initialize() {
        metricsLogger = MetricsLogger()
        packageManagerInternal = LocalServices.getService(PackageManagerInternal::class.java)
        packageManagerLocal =
            LocalManagerRegistry.getManagerOrThrow(PackageManagerLocal::class.java)
        platformCompat =
            IPlatformCompat.Stub.asInterface(
                ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE)
            )
        systemConfig = SystemConfig.getInstance()
        userManagerInternal = LocalServices.getService(UserManagerInternal::class.java)
        userManagerService = UserManagerService.getInstance()
        // The package info cache is the cache for package and permission information.
        // Disable the package info and package permission caches locally but leave the
        // checkPermission cache active.
        PackageManager.invalidatePackageInfoCache()
        PermissionManager.disablePackageNamePermissionCache()

        handlerThread =
            ServiceThread(LOG_TAG, Process.THREAD_PRIORITY_BACKGROUND, true).apply { start() }
        handler = Handler(handlerThread.looper)
        onPermissionsChangeListeners = OnPermissionsChangeListeners(FgThread.get().looper)
        onPermissionFlagsChangedListener = OnPermissionFlagsChangedListener()
        policy.addOnPermissionFlagsChangedListener(onPermissionFlagsChangedListener)
        devicePolicy.addOnPermissionFlagsChangedListener(onPermissionFlagsChangedListener)
    }

    override fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo> {
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            val callingUid = Binder.getCallingUid()
            if (snapshot.isUidInstantApp(callingUid)) {
                return emptyList()
            }

            val permissionGroups = service.getState { with(policy) { getPermissionGroups() } }

            return permissionGroups.mapNotNullIndexedTo(ArrayList()) { _, _, permissionGroup ->
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

            permissionGroup =
                service.getState { with(policy) { getPermissionGroups()[permissionGroupName] } }
                    ?: return null

            if (!snapshot.isPackageVisibleToUid(permissionGroup.packageName, callingUid)) {
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

            permission =
                service.getState { with(policy) { getPermissions()[permissionName] } }
                    ?: return null

            if (!snapshot.isPackageVisibleToUid(permission.packageName, callingUid)) {
                return null
            }

            val opPackage = snapshot.getPackageState(opPackageName)?.androidPackage
            targetSdkVersion =
                when {
                    // System sees all flags.
                    isRootOrSystemOrShellUid(callingUid) -> Build.VERSION_CODES.CUR_DEVELOPMENT
                    opPackage != null -> opPackage.targetSdkVersion
                    else -> Build.VERSION_CODES.CUR_DEVELOPMENT
                }
        }

        return permission.generatePermissionInfo(flags, targetSdkVersion)
    }

    /** Generate a new [PermissionInfo] from [Permission] and adjust it accordingly. */
    private fun Permission.generatePermissionInfo(
        flags: Int,
        targetSdkVersion: Int = Build.VERSION_CODES.CUR_DEVELOPMENT
    ): PermissionInfo =
        @Suppress("DEPRECATION")
        PermissionInfo(permissionInfo).apply {
            // All Permission objects are registered so the PermissionInfo generated for it should
            // also have FLAG_INSTALLED.
            this.flags = this.flags or PermissionInfo.FLAG_INSTALLED
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

            val permissions =
                service.getState {
                    if (permissionGroupName != null) {
                        val permissionGroup =
                            with(policy) { getPermissionGroups()[permissionGroupName] }
                                ?: return null

                        if (
                            !snapshot.isPackageVisibleToUid(permissionGroup.packageName, callingUid)
                        ) {
                            return null
                        }
                    }

                    with(policy) { getPermissions() }
                }

            return permissions.mapNotNullIndexedTo(ArrayList()) { _, _, permission ->
                if (
                    permission.groupName == permissionGroupName &&
                        snapshot.isPackageVisibleToUid(permission.packageName, callingUid)
                ) {
                    permission.generatePermissionInfo(flags)
                } else {
                    null
                }
            }
        }
    }

    override fun getAllPermissionsWithProtection(protection: Int): List<PermissionInfo> =
        getPermissionsWithProtectionOrProtectionFlags { permission ->
            permission.protection == protection
        }

    override fun getAllPermissionsWithProtectionFlags(protectionFlags: Int): List<PermissionInfo> =
        getPermissionsWithProtectionOrProtectionFlags { permission ->
            permission.protectionFlags.hasBits(protectionFlags)
        }

    private inline fun getPermissionsWithProtectionOrProtectionFlags(
        predicate: (Permission) -> Boolean
    ): List<PermissionInfo> {
        val permissions = service.getState { with(policy) { getPermissions() } }

        return permissions.mapNotNullIndexedTo(ArrayList()) { _, _, permission ->
            if (predicate(permission)) {
                permission.generatePermissionInfo(0)
            } else {
                null
            }
        }
    }

    override fun getPermissionGids(permissionName: String, userId: Int): IntArray {
        val permission =
            service.getState { with(policy) { getPermissions()[permissionName] } }
                ?: return EmptyArray.INT
        return permission.getGidsForUser(userId)
    }

    override fun getInstalledPermissions(packageName: String): Set<String> {
        requireNotNull(packageName) { "packageName cannot be null" }

        val permissions = service.getState { with(policy) { getPermissions() } }

        return permissions.mapNotNullIndexedTo(ArraySet()) { _, _, permission ->
            if (permission.packageName == packageName) {
                permission.name
            } else {
                null
            }
        }
    }

    override fun addPermission(permissionInfo: PermissionInfo, async: Boolean): Boolean {
        val permissionName = permissionInfo.name
        requireNotNull(permissionName) { "permissionName cannot be null" }
        val callingUid = Binder.getCallingUid()
        if (packageManagerLocal.withUnfilteredSnapshot().use { it.isUidInstantApp(callingUid) }) {
            throw SecurityException("Instant apps cannot add permissions")
        }
        if (permissionInfo.labelRes == 0 && permissionInfo.nonLocalizedLabel == null) {
            throw SecurityException("Label must be specified in permission")
        }
        val oldPermission: Permission?

        service.mutateState {
            val permissionTree = getAndEnforcePermissionTree(permissionName)
            enforcePermissionTreeSize(permissionInfo, permissionTree)

            oldPermission = with(policy) { getPermissions()[permissionName] }
            if (oldPermission != null && !oldPermission.isDynamic) {
                throw SecurityException(
                    "Not allowed to modify non-dynamic permission $permissionName"
                )
            }

            permissionInfo.packageName = permissionTree.permissionInfo.packageName
            @Suppress("DEPRECATION")
            permissionInfo.protectionLevel =
                PermissionInfo.fixProtectionLevel(permissionInfo.protectionLevel)

            val newPermission =
                Permission(permissionInfo, true, Permission.TYPE_DYNAMIC, permissionTree.appId)

            with(policy) { addPermission(newPermission, !async) }
        }

        return oldPermission == null
    }

    override fun removePermission(permissionName: String) {
        val callingUid = Binder.getCallingUid()
        if (packageManagerLocal.withUnfilteredSnapshot().use { it.isUidInstantApp(callingUid) }) {
            throw SecurityException("Instant applications don't have access to this method")
        }
        service.mutateState {
            getAndEnforcePermissionTree(permissionName)
            val permission = with(policy) { getPermissions()[permissionName] } ?: return@mutateState

            if (!permission.isDynamic) {
                // TODO(b/67371907): switch to logging if it fails
                throw SecurityException(
                    "Not allowed to modify non-dynamic permission $permissionName"
                )
            }

            with(policy) { removePermission(permission) }
        }
    }

    private fun GetStateScope.getAndEnforcePermissionTree(permissionName: String): Permission {
        val callingUid = Binder.getCallingUid()
        val permissionTree = with(policy) { findPermissionTree(permissionName) }
        if (permissionTree != null && permissionTree.appId == UserHandle.getAppId(callingUid)) {
            return permissionTree
        }

        throw SecurityException(
            "Calling UID $callingUid is not allowed to add to or remove from the permission tree"
        )
    }

    private fun GetStateScope.enforcePermissionTreeSize(
        permissionInfo: PermissionInfo,
        permissionTree: Permission
    ) {
        // We calculate the max size of permissions defined by this uid and throw
        // if that plus the size of 'info' would exceed our stated maximum.
        if (permissionTree.appId != Process.SYSTEM_UID) {
            val permissionTreeFootprint = calculatePermissionTreeFootprint(permissionTree)
            if (
                permissionTreeFootprint + permissionInfo.calculateFootprint() >
                    MAX_PERMISSION_TREE_FOOTPRINT
            ) {
                throw SecurityException("Permission tree size cap exceeded")
            }
        }
    }

    private fun GetStateScope.calculatePermissionTreeFootprint(permissionTree: Permission): Int {
        var size = 0
        with(policy) {
            getPermissions().forEachIndexed { _, _, permission ->
                if (permissionTree.appId == permission.appId) {
                    size += permission.footprint
                }
            }
        }
        return size
    }

    override fun checkUidPermission(uid: Int, permissionName: String, deviceId: String): Int {
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
                Slog.e(
                    LOG_TAG,
                    "checkUidPermission: PackageState not found for AndroidPackage" +
                        " $androidPackage"
                )
                return PackageManager.PERMISSION_DENIED
            }

            val isPermissionGranted =
                service.getState {
                    isPermissionGranted(packageState, userId, permissionName, deviceId)
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

    /** Internal implementation that should only be called by [checkUidPermission]. */
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

    override fun checkPermission(
        packageName: String,
        permissionName: String,
        deviceId: String,
        userId: Int
    ): Int {
        if (!userManagerInternal.exists(userId)) {
            return PackageManager.PERMISSION_DENIED
        }

        val packageState =
            packageManagerLocal.withFilteredSnapshot(Binder.getCallingUid(), userId).use {
                it.getPackageState(packageName)
            }
                ?: return PackageManager.PERMISSION_DENIED

        val isPermissionGranted =
            service.getState { isPermissionGranted(packageState, userId, permissionName, deviceId) }
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
        permissionName: String,
        deviceId: String
    ): Boolean {
        val appId = packageState.appId
        // Note that instant apps can't have shared UIDs, so we only need to check the current
        // package state.
        val isInstantApp = packageState.getUserStateOrDefault(userId).isInstantApp
        if (isSinglePermissionGranted(appId, userId, isInstantApp, permissionName, deviceId)) {
            return true
        }

        val fullerPermissionName = FULLER_PERMISSIONS[permissionName]
        if (
            fullerPermissionName != null &&
                isSinglePermissionGranted(
                    appId,
                    userId,
                    isInstantApp,
                    fullerPermissionName,
                    deviceId
                )
        ) {
            return true
        }

        return false
    }

    /** Internal implementation that should only be called by [isPermissionGranted]. */
    private fun GetStateScope.isSinglePermissionGranted(
        appId: Int,
        userId: Int,
        isInstantApp: Boolean,
        permissionName: String,
        deviceId: String,
    ): Boolean {
        val flags = getPermissionFlagsWithPolicy(appId, userId, permissionName, deviceId)
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

        val packageState =
            packageManagerLocal.withUnfilteredSnapshot().use { it.getPackageState(packageName) }
        if (packageState == null) {
            Slog.w(LOG_TAG, "getGrantedPermissions: Unknown package $packageName")
            return emptySet()
        }

        service.getState {
            val permissionFlags =
                with(policy) { getUidPermissionFlags(packageState.appId, userId) }
                    ?: return emptySet()

            return permissionFlags.mapNotNullIndexedTo(ArraySet()) { _, permissionName, _ ->
                if (
                    isPermissionGranted(
                        packageState,
                        userId,
                        permissionName,
                        VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT
                    )
                ) {
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
            val permissionFlags =
                with(policy) { getUidPermissionFlags(appId, userId) } ?: return globalGids.copyOf()

            val gids = GrowingIntArray.wrap(globalGids)
            permissionFlags.forEachIndexed { _, permissionName, flags ->
                if (!PermissionFlags.isPermissionGranted(flags)) {
                    return@forEachIndexed
                }

                val permission =
                    with(policy) { getPermissions()[permissionName] } ?: return@forEachIndexed
                val permissionGids = permission.getGidsForUser(userId)
                if (permissionGids.isEmpty()) {
                    return@forEachIndexed
                }
                gids.addAll(permissionGids)
            }
            return gids.toArray()
        }
    }

    override fun grantRuntimePermission(
        packageName: String,
        permissionName: String,
        deviceId: String,
        userId: Int
    ) {
        setRuntimePermissionGranted(packageName, userId, permissionName, deviceId, isGranted = true)
    }

    override fun revokeRuntimePermission(
        packageName: String,
        permissionName: String,
        deviceId: String,
        userId: Int,
        reason: String?
    ) {
        setRuntimePermissionGranted(
            packageName,
            userId,
            permissionName,
            deviceId,
            isGranted = false,
            revokeReason = reason
        )
    }

    override fun revokePostNotificationPermissionWithoutKillForTest(
        packageName: String,
        userId: Int
    ) {
        setRuntimePermissionGranted(
            packageName,
            userId,
            Manifest.permission.POST_NOTIFICATIONS,
            VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT,
            isGranted = false,
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
        deviceId: String,
        isGranted: Boolean,
        skipKillUid: Boolean = false,
        revokeReason: String? = null
    ) {
        val methodName = if (isGranted) "grantRuntimePermission" else "revokeRuntimePermission"
        val callingUid = Binder.getCallingUid()
        val isDebugEnabled =
            if (isGranted) {
                PermissionManager.DEBUG_TRACE_GRANTS
            } else {
                PermissionManager.DEBUG_TRACE_PERMISSION_UPDATES
            }
        if (
            isDebugEnabled &&
                PermissionManager.shouldTraceGrant(packageName, permissionName, userId)
        ) {
            val callingUidName = packageManagerInternal.getNameForUid(callingUid)
            Slog.i(
                LOG_TAG,
                "$methodName(packageName = $packageName," +
                    " permissionName = $permissionName" +
                    (if (isGranted) "" else "skipKillUid = $skipKillUid, reason = $revokeReason") +
                    ", userId = $userId," +
                    " callingUid = $callingUidName ($callingUid))," +
                    " deviceId = $deviceId",
                RuntimeException()
            )
        }

        if (!userManagerInternal.exists(userId)) {
            Slog.w(LOG_TAG, "$methodName: Unknown user $userId")
            return
        }

        enforceCallingOrSelfCrossUserPermission(
            userId,
            enforceFullPermission = true,
            enforceShellRestriction = true,
            methodName
        )
        val enforcedPermissionName =
            if (isGranted) {
                Manifest.permission.GRANT_RUNTIME_PERMISSIONS
            } else {
                Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
            }
        context.enforceCallingOrSelfPermission(enforcedPermissionName, methodName)

        val packageState: PackageState?
        val permissionControllerPackageName =
            packageManagerInternal
                .getKnownPackageNames(
                    KnownPackages.PACKAGE_PERMISSION_CONTROLLER,
                    UserHandle.USER_SYSTEM
                )
                .first()
        val permissionControllerPackageState: PackageState?
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            packageState =
                snapshot.filtered(callingUid, userId).use { it.getPackageState(packageName) }
            permissionControllerPackageState =
                snapshot.getPackageState(permissionControllerPackageName)
        }
        val androidPackage = packageState?.androidPackage
        // Different from the old implementation, which returns when package doesn't exist but
        // throws when package exists but isn't visible, we now return in both cases to avoid
        // leaking the package existence.
        if (androidPackage == null) {
            Slog.w(LOG_TAG, "$methodName: Unknown package $packageName")
            return
        }

        val canManageRolePermission =
            isRootOrSystemUid(callingUid) ||
                UserHandle.getAppId(callingUid) == permissionControllerPackageState!!.appId
        val overridePolicyFixed =
            context.checkCallingOrSelfPermission(
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
                packageState,
                userId,
                permissionName,
                deviceId,
                isGranted,
                canManageRolePermission,
                overridePolicyFixed,
                reportError = true,
                methodName
            )
        }
    }

    private fun setRequestedPermissionStates(
        packageState: PackageState,
        userId: Int,
        permissionStates: ArrayMap<String, Int>
    ) {
        service.mutateState {
            permissionStates.forEachIndexed { _, permissionName, permissionState ->
                when (permissionState) {
                    PackageInstaller.SessionParams.PERMISSION_STATE_GRANTED,
                    PackageInstaller.SessionParams.PERMISSION_STATE_DENIED -> {}
                    else -> {
                        Slog.w(
                            LOG_TAG,
                            "setRequestedPermissionStates: Unknown permission state" +
                                " $permissionState for permission $permissionName"
                        )
                        return@forEachIndexed
                    }
                }
                if (permissionName !in packageState.androidPackage!!.requestedPermissions) {
                    return@forEachIndexed
                }
                val permission =
                    with(policy) { getPermissions()[permissionName] } ?: return@forEachIndexed
                when {
                    permission.isDevelopment || permission.isRuntime -> {
                        if (
                            permissionState ==
                                PackageInstaller.SessionParams.PERMISSION_STATE_GRANTED
                        ) {
                            setRuntimePermissionGranted(
                                packageState,
                                userId,
                                permissionName,
                                VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT,
                                isGranted = true,
                                canManageRolePermission = false,
                                overridePolicyFixed = false,
                                reportError = false,
                                "setRequestedPermissionStates"
                            )
                            updatePermissionFlags(
                                packageState.appId,
                                userId,
                                permissionName,
                                VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT,
                                PackageManager.FLAG_PERMISSION_REVIEW_REQUIRED or
                                    PackageManager.FLAG_PERMISSION_REVOKED_COMPAT,
                                0,
                                canUpdateSystemFlags = false,
                                reportErrorForUnknownPermission = false,
                                isPermissionRequested = true,
                                "setRequestedPermissionStates",
                                packageState.packageName
                            )
                        }
                    }
                    permission.isAppOp &&
                        permissionName in
                            PackageInstallerService.INSTALLER_CHANGEABLE_APP_OP_PERMISSIONS ->
                        setAppOpPermissionGranted(
                            packageState,
                            userId,
                            permissionName,
                            permissionState ==
                                PackageInstaller.SessionParams.PERMISSION_STATE_GRANTED
                        )
                    else -> {}
                }
            }
        }
    }

    /** Set whether a runtime permission is granted, without any validation on caller. */
    private fun MutateStateScope.setRuntimePermissionGranted(
        packageState: PackageState,
        userId: Int,
        permissionName: String,
        deviceId: String,
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
                if (
                    isGranted &&
                        packageState.getUserStateOrDefault(userId).isInstantApp &&
                        !permission.isInstant
                ) {
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
        val oldFlags = getPermissionFlagsWithPolicy(appId, userId, permissionName, deviceId)

        if (permissionName !in androidPackage.requestedPermissions && oldFlags == 0) {
            if (reportError) {
                Slog.e(
                    LOG_TAG,
                    "Permission $permissionName isn't requested by package $packageName"
                )
            }
            return
        }

        if (oldFlags.hasBits(PermissionFlags.SYSTEM_FIXED)) {
            if (reportError) {
                Slog.e(
                    LOG_TAG,
                    "$methodName: Cannot change system fixed permission $permissionName" +
                        " for package $packageName"
                )
            }
            return
        }

        if (oldFlags.hasBits(PermissionFlags.POLICY_FIXED) && !overridePolicyFixed) {
            if (reportError) {
                Slog.e(
                    LOG_TAG,
                    "$methodName: Cannot change policy fixed permission $permissionName" +
                        " for package $packageName"
                )
            }
            return
        }

        if (isGranted && oldFlags.hasBits(PermissionFlags.RESTRICTION_REVOKED)) {
            if (reportError) {
                Slog.e(
                    LOG_TAG,
                    "$methodName: Cannot grant hard-restricted non-exempt permission" +
                        " $permissionName to package $packageName"
                )
            }
            return
        }

        if (isGranted && oldFlags.hasBits(PermissionFlags.SOFT_RESTRICTED)) {
            if (reportError) {
                Slog.e(
                    LOG_TAG,
                    "$methodName: Cannot grant soft-restricted non-exempt permission" +
                        " $permissionName to package $packageName"
                )
            }
            return
        }

        val newFlags = PermissionFlags.updateRuntimePermissionGranted(oldFlags, isGranted)
        if (oldFlags == newFlags) {
            return
        }

        setPermissionFlagsWithPolicy(appId, userId, permissionName, deviceId, newFlags)

        if (permission.isRuntime) {
            val action =
                if (isGranted) {
                    MetricsProto.MetricsEvent.ACTION_PERMISSION_GRANTED
                } else {
                    MetricsProto.MetricsEvent.ACTION_PERMISSION_REVOKED
                }
            val log =
                LogMaker(action).apply {
                    setPackageName(packageName)
                    addTaggedData(MetricsProto.MetricsEvent.FIELD_PERMISSION, permissionName)
                }
            metricsLogger.write(log)
        }
    }

    private fun MutateStateScope.setAppOpPermissionGranted(
        packageState: PackageState,
        userId: Int,
        permissionName: String,
        isGranted: Boolean
    ) {
        val appOpPolicy =
            service.getSchemePolicy(UidUri.SCHEME, AppOpUri.SCHEME) as AppIdAppOpPolicy
        val appOpName = AppOpsManager.permissionToOp(permissionName)!!
        val mode = if (isGranted) AppOpsManager.MODE_ALLOWED else AppOpsManager.MODE_ERRORED
        with(appOpPolicy) { setAppOpMode(packageState.appId, userId, appOpName, mode) }
    }

    override fun getPermissionFlags(
        packageName: String,
        permissionName: String,
        deviceId: String,
        userId: Int,
    ): Int {
        if (!userManagerInternal.exists(userId)) {
            Slog.w(LOG_TAG, "getPermissionFlags: Unknown user $userId")
            return 0
        }

        enforceCallingOrSelfCrossUserPermission(
            userId,
            enforceFullPermission = true,
            enforceShellRestriction = false,
            "getPermissionFlags"
        )
        enforceCallingOrSelfAnyPermission(
            "getPermissionFlags",
            Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
            Manifest.permission.GET_RUNTIME_PERMISSIONS
        )

        val packageState =
            packageManagerLocal.withFilteredSnapshot().use { it.getPackageState(packageName) }
        if (packageState == null) {
            Slog.w(LOG_TAG, "getPermissionFlags: Unknown package $packageName")
            return 0
        }

        service.getState {
            val permission = with(policy) { getPermissions()[permissionName] }
            if (permission == null) {
                Slog.w(LOG_TAG, "getPermissionFlags: Unknown permission $permissionName")
                return 0
            }

            val flags =
                getPermissionFlagsWithPolicy(packageState.appId, userId, permissionName, deviceId)

            return PermissionFlags.toApiFlags(flags)
        }
    }

    override fun getAllPermissionStates(
        packageName: String,
        deviceId: String,
        userId: Int
    ): Map<String, PermissionState> {
        if (!userManagerInternal.exists(userId)) {
            Slog.w(LOG_TAG, "getAllPermissionStates: Unknown user $userId")
            return emptyMap()
        }
        enforceCallingOrSelfCrossUserPermission(
            userId,
            enforceFullPermission = true,
            enforceShellRestriction = false,
            "getAllPermissionStates"
        )
        enforceCallingOrSelfAnyPermission(
            "getAllPermissionStates",
            Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS,
            Manifest.permission.GET_RUNTIME_PERMISSIONS
        )

        val packageState =
            packageManagerLocal.withFilteredSnapshot().use { it.getPackageState(packageName) }
        if (packageState == null) {
            Slog.w(LOG_TAG, "getAllPermissionStates: Unknown package $packageName")
            return emptyMap()
        }

        service.getState {
            val permissionFlags =
                if (deviceId == VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT) {
                    with(policy) { getAllPermissionFlags(packageState.appId, userId) }
                } else {
                    with(devicePolicy) {
                        getAllPermissionFlags(packageState.appId, deviceId, userId)
                    }
                } ?: return emptyMap()
            val permissionStates = ArrayMap<String, PermissionState>()
            permissionFlags.forEachIndexed { _, permissionName, flags ->
                val granted = isPermissionGranted(packageState, userId, permissionName, deviceId)
                val apiFlags = PermissionFlags.toApiFlags(flags)
                permissionStates[permissionName] = PermissionState(granted, apiFlags)
            }
            return permissionStates
        }
    }

    override fun isPermissionRevokedByPolicy(
        packageName: String,
        permissionName: String,
        deviceId: String,
        userId: Int
    ): Boolean {
        if (!userManagerInternal.exists(userId)) {
            Slog.w(LOG_TAG, "isPermissionRevokedByPolicy: Unknown user $userId")
            return false
        }

        enforceCallingOrSelfCrossUserPermission(
            userId,
            enforceFullPermission = true,
            enforceShellRestriction = false,
            "isPermissionRevokedByPolicy"
        )

        val packageState =
            packageManagerLocal.withFilteredSnapshot(Binder.getCallingUid(), userId).use {
                it.getPackageState(packageName)
            }
                ?: return false

        service.getState {
            if (isPermissionGranted(packageState, userId, permissionName, deviceId)) {
                return false
            }

            val flags =
                getPermissionFlagsWithPolicy(packageState.appId, userId, permissionName, deviceId)

            return flags.hasBits(PermissionFlags.POLICY_FIXED)
        }
    }

    override fun isPermissionsReviewRequired(packageName: String, userId: Int): Boolean {
        requireNotNull(packageName) { "packageName cannot be null" }
        // TODO(b/173235285): Some caller may pass USER_ALL as userId.
        // Preconditions.checkArgumentNonnegative(userId, "userId")

        val packageState =
            packageManagerLocal.withUnfilteredSnapshot().use { it.getPackageState(packageName) }
                ?: return false

        val permissionFlags =
            service.getState { with(policy) { getUidPermissionFlags(packageState.appId, userId) } }
                ?: return false
        return permissionFlags.anyIndexed { _, _, it -> it.hasBits(REVIEW_REQUIRED_FLAGS) }
    }

    override fun shouldShowRequestPermissionRationale(
        packageName: String,
        permissionName: String,
        deviceId: String,
        userId: Int,
    ): Boolean {
        if (!userManagerInternal.exists(userId)) {
            Slog.w(LOG_TAG, "shouldShowRequestPermissionRationale: Unknown user $userId")
            return false
        }

        enforceCallingOrSelfCrossUserPermission(
            userId,
            enforceFullPermission = true,
            enforceShellRestriction = false,
            "shouldShowRequestPermissionRationale"
        )

        val callingUid = Binder.getCallingUid()
        val packageState =
            packageManagerLocal.withFilteredSnapshot(callingUid, userId).use {
                it.getPackageState(packageName)
            }
                ?: return false
        val appId = packageState.appId
        if (UserHandle.getAppId(callingUid) != appId) {
            return false
        }

        val flags: Int
        service.getState {
            if (isPermissionGranted(packageState, userId, permissionName, deviceId)) {
                return false
            }

            flags = getPermissionFlagsWithPolicy(appId, userId, permissionName, deviceId)
        }
        if (flags.hasAnyBit(UNREQUESTABLE_MASK)) {
            return false
        }

        if (permissionName == Manifest.permission.ACCESS_BACKGROUND_LOCATION) {
            val isBackgroundRationaleChangeEnabled =
                Binder::class.withClearedCallingIdentity {
                    try {
                        platformCompat.isChangeEnabledByPackageName(
                            BACKGROUND_RATIONALE_CHANGE_ID,
                            packageName,
                            userId
                        )
                    } catch (e: RemoteException) {
                        Slog.e(
                            LOG_TAG,
                            "shouldShowRequestPermissionRationale: Unable to check if" +
                                " compatibility change is enabled",
                            e
                        )
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
        deviceId: String,
        userId: Int
    ) {
        val callingUid = Binder.getCallingUid()
        if (
            PermissionManager.DEBUG_TRACE_PERMISSION_UPDATES &&
                PermissionManager.shouldTraceGrant(packageName, permissionName, userId)
        ) {
            val flagMaskString =
                DebugUtils.flagsToString(
                    PackageManager::class.java,
                    "FLAG_PERMISSION_",
                    flagMask.toLong()
                )
            val flagValuesString =
                DebugUtils.flagsToString(
                    PackageManager::class.java,
                    "FLAG_PERMISSION_",
                    flagValues.toLong()
                )
            val callingUidName = packageManagerInternal.getNameForUid(callingUid)
            Slog.i(
                LOG_TAG,
                "updatePermissionFlags(packageName = $packageName," +
                    " permissionName = $permissionName, flagMask = $flagMaskString," +
                    " flagValues = $flagValuesString, userId = $userId," +
                    " deviceId = $deviceId," +
                    " callingUid = $callingUidName ($callingUid))",
                RuntimeException()
            )
        }

        if (!userManagerInternal.exists(userId)) {
            Slog.w(LOG_TAG, "updatePermissionFlags: Unknown user $userId")
            return
        }

        enforceCallingOrSelfCrossUserPermission(
            userId,
            enforceFullPermission = true,
            enforceShellRestriction = true,
            "updatePermissionFlags"
        )
        enforceCallingOrSelfAnyPermission(
            "updatePermissionFlags",
            Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
        )

        // Different from the old implementation, which implicitly didn't allow modifying the
        // POLICY_FIXED flag if the caller is system or root UID, now we do allow that since system
        // and root UIDs are supposed to have all permissions including
        // ADJUST_RUNTIME_PERMISSIONS_POLICY.
        if (!isRootOrSystemUid(callingUid)) {
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
        if (
            androidPackage == null ||
                packageManagerInternal.filterAppAccess(packageName, callingUid, userId, false)
        ) {
            Slog.w(LOG_TAG, "updatePermissionFlags: Unknown package $packageName")
            return
        }

        // Different from the old implementation, which only allowed the system UID to modify the
        // following flags, we now allow the root UID as well since both should have all
        // permissions.
        val canUpdateSystemFlags = isRootOrSystemUid(callingUid)

        val isPermissionRequested =
            if (permissionName in androidPackage.requestedPermissions) {
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
                appId,
                userId,
                permissionName,
                deviceId,
                flagMask,
                flagValues,
                canUpdateSystemFlags,
                reportErrorForUnknownPermission = true,
                isPermissionRequested,
                "updatePermissionFlags",
                packageName
            )
        }
    }

    override fun updatePermissionFlagsForAllApps(flagMask: Int, flagValues: Int, userId: Int) {
        val callingUid = Binder.getCallingUid()
        if (PermissionManager.DEBUG_TRACE_PERMISSION_UPDATES) {
            val flagMaskString =
                DebugUtils.flagsToString(
                    PackageManager::class.java,
                    "FLAG_PERMISSION_",
                    flagMask.toLong()
                )
            val flagValuesString =
                DebugUtils.flagsToString(
                    PackageManager::class.java,
                    "FLAG_PERMISSION_",
                    flagValues.toLong()
                )
            val callingUidName = packageManagerInternal.getNameForUid(callingUid)
            Slog.i(
                LOG_TAG,
                "updatePermissionFlagsForAllApps(flagMask = $flagMaskString," +
                    " flagValues = $flagValuesString, userId = $userId," +
                    " callingUid = $callingUidName ($callingUid))",
                RuntimeException()
            )
        }

        if (!userManagerInternal.exists(userId)) {
            Slog.w(LOG_TAG, "updatePermissionFlagsForAllApps: Unknown user $userId")
            return
        }

        enforceCallingOrSelfCrossUserPermission(
            userId,
            enforceFullPermission = true,
            enforceShellRestriction = true,
            "updatePermissionFlagsForAllApps"
        )
        enforceCallingOrSelfAnyPermission(
            "updatePermissionFlagsForAllApps",
            Manifest.permission.GRANT_RUNTIME_PERMISSIONS,
            Manifest.permission.REVOKE_RUNTIME_PERMISSIONS
        )

        // Different from the old implementation, which only sanitized the SYSTEM_FIXED
        // flag, we now properly sanitize all flags as in updatePermissionFlags().
        val canUpdateSystemFlags = isRootOrSystemUid(callingUid)

        val packageStates = packageManagerLocal.withUnfilteredSnapshot().use { it.packageStates }
        service.mutateState {
            packageStates.forEach { (packageName, packageState) ->
                if (packageState.isApex) {
                    return@forEach
                }
                val androidPackage = packageState.androidPackage ?: return@forEach
                androidPackage.requestedPermissions.forEach { permissionName ->
                    updatePermissionFlags(
                        packageState.appId,
                        userId,
                        permissionName,
                        VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT,
                        flagMask,
                        flagValues,
                        canUpdateSystemFlags,
                        reportErrorForUnknownPermission = false,
                        isPermissionRequested = true,
                        "updatePermissionFlagsForAllApps",
                        packageName
                    )
                }
            }
        }
    }

    /** Update flags for a permission, without any validation on caller. */
    private fun MutateStateScope.updatePermissionFlags(
        appId: Int,
        userId: Int,
        permissionName: String,
        deviceId: String,
        flagMask: Int,
        flagValues: Int,
        canUpdateSystemFlags: Boolean,
        reportErrorForUnknownPermission: Boolean,
        isPermissionRequested: Boolean,
        methodName: String,
        packageName: String
    ) {
        @Suppress("NAME_SHADOWING") var flagMask = flagMask
        @Suppress("NAME_SHADOWING") var flagValues = flagValues
        // Only the system can change these flags and nothing else.
        if (!canUpdateSystemFlags) {
            // Different from the old implementation, which allowed non-system UIDs to remove (but
            // not add) permission restriction flags, we now consistently ignore them altogether.
            val ignoredMask =
                PackageManager.FLAG_PERMISSION_SYSTEM_FIXED or
                    PackageManager.FLAG_PERMISSION_GRANTED_BY_DEFAULT or
                    PackageManager.FLAG_PERMISSION_RESTRICTION_SYSTEM_EXEMPT or
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

        val oldFlags = getPermissionFlagsWithPolicy(appId, userId, permissionName, deviceId)
        if (!isPermissionRequested && oldFlags == 0) {
            Slog.w(
                LOG_TAG,
                "$methodName: Permission $permissionName isn't requested by package" +
                    " $packageName"
            )
            return
        }

        val newFlags = PermissionFlags.updateFlags(permission, oldFlags, flagMask, flagValues)
        setPermissionFlagsWithPolicy(appId, userId, permissionName, deviceId, newFlags)
    }

    override fun getAllowlistedRestrictedPermissions(
        packageName: String,
        allowlistedFlags: Int,
        userId: Int
    ): ArrayList<String>? {
        requireNotNull(packageName) { "packageName cannot be null" }
        Preconditions.checkFlagsArgument(allowlistedFlags, PERMISSION_ALLOWLIST_MASK)
        Preconditions.checkArgumentNonnegative(userId, "userId cannot be null")

        if (!userManagerInternal.exists(userId)) {
            Slog.w(LOG_TAG, "AllowlistedRestrictedPermission api: Unknown user $userId")
            return null
        }

        enforceCallingOrSelfCrossUserPermission(
            userId,
            enforceFullPermission = false,
            enforceShellRestriction = false,
            "getAllowlistedRestrictedPermissions"
        )

        val callingUid = Binder.getCallingUid()
        val packageState =
            packageManagerLocal.withFilteredSnapshot(callingUid, userId).use {
                it.getPackageState(packageName)
            }
                ?: return null
        val androidPackage = packageState.androidPackage ?: return null

        val isCallerPrivileged =
            context.checkCallingOrSelfPermission(
                Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS
            ) == PackageManager.PERMISSION_GRANTED

        if (
            allowlistedFlags.hasBits(PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM) &&
                !isCallerPrivileged
        ) {
            throw SecurityException(
                "Querying system allowlist requires " +
                    Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS
            )
        }

        val isCallerInstallerOnRecord =
            packageManagerInternal.isCallerInstallerOfRecord(androidPackage, callingUid)

        if (
            allowlistedFlags.hasAnyBit(
                PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE or
                    PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER
            )
        ) {
            if (!isCallerPrivileged && !isCallerInstallerOnRecord) {
                throw SecurityException(
                    "Querying upgrade or installer allowlist requires being installer on record" +
                        " or ${Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS}"
                )
            }
        }

        return getAllowlistedRestrictedPermissionsUnchecked(
            packageState.appId,
            allowlistedFlags,
            userId
        )
    }

    private fun GetStateScope.getPermissionFlagsWithPolicy(
        appId: Int,
        userId: Int,
        permissionName: String,
        deviceId: String,
    ): Int {
        return if (
            !Flags.deviceAwarePermissionApisEnabled() ||
                deviceId == VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT
        ) {
            with(policy) { getPermissionFlags(appId, userId, permissionName) }
        } else {
            if (permissionName !in PermissionManager.DEVICE_AWARE_PERMISSIONS) {
                Slog.i(
                    LOG_TAG,
                    "$permissionName is not device aware permission, " +
                        " get the flags for default device."
                )
                return with(policy) { getPermissionFlags(appId, userId, permissionName) }
            }
            with(devicePolicy) { getPermissionFlags(appId, deviceId, userId, permissionName) }
        }
    }

    private fun MutateStateScope.setPermissionFlagsWithPolicy(
        appId: Int,
        userId: Int,
        permissionName: String,
        deviceId: String,
        flags: Int
    ): Boolean {
        return if (
            !Flags.deviceAwarePermissionApisEnabled() ||
                deviceId == VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT
        ) {
            with(policy) { setPermissionFlags(appId, userId, permissionName, flags) }
        } else {
            if (permissionName !in PermissionManager.DEVICE_AWARE_PERMISSIONS) {
                Slog.i(
                    LOG_TAG,
                    "$permissionName is not device aware permission, " +
                        " set the flags for default device."
                )
                return with(policy) { setPermissionFlags(appId, userId, permissionName, flags) }
            }

            with(devicePolicy) {
                setPermissionFlags(appId, deviceId, userId, permissionName, flags)
            }
        }
    }

    /**
     * This method does not enforce checks on the caller, should only be called after required
     * checks.
     */
    private fun getAllowlistedRestrictedPermissionsUnchecked(
        appId: Int,
        allowlistedFlags: Int,
        userId: Int
    ): ArrayList<String>? {
        val permissionFlags =
            service.getState { with(policy) { getUidPermissionFlags(appId, userId) } }
                ?: return null

        var queryFlags = 0
        if (allowlistedFlags.hasBits(PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM)) {
            queryFlags = queryFlags or PermissionFlags.SYSTEM_EXEMPT
        }
        if (allowlistedFlags.hasBits(PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE)) {
            queryFlags = queryFlags or PermissionFlags.UPGRADE_EXEMPT
        }
        if (allowlistedFlags.hasBits(PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER)) {
            queryFlags = queryFlags or PermissionFlags.INSTALLER_EXEMPT
        }

        return permissionFlags.mapNotNullIndexedTo(ArrayList()) { _, permissionName, flags ->
            if (flags.hasAnyBit(queryFlags)) permissionName else null
        }
    }

    override fun addAllowlistedRestrictedPermission(
        packageName: String,
        permissionName: String,
        allowlistedFlags: Int,
        userId: Int
    ): Boolean {
        requireNotNull(permissionName) { "permissionName cannot be null" }
        if (!enforceRestrictedPermission(permissionName)) {
            return false
        }

        val permissionNames =
            getAllowlistedRestrictedPermissions(packageName, allowlistedFlags, userId)
                ?: ArrayList(1)

        if (permissionName !in permissionNames) {
            permissionNames += permissionName
            return setAllowlistedRestrictedPermissions(
                packageName,
                permissionNames,
                allowlistedFlags,
                userId,
                isAddingPermission = true
            )
        }
        return false
    }

    private fun addAllowlistedRestrictedPermissionsUnchecked(
        androidPackage: AndroidPackage,
        appId: Int,
        permissionNames: List<String>,
        userId: Int
    ) {
        val newPermissionNames =
            getAllowlistedRestrictedPermissionsUnchecked(
                    appId,
                    PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER,
                    userId
                )
                ?.let { ArraySet(permissionNames).apply { this += it }.toList() }
                ?: permissionNames

        setAllowlistedRestrictedPermissionsUnchecked(
            androidPackage,
            appId,
            newPermissionNames,
            PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER,
            userId
        )
    }

    override fun removeAllowlistedRestrictedPermission(
        packageName: String,
        permissionName: String,
        allowlistedFlags: Int,
        userId: Int
    ): Boolean {
        requireNotNull(permissionName) { "permissionName cannot be null" }
        if (!enforceRestrictedPermission(permissionName)) {
            return false
        }

        val permissions =
            getAllowlistedRestrictedPermissions(packageName, allowlistedFlags, userId)
                ?: return false

        if (permissions.remove(permissionName)) {
            return setAllowlistedRestrictedPermissions(
                packageName,
                permissions,
                allowlistedFlags,
                userId,
                isAddingPermission = false
            )
        }

        return false
    }

    private fun enforceRestrictedPermission(permissionName: String): Boolean {
        val permission = service.getState { with(policy) { getPermissions()[permissionName] } }
        if (permission == null) {
            Slog.w(LOG_TAG, "permission definition for $permissionName does not exist")
            return false
        }

        if (
            packageManagerLocal.withFilteredSnapshot().use {
                it.getPackageState(permission.packageName)
            } == null
        ) {
            return false
        }

        val isImmutablyRestrictedPermission =
            permission.isHardOrSoftRestricted && permission.isImmutablyRestricted
        if (
            isImmutablyRestrictedPermission &&
                context.checkCallingOrSelfPermission(
                    Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS
                ) != PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(
                "Cannot modify allowlist of an immutably restricted permission: ${permission.name}"
            )
        }

        return true
    }

    private fun setAllowlistedRestrictedPermissions(
        packageName: String,
        permissionNames: List<String>,
        allowlistedFlags: Int,
        userId: Int,
        isAddingPermission: Boolean
    ): Boolean {
        Preconditions.checkArgument(allowlistedFlags.countOneBits() == 1)

        val isCallerPrivileged =
            context.checkCallingOrSelfPermission(
                Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS
            ) == PackageManager.PERMISSION_GRANTED

        val callingUid = Binder.getCallingUid()
        val packageState =
            packageManagerLocal.withFilteredSnapshot(callingUid, userId).use { snapshot ->
                snapshot.packageStates[packageName] ?: return false
            }
        val androidPackage = packageState.androidPackage ?: return false

        val isCallerInstallerOnRecord =
            packageManagerInternal.isCallerInstallerOfRecord(androidPackage, callingUid)

        if (allowlistedFlags.hasBits(PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE)) {
            if (!isCallerPrivileged && !isCallerInstallerOnRecord) {
                throw SecurityException(
                    "Modifying upgrade allowlist requires being installer on record or " +
                        Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS
                )
            }
            if (isAddingPermission && !isCallerPrivileged) {
                throw SecurityException(
                    "Adding to upgrade allowlist requires" +
                        Manifest.permission.WHITELIST_RESTRICTED_PERMISSIONS
                )
            }
        }

        setAllowlistedRestrictedPermissionsUnchecked(
            androidPackage,
            packageState.appId,
            permissionNames,
            allowlistedFlags,
            userId
        )

        return true
    }

    /**
     * This method does not enforce checks on the caller, should only be called after required
     * checks.
     */
    private fun setAllowlistedRestrictedPermissionsUnchecked(
        androidPackage: AndroidPackage,
        appId: Int,
        permissionNames: List<String>,
        allowlistedFlags: Int,
        userId: Int
    ) {
        var exemptMask = 0
        if (allowlistedFlags.hasBits(PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM)) {
            exemptMask = exemptMask or PermissionFlags.SYSTEM_EXEMPT
        }
        if (allowlistedFlags.hasBits(PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE)) {
            exemptMask = exemptMask or PermissionFlags.UPGRADE_EXEMPT
        }
        if (allowlistedFlags.hasBits(PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER)) {
            exemptMask = exemptMask or PermissionFlags.INSTALLER_EXEMPT
        }

        service.mutateState {
            with(policy) {
                val permissions = getPermissions()
                androidPackage.requestedPermissions.forEachIndexed { _, requestedPermission ->
                    val permission = permissions[requestedPermission]
                    if (permission == null || !permission.isHardOrSoftRestricted) {
                        return@forEachIndexed
                    }

                    var exemptFlags = if (requestedPermission in permissionNames) exemptMask else 0
                    updatePermissionExemptFlags(appId, userId, permission, exemptMask, exemptFlags)
                }
            }
        }
    }

    override fun resetRuntimePermissions(androidPackage: AndroidPackage, userId: Int) {
        service.mutateState {
            with(policy) { resetRuntimePermissions(androidPackage.packageName, userId) }
            with(devicePolicy) { resetRuntimePermissions(androidPackage.packageName, userId) }
        }
    }

    override fun resetRuntimePermissionsForUser(userId: Int) {
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            service.mutateState {
                snapshot.packageStates.forEach { (_, packageState) ->
                    if (packageState.isApex) {
                        return@forEach
                    }
                    with(policy) { resetRuntimePermissions(packageState.packageName, userId) }
                    with(devicePolicy) { resetRuntimePermissions(packageState.packageName, userId) }
                }
            }
        }
    }

    override fun addOnPermissionsChangeListener(listener: IOnPermissionsChangeListener) {
        context.enforceCallingOrSelfPermission(
            Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS,
            "addOnPermissionsChangeListener"
        )

        onPermissionsChangeListeners.addListener(listener)
    }

    override fun removeOnPermissionsChangeListener(listener: IOnPermissionsChangeListener) {
        context.enforceCallingOrSelfPermission(
            Manifest.permission.OBSERVE_GRANT_REVOKE_PERMISSIONS,
            "removeOnPermissionsChangeListener"
        )

        onPermissionsChangeListeners.removeListener(listener)
    }

    override fun getSplitPermissions(): List<SplitPermissionInfoParcelable> {
        return PermissionManager.splitPermissionInfoListToParcelableList(
            systemConfig.splitPermissions
        )
    }

    override fun getAppOpPermissionPackages(permissionName: String): Array<String> {
        requireNotNull(permissionName) { "permissionName cannot be null" }
        val packageNames = ArraySet<String>()

        val permission = service.getState { with(policy) { getPermissions()[permissionName] } }
        if (permission == null || !permission.isAppOp) {
            packageNames.toTypedArray()
        }

        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            snapshot.packageStates.forEach { (_, packageState) ->
                if (packageState.isApex) {
                    return@forEach
                }
                val androidPackage = packageState.androidPackage ?: return@forEach
                if (permissionName in androidPackage.requestedPermissions) {
                    packageNames += androidPackage.packageName
                }
            }
        }

        return packageNames.toTypedArray()
    }

    override fun getAllAppOpPermissionPackages(): Map<String, Set<String>> {
        val appOpPermissionPackageNames = ArrayMap<String, ArraySet<String>>()
        val permissions = service.getState { with(policy) { getPermissions() } }
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            snapshot.packageStates.forEach packageStates@{ (_, packageState) ->
                if (packageState.isApex) {
                    return@packageStates
                }
                val androidPackage = packageState.androidPackage ?: return@packageStates
                androidPackage.requestedPermissions.forEach requestedPermissions@{ permissionName ->
                    val permission = permissions[permissionName] ?: return@requestedPermissions
                    if (permission.isAppOp) {
                        val packageNames =
                            appOpPermissionPackageNames.getOrPut(permissionName) { ArraySet() }
                        packageNames += androidPackage.packageName
                    }
                }
            }
        }
        return appOpPermissionPackageNames
    }

    override fun backupRuntimePermissions(userId: Int): ByteArray? {
        Preconditions.checkArgumentNonnegative(userId, "userId cannot be null")
        val backup = CompletableFuture<ByteArray>()
        permissionControllerManager.getRuntimePermissionBackup(
            UserHandle.of(userId),
            PermissionThread.getExecutor(),
            backup::complete
        )

        return try {
            backup.get(BACKUP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            when (e) {
                is TimeoutException,
                is InterruptedException,
                is ExecutionException -> {
                    Slog.e(LOG_TAG, "Cannot create permission backup for user $userId", e)
                    null
                }
                else -> throw e
            }
        }
    }

    override fun restoreRuntimePermissions(backup: ByteArray, userId: Int) {
        requireNotNull(backup) { "backup" }
        Preconditions.checkArgumentNonnegative(userId, "userId")

        synchronized(isDelayedPermissionBackupFinished) {
            isDelayedPermissionBackupFinished -= userId
        }
        permissionControllerManager.stageAndApplyRuntimePermissionsBackup(
            backup,
            UserHandle.of(userId)
        )
    }

    override fun restoreDelayedRuntimePermissions(packageName: String, userId: Int) {
        requireNotNull(packageName) { "packageName" }
        Preconditions.checkArgumentNonnegative(userId, "userId")

        synchronized(isDelayedPermissionBackupFinished) {
            if (isDelayedPermissionBackupFinished.get(userId, false)) {
                return
            }
        }
        permissionControllerManager.applyStagedRuntimePermissionBackup(
            packageName,
            UserHandle.of(userId),
            PermissionThread.getExecutor()
        ) { hasMoreBackup ->
            if (hasMoreBackup) {
                return@applyStagedRuntimePermissionBackup
            }
            synchronized(isDelayedPermissionBackupFinished) {
                isDelayedPermissionBackupFinished.put(userId, true)
            }
        }
    }

    override fun dump(fd: FileDescriptor, pw: PrintWriter, args: Array<out String>?) {
        if (!DumpUtils.checkDumpPermission(context, LOG_TAG, pw)) {
            return
        }

        val writer = IndentingPrintWriter(pw, "  ")

        if (args.isNullOrEmpty() || args[0] == "-a") {
            service.getState {
                writer.dumpSystemState(state)
                getAllAppIdPackageNames(state).forEachIndexed { _, appId, packageNames ->
                    if (appId != Process.INVALID_UID) {
                        writer.dumpAppIdState(appId, state, packageNames)
                    }
                }
            }
        } else if (args[0] == "--app-id" && args.size == 2) {
            val appId = args[1].toInt()
            service.getState {
                val appIdPackageNames = getAllAppIdPackageNames(state)
                if (appId in appIdPackageNames) {
                    writer.dumpAppIdState(appId, state, appIdPackageNames[appId])
                } else {
                    writer.println("Unknown app ID $appId.")
                }
            }
        } else if (args[0] == "--package" && args.size == 2) {
            val packageName = args[1]
            service.getState {
                val packageState = state.externalState.packageStates[packageName]
                if (packageState != null) {
                    writer.dumpAppIdState(packageState.appId, state, indexedSetOf(packageName))
                } else {
                    writer.println("Unknown package $packageName.")
                }
            }
        } else {
            writer.println(
                "Usage: dumpsys permissionmgr [--app-id <APP_ID>] [--package <PACKAGE_NAME>]"
            )
        }
    }

    private fun getAllAppIdPackageNames(
        state: AccessState
    ): IndexedMap<Int, MutableIndexedSet<String>> {
        val appIds = MutableIndexedSet<Int>()

        val packageStates = packageManagerLocal.withUnfilteredSnapshot().use { it.packageStates }
        state.userStates.forEachIndexed { _, _, userState ->
            userState.appIdPermissionFlags.forEachIndexed { _, appId, _ -> appIds.add(appId) }
            userState.appIdAppOpModes.forEachIndexed { _, appId, _ -> appIds.add(appId) }
            userState.packageVersions.forEachIndexed packageVersions@{ _, packageName, _ ->
                val appId = packageStates[packageName]?.appId ?: return@packageVersions
                appIds.add(appId)
            }
            userState.packageAppOpModes.forEachIndexed packageAppOpModes@{ _, packageName, _ ->
                val appId = packageStates[packageName]?.appId ?: return@packageAppOpModes
                appIds.add(appId)
            }
        }

        val appIdPackageNames = MutableIndexedMap<Int, MutableIndexedSet<String>>()
        packageStates.forEach { (_, packageState) ->
            if (packageState.isApex) {
                return@forEach
            }
            appIdPackageNames
                .getOrPut(packageState.appId) { MutableIndexedSet() }
                .add(packageState.packageName)
        }
        // add non-package app IDs which might not be reported by package manager.
        appIds.forEachIndexed { _, appId ->
            appIdPackageNames.getOrPut(appId) { MutableIndexedSet() }
        }

        return appIdPackageNames
    }

    private fun IndentingPrintWriter.dumpSystemState(state: AccessState) {
        println("Permissions:")
        withIndent {
            state.systemState.permissions.forEachIndexed { _, _, permission ->
                val protectionLevel = PermissionInfo.protectionToString(permission.protectionLevel)
                println(
                    "${permission.name}: " +
                        "type=${Permission.typeToString(permission.type)}, " +
                        "packageName=${permission.packageName}, " +
                        "appId=${permission.appId}, " +
                        "gids=${permission.gids.contentToString()}, " +
                        "protectionLevel=[$protectionLevel], " +
                        "flags=${PermissionInfo.flagsToString(permission.permissionInfo.flags)}"
                )
            }
        }

        println("Permission groups:")
        withIndent {
            state.systemState.permissionGroups.forEachIndexed { _, _, permissionGroup ->
                println("${permissionGroup.name}: " + "packageName=${permissionGroup.packageName}")
            }
        }

        println("Permission trees:")
        withIndent {
            state.systemState.permissionTrees.forEachIndexed { _, _, permissionTree ->
                println(
                    "${permissionTree.name}: " +
                        "packageName=${permissionTree.packageName}, " +
                        "appId=${permissionTree.appId}"
                )
            }
        }
    }

    private fun IndentingPrintWriter.dumpAppIdState(
        appId: Int,
        state: AccessState,
        packageNames: IndexedSet<String>?
    ) {
        println("App ID: $appId")
        withIndent {
            state.userStates.forEachIndexed { _, userId, userState ->
                println("User: $userId")
                withIndent {
                    println("Permissions:")
                    withIndent {
                        userState.appIdPermissionFlags[appId]?.forEachIndexed {
                            _,
                            permissionName,
                            flags ->
                            val isGranted = PermissionFlags.isPermissionGranted(flags)
                            println(
                                "$permissionName: granted=$isGranted, flags=" +
                                    PermissionFlags.toString(flags)
                            )
                        }
                    }

                    userState.appIdDevicePermissionFlags[appId]?.forEachIndexed {
                        _,
                        deviceId,
                        devicePermissionFlags ->
                        println("Permissions (Device $deviceId):")
                        withIndent {
                            devicePermissionFlags.forEachIndexed { _, permissionName, flags ->
                                val isGranted = PermissionFlags.isPermissionGranted(flags)
                                println(
                                    "$permissionName: granted=$isGranted, flags=" +
                                        PermissionFlags.toString(flags)
                                )
                            }
                        }
                    }

                    println("App ops:")
                    withIndent {
                        userState.appIdAppOpModes[appId]?.forEachIndexed { _, appOpName, appOpMode
                            ->
                            println("$appOpName: mode=${AppOpsManager.modeToName(appOpMode)}")
                        }
                    }

                    packageNames?.forEachIndexed { _, packageName ->
                        println("Package: $packageName")
                        withIndent {
                            println("version=${userState.packageVersions[packageName]}")
                            println("App ops:")
                            withIndent {
                                userState.packageAppOpModes[packageName]?.forEachIndexed {
                                    _,
                                    appOpName,
                                    appOpMode ->
                                    val modeName = AppOpsManager.modeToName(appOpMode)
                                    println("$appOpName: mode=$modeName")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private inline fun IndentingPrintWriter.withIndent(block: IndentingPrintWriter.() -> Unit) {
        increaseIndent()
        block()
        decreaseIndent()
    }

    override fun getPermissionTEMP(permissionName: String): LegacyPermission2? {
        val permission =
            service.getState { with(policy) { getPermissions()[permissionName] } } ?: return null

        return LegacyPermission2(
            permission.permissionInfo,
            permission.type,
            permission.isReconciled,
            permission.appId,
            permission.gids,
            permission.areGidsPerUser
        )
    }

    override fun getLegacyPermissions(): List<LegacyPermission> =
        service
            .getState { with(policy) { getPermissions() } }
            .mapIndexedTo(ArrayList()) { _, _, permission ->
                LegacyPermission(
                    permission.permissionInfo,
                    permission.type,
                    permission.appId,
                    permission.gids
                )
            }

    override fun readLegacyPermissionsTEMP(legacyPermissionSettings: LegacyPermissionSettings) {
        // Package settings has been read when this method is called.
        service.initialize()
    }

    override fun writeLegacyPermissionsTEMP(legacyPermissionSettings: LegacyPermissionSettings) {
        service.getState {
            val permissions = with(policy) { getPermissions() }
            legacyPermissionSettings.replacePermissions(toLegacyPermissions(permissions))
            val permissionTrees = with(policy) { getPermissionTrees() }
            legacyPermissionSettings.replacePermissionTrees(toLegacyPermissions(permissionTrees))
        }
    }

    private fun toLegacyPermissions(
        permissions: IndexedMap<String, Permission>
    ): List<LegacyPermission> =
        permissions.mapIndexedTo(ArrayList()) { _, _, permission ->
            // We don't need to provide UID and GIDs, which are only retrieved when dumping.
            LegacyPermission(permission.permissionInfo, permission.type, 0, EmptyArray.INT)
        }

    override fun getLegacyPermissionState(appId: Int): LegacyPermissionState {
        val legacyState = LegacyPermissionState()
        val userIds = userManagerService.userIdsIncludingPreCreated
        service.getState {
            val permissions = with(policy) { getPermissions() }
            userIds.forEachIndexed { _, userId ->
                val permissionFlags =
                    with(policy) { getUidPermissionFlags(appId, userId) } ?: return@forEachIndexed

                permissionFlags.forEachIndexed permissionFlags@{ _, permissionName, flags ->
                    val permission = permissions[permissionName] ?: return@permissionFlags
                    val legacyPermissionState =
                        LegacyPermissionState.PermissionState(
                            permissionName,
                            permission.isRuntime,
                            PermissionFlags.isPermissionGranted(flags),
                            PermissionFlags.toApiFlags(flags)
                        )
                    legacyState.putPermissionState(legacyPermissionState, userId)
                }
            }
        }
        return legacyState
    }

    override fun readLegacyPermissionStateTEMP() {}

    override fun writeLegacyPermissionStateTEMP() {}

    override fun getDefaultPermissionGrantFingerprint(userId: Int): String? =
        service.getState { state.userStates[userId]!!.defaultPermissionGrantFingerprint }

    override fun setDefaultPermissionGrantFingerprint(fingerprint: String, userId: Int) {
        service.mutateState {
            newState.mutateUserState(userId)!!.setDefaultPermissionGrantFingerprint(fingerprint)
        }
    }

    override fun onSystemReady() {
        service.onSystemReady()

        virtualDeviceManagerInternal =
            LocalServices.getService(VirtualDeviceManagerInternal::class.java)
        virtualDeviceManagerInternal?.allPersistentDeviceIds?.let { persistentDeviceIds ->
            service.mutateState {
                with(devicePolicy) { trimDevicePermissionStates(persistentDeviceIds) }
            }
        }
        virtualDeviceManagerInternal?.registerPersistentDeviceIdRemovedListener { deviceId ->
            service.mutateState { with(devicePolicy) { onDeviceIdRemoved(deviceId) } }
        }

        permissionControllerManager =
            PermissionControllerManager(context, PermissionThread.getHandler())
    }

    override fun onUserCreated(userId: Int) {
        withCorkedPackageInfoCache { service.onUserAdded(userId) }
    }

    override fun onUserRemoved(userId: Int) {
        service.onUserRemoved(userId)
    }

    override fun onStorageVolumeMounted(volumeUuid: String, fingerprintChanged: Boolean) {
        val packageNames: List<String>
        synchronized(storageVolumeLock) {
            // Removing the storageVolumePackageNames entry because we expect onPackageAdded()
            // to always be called before onStorageVolumeMounted().
            packageNames = storageVolumePackageNames.remove(volumeUuid) ?: emptyList()
            mountedStorageVolumes += volumeUuid
        }
        withCorkedPackageInfoCache {
            service.onStorageVolumeMounted(volumeUuid, packageNames, fingerprintChanged)
        }
    }

    override fun onPackageAdded(
        packageState: PackageState,
        isInstantApp: Boolean,
        oldPackage: AndroidPackage?
    ) {
        if (packageState.isApex) {
            return
        }

        synchronized(storageVolumeLock) {
            // Accumulating the package names here because we want to maintain the same call order
            // of onPackageAdded() and reuse this order in onStorageVolumeAdded(). We need the
            // packages to be iterated in onStorageVolumeAdded() in the same order so that the
            // ownership of permissions is consistent.
            storageVolumePackageNames.getOrPut(packageState.volumeUuid) { mutableListOf() } +=
                packageState.packageName
            if (packageState.volumeUuid !in mountedStorageVolumes) {
                // Wait for the storage volume to be mounted and batch the state mutation there.
                return
            }
        }
        service.onPackageAdded(packageState.packageName)
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
        if (androidPackage.isApex) {
            return
        }

        if (params === PermissionManagerServiceInternal.PackageInstalledParams.DEFAULT) {
            // TODO: We should actually stop calling onPackageInstalled() when we are passing
            //  PackageInstalledParams.DEFAULT in InstallPackageHelper, because there's actually no
            //  installer in those cases of system app installs, and the default params won't
            //  allowlist any permissions which means the original UPGRADE_EXEMPT will be dropped
            //  without any INSTALLER_EXEMPT added. However, we can't do that right now because the
            //  old permission subsystem still depends on this method being called to set up the
            //  permission state for the first time (which we are doing in onPackageAdded() or
            //  onStorageVolumeMounted() now).
            return
        }

        synchronized(storageVolumeLock) {
            if (androidPackage.volumeUuid !in mountedStorageVolumes) {
                // Wait for the storage volume to be mounted and batch the state mutation there.
                // PackageInstalledParams won't exist when packages are being scanned instead of
                // being installed by an installer.
                return
            }
        }
        val userIds =
            if (userId == UserHandle.USER_ALL) {
                userManagerService.userIdsIncludingPreCreated
            } else {
                intArrayOf(userId)
            }
        @Suppress("NAME_SHADOWING")
        userIds.forEach { userId -> service.onPackageInstalled(androidPackage.packageName, userId) }

        @Suppress("NAME_SHADOWING")
        userIds.forEach { userId ->
            // TODO: Remove when this callback receives packageState directly.
            val packageState =
                packageManagerInternal.getPackageStateInternal(androidPackage.packageName)!!
            addAllowlistedRestrictedPermissionsUnchecked(
                androidPackage,
                packageState.appId,
                params.allowlistedRestrictedPermissions,
                userId
            )
            setRequestedPermissionStates(packageState, userId, params.permissionStates)
        }
    }

    override fun onPackageUninstalled(
        packageName: String,
        appId: Int,
        packageState: PackageState,
        androidPackage: AndroidPackage?,
        sharedUserPkgs: List<AndroidPackage>,
        userId: Int
    ) {
        if (packageState.isApex) {
            return
        }

        val userIds =
            if (userId == UserHandle.USER_ALL) {
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

    private inline fun <T> withCorkedPackageInfoCache(block: () -> T): T {
        PackageManager.corkPackageInfoCache()
        try {
            return block()
        } finally {
            PackageManager.uncorkPackageInfoCache()
        }
    }

    /** Check whether a UID is root or system UID. */
    private fun isRootOrSystemUid(uid: Int) =
        when (UserHandle.getAppId(uid)) {
            Process.ROOT_UID,
            Process.SYSTEM_UID -> true
            else -> false
        }

    /** Check whether a UID is shell UID. */
    private fun isShellUid(uid: Int) = UserHandle.getAppId(uid) == Process.SHELL_UID

    /** Check whether a UID is root, system or shell UID. */
    private fun isRootOrSystemOrShellUid(uid: Int) = isRootOrSystemUid(uid) || isShellUid(uid)

    /**
     * This method should typically only be used when granting or revoking permissions, since the
     * app may immediately restart after this call.
     *
     * If you're doing surgery on app code/data, use [PackageFreezer] to guard your work against the
     * app being relaunched.
     */
    private fun killUid(uid: Int, reason: String) {
        val activityManager = ActivityManager.getService()
        if (activityManager != null) {
            val appId = UserHandle.getAppId(uid)
            val userId = UserHandle.getUserId(uid)
            Binder::class.withClearedCallingIdentity {
                try {
                    activityManager.killUidForPermissionChange(appId, userId, reason)
                } catch (e: RemoteException) {
                    /* ignore - same process */
                }
            }
        }
    }

    /** @see PackageManagerLocal.withFilteredSnapshot */
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

    /** Check whether a UID belongs to an instant app. */
    private fun PackageManagerLocal.UnfilteredSnapshot.isUidInstantApp(uid: Int): Boolean =
        // Unfortunately we don't have the API for getting the owner UID of an isolated UID or the
        // API for getting the SharedUserApi object for an app ID yet, so for now we just keep
        // calling the old API.
        packageManagerInternal.getInstantAppPackageName(uid) != null

    /** Check whether a package is visible to a UID within the same user as the UID. */
    private fun PackageManagerLocal.UnfilteredSnapshot.isPackageVisibleToUid(
        packageName: String,
        uid: Int
    ): Boolean = isPackageVisibleToUid(packageName, UserHandle.getUserId(uid), uid)

    /** Check whether a package in a particular user is visible to a UID. */
    private fun PackageManagerLocal.UnfilteredSnapshot.isPackageVisibleToUid(
        packageName: String,
        userId: Int,
        uid: Int
    ): Boolean = filtered(uid, userId).use { it.getPackageState(packageName) != null }

    /** @see PackageManagerLocal.UnfilteredSnapshot.filtered */
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
            val permissionName =
                if (enforceFullPermission) {
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL
                } else {
                    Manifest.permission.INTERACT_ACROSS_USERS
                }
            if (
                context.checkCallingOrSelfPermission(permissionName) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                val exceptionMessage = buildString {
                    if (message != null) {
                        append(message)
                        append(": ")
                    }
                    append("Neither user ")
                    append(callingUid)
                    append(" nor current process has ")
                    append(permissionName)
                    append(" to access user ")
                    append(userId)
                }
                throw SecurityException(exceptionMessage)
            }
        }
        if (enforceShellRestriction && isShellUid(callingUid)) {
            val isShellRestricted =
                userManagerInternal.hasUserRestriction(
                    UserManager.DISALLOW_DEBUGGING_FEATURES,
                    userId
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
        val hasAnyPermission =
            permissionNames.any { permissionName ->
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

    /** Callback invoked when interesting actions have been taken on a permission. */
    private inner class OnPermissionFlagsChangedListener :
        AppIdPermissionPolicy.OnPermissionFlagsChangedListener,
        DevicePermissionPolicy.OnDevicePermissionFlagsChangedListener {
        private var isPermissionFlagsChanged = false

        private val runtimePermissionChangedUidDevices = MutableIntMap<MutableSet<String>>()
        // Mapping from UID to whether only notifications permissions are revoked.
        private val runtimePermissionRevokedUids = SparseBooleanArray()
        private val gidsChangedUids = MutableIntSet()

        private var isKillRuntimePermissionRevokedUidsSkipped = false
        private val killRuntimePermissionRevokedUidsReasons = ArraySet<String>()

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
            onDevicePermissionFlagsChanged(
                appId,
                userId,
                VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT,
                permissionName,
                oldFlags,
                newFlags
            )
        }

        override fun onDevicePermissionFlagsChanged(
            appId: Int,
            userId: Int,
            deviceId: String,
            permissionName: String,
            oldFlags: Int,
            newFlags: Int
        ) {
            isPermissionFlagsChanged = true

            val uid = UserHandle.getUid(userId, appId)
            val permission =
                service.getState { with(policy) { getPermissions()[permissionName] } } ?: return
            val wasPermissionGranted = PermissionFlags.isPermissionGranted(oldFlags)
            val isPermissionGranted = PermissionFlags.isPermissionGranted(newFlags)

            if (permission.isRuntime) {
                // Different from the old implementation, which notifies the listeners when the
                // permission flags have changed for a non-runtime permission, now we no longer do
                // that because permission flags are only for runtime permissions and the listeners
                // aren't being notified of non-runtime permission grant state changes anyway.
                if (wasPermissionGranted && !isPermissionGranted) {
                    runtimePermissionRevokedUids[uid] =
                        permissionName in NOTIFICATIONS_PERMISSIONS &&
                            runtimePermissionRevokedUids.get(uid, true)
                }
                runtimePermissionChangedUidDevices.getOrPut(uid) { mutableSetOf() } += deviceId
            }

            if (permission.hasGids && !wasPermissionGranted && isPermissionGranted) {
                gidsChangedUids += uid
            }
        }

        override fun onStateMutated() {
            if (isPermissionFlagsChanged) {
                PackageManager.invalidatePackageInfoCache()
                isPermissionFlagsChanged = false
            }

            runtimePermissionChangedUidDevices.forEachIndexed { _, uid, persistentDeviceIds ->
                persistentDeviceIds.forEach { deviceId ->
                    onPermissionsChangeListeners.onPermissionsChanged(uid, deviceId)
                }
            }
            runtimePermissionChangedUidDevices.clear()

            if (!isKillRuntimePermissionRevokedUidsSkipped) {
                val reason =
                    if (killRuntimePermissionRevokedUidsReasons.isNotEmpty()) {
                        killRuntimePermissionRevokedUidsReasons.joinToString(", ")
                    } else {
                        PermissionManager.KILL_APP_REASON_PERMISSIONS_REVOKED
                    }
                runtimePermissionRevokedUids.forEachIndexed {
                    _,
                    uid,
                    areOnlyNotificationsPermissionsRevoked ->
                    handler.post {
                        if (
                            areOnlyNotificationsPermissionsRevoked &&
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
            if (
                checkUidPermission(
                    uid,
                    Manifest.permission.BACKUP,
                    VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return false
            }
            return try {
                val contentResolver = context.contentResolver
                val userId = UserHandle.getUserId(uid)
                val isInSetup =
                    Settings.Secure.getIntForUser(
                        contentResolver,
                        Settings.Secure.USER_SETUP_COMPLETE,
                        userId
                    ) == 0
                val isInDeferredSetup =
                    Settings.Secure.getIntForUser(
                        contentResolver,
                        Settings.Secure.USER_SETUP_PERSONALIZATION_STATE,
                        userId
                    ) == Settings.Secure.USER_SETUP_PERSONALIZATION_STARTED
                isInSetup || isInDeferredSetup
            } catch (e: Settings.SettingNotFoundException) {
                Slog.w(LOG_TAG, "Failed to check if the user is in restore: $e")
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
                    val deviceId = msg.obj as String
                    handleOnPermissionsChanged(uid, deviceId)
                }
            }
        }

        private fun handleOnPermissionsChanged(uid: Int, deviceId: String) {
            listeners.broadcast { listener ->
                try {
                    listener.onPermissionsChanged(uid, deviceId)
                } catch (e: RemoteException) {
                    Slog.e(LOG_TAG, "Error when calling OnPermissionsChangeListener", e)
                }
            }
        }

        fun addListener(listener: IOnPermissionsChangeListener) {
            listeners.register(listener)
        }

        fun removeListener(listener: IOnPermissionsChangeListener) {
            listeners.unregister(listener)
        }

        fun onPermissionsChanged(uid: Int, deviceId: String) {
            if (listeners.registeredCallbackCount > 0) {
                obtainMessage(MSG_ON_PERMISSIONS_CHANGED, uid, 0, deviceId).sendToTarget()
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

        private val FULLER_PERMISSIONS =
            ArrayMap<String, String>().apply {
                this[Manifest.permission.ACCESS_COARSE_LOCATION] =
                    Manifest.permission.ACCESS_FINE_LOCATION
                this[Manifest.permission.INTERACT_ACROSS_USERS] =
                    Manifest.permission.INTERACT_ACROSS_USERS_FULL
            }

        private val NOTIFICATIONS_PERMISSIONS = arraySetOf(Manifest.permission.POST_NOTIFICATIONS)

        private const val REVIEW_REQUIRED_FLAGS =
            PermissionFlags.LEGACY_GRANTED or PermissionFlags.IMPLICIT
        private const val UNREQUESTABLE_MASK =
            PermissionFlags.RESTRICTION_REVOKED or
                PermissionFlags.SYSTEM_FIXED or
                PermissionFlags.POLICY_FIXED or
                PermissionFlags.USER_FIXED

        private val BACKUP_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(60)

        /**
         * Cap the size of permission trees that 3rd party apps can define; in characters of text
         */
        private const val MAX_PERMISSION_TREE_FOOTPRINT = 32768

        private const val PERMISSION_ALLOWLIST_MASK =
            PackageManager.FLAG_PERMISSION_WHITELIST_UPGRADE or
                PackageManager.FLAG_PERMISSION_WHITELIST_SYSTEM or
                PackageManager.FLAG_PERMISSION_WHITELIST_INSTALLER

        fun getFullerPermission(permissionName: String): String? =
            FULLER_PERMISSIONS[permissionName]
    }
}
