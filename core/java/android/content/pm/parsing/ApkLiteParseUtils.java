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

import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.VerifierInfo;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
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
    public static ParseResult<PackageParser.PackageLite> parsePackageLite(ParseInput input,
            File packageFile, int flags) {
        if (packageFile.isDirectory()) {
            return parseClusterPackageLite(input, packageFile, flags);
        } else {
            return parseMonolithicPackageLite(input, packageFile, flags);
        }
    }

    public static ParseResult<PackageParser.PackageLite> parseMonolithicPackageLite(
            ParseInput input, File packageFile, int flags) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parseApkLite");
        try {
            ParseResult<PackageParser.ApkLite> result = parseApkLite(input, packageFile, flags);
            if (result.isError()) {
                return input.error(result);
            }

            final PackageParser.ApkLite baseApk = result.getResult();
            final String packagePath = packageFile.getAbsolutePath();
            return input.success(
                    new PackageParser.PackageLite(packagePath, baseApk, null, null, null, null,
                            null, null));
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    public static ParseResult<PackageParser.PackageLite> parseClusterPackageLite(ParseInput input,
            File packageDir, int flags) {
        final File[] files = packageDir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            return input.error(PackageManager.INSTALL_PARSE_FAILED_NOT_APK,
                    "No packages found in split");
        }
        // Apk directory is directly nested under the current directory
        if (files.length == 1 && files[0].isDirectory()) {
            return parseClusterPackageLite(input, files[0], flags);
        }

        String packageName = null;
        int versionCode = 0;

        final ArrayMap<String, PackageParser.ApkLite> apks = new ArrayMap<>();
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parseApkLite");
        try {
            for (File file : files) {
                if (PackageParser.isApkFile(file)) {
                    ParseResult<PackageParser.ApkLite> result = parseApkLite(input, file, flags);
                    if (result.isError()) {
                        return input.error(result);
                    }

                    final PackageParser.ApkLite lite = result.getResult();
                    // Assert that all package names and version codes are
                    // consistent with the first one we encounter.
                    if (packageName == null) {
                        packageName = lite.packageName;
                        versionCode = lite.versionCode;
                    } else {
                        if (!packageName.equals(lite.packageName)) {
                            return input.error(PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                                    "Inconsistent package " + lite.packageName + " in " + file
                                            + "; expected " + packageName);
                        }
                        if (versionCode != lite.versionCode) {
                            return input.error(PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                                    "Inconsistent version " + lite.versionCode + " in " + file
                                            + "; expected " + versionCode);
                        }
                    }

                    // Assert that each split is defined only oncuses-static-libe
                    if (apks.put(lite.splitName, lite) != null) {
                        return input.error(PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                                "Split name " + lite.splitName
                                        + " defined more than once; most recent was " + file);
                    }
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        final PackageParser.ApkLite baseApk = apks.remove(null);
        if (baseApk == null) {
            return input.error(PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
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
        return input.success(new PackageParser.PackageLite(codePath, baseApk, splitNames,
                isFeatureSplits, usesSplitNames, configForSplits, splitCodePaths,
                splitRevisionCodes));
    }

    /**
     * Utility method that retrieves lightweight details about a single APK
     * file, including package name, split name, and install location.
     *
     * @param apkFile path to a single APK
     * @param flags optional parse flags, such as
     *            {@link PackageParser#PARSE_COLLECT_CERTIFICATES}
     */
    public static ParseResult<PackageParser.ApkLite> parseApkLite(ParseInput input, File apkFile,
            int flags) {
        return parseApkLiteInner(input, apkFile, null, null, flags);
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
    public static ParseResult<PackageParser.ApkLite> parseApkLite(ParseInput input,
            FileDescriptor fd, String debugPathName, int flags) {
        return parseApkLiteInner(input, null, fd, debugPathName, flags);
    }

    private static ParseResult<PackageParser.ApkLite> parseApkLiteInner(ParseInput input,
            File apkFile, FileDescriptor fd, String debugPathName, int flags) {
        final String apkPath = fd != null ? debugPathName : apkFile.getAbsolutePath();

        XmlResourceParser parser = null;
        ApkAssets apkAssets = null;
        try {
            try {
                apkAssets = fd != null
                        ? ApkAssets.loadFromFd(fd, debugPathName, 0 /* flags */, null /* assets */)
                        : ApkAssets.loadFromPath(apkPath);
            } catch (IOException e) {
                return input.error(PackageManager.INSTALL_PARSE_FAILED_NOT_APK,
                        "Failed to parse " + apkPath, e);
            }

            parser = apkAssets.openXml(PackageParser.ANDROID_MANIFEST_FILENAME);

            final PackageParser.SigningDetails signingDetails;
            if ((flags & PackageParser.PARSE_COLLECT_CERTIFICATES) != 0) {
                final boolean skipVerify = (flags & PackageParser.PARSE_IS_SYSTEM_DIR) != 0;
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "collectCertificates");
                try {
                    ParseResult<PackageParser.SigningDetails> result =
                            ParsingPackageUtils.getSigningDetails(input,
                                    apkFile.getAbsolutePath(), skipVerify, false,
                                    PackageParser.SigningDetails.UNKNOWN,
                                    DEFAULT_TARGET_SDK_VERSION);
                    if (result.isError()) {
                        return input.error(result);
                    }
                    signingDetails = result.getResult();
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            } else {
                signingDetails = PackageParser.SigningDetails.UNKNOWN;
            }

            final AttributeSet attrs = parser;
            return parseApkLite(input, apkPath, parser, attrs, signingDetails);
        } catch (XmlPullParserException | IOException | RuntimeException e) {
            Slog.w(TAG, "Failed to parse " + apkPath, e);
            return input.error(PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
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

    private static ParseResult<PackageParser.ApkLite> parseApkLite(ParseInput input,
            String codePath, XmlPullParser parser, AttributeSet attrs,
            PackageParser.SigningDetails signingDetails)
            throws IOException, XmlPullParserException {
        ParseResult<Pair<String, String>> result = parsePackageSplitNames(input, parser, attrs);
        if (result.isError()) {
            return input.error(result);
        }

        Pair<String, String> packageSplit = result.getResult();

        int installLocation = PARSE_DEFAULT_INSTALL_LOCATION;
        int versionCode = 0;
        int versionCodeMajor = 0;
        int targetSdkVersion = DEFAULT_TARGET_SDK_VERSION;
        int minSdkVersion = DEFAULT_MIN_SDK_VERSION;
        int revisionCode = 0;
        boolean coreApp = false;
        boolean debuggable = false;
        boolean profilableByShell = false;
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
                            if (debuggable) {
                                // Debuggable implies profileable
                                profilableByShell = true;
                            }
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
                    return input.error(PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
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
            } else if (PackageParser.TAG_PROFILEABLE.equals(parser.getName())) {
                for (int i = 0; i < attrs.getAttributeCount(); ++i) {
                    final String attr = attrs.getAttributeName(i);
                    if ("shell".equals(attr)) {
                        profilableByShell = attrs.getAttributeBooleanValue(i, profilableByShell);
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

        return input.success(
                new PackageParser.ApkLite(codePath, packageSplit.first, packageSplit.second,
                        isFeatureSplit, configForSplit, usesSplitName, isSplitRequired, versionCode,
                        versionCodeMajor, revisionCode, installLocation, verifiers, signingDetails,
                        coreApp, debuggable, profilableByShell, multiArch, use32bitAbi,
                        useEmbeddedDex, extractNativeLibs, isolatedSplits, targetPackage,
                        overlayIsStatic, overlayPriority, minSdkVersion, targetSdkVersion));
    }

    public static ParseResult<Pair<String, String>> parsePackageSplitNames(ParseInput input,
            XmlPullParser parser, AttributeSet attrs) throws IOException, XmlPullParserException {
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
        }

        if (type != XmlPullParser.START_TAG) {
            return input.error(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "No start tag found");
        }
        if (!parser.getName().equals(PackageParser.TAG_MANIFEST)) {
            return input.error(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "No <manifest> tag");
        }

        final String packageName = attrs.getAttributeValue(null, "package");
        if (!"android".equals(packageName)) {
            final String error = PackageParser.validateName(packageName, true, true);
            if (error != null) {
                return input.error(INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
                        "Invalid manifest package: " + error);
            }
        }

        String splitName = attrs.getAttributeValue(null, "split");
        if (splitName != null) {
            if (splitName.length() == 0) {
                splitName = null;
            } else {
                final String error = PackageParser.validateName(splitName, false, false);
                if (error != null) {
                    return input.error(INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
                            "Invalid manifest split: " + error);
                }
            }
        }

        return input.success(Pair.create(packageName.intern(),
                (splitName != null) ? splitName.intern() : splitName));
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
