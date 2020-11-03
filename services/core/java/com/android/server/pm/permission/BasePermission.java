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
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.Permission;
import android.content.pm.PermissionInfo;
import android.content.pm.Signature;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.PackageSettingBase;

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

    final String name;

    final @PermissionType int type;

    private boolean mPermissionDefinitionChanged;

    String sourcePackageName;

    // TODO: Can we get rid of this? Seems we only use some signature info from the setting
    PackageSettingBase sourcePackageSetting;

    int protectionLevel;

    PackageParser.Permission perm;

    PermissionInfo pendingPermissionInfo;

    /** UID that owns the definition of this permission */
    int uid;

    /** Additional GIDs given to apps granted this permission */
    private int[] gids;

    /**
     * Flag indicating that {@link #gids} should be adjusted based on the
     * {@link UserHandle} the granted app is running as.
     */
    private boolean perUser;

    public BasePermission(String _name, String _sourcePackageName, @PermissionType int _type) {
        name = _name;
        sourcePackageName = _sourcePackageName;
        type = _type;
        // Default to most conservative protection level.
        protectionLevel = PermissionInfo.PROTECTION_SIGNATURE;
    }

    @Override
    public String toString() {
        return "BasePermission{" + Integer.toHexString(System.identityHashCode(this)) + " " + name
                + "}";
    }

    public String getName() {
        return name;
    }
    public int getProtectionLevel() {
        return protectionLevel;
    }
    public String getSourcePackageName() {
        return sourcePackageName;
    }
    public PackageSettingBase getSourcePackageSetting() {
        return sourcePackageSetting;
    }
    public Signature[] getSourceSignatures() {
        return sourcePackageSetting.getSignatures();
    }

    public boolean isPermissionDefinitionChanged() {
        return mPermissionDefinitionChanged;
    }

    public int getType() {
        return type;
    }
    public int getUid() {
        return uid;
    }
    public void setGids(int[] gids, boolean perUser) {
        this.gids = gids;
        this.perUser = perUser;
    }
    public void setPermission(@Nullable Permission perm) {
        this.perm = perm;
    }
    public void setSourcePackageSetting(PackageSettingBase sourcePackageSetting) {
        this.sourcePackageSetting = sourcePackageSetting;
    }

    public void setPermissionDefinitionChanged(boolean shouldOverride) {
        mPermissionDefinitionChanged = shouldOverride;
    }

    public int[] computeGids(int userId) {
        if (perUser) {
            final int[] userGids = new int[gids.length];
            for (int i = 0; i < gids.length; i++) {
                userGids[i] = UserHandle.getUid(userId, gids[i]);
            }
            return userGids;
        } else {
            return gids;
        }
    }

    public int calculateFootprint(BasePermission perm) {
        if (uid == perm.uid) {
            return perm.name.length() + perm.perm.info.calculateFootprint();
        }
        return 0;
    }

    public boolean isPermission(Permission perm) {
        return this.perm == perm;
    }

    public boolean isDynamic() {
        return type == TYPE_DYNAMIC;
    }


    public boolean isNormal() {
        return (protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_NORMAL;
    }
    public boolean isRuntime() {
        return (protectionLevel & PermissionInfo.PROTECTION_MASK_BASE)
                == PermissionInfo.PROTECTION_DANGEROUS;
    }

    public boolean isRemoved() {
        return perm != null && perm.info != null
                && (perm.info.flags & PermissionInfo.FLAG_REMOVED) != 0;
    }

    public boolean isSoftRestricted() {
        return perm != null && perm.info != null
                && (perm.info.flags & PermissionInfo.FLAG_SOFT_RESTRICTED) != 0;
    }

    public boolean isHardRestricted() {
        return perm != null && perm.info != null
                && (perm.info.flags & PermissionInfo.FLAG_HARD_RESTRICTED) != 0;
    }

    public boolean isHardOrSoftRestricted() {
        return perm != null && perm.info != null
                && (perm.info.flags & (PermissionInfo.FLAG_HARD_RESTRICTED
                | PermissionInfo.FLAG_SOFT_RESTRICTED)) != 0;
    }

    public boolean isImmutablyRestricted() {
        return perm != null && perm.info != null
                && (perm.info.flags & PermissionInfo.FLAG_IMMUTABLY_RESTRICTED) != 0;
    }

    public boolean isSignature() {
        return (protectionLevel & PermissionInfo.PROTECTION_MASK_BASE) ==
                PermissionInfo.PROTECTION_SIGNATURE;
    }

    public boolean isAppOp() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_APPOP) != 0;
    }
    public boolean isDevelopment() {
        return isSignature()
                && (protectionLevel & PermissionInfo.PROTECTION_FLAG_DEVELOPMENT) != 0;
    }
    public boolean isInstaller() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTALLER) != 0;
    }
    public boolean isInstant() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_INSTANT) != 0;
    }
    public boolean isOEM() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_OEM) != 0;
    }
    public boolean isPre23() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_PRE23) != 0;
    }
    public boolean isPreInstalled() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_PREINSTALLED) != 0;
    }
    public boolean isPrivileged() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_PRIVILEGED) != 0;
    }
    public boolean isRuntimeOnly() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY) != 0;
    }
    public boolean isSetup() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_SETUP) != 0;
    }
    public boolean isVerifier() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_VERIFIER) != 0;
    }
    public boolean isVendorPrivileged() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_VENDOR_PRIVILEGED) != 0;
    }
    public boolean isSystemTextClassifier() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_SYSTEM_TEXT_CLASSIFIER)
                != 0;
    }
    public boolean isWellbeing() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_WELLBEING) != 0;
    }
    public boolean isDocumenter() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_DOCUMENTER) != 0;
    }
    public boolean isConfigurator() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_CONFIGURATOR)
            != 0;
    }
    public boolean isIncidentReportApprover() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_INCIDENT_REPORT_APPROVER) != 0;
    }
    public boolean isAppPredictor() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_APP_PREDICTOR) != 0;
    }

    public void transfer(@NonNull String origPackageName, @NonNull String newPackageName) {
        if (!origPackageName.equals(sourcePackageName)) {
            return;
        }
        sourcePackageName = newPackageName;
        sourcePackageSetting = null;
        perm = null;
        if (pendingPermissionInfo != null) {
            pendingPermissionInfo.packageName = newPackageName;
        }
        uid = 0;
        setGids(null, false);
    }

    public boolean addToTree(@ProtectionLevel int protectionLevel,
            @NonNull PermissionInfo info, @NonNull BasePermission tree) {
        final boolean changed =
                (this.protectionLevel != protectionLevel
                    || perm == null
                    || uid != tree.uid
                    || !perm.owner.equals(tree.perm.owner)
                    || !comparePermissionInfos(perm.info, info));
        this.protectionLevel = protectionLevel;
        info = new PermissionInfo(info);
        info.protectionLevel = protectionLevel;
        perm = new PackageParser.Permission(tree.perm.owner, info);
        perm.info.packageName = tree.perm.info.packageName;
        uid = tree.uid;
        return changed;
    }

    public void updateDynamicPermission(Collection<BasePermission> permissionTrees) {
        if (PackageManagerService.DEBUG_SETTINGS) Log.v(TAG, "Dynamic permission: name="
                + getName() + " pkg=" + getSourcePackageName()
                + " info=" + pendingPermissionInfo);
        if (sourcePackageSetting == null && pendingPermissionInfo != null) {
            final BasePermission tree = findPermissionTree(permissionTrees, name);
            if (tree != null && tree.perm != null) {
                sourcePackageSetting = tree.sourcePackageSetting;
                perm = new PackageParser.Permission(tree.perm.owner,
                        new PermissionInfo(pendingPermissionInfo));
                perm.info.packageName = tree.perm.info.packageName;
                perm.info.name = name;
                uid = tree.uid;
            }
        }
    }

    static BasePermission createOrUpdate(@Nullable BasePermission bp, @NonNull Permission p,
            @NonNull PackageParser.Package pkg, Collection<BasePermission> permissionTrees,
            boolean chatty) {
        final PackageSettingBase pkgSetting = (PackageSettingBase) pkg.mExtras;
        // Allow system apps to redefine non-system permissions
        boolean ownerChanged = false;
        if (bp != null && !Objects.equals(bp.sourcePackageName, p.info.packageName)) {
            final boolean currentOwnerIsSystem = (bp.perm != null
                    && bp.perm.owner.isSystem());
            if (p.owner.isSystem()) {
                if (bp.type == BasePermission.TYPE_BUILTIN && bp.perm == null) {
                    // It's a built-in permission and no owner, take ownership now
                    bp.sourcePackageSetting = pkgSetting;
                    bp.perm = p;
                    bp.uid = pkg.applicationInfo.uid;
                    bp.sourcePackageName = p.info.packageName;
                    p.info.flags |= PermissionInfo.FLAG_INSTALLED;
                } else if (!currentOwnerIsSystem) {
                    String msg = "New decl " + p.owner + " of permission  "
                            + p.info.name + " is system; overriding " + bp.sourcePackageName;
                    PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                    ownerChanged = true;
                    bp = null;
                }
            }
        }
        if (bp == null) {
            bp = new BasePermission(p.info.name, p.info.packageName, TYPE_NORMAL);
        }
        boolean wasNonRuntime = !bp.isRuntime();
        StringBuilder r = null;
        if (bp.perm == null) {
            if (bp.sourcePackageName == null
                    || bp.sourcePackageName.equals(p.info.packageName)) {
                final BasePermission tree = findPermissionTree(permissionTrees, p.info.name);
                if (tree == null
                        || tree.sourcePackageName.equals(p.info.packageName)) {
                    bp.sourcePackageSetting = pkgSetting;
                    bp.perm = p;
                    bp.uid = pkg.applicationInfo.uid;
                    bp.sourcePackageName = p.info.packageName;
                    p.info.flags |= PermissionInfo.FLAG_INSTALLED;
                    if (chatty) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(p.info.name);
                    }
                } else {
                    Slog.w(TAG, "Permission " + p.info.name + " from package "
                            + p.info.packageName + " ignored: base tree "
                            + tree.name + " is from package "
                            + tree.sourcePackageName);
                }
            } else {
                Slog.w(TAG, "Permission " + p.info.name + " from package "
                        + p.info.packageName + " ignored: original from "
                        + bp.sourcePackageName);
            }
        } else if (chatty) {
            if (r == null) {
                r = new StringBuilder(256);
            } else {
                r.append(' ');
            }
            r.append("DUP:");
            r.append(p.info.name);
        }
        if (bp.perm == p) {
            bp.protectionLevel = p.info.protectionLevel;
        }
        if (bp.isRuntime() && (ownerChanged || wasNonRuntime)) {
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
                if (bp.uid == UserHandle.getAppId(callingUid)) {
                    return bp;
                }
                throw new SecurityException("Calling uid " + callingUid
                        + " is not allowed to add to permission tree "
                        + bp.name + " owned by uid " + bp.uid);
            }
        }
        throw new SecurityException("No permission tree found for " + permName);
    }

    public void enforceDeclaredUsedAndRuntimeOrDevelopment(PackageParser.Package pkg) {
        final PackageSetting pkgSetting = (PackageSetting) pkg.mExtras;
        final PermissionsState permsState = pkgSetting.getPermissionsState();
        int index = pkg.requestedPermissions.indexOf(name);
        if (!permsState.hasRequestedPermission(name) && index == -1) {
            throw new SecurityException("Package " + pkg.packageName
                    + " has not requested permission " + name);
        }
        if (!isRuntime() && !isDevelopment()) {
            throw new SecurityException("Permission " + name
                    + " requested by " + pkg.packageName + " is not a changeable permission type");
        }
    }

    private static BasePermission findPermissionTree(
            Collection<BasePermission> permissionTrees, String permName) {
        for (BasePermission bp : permissionTrees) {
            if (permName.startsWith(bp.name) &&
                    permName.length() > bp.name.length() &&
                    permName.charAt(bp.name.length()) == '.') {
                return bp;
            }
        }
        return null;
    }

    public @Nullable PermissionInfo generatePermissionInfo(@NonNull String groupName, int flags) {
        if (groupName == null) {
            if (perm == null || perm.info.group == null) {
                return generatePermissionInfo(protectionLevel, flags);
            }
        } else {
            if (perm != null && groupName.equals(perm.info.group)) {
                return PackageParser.generatePermissionInfo(perm, flags);
            }
        }
        return null;
    }

    public @NonNull PermissionInfo generatePermissionInfo(int adjustedProtectionLevel, int flags) {
        PermissionInfo permissionInfo;
        if (perm != null) {
            final boolean protectionLevelChanged = protectionLevel != adjustedProtectionLevel;
            permissionInfo = PackageParser.generatePermissionInfo(perm, flags);
            if (protectionLevelChanged && permissionInfo == perm.info) {
                // if we return different protection level, don't use the cached info
                permissionInfo = new PermissionInfo(permissionInfo);
                permissionInfo.protectionLevel = adjustedProtectionLevel;
            }
            return permissionInfo;
        }
        permissionInfo = new PermissionInfo();
        permissionInfo.name = name;
        permissionInfo.packageName = sourcePackageName;
        permissionInfo.nonLocalizedLabel = name;
        permissionInfo.protectionLevel = protectionLevel;
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
        if (bp == null || bp.type != TYPE_BUILTIN) {
            bp = new BasePermission(name.intern(), sourcePackage,
                    dynamic ? TYPE_DYNAMIC : TYPE_NORMAL);
        }
        bp.protectionLevel = readInt(parser, null, "protection",
                PermissionInfo.PROTECTION_NORMAL);
        bp.protectionLevel = PermissionInfo.fixProtectionLevel(bp.protectionLevel);
        if (dynamic) {
            final PermissionInfo pi = new PermissionInfo();
            pi.packageName = sourcePackage.intern();
            pi.name = name.intern();
            pi.icon = readInt(parser, null, "icon", 0);
            pi.nonLocalizedLabel = parser.getAttributeValue(null, "label");
            pi.protectionLevel = bp.protectionLevel;
            bp.pendingPermissionInfo = pi;
        }
        out.put(bp.name, bp);
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
        if (sourcePackageName == null) {
            return;
        }
        serializer.startTag(null, TAG_ITEM);
        serializer.attribute(null, ATTR_NAME, name);
        serializer.attribute(null, ATTR_PACKAGE, sourcePackageName);
        if (protectionLevel != PermissionInfo.PROTECTION_NORMAL) {
            serializer.attribute(null, "protection", Integer.toString(protectionLevel));
        }
        if (type == BasePermission.TYPE_DYNAMIC) {
            final PermissionInfo pi = perm != null ? perm.info : pendingPermissionInfo;
            if (pi != null) {
                serializer.attribute(null, "type", "dynamic");
                if (pi.icon != 0) {
                    serializer.attribute(null, "icon", Integer.toString(pi.icon));
                }
                if (pi.nonLocalizedLabel != null) {
                    serializer.attribute(null, "label", pi.nonLocalizedLabel.toString());
                }
            }
        }
        serializer.endTag(null, TAG_ITEM);
    }

    private static boolean compareStrings(CharSequence s1, CharSequence s2) {
        if (s1 == null) {
            return s2 == null;
        }
        if (s2 == null) {
            return false;
        }
        if (s1.getClass() != s2.getClass()) {
            return false;
        }
        return s1.equals(s2);
    }

    private static boolean comparePermissionInfos(PermissionInfo pi1, PermissionInfo pi2) {
        if (pi1.icon != pi2.icon) return false;
        if (pi1.logo != pi2.logo) return false;
        if (pi1.protectionLevel != pi2.protectionLevel) return false;
        if (!compareStrings(pi1.name, pi2.name)) return false;
        if (!compareStrings(pi1.nonLocalizedLabel, pi2.nonLocalizedLabel)) return false;
        // We'll take care of setting this one.
        if (!compareStrings(pi1.packageName, pi2.packageName)) return false;
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
        if (packageName != null && !packageName.equals(sourcePackageName)) {
            return false;
        }
        if (permissionNames != null && !permissionNames.contains(name)) {
            return false;
        }
        if (!printedSomething) {
            if (dumpState.onTitlePrinted())
                pw.println();
            pw.println("Permissions:");
            printedSomething = true;
        }
        pw.print("  Permission ["); pw.print(name); pw.print("] (");
                pw.print(Integer.toHexString(System.identityHashCode(this)));
                pw.println("):");
        pw.print("    sourcePackage="); pw.println(sourcePackageName);
        pw.print("    uid="); pw.print(uid);
                pw.print(" gids="); pw.print(Arrays.toString(
                        computeGids(UserHandle.USER_SYSTEM)));
                pw.print(" type="); pw.print(type);
                pw.print(" prot=");
                pw.println(PermissionInfo.protectionToString(protectionLevel));
        if (perm != null) {
            pw.print("    perm="); pw.println(perm);
            if ((perm.info.flags & PermissionInfo.FLAG_INSTALLED) == 0
                    || (perm.info.flags & PermissionInfo.FLAG_REMOVED) != 0) {
                pw.print("    flags=0x"); pw.println(Integer.toHexString(perm.info.flags));
            }
        }
        if (sourcePackageSetting != null) {
            pw.print("    packageSetting="); pw.println(sourcePackageSetting);
        }
        if (READ_EXTERNAL_STORAGE.equals(name)) {
            pw.print("    enforced=");
            pw.println(readEnforced);
        }
        return true;
    }
}
