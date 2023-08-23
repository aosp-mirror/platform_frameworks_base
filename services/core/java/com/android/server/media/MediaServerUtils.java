/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.media;

import android.annotation.NonNull;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.ResolveInfo;
import android.os.Binder;
import android.os.Process;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

/** Util class for media server. */
/* package */ class MediaServerUtils {

    /**
     * Returns whether the provided {@link ComponentName} and {@code action} resolve to a valid
     * activity for the user defined by {@code userHandle}.
     */
    public static boolean isValidActivityComponentName(
            @NonNull Context context,
            @NonNull ComponentName componentName,
            @NonNull String action,
            @NonNull UserHandle userHandle) {
        Intent intent = new Intent(action);
        intent.setComponent(componentName);
        List<ResolveInfo> resolveInfos =
                context.getPackageManager()
                        .queryIntentActivitiesAsUser(intent, /* flags= */ 0, userHandle);
        return !resolveInfos.isEmpty();
    }

    /**
     * Throws if the given {@code packageName} does not correspond to the given {@code uid}.
     *
     * <p>This method trusts calls from {@link Process#ROOT_UID} and {@link Process#SHELL_UID}.
     *
     * @param packageName A package name to verify (usually sent over binder by an app).
     * @param uid The calling uid, obtained via {@link Binder#getCallingUid()}.
     * @throws IllegalArgumentException If the given {@code packageName} does not correspond to the
     *     given {@code uid}, and {@code uid} is not the root uid, or the shell uid.
     */
    public static void enforcePackageName(
            @NonNull Context context, @NonNull String packageName, int uid) {
        if (uid == Process.ROOT_UID || uid == Process.SHELL_UID) {
            return;
        }
        if (TextUtils.isEmpty(packageName)) {
            throw new IllegalArgumentException("packageName may not be empty");
        }
        final PackageManagerInternal packageManagerInternal =
                LocalServices.getService(PackageManagerInternal.class);
        final int actualUid =
                packageManagerInternal.getPackageUid(
                        packageName, 0 /* flags */, UserHandle.getUserId(uid));
        if (!UserHandle.isSameApp(uid, actualUid)) {
            String[] uidPackages = context.getPackageManager().getPackagesForUid(uid);
            throw new IllegalArgumentException(
                    "packageName does not belong to the calling uid; "
                            + "pkg="
                            + packageName
                            + ", uid="
                            + uid
                            + " ("
                            + Arrays.toString(uidPackages)
                            + ")");
        }
    }

    /**
     * Verify that caller holds {@link android.Manifest.permission#DUMP}.
     */
    public static boolean checkDumpPermission(Context context, String tag, PrintWriter pw) {
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.DUMP)
                != PackageManager.PERMISSION_GRANTED) {
            pw.println("Permission Denial: can't dump " + tag + " from from pid="
                    + Binder.getCallingPid() + ", uid=" + Binder.getCallingUid()
                    + " due to missing android.permission.DUMP permission");
            return false;
        } else {
            return true;
        }
    }
}
