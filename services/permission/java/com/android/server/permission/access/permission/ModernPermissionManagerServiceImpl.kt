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

import android.content.pm.PackageManager
import android.content.pm.PackageManagerInternal
import android.content.pm.PermissionGroupInfo
import android.content.pm.PermissionInfo
import android.content.pm.permission.SplitPermissionInfoParcelable
import android.os.Binder
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.permission.IOnPermissionsChangeListener
import com.android.server.LocalManagerRegistry
import com.android.server.LocalServices
import com.android.server.pm.PackageManagerLocal
import com.android.server.pm.permission.PermissionManagerServiceInterface
import com.android.server.permission.access.AccessCheckingService
import com.android.server.permission.access.PermissionUri
import com.android.server.permission.access.UidUri
import com.android.server.permission.access.data.Permission
import com.android.server.permission.access.util.hasBits
import com.android.server.pm.permission.LegacyPermission
import com.android.server.pm.permission.LegacyPermissionSettings
import com.android.server.pm.permission.LegacyPermissionState
import com.android.server.pm.permission.PermissionManagerServiceInternal
import com.android.server.pm.pkg.AndroidPackage
import java.io.FileDescriptor
import java.io.PrintWriter

/**
 * Modern implementation of [PermissionManagerServiceInterface].
 */
class ModernPermissionManagerServiceImpl(
    private val service: AccessCheckingService
) : PermissionManagerServiceInterface {
    private val policy =
        service.getSchemePolicy(UidUri.SCHEME, PermissionUri.SCHEME) as UidPermissionPolicy

    private val packageManagerInternal =
        LocalServices.getService(PackageManagerInternal::class.java)

    private val packageManagerLocal =
        LocalManagerRegistry.getManagerOrThrow(PackageManagerLocal::class.java)

    override fun getAllPermissionGroups(flags: Int): List<PermissionGroupInfo> {
        TODO("Not yet implemented")
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
                with(policy) { getPermissionGroup(permissionGroupName) }
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
                with(policy) { getPermission(permissionName) }
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
        targetSdkVersion: Int
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
        permissionGroupName: String,
        flags: Int
    ): List<PermissionInfo> {
        TODO("Not yet implemented")
    }

    override fun getAllPermissionsWithProtection(protection: Int): List<PermissionInfo> {
        TODO("Not yet implemented")
    }

    override fun getAllPermissionsWithProtectionFlags(protectionFlags: Int): List<PermissionInfo> {
        TODO("Not yet implemented")
    }

    override fun getPermissionGids(permissionName: String, userId: Int): IntArray {
        TODO("Not yet implemented")
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

    override fun addOnPermissionsChangeListener(listener: IOnPermissionsChangeListener) {
        TODO("Not yet implemented")
    }

    override fun removeOnPermissionsChangeListener(listener: IOnPermissionsChangeListener) {
        TODO("Not yet implemented")
    }

    override fun getPermissionFlags(packageName: String, permissionName: String, userId: Int): Int {
        TODO("Not yet implemented")
    }

    override fun isPermissionRevokedByPolicy(
        packageName: String,
        permissionName: String,
        userId: Int
    ): Boolean {
        TODO("Not yet implemented")
    }

    override fun isPermissionsReviewRequired(packageName: String, userId: Int): Boolean {
        TODO("Not yet implemented")
    }

    override fun shouldShowRequestPermissionRationale(
        packageName: String,
        permissionName: String,
        userId: Int
    ): Boolean {
        TODO("Not yet implemented")
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

    override fun addOnRuntimePermissionStateChangedListener(
        listener: PermissionManagerServiceInternal.OnRuntimePermissionStateChangedListener
    ) {
        TODO("Not yet implemented")
    }

    override fun removeOnRuntimePermissionStateChangedListener(
        listener: PermissionManagerServiceInternal.OnRuntimePermissionStateChangedListener
    ) {
        TODO("Not yet implemented")
    }

    override fun getSplitPermissions(): List<SplitPermissionInfoParcelable> {
        TODO("Not yet implemented")
    }

    override fun getAppOpPermissionPackages(permissionName: String): Array<String> {
        TODO("Not yet implemented")
    }

    override fun getAllAppOpPermissionPackages(): Map<String, Set<String>> {
        TODO("Not yet implemented")
    }

    override fun getGidsForUid(uid: Int): IntArray {
        TODO("Not yet implemented")
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
        TODO("Not yet implemented")
    }

    override fun onUserRemoved(userId: Int) {
        TODO("Not yet implemented")
    }

    override fun onStorageVolumeMounted(volumeUuid: String, fingerprintChanged: Boolean) {
        TODO("Not yet implemented")
    }

    override fun onPackageAdded(
        androidPackage: AndroidPackage,
        isInstantApp: Boolean,
        oldPackage: AndroidPackage?
    ) {
        TODO("Not yet implemented")
    }

    override fun onPackageInstalled(
        androidPackage: AndroidPackage,
        previousAppId: Int,
        params: PermissionManagerServiceInternal.PackageInstalledParams,
        userId: Int
    ) {
        TODO("Not yet implemented")
    }

    override fun onPackageUninstalled(
        packageName: String,
        appId: Int,
        androidPackage: AndroidPackage?,
        sharedUserPkgs: MutableList<AndroidPackage>,
        userId: Int
    ) {
        TODO("Not yet implemented")
    }

    override fun onPackageRemoved(androidPackage: AndroidPackage) {
        TODO("Not yet implemented")
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
}
