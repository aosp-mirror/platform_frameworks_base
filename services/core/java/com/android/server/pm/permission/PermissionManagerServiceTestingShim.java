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
import android.annotation.UserIdInt;
import android.content.pm.PermissionGroupInfo;
import android.content.pm.PermissionInfo;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.os.Build;
import android.permission.IOnPermissionsChangeListener;

import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A testing shim, which supports running two variants of a PermissionManagerServiceInterface at
 * once, and checking the results of both.
 */
public class PermissionManagerServiceTestingShim implements PermissionManagerServiceInterface {

    private PermissionManagerServiceInterface mOldImplementation;
    private PermissionManagerServiceInterface mNewImplementation;

    public PermissionManagerServiceTestingShim(PermissionManagerServiceInterface oldImpl,
            PermissionManagerServiceInterface newImpl) {
        mOldImplementation = oldImpl;
        mNewImplementation = newImpl;
    }

    private void signalImplDifference(String message) {
        //TODO b/252886104 implement
    }


    @Nullable
    @Override
    public byte[] backupRuntimePermissions(int userId) {
        byte[] oldVal = mOldImplementation.backupRuntimePermissions(userId);
        byte[] newVal = mNewImplementation.backupRuntimePermissions(userId);
        if (!Arrays.equals(oldVal, newVal)) {
            signalImplDifference("backupRuntimePermissions");
        }

        return newVal;
    }

    @Override
    public void restoreRuntimePermissions(@NonNull byte[] backup, int userId) {
        mOldImplementation.backupRuntimePermissions(userId);
        mNewImplementation.backupRuntimePermissions(userId);
    }

    @Override
    public void restoreDelayedRuntimePermissions(@NonNull String packageName, int userId) {
        mOldImplementation.restoreDelayedRuntimePermissions(packageName, userId);
        mNewImplementation.restoreDelayedRuntimePermissions(packageName, userId);

    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        mOldImplementation.dump(fd, pw, args);
        mNewImplementation.dump(fd, pw, args);
    }

    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        List<PermissionGroupInfo> oldVal = mOldImplementation.getAllPermissionGroups(flags);
        List<PermissionGroupInfo> newVal = mNewImplementation.getAllPermissionGroups(flags);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getAllPermissionGroups");
        }
        return newVal;
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags) {
        PermissionGroupInfo oldVal = mOldImplementation.getPermissionGroupInfo(groupName, flags);
        PermissionGroupInfo newVal = mNewImplementation.getPermissionGroupInfo(groupName, flags);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getPermissionGroupInfo");
        }
        return newVal;
    }

    @Override
    public PermissionInfo getPermissionInfo(@NonNull String permName, int flags,
            @NonNull String opPackageName) {
        PermissionInfo oldVal = mOldImplementation.getPermissionInfo(permName, flags,
                opPackageName);
        PermissionInfo newVal = mNewImplementation.getPermissionInfo(permName, flags,
                opPackageName);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getPermissionInfo");
        }
        return newVal;
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String groupName, int flags) {
        List<PermissionInfo> oldVal = mOldImplementation.queryPermissionsByGroup(groupName,
                flags);
        List<PermissionInfo> newVal = mNewImplementation.queryPermissionsByGroup(groupName, flags);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("queryPermissionsByGroup");
        }
        return newVal;
    }

    @Override
    public boolean addPermission(PermissionInfo info, boolean async) {
        boolean oldVal = mOldImplementation.addPermission(info, async);
        boolean newVal = mNewImplementation.addPermission(info, async);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("addPermission");
        }
        return newVal;
    }

    @Override
    public void removePermission(String permName) {
        mOldImplementation.removePermission(permName);
        mNewImplementation.removePermission(permName);
    }

    @Override
    public int getPermissionFlags(String packageName, String permName, int deviceId,
            @UserIdInt int userId) {
        int oldVal = mOldImplementation.getPermissionFlags(packageName, permName, deviceId, userId);
        int newVal = mNewImplementation.getPermissionFlags(packageName, permName, deviceId, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getPermissionFlags");
        }
        return newVal;
    }

    @Override
    public void updatePermissionFlags(String packageName, String permName, int flagMask,
            int flagValues, boolean checkAdjustPolicyFlagPermission, int deviceId,
            @UserIdInt int userId) {
        mOldImplementation.updatePermissionFlags(packageName, permName, flagMask, flagValues,
                checkAdjustPolicyFlagPermission, deviceId, userId);
        mNewImplementation.updatePermissionFlags(packageName, permName, flagMask, flagValues,
                checkAdjustPolicyFlagPermission, deviceId, userId);
    }

    @Override
    public void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId) {
        mOldImplementation.updatePermissionFlagsForAllApps(flagMask, flagValues, userId);
        mNewImplementation.updatePermissionFlagsForAllApps(flagMask, flagValues, userId);
    }

    @Override
    public void addOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        mOldImplementation.addOnPermissionsChangeListener(listener);
        mNewImplementation.addOnPermissionsChangeListener(listener);
    }

    @Override
    public void removeOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        mOldImplementation.removeOnPermissionsChangeListener(listener);
        mNewImplementation.removeOnPermissionsChangeListener(listener);
    }

    @Override
    public boolean addAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, int flags, int userId) {
        boolean oldVal = mOldImplementation.addAllowlistedRestrictedPermission(packageName,
                permName,
                flags, userId);
        boolean newVal = mNewImplementation.addAllowlistedRestrictedPermission(packageName,
                permName, flags, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("addAllowlistedRestrictedPermission");
        }
        return newVal;
    }

    @Override
    public List<String> getAllowlistedRestrictedPermissions(@NonNull String packageName, int flags,
            int userId) {
        List<String> oldVal = mOldImplementation.getAllowlistedRestrictedPermissions(packageName,
                flags, userId);
        List<String> newVal = mNewImplementation.getAllowlistedRestrictedPermissions(packageName,
                flags, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getAllowlistedRestrictedPermissions");
        }
        return newVal;
    }

    @Override
    public boolean removeAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, int flags, int userId) {
        boolean oldVal = mOldImplementation.removeAllowlistedRestrictedPermission(packageName,
                permName, flags, userId);
        boolean newVal = mNewImplementation.removeAllowlistedRestrictedPermission(packageName,
                permName, flags, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("removeAllowlistedRestrictedPermission");
        }
        return newVal;
    }

    @Override
    public void grantRuntimePermission(String packageName, String permName, int deviceId,
            @UserIdInt int userId) {
        mOldImplementation.grantRuntimePermission(packageName, permName, deviceId, userId);
        mNewImplementation.grantRuntimePermission(packageName, permName, deviceId, userId);
    }

    @Override
    public void revokeRuntimePermission(String packageName, String permName, int deviceId,
            @UserIdInt int userId, String reason) {
        mOldImplementation.revokeRuntimePermission(packageName, permName, deviceId, userId, reason);
        mNewImplementation.revokeRuntimePermission(packageName, permName, deviceId, userId, reason);
    }

    @Override
    public void revokePostNotificationPermissionWithoutKillForTest(String packageName, int userId) {
        mOldImplementation.revokePostNotificationPermissionWithoutKillForTest(packageName,
                userId);
        mNewImplementation.revokePostNotificationPermissionWithoutKillForTest(packageName, userId);
    }

    @Override
    public boolean shouldShowRequestPermissionRationale(String packageName, String permName,
            int deviceId, @UserIdInt int userId) {
        boolean oldVal = mOldImplementation.shouldShowRequestPermissionRationale(packageName,
                permName, deviceId,  userId);
        boolean newVal = mNewImplementation.shouldShowRequestPermissionRationale(packageName,
                permName, deviceId, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("shouldShowRequestPermissionRationale");
        }
        return newVal;
    }

    @Override
    public boolean isPermissionRevokedByPolicy(String packageName, String permName, int deviceId,
            @UserIdInt int userId) {
        boolean oldVal = mOldImplementation.isPermissionRevokedByPolicy(packageName, permName,
                deviceId, userId);
        boolean newVal = mNewImplementation.isPermissionRevokedByPolicy(packageName, permName,
                deviceId, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("isPermissionRevokedByPolicy");
        }
        return newVal;
    }

    @Override
    public List<SplitPermissionInfoParcelable> getSplitPermissions() {
        List<SplitPermissionInfoParcelable> oldVal = mOldImplementation.getSplitPermissions();
        List<SplitPermissionInfoParcelable> newVal = mNewImplementation.getSplitPermissions();

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getSplitPermissions");
        }
        return newVal;
    }

    @Override
    public int checkPermission(String pkgName, String permName, int deviceId, int userId) {
        int oldVal = mOldImplementation.checkPermission(pkgName, permName, deviceId, userId);
        int newVal = mNewImplementation.checkPermission(pkgName, permName, deviceId, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("checkPermission");
        }
        return newVal;
    }

    @Override
    public int checkUidPermission(int uid, String permName, int deviceId) {
        int oldVal = mOldImplementation.checkUidPermission(uid, permName, deviceId);
        int newVal = mNewImplementation.checkUidPermission(uid, permName, deviceId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("checkUidPermission");
        }
        return newVal;
    }

    @Override
    public Map<String, Set<String>> getAllAppOpPermissionPackages() {
        Map<String, Set<String>> oldVal = mOldImplementation.getAllAppOpPermissionPackages();
        Map<String, Set<String>> newVal = mNewImplementation.getAllAppOpPermissionPackages();

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getAllAppOpPermissionPackages");
        }
        return newVal;
    }

    @Override
    public boolean isPermissionsReviewRequired(@NonNull String packageName, int userId) {
        boolean oldVal = mOldImplementation.isPermissionsReviewRequired(packageName, userId);
        boolean newVal = mNewImplementation.isPermissionsReviewRequired(packageName, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("isPermissionsReviewRequired");
        }
        return newVal;
    }

    @Override
    public void resetRuntimePermissions(@NonNull AndroidPackage pkg, @UserIdInt int userId) {
        mOldImplementation.resetRuntimePermissions(pkg, userId);
        mNewImplementation.resetRuntimePermissions(pkg, userId);
    }

    @Override
    public void resetRuntimePermissionsForUser(int userId) {
        mOldImplementation.resetRuntimePermissionsForUser(userId);
        mNewImplementation.resetRuntimePermissionsForUser(userId);
    }

    @Override
    public void readLegacyPermissionStateTEMP() {
        mOldImplementation.readLegacyPermissionStateTEMP();
        mNewImplementation.readLegacyPermissionStateTEMP();
    }

    @Override
    public void writeLegacyPermissionStateTEMP() {
        mOldImplementation.writeLegacyPermissionStateTEMP();
        mNewImplementation.writeLegacyPermissionStateTEMP();
    }

    @Override
    public Set<String> getInstalledPermissions(String packageName) {
        Set<String> oldVal = mOldImplementation.getInstalledPermissions(packageName);
        Set<String> newVal = mNewImplementation.getInstalledPermissions(packageName);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getInstalledPermissions");
        }
        return newVal;
    }

    @NonNull
    @Override
    public Set<String> getGrantedPermissions(@NonNull String packageName, @UserIdInt int userId) {
        Set<String> oldVal = mOldImplementation.getGrantedPermissions(packageName, userId);
        Set<String> newVal = mNewImplementation.getGrantedPermissions(packageName, userId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getGrantedPermissions");
        }
        return newVal;
    }

    @NonNull
    @Override
    public int[] getPermissionGids(@NonNull String permissionName, int userId) {
        int[] oldVal = mOldImplementation.getPermissionGids(permissionName, userId);
        int[] newVal = mNewImplementation.getPermissionGids(permissionName, userId);

        if (!Arrays.equals(oldVal, newVal)) {
            signalImplDifference("getPermissionGids");
        }
        return newVal;
    }

    @NonNull
    @Override
    public String[] getAppOpPermissionPackages(@NonNull String permissionName) {
        String[] oldVal = mOldImplementation.getAppOpPermissionPackages(permissionName);
        String[] newVal = mNewImplementation.getAppOpPermissionPackages(permissionName);

        if (!Arrays.equals(oldVal, newVal)) {
            signalImplDifference("getAppOpPermissionPackages");
        }
        return newVal;
    }

    @Nullable
    @Override
    public Permission getPermissionTEMP(@NonNull String permName) {
        Permission oldVal = mOldImplementation.getPermissionTEMP(permName);
        Permission newVal = mNewImplementation.getPermissionTEMP(permName);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getPermissionTEMP");
        }
        return newVal;
    }

    @NonNull
    @Override
    public List<PermissionInfo> getAllPermissionsWithProtection(int protection) {
        List<PermissionInfo> oldVal = mOldImplementation.getAllPermissionsWithProtection(
                protection);
        List<PermissionInfo> newVal = mNewImplementation.getAllPermissionsWithProtection(
                protection);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getAllPermissionsWithProtection");
        }
        return newVal;
    }

    @NonNull
    @Override
    public List<PermissionInfo> getAllPermissionsWithProtectionFlags(int protectionFlags) {
        List<PermissionInfo> oldVal = mOldImplementation
                .getAllPermissionsWithProtectionFlags(protectionFlags);
        List<PermissionInfo> newVal = mNewImplementation.getAllPermissionsWithProtectionFlags(
                protectionFlags);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getAllPermissionsWithProtectionFlags");
        }
        return newVal;
    }

    @NonNull
    @Override
    public List<LegacyPermission> getLegacyPermissions() {
        List<LegacyPermission> oldVal = mOldImplementation.getLegacyPermissions();
        List<LegacyPermission> newVal = mNewImplementation.getLegacyPermissions();

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getLegacyPermissions");
        }
        return newVal;
    }

    @NonNull
    @Override
    public LegacyPermissionState getLegacyPermissionState(int appId) {
        LegacyPermissionState oldVal = mOldImplementation.getLegacyPermissionState(appId);
        LegacyPermissionState newVal = mNewImplementation.getLegacyPermissionState(appId);

        if (!Objects.equals(oldVal, newVal)) {
            signalImplDifference("getLegacyPermissionState");
        }
        return newVal;
    }

    @Override
    public void readLegacyPermissionsTEMP(
            @NonNull LegacyPermissionSettings legacyPermissionSettings) {
        mOldImplementation.readLegacyPermissionsTEMP(legacyPermissionSettings);
        mNewImplementation.readLegacyPermissionsTEMP(legacyPermissionSettings);
    }

    @Override
    public void writeLegacyPermissionsTEMP(
            @NonNull LegacyPermissionSettings legacyPermissionSettings) {
        mOldImplementation.writeLegacyPermissionsTEMP(legacyPermissionSettings);
        mNewImplementation.writeLegacyPermissionsTEMP(legacyPermissionSettings);
    }

    @Nullable
    @Override
    public String getDefaultPermissionGrantFingerprint(@UserIdInt int userId) {
        String oldVal = mOldImplementation.getDefaultPermissionGrantFingerprint(userId);
        String newVal = mNewImplementation.getDefaultPermissionGrantFingerprint(userId);

        if (Objects.equals(oldVal, Build.FINGERPRINT)
                != Objects.equals(newVal, Build.FINGERPRINT)) {
            signalImplDifference("getDefaultPermissionGrantFingerprint");
        }
        return newVal;
    }

    @Override
    public void setDefaultPermissionGrantFingerprint(@NonNull String fingerprint,
            @UserIdInt int userId) {
        mOldImplementation.setDefaultPermissionGrantFingerprint(fingerprint, userId);
        mNewImplementation.setDefaultPermissionGrantFingerprint(fingerprint, userId);
    }

    @Override
    public void onSystemReady() {
        mOldImplementation.onSystemReady();
        mNewImplementation.onSystemReady();
    }

    @Override
    public void onStorageVolumeMounted(@NonNull String volumeUuid, boolean fingerprintChanged) {
        mOldImplementation.onStorageVolumeMounted(volumeUuid, fingerprintChanged);
        mNewImplementation.onStorageVolumeMounted(volumeUuid, fingerprintChanged);
    }

    @NonNull
    @Override
    public int[] getGidsForUid(int uid) {
        int[] oldVal = mOldImplementation.getGidsForUid(uid);
        int[] newVal = mNewImplementation.getGidsForUid(uid);

        if (!Arrays.equals(oldVal, newVal)) {
            signalImplDifference("getGidsForUid");
        }
        return newVal;
    }

    @Override
    public void onUserCreated(int userId) {
        mOldImplementation.onUserCreated(userId);
        mNewImplementation.onUserCreated(userId);
    }

    @Override
    public void onUserRemoved(int userId) {
        mOldImplementation.onUserRemoved(userId);
        mNewImplementation.onUserRemoved(userId);
    }

    @Override
    public void onPackageAdded(@NonNull PackageState pkg, boolean isInstantApp,
            @Nullable AndroidPackage oldPkg) {
        mOldImplementation.onPackageAdded(pkg, isInstantApp, oldPkg);
        mNewImplementation.onPackageAdded(pkg, isInstantApp, oldPkg);
    }

    @Override
    public void onPackageInstalled(@NonNull AndroidPackage pkg, int previousAppId,
            @NonNull PermissionManagerServiceInternal.PackageInstalledParams params, int userId) {
        mOldImplementation.onPackageInstalled(pkg, previousAppId, params, userId);
        mNewImplementation.onPackageInstalled(pkg, previousAppId, params, userId);
    }

    @Override
    public void onPackageRemoved(@NonNull AndroidPackage pkg) {
        mOldImplementation.onPackageRemoved(pkg);
        mNewImplementation.onPackageRemoved(pkg);
    }

    @Override
    public void onPackageUninstalled(@NonNull String packageName, int appId,
            @NonNull PackageState packageState, @Nullable AndroidPackage pkg,
            @NonNull List<AndroidPackage> sharedUserPkgs, int userId) {
        mOldImplementation.onPackageUninstalled(packageName, appId, packageState, pkg,
                sharedUserPkgs, userId);
        mNewImplementation.onPackageUninstalled(packageName, appId, packageState, pkg,
                sharedUserPkgs, userId);
    }
}
