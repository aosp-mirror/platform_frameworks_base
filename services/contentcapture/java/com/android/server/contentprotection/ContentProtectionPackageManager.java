/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.contentprotection;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.util.Slog;

import java.util.Arrays;

/**
 * Basic package manager for content protection using content capture.
 *
 * @hide
 */
final class ContentProtectionPackageManager {
    private static final String TAG = ContentProtectionPackageManager.class.getSimpleName();

    private static final PackageInfoFlags PACKAGE_INFO_FLAGS =
            PackageInfoFlags.of(PackageManager.GET_PERMISSIONS);

    @NonNull private final PackageManager mPackageManager;

    ContentProtectionPackageManager(@NonNull Context context) {
        mPackageManager = context.getPackageManager();
    }

    @Nullable
    public PackageInfo getPackageInfo(@NonNull String packageName) {
        try {
            return mPackageManager.getPackageInfo(packageName, PACKAGE_INFO_FLAGS);
        } catch (NameNotFoundException ex) {
            Slog.w(TAG, "Package info not found: ", ex);
            return null;
        }
    }

    public boolean isSystemApp(@NonNull PackageInfo packageInfo) {
        return packageInfo.applicationInfo != null && isSystemApp(packageInfo.applicationInfo);
    }

    private boolean isSystemApp(@NonNull ApplicationInfo applicationInfo) {
        return (applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    public boolean isUpdatedSystemApp(@NonNull PackageInfo packageInfo) {
        return packageInfo.applicationInfo != null
                && isUpdatedSystemApp(packageInfo.applicationInfo);
    }

    private boolean isUpdatedSystemApp(@NonNull ApplicationInfo applicationInfo) {
        return (applicationInfo.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
    }

    public boolean hasRequestedInternetPermissions(@NonNull PackageInfo packageInfo) {
        return packageInfo.requestedPermissions != null
                && Arrays.asList(packageInfo.requestedPermissions)
                        .contains(Manifest.permission.INTERNET);
    }
}
