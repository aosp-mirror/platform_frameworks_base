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

import static android.Manifest.permission.READ_EXTERNAL_STORAGE;
import static android.content.pm.PermissionInfo.PROTECTION_DANGEROUS;
import static android.content.pm.PermissionInfo.PROTECTION_NORMAL;
import static android.content.pm.PermissionInfo.PROTECTION_SIGNATURE;
import static android.content.pm.PermissionInfo.PROTECTION_SIGNATURE_OR_SYSTEM;

import static com.android.server.pm.Settings.ATTR_NAME;
import static com.android.server.pm.Settings.ATTR_PACKAGE;
import static com.android.server.pm.Settings.TAG_ITEM;

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
    static final String TAG = "PackageManager";

    public static final int TYPE_NORMAL = 0;
    public static final int TYPE_BUILTIN = 1;
    public static final int TYPE_DYNAMIC = 2;
    @IntDef(value = {
        TYPE_NORMAL,
        TYPE_BUILTIN,
        TYPE_DYNAMIC,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PermissionType {}

    @IntDef(value = {
        PROTECTION_DANGEROUS,
        PROTECTION_NORMAL,
        PROTECTION_SIGNATURE,
        PROTECTION_SIGNATURE_OR_SYSTEM,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProtectionLevel {}

    @NonNull
    private final String mName;

    private final @PermissionType int mType;

    private String mPackageName;

    private int mProtectionLevel;

    @Nullable
    private PermissionInfo mPermissionInfo;

    @Nullable
    private PermissionInfo mPendingPermissionInfo;

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
        mName = name;
        mPackageName = packageName;
        mType = type;
        // Default to most conservative protection level.
        mProtectionLevel = PermissionInfo.PROTECTION_SIGNATURE;
    }

    @Override
    public String toString() {
        return "BasePermission{" + Integer.toHexString(System.identityHashCode(this)) + " " + mName
                + "}";
    }

    @NonNull
    public String getName() {
        return mName;
    }

    public int getProtectionLevel() {
        return mProtectionLevel;
    }

    public String getPackageName() {
        return mPackageName;
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
        mPermissionInfo = permissionInfo;
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
            return perm.mName.length() + perm.mPermissionInfo.calculateFootprint();
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
        return (mProtectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_NORMAL;
    }
    public boolean isRuntime() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_DANGEROUS;
    }

    public boolean isRemoved() {
        return mPermissionInfo != null
                && (mPermissionInfo.flags & PermissionInfo.FLAG_REMOVED) != 0;
    }

    public boolean isSoftRestricted() {
        return mPermissionInfo != null
                && (mPermissionInfo.flags & PermissionInfo.FLAG_SOFT_RESTRICTED) != 0;
    }

    public boolean isHardRestricted() {
        return mPermissionInfo != null
                && (mPermissionInfo.flags & PermissionInfo.FLAG_HARD_RESTRICTED) != 0;
    }

    public boolean isHardOrSoftRestricted() {
        return mPermissionInfo != null && (mPermissionInfo.flags
                & (PermissionInfo.FLAG_HARD_RESTRICTED | PermissionInfo.FLAG_SOFT_RESTRICTED)) != 0;
    }

    public boolean isImmutablyRestricted() {
        return mPermissionInfo != null
                && (mPermissionInfo.flags & PermissionInfo.FLAG_IMMUTABLY_RESTRICTED) != 0;
    }

    public boolean isInstallerExemptIgnored() {
        return mPermissionInfo != null
                && (mPermissionInfo.flags & PermissionInfo.FLAG_INSTALLER_EXEMPT_IGNORED) != 0;
    }

    public boolean isSignature() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_SIGNATURE;
    }

    public boolean isAppOp() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_APPOP) != 0;
    }
    public boolean isDevelopment() {
        return isSignature()
                && (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0;
    }
    public boolean isInstaller() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_INSTALLER) != 0;
    }
    public boolean isInstant() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_INSTANT) != 0;
    }
    public boolean isOEM() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_OEM) != 0;
    }
    public boolean isPre23() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_PRE23) != 0;
    }
    public boolean isPreInstalled() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_PREINSTALLED) != 0;
    }
    public boolean isPrivileged() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0;
    }
    public boolean isRuntimeOnly() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY) != 0;
    }
    public boolean isSetup() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_SETUP) != 0;
    }
    public boolean isVerifier() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_VERIFIER) != 0;
    }
    public boolean isVendorPrivileged() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_VENDOR_PRIVILEGED) != 0;
    }
    public boolean isSystemTextClassifier() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_SYSTEM_TEXT_CLASSIFIER)
                != 0;
    }
    public boolean isWellbeing() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_WELLBEING) != 0;
    }
    public boolean isDocumenter() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_DOCUMENTER) != 0;
    }
    public boolean isConfigurator() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_CONFIGURATOR)
            != 0;
    }
    public boolean isIncidentReportApprover() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_INCIDENT_REPORT_APPROVER) != 0;
    }
    public boolean isAppPredictor() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_APP_PREDICTOR) != 0;
    }
    public boolean isCompanion() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_COMPANION) != 0;
    }

    public boolean isRetailDemo() {
        return (mProtectionLevel & PermissionInfo.PROTECTION_FLAG_RETAIL_DEMO) != 0;
    }

    public void transfer(@NonNull String origPackageName, @NonNull String newPackageName) {
        if (!origPackageName.equals(mPackageName)) {
            return;
        }
        mPackageName = newPackageName;
        mPermissionInfo = null;
        if (mPendingPermissionInfo != null) {
            mPendingPermissionInfo.packageName = newPackageName;
        }
        mUid = 0;
        mGids = EmptyArray.INT;
        mGidsPerUser = false;
    }

    public boolean addToTree(@ProtectionLevel int protectionLevel,
            @NonNull PermissionInfo permissionInfo, @NonNull BasePermission tree) {
        final boolean changed =
                (mProtectionLevel != protectionLevel
                    || mPermissionInfo == null
                    || mUid != tree.mUid
                    || !Objects.equals(mPermissionInfo.packageName,
                            tree.mPermissionInfo.packageName)
                    || !comparePermissionInfos(mPermissionInfo, permissionInfo));
        mProtectionLevel = protectionLevel;
        mPermissionInfo = new PermissionInfo(permissionInfo);
        mPermissionInfo.protectionLevel = protectionLevel;
        mPermissionInfo.packageName = tree.mPermissionInfo.packageName;
        mUid = tree.mUid;
        return changed;
    }

    public void updateDynamicPermission(Collection<BasePermission> permissionTrees) {
        if (PackageManagerService.DEBUG_SETTINGS) Log.v(TAG, "Dynamic permission: name="
                + getName() + " pkg=" + getPackageName()
                + " info=" + mPendingPermissionInfo);
        if (mPendingPermissionInfo != null) {
            final BasePermission tree = findPermissionTree(permissionTrees, mName);
            if (tree != null && tree.mPermissionInfo != null) {
                mPermissionInfo = new PermissionInfo(mPendingPermissionInfo);
                mPermissionInfo.packageName = tree.mPermissionInfo.packageName;
                mPermissionInfo.name = mName;
                mUid = tree.mUid;
            }
        }
    }

    static BasePermission createOrUpdate(PackageManagerInternal packageManagerInternal,
            @Nullable BasePermission bp, @NonNull PermissionInfo p,
            @NonNull AndroidPackage pkg, Collection<BasePermission> permissionTrees,
            boolean chatty) {
        // Allow system apps to redefine non-system permissions
        if (bp != null && !Objects.equals(bp.mPackageName, p.packageName)) {
            final boolean currentOwnerIsSystem;
            if (bp.mPermissionInfo == null) {
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
                if (bp.mType == BasePermission.TYPE_BUILTIN && bp.mPermissionInfo == null) {
                    // It's a built-in permission and no owner, take ownership now
                    p.flags |= PermissionInfo.FLAG_INSTALLED;
                    bp.mPermissionInfo = p;
                    bp.mUid = pkg.getUid();
                    bp.mPackageName = p.packageName;
                } else if (!currentOwnerIsSystem) {
                    String msg = "New decl " + pkg + " of permission  "
                            + p.name + " is system; overriding " + bp.mPackageName;
                    PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                    bp = null;
                }
            }
        }
        if (bp == null) {
            bp = new BasePermission(p.name, p.packageName, TYPE_NORMAL);
        }
        StringBuilder r = null;
        if (bp.mPermissionInfo == null) {
            if (bp.mPackageName == null
                    || bp.mPackageName.equals(p.packageName)) {
                final BasePermission tree = findPermissionTree(permissionTrees, p.name);
                if (tree == null
                        || tree.mPackageName.equals(p.packageName)) {
                    p.flags |= PermissionInfo.FLAG_INSTALLED;
                    bp.mPermissionInfo = p;
                    bp.mUid = pkg.getUid();
                    bp.mPackageName = p.packageName;
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
                            + tree.mName + " is from package "
                            + tree.mPackageName);
                }
            } else {
                Slog.w(TAG, "Permission " + p.name + " from package "
                        + p.packageName + " ignored: original from "
                        + bp.mPackageName);
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
        if (bp.mPermissionInfo == p) {
            bp.mProtectionLevel = p.protectionLevel;
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
                        + bp.mName + " owned by uid " + bp.mUid);
            }
        }
        throw new SecurityException("No permission tree found for " + permName);
    }

    private static BasePermission findPermissionTree(
            Collection<BasePermission> permissionTrees, String permName) {
        for (BasePermission bp : permissionTrees) {
            if (permName.startsWith(bp.mName)
                    && permName.length() > bp.mName.length()
                    && permName.charAt(bp.mName.length()) == '.') {
                return bp;
            }
        }
        return null;
    }

    @Nullable
    public String getBackgroundPermission() {
        return mPermissionInfo != null ? mPermissionInfo.backgroundPermission : null;
    }

    @Nullable
    public String getGroup() {
        return mPermissionInfo != null ? mPermissionInfo.group : null;
    }

    public int getProtection() {
        return mProtectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
    }

    public int getProtectionFlags() {
        return mProtectionLevel & PermissionInfo.PROTECTION_MASK_FLAGS;
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
            permissionInfo.name = mName;
            permissionInfo.packageName = mPackageName;
            permissionInfo.nonLocalizedLabel = mName;
        }
        if (targetSdkVersion >= Build.VERSION_CODES.O) {
            permissionInfo.protectionLevel = mProtectionLevel;
        } else {
            final int protection = mProtectionLevel & PermissionInfo.PROTECTION_MASK_BASE;
            if (protection == PermissionInfo.PROTECTION_SIGNATURE) {
                // Signature permission's protection flags are always reported.
                permissionInfo.protectionLevel = mProtectionLevel;
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
        final String sourcePackage = parser.getAttributeValue(null, ATTR_PACKAGE);
        final String ptype = parser.getAttributeValue(null, "type");
        if (name == null || sourcePackage == null) {
            PackageManagerService.reportSettingsProblem(Log.WARN,
                    "Error in package manager settings: permissions has" + " no name at "
                            + parser.getPositionDescription());
            return false;
        }
        final boolean dynamic = "dynamic".equals(ptype);
        BasePermission bp = out.get(name);
        // If the permission is builtin, do not clobber it.
        if (bp == null || bp.mType != TYPE_BUILTIN) {
            bp = new BasePermission(name.intern(), sourcePackage,
                    dynamic ? TYPE_DYNAMIC : TYPE_NORMAL);
        }
        bp.mProtectionLevel = readInt(parser, null, "protection",
                PermissionInfo.PROTECTION_NORMAL);
        bp.mProtectionLevel = PermissionInfo.fixProtectionLevel(bp.mProtectionLevel);
        if (dynamic) {
            final PermissionInfo pi = new PermissionInfo();
            pi.packageName = sourcePackage.intern();
            pi.name = name.intern();
            pi.icon = readInt(parser, null, "icon", 0);
            pi.nonLocalizedLabel = parser.getAttributeValue(null, "label");
            pi.protectionLevel = bp.mProtectionLevel;
            bp.mPendingPermissionInfo = pi;
        }
        out.put(bp.mName, bp);
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
        if (mPackageName == null) {
            return;
        }
        serializer.startTag(null, TAG_ITEM);
        serializer.attribute(null, ATTR_NAME, mName);
        serializer.attribute(null, ATTR_PACKAGE, mPackageName);
        if (mProtectionLevel != PermissionInfo.PROTECTION_NORMAL) {
            serializer.attribute(null, "protection", Integer.toString(mProtectionLevel));
        }
        if (mType == BasePermission.TYPE_DYNAMIC) {
            if (mPermissionInfo != null || mPendingPermissionInfo != null) {
                serializer.attribute(null, "type", "dynamic");
                int icon = mPermissionInfo != null ? mPermissionInfo.icon
                        : mPendingPermissionInfo.icon;
                CharSequence nonLocalizedLabel = mPermissionInfo != null
                        ? mPermissionInfo.nonLocalizedLabel
                        : mPendingPermissionInfo.nonLocalizedLabel;

                if (icon != 0) {
                    serializer.attribute(null, "icon", Integer.toString(icon));
                }
                if (nonLocalizedLabel != null) {
                    serializer.attribute(null, "label", nonLocalizedLabel.toString());
                }
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
        if (packageName != null && !packageName.equals(mPackageName)) {
            return false;
        }
        if (permissionNames != null && !permissionNames.contains(mName)) {
            return false;
        }
        if (!printedSomething) {
            if (dumpState.onTitlePrinted())
                pw.println();
            pw.println("Permissions:");
        }
        pw.print("  Permission ["); pw.print(mName); pw.print("] (");
                pw.print(Integer.toHexString(System.identityHashCode(this)));
                pw.println("):");
        pw.print("    sourcePackage="); pw.println(mPackageName);
        pw.print("    uid="); pw.print(mUid);
        pw.print(" gids="); pw.print(Arrays.toString(computeGids(UserHandle.USER_SYSTEM)));
        pw.print(" type="); pw.print(mType);
        pw.print(" prot=");
        pw.println(PermissionInfo.protectionToString(mProtectionLevel));
        if (mPermissionInfo != null) {
            pw.print("    perm="); pw.println(mPermissionInfo);
            if ((mPermissionInfo.flags & PermissionInfo.FLAG_INSTALLED) == 0
                    || (mPermissionInfo.flags & PermissionInfo.FLAG_REMOVED) != 0) {
                pw.print("    flags=0x"); pw.println(Integer.toHexString(mPermissionInfo.flags));
            }
        }
        if (READ_EXTERNAL_STORAGE.equals(mName)) {
            pw.print("    enforced=");
            pw.println(readEnforced);
        }
        return true;
    }
}
