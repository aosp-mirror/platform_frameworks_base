/*
 * Copyright (C) 2023 The Android Open Source Project
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
import android.os.Trace;
import android.permission.IOnPermissionsChangeListener;

import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Surrounds all PermissionManagerServiceInterface method calls with Trace.traceBegin and
 * Trace.traceEnd. These traces are used for identifying permission issues and testing.
 */
public class PermissionManagerServiceTracingDecorator implements PermissionManagerServiceInterface {
    private static final long TRACE_TAG = Trace.TRACE_TAG_PACKAGE_MANAGER;

    @NonNull
    private final PermissionManagerServiceInterface mService;

    public PermissionManagerServiceTracingDecorator(
            @NonNull PermissionManagerServiceInterface service
    ) {
        mService = service;
    }

    @Nullable
    @Override
    public byte[] backupRuntimePermissions(int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#backupRuntimePermissions");
        try {
            return mService.backupRuntimePermissions(userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void restoreRuntimePermissions(@NonNull byte[] backup, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#restoreRuntimePermissions");
        try {
            mService.restoreRuntimePermissions(backup, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void restoreDelayedRuntimePermissions(@NonNull String packageName, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#restoreDelayedRuntimePermissions");
        try {
            mService.restoreDelayedRuntimePermissions(packageName, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#dump");
        try {
            mService.dump(fd, pw, args);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public List<PermissionGroupInfo> getAllPermissionGroups(int flags) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getAllPermissionGroups");
        try {
            return mService.getAllPermissionGroups(flags);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public PermissionGroupInfo getPermissionGroupInfo(String groupName, int flags) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getPermissionGroupInfo");
        try {
            return mService.getPermissionGroupInfo(groupName, flags);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public PermissionInfo getPermissionInfo(@NonNull String permName, int flags,
            @NonNull String opPackageName) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#getPermissionInfo");
        try {
            return mService.getPermissionInfo(permName, flags, opPackageName);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public List<PermissionInfo> queryPermissionsByGroup(String groupName, int flags) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#queryPermissionsByGroup");
        try {
            return mService.queryPermissionsByGroup(groupName, flags);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean addPermission(PermissionInfo info, boolean async) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#addPermission");
        try {
            return mService.addPermission(info, async);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void removePermission(String permName) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#removePermission");
        try {
            mService.removePermission(permName);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public int getPermissionFlags(String packageName, String permName, int deviceId, int userId) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#getPermissionFlags");
        try {
            return mService.getPermissionFlags(packageName, permName, deviceId, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void updatePermissionFlags(String packageName, String permName, int flagMask,
            int flagValues, boolean checkAdjustPolicyFlagPermission, int deviceId, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#updatePermissionFlags");
        try {
            mService.updatePermissionFlags(packageName, permName, flagMask, flagValues,
                    checkAdjustPolicyFlagPermission, deviceId, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void updatePermissionFlagsForAllApps(int flagMask, int flagValues, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#updatePermissionFlagsForAllApps");
        try {
            mService.updatePermissionFlagsForAllApps(flagMask, flagValues, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void addOnPermissionsChangeListener(IOnPermissionsChangeListener listener) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#addOnPermissionsChangeListener");
        try {
            mService.addOnPermissionsChangeListener(listener);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void removeOnPermissionsChangeListener(
            IOnPermissionsChangeListener listener) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#removeOnPermissionsChangeListener");
        try {
            mService.removeOnPermissionsChangeListener(listener);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean addAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, int flags, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#addAllowlistedRestrictedPermission");
        try {
            return mService.addAllowlistedRestrictedPermission(packageName, permName, flags,
                    userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public List<String> getAllowlistedRestrictedPermissions(@NonNull String packageName, int flags,
            int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getAllowlistedRestrictedPermissions");
        try {
            return mService.getAllowlistedRestrictedPermissions(packageName, flags, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean removeAllowlistedRestrictedPermission(@NonNull String packageName,
            @NonNull String permName, int flags, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#removeAllowlistedRestrictedPermission");
        try {
            return mService.removeAllowlistedRestrictedPermission(packageName, permName, flags,
                    userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void grantRuntimePermission(String packageName, String permName, int deviceId,
            int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#grantRuntimePermission");
        try {
            mService.grantRuntimePermission(packageName, permName, deviceId, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void revokeRuntimePermission(String packageName, String permName, int deviceId,
            int userId, String reason) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#revokeRuntimePermission");
        try {
            mService.revokeRuntimePermission(packageName, permName, deviceId, userId, reason);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void revokePostNotificationPermissionWithoutKillForTest(String packageName, int userId) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl"
                + "#revokePostNotificationPermissionWithoutKillForTest");
        try {
            mService.revokePostNotificationPermissionWithoutKillForTest(packageName, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean shouldShowRequestPermissionRationale(String packageName, String permName,
            int deviceId, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#shouldShowRequestPermissionRationale");
        try {
            return mService.shouldShowRequestPermissionRationale(
                    packageName, permName, deviceId, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean isPermissionRevokedByPolicy(String packageName, String permName, int deviceId,
            int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#isPermissionRevokedByPolicy");
        try {
            return mService.isPermissionRevokedByPolicy(packageName, permName, deviceId, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public List<SplitPermissionInfoParcelable> getSplitPermissions() {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getSplitPermissions");
        try {
            return mService.getSplitPermissions();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public int checkPermission(String pkgName, String permName, int deviceId, int userId) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#checkPermission");
        try {
            return mService.checkPermission(pkgName, permName, deviceId, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public int checkUidPermission(int uid, String permName, int deviceId) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#checkUidPermission");
        try {
            return mService.checkUidPermission(uid, permName, deviceId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public Map<String, Set<String>> getAllAppOpPermissionPackages() {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getAllAppOpPermissionPackages");
        try {
            return mService.getAllAppOpPermissionPackages();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public boolean isPermissionsReviewRequired(@NonNull String packageName, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#isPermissionsReviewRequired");
        try {
            return mService.isPermissionsReviewRequired(packageName, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void resetRuntimePermissions(@NonNull AndroidPackage pkg, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#resetRuntimePermissions");
        try {
            mService.resetRuntimePermissions(pkg, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void resetRuntimePermissionsForUser(int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#resetRuntimePermissionsForUser");
        try {
            mService.resetRuntimePermissionsForUser(userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void readLegacyPermissionStateTEMP() {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#readLegacyPermissionStateTEMP");
        try {
            mService.readLegacyPermissionStateTEMP();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void writeLegacyPermissionStateTEMP() {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#writeLegacyPermissionStateTEMP");
        try {
            mService.writeLegacyPermissionStateTEMP();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @NonNull
    @Override
    public Set<String> getInstalledPermissions(@NonNull String packageName) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getInstalledPermissions");
        try {
            return mService.getInstalledPermissions(packageName);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @NonNull
    @Override
    public Set<String> getGrantedPermissions(@NonNull String packageName, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getGrantedPermissions");
        try {
            return mService.getGrantedPermissions(packageName, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @NonNull
    @Override
    public int[] getPermissionGids(@NonNull String permissionName, int userId) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#getPermissionGids");
        try {
            return mService.getPermissionGids(permissionName, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @NonNull
    @Override
    public String[] getAppOpPermissionPackages(@NonNull String permissionName) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getAppOpPermissionPackages");
        try {
            return mService.getAppOpPermissionPackages(permissionName);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Nullable
    @Override
    public Permission getPermissionTEMP(@NonNull String permName) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#getPermissionTEMP");
        try {
            return mService.getPermissionTEMP(permName);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @NonNull
    @Override
    public List<PermissionInfo> getAllPermissionsWithProtection(int protection) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getAllPermissionsWithProtection");
        try {
            return mService.getAllPermissionsWithProtection(protection);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @NonNull
    @Override
    public List<PermissionInfo> getAllPermissionsWithProtectionFlags(int protectionFlags) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getAllPermissionsWithProtectionFlags");
        try {
            return mService.getAllPermissionsWithProtectionFlags(protectionFlags);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @NonNull
    @Override
    public List<LegacyPermission> getLegacyPermissions() {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getLegacyPermissions");
        try {
            return mService.getLegacyPermissions();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @NonNull
    @Override
    public LegacyPermissionState getLegacyPermissionState(int appId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getLegacyPermissionState");
        try {
            return mService.getLegacyPermissionState(appId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void readLegacyPermissionsTEMP(
            @NonNull LegacyPermissionSettings legacyPermissionSettings) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#readLegacyPermissionsTEMP");
        try {
            mService.readLegacyPermissionsTEMP(legacyPermissionSettings);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void writeLegacyPermissionsTEMP(
            @NonNull LegacyPermissionSettings legacyPermissionSettings) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#writeLegacyPermissionsTEMP");
        try {
            mService.writeLegacyPermissionsTEMP(legacyPermissionSettings);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Nullable
    @Override
    public String getDefaultPermissionGrantFingerprint(int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#getDefaultPermissionGrantFingerprint");
        try {
            return mService.getDefaultPermissionGrantFingerprint(userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void setDefaultPermissionGrantFingerprint(@NonNull String fingerprint, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#setDefaultPermissionGrantFingerprint");
        try {
            mService.setDefaultPermissionGrantFingerprint(fingerprint, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void onSystemReady() {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#onSystemReady");
        try {
            mService.onSystemReady();
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void onStorageVolumeMounted(@NonNull String volumeUuid, boolean fingerprintChanged) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#onStorageVolumeMounted");
        try {
            mService.onStorageVolumeMounted(volumeUuid, fingerprintChanged);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @NonNull
    @Override
    public int[] getGidsForUid(int uid) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#getGidsForUid");
        try {
            return mService.getGidsForUid(uid);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void onUserCreated(int userId) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#onUserCreated");
        try {
            mService.onUserCreated(userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void onUserRemoved(int userId) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#onUserRemoved");
        try {
            mService.onUserRemoved(userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void onPackageAdded(@NonNull PackageState packageState, boolean isInstantApp,
            @Nullable AndroidPackage oldPkg) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#onPackageAdded");
        try {
            mService.onPackageAdded(packageState, isInstantApp, oldPkg);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void onPackageInstalled(@NonNull AndroidPackage pkg, int previousAppId,
            @NonNull PermissionManagerServiceInternal.PackageInstalledParams params, int userId) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#onPackageInstalled");
        try {
            mService.onPackageInstalled(pkg, previousAppId, params, userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void onPackageRemoved(@NonNull AndroidPackage pkg) {
        Trace.traceBegin(TRACE_TAG, "TaggedTracingPermissionManagerServiceImpl#onPackageRemoved");
        try {
            mService.onPackageRemoved(pkg);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }

    @Override
    public void onPackageUninstalled(@NonNull String packageName, int appId,
            @NonNull PackageState packageState, @Nullable AndroidPackage pkg,
            @NonNull List<AndroidPackage> sharedUserPkgs, int userId) {
        Trace.traceBegin(TRACE_TAG,
                "TaggedTracingPermissionManagerServiceImpl#onPackageUninstalled");
        try {
            mService.onPackageUninstalled(packageName, appId, packageState, pkg, sharedUserPkgs,
                    userId);
        } finally {
            Trace.traceEnd(TRACE_TAG);
        }
    }
}
