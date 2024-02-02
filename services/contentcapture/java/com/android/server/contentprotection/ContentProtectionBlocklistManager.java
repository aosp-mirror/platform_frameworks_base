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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageInfo;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Manages whether the content protection is enabled for an app using a blocklist.
 *
 * @hide
 */
public class ContentProtectionBlocklistManager {

    private static final String TAG = "ContentProtectionBlocklistManager";

    private static final String PACKAGE_NAME_BLOCKLIST_FILENAME =
            "/product/etc/res/raw/content_protection/package_name_blocklist.txt";

    @NonNull private final ContentProtectionPackageManager mContentProtectionPackageManager;

    @Nullable private Set<String> mPackageNameBlocklist;

    public ContentProtectionBlocklistManager(
            @NonNull ContentProtectionPackageManager contentProtectionPackageManager) {
        mContentProtectionPackageManager = contentProtectionPackageManager;
    }

    public boolean isAllowed(@NonNull String packageName) {
        if (mPackageNameBlocklist == null) {
            // List not loaded or failed to load, don't run on anything
            return false;
        }
        if (mPackageNameBlocklist.contains(packageName)) {
            return false;
        }
        PackageInfo packageInfo = mContentProtectionPackageManager.getPackageInfo(packageName);
        if (packageInfo == null) {
            return false;
        }
        if (!mContentProtectionPackageManager.hasRequestedInternetPermissions(packageInfo)) {
            return false;
        }
        if (mContentProtectionPackageManager.isSystemApp(packageInfo)) {
            return false;
        }
        if (mContentProtectionPackageManager.isUpdatedSystemApp(packageInfo)) {
            return false;
        }
        return true;
    }

    public void updateBlocklist(int blocklistSize) {
        Slog.i(TAG, "Blocklist size updating to: " + blocklistSize);
        mPackageNameBlocklist = readPackageNameBlocklist(blocklistSize);
    }

    @Nullable
    private Set<String> readPackageNameBlocklist(int blocklistSize) {
        if (blocklistSize <= 0) {
            // Explicitly requested an empty blocklist
            return Collections.emptySet();
        }
        List<String> lines = readLinesFromRawFile(PACKAGE_NAME_BLOCKLIST_FILENAME);
        if (lines == null) {
            return null;
        }
        return lines.stream().limit(blocklistSize).collect(Collectors.toSet());
    }

    @VisibleForTesting
    @Nullable
    protected List<String> readLinesFromRawFile(@NonNull String filename) {
        try (FileReader fileReader = new FileReader(filename);
                BufferedReader bufferedReader = new BufferedReader(fileReader)) {
            return bufferedReader
                    .lines()
                    .map(line -> line.trim())
                    .filter(line -> !line.isBlank())
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            Slog.e(TAG, "Failed to read: " + filename, ex);
            return null;
        }
    }
}
