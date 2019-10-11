/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.packageinstaller.wear;

import android.annotation.TargetApi;
import android.app.ActivityManager;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.List;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class WearPackageIconProvider extends ContentProvider {
    private static final String TAG = "WearPackageIconProvider";
    public static final String AUTHORITY = "com.google.android.packageinstaller.wear.provider";

    private static final String REQUIRED_PERMISSION =
            "com.google.android.permission.INSTALL_WEARABLE_PACKAGES";

    /** MIME types. */
    public static final String ICON_TYPE = "vnd.android.cursor.item/cw_package_icon";

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        throw new UnsupportedOperationException("Query is not supported.");
    }

    @Override
    public String getType(Uri uri) {
        if (uri == null) {
            throw new IllegalArgumentException("URI passed in is null.");
        }

        if (AUTHORITY.equals(uri.getEncodedAuthority())) {
            return ICON_TYPE;
        }
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Insert is not supported.");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        if (uri == null) {
            throw new IllegalArgumentException("URI passed in is null.");
        }

        enforcePermissions(uri);

        if (ICON_TYPE.equals(getType(uri))) {
            final File file = WearPackageUtil.getIconFile(
                    this.getContext().getApplicationContext(), getPackageNameFromUri(uri));
            if (file != null) {
                file.delete();
            }
        }

        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Update is not supported.");
    }

    @Override
    public ParcelFileDescriptor openFile(
            Uri uri, @SuppressWarnings("unused") String mode) throws FileNotFoundException {
        if (uri == null) {
            throw new IllegalArgumentException("URI passed in is null.");
        }

        enforcePermissions(uri);

        if (ICON_TYPE.equals(getType(uri))) {
            final File file = WearPackageUtil.getIconFile(
                    this.getContext().getApplicationContext(), getPackageNameFromUri(uri));
            if (file != null) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            }
        }
        return null;
    }

    public static Uri getUriForPackage(final String packageName) {
        return Uri.parse("content://" + AUTHORITY + "/icons/" + packageName + ".icon");
    }

    private String getPackageNameFromUri(Uri uri) {
        if (uri == null) {
            return null;
        }
        List<String> pathSegments = uri.getPathSegments();
        String packageName = pathSegments.get(pathSegments.size() - 1);

        if (packageName.endsWith(".icon")) {
            packageName = packageName.substring(0, packageName.lastIndexOf("."));
        }
        return packageName;
    }

    /**
     * Make sure the calling app is either a system app or the same app or has the right permission.
     * @throws SecurityException if the caller has insufficient permissions.
     */
    @TargetApi(Build.VERSION_CODES.BASE_1_1)
    private void enforcePermissions(Uri uri) {
        // Redo some of the permission check in {@link ContentProvider}. Just add an extra check to
        // allow System process to access this provider.
        Context context = getContext();
        final int pid = Binder.getCallingPid();
        final int uid = Binder.getCallingUid();
        final int myUid = android.os.Process.myUid();

        if (uid == myUid || isSystemApp(context, pid)) {
            return;
        }

        if (context.checkPermission(REQUIRED_PERMISSION, pid, uid) == PERMISSION_GRANTED) {
            return;
        }

        // last chance, check against any uri grants
        if (context.checkUriPermission(uri, pid, uid, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                == PERMISSION_GRANTED) {
            return;
        }

        throw new SecurityException("Permission Denial: reading "
                + getClass().getName() + " uri " + uri + " from pid=" + pid
                + ", uid=" + uid);
    }

    /**
     * From the pid of the calling process, figure out whether this is a system app or not. We do
     * this by checking the application information corresponding to the pid and then checking if
     * FLAG_SYSTEM is set.
     */
    @TargetApi(Build.VERSION_CODES.CUPCAKE)
    private boolean isSystemApp(Context context, int pid) {
        // Get the Activity Manager Object
        ActivityManager aManager =
                (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        // Get the list of running Applications
        List<ActivityManager.RunningAppProcessInfo> rapInfoList =
                aManager.getRunningAppProcesses();
        for (ActivityManager.RunningAppProcessInfo rapInfo : rapInfoList) {
            if (rapInfo.pid == pid) {
                try {
                    PackageInfo pkgInfo = context.getPackageManager().getPackageInfo(
                            rapInfo.pkgList[0], 0);
                    if (pkgInfo != null && pkgInfo.applicationInfo != null &&
                            (pkgInfo.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                        Log.d(TAG, pid + " is a system app.");
                        return true;
                    }
                } catch (PackageManager.NameNotFoundException e) {
                    Log.e(TAG, "Could not find package information.", e);
                    return false;
                }
            }
        }
        return false;
    }
}
