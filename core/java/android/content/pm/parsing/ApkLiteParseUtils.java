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

import android.annotation.NonNull;
import android.app.admin.DeviceAdminReceiver;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningDetails;
import android.content.pm.VerifierInfo;
import android.content.pm.parsing.result.ParseInput;
import android.content.pm.parsing.result.ParseResult;
import android.content.res.ApkAssets;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.SystemProperties;
import android.os.Trace;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.pm.pkg.component.flags.Flags;
import com.android.internal.util.ArrayUtils;

import libcore.io.IoUtils;
import libcore.util.HexEncoding;

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
import java.util.Set;

/** @hide */
public class ApkLiteParseUtils {

    private static final String TAG = "ApkLiteParseUtils";

    private static final int PARSE_DEFAULT_INSTALL_LOCATION =
            PackageInfo.INSTALL_LOCATION_UNSPECIFIED;

    private static final Comparator<String> sSplitNameComparator = new SplitNameComparator();

    public static final String APK_FILE_EXTENSION = ".apk";


    // Constants copied from services.jar side since they're not accessible
    private static final String ANDROID_RES_NAMESPACE =
            "http://schemas.android.com/apk/res/android";
    public static final int DEFAULT_MIN_SDK_VERSION = 1;
    private static final int DEFAULT_TARGET_SDK_VERSION = 0;
    public static final String ANDROID_MANIFEST_FILENAME = "AndroidManifest.xml";
    private static final int PARSE_IS_SYSTEM_DIR = 1 << 4;
    private static final int PARSE_COLLECT_CERTIFICATES = 1 << 5;
    private static final String TAG_APPLICATION = "application";
    private static final String TAG_PACKAGE_VERIFIER = "package-verifier";
    private static final String TAG_PROFILEABLE = "profileable";
    private static final String TAG_RECEIVER = "receiver";
    private static final String TAG_OVERLAY = "overlay";
    private static final String TAG_USES_SDK = "uses-sdk";
    private static final String TAG_USES_SPLIT = "uses-split";
    private static final String TAG_MANIFEST = "manifest";
    private static final String TAG_USES_SDK_LIBRARY = "uses-sdk-library";
    private static final String TAG_USES_STATIC_LIBRARY = "uses-static-library";
    private static final String TAG_SDK_LIBRARY = "sdk-library";
    private static final int SDK_VERSION = Build.VERSION.SDK_INT;
    private static final String[] SDK_CODENAMES = Build.VERSION.ACTIVE_CODENAMES;
    private static final String TAG_PROCESSES = "processes";
    private static final String TAG_PROCESS = "process";
    private static final String TAG_STATIC_LIBRARY = "static-library";
    private static final String TAG_LIBRARY = "library";

    /**
     * Parse only lightweight details about the package at the given location.
     * Automatically detects if the package is a monolithic style (single APK
     * file) or cluster style (directory of APKs).
     * <p>
     * This performs validity checking on cluster style packages, such as
     * requiring identical package name and version codes, a single base APK,
     * and unique split names.
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
     * Parse lightweight details about a single APK file.
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
                    new PackageLite(packagePath, baseApk.getPath(), baseApk, null /* splitNames */,
                            null /* isFeatureSplits */, null /* usesSplitNames */,
                            null /* configForSplit */, null /* splitApkPaths */,
                            null /* splitRevisionCodes */, baseApk.getTargetSdkVersion(),
                            null /* requiredSplitTypes */, null /* splitTypes */));
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Parse lightweight details about a single APK file passed as an FD.
     */
    public static ParseResult<PackageLite> parseMonolithicPackageLite(ParseInput input,
            FileDescriptor packageFd, String debugPathName, int flags) {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parseApkLite");
        try {
            final ParseResult<ApkLite> result = parseApkLite(input, packageFd, debugPathName,
                    flags);
            if (result.isError()) {
                return input.error(result);
            }

            final ApkLite baseApk = result.getResult();
            final String packagePath = debugPathName;
            return input.success(
                    new PackageLite(packagePath, baseApk.getPath(), baseApk, null /* splitNames */,
                            null /* isFeatureSplits */, null /* usesSplitNames */,
                            null /* configForSplit */, null /* splitApkPaths */,
                            null /* splitRevisionCodes */, baseApk.getTargetSdkVersion(),
                            null /* requiredSplitTypes */, null /* splitTypes */));
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    /**
     * Parse lightweight details about a directory of APKs.
     *
     * @param packageDir is the folder that contains split apks for a regular app
     */
    public static ParseResult<PackageLite> parseClusterPackageLite(ParseInput input,
            File packageDir, int flags) {
        final File[] files;
        files = packageDir.listFiles();
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
        ApkLite baseApk = null;

        final ArrayMap<String, ApkLite> apks = new ArrayMap<>();
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parseApkLite");
        try {
            for (File file : files) {
                if (!isApkFile(file)) {
                    continue;
                }

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

                // Assert that each split is defined only once
                ApkLite prev = apks.put(lite.getSplitName(), lite);
                if (prev != null) {
                    return input.error(PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST,
                            "Split name " + lite.getSplitName()
                                    + " defined more than once; most recent was " + file
                                    + ", previous was " + prev.getPath());
                }
            }
            baseApk = apks.remove(null);
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
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
        Set<String>[] requiredSplitTypes = null;
        Set<String>[] splitTypes = null;
        boolean[] isFeatureSplits = null;
        String[] usesSplitNames = null;
        String[] configForSplits = null;
        String[] splitCodePaths = null;
        int[] splitRevisionCodes = null;
        if (size > 0) {
            splitNames = new String[size];
            requiredSplitTypes = new Set[size];
            splitTypes = new Set[size];
            isFeatureSplits = new boolean[size];
            usesSplitNames = new String[size];
            configForSplits = new String[size];
            splitCodePaths = new String[size];
            splitRevisionCodes = new int[size];

            splitNames = splitApks.keySet().toArray(splitNames);
            Arrays.sort(splitNames, sSplitNameComparator);

            for (int i = 0; i < size; i++) {
                final ApkLite apk = splitApks.get(splitNames[i]);
                requiredSplitTypes[i] = apk.getRequiredSplitTypes();
                splitTypes[i] = apk.getSplitTypes();
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
                        baseApk.getTargetSdkVersion(), requiredSplitTypes, splitTypes));
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

            parser = apkAssets.openXml(ANDROID_MANIFEST_FILENAME);

            final SigningDetails signingDetails;
            if ((flags & PARSE_COLLECT_CERTIFICATES) != 0) {
                final boolean skipVerify = (flags & PARSE_IS_SYSTEM_DIR) != 0;
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "collectCertificates");
                try {
                    final ParseResult<SigningDetails> result =
                            FrameworkParsingPackageUtils.getSigningDetails(input,
                                    apkFile.getAbsolutePath(),
                                    skipVerify, /* isStaticSharedLibrary */ false,
                                    SigningDetails.UNKNOWN, DEFAULT_TARGET_SDK_VERSION);
                    if (result.isError()) {
                        return input.error(result);
                    }
                    signingDetails = result.getResult();
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
            } else {
                signingDetails = SigningDetails.UNKNOWN;
            }

            return parseApkLite(input, apkPath, parser, signingDetails, flags);
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
            XmlResourceParser parser, SigningDetails signingDetails, int flags)
            throws IOException, XmlPullParserException {
        ParseResult<Pair<String, String>> result = parsePackageSplitNames(input, parser);
        if (result.isError()) {
            return input.error(result);
        }
        Pair<String, String> packageSplit = result.getResult();

        final ParseResult<Pair<Set<String>, Set<String>>> requiredSplitTypesResult =
                parseRequiredSplitTypes(input, parser);
        if (requiredSplitTypesResult.isError()) {
            return input.error(result);
        }
        Pair<Set<String>, Set<String>> requiredSplitTypes = requiredSplitTypesResult.getResult();

        int installLocation = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE,
                "installLocation", PARSE_DEFAULT_INSTALL_LOCATION);
        int versionCode = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE, "versionCode", 0);
        int versionCodeMajor = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE,
                "versionCodeMajor",
                0);
        int revisionCode = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE, "revisionCode", 0);
        boolean coreApp = parser.getAttributeBooleanValue(null, "coreApp", false);
        boolean updatableSystem = parser.getAttributeBooleanValue(null, "updatableSystem", true);
        boolean isolatedSplits = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE,
                "isolatedSplits", false);
        boolean isFeatureSplit = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE,
                "isFeatureSplit", false);
        boolean isSplitRequired = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE,
                "isSplitRequired", false);
        String configForSplit = parser.getAttributeValue(null, "configForSplit");
        String emergencyInstaller = parser.getAttributeValue(null, "emergencyInstaller");

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

        boolean hasDeviceAdminReceiver = false;

        boolean isSdkLibrary = false;
        List<String> usesSdkLibraries = new ArrayList<>();
        long[] usesSdkLibrariesVersionsMajor = new long[0];
        String[][] usesSdkLibrariesCertDigests = new String[0][0];

        List<String> usesStaticLibraries = new ArrayList<>();
        long[] usesStaticLibrariesVersions = new long[0];
        String[][] usesStaticLibrariesCertDigests = new String[0][0];

        List<SharedLibraryInfo> declaredLibraries = new ArrayList<>();

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

            if (TAG_PACKAGE_VERIFIER.equals(parser.getName())) {
                final VerifierInfo verifier = parseVerifier(parser);
                if (verifier != null) {
                    verifiers.add(verifier);
                }
            } else if (TAG_APPLICATION.equals(parser.getName())) {
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
                String permission = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                        "permission");
                boolean hasBindDeviceAdminPermission =
                        android.Manifest.permission.BIND_DEVICE_ADMIN.equals(permission);

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

                    switch (parser.getName()) {
                        case TAG_PROFILEABLE:
                            profilableByShell = parser.getAttributeBooleanValue(
                                    ANDROID_RES_NAMESPACE, "shell", profilableByShell);
                            break;
                        case TAG_RECEIVER:
                            hasDeviceAdminReceiver |= isDeviceAdminReceiver(parser,
                                    hasBindDeviceAdminPermission);
                            break;
                        case TAG_USES_SDK_LIBRARY:
                            String usesSdkLibName = parser.getAttributeValue(
                                    ANDROID_RES_NAMESPACE, "name");
                            long usesSdkLibVersionMajor = parser.getAttributeIntValue(
                                    ANDROID_RES_NAMESPACE, "versionMajor", -1);
                            String usesSdkCertDigest = parser.getAttributeValue(
                                     ANDROID_RES_NAMESPACE, "certDigest");

                            if (usesSdkLibName == null || usesSdkLibName.isBlank()
                                    || usesSdkLibVersionMajor < 0) {
                                return input.error(
                                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                        "Bad uses-sdk-library declaration name: "
                                                + usesSdkLibName
                                                + " version: " + usesSdkLibVersionMajor);
                            }

                            if (usesSdkLibraries.contains(usesSdkLibName)) {
                                return input.error(
                                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                        "Bad uses-sdk-library declaration. Depending on"
                                                + " multiple versions of SDK library: "
                                                + usesSdkLibName);
                            }

                            usesSdkLibraries.add(usesSdkLibName);
                            usesSdkLibrariesVersionsMajor = ArrayUtils.appendLong(
                                    usesSdkLibrariesVersionsMajor, usesSdkLibVersionMajor,
                                    /*allowDuplicates=*/ true);

                            // We allow ":" delimiters in the SHA declaration as this is the format
                            // emitted by the certtool making it easy for developers to copy/paste.
                            // TODO(372862145): Add test for this replacement
                            usesSdkCertDigest = usesSdkCertDigest.replace(":", "").toLowerCase();

                            if ("".equals(usesSdkCertDigest)) {
                                // Test-only uses-sdk-library empty certificate digest override.
                                usesSdkCertDigest = SystemProperties.get(
                                        "debug.pm.uses_sdk_library_default_cert_digest", "");
                                // Validate the overridden digest.
                                try {
                                    HexEncoding.decode(usesSdkCertDigest, false);
                                } catch (IllegalArgumentException e) {
                                    usesSdkCertDigest = "";
                                }
                            }
                            // TODO(372862145): Add support for multiple signer
                            usesSdkLibrariesCertDigests = ArrayUtils.appendElement(String[].class,
                                    usesSdkLibrariesCertDigests, new String[]{usesSdkCertDigest},
                                    /*allowDuplicates=*/ true);
                            break;
                        case TAG_USES_STATIC_LIBRARY:
                            String usesStaticLibName = parser.getAttributeValue(
                                    ANDROID_RES_NAMESPACE, "name");
                            long usesStaticLibVersion = parser.getAttributeIntValue(
                                    ANDROID_RES_NAMESPACE, "version", -1);
                            String usesStaticLibCertDigest = parser.getAttributeValue(
                                    ANDROID_RES_NAMESPACE, "certDigest");

                            if (usesStaticLibName == null || usesStaticLibName.isBlank()
                                    || usesStaticLibVersion < 0
                                    || usesStaticLibCertDigest == null) {
                                return input.error(
                                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                        "Bad uses-static-library declaration name: "
                                                + usesStaticLibName
                                                + " version: " + usesStaticLibVersion
                                                + " certDigest: " + usesStaticLibCertDigest);
                            }

                            if (usesStaticLibraries.contains(usesStaticLibName)) {
                                return input.error(
                                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                        "Bad uses-sdk-library declaration. Depending on"
                                                + " multiple versions of static library: "
                                                + usesStaticLibName);
                            }

                            usesStaticLibraries.add(usesStaticLibName);
                            usesStaticLibrariesVersions = ArrayUtils.appendLong(
                                    usesStaticLibrariesVersions, usesStaticLibVersion,
                                    /*allowDuplicates=*/ true);

                            // We allow ":" delimiters in the SHA declaration as this is the format
                            // emitted by the certtool making it easy for developers to copy/paste.
                            // TODO(372862145): Add test for this replacement
                            usesStaticLibCertDigest =
                                    usesStaticLibCertDigest.replace(":", "").toLowerCase();

                            // TODO(372862145): Add support for multiple signer for app targeting
                            //  O-MR1
                            usesStaticLibrariesCertDigests = ArrayUtils.appendElement(
                                    String[].class, usesStaticLibrariesCertDigests,
                                    new String[]{usesStaticLibCertDigest},
                                    /*allowDuplicates=*/ true);
                            break;
                        case TAG_SDK_LIBRARY:
                            isSdkLibrary = true;
                            // Mirrors ParsingPackageUtils#parseSdkLibrary until lite and full
                            // parsing are combined
                            String sdkLibName = parser.getAttributeValue(
                                    ANDROID_RES_NAMESPACE, "name");
                            int sdkLibVersionMajor = parser.getAttributeIntValue(
                                        ANDROID_RES_NAMESPACE, "versionMajor", -1);
                            if (sdkLibName == null || sdkLibVersionMajor < 0) {
                                return input.error(
                                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                        "Bad sdk-library declaration name: " + sdkLibName
                                                + " version: " + sdkLibVersionMajor);
                            }
                            declaredLibraries.add(new SharedLibraryInfo(
                                    sdkLibName, sdkLibVersionMajor,
                                    SharedLibraryInfo.TYPE_SDK_PACKAGE));
                            break;
                        case TAG_STATIC_LIBRARY:
                            // Mirrors ParsingPackageUtils#parseStaticLibrary until lite and full
                            // parsing are combined
                            String staticLibName = parser.getAttributeValue(
                                    ANDROID_RES_NAMESPACE, "name");
                            int staticLibVersion = parser.getAttributeIntValue(
                                    ANDROID_RES_NAMESPACE, "version", -1);
                            int staticLibVersionMajor = parser.getAttributeIntValue(
                                    ANDROID_RES_NAMESPACE, "versionMajor", 0);
                            if (staticLibName == null || staticLibVersion < 0) {
                                return input.error("Bad static-library declaration name: "
                                        + staticLibName + " version: " + staticLibVersion);
                            }
                            declaredLibraries.add(new SharedLibraryInfo(staticLibName,
                                    PackageInfo.composeLongVersionCode(staticLibVersionMajor,
                                            staticLibVersion), SharedLibraryInfo.TYPE_STATIC));
                            break;
                        case TAG_LIBRARY:
                            // Mirrors ParsingPackageUtils#parseLibrary until lite and full parsing
                            // are combined
                            String libName = parser.getAttributeValue(
                                    ANDROID_RES_NAMESPACE, "name");
                            if (libName == null) {
                                return input.error("Bad library declaration name: null");
                            }
                            libName = libName.intern();
                            declaredLibraries.add(new SharedLibraryInfo(libName,
                                    SharedLibraryInfo.VERSION_UNDEFINED,
                                    SharedLibraryInfo.TYPE_DYNAMIC));
                            break;
                        case TAG_PROCESSES:
                            final int processesDepth = parser.getDepth();
                            int processesType;
                            while ((processesType = parser.next()) != XmlPullParser.END_DOCUMENT
                                    && (processesType != XmlPullParser.END_TAG
                                    || parser.getDepth() > processesDepth)) {
                                if (processesType == XmlPullParser.END_TAG
                                        || processesType == XmlPullParser.TEXT) {
                                    continue;
                                }

                                if (parser.getDepth() != processesDepth + 1) {
                                    // Search only under <processes>.
                                    continue;
                                }

                                if (parser.getName().equals(TAG_PROCESS)
                                        && Flags.enablePerProcessUseEmbeddedDexAttr()) {
                                    useEmbeddedDex |= parser.getAttributeBooleanValue(
                                            ANDROID_RES_NAMESPACE, "useEmbeddedDex", false);
                                }
                            }
                    }
                }
            } else if (TAG_OVERLAY.equals(parser.getName())) {
                requiredSystemPropertyName = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                        "requiredSystemPropertyName");
                requiredSystemPropertyValue = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                        "requiredSystemPropertyValue");
                targetPackage = parser.getAttributeValue(ANDROID_RES_NAMESPACE, "targetPackage");
                overlayIsStatic = parser.getAttributeBooleanValue(ANDROID_RES_NAMESPACE, "isStatic",
                        false);
                overlayPriority = parser.getAttributeIntValue(ANDROID_RES_NAMESPACE, "priority", 0);
            } else if (TAG_USES_SPLIT.equals(parser.getName())) {
                if (usesSplitName != null) {
                    Slog.w(TAG, "Only one <uses-split> permitted. Ignoring others.");
                    continue;
                }

                usesSplitName = parser.getAttributeValue(ANDROID_RES_NAMESPACE, "name");
                if (usesSplitName == null) {
                    return input.error(PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "<uses-split> tag requires 'android:name' attribute");
                }
            } else if (TAG_USES_SDK.equals(parser.getName())) {
                // Mirrors FrameworkParsingPackageUtils#parseUsesSdk until lite and full parsing is combined
                String minSdkVersionString = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                        "minSdkVersion");
                String targetSdkVersionString = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                        "targetSdkVersion");

                int minVer = DEFAULT_MIN_SDK_VERSION;
                String minCode = null;
                boolean minAssigned = false;
                int targetVer = DEFAULT_TARGET_SDK_VERSION;
                String targetCode = null;

                if (!TextUtils.isEmpty(minSdkVersionString)) {
                    try {
                        minVer = Integer.parseInt(minSdkVersionString);
                        minAssigned = true;
                    } catch (NumberFormatException ignored) {
                        minCode = minSdkVersionString;
                        minAssigned = !TextUtils.isEmpty(minCode);
                    }
                }

                if (!TextUtils.isEmpty(targetSdkVersionString)) {
                    try {
                        targetVer = Integer.parseInt(targetSdkVersionString);
                    } catch (NumberFormatException ignored) {
                        targetCode = targetSdkVersionString;
                        if (!minAssigned) {
                            minCode = targetCode;
                        }
                    }
                } else {
                    targetVer = minVer;
                    targetCode = minCode;
                }

                boolean allowUnknownCodenames = false;
                if ((flags & FrameworkParsingPackageUtils.PARSE_APK_IN_APEX) != 0) {
                    allowUnknownCodenames = true;
                }

                ParseResult<Integer> targetResult = FrameworkParsingPackageUtils.computeTargetSdkVersion(
                        targetVer, targetCode, SDK_CODENAMES, input,
                        allowUnknownCodenames);
                if (targetResult.isError()) {
                    return input.error(targetResult);
                }
                targetSdkVersion = targetResult.getResult();

                ParseResult<Integer> minResult = FrameworkParsingPackageUtils.computeMinSdkVersion(
                        minVer, minCode, SDK_VERSION, SDK_CODENAMES, input);
                if (minResult.isError()) {
                    return input.error(minResult);
                }
                minSdkVersion = minResult.getResult();
            }
        }

        // Check to see if overlay should be excluded based on system property condition
        if ((flags & FrameworkParsingPackageUtils.PARSE_IGNORE_OVERLAY_REQUIRED_SYSTEM_PROPERTY)
                == 0 && !FrameworkParsingPackageUtils.checkRequiredSystemProperties(
                requiredSystemPropertyName, requiredSystemPropertyValue)) {
            String message = "Skipping target and overlay pair " + targetPackage + " and "
                    + codePath + ": overlay ignored due to required system property: "
                    + requiredSystemPropertyName + " with value: " + requiredSystemPropertyValue;
            Slog.i(TAG, message);
            return input.skip(message);
        }

        return input.success(
                new ApkLite(codePath, packageSplit.first, packageSplit.second, isFeatureSplit,
                        configForSplit, usesSplitName, isSplitRequired, versionCode,
                        versionCodeMajor, revisionCode, installLocation, verifiers, signingDetails,
                        coreApp, debuggable, profilableByShell, multiArch, use32bitAbi,
                        useEmbeddedDex, extractNativeLibs, isolatedSplits, targetPackage,
                        overlayIsStatic, overlayPriority, requiredSystemPropertyName,
                        requiredSystemPropertyValue, minSdkVersion, targetSdkVersion,
                        rollbackDataPolicy, requiredSplitTypes.first, requiredSplitTypes.second,
                        hasDeviceAdminReceiver, isSdkLibrary, usesSdkLibraries,
                        usesSdkLibrariesVersionsMajor, usesSdkLibrariesCertDigests,
                        usesStaticLibraries, usesStaticLibrariesVersions,
                        usesStaticLibrariesCertDigests, updatableSystem, emergencyInstaller,
                        declaredLibraries));
    }

    private static boolean isDeviceAdminReceiver(
            XmlResourceParser parser, boolean applicationHasBindDeviceAdminPermission)
            throws XmlPullParserException, IOException {
        String permission = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                "permission");
        if (!applicationHasBindDeviceAdminPermission
                && !android.Manifest.permission.BIND_DEVICE_ADMIN.equals(permission)) {
            return false;
        }

        boolean hasDeviceAdminReceiver = false;
        final int depth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > depth)) {
            if (type == XmlPullParser.END_TAG
                    || type == XmlPullParser.TEXT) {
                continue;
            }
            if (parser.getDepth() != depth + 1) {
                // Search only under <receiver>.
                continue;
            }
            if (!hasDeviceAdminReceiver && "meta-data".equals(parser.getName())) {
                String name = parser.getAttributeValue(ANDROID_RES_NAMESPACE,
                        "name");
                if (DeviceAdminReceiver.DEVICE_ADMIN_META_DATA.equals(name)) {
                    hasDeviceAdminReceiver = true;
                }
            }
        }
        return hasDeviceAdminReceiver;
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
        if (!parser.getName().equals(TAG_MANIFEST)) {
            return input.error(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "No <manifest> tag");
        }

        final String packageName = parser.getAttributeValue(null, "package");
        if (!"android".equals(packageName)) {
            final ParseResult<?> nameResult = FrameworkParsingPackageUtils.validateName(input,
                    packageName, true, true);
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
                final ParseResult<?> nameResult = FrameworkParsingPackageUtils.validateName(input,
                        splitName, false, false);
                if (nameResult.isError()) {
                    return input.error(INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
                            "Invalid manifest split: " + nameResult.getErrorMessage());
                }
            }
        }

        return input.success(Pair.create(packageName.intern(),
                (splitName != null) ? splitName.intern() : splitName));
    }

    /**
     * Utility method that parses attributes android:requiredSplitTypes and android:splitTypes.
     */
    public static ParseResult<Pair<Set<String>, Set<String>>> parseRequiredSplitTypes(
            ParseInput input, XmlResourceParser parser) {
        Set<String> requiredSplitTypes = null;
        Set<String> splitTypes = null;
        String value = parser.getAttributeValue(ANDROID_RES_NAMESPACE, "requiredSplitTypes");
        if (!TextUtils.isEmpty(value)) {
            final ParseResult<Set<String>> result = separateAndValidateSplitTypes(input, value);
            if (result.isError()) {
                return input.error(result);
            }
            requiredSplitTypes = result.getResult();
        }

        value = parser.getAttributeValue(ANDROID_RES_NAMESPACE, "splitTypes");
        if (!TextUtils.isEmpty(value)) {
            final ParseResult<Set<String>> result = separateAndValidateSplitTypes(input, value);
            if (result.isError()) {
                return input.error(result);
            }
            splitTypes = result.getResult();
        }

        return input.success(Pair.create(requiredSplitTypes, splitTypes));
    }

    private static ParseResult<Set<String>> separateAndValidateSplitTypes(ParseInput input,
            String values) {
        final Set<String> ret = new ArraySet<>();
        for (String value : values.trim().split(",")) {
            final String type = value.trim();
            // Using requireFilename as true because it limits length of the name to the
            // {@link #MAX_FILE_NAME_SIZE}.
            final ParseResult<?> nameResult = FrameworkParsingPackageUtils.validateName(input, type,
                    false /* requireSeparator */, true /* requireFilename */);
            if (nameResult.isError()) {
                return input.error(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        "Invalid manifest split types: " + nameResult.getErrorMessage());
            }
            if (!ret.add(type)) {
                Slog.w(TAG, type + " was defined multiple times");
            }
        }
        return input.success(ret);
    }

    public static VerifierInfo parseVerifier(AttributeSet attrs) {
        String packageName = attrs.getAttributeValue(ANDROID_RES_NAMESPACE, "name");
        String encodedPublicKey = attrs.getAttributeValue(ANDROID_RES_NAMESPACE, "publicKey");

        if (packageName == null || packageName.length() == 0) {
            Slog.i(TAG, "verifier package name was null; skipping");
            return null;
        }

        final PublicKey publicKey = FrameworkParsingPackageUtils.parsePublicKey(encodedPublicKey);
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
