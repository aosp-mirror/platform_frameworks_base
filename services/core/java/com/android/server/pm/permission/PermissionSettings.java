/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.content.pm.parsing.component.ParsedPermissionGroup;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.XmlUtils;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collection;

/**
 * Permissions and other related data. This class is not meant for
 * direct access outside of the permission package with the sole exception
 * of package settings. Instead, it should be reference either from the
 * permission manager or package settings.
 */
public class PermissionSettings {

    /**
     * All of the permissions known to the system. The mapping is from permission
     * name to permission object.
     */
    @GuardedBy("mLock")
    final ArrayMap<String, BasePermission> mPermissions =
            new ArrayMap<String, BasePermission>();

    /**
     * All permission trees known to the system. The mapping is from permission tree
     * name to permission object.
     */
    @GuardedBy("mLock")
    final ArrayMap<String, BasePermission> mPermissionTrees =
            new ArrayMap<String, BasePermission>();

    /**
     * All permisson groups know to the system. The mapping is from permission group
     * name to permission group object.
     */
    @GuardedBy("mLock")
    final ArrayMap<String, ParsedPermissionGroup> mPermissionGroups =
            new ArrayMap<>();

    /**
     * Set of packages that request a particular app op. The mapping is from permission
     * name to package names.
     */
    @GuardedBy("mLock")
    final ArrayMap<String, ArraySet<String>> mAppOpPermissionPackages = new ArrayMap<>();

    private final Object mLock;

    PermissionSettings(@NonNull Object lock) {
        mLock = lock;
    }

    public @Nullable BasePermission getPermission(@NonNull String permName) {
        synchronized (mLock) {
            return getPermissionLocked(permName);
        }
    }

    public void addAppOpPackage(String permName, String packageName) {
        ArraySet<String> pkgs = mAppOpPermissionPackages.get(permName);
        if (pkgs == null) {
            pkgs = new ArraySet<>();
            mAppOpPermissionPackages.put(permName, pkgs);
        }
        pkgs.add(packageName);
    }

    /**
     * Transfers ownership of permissions from one package to another.
     */
    public void transferPermissions(String origPackageName, String newPackageName) {
        synchronized (mLock) {
            for (int i=0; i<2; i++) {
                ArrayMap<String, BasePermission> permissions =
                        i == 0 ? mPermissionTrees : mPermissions;
                for (BasePermission bp : permissions.values()) {
                    bp.transfer(origPackageName, newPackageName);
                }
            }
        }
    }

    public boolean canPropagatePermissionToInstantApp(String permName) {
        synchronized (mLock) {
            final BasePermission bp = mPermissions.get(permName);
            return (bp != null && (bp.isRuntime() || bp.isDevelopment()) && bp.isInstant());
        }
    }

    public void readPermissions(XmlPullParser parser) throws IOException, XmlPullParserException {
        synchronized (mLock) {
            readPermissions(mPermissions, parser);
        }
    }

    public void readPermissionTrees(XmlPullParser parser)
            throws IOException, XmlPullParserException {
        synchronized (mLock) {
            readPermissions(mPermissionTrees, parser);
        }
    }

    public void writePermissions(XmlSerializer serializer) throws IOException {
        synchronized (mLock) {
            for (BasePermission bp : mPermissions.values()) {
                bp.writeLPr(serializer);
            }
        }
    }

    public void writePermissionTrees(XmlSerializer serializer) throws IOException {
        synchronized (mLock) {
            for (BasePermission bp : mPermissionTrees.values()) {
                bp.writeLPr(serializer);
            }
        }
    }

    public static void readPermissions(ArrayMap<String, BasePermission> out, XmlPullParser parser)
            throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (!BasePermission.readLPw(out, parser)) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element reading permissions: " + parser.getName() + " at "
                                + parser.getPositionDescription());
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    public void dumpPermissions(PrintWriter pw, String packageName,
            ArraySet<String> permissionNames, boolean externalStorageEnforced,
            DumpState dumpState) {
        synchronized (mLock) {
            boolean printedSomething = false;
            for (BasePermission bp : mPermissions.values()) {
                printedSomething = bp.dumpPermissionsLPr(pw, packageName, permissionNames,
                        externalStorageEnforced, printedSomething, dumpState);
            }
            if (packageName == null && permissionNames == null) {
                for (int iperm = 0; iperm<mAppOpPermissionPackages.size(); iperm++) {
                    if (iperm == 0) {
                        if (dumpState.onTitlePrinted())
                            pw.println();
                        pw.println("AppOp Permissions:");
                    }
                    pw.print("  AppOp Permission ");
                    pw.print(mAppOpPermissionPackages.keyAt(iperm));
                    pw.println(":");
                    ArraySet<String> pkgs = mAppOpPermissionPackages.valueAt(iperm);
                    for (int ipkg=0; ipkg<pkgs.size(); ipkg++) {
                        pw.print("    "); pw.println(pkgs.valueAt(ipkg));
                    }
                }
            }
        }
    }

    @GuardedBy("mLock")
    @Nullable BasePermission getPermissionLocked(@NonNull String permName) {
        return mPermissions.get(permName);
    }

    @GuardedBy("mLock")
    @Nullable BasePermission getPermissionTreeLocked(@NonNull String permName) {
        return mPermissionTrees.get(permName);
    }

    @GuardedBy("mLock")
    void putPermissionLocked(@NonNull String permName, @NonNull BasePermission permission) {
        mPermissions.put(permName, permission);
    }

    @GuardedBy("mLock")
    void putPermissionTreeLocked(@NonNull String permName, @NonNull BasePermission permission) {
        mPermissionTrees.put(permName, permission);
    }

    @GuardedBy("mLock")
    void removePermissionLocked(@NonNull String permName) {
        mPermissions.remove(permName);
    }

    @GuardedBy("mLock")
    void removePermissionTreeLocked(@NonNull String permName) {
        mPermissionTrees.remove(permName);
    }

    @GuardedBy("mLock")
    @NonNull Collection<BasePermission> getAllPermissionsLocked() {
        return mPermissions.values();
    }

    @GuardedBy("mLock")
    @NonNull Collection<BasePermission> getAllPermissionTreesLocked() {
        return mPermissionTrees.values();
    }

    /**
     * Returns the permission tree for the given permission.
     * @throws SecurityException If the calling UID is not allowed to add permissions to the
     * found permission tree.
     */
    @Nullable BasePermission enforcePermissionTree(@NonNull String permName, int callingUid) {
        synchronized (mLock) {
            return BasePermission.enforcePermissionTree(
                    mPermissionTrees.values(), permName, callingUid);
        }
    }

    /**
     * Check whether a permission is runtime.
     *
     * @see BasePermission#isRuntime()
     */
    public boolean isPermissionRuntime(@NonNull String permName) {
        synchronized (mLock) {
            final BasePermission bp = mPermissions.get(permName);
            return (bp != null && bp.isRuntime());
        }
    }

    public boolean isPermissionInstant(String permName) {
        synchronized (mLock) {
            final BasePermission bp = mPermissions.get(permName);
            return (bp != null && bp.isInstant());
        }
    }

    boolean isPermissionAppOp(String permName) {
        synchronized (mLock) {
            final BasePermission bp = mPermissions.get(permName);
            return (bp != null && bp.isAppOp());
        }
    }

}
