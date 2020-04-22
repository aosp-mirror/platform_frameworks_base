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
import android.content.pm.PackageManagerInternal;
import android.content.pm.PermissionInfo;
import android.content.pm.parsing.component.ParsedPermission;
import android.os.UserHandle;
import android.util.Log;
import android.util.Slog;

import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.PackageSettingBase;
import com.android.server.pm.parsing.PackageInfoUtils;
import com.android.server.pm.parsing.pkg.AndroidPackage;

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

    String sourcePackageName;

    int protectionLevel;

    ParsedPermission perm;

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
    public void setPermission(@Nullable ParsedPermission perm) {
        this.perm = perm;
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
            return perm.name.length() + perm.perm.calculateFootprint();
        }
        return 0;
    }

    public boolean isPermission(ParsedPermission perm) {
        if (this.perm == null) {
            return false;
        }
        return Objects.equals(this.perm.getPackageName(), perm.getPackageName())
                && Objects.equals(this.perm.getName(), perm.getName());
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
        return perm != null && (perm.getFlags() & PermissionInfo.FLAG_REMOVED) != 0;
    }

    public boolean isSoftRestricted() {
        return perm != null && (perm.getFlags() & PermissionInfo.FLAG_SOFT_RESTRICTED) != 0;
    }

    public boolean isHardRestricted() {
        return perm != null && (perm.getFlags() & PermissionInfo.FLAG_HARD_RESTRICTED) != 0;
    }

    public boolean isHardOrSoftRestricted() {
        return perm != null && (perm.getFlags() & (PermissionInfo.FLAG_HARD_RESTRICTED
                | PermissionInfo.FLAG_SOFT_RESTRICTED)) != 0;
    }

    public boolean isImmutablyRestricted() {
        return perm != null && (perm.getFlags() & PermissionInfo.FLAG_IMMUTABLY_RESTRICTED) != 0;
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
    public boolean isCompanion() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_COMPANION) != 0;
    }

    public boolean isRetailDemo() {
        return (protectionLevel & PermissionInfo.PROTECTION_FLAG_RETAIL_DEMO) != 0;
    }

    public void transfer(@NonNull String origPackageName, @NonNull String newPackageName) {
        if (!origPackageName.equals(sourcePackageName)) {
            return;
        }
        sourcePackageName = newPackageName;
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
                    || !Objects.equals(perm.getPackageName(), tree.perm.getPackageName())
                    || !comparePermissionInfos(perm, info));
        this.protectionLevel = protectionLevel;
        info = new PermissionInfo(info);
        info.protectionLevel = protectionLevel;
        perm = new ParsedPermission(tree.perm);
        uid = tree.uid;
        return changed;
    }

    public void updateDynamicPermission(Collection<BasePermission> permissionTrees) {
        if (PackageManagerService.DEBUG_SETTINGS) Log.v(TAG, "Dynamic permission: name="
                + getName() + " pkg=" + getSourcePackageName()
                + " info=" + pendingPermissionInfo);
        if (pendingPermissionInfo != null) {
            final BasePermission tree = findPermissionTree(permissionTrees, name);
            if (tree != null && tree.perm != null) {
                perm = new ParsedPermission(tree.perm, pendingPermissionInfo,
                        tree.perm.getPackageName(), name);
                uid = tree.uid;
            }
        }
    }

    static BasePermission createOrUpdate(PackageManagerInternal packageManagerInternal,
            @Nullable BasePermission bp, @NonNull ParsedPermission p,
            @NonNull AndroidPackage pkg, Collection<BasePermission> permissionTrees,
            boolean chatty) {
        final PackageSettingBase pkgSetting =
                (PackageSettingBase) packageManagerInternal.getPackageSetting(pkg.getPackageName());
        // Allow system apps to redefine non-system permissions
        if (bp != null && !Objects.equals(bp.sourcePackageName, p.getPackageName())) {
            final boolean currentOwnerIsSystem;
            if (bp.perm == null) {
                currentOwnerIsSystem = false;
            } else {
                AndroidPackage currentPackage = packageManagerInternal.getPackage(
                        bp.perm.getPackageName());
                if (currentPackage == null) {
                    currentOwnerIsSystem = false;
                } else {
                    currentOwnerIsSystem = currentPackage.isSystem();
                }
            }

            if (pkg.isSystem()) {
                if (bp.type == BasePermission.TYPE_BUILTIN && bp.perm == null) {
                    // It's a built-in permission and no owner, take ownership now
                    p.setFlags(p.getFlags() | PermissionInfo.FLAG_INSTALLED);
                    bp.perm = p;
                    bp.uid = pkg.getUid();
                    bp.sourcePackageName = p.getPackageName();
                } else if (!currentOwnerIsSystem) {
                    String msg = "New decl " + pkg + " of permission  "
                            + p.getName() + " is system; overriding " + bp.sourcePackageName;
                    PackageManagerService.reportSettingsProblem(Log.WARN, msg);
                    bp = null;
                }
            }
        }
        if (bp == null) {
            bp = new BasePermission(p.getName(), p.getPackageName(), TYPE_NORMAL);
        }
        StringBuilder r = null;
        if (bp.perm == null) {
            if (bp.sourcePackageName == null
                    || bp.sourcePackageName.equals(p.getPackageName())) {
                final BasePermission tree = findPermissionTree(permissionTrees, p.getName());
                if (tree == null
                        || tree.sourcePackageName.equals(p.getPackageName())) {
                    p.setFlags(p.getFlags() | PermissionInfo.FLAG_INSTALLED);
                    bp.perm = p;
                    bp.uid = pkg.getUid();
                    bp.sourcePackageName = p.getPackageName();
                    if (chatty) {
                        if (r == null) {
                            r = new StringBuilder(256);
                        } else {
                            r.append(' ');
                        }
                        r.append(p.getName());
                    }
                } else {
                    Slog.w(TAG, "Permission " + p.getName() + " from package "
                            + p.getPackageName() + " ignored: base tree "
                            + tree.name + " is from package "
                            + tree.sourcePackageName);
                }
            } else {
                Slog.w(TAG, "Permission " + p.getName() + " from package "
                        + p.getPackageName() + " ignored: original from "
                        + bp.sourcePackageName);
            }
        } else if (chatty) {
            if (r == null) {
                r = new StringBuilder(256);
            } else {
                r.append(' ');
            }
            r.append("DUP:");
            r.append(p.getName());
        }
        if (bp.perm != null && Objects.equals(bp.perm.getPackageName(), p.getPackageName())
                && Objects.equals(bp.perm.getName(), p.getName())) {
            bp.protectionLevel = p.getProtectionLevel();
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

    public void enforceDeclaredUsedAndRuntimeOrDevelopment(AndroidPackage pkg,
            PackageSetting pkgSetting) {
        final PermissionsState permsState = pkgSetting.getPermissionsState();
        int index = pkg.getRequestedPermissions().indexOf(name);
        if (!permsState.hasRequestedPermission(name) && index == -1) {
            throw new SecurityException("Package " + pkg.getPackageName()
                    + " has not requested permission " + name);
        }
        if (!isRuntime() && !isDevelopment()) {
            throw new SecurityException("Permission " + name + " requested by "
                    + pkg.getPackageName() + " is not a changeable permission type");
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
            if (perm == null || perm.getGroup() == null) {
                return generatePermissionInfo(protectionLevel, flags);
            }
        } else {
            if (perm != null && groupName.equals(perm.getGroup())) {
                return PackageInfoUtils.generatePermissionInfo(perm, flags);
            }
        }
        return null;
    }

    public @NonNull PermissionInfo generatePermissionInfo(int adjustedProtectionLevel, int flags) {
        PermissionInfo permissionInfo;
        if (perm != null) {
            final boolean protectionLevelChanged = protectionLevel != adjustedProtectionLevel;
            permissionInfo = PackageInfoUtils.generatePermissionInfo(perm, flags);
            if (protectionLevelChanged) {
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
            if (perm != null || pendingPermissionInfo != null) {
                serializer.attribute(null, "type", "dynamic");
                int icon = perm != null ? perm.getIcon() : pendingPermissionInfo.icon;
                CharSequence nonLocalizedLabel = perm != null
                        ? perm.getNonLocalizedLabel()
                        : pendingPermissionInfo.nonLocalizedLabel;

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

    private static boolean comparePermissionInfos(ParsedPermission pi1, PermissionInfo pi2) {
        if (pi1.getIcon() != pi2.icon) return false;
        if (pi1.getLogo() != pi2.logo) return false;
        if (pi1.getProtectionLevel() != pi2.protectionLevel) return false;
        if (!compareStrings(pi1.getName(), pi2.name)) return false;
        if (!compareStrings(pi1.getNonLocalizedLabel(), pi2.nonLocalizedLabel)) return false;
        // We'll take care of setting this one.
        if (!compareStrings(pi1.getPackageName(), pi2.packageName)) return false;
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
            if ((perm.getFlags() & PermissionInfo.FLAG_INSTALLED) == 0
                    || (perm.getFlags() & PermissionInfo.FLAG_REMOVED) != 0) {
                pw.print("    flags=0x"); pw.println(Integer.toHexString(perm.getFlags()));
            }
        }
        if (READ_EXTERNAL_STORAGE.equals(name)) {
            pw.print("    enforced=");
            pw.println(readEnforced);
        }
        return true;
    }
}
