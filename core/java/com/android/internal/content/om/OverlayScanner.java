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

import static android.content.pm.parsing.ParsingPackageUtils.PARSE_IGNORE_OVERLAY_REQUIRED_SYSTEM_PROPERTY;
import static android.content.pm.parsing.ParsingPackageUtils.checkRequiredSystemProperties;

import static com.android.internal.content.om.OverlayConfig.TAG;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.parsing.ApkLite;
import android.content.pm.parsing.ApkLiteParseUtils;
import android.content.pm.parsing.result.ParseResult;
import android.content.pm.parsing.result.ParseTypeImpl;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import android.util.Pair;

import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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

    /**
     * A list of pair<packageName, apkFile> which is excluded from the system based on the
     * system property condition.
     *
     * @see #isExcludedOverlayPackage(String, OverlayConfigParser.OverlayPartition)
     */
    private final List<Pair<String, File>> mExcludedOverlayPackages = new ArrayList<>();

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
     * Returns {@code true} if the given package name on the given overlay partition is an
     * excluded overlay package.
     * <p>
     * An excluded overlay package declares overlay attributes of required system property in its
     * manifest that do not match the corresponding values on the device.
     */
    final boolean isExcludedOverlayPackage(@NonNull String packageName,
            @NonNull OverlayConfigParser.OverlayPartition overlayPartition) {
        for (int i = 0; i < mExcludedOverlayPackages.size(); i++) {
            final Pair<String, File> pair = mExcludedOverlayPackages.get(i);
            if (pair.first.equals(packageName)
                    && overlayPartition.containsOverlay(pair.second)) {
                return true;
            }
        }
        return false;
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

            final ParsedOverlayInfo info = parseOverlayManifest(f, mExcludedOverlayPackages);
            if (info == null) {
                continue;
            }

            mParsedOverlayInfos.put(info.packageName, info);
        }
    }

    /**
     * Extracts information about the overlay from its manifest. Adds the package name and apk file
     * into the {@code outExcludedOverlayPackages} if the apk is excluded from the system based
     * on the system property condition.
     */
    @VisibleForTesting
    public ParsedOverlayInfo parseOverlayManifest(File overlayApk,
            List<Pair<String, File>> outExcludedOverlayPackages) {
        final ParseTypeImpl input = ParseTypeImpl.forParsingWithoutPlatformCompat();
        final ParseResult<ApkLite> ret = ApkLiteParseUtils.parseApkLite(input.reset(),
                overlayApk, PARSE_IGNORE_OVERLAY_REQUIRED_SYSTEM_PROPERTY);
        if (ret.isError()) {
            Log.w(TAG, "Got exception loading overlay.", ret.getException());
            return null;
        }
        final ApkLite apkLite = ret.getResult();
        if (apkLite.getTargetPackageName() == null) {
            // Not an overlay package
            return null;
        }
        final String propName = apkLite.getRequiredSystemPropertyName();
        final String propValue = apkLite.getRequiredSystemPropertyValue();
        if ((!TextUtils.isEmpty(propName) || !TextUtils.isEmpty(propValue))
                && !checkRequiredSystemProperties(propName, propValue)) {
            // The overlay package should be excluded. Adds it into the outExcludedOverlayPackages
            // for overlay configuration parser to ignore it.
            outExcludedOverlayPackages.add(Pair.create(apkLite.getPackageName(), overlayApk));
            return null;
        }
        return new ParsedOverlayInfo(apkLite.getPackageName(), apkLite.getTargetPackageName(),
                apkLite.getTargetSdkVersion(), apkLite.isOverlayIsStatic(),
                apkLite.getOverlayPriority(), new File(apkLite.getPath()));
    }
}
