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
import android.permission.IOnPermissionsChangeListener
import android.permission.PermissionManager
import android.provider.Settings
import android.util.IntArray as GrowingIntArray
import android.util.Log
import com.android.internal.compat.IPlatformCompat
import com.android.server.FgThread
import com.android.server.LocalManagerRegistry
import com.android.server.LocalServices
import com.android.server.ServiceThread
import com.android.server.SystemConfig
import com.android.server.permission.access.AccessCheckingService
import com.android.server.permission.access.PermissionUri
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.collection.* // ktlint-disable no-wildcard-imports
import com.android.server.permission.access.util.hasAnyBit
import com.android.server.permission.access.util.hasBits
import com.android.server.pm.PackageManagerLocal
import com.android.server.pm.UserManagerService
import com.android.server.pm.permission.LegacyPermission
import com.android.server.pm.permission.LegacyPermissionSettings
import com.android.server.pm.permission.LegacyPermissionState
import com.android.server.pm.permission.PermissionManagerServiceInterface
import com.android.server.pm.permission.PermissionManagerServiceInternal
import com.android.server.pm.pkg.AndroidPackage
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
    private lateinit var packageManagerInternal: PackageManagerInternal
    private lateinit var packageManagerLocal: PackageManagerLocal
    private lateinit var platformCompat: IPlatformCompat
    private lateinit var systemConfig: SystemConfig
    private lateinit var userManagerService: UserManagerService

    private val mountedStorageVolumes = IndexedSet<String?>()

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    private lateinit var onPermissionsChangeListeners: OnPermissionsChangeListeners
    private lateinit var onPermissionFlagsChangedListener: OnPermissionFlagsChangedListener

    fun initialize() {
        packageManagerInternal = LocalServices.getService(PackageManagerInternal::class.java)
        packageManagerLocal =
            LocalManagerRegistry.getManagerOrThrow(PackageManagerLocal::class.java)
        platformCompat = IPlatformCompat.Stub.asInterface(
            ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE)
        )
        systemConfig = SystemConfig.getInstance()
        userManagerService = UserManagerService.getInstance()

        handlerThread = ServiceThread(LOG_TAG, Process.THREAD_PRIORITY_BACKGROUND, true)
        handler = Handler(handlerThread.looper)

        onPermissionsChangeListeners = OnPermissionsChangeListeners(FgThread.get().looper)
        onPermissionFlagsChangedListener = OnPermissionFlagsChangedListener()
        policy.addOnPermissionFlagsChangedListener(onPermissionFlagsChangedListener)
    }

    override fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo> {
        val callingUid = Binder.getCallingUid()
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            if (snapshot.isUidInstantApp(callingUid)) {
                return emptyList()
            }

            val permissionGroups = service.getState {
                with(policy) { getPermissionGroups() }
            }

            return permissionGroups.mapNotNullIndexed { _, permissionGroup ->
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

            val callingAppId = UserHandle.getAppId(callingUid)
            val opPackage = snapshot.packageStates[opPackageName]?.androidPackage
            targetSdkVersion = when {
                // System sees all flags.
                callingAppId == Process.ROOT_UID || callingAppId == Process.SYSTEM_UID ||
                    callingAppId == Process.SHELL_UID -> Build.VERSION_CODES.CUR_DEVELOPMENT
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
        val callingUid = Binder.getCallingUid()
        packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            if (snapshot.isUidInstantApp(callingUid)) {
                return null
            }

            if (permissionGroupName != null) {
                val permissionGroup = service.getState {
                    with(policy) { getPermissionGroups()[permissionGroupName] }
                } ?: return null

                if (!snapshot.isPackageVisibleToUid(permissionGroup.packageName, callingUid)) {
                    return null
                }
            }

            val permissions = service.getState {
                with(policy) { getPermissions() }
            }

            return permissions.mapNotNullIndexed { _, permission ->
                if (permission.groupName == permissionGroupName &&
                    snapshot.isPackageVisibleToUid(permission.packageName, callingUid)) {
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

    override fun checkPermission(packageName: String, permissionName: String, userId: Int): Int {
        TODO("Not yet implemented")
    }

    override fun checkUidPermission(uid: Int, permissionName: String): Int {
        TODO("Not yet implemented")
    }

    override fun getGrantedPermissions(packageName: String, userId: Int): Set<String> {
        TODO("Not yet implemented")
    }

    override fun getGidsForUid(uid: Int): IntArray {
        val appId = UserHandle.getAppId(uid)
        val userId = UserHandle.getUserId(uid)
        val permissionFlags = service.getState {
            with(policy) { getUidPermissionFlags(appId, userId) }
        } ?: return EmptyArray.INT
        val gids = GrowingIntArray.wrap(systemConfig.globalGids)
        permissionFlags.forEachIndexed { _, permissionName, flags ->
            if (!PermissionFlags.isPermissionGranted(flags)) {
                return@forEachIndexed
            }
            val permission = service.getState {
                with(policy) { getPermissions()[permissionName] }
            } ?: return@forEachIndexed
            val permissionGids = permission.getGidsForUser(userId)
            if (permissionGids.isEmpty()) {
                return@forEachIndexed
            }
            gids.addAll(permissionGids)
        }
        return gids.toArray()
    }

    override fun grantRuntimePermission(packageName: String, permissionName: String, userId: Int) {
        TODO("Not yet implemented")
    }

    override fun revokeRuntimePermission(
        packageName: String,
        permissionName: String,
        userId: Int,
        reason: String?
    ) {
        TODO("Not yet implemented")
    }

    override fun revokePostNotificationPermissionWithoutKillForTest(
        packageName: String,
        userId: Int
    ) {
        TODO("Not yet implemented")
    }

    override fun getPermissionFlags(packageName: String, permissionName: String, userId: Int): Int {
        // TODO: Implement permission checks.
        val appId = 0
        val flags = service.getState {
            with(policy) { getPermissionFlags(appId, userId, permissionName) }
        }
        return PermissionFlags.toApiFlags(flags)
    }

    override fun isPermissionRevokedByPolicy(
        packageName: String,
        permissionName: String,
        userId: Int
    ): Boolean {
        if (UserHandle.getCallingUserId() != userId) {
            context.enforceCallingPermission(
                Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                "isPermissionRevokedByPolicy for user $userId"
            )
        }

        if (checkPermission(packageName, permissionName, userId) ==
            PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val callingUid = Binder.getCallingUid()
        if (packageManagerLocal.withUnfilteredSnapshot()
            .use { !it.isPackageVisibleToUid(packageName, userId, callingUid) }) {
            return false
        }

        val permissionFlags = getPermissionFlagsUnchecked(packageName,
            permissionName, callingUid, userId)
        return permissionFlags.hasBits(PackageManager.FLAG_PERMISSION_POLICY_FIXED)
    }

    private fun getPermissionFlagsUnchecked(
        packageName: String,
        permName: String,
        callingUid: Int,
        userId: Int
    ): Int {
        throw NotImplementedError()
    }

    override fun isPermissionsReviewRequired(packageName: String, userId: Int): Boolean {
        requireNotNull(packageName) { "packageName" }
        // TODO(b/173235285): Some caller may pass USER_ALL as userId.
        // Preconditions.checkArgumentNonnegative(userId, "userId");
        val packageState = packageManagerLocal.withUnfilteredSnapshot()
            .use { it.packageStates[packageName] } ?: return false
        val permissionFlags = service.getState {
            with(policy) { getUidPermissionFlags(packageState.appId, userId) }
        } ?: return false
        return permissionFlags.anyIndexed { _, _, flags -> PermissionFlags.isReviewRequired(flags)
        }
    }

    override fun shouldShowRequestPermissionRationale(
        packageName: String,
        permissionName: String,
        userId: Int
    ): Boolean {
        val callingUid = Binder.getCallingUid()
        if (UserHandle.getCallingUserId() != userId) {
            context.enforceCallingPermission(
                Manifest.permission.INTERACT_ACROSS_USERS_FULL,
                "canShowRequestPermissionRationale for user $userId"
            )
        }

        val appId = packageManagerLocal.withUnfilteredSnapshot().use { snapshot ->
            snapshot.packageStates[packageName]?.appId ?: return false
        }
        if (UserHandle.getAppId(callingUid) != appId) {
            return false
        }

        if (checkPermission(packageName, permissionName, userId) ==
            PackageManager.PERMISSION_GRANTED) {
            return false
        }

        val identity = Binder.clearCallingIdentity()
        val permissionFlags = try {
            getPermissionFlagsInternal(packageName, permissionName, callingUid, userId)
        } finally {
            Binder.restoreCallingIdentity(identity)
        }

        val fixedFlags = (PermissionFlags.SYSTEM_FIXED or PermissionFlags.POLICY_FIXED
            or PermissionFlags.USER_FIXED)

        if (permissionFlags.hasAnyBit(fixedFlags) ||
            permissionFlags.hasBits(PermissionFlags.RESTRICTION_REVOKED)) {
            return false
        }

        val token = Binder.clearCallingIdentity()
        try {
            if (permissionName == Manifest.permission.ACCESS_BACKGROUND_LOCATION &&
                platformCompat.isChangeEnabledByPackageName(
                    BACKGROUND_RATIONALE_CHANGE_ID, packageName, userId)
            ) {
                return true
            }
        } catch (e: RemoteException) {
            Log.e(LOG_TAG, "Unable to check if compatibility change is enabled.", e)
        } finally {
            Binder.restoreCallingIdentity(token)
        }

        return permissionFlags and PackageManager.FLAG_PERMISSION_USER_SET != 0
    }

    /**
     * read internal permission flags
     * @return internal permission Flags
     * @see PermissionFlags
     */
    private fun getPermissionFlagsInternal(
        packageName: String,
        permName: String,
        callingUid: Int,
        userId: Int
    ): Int {
        throw NotImplementedError()
    }

    override fun updatePermissionFlags(
        packageName: String,
        permissionName: String,
        flagMask: Int,
        flagValues: Int,
        checkAdjustPolicyFlagPermission: Boolean,
        userId: Int
    ) {
        TODO("Not yet implemented")
    }

    override fun updatePermissionFlagsForAllApps(flagMask: Int, flagValues: Int, userId: Int) {
        TODO("Not yet implemented")
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
                return
            }
        }
        val userIds = if (userId == UserHandle.USER_ALL) {
            userManagerService.userIdsIncludingPreCreated
        } else {
            intArrayOf(userId)
        }
        userIds.forEach { service.onPackageInstalled(androidPackage.packageName, it) }
        // TODO: Handle params.
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
     * Check whether a UID belongs to an instant app.
     */
    private fun PackageManagerLocal.UnfilteredSnapshot.isUidInstantApp(uid: Int): Boolean {
        if (Process.isIsolatedUid(uid)) {
            // Unfortunately we don't have the API for getting the owner UID of an isolated UID yet,
            // so for now we just keep calling the old API.
            return packageManagerInternal.getInstantAppPackageName(uid) != null
        }
        val appId = UserHandle.getAppId(uid)
        // Instant apps can't have shared UIDs, so we can just take the first package.
        val firstPackageState = packageStates.values.firstOrNull { it.appId == appId }
            ?: return false
        val userId = UserHandle.getUserId(uid)
        return firstPackageState.getUserStateOrDefault(userId).isInstantApp
    }

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
    ): Boolean {
        val user = UserHandle.of(userId)
        return filtered(uid, user).use { it.getPackageState(packageName) != null }
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

            runtimePermissionRevokedUids.forEachIndexed {
                _, uid, areOnlyNotificationsPermissionsRevoked ->
                handler.post {
                    if (areOnlyNotificationsPermissionsRevoked &&
                        isAppBackupAndRestoreRunning(uid)) {
                        return@post
                    }
                    killUid(uid, PermissionManager.KILL_APP_REASON_PERMISSIONS_REVOKED)
                }
            }
            runtimePermissionRevokedUids.clear()

            gidsChangedUids.forEachIndexed { _, uid ->
                handler.post { killUid(uid, PermissionManager.KILL_APP_REASON_GIDS_CHANGED) }
            }
            gidsChangedUids.clear()
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

        private val NOTIFICATIONS_PERMISSIONS = indexedSetOf(
            Manifest.permission.POST_NOTIFICATIONS
        )
    }
}
