/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.content.pm.parsing;

import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import android.compat.annotation.UnsupportedAppUsage;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.VerifierInfo;
import android.content.res.ApkAssets;
import android.content.res.XmlResourceParser;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.R;
import com.android.internal.util.ArrayUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** @hide */
public class ApkLiteParseUtils {

    private static final String TAG = ParsingPackageUtils.TAG;

    // TODO(b/135203078): Consolidate constants
    private static final int DEFAULT_MIN_SDK_VERSION = 1;
    private static final int DEFAULT_TARGET_SDK_VERSION = 0;

    private static final int PARSE_DEFAULT_INSTALL_LOCATION =
            PackageInfo.INSTALL_LOCATION_UNSPECIFIED;

    /**
     * Parse only lightweight details about the package at the given location.
     * Automatically detects if the package is a monolithic style (single APK
     * file) or cluster style (directory of APKs).
     * <p>
     * This performs sanity checking on cluster style packages, such as
     * requiring identical package name and version codes, a single base APK,
     * and unique split names.
     *
     * @see PackageParser#parsePackage(File, int)
     */
    @UnsupportedAppUsage
    public static PackageParser.PackageLite parsePackageLite(File packageFile, int flags)
            throws PackageParser.PackageParserException {
        if (packageFile.isDirectory()) {
            return parseClusterPackageLite(packageFile, flags);
        } else {
            return parseMonolithicPackageLite(packageFile, flags);
        }
    }

    public static PackageParser.PackageLite parseMonolithicPackageLite(File packageFile, int flags)
            throws PackageParser.PackageParserException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parseApkLite");
        final PackageParser.ApkLite baseApk = parseApkLite(packageFile, flags);
        final String packagePath = packageFile.getAbsolutePath();
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        return new PackageParser.PackageLite(packagePath, baseApk, null, null, null, null,
                null, null);
    }

    public static PackageParser.PackageLite parseClusterPackageLite(File packageDir, int flags)
            throws PackageParser.PackageParserException {
        final File[] files = packageDir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            throw new PackageParser.PackageParserException(
                    PackageManager.INSTALL_PARSE_FAILED_NOT_APK, "No packages found in split");
        }
        // Apk directory is directly nested under the current directory
        if (files.length == 1 && files[0].isDirectory()) {
            return parseClusterPackageLite(files[0], flags);
        }

        String packageName = null;
        int versionCode = 0;

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parseApkLite");
        final ArrayMap<String, PackageParser.ApkLite> apks = new ArrayMap<>();
        for (File file : files) {
            if (PackageParser.isApkFile(file)) {
                final PackageParser.ApkLite lite = parseApkLite(file, flags);

                // Assert that all package names and version codes are
                // consistent with the first one we encounter.
                if (packageName == null) {
                    packageName = lite.packageName;
                    versionCode = lite.versionCode;
                } else {
                    if (!packageName.equals(lite.packageName)) {
                        throw new PackageParser.PackageParserException(
                                PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                                "Inconsistent package " + lite.packageName + " in " + file
                                        + "; expected " + packageName);
                    }
                    if (versionCode != lite.versionCode) {
                        throw new PackageParser.PackageParserException(
                                PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                                "Inconsistent version " + lite.versionCode + " in " + file
                                        + "; expected " + versionCode);
                    }
                }

                // Assert that each split is defined only oncuses-static-libe
                if (apks.put(lite.splitName, lite) != null) {
                    throw new PackageParser.PackageParserException(
                            PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                            "Split name " + lite.splitName
                                    + " defined more than once; most recent was " + file);
                }
            }
        }
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

        final PackageParser.ApkLite baseApk = apks.remove(null);
        if (baseApk == null) {
            throw new PackageParser.PackageParserException(
                    PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                    "Missing base APK in " + packageDir);
        }

        // Always apply deterministic ordering based on splitName
        final int size = apks.size();

        String[] splitNames = null;
        boolean[] isFeatureSplits = null;
        String[] usesSplitNames = null;
        String[] configForSplits = null;
        String[] splitCodePaths = null;
        int[] splitRevisionCodes = null;
        if (size > 0) {
            splitNames = new String[size];
            isFeatureSplits = new boolean[size];
            usesSplitNames = new String[size];
            configForSplits = new String[size];
            splitCodePaths = new String[size];
            splitRevisionCodes = new int[size];

            splitNames = apks.keySet().toArray(splitNames);
            Arrays.sort(splitNames, PackageParser.sSplitNameComparator);

            for (int i = 0; i < size; i++) {
                final PackageParser.ApkLite apk = apks.get(splitNames[i]);
                usesSplitNames[i] = apk.usesSplitName;
                isFeatureSplits[i] = apk.isFeatureSplit;
                configForSplits[i] = apk.configForSplit;
                splitCodePaths[i] = apk.codePath;
                splitRevisionCodes[i] = apk.revisionCode;
            }
        }

        final String codePath = packageDir.getAbsolutePath();
        return new PackageParser.PackageLite(codePath, baseApk, splitNames, isFeatureSplits,
                usesSplitNames, configForSplits, splitCodePaths, splitRevisionCodes);
    }

    /**
     * Utility method that retrieves lightweight details about a single APK
     * file, including package name, split name, and install location.
     *
     * @param apkFile path to a single APK
     * @param flags optional parse flags, such as
     *            {@link PackageParser#PARSE_COLLECT_CERTIFICATES}
     */
    public static PackageParser.ApkLite parseApkLite(File apkFile, int flags)
            throws PackageParser.PackageParserException {
        return parseApkLiteInner(apkFile, null, null, flags);
    }

    /**
     * Utility method that retrieves lightweight details about a single APK
     * file, including package name, split name, and install location.
     *
     * @param fd already open file descriptor of an apk file
     * @param debugPathName arbitrary text name for this file, for debug output
     * @param flags optional parse flags, such as
     *            {@link PackageParser#PARSE_COLLECT_CERTIFICATES}
     */
    public static PackageParser.ApkLite parseApkLite(FileDescriptor fd, String debugPathName,
            int flags) throws PackageParser.PackageParserException {
        return parseApkLiteInner(null, fd, debugPathName, flags);
    }

    private static PackageParser.ApkLite parseApkLiteInner(File apkFile, FileDescriptor fd,
            String debugPathName, int flags) throws PackageParser.PackageParserException {
        final String apkPath = fd != null ? debugPathName : apkFile.getAbsolutePath();

        XmlResourceParser parser = null;
        ApkAssets apkAssets = null;
        try {
            try {
                apkAssets = fd != null
                        ? ApkAssets.loadFromFd(fd, debugPathName, 0 /* flags */, null /* assets */)
                        : ApkAssets.loadFromPath(apkPath);
            } catch (IOException e) {
                throw new PackageParser.PackageParserException(
                        PackageManager.INSTALL_PARSE_FAILED_NOT_APK,
                        "Failed to parse " + apkPath, e);
            }

            parser = apkAssets.openXml(PackageParser.ANDROID_MANIFEST_FILENAME);

            final PackageParser.SigningDetails signingDetails;
            if ((flags & PackageParser.PARSE_COLLECT_CERTIFICATES) != 0) {
                final boolean skipVerify = (flags & PackageParser.PARSE_IS_SYSTEM_DIR) != 0;
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "collectCertificates");
                try {
                    signingDetails = ParsingPackageUtils.collectCertificates(apkFile.getAbsolutePath(),
                            skipVerify, false, PackageParser.SigningDetails.UNKNOWN,
                            DEFAULT_TARGET_SDK_VERSION);
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            } else {
                signingDetails = PackageParser.SigningDetails.UNKNOWN;
            }

            final AttributeSet attrs = parser;
            return parseApkLite(apkPath, parser, attrs, signingDetails);

        } catch (XmlPullParserException | IOException | RuntimeException e) {
            Slog.w(TAG, "Failed to parse " + apkPath, e);
            throw new PackageParser.PackageParserException(
                    PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to parse " + apkPath, e);
        } finally {
            IoUtils.closeQuietly(parser);
            if (apkAssets != null) {
                try {
                    apkAssets.close();
                } catch (Throwable ignored) {
                }
            }
            // TODO(b/72056911): Implement AutoCloseable on ApkAssets.
        }
    }

    private static PackageParser.ApkLite parseApkLite(
            String codePath, XmlPullParser parser, AttributeSet attrs,
            PackageParser.SigningDetails signingDetails)
            throws IOException, XmlPullParserException, PackageParser.PackageParserException {
        final Pair<String, String> packageSplit = PackageParser.parsePackageSplitNames(
                parser, attrs);

        int installLocation = PARSE_DEFAULT_INSTALL_LOCATION;
        int versionCode = 0;
        int versionCodeMajor = 0;
        int targetSdkVersion = DEFAULT_TARGET_SDK_VERSION;
        int minSdkVersion = DEFAULT_MIN_SDK_VERSION;
        int revisionCode = 0;
        boolean coreApp = false;
        boolean debuggable = false;
        boolean multiArch = false;
        boolean use32bitAbi = false;
        boolean extractNativeLibs = true;
        boolean isolatedSplits = false;
        boolean isFeatureSplit = false;
        boolean isSplitRequired = false;
        boolean useEmbeddedDex = false;
        String configForSplit = null;
        String usesSplitName = null;
        String targetPackage = null;
        boolean overlayIsStatic = false;
        int overlayPriority = 0;

        String requiredSystemPropertyName = null;
        String requiredSystemPropertyValue = null;

        for (int i = 0; i < attrs.getAttributeCount(); i++) {
            final String attr = attrs.getAttributeName(i);
            switch (attr) {
                case "installLocation":
                    installLocation = attrs.getAttributeIntValue(i,
                            PARSE_DEFAULT_INSTALL_LOCATION);
                    break;
                case "versionCode":
                    versionCode = attrs.getAttributeIntValue(i, 0);
                    break;
                case "versionCodeMajor":
                    versionCodeMajor = attrs.getAttributeIntValue(i, 0);
                    break;
                case "revisionCode":
                    revisionCode = attrs.getAttributeIntValue(i, 0);
                    break;
                case "coreApp":
                    coreApp = attrs.getAttributeBooleanValue(i, false);
                    break;
                case "isolatedSplits":
                    isolatedSplits = attrs.getAttributeBooleanValue(i, false);
                    break;
                case "configForSplit":
                    configForSplit = attrs.getAttributeValue(i);
                    break;
                case "isFeatureSplit":
                    isFeatureSplit = attrs.getAttributeBooleanValue(i, false);
                    break;
                case "isSplitRequired":
                    isSplitRequired = attrs.getAttributeBooleanValue(i, false);
                    break;
            }
        }

        // Only search the tree when the tag is the direct child of <manifest> tag
        int type;
        final int searchDepth = parser.getDepth() + 1;

        final List<VerifierInfo> verifiers = new ArrayList<>();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() >= searchDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getDepth() != searchDepth) {
                continue;
            }

            if (PackageParser.TAG_PACKAGE_VERIFIER.equals(parser.getName())) {
                final VerifierInfo verifier = parseVerifier(attrs);
                if (verifier != null) {
                    verifiers.add(verifier);
                }
            } else if (PackageParser.TAG_APPLICATION.equals(parser.getName())) {
                for (int i = 0; i < attrs.getAttributeCount(); ++i) {
                    final String attr = attrs.getAttributeName(i);
                    switch (attr) {
                        case "debuggable":
                            debuggable = attrs.getAttributeBooleanValue(i, false);
                            break;
                        case "multiArch":
                            multiArch = attrs.getAttributeBooleanValue(i, false);
                            break;
                        case "use32bitAbi":
                            use32bitAbi = attrs.getAttributeBooleanValue(i, false);
                            break;
                        case "extractNativeLibs":
                            extractNativeLibs = attrs.getAttributeBooleanValue(i, true);
                            break;
                        case "useEmbeddedDex":
                            useEmbeddedDex = attrs.getAttributeBooleanValue(i, false);
                            break;
                    }
                }
            } else if (PackageParser.TAG_OVERLAY.equals(parser.getName())) {
                for (int i = 0; i < attrs.getAttributeCount(); ++i) {
                    final String attr = attrs.getAttributeName(i);
                    if ("requiredSystemPropertyName".equals(attr)) {
                        requiredSystemPropertyName = attrs.getAttributeValue(i);
                    } else if ("requiredSystemPropertyValue".equals(attr)) {
                        requiredSystemPropertyValue = attrs.getAttributeValue(i);
                    } else if ("targetPackage".equals(attr)) {
                        targetPackage = attrs.getAttributeValue(i);;
                    } else if ("isStatic".equals(attr)) {
                        overlayIsStatic = attrs.getAttributeBooleanValue(i, false);
                    } else if ("priority".equals(attr)) {
                        overlayPriority = attrs.getAttributeIntValue(i, 0);
                    }
                }
            } else if (PackageParser.TAG_USES_SPLIT.equals(parser.getName())) {
                if (usesSplitName != null) {
                    Slog.w(TAG, "Only one <uses-split> permitted. Ignoring others.");
                    continue;
                }

                usesSplitName = attrs.getAttributeValue(PackageParser.ANDROID_RESOURCES, "name");
                if (usesSplitName == null) {
                    throw new PackageParser.PackageParserException(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "<uses-split> tag requires 'android:name' attribute");
                }
            } else if (PackageParser.TAG_USES_SDK.equals(parser.getName())) {
                for (int i = 0; i < attrs.getAttributeCount(); ++i) {
                    final String attr = attrs.getAttributeName(i);
                    if ("targetSdkVersion".equals(attr)) {
                        targetSdkVersion = attrs.getAttributeIntValue(i,
                                DEFAULT_TARGET_SDK_VERSION);
                    }
                    if ("minSdkVersion".equals(attr)) {
                        minSdkVersion = attrs.getAttributeIntValue(i, DEFAULT_MIN_SDK_VERSION);
                    }
                }
            }
        }

        // Check to see if overlay should be excluded based on system property condition
        if (!PackageParser.checkRequiredSystemProperties(requiredSystemPropertyName,
                requiredSystemPropertyValue)) {
            Slog.i(TAG, "Skipping target and overlay pair " + targetPackage + " and "
                    + codePath + ": overlay ignored due to required system property: "
                    + requiredSystemPropertyName + " with value: " + requiredSystemPropertyValue);
            targetPackage = null;
            overlayIsStatic = false;
            overlayPriority = 0;
        }

        return new PackageParser.ApkLite(codePath, packageSplit.first, packageSplit.second,
                isFeatureSplit, configForSplit, usesSplitName, isSplitRequired, versionCode,
                versionCodeMajor, revisionCode, installLocation, verifiers, signingDetails,
                coreApp, debuggable, multiArch, use32bitAbi, useEmbeddedDex, extractNativeLibs,
                isolatedSplits, targetPackage, overlayIsStatic, overlayPriority, minSdkVersion,
                targetSdkVersion);
    }

    public static VerifierInfo parseVerifier(AttributeSet attrs) {
        String packageName = null;
        String encodedPublicKey = null;

        final int attrCount = attrs.getAttributeCount();
        for (int i = 0; i < attrCount; i++) {
            final int attrResId = attrs.getAttributeNameResource(i);
            switch (attrResId) {
                case R.attr.name:
                    packageName = attrs.getAttributeValue(i);
                    break;

                case R.attr.publicKey:
                    encodedPublicKey = attrs.getAttributeValue(i);
                    break;
            }
        }

        if (packageName == null || packageName.length() == 0) {
            Slog.i(TAG, "verifier package name was null; skipping");
            return null;
        }

        final PublicKey publicKey = PackageParser.parsePublicKey(encodedPublicKey);
        if (publicKey == null) {
            Slog.i(TAG, "Unable to parse verifier public key for " + packageName);
            return null;
        }

        return new VerifierInfo(packageName, publicKey);
    }
}
