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

package com.android.internal.content.om;

import static com.android.internal.content.om.OverlayConfig.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageParser;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.util.Collection;

/**
 * This class scans a directory containing overlay APKs and extracts information from the overlay
 * manifests by parsing the overlay manifests.
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class OverlayScanner {

    /** Represents information parsed from the manifest of an overlay. */
    public static class ParsedOverlayInfo {
        public final String packageName;
        public final String targetPackageName;
        public final int targetSdkVersion;
        public final boolean isStatic;
        public final int priority;
        public final File path;

        public ParsedOverlayInfo(String packageName, String targetPackageName,
                int targetSdkVersion, boolean isStatic, int priority, File path) {
            this.packageName = packageName;
            this.targetPackageName = targetPackageName;
            this.targetSdkVersion = targetSdkVersion;
            this.isStatic = isStatic;
            this.priority = priority;
            this.path = path;
        }

        @Override
        public String toString() {
            return getClass().getSimpleName() + String.format("{packageName=%s"
                            + ", targetPackageName=%s, targetSdkVersion=%s, isStatic=%s"
                            + ", priority=%s, path=%s}",
                    packageName, targetPackageName, targetSdkVersion, isStatic, priority, path);
        }
    }

    /**
     * A map of overlay package name to the parsed manifest information of the latest version of
     * the overlay.
     */
    private final ArrayMap<String, ParsedOverlayInfo> mParsedOverlayInfos = new ArrayMap<>();

    /** Retrieves information parsed from the overlay with the package name. */
    @Nullable
    public final ParsedOverlayInfo getParsedInfo(String packageName) {
        return mParsedOverlayInfos.get(packageName);
    }

    /** Retrieves all of the scanned overlays. */
    @NonNull
    final Collection<ParsedOverlayInfo> getAllParsedInfos() {
        return mParsedOverlayInfos.values();
    }

    /**
     * Recursively searches the directory for overlay APKs. If an overlay is found with the same
     * package name as a previously scanned overlay, the info of the new overlay will replace the
     * info of the previously scanned overlay.
     */
    public void scanDir(File partitionOverlayDir) {
        if (!partitionOverlayDir.exists() || !partitionOverlayDir.isDirectory()) {
            return;
        }

        if (!partitionOverlayDir.canRead()) {
            Log.w(TAG, "Directory " + partitionOverlayDir + " cannot be read");
            return;
        }

        final File[] files = partitionOverlayDir.listFiles();
        if (files == null) {
            return;
        }

        for (int i = 0; i < files.length; i++) {
            final File f = files[i];
            if (f.isDirectory()) {
                scanDir(f);
            }

            if (!f.isFile() || !f.getPath().endsWith(".apk")) {
                continue;
            }

            final ParsedOverlayInfo info = parseOverlayManifest(f);
            if (info == null) {
                continue;
            }

            mParsedOverlayInfos.put(info.packageName, info);
        }
    }

    /** Extracts information about the overlay from its manifest. */
    @VisibleForTesting
    public ParsedOverlayInfo parseOverlayManifest(File overlayApk) {
        try {
            final PackageParser.ApkLite apkLite = PackageParser.parseApkLite(overlayApk, 0);
            return apkLite.targetPackageName == null ? null :
                    new ParsedOverlayInfo(apkLite.packageName, apkLite.targetPackageName,
                            apkLite.targetSdkVersion, apkLite.overlayIsStatic,
                            apkLite.overlayPriority, new File(apkLite.codePath));
        } catch (PackageParser.PackageParserException e) {
            Log.w(TAG, "Got exception loading overlay.", e);
            return null;
        }
    }
}
