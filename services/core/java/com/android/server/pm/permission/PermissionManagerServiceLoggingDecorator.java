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

package com.android.server.pm.permission;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.permission.IOnPermissionsChangeListener;
import android.util.Log;

import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Logging decorator for {@link PermissionManagerServiceInterface}.
 */
public class PermissionManagerServiceLoggingDecorator implements PermissionManagerServiceInterface {
    private static final String LOG_TAG =
            PermissionManagerServiceLoggingDecorator.class.getSimpleName();

    @NonNull
    private final PermissionManagerServiceInterface mService;

    public PermissionManagerServiceLoggingDecorator(
            @NonNull PermissionManagerServiceInterface service
    ) {
        mService = service;
    }

    @Nullable
    @Override
    public byte[] backupRuntimePermissions(int userId) {
        Log.i(LOG_TAG, "backupRuntimePermissions(userId = " + userId + ")");
        return mService.backupRuntimePermissions(userId);
    }

    @Override
    @SuppressWarnings("ArrayToString")
    public void restoreRuntimePermissions(@NonNull byte[] backup, int userId) {
        Log.i(LOG_TAG, "restoreRuntimePermissions(backup = " + backup + ", userId = " + userId
                + ")");
        mService.restoreRuntimePermissions(backup, userId);
    }

    @Override
    public void restoreDelayedRuntimePermissions(@NonNull String packageName, int userId) {
        Log.i(LOG_TAG, "restoreDelayedRuntimePermissions(packageName = " + packageName
                + ", userId = " + userId + ")");
        mService.restoreDelayedRuntimePermissions(packageName, userId);
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        Log.i(LOG_TAG, "dump(fd = " + fd + ", pw = " + pw + ", args = " + Arrays.toString(args)
                + ")");
        mService.dump(fd, pw, args);
    }

    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        Log.i(LOG_TAG, "getAllPermissionGroups(flags = " + flags + ")");
        return mService.getAllPermissionGroups(flags);
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags) {
        Log.i(LOG_TAG, "getPermissionGroupInfo(groupName = " + groupName + ", flags = " + flags
                + ")");
        return mService.getPermissionGroupInfo(groupName, flags);
    }

    @Override
    public PermissionInfo getPermissionInfo(@NonNull String permName, int flags,
            @NonNull String opPackageName) {
        Log.i(LOG_TAG, "getPermissionInfo(permName = " + permName + ", flags = " + flags
                + ", opPackageName = " + opPackageName + ")");
        return mService.getPermissionInfo(permName, flags, opPackageName);
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String groupName, int flags) {
        Log.i(LOG_TAG, "queryPermissionsByGroup(groupName = " + groupName + ", flags = " + flags
                + ")");
        return mService.queryPermissionsByGroup(groupName, flags);
    }

    @Override
    public boolean addPermission(PermissionInfo info, boolean async) {
        Log.i(LOG_TAG, "addPermission(info = " + info + ", async = " + async + ")");
        return mService.addPermission(info, async);
    }

    @Override
    public void removePermission(String permName) {
        Log.i(LOG_TAG, "removePermission(permName = " + permName + ")");
        mService.removePermission(permName);
    }

    @Override
    public int getPermissionFlags(String packageName, String permName, int deviceId, int userId) {
        Log.i(LOG_TAG, "getPermissionFlags(packageName = " + packageName + ", permName = "
                + permName + ", deviceId = " + deviceId +  ", userId = " + userId + ")");
        return mService.getPermissionFlags(packageName, permName, deviceId, userId);
    }

    @Override
    public void updatePermissionFlags(String packageName, String permName, int flagMask,
            int flagValues, boolean checkAdjustPolicyFlagPermission, int deviceId, int userId) {
        Log.i(LOG_TAG, "updatePermissionFlags(packageName = " + packageName + ", permName = "
                + permName + ", flagMask = " + flagMask + ", flagValues = " + flagValues
                + ", checkAdjustPolicyFlagPermission = " + checkAdjustPolicyFlagPermission
                + ", deviceId = " + deviceId + ", userId = " + userId + ")");
        mService.updatePermissionFlags(packageName, permName, flagMask, flagValues,
                checkAdjustPolicyFlagPermission, deviceId, userId);
    }

    @Override
    public void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId) {
        Log.i(LOG_TAG, "updatePermissionFlagsForAllApps(flagMask = " + flagMask + ", flagValues = "
                + flagValues + ", userId = " + userId + ")");
        mService.updatePermissionFlagsForAllApps(flagMask, flagValues, userId);
    }

    @Override
    public void addOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        Log.i(LOG_TAG, "addOnPermissionsChangeListener(listener = " + listener + ")");
        mService.addOnPermissionsChangeListener(listener);
    }

    @Override
    public void removeOnPermissionsChangeListener(
            IOnPermissionsChangeListener listener) {
        Log.i(LOG_TAG, "removeOnPermissionsChangeListener(listener = " + listener + ")");
        mService.removeOnPermissionsChangeListener(listener);
    }

    @Override
    public boolean addAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, int flags, int userId) {
        Log.i(LOG_TAG, "addAllowlistedRestrictedPermission(packageName = " + packageName
                + ", permName = " + permName + ", flags = " + flags + ", userId = " + userId + ")");
        return mService.addAllowlistedRestrictedPermission(packageName, permName, flags, userId);
    }

    @Override
    public List<String> getAllowlistedRestrictedPermissions(@NonNull String packageName, int flags,
            int userId) {
        Log.i(LOG_TAG, "getAllowlistedRestrictedPermissions(packageName = " + packageName
                + ", flags = " + flags + ", userId = " + userId + ")");
        return mService.getAllowlistedRestrictedPermissions(packageName, flags, userId);
    }

    @Override
    public boolean removeAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, int flags, int userId) {
        Log.i(LOG_TAG, "removeAllowlistedRestrictedPermission(packageName = " + packageName
                + ", permName = " + permName + ", flags = " + flags + ", userId = " + userId + ")");
        return mService.removeAllowlistedRestrictedPermission(packageName, permName, flags, userId);
    }

    @Override
    public void grantRuntimePermission(String packageName, String permName, int deviceId,
            int userId) {
        Log.i(LOG_TAG, "grantRuntimePermission(packageName = " + packageName + ", permName = "
                + permName + ", deviceId = " + deviceId + ", userId = " + userId + ")");
        mService.grantRuntimePermission(packageName, permName, deviceId, userId);
    }

    @Override
    public void revokeRuntimePermission(String packageName, String permName, int deviceId,
            int userId, String reason) {
        Log.i(LOG_TAG, "revokeRuntimePermission(packageName = " + packageName + ", permName = "
                + permName + ", deviceId = " + deviceId + ", userId = " + userId
                + ", reason = " + reason + ")");
        mService.revokeRuntimePermission(packageName, permName, deviceId, userId, reason);
    }

    @Override
    public void revokePostNotificationPermissionWithoutKillForTest(String packageName, int userId) {
        Log.i(LOG_TAG, "revokePostNotificationPermissionWithoutKillForTest(packageName = "
                + packageName + ", userId = " + userId + ")");
        mService.revokePostNotificationPermissionWithoutKillForTest(packageName, userId);
    }

    @Override
    public boolean shouldShowRequestPermissionRationale(String packageName, String permName,
            int deviceId, int userId) {
        Log.i(LOG_TAG, "shouldShowRequestPermissionRationale(packageName = " + packageName
                + ", permName = " + permName + ", deviceId = " + deviceId
                +  ", userId = " + userId + ")");
        return mService.shouldShowRequestPermissionRationale(packageName, permName, deviceId,
                userId);
    }

    @Override
    public boolean isPermissionRevokedByPolicy(String packageName, String permName, int deviceId,
            int userId) {
        Log.i(LOG_TAG, "isPermissionRevokedByPolicy(packageName = " + packageName + ", permName = "
                + permName + ", deviceId = " + deviceId + ", userId = " + userId + ")");
        return mService.isPermissionRevokedByPolicy(packageName, permName, deviceId, userId);
    }

    @Override
    public List<SplitPermissionInfoParcelable> getSplitPermissions() {
        Log.i(LOG_TAG, "getSplitPermissions()");
        return mService.getSplitPermissions();
    }

    @Override
    public int checkPermission(String pkgName, String permName, int deviceId, int userId) {
        Log.i(LOG_TAG, "checkPermission(pkgName = " + pkgName + ", permName = " + permName
                + ", deviceId = " + deviceId + ", userId = " + userId + ")");
        return mService.checkPermission(pkgName, permName, deviceId, userId);
    }

    @Override
    public int checkUidPermission(int uid, String permName, int deviceId) {
        Log.i(LOG_TAG, "checkUidPermission(uid = " + uid + ", permName = " + permName
                + ", deviceId = " + deviceId + ")");
        return mService.checkUidPermission(uid, permName, deviceId);
    }

    @Override
    public Map<String, Set<String>> getAllAppOpPermissionPackages() {
        Log.i(LOG_TAG, "getAllAppOpPermissionPackages()");
        return mService.getAllAppOpPermissionPackages();
    }

    @Override
    public boolean isPermissionsReviewRequired(@NonNull String packageName, int userId) {
        Log.i(LOG_TAG, "isPermissionsReviewRequired(packageName = " + packageName + ", userId = "
                + userId + ")");
        return mService.isPermissionsReviewRequired(packageName, userId);
    }

    @Override
    public void resetRuntimePermissions(@NonNull AndroidPackage pkg, int userId) {
        Log.i(LOG_TAG, "resetRuntimePermissions(pkg = " + pkg + ", userId = " + userId + ")");
        mService.resetRuntimePermissions(pkg, userId);
    }

    @Override
    public void resetRuntimePermissionsForUser(int userId) {
        Log.i(LOG_TAG, "resetRuntimePermissionsForUser(userId = " + userId + ")");
        mService.resetRuntimePermissionsForUser(userId);
    }

    @Override
    public void readLegacyPermissionStateTEMP() {
        Log.i(LOG_TAG, "readLegacyPermissionStateTEMP()");
        mService.readLegacyPermissionStateTEMP();
    }

    @Override
    public void writeLegacyPermissionStateTEMP() {
        Log.i(LOG_TAG, "writeLegacyPermissionStateTEMP()");
        mService.writeLegacyPermissionStateTEMP();
    }

    @NonNull
    @Override
    public Set<String> getInstalledPermissions(@NonNull String packageName) {
        Log.i(LOG_TAG, "getInstalledPermissions(packageName = " + packageName + ")");
        return mService.getInstalledPermissions(packageName);
    }

    @NonNull
    @Override
    public Set<String> getGrantedPermissions(@NonNull String packageName, int userId) {
        Log.i(LOG_TAG, "getGrantedPermissions(packageName = " + packageName + ", userId = "
                + userId + ")");
        return mService.getGrantedPermissions(packageName, userId);
    }

    @NonNull
    @Override
    public int[] getPermissionGids(@NonNull String permissionName, int userId) {
        Log.i(LOG_TAG, "getPermissionGids(permissionName = " + permissionName + ", userId = "
                + userId + ")");
        return mService.getPermissionGids(permissionName, userId);
    }

    @NonNull
    @Override
    public String[] getAppOpPermissionPackages(@NonNull String permissionName) {
        Log.i(LOG_TAG, "getAppOpPermissionPackages(permissionName = " + permissionName + ")");
        return mService.getAppOpPermissionPackages(permissionName);
    }

    @Nullable
    @Override
    public Permission getPermissionTEMP(@NonNull String permName) {
        Log.i(LOG_TAG, "getPermissionTEMP(permName = " + permName + ")");
        return mService.getPermissionTEMP(permName);
    }

    @NonNull
    @Override
    public List<PermissionInfo> getAllPermissionsWithProtection(int protection) {
        Log.i(LOG_TAG, "getAllPermissionsWithProtection(protection = " + protection + ")");
        return mService.getAllPermissionsWithProtection(protection);
    }

    @NonNull
    @Override
    public List<PermissionInfo> getAllPermissionsWithProtectionFlags(int protectionFlags) {
        Log.i(LOG_TAG, "getAllPermissionsWithProtectionFlags(protectionFlags = " + protectionFlags
                + ")");
        return mService.getAllPermissionsWithProtectionFlags(protectionFlags);
    }

    @NonNull
    @Override
    public List<LegacyPermission> getLegacyPermissions() {
        Log.i(LOG_TAG, "getLegacyPermissions()");
        return mService.getLegacyPermissions();
    }

    @NonNull
    @Override
    public LegacyPermissionState getLegacyPermissionState(int appId) {
        Log.i(LOG_TAG, "getLegacyPermissionState(appId = " + appId + ")");
        return mService.getLegacyPermissionState(appId);
    }

    @Override
    public void readLegacyPermissionsTEMP(
            @NonNull LegacyPermissionSettings legacyPermissionSettings) {
        Log.i(LOG_TAG, "readLegacyPermissionsTEMP(legacyPermissionSettings = "
                + legacyPermissionSettings + ")");
        mService.readLegacyPermissionsTEMP(legacyPermissionSettings);
    }

    @Override
    public void writeLegacyPermissionsTEMP(
            @NonNull LegacyPermissionSettings legacyPermissionSettings) {
        Log.i(LOG_TAG, "writeLegacyPermissionsTEMP(legacyPermissionSettings = "
                + legacyPermissionSettings + ")");
        mService.writeLegacyPermissionsTEMP(legacyPermissionSettings);
    }

    @Nullable
    @Override
    public String getDefaultPermissionGrantFingerprint(int userId) {
        Log.i(LOG_TAG, "getDefaultPermissionGrantFingerprint(userId = " + userId + ")");
        return mService.getDefaultPermissionGrantFingerprint(userId);
    }

    @Override
    public void setDefaultPermissionGrantFingerprint(@NonNull String fingerprint, int userId) {
        Log.i(LOG_TAG, "setDefaultPermissionGrantFingerprint(fingerprint = " + fingerprint
                + ", userId = " + userId + ")");
        mService.setDefaultPermissionGrantFingerprint(fingerprint, userId);
    }

    @Override
    public void onSystemReady() {
        Log.i(LOG_TAG, "onSystemReady()");
        mService.onSystemReady();
    }

    @Override
    public void onStorageVolumeMounted(@NonNull String volumeUuid, boolean fingerprintChanged) {
        Log.i(LOG_TAG, "onStorageVolumeMounted(volumeUuid = " + volumeUuid
                + ", fingerprintChanged = " + fingerprintChanged + ")");
        mService.onStorageVolumeMounted(volumeUuid, fingerprintChanged);
    }

    @NonNull
    @Override
    public int[] getGidsForUid(int uid) {
        Log.i(LOG_TAG, "getGidsForUid(uid = " + uid + ")");
        return mService.getGidsForUid(uid);
    }

    @Override
    public void onUserCreated(int userId) {
        Log.i(LOG_TAG, "onUserCreated(userId = " + userId + ")");
        mService.onUserCreated(userId);
    }

    @Override
    public void onUserRemoved(int userId) {
        Log.i(LOG_TAG, "onUserRemoved(userId = " + userId + ")");
        mService.onUserRemoved(userId);
    }

    @Override
    public void onPackageAdded(@NonNull PackageState packageState, boolean isInstantApp,
            @Nullable AndroidPackage oldPkg) {
        Log.i(LOG_TAG, "onPackageAdded(packageState = " + packageState + ", isInstantApp = "
                + isInstantApp + ", oldPkg = " + oldPkg + ")");
        mService.onPackageAdded(packageState, isInstantApp, oldPkg);
    }

    @Override
    public void onPackageInstalled(@NonNull AndroidPackage pkg, int previousAppId,
            @NonNull PermissionManagerServiceInternal.PackageInstalledParams params, int userId) {
        Log.i(LOG_TAG, "onPackageInstalled(pkg = " + pkg + ", previousAppId = " + previousAppId
                + ", params = " + params + ", userId = " + userId + ")");
        mService.onPackageInstalled(pkg, previousAppId, params, userId);
    }

    @Override
    public void onPackageRemoved(@NonNull AndroidPackage pkg) {
        Log.i(LOG_TAG, "onPackageRemoved(pkg = " + pkg + ")");
        mService.onPackageRemoved(pkg);
    }

    @Override
    public void onPackageUninstalled(@NonNull String packageName, int appId,
            @NonNull PackageState packageState, @Nullable AndroidPackage pkg,
            @NonNull List<AndroidPackage> sharedUserPkgs, int userId) {
        Log.i(LOG_TAG, "onPackageUninstalled(packageName = " + packageName + ", appId = " + appId
                + ", packageState = " + packageState + ", pkg = " + pkg + ", sharedUserPkgs = "
                + sharedUserPkgs + ", userId = " + userId + ")");
        mService.onPackageUninstalled(packageName, appId, packageState, pkg, sharedUserPkgs,
                userId);
    }
}
