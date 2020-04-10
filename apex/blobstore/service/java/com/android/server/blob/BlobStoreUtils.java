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

package com.android.server.blob;

import static com.android.server.blob.BlobStoreConfig.TAG;

import android.annotation.IdRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.text.format.TimeMigrationUtils;
import android.util.Slog;

class BlobStoreUtils {
    private static final String DESC_RES_TYPE_STRING = "string";

    @Nullable
    static Resources getPackageResources(@NonNull Context context,
            @NonNull String packageName, int userId) {
        try {
            return context.getPackageManager()
                    .getResourcesForApplicationAsUser(packageName, userId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.d(TAG, "Unknown package in user " + userId + ": "
                    + packageName, e);
            return null;
        }
    }

    @IdRes
    static int getDescriptionResourceId(@NonNull Resources resources,
            @NonNull String resourceEntryName, @NonNull String packageName) {
        return resources.getIdentifier(resourceEntryName, DESC_RES_TYPE_STRING, packageName);
    }

    @IdRes
    static int getDescriptionResourceId(@NonNull Context context,
            @NonNull String resourceEntryName, @NonNull String packageName, int userId) {
        final Resources resources = getPackageResources(context, packageName, userId);
        return resources == null
                ? Resources.ID_NULL
                : getDescriptionResourceId(resources, resourceEntryName, packageName);
    }

    @NonNull
    static String formatTime(long timeMs) {
        return TimeMigrationUtils.formatMillisWithFixedFormat(timeMs);
    }
}
