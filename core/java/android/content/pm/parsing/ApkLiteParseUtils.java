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
import static android.content.pm.parsing.ParsingPackageUtils.validateName;
import static android.content.pm.parsing.ParsingUtils.ANDROID_RES_NAMESPACE;
import static android.content.pm.parsing.ParsingUtils.DEFAULT_MIN_SDK_VERSION;
import static android.content.pm.parsing.ParsingUtils.DEFAULT_TARGET_SDK_VERSION;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;

import android.annotation.NonNull;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.VerifierInfo;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.ApkAssets;
import android.content.res.XmlResourceParser;
import android.os.Trace;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.Slog;

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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** @hide */
public class ApkLiteParseUtils {

    private static final String TAG = ParsingUtils.TAG;

    private static final int PARSE_DEFAULT_INSTALL_LOCATION =
            PackageInfo.INSTALL_LOCATION_UNSPECIFIED;

    private static final Comparator<String> sSplitNameComparator = new SplitNameComparator();

    public static final String APK_FILE_EXTENSION = ".apk";

    /**
     * Parse only lightweight details about the package at the given location.
     * Automatically detects if the package is a monolithic style (single APK
     * file) or cluster style (directory of APKs).
     * <p>
     * This performs validity checking on cluster style packages, such as
     * requiring identical package name and version codes, a single base APK,
     * and unique split names.
     *
     * @see PackageParser#parsePackage(File, int)
     */
    public static ParseResult<PackageLite> parsePackageLite(ParseInput input,
            File packageFile, int flags) {
        if (packageFile.isDirectory()) {
            return parseClusterPackageLite(input, packageFile, flags);
        } else {
            return parseMonolithicPackageLite(input, packageFile, flags);
        }
    }

    /**
     * Parse lightweight details about a single APK files.
     */
    public static ParseResult<PackageLite> parseMonolithicPackageLite(ParseInput input,
            File packageFile, int flags) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parseApkLite");
        try {
            final ParseResult<ApkLite> result = parseApkLite(input, packageFile, flags);
            if (result.isError()) {
                return input.error(result);
            }

            final ApkLite baseApk = result.getResult();
            final String packagePath = packageFile.getAbsolutePath();
            return input.success(
                    new PackageLite(packagePath, baseApk.getPath(), baseApk, null,
                            null, null, null, null, null, baseApk.getTargetSdkVersion()));
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Parse lightweight details about a directory of APKs.
     */
    public static ParseResult<PackageLite> parseClusterPackageLite(ParseInput input,
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

        final ArrayMap<String, ApkLite> apks = new ArrayMap<>();
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parseApkLite");
        try {
            for (File file : files) {
                if (isApkFile(file)) {
                    final ParseResult<ApkLite> result = parseApkLite(input, file, flags);
                    if (result.isError()) {
                        return input.error(result);
                    }

                    final ApkLite lite = result.getResult();
                    // Assert that all package names and version codes are
                    // consistent with the first one we encounter.
                    if (packageName == null) {
                        packageName = lite.getPackageName();
                        versionCode = lite.getVersionCode();
                    } else {
                        if (!packageName.equals(lite.getPackageName())) {
                            return input.error(PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                                    "Inconsistent package " + lite.getPackageName() + " in " + file
                                            + "; expected " + packageName);
                        }
                        if (versionCode != lite.getVersionCode()) {
                            return input.error(PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                                    "Inconsistent version " + lite.getVersionCode() + " in " + file
                                            + "; expected " + versionCode);
                        }
                    }

                    // Assert that each split is defined only oncuses-static-libe
                    if (apks.put(lite.getSplitName(), lite) != null) {
                        return input.error(PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                                "Split name " + lite.getSplitName()
                                        + " defined more than once; most recent was " + file);
                    }
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }

        final ApkLite baseApk = apks.remove(null);
        return composePackageLiteFromApks(input, packageDir, baseApk, apks);
    }

    /**
     * Utility method that retrieves lightweight details about the package by given location,
     * base APK, and split APKs.
     *
     * @param packageDir Path to the package
     * @param baseApk Parsed base APK
     * @param splitApks Parsed split APKs
     * @return PackageLite
     */
    public static ParseResult<PackageLite> composePackageLiteFromApks(ParseInput input,
            File packageDir, ApkLite baseApk, ArrayMap<String, ApkLite> splitApks) {
        return composePackageLiteFromApks(input, packageDir, baseApk, splitApks, false);
    }

    /**
     * Utility method that retrieves lightweight details about the package by given location,
     * base APK, and split APKs.
     *
     * @param packageDir Path to the package
     * @param baseApk Parsed base APK
     * @param splitApks Parsed split APKs
     * @param apkRenamed Indicate whether the APKs are renamed after parsed.
     * @return PackageLite
     */
    public static ParseResult<PackageLite> composePackageLiteFromApks(
            ParseInput input, File packageDir, ApkLite baseApk,
            ArrayMap<String, ApkLite> splitApks, boolean apkRenamed) {
        if (baseApk == null) {
            return input.error(PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                    "Missing base APK in " + packageDir);
        }
        // Always apply deterministic ordering based on splitName
        final int size = ArrayUtils.size(splitApks);

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

            splitNames = splitApks.keySet().toArray(splitNames);
            Arrays.sort(splitNames, sSplitNameComparator);

            for (int i = 0; i < size; i++) {
                final ApkLite apk = splitApks.get(splitNames[i]);
                usesSplitNames[i] = apk.getUsesSplitName();
                isFeatureSplits[i] = apk.isFeatureSplit();
                configForSplits[i] = apk.getConfigForSplit();
                splitCodePaths[i] = apkRenamed ? new File(packageDir,
                        splitNameToFileName(apk)).getAbsolutePath() : apk.getPath();
                splitRevisionCodes[i] = apk.getRevisionCode();
            }
        }

        final String codePath = packageDir.getAbsolutePath();
        final String baseCodePath = apkRenamed ? new File(packageDir,
                splitNameToFileName(baseApk)).getAbsolutePath() : baseApk.getPath();
        return input.success(
                new PackageLite(codePath, baseCodePath, baseApk, splitNames, isFeatureSplits,
                        usesSplitNames, configForSplits, splitCodePaths, splitRevisionCodes,
                        baseApk.getTargetSdkVersion()));
    }

    /**
     * Utility method that retrieves canonical file name by given split name from parsed APK.
     *
     * @param apk Parsed APK
     * @return The canonical file name
     */
    public static String splitNameToFileName(@NonNull ApkLite apk) {
        Objects.requireNonNull(apk);
        final String fileName = apk.getSplitName() == null ? "base" : "split_" + apk.getSplitName();
        return fileName + APK_FILE_EXTENSION;
    }

    /**
     * Utility method that retrieves lightweight details about a single APK
     * file, including package name, split name, and install location.
     *
     * @param apkFile path to a single APK
     * @param flags optional parse flags, such as
     *            {@link ParsingPackageUtils#PARSE_COLLECT_CERTIFICATES}
     */
    public static ParseResult<ApkLite> parseApkLite(ParseInput input, File apkFile, int flags) {
        return parseApkLiteInner(input, apkFile, null, null, flags);
    }

    /**
     * Utility method that retrieves lightweight details about a single APK
     * file, including package name, split name, and install location.
     *
     * @param fd already open file descriptor of an apk file
     * @param debugPathName arbitrary text name for this file, for debug output
     * @param flags optional parse flags, such as
     *            {@link ParsingPackageUtils#PARSE_COLLECT_CERTIFICATES}
     */
    public static ParseResult<ApkLite> parseApkLite(ParseInput input,
            FileDescriptor fd, String debugPathName, int flags) {
        return parseApkLiteInner(input, null, fd, debugPathName, flags);
    }

    private static ParseResult<ApkLite> parseApkLiteInner(ParseInput input,
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

            parser = apkAssets.openXml(ParsingPackageUtils.ANDROID_MANIFEST_FILENAME);

            final PackageParser.SigningDetails signingDetails;
            if ((flags & ParsingPackageUtils.PARSE_COLLECT_CERTIFICATES) != 0) {
                final boolean skipVerify = (flags & ParsingPackageUtils.PARSE_IS_SYSTEM_DIR) != 0;
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

            return parseApkLite(input, apkPath, parser, signingDetails);
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

    private static ParseResult<ApkLite> parseApkLite(ParseInput input, String codePath,
            XmlResourceParser parser, PackageParser.SigningDetails signingDetails)
            throws IOException, XmlPullParserException {
        ParseResult<Pair<String, String>> result = parsePackageSplitNames(input, parser);
        if (result.isError()) {
            return input.error(result);
        }

        Pair<String, String> packageSplit = result.getResult();

        int installLocation = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE,
                "installLocation", PARSE_DEFAULT_INSTALL_LOCATION);
        int versionCode = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE, "versionCode", 0);
        int versionCodeMajor = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE,
                "versionCodeMajor",
                0);
        int revisionCode = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE, "revisionCode", 0);
        boolean coreApp = parser.getAttributeBooleanValue(null, "coreApp", false);
        boolean isolatedSplits = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE,
                "isolatedSplits", false);
        boolean isFeatureSplit = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE,
                "isFeatureSplit", false);
        boolean isSplitRequired = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE,
                "isSplitRequired", false);
        String configForSplit = parser.getAttributeValue(null, "configForSplit");

        int targetSdkVersion = DEFAULT_TARGET_SDK_VERSION;
        int minSdkVersion = DEFAULT_MIN_SDK_VERSION;
        boolean debuggable = false;
        boolean profilableByShell = false;
        boolean multiArch = false;
        boolean use32bitAbi = false;
        boolean extractNativeLibs = true;
        boolean useEmbeddedDex = false;
        String usesSplitName = null;
        String targetPackage = null;
        boolean overlayIsStatic = false;
        int overlayPriority = 0;
        int rollbackDataPolicy = 0;

        String requiredSystemPropertyName = null;
        String requiredSystemPropertyValue = null;

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

            if (ParsingPackageUtils.TAG_PACKAGE_VERIFIER.equals(parser.getName())) {
                final VerifierInfo verifier = parseVerifier(parser);
                if (verifier != null) {
                    verifiers.add(verifier);
                }
            } else if (ParsingPackageUtils.TAG_APPLICATION.equals(parser.getName())) {
                debuggable = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE, "debuggable",
                        false);
                multiArch = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE, "multiArch",
                        false);
                use32bitAbi = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE, "use32bitAbi",
                        false);
                extractNativeLibs = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE,
                        "extractNativeLibs", true);
                useEmbeddedDex = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE,
                        "useEmbeddedDex", false);
                rollbackDataPolicy = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE,
                        "rollbackDataPolicy", 0);

                final int innerDepth = parser.getDepth();
                int innerType;
                while ((innerType = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (innerType != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
                    if (innerType == XmlPullParser.END_TAG || innerType == XmlPullParser.TEXT) {
                        continue;
                    }

                    if (parser.getDepth() != innerDepth + 1) {
                        // Search only under <application>.
                        continue;
                    }

                    if (ParsingPackageUtils.TAG_PROFILEABLE.equals(parser.getName())) {
                        profilableByShell = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE,
                                "shell", profilableByShell);
                    }
                }
            } else if (ParsingPackageUtils.TAG_OVERLAY.equals(parser.getName())) {
                requiredSystemPropertyName = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                        "requiredSystemPropertyName");
                requiredSystemPropertyValue = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                        "requiredSystemPropertyValue");
                targetPackage = parser.getAttributeValue(ANDROID_RES_NAMESPACE, "targetPackage");
                overlayIsStatic = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE, "isStatic",
                        false);
                overlayPriority = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE, "priority", 0);
            } else if (ParsingPackageUtils.TAG_USES_SPLIT.equals(parser.getName())) {
                if (usesSplitName != null) {
                    Slog.w(TAG, "Only one <uses-split> permitted. Ignoring others.");
                    continue;
                }

                usesSplitName = parser.getAttributeValue(ANDROID_RES_NAMESPACE, "name");
                if (usesSplitName == null) {
                    return input.error(PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "<uses-split> tag requires 'android:name' attribute");
                }
            } else if (ParsingPackageUtils.TAG_USES_SDK.equals(parser.getName())) {
                // Mirrors ParsingPackageUtils#parseUsesSdk until lite and full parsing is combined
                String minSdkVersionString = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                        "minSdkVersion");
                String targetSdkVersionString = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                        "targetSdkVersion");

                int minVer = DEFAULT_MIN_SDK_VERSION;
                String minCode = null;
                int targetVer = DEFAULT_TARGET_SDK_VERSION;
                String targetCode = null;

                if (!TextUtils.isEmpty(minSdkVersionString)) {
                    try {
                        minVer = Integer.parseInt(minSdkVersionString);
                    } catch (NumberFormatException ignored) {
                        minCode = minSdkVersionString;
                    }
                }

                if (!TextUtils.isEmpty(targetSdkVersionString)) {
                    try {
                        targetVer = Integer.parseInt(targetSdkVersionString);
                    } catch (NumberFormatException ignored) {
                        targetCode = targetSdkVersionString;
                        if (minCode == null) {
                            minCode = targetCode;
                        }
                    }
                } else {
                    targetVer = minVer;
                    targetCode = minCode;
                }

                ParseResult<Integer> targetResult = ParsingPackageUtils.computeTargetSdkVersion(
                        targetVer, targetCode, ParsingPackageUtils.SDK_CODENAMES, input);
                if (targetResult.isError()) {
                    return input.error(targetResult);
                }

                ParseResult<Integer> minResult = ParsingPackageUtils.computeMinSdkVersion(
                        minVer, minCode, ParsingPackageUtils.SDK_VERSION,
                        ParsingPackageUtils.SDK_CODENAMES, input);
                if (minResult.isError()) {
                    return input.error(minResult);
                }

                targetSdkVersion = targetResult.getResult();
                minSdkVersion = minResult.getResult();
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
                new ApkLite(codePath, packageSplit.first, packageSplit.second, isFeatureSplit,
                        configForSplit, usesSplitName, isSplitRequired, versionCode,
                        versionCodeMajor, revisionCode, installLocation, verifiers, signingDetails,
                        coreApp, debuggable, profilableByShell, multiArch, use32bitAbi,
                        useEmbeddedDex, extractNativeLibs, isolatedSplits, targetPackage,
                        overlayIsStatic, overlayPriority, minSdkVersion, targetSdkVersion,
                        rollbackDataPolicy));
    }

    public static ParseResult<Pair<String, String>> parsePackageSplitNames(ParseInput input,
            XmlResourceParser parser) throws IOException, XmlPullParserException {
        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
        }

        if (type != XmlPullParser.START_TAG) {
            return input.error(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "No start tag found");
        }
        if (!parser.getName().equals(ParsingPackageUtils.TAG_MANIFEST)) {
            return input.error(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "No <manifest> tag");
        }

        final String packageName = parser.getAttributeValue(null, "package");
        if (!"android".equals(packageName)) {
            final ParseResult<?> nameResult = validateName(input, packageName, true, true);
            if (nameResult.isError()) {
                return input.error(INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
                        "Invalid manifest package: " + nameResult.getErrorMessage());
            }
        }

        String splitName = parser.getAttributeValue(null, "split");
        if (splitName != null) {
            if (splitName.length() == 0) {
                splitName = null;
            } else {
                final ParseResult<?> nameResult = validateName(input, splitName, false, false);
                if (nameResult.isError()) {
                    return input.error(INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
                            "Invalid manifest split: " + nameResult.getErrorMessage());
                }
            }
        }

        return input.success(Pair.create(packageName.intern(),
                (splitName != null) ? splitName.intern() : splitName));
    }

    public static VerifierInfo parseVerifier(AttributeSet attrs) {
        String packageName = attrs.getAttributeValue(ANDROID_RES_NAMESPACE, "name");
        String encodedPublicKey = attrs.getAttributeValue(ANDROID_RES_NAMESPACE, "publicKey");

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

    /**
     * Used to sort a set of APKs based on their split names, always placing the
     * base APK (with {@code null} split name) first.
     */
    private static class SplitNameComparator implements Comparator<String> {
        @Override
        public int compare(String lhs, String rhs) {
            if (lhs == null) {
                return -1;
            } else if (rhs == null) {
                return 1;
            } else {
                return lhs.compareTo(rhs);
            }
        }
    }

    /**
     * Check if the given file is an APK file.
     *
     * @param file the file to check.
     * @return {@code true} if the given file is an APK file.
     */
    public static boolean isApkFile(File file) {
        return isApkPath(file.getName());
    }

    /**
     * Check if the given path ends with APK file extension.
     *
     * @param path the path to check.
     * @return {@code true} if the given path ends with APK file extension.
     */
    public static boolean isApkPath(String path) {
        return path.endsWith(APK_FILE_EXTENSION);
    }
}
