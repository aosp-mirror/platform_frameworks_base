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
import android.content.Context;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.R;
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

    final boolean mPermissionReviewRequired;
    /**
     * All of the permissions known to the system. The mapping is from permission
     * name to permission object.
     */
    private final ArrayMap<String, BasePermission> mPermissions =
            new ArrayMap<String, BasePermission>();
    private final Object mLock;

    PermissionSettings(@NonNull Context context, @NonNull Object lock) {
        mPermissionReviewRequired =
                context.getResources().getBoolean(R.bool.config_permissionReviewRequired);
        mLock = lock;
    }

    public @Nullable BasePermission getPermission(@NonNull String permName) {
        synchronized (mLock) {
            return getPermissionLocked(permName);
        }
    }

    /**
     * Transfers ownership of permissions from one package to another.
     */
    public void transferPermissions(String origPackageName, String newPackageName,
            ArrayMap<String, BasePermission> permissionTrees) {
        synchronized (mLock) {
            for (int i=0; i<2; i++) {
                ArrayMap<String, BasePermission> permissions =
                        i == 0 ? permissionTrees : mPermissions;
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

    public void writePermissions(XmlSerializer serializer) throws IOException {
        for (BasePermission bp : mPermissions.values()) {
            bp.writeLPr(serializer);
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
        }
    }

    @Nullable BasePermission getPermissionLocked(@NonNull String permName) {
        return mPermissions.get(permName);
    }

    void putPermissionLocked(@NonNull String permName, @NonNull BasePermission permission) {
        mPermissions.put(permName, permission);
    }

    void removePermissionLocked(@NonNull String permName) {
        mPermissions.remove(permName);
    }

    Collection<BasePermission> getAllPermissionsLocked() {
        return mPermissions.values();
    }
}
