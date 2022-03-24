/*
 * Copyright (C) 2006 The Android Open Source Project
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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PermissionInfo;
import com.android.server.pm.pkg.component.ParsedPermission;
import android.os.Build;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.server.pm.PackageManagerService;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import libcore.util.EmptyArray;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collection;
import java.util.Objects;
import java.util.Set;

/**
 * Permission definition.
 */
public final class Permission {
    private static final String TAG = "Permission";

    public static final int TYPE_MANIFEST = LegacyPermission.TYPE_MANIFEST;
    public static final int TYPE_CONFIG = LegacyPermission.TYPE_CONFIG;
    public static final int TYPE_DYNAMIC = LegacyPermission.TYPE_DYNAMIC;
    @IntDef({
            TYPE_MANIFEST,
            TYPE_CONFIG,
            TYPE_DYNAMIC,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionType {}

    @IntDef({
            PermissionInfo.PROTECTION_DANGEROUS,
            PermissionInfo.PROTECTION_NORMAL,
            PermissionInfo.PROTECTION_SIGNATURE,
            PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM,
            PermissionInfo.PROTECTION_INTERNAL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProtectionLevel {}

    @NonNull
    private PermissionInfo mPermissionInfo;

    private boolean mReconciled;

    @PermissionType
    private final int mType;

    /** UID that owns the definition of this permission */
    private int mUid;

    /** Additional GIDs given to apps granted this permission */
    @NonNull
    private int[] mGids = EmptyArray.INT;

    /**
     * Flag indicating that {@link #mGids} should be adjusted based on the
     * {@link UserHandle} the granted app is running as.
     */
    private boolean mGidsPerUser;

    private boolean mDefinitionChanged;

    public Permission(@NonNull String name, @NonNull String packageName,
            @PermissionType int type) {
        mPermissionInfo = new PermissionInfo();
        mPermissionInfo.name = name;
        mPermissionInfo.packageName = packageName;
        // Default to most conservative protection level.
        mPermissionInfo.protectionLevel = PermissionInfo.PROTECTION_SIGNATURE;
        mType = type;
    }

    public Permission(@NonNull PermissionInfo permissionInfo, @PermissionType int type) {
        mPermissionInfo = permissionInfo;
        mType = type;
    }

    @NonNull
    public PermissionInfo getPermissionInfo() {
        return mPermissionInfo;
    }

    public void setPermissionInfo(@Nullable PermissionInfo permissionInfo) {
        if (permissionInfo != null) {
            mPermissionInfo = permissionInfo;
        } else {
            final PermissionInfo newPermissionInfo = new PermissionInfo();
            newPermissionInfo.name = mPermissionInfo.name;
            newPermissionInfo.packageName = mPermissionInfo.packageName;
            newPermissionInfo.protectionLevel = mPermissionInfo.protectionLevel;
            mPermissionInfo = newPermissionInfo;
        }
        mReconciled = permissionInfo != null;
    }

    @NonNull
    public String getName() {
        return mPermissionInfo.name;
    }

    public int getProtectionLevel() {
        return mPermissionInfo.protectionLevel;
    }

    @NonNull
    public String getPackageName() {
        return mPermissionInfo.packageName;
    }

    public int getType() {
        return mType;
    }

    public int getUid() {
        return mUid;
    }

    public boolean hasGids() {
        return mGids.length != 0;
    }

    @NonNull
    public int[] getRawGids() {
        return mGids;
    }

    public boolean areGidsPerUser() {
        return mGidsPerUser;
    }

    public void setGids(@NonNull int[] gids, boolean gidsPerUser) {
        mGids = gids;
        mGidsPerUser = gidsPerUser;
    }

    @NonNull
    public int[] computeGids(@UserIdInt int userId) {
        if (mGidsPerUser) {
            final int[] userGids = new int[mGids.length];
            for (int i = 0; i < mGids.length; i++) {
                final int gid = mGids[i];
                userGids[i] = UserHandle.getUid(userId, gid);
            }
            return userGids;
        } else {
            return mGids.length != 0 ? mGids.clone() : mGids;
        }
    }

    public boolean isDefinitionChanged() {
        return mDefinitionChanged;
    }

    public void setDefinitionChanged(boolean definitionChanged) {
        mDefinitionChanged = definitionChanged;
    }

    public int calculateFootprint(@NonNull Permission permission) {
        if (mUid == permission.mUid) {
            return permission.mPermissionInfo.name.length()
                    + permission.mPermissionInfo.calculateFootprint();
        }
        return 0;
    }

    public boolean isPermission(@NonNull ParsedPermission parsedPermission) {
        if (mPermissionInfo == null) {
            return false;
        }
        return Objects.equals(mPermissionInfo.packageName, parsedPermission.getPackageName())
                && Objects.equals(mPermissionInfo.name, parsedPermission.getName());
    }

    public boolean isDynamic() {
        return mType == TYPE_DYNAMIC;
    }

    public boolean isNormal() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_NORMAL;
    }
    public boolean isRuntime() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_DANGEROUS;
    }

    public boolean isInstalled() {
        return (mPermissionInfo.flags & PermissionInfo.FLAG_INSTALLED) != 0;
    }

    public boolean isRemoved() {
        return (mPermissionInfo.flags & PermissionInfo.FLAG_REMOVED) != 0;
    }

    public boolean isSoftRestricted() {
        return (mPermissionInfo.flags & PermissionInfo.FLAG_SOFT_RESTRICTED) != 0;
    }

    public boolean isHardRestricted() {
        return (mPermissionInfo.flags & PermissionInfo.FLAG_HARD_RESTRICTED) != 0;
    }

    public boolean isHardOrSoftRestricted() {
        return (mPermissionInfo.flags & (PermissionInfo.FLAG_HARD_RESTRICTED
                | PermissionInfo.FLAG_SOFT_RESTRICTED)) != 0;
    }

    public boolean isImmutablyRestricted() {
        return (mPermissionInfo.flags & PermissionInfo.FLAG_IMMUTABLY_RESTRICTED) != 0;
    }

    public boolean isSignature() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_SIGNATURE;
    }

    public boolean isInternal() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_INTERNAL;
    }

    public boolean isAppOp() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_APPOP) != 0;
    }

    public boolean isDevelopment() {
        return isSignature() && (mPermissionInfo.protectionLevel
                & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0;
    }

    public boolean isInstaller() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTALLER) != 0;
    }

    public boolean isInstant() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTANT) != 0;
    }

    public boolean isOem() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_OEM) != 0;
    }

    public boolean isPre23() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_PRE23) != 0;
    }

    public boolean isPreInstalled() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_PREINSTALLED) != 0;
    }

    public boolean isPrivileged() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0;
    }

    public boolean isRuntimeOnly() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY) != 0;
    }

    public boolean isSetup() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_SETUP) != 0;
    }

    public boolean isVerifier() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_VERIFIER) != 0;
    }

    public boolean isVendorPrivileged() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_VENDOR_PRIVILEGED)
                != 0;
    }

    public boolean isSystemTextClassifier() {
        return (mPermissionInfo.protectionLevel
                & PermissionInfo.PROTECTION_FLAG_SYSTEM_TEXT_CLASSIFIER) != 0;
    }

    public boolean isConfigurator() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_CONFIGURATOR) != 0;
    }

    public boolean isIncidentReportApprover() {
        return (mPermissionInfo.protectionLevel
                & PermissionInfo.PROTECTION_FLAG_INCIDENT_REPORT_APPROVER) != 0;
    }

    public boolean isAppPredictor() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_APP_PREDICTOR)
                != 0;
    }

    public boolean isCompanion() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_COMPANION) != 0;
    }

    public boolean isRetailDemo() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_RETAIL_DEMO) != 0;
    }

    public boolean isRecents() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_RECENTS) != 0;
    }

    public boolean isRole() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_ROLE) != 0;
    }

    public boolean isKnownSigner() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_KNOWN_SIGNER) != 0;
    }

    public Set<String> getKnownCerts() {
        return mPermissionInfo.knownCerts;
    }

    public void transfer(@NonNull String oldPackageName, @NonNull String newPackageName) {
        if (!oldPackageName.equals(mPermissionInfo.packageName)) {
            return;
        }
        final PermissionInfo newPermissionInfo = new PermissionInfo();
        newPermissionInfo.name = mPermissionInfo.name;
        newPermissionInfo.packageName = newPackageName;
        newPermissionInfo.protectionLevel = mPermissionInfo.protectionLevel;
        mPermissionInfo = newPermissionInfo;
        mReconciled = false;
        mUid = 0;
        mGids = EmptyArray.INT;
        mGidsPerUser = false;
    }

    public boolean addToTree(@ProtectionLevel int protectionLevel,
            @NonNull PermissionInfo permissionInfo, @NonNull Permission permissionTree) {
        final boolean changed =
                (mPermissionInfo.protectionLevel != protectionLevel
                    || !mReconciled
                    || mUid != permissionTree.mUid
                    || !Objects.equals(mPermissionInfo.packageName,
                            permissionTree.mPermissionInfo.packageName)
                    || !comparePermissionInfos(mPermissionInfo, permissionInfo));
        mPermissionInfo = new PermissionInfo(permissionInfo);
        mPermissionInfo.packageName = permissionTree.mPermissionInfo.packageName;
        mPermissionInfo.protectionLevel = protectionLevel;
        mReconciled = true;
        mUid = permissionTree.mUid;
        return changed;
    }

    public void updateDynamicPermission(@NonNull Collection<Permission> permissionTrees) {
        if (PackageManagerService.DEBUG_SETTINGS) {
            Log.v(TAG, "Dynamic permission: name=" + getName() + " pkg=" + getPackageName()
                    + " info=" + mPermissionInfo);
        }
        if (mType == TYPE_DYNAMIC) {
            final Permission tree = findPermissionTree(permissionTrees, mPermissionInfo.name);
            if (tree != null) {
                mPermissionInfo.packageName = tree.mPermissionInfo.packageName;
                mReconciled = true;
                mUid = tree.mUid;
            }
        }
    }

    public static boolean isOverridingSystemPermission(@Nullable Permission permission,
            @NonNull PermissionInfo permissionInfo,
            @NonNull PackageManagerInternal packageManagerInternal) {
        if (permission == null || Objects.equals(permission.mPermissionInfo.packageName,
                permissionInfo.packageName)) {
            return false;
        }
        if (!permission.mReconciled) {
            return false;
        }
        final AndroidPackage currentPackage = packageManagerInternal.getPackage(
                permission.mPermissionInfo.packageName);
        if (currentPackage == null) {
            return false;
        }
        return currentPackage.isSystem();
    }

    @NonNull
    public static Permission createOrUpdate(@Nullable Permission permission,
            @NonNull PermissionInfo permissionInfo, @NonNull AndroidPackage pkg,
            @NonNull Collection<Permission> permissionTrees, boolean isOverridingSystemPermission) {
        // Allow system apps to redefine non-system permissions
        boolean ownerChanged = false;
        if (permission != null && !Objects.equals(permission.mPermissionInfo.packageName,
                permissionInfo.packageName)) {
            if (pkg.isSystem()) {
                if (permission.mType == Permission.TYPE_CONFIG && !permission.mReconciled) {
                    // It's a built-in permission and no owner, take ownership now
                    permissionInfo.flags |= PermissionInfo.FLAG_INSTALLED;
                    permission.mPermissionInfo = permissionInfo;
                    permission.mReconciled = true;
                    permission.mUid = pkg.getUid();
                } else if (!isOverridingSystemPermission) {
                    Slog.w(TAG, "New decl " + pkg + " of permission  "
                            + permissionInfo.name + " is system; overriding "
                            + permission.mPermissionInfo.packageName);
                    ownerChanged = true;
                    permission = null;
                }
            }
        }
        boolean wasNonInternal = permission != null && permission.mType != TYPE_CONFIG
                && !permission.isInternal();
        boolean wasNonRuntime = permission != null && permission.mType != TYPE_CONFIG
                && !permission.isRuntime();
        if (permission == null) {
            permission = new Permission(permissionInfo.name, permissionInfo.packageName,
                    TYPE_MANIFEST);
        }
        StringBuilder r = null;
        if (!permission.mReconciled) {
            if (permission.mPermissionInfo.packageName == null
                    || permission.mPermissionInfo.packageName.equals(permissionInfo.packageName)) {
                final Permission tree = findPermissionTree(permissionTrees, permissionInfo.name);
                if (tree == null
                        || tree.mPermissionInfo.packageName.equals(permissionInfo.packageName)) {
                    permissionInfo.flags |= PermissionInfo.FLAG_INSTALLED;
                    permission.mPermissionInfo = permissionInfo;
                    permission.mReconciled = true;
                    permission.mUid = pkg.getUid();
                    if (PackageManagerService.DEBUG_PACKAGE_SCANNING) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(permissionInfo.name);
                    }
                } else {
                    Slog.w(TAG, "Permission " + permissionInfo.name + " from package "
                            + permissionInfo.packageName + " ignored: base tree "
                            + tree.mPermissionInfo.name + " is from package "
                            + tree.mPermissionInfo.packageName);
                }
            } else {
                Slog.w(TAG, "Permission " + permissionInfo.name + " from package "
                        + permissionInfo.packageName + " ignored: original from "
                        + permission.mPermissionInfo.packageName);
            }
        } else if (PackageManagerService.DEBUG_PACKAGE_SCANNING) {
            if (r == null) {
                r = new StringBuilder(256);
            } else {
                r.append(' ');
            }
            r.append("DUP:");
            r.append(permissionInfo.name);
        }
        if ((permission.isInternal() && (ownerChanged || wasNonInternal))
                || (permission.isRuntime() && (ownerChanged || wasNonRuntime))) {
            // If this is an internal/runtime permission and the owner has changed, or this wasn't a
            // internal/runtime permission, then permission state should be cleaned up.
            permission.mDefinitionChanged = true;
        }
        if (PackageManagerService.DEBUG_PACKAGE_SCANNING && r != null) {
            Log.d(TAG, "  Permissions: " + r);
        }
        return permission;
    }

    @NonNull
    public static Permission enforcePermissionTree(@NonNull Collection<Permission> permissionTrees,
            @NonNull String permissionName, int callingUid) {
        if (permissionName != null) {
            final Permission permissionTree = Permission.findPermissionTree(permissionTrees,
                    permissionName);
            if (permissionTree != null) {
                if (permissionTree.getUid() == UserHandle.getAppId(callingUid)) {
                    return permissionTree;
                }
            }
        }
        throw new SecurityException("Calling uid " + callingUid
            + " is not allowed to add to or remove from the permission tree");
    }

    @Nullable
    private static Permission findPermissionTree(@NonNull Collection<Permission> permissionTrees,
            @NonNull String permissionName) {
        for (final Permission permissionTree : permissionTrees) {
            final String permissionTreeName = permissionTree.getName();
            if (permissionName.startsWith(permissionTreeName)
                    && permissionName.length() > permissionTreeName.length()
                    && permissionName.charAt(permissionTreeName.length()) == '.') {
                return permissionTree;
            }
        }
        return null;
    }

    @Nullable
    public String getBackgroundPermission() {
        return mPermissionInfo.backgroundPermission;
    }

    @Nullable
    public String getGroup() {
        return mPermissionInfo.group;
    }

    public int getProtection() {
        return mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
    }

    public int getProtectionFlags() {
        return mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_FLAGS;
    }

    @NonNull
    public PermissionInfo generatePermissionInfo(int flags) {
        return generatePermissionInfo(flags, Build.VERSION_CODES.CUR_DEVELOPMENT);
    }

    @NonNull
    public PermissionInfo generatePermissionInfo(int flags, int targetSdkVersion) {
        final PermissionInfo permissionInfo;
        if (mPermissionInfo != null) {
            permissionInfo = new PermissionInfo(mPermissionInfo);
            if ((flags & PackageManager.GET_META_DATA) != PackageManager.GET_META_DATA) {
                permissionInfo.metaData = null;
            }
        } else {
            permissionInfo = new PermissionInfo();
            permissionInfo.name = mPermissionInfo.name;
            permissionInfo.packageName = mPermissionInfo.packageName;
            permissionInfo.nonLocalizedLabel = mPermissionInfo.name;
        }
        if (targetSdkVersion >= Build.VERSION_CODES.O) {
            permissionInfo.protectionLevel = mPermissionInfo.protectionLevel;
        } else {
            final int protection = mPermissionInfo.protectionLevel
                    & PermissionInfo.PROTECTION_MASK_BASE;
            if (protection == PermissionInfo.PROTECTION_SIGNATURE) {
                // Signature permission's protection flags are always reported.
                permissionInfo.protectionLevel = mPermissionInfo.protectionLevel;
            } else {
                permissionInfo.protectionLevel = protection;
            }
        }
        return permissionInfo;
    }

    private static boolean comparePermissionInfos(PermissionInfo pi1, PermissionInfo pi2) {
        if (pi1.icon != pi2.icon) return false;
        if (pi1.logo != pi2.logo) return false;
        if (pi1.protectionLevel != pi2.protectionLevel) return false;
        if (!Objects.equals(pi1.name, pi2.name)) return false;
        if (!Objects.equals(pi1.nonLocalizedLabel, pi2.nonLocalizedLabel)) return false;
        // We'll take care of setting this one.
        if (!Objects.equals(pi1.packageName, pi2.packageName)) return false;
        // These are not currently stored in settings.
        //if (!compareStrings(pi1.group, pi2.group)) return false;
        //if (!compareStrings(pi1.nonLocalizedDescription, pi2.nonLocalizedDescription)) return false;
        //if (pi1.labelRes != pi2.labelRes) return false;
        //if (pi1.descriptionRes != pi2.descriptionRes) return false;
        return true;
    }
}
