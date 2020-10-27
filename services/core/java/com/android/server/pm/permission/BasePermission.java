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
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.PermissionInfo;
import android.content.pm.parsing.component.ParsedPermission;
import android.os.Build;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class BasePermission {
    private static final String TAG = "PackageManager";

    public static final int TYPE_MANIFEST = 0;
    public static final int TYPE_CONFIG = 1;
    public static final int TYPE_DYNAMIC = 2;
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
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProtectionLevel {}

    private static final String ATTR_NAME = "name";
    private static final String ATTR_PACKAGE = "package";
    private static final String TAG_ITEM = "item";

    private boolean mPermissionDefinitionChanged;

    @NonNull
    private PermissionInfo mPermissionInfo;

    private boolean mReconciled;

    private final @PermissionType int mType;

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

    public BasePermission(@NonNull String name, String packageName, @PermissionType int type) {
        mPermissionInfo = new PermissionInfo();
        mPermissionInfo.name = name;
        mPermissionInfo.packageName = packageName;
        // Default to most conservative protection level.
        mPermissionInfo.protectionLevel = PermissionInfo.PROTECTION_SIGNATURE;
        mType = type;
    }

    @Override
    public String toString() {
        return "BasePermission{" + Integer.toHexString(System.identityHashCode(this)) + " "
                + mPermissionInfo.name + "}";
    }

    @NonNull
    public String getName() {
        return mPermissionInfo.name;
    }

    public int getProtectionLevel() {
        return mPermissionInfo.protectionLevel;
    }

    public String getPackageName() {
        return mPermissionInfo.packageName;
    }

    public boolean isPermissionDefinitionChanged() {
        return mPermissionDefinitionChanged;
    }

    public int getType() {
        return mType;
    }

    public int getUid() {
        return mUid;
    }

    public void setGids(@NonNull int[] gids, boolean gidsPerUser) {
        mGids = gids;
        mGidsPerUser = gidsPerUser;
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

    public void setPermissionDefinitionChanged(boolean shouldOverride) {
        mPermissionDefinitionChanged = shouldOverride;
    }

    public boolean hasGids() {
        return mGids.length != 0;
    }

    @NonNull
    public int[] computeGids(int userId) {
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

    public int calculateFootprint(BasePermission perm) {
        if (mUid == perm.mUid) {
            return perm.mPermissionInfo.name.length() + perm.mPermissionInfo.calculateFootprint();
        }
        return 0;
    }

    public boolean isPermission(ParsedPermission perm) {
        if (mPermissionInfo == null) {
            return false;
        }
        return Objects.equals(mPermissionInfo.packageName, perm.getPackageName())
                && Objects.equals(mPermissionInfo.name, perm.getName());
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

    public boolean isInstallerExemptIgnored() {
        return (mPermissionInfo.flags & PermissionInfo.FLAG_INSTALLER_EXEMPT_IGNORED) != 0;
    }

    public boolean isSignature() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_SIGNATURE;
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

    public boolean isWellbeing() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_WELLBEING) != 0;
    }

    public boolean isDocumenter() {
        return (mPermissionInfo.protectionLevel & PermissionInfo.PROTECTION_FLAG_DOCUMENTER) != 0;
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

    public void transfer(@NonNull String origPackageName, @NonNull String newPackageName) {
        if (!origPackageName.equals(mPermissionInfo.packageName)) {
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
            @NonNull PermissionInfo permissionInfo, @NonNull BasePermission tree) {
        final boolean changed =
                (mPermissionInfo.protectionLevel != protectionLevel
                    || !mReconciled
                    || mUid != tree.mUid
                    || !Objects.equals(mPermissionInfo.packageName,
                            tree.mPermissionInfo.packageName)
                    || !comparePermissionInfos(mPermissionInfo, permissionInfo));
        mPermissionInfo = new PermissionInfo(permissionInfo);
        mPermissionInfo.packageName = tree.mPermissionInfo.packageName;
        mPermissionInfo.protectionLevel = protectionLevel;
        mReconciled = true;
        mUid = tree.mUid;
        return changed;
    }

    public void updateDynamicPermission(Collection<BasePermission> permissionTrees) {
        if (PackageManagerService.DEBUG_SETTINGS) Log.v(TAG, "Dynamic permission: name="
                + getName() + " pkg=" + getPackageName()
                + " info=" + mPermissionInfo);
        if (mType == TYPE_DYNAMIC) {
            final BasePermission tree = findPermissionTree(permissionTrees, mPermissionInfo.name);
            if (tree != null) {
                mPermissionInfo.packageName = tree.mPermissionInfo.packageName;
                mReconciled = true;
                mUid = tree.mUid;
            }
        }
    }

    static BasePermission createOrUpdate(PackageManagerInternal packageManagerInternal,
            @Nullable BasePermission bp, @NonNull PermissionInfo p,
            @NonNull AndroidPackage pkg, Collection<BasePermission> permissionTrees,
            boolean chatty) {
        // Allow system apps to redefine non-system permissions
        boolean ownerChanged = false;
        if (bp != null && !Objects.equals(bp.mPermissionInfo.packageName, p.packageName)) {
            final boolean currentOwnerIsSystem;
            if (!bp.mReconciled) {
                currentOwnerIsSystem = false;
            } else {
                AndroidPackage currentPackage = packageManagerInternal.getPackage(
                        bp.mPermissionInfo.packageName);
                if (currentPackage == null) {
                    currentOwnerIsSystem = false;
                } else {
                    currentOwnerIsSystem = currentPackage.isSystem();
                }
            }

            if (pkg.isSystem()) {
                if (bp.mType == BasePermission.TYPE_CONFIG && !bp.mReconciled) {
                    // It's a built-in permission and no owner, take ownership now
                    p.flags |= PermissionInfo.FLAG_INSTALLED;
                    bp.mPermissionInfo = p;
                    bp.mReconciled = true;
                    bp.mUid = pkg.getUid();
                } else if (!currentOwnerIsSystem) {
                    String msg = "New decl " + pkg + " of permission  "
                            + p.name + " is system; overriding " + bp.mPermissionInfo.packageName;
                    PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                    ownerChanged = true;
                    bp = null;
                }
            }
        }
        if (bp == null) {
            bp = new BasePermission(p.name, p.packageName, TYPE_MANIFEST);
        }
        boolean wasNormal = bp.isNormal();
        StringBuilder r = null;
        if (!bp.mReconciled) {
            if (bp.mPermissionInfo.packageName == null
                    || bp.mPermissionInfo.packageName.equals(p.packageName)) {
                final BasePermission tree = findPermissionTree(permissionTrees, p.name);
                if (tree == null
                        || tree.mPermissionInfo.packageName.equals(p.packageName)) {
                    p.flags |= PermissionInfo.FLAG_INSTALLED;
                    bp.mPermissionInfo = p;
                    bp.mReconciled = true;
                    bp.mUid = pkg.getUid();
                    if (chatty) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(p.name);
                    }
                } else {
                    Slog.w(TAG, "Permission " + p.name + " from package "
                            + p.packageName + " ignored: base tree "
                            + tree.mPermissionInfo.name + " is from package "
                            + tree.mPermissionInfo.packageName);
                }
            } else {
                Slog.w(TAG, "Permission " + p.name + " from package "
                        + p.packageName + " ignored: original from "
                        + bp.mPermissionInfo.packageName);
            }
        } else if (chatty) {
            if (r == null) {
                r = new StringBuilder(256);
            } else {
                r.append(' ');
            }
            r.append("DUP:");
            r.append(p.name);
        }
        if (bp.isRuntime() && (ownerChanged || wasNormal)) {
            // If this is a runtime permission and the owner has changed, or this was a normal
            // permission, then permission state should be cleaned up
            bp.mPermissionDefinitionChanged = true;
        }
        if (PackageManagerService.DEBUG_PACKAGE_SCANNING && r != null) {
            Log.d(TAG, "  Permissions: " + r);
        }
        return bp;
    }

    static BasePermission enforcePermissionTree(
            Collection<BasePermission> permissionTrees, String permName, int callingUid) {
        if (permName != null) {
            BasePermission bp = findPermissionTree(permissionTrees, permName);
            if (bp != null) {
                if (bp.mUid == UserHandle.getAppId(callingUid)) {
                    return bp;
                }
                throw new SecurityException("Calling uid " + callingUid
                        + " is not allowed to add to permission tree "
                        + bp.mPermissionInfo.name + " owned by uid " + bp.mUid);
            }
        }
        throw new SecurityException("No permission tree found for " + permName);
    }

    private static BasePermission findPermissionTree(
            Collection<BasePermission> permissionTrees, String permName) {
        for (BasePermission bp : permissionTrees) {
            if (permName.startsWith(bp.mPermissionInfo.name)
                    && permName.length() > bp.mPermissionInfo.name.length()
                    && permName.charAt(bp.mPermissionInfo.name.length()) == '.') {
                return bp;
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
        PermissionInfo permissionInfo;
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

    public static boolean readLPw(@NonNull Map<String, BasePermission> out,
            @NonNull XmlPullParser parser) {
        final String tagName = parser.getName();
        if (!tagName.equals(TAG_ITEM)) {
            return false;
        }
        final String name = parser.getAttributeValue(null, ATTR_NAME);
        final String packageName = parser.getAttributeValue(null, ATTR_PACKAGE);
        final String ptype = parser.getAttributeValue(null, "type");
        if (name == null || packageName == null) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: permissions has" + " no name at "
                            + parser.getPositionDescription());
            return false;
        }
        final boolean dynamic = "dynamic".equals(ptype);
        BasePermission bp = out.get(name);
        // If the permission is builtin, do not clobber it.
        if (bp == null || bp.mType != TYPE_CONFIG) {
            bp = new BasePermission(name.intern(), packageName,
                    dynamic ? TYPE_DYNAMIC : TYPE_MANIFEST);
        }
        bp.mPermissionInfo.protectionLevel = readInt(parser, null, "protection",
                PermissionInfo.PROTECTION_NORMAL);
        bp.mPermissionInfo.protectionLevel = PermissionInfo.fixProtectionLevel(
                bp.mPermissionInfo.protectionLevel);
        if (dynamic) {
            bp.mPermissionInfo.icon = readInt(parser, null, "icon", 0);
            bp.mPermissionInfo.nonLocalizedLabel = parser.getAttributeValue(null, "label");
        }
        out.put(bp.mPermissionInfo.name, bp);
        return true;
    }

    private static int readInt(XmlPullParser parser, String ns, String name, int defValue) {
        String v = parser.getAttributeValue(ns, name);
        try {
            if (v == null) {
                return defValue;
            }
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: attribute " + name
                            + " has bad integer value " + v + " at "
                            + parser.getPositionDescription());
        }
        return defValue;
    }

    public void writeLPr(@NonNull XmlSerializer serializer) throws IOException {
        if (mPermissionInfo.packageName == null) {
            return;
        }
        serializer.startTag(null, TAG_ITEM);
        serializer.attribute(null, ATTR_NAME, mPermissionInfo.name);
        serializer.attribute(null, ATTR_PACKAGE, mPermissionInfo.packageName);
        if (mPermissionInfo.protectionLevel != PermissionInfo.PROTECTION_NORMAL) {
            serializer.attribute(null, "protection",
                    Integer.toString(mPermissionInfo.protectionLevel));
        }
        if (mType == TYPE_DYNAMIC) {
            serializer.attribute(null, "type", "dynamic");
            if (mPermissionInfo.icon != 0) {
                serializer.attribute(null, "icon", Integer.toString(mPermissionInfo.icon));
            }
            if (mPermissionInfo.nonLocalizedLabel != null) {
                serializer.attribute(null, "label", mPermissionInfo.nonLocalizedLabel.toString());
            }
        }
        serializer.endTag(null, TAG_ITEM);
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

    public boolean dumpPermissionsLPr(@NonNull PrintWriter pw, @NonNull String packageName,
            @NonNull Set<String> permissionNames, boolean readEnforced,
            boolean printedSomething, @NonNull DumpState dumpState) {
        if (packageName != null && !packageName.equals(mPermissionInfo.packageName)) {
            return false;
        }
        if (permissionNames != null && !permissionNames.contains(mPermissionInfo.name)) {
            return false;
        }
        if (!printedSomething) {
            if (dumpState.onTitlePrinted())
                pw.println();
            pw.println("Permissions:");
        }
        pw.print("  Permission ["); pw.print(mPermissionInfo.name); pw.print("] (");
                pw.print(Integer.toHexString(System.identityHashCode(this)));
                pw.println("):");
        pw.print("    sourcePackage="); pw.println(mPermissionInfo.packageName);
        pw.print("    uid="); pw.print(mUid);
        pw.print(" gids="); pw.print(Arrays.toString(computeGids(UserHandle.USER_SYSTEM)));
        pw.print(" type="); pw.print(mType);
        pw.print(" prot=");
        pw.println(PermissionInfo.protectionToString(mPermissionInfo.protectionLevel));
        if (mPermissionInfo != null) {
            pw.print("    perm="); pw.println(mPermissionInfo);
            if ((mPermissionInfo.flags & PermissionInfo.FLAG_INSTALLED) == 0
                    || (mPermissionInfo.flags & PermissionInfo.FLAG_REMOVED) != 0) {
                pw.print("    flags=0x"); pw.println(Integer.toHexString(mPermissionInfo.flags));
            }
        }
        if (Objects.equals(mPermissionInfo.name,
                android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
            pw.print("    enforced=");
            pw.println(readEnforced);
        }
        return true;
    }
}
