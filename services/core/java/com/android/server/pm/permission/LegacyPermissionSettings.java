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
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.XmlUtils;
import com.android.server.pm.DumpState;
import com.android.server.pm.PackageManagerService;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Legacy permission settings for migration.
 */
public class LegacyPermissionSettings {
    /**
     * All of the permissions known to the system. The mapping is from permission
     * name to permission object.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, LegacyPermission> mPermissions = new ArrayMap<>();

    /**
     * All permission trees known to the system. The mapping is from permission tree
     * name to permission object.
     */
    @GuardedBy("mLock")
    private final ArrayMap<String, LegacyPermission> mPermissionTrees = new ArrayMap<>();

    @NonNull
    private final Object mLock;

    public LegacyPermissionSettings(@NonNull Object lock) {
        mLock = lock;
    }

    @NonNull
    public List<LegacyPermission> getPermissions() {
        synchronized (mLock) {
            return new ArrayList<>(mPermissions.values());
        }
    }

    @NonNull
    public List<LegacyPermission> getPermissionTrees() {
        synchronized (mLock) {
            return new ArrayList<>(mPermissionTrees.values());
        }
    }

    public void replacePermissions(@NonNull List<LegacyPermission> permissions) {
        synchronized (mLock) {
            mPermissions.clear();
            final int permissionsSize = permissions.size();
            for (int i = 0; i < permissionsSize; i++) {
                final LegacyPermission permission = permissions.get(i);
                mPermissions.put(permission.getPermissionInfo().name, permission);
            }
        }
    }

    public void replacePermissionTrees(@NonNull List<LegacyPermission> permissionTrees) {
        synchronized (mLock) {
            mPermissionTrees.clear();
            final int permissionsSize = permissionTrees.size();
            for (int i = 0; i < permissionsSize; i++) {
                final LegacyPermission permissionTree = permissionTrees.get(i);
                mPermissionTrees.put(permissionTree.getPermissionInfo().name, permissionTree);
            }
        }
    }

    public void readPermissions(@NonNull TypedXmlPullParser parser) throws IOException,
            XmlPullParserException {
        synchronized (mLock) {
            readPermissions(mPermissions, parser);
        }
    }

    public void readPermissionTrees(@NonNull TypedXmlPullParser parser) throws IOException,
            XmlPullParserException {
        synchronized (mLock) {
            readPermissions(mPermissionTrees, parser);
        }
    }

    public static void readPermissions(@NonNull ArrayMap<String, LegacyPermission> out,
            @NonNull TypedXmlPullParser parser) throws IOException, XmlPullParserException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (!LegacyPermission.read(out, parser)) {
                PackageManagerService.reportSettingsProblem(Log.WARN,
                        "Unknown element reading permissions: " + parser.getName() + " at "
                                + parser.getPositionDescription());
            }
            XmlUtils.skipCurrentTag(parser);
        }
    }

    public void writePermissions(@NonNull TypedXmlSerializer serializer) throws IOException {
        synchronized (mLock) {
            for (LegacyPermission bp : mPermissions.values()) {
                bp.write(serializer);
            }
        }
    }

    public void writePermissionTrees(@NonNull TypedXmlSerializer serializer) throws IOException {
        synchronized (mLock) {
            for (LegacyPermission bp : mPermissionTrees.values()) {
                bp.write(serializer);
            }
        }
    }

    public static void dumpPermissions(@NonNull PrintWriter pw, @Nullable String packageName,
            @Nullable ArraySet<String> permissionNames, @NonNull List<LegacyPermission> permissions,
            @NonNull Map<String, Set<String>> appOpPermissionPackages,
            boolean externalStorageEnforced, @NonNull DumpState dumpState) {
        boolean printedSomething = false;
        final int permissionsSize = permissions.size();
        for (int i = 0; i < permissionsSize; i++) {
            final LegacyPermission permission = permissions.get(i);
            printedSomething = permission.dump(pw, packageName, permissionNames,
                    externalStorageEnforced, printedSomething, dumpState);
        }
        if (packageName == null && permissionNames == null) {
            boolean firstEntry = true;
            for (final Map.Entry<String, Set<String>> entry : appOpPermissionPackages.entrySet()) {
                if (firstEntry) {
                    firstEntry = false;
                    if (dumpState.onTitlePrinted()) {
                        pw.println();
                    }
                    pw.println("AppOp Permissions:");
                }
                pw.print("  AppOp Permission ");
                pw.print(entry.getKey());
                pw.println(":");
                for (final String appOpPackageName : entry.getValue()) {
                    pw.print("    ");
                    pw.println(appOpPackageName);
                }
            }
        }
    }
}
