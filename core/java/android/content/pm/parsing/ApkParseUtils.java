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

import static android.content.pm.ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NOT_APK;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ConfigurationInfo;
import android.content.pm.FeatureGroupInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageItemInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.PackageParser.PackageParserException;
import android.content.pm.PackageParser.SigningDetails;
import android.content.pm.Signature;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.content.pm.split.DefaultSplitAssetLoader;
import android.content.pm.split.SplitAssetDependencyLoader;
import android.content.pm.split.SplitAssetLoader;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.util.apk.ApkSignatureVerifier;

import com.android.internal.R;
import com.android.internal.os.ClassLoaderFactory;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** @hide */
public class ApkParseUtils {

    // TODO(b/135203078): Consolidate log tags
    static final String TAG = "PackageParsing";

    /**
     * Parse the package at the given location. Automatically detects if the
     * package is a monolithic style (single APK file) or cluster style
     * (directory of APKs).
     * <p>
     * This performs sanity checking on cluster style packages, such as
     * requiring identical package name and version codes, a single base APK,
     * and unique split names.
     * <p>
     * Note that this <em>does not</em> perform signature verification; that
     * must be done separately in {@link #collectCertificates(ParsedPackage, boolean)}.
     *
     * If {@code useCaches} is true, the package parser might return a cached
     * result from a previous parse of the same {@code packageFile} with the same
     * {@code flags}. Note that this method does not check whether {@code packageFile}
     * has changed since the last parse, it's up to callers to do so.
     *
     * @see PackageParser#parsePackageLite(File, int)
     */
    public static ParsingPackage parsePackage(
            ParseInput parseInput,
            String[] separateProcesses,
            PackageParser.Callback callback,
            DisplayMetrics displayMetrics,
            boolean onlyCoreApps,
            File packageFile,
            int flags
    ) throws PackageParserException {
        if (packageFile.isDirectory()) {
            return parseClusterPackage(parseInput, separateProcesses, callback, displayMetrics,
                    onlyCoreApps, packageFile, flags);
        } else {
            return parseMonolithicPackage(parseInput, separateProcesses, callback, displayMetrics,
                    onlyCoreApps, packageFile, flags);
        }
    }

    /**
     * Parse all APKs contained in the given directory, treating them as a
     * single package. This also performs sanity checking, such as requiring
     * identical package name and version codes, a single base APK, and unique
     * split names.
     * <p>
     * Note that this <em>does not</em> perform signature verification; that
     * must be done separately in {@link #collectCertificates(ParsedPackage, boolean)}.
     */
    private static ParsingPackage parseClusterPackage(
            ParseInput parseInput,
            String[] separateProcesses,
            PackageParser.Callback callback,
            DisplayMetrics displayMetrics,
            boolean onlyCoreApps,
            File packageDir,
            int flags
    ) throws PackageParserException {
        final PackageParser.PackageLite lite = ApkLiteParseUtils.parseClusterPackageLite(packageDir,
                0);
        if (onlyCoreApps && !lite.coreApp) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "Not a coreApp: " + packageDir);
        }

        // Build the split dependency tree.
        SparseArray<int[]> splitDependencies = null;
        final SplitAssetLoader assetLoader;
        if (lite.isolatedSplits && !ArrayUtils.isEmpty(lite.splitNames)) {
            try {
                splitDependencies = SplitAssetDependencyLoader.createDependenciesFromPackage(lite);
                assetLoader = new SplitAssetDependencyLoader(lite, splitDependencies, flags);
            } catch (SplitAssetDependencyLoader.IllegalDependencyException e) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST, e.getMessage());
            }
        } else {
            assetLoader = new DefaultSplitAssetLoader(lite, flags);
        }

        try {
            final AssetManager assets = assetLoader.getBaseAssetManager();
            final File baseApk = new File(lite.baseCodePath);
            ParsingPackage parsingPackage = parseBaseApk(parseInput, separateProcesses, callback,
                    displayMetrics, baseApk, assets, flags);
            if (parsingPackage == null) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NOT_APK,
                        "Failed to parse base APK: " + baseApk);
            }

            if (!ArrayUtils.isEmpty(lite.splitNames)) {
                parsingPackage.asSplit(
                        lite.splitNames,
                        lite.splitCodePaths,
                        lite.splitRevisionCodes,
                        splitDependencies
                );
                final int num = lite.splitNames.length;

                for (int i = 0; i < num; i++) {
                    final AssetManager splitAssets = assetLoader.getSplitAssetManager(i);
                    parseSplitApk(parseInput, displayMetrics, separateProcesses, parsingPackage, i,
                            splitAssets, flags);
                }
            }

            return parsingPackage.setCodePath(packageDir.getCanonicalPath())
                    .setUse32BitAbi(lite.use32bitAbi);
        } catch (IOException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to get path: " + lite.baseCodePath, e);
        } finally {
            IoUtils.closeQuietly(assetLoader);
        }
    }

    /**
     * Parse the given APK file, treating it as as a single monolithic package.
     * <p>
     * Note that this <em>does not</em> perform signature verification; that
     * must be done separately in {@link #collectCertificates(AndroidPackage, boolean)}.
     */
    public static ParsingPackage parseMonolithicPackage(
            ParseInput parseInput,
            String[] separateProcesses,
            PackageParser.Callback callback,
            DisplayMetrics displayMetrics,
            boolean onlyCoreApps,
            File apkFile,
            int flags
    ) throws PackageParserException {
        final PackageParser.PackageLite lite = ApkLiteParseUtils.parseMonolithicPackageLite(apkFile,
                flags);
        if (onlyCoreApps) {
            if (!lite.coreApp) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        "Not a coreApp: " + apkFile);
            }
        }

        final SplitAssetLoader assetLoader = new DefaultSplitAssetLoader(lite, flags);
        try {
            return parseBaseApk(parseInput, separateProcesses, callback,
                    displayMetrics, apkFile, assetLoader.getBaseAssetManager(), flags)
                    .setCodePath(apkFile.getCanonicalPath())
                    .setUse32BitAbi(lite.use32bitAbi);
        } catch (IOException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to get path: " + apkFile, e);
        } finally {
            IoUtils.closeQuietly(assetLoader);
        }
    }

    private static ParsingPackage parseBaseApk(
            ParseInput parseInput,
            String[] separateProcesses,
            PackageParser.Callback callback,
            DisplayMetrics displayMetrics,
            File apkFile,
            AssetManager assets,
            int flags
    ) throws PackageParserException {
        final String apkPath = apkFile.getAbsolutePath();

        String volumeUuid = null;
        if (apkPath.startsWith(PackageParser.MNT_EXPAND)) {
            final int end = apkPath.indexOf('/', PackageParser.MNT_EXPAND.length());
            volumeUuid = apkPath.substring(PackageParser.MNT_EXPAND.length(), end);
        }

        if (PackageParser.DEBUG_JAR) Slog.d(TAG, "Scanning base APK: " + apkPath);

        XmlResourceParser parser = null;
        try {
            final int cookie = assets.findCookieForPath(apkPath);
            if (cookie == 0) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                        "Failed adding asset path: " + apkPath);
            }
            parser = assets.openXmlResourceParser(cookie, PackageParser.ANDROID_MANIFEST_FILENAME);
            final Resources res = new Resources(assets, displayMetrics, null);

            ParseResult result = parseBaseApk(parseInput, separateProcesses, callback, apkPath, res,
                    parser, flags);
            if (!result.isSuccess()) {
                throw new PackageParserException(result.getParseError(),
                        apkPath + " (at " + parser.getPositionDescription() + "): "
                                + result.getErrorMessage());
            }

            return result.getResultAndNull()
                    .setVolumeUuid(volumeUuid)
                    .setApplicationVolumeUuid(volumeUuid)
                    .setSigningDetails(SigningDetails.UNKNOWN);
        } catch (PackageParserException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to read manifest from " + apkPath, e);
        } finally {
            IoUtils.closeQuietly(parser);
        }
    }

    private static void parseSplitApk(
            ParseInput parseInput,
            DisplayMetrics displayMetrics,
            String[] separateProcesses,
            ParsingPackage parsingPackage,
            int splitIndex,
            AssetManager assets,
            int flags
    ) throws PackageParserException {
        final String apkPath = parsingPackage.getSplitCodePaths()[splitIndex];

        if (PackageParser.DEBUG_JAR) Slog.d(TAG, "Scanning split APK: " + apkPath);

        final Resources res;
        XmlResourceParser parser = null;
        try {
            // This must always succeed, as the path has been added to the AssetManager before.
            final int cookie = assets.findCookieForPath(apkPath);
            if (cookie == 0) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                        "Failed adding asset path: " + apkPath);
            }

            parser = assets.openXmlResourceParser(cookie, PackageParser.ANDROID_MANIFEST_FILENAME);
            res = new Resources(assets, displayMetrics, null);

            final String[] outError = new String[1];
            ParseResult parseResult = parseSplitApk(parseInput, separateProcesses, parsingPackage,
                    res, parser, flags, splitIndex, outError);
            if (!parseResult.isSuccess()) {
                throw new PackageParserException(parseResult.getParseError(),
                        apkPath + " (at " + parser.getPositionDescription() + "): "
                                + parseResult.getErrorMessage());
            }
        } catch (PackageParserException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to read manifest from " + apkPath, e);
        } finally {
            IoUtils.closeQuietly(parser);
        }
    }

    /**
     * Parse the manifest of a <em>base APK</em>. When adding new features you
     * need to consider whether they should be supported by split APKs and child
     * packages.
     *
     * @param apkPath  The package apk file path
     * @param res      The resources from which to resolve values
     * @param parser   The manifest parser
     * @param flags    Flags how to parse
     * @return Parsed package or null on error.
     */
    private static ParseResult parseBaseApk(
            ParseInput parseInput,
            String[] separateProcesses,
            PackageParser.Callback callback,
            String apkPath,
            Resources res,
            XmlResourceParser parser,
            int flags
    ) throws XmlPullParserException, IOException {
        final String splitName;
        final String pkgName;

        try {
            Pair<String, String> packageSplit = PackageParser.parsePackageSplitNames(parser,
                    parser);
            pkgName = packageSplit.first;
            splitName = packageSplit.second;

            if (!TextUtils.isEmpty(splitName)) {
                return parseInput.error(
                        PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
                        "Expected base APK, but found split " + splitName
                );
            }
        } catch (PackageParserException e) {
            return parseInput.error(PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME);
        }

        // TODO: Remove when manifest overlaying removed
        if (callback != null) {
            String[] overlayPaths = callback.getOverlayPaths(pkgName, apkPath);
            if (overlayPaths != null && overlayPaths.length > 0) {
                for (String overlayPath : overlayPaths) {
                    res.getAssets().addOverlayPath(overlayPath);
                }
            }
        }

        TypedArray manifestArray = null;

        try {
            manifestArray = res.obtainAttributes(parser, R.styleable.AndroidManifest);

            boolean isCoreApp = parser.getAttributeBooleanValue(null, "coreApp", false);

            ParsingPackage parsingPackage = PackageImpl.forParsing(
                    pkgName,
                    apkPath,
                    manifestArray,
                    isCoreApp
            );

            ParseResult result = parseBaseApkTags(parseInput, separateProcesses, callback,
                    parsingPackage, manifestArray, res, parser, flags);
            if (!result.isSuccess()) {
                return result;
            }

            return parseInput.success(parsingPackage);
        } finally {
            if (manifestArray != null) {
                manifestArray.recycle();
            }
        }
    }

    /**
     * Parse the manifest of a <em>split APK</em>.
     * <p>
     * Note that split APKs have many more restrictions on what they're capable
     * of doing, so many valid features of a base APK have been carefully
     * omitted here.
     *
     * @param parsingPackage builder to fill
     * @return false on failure
     */
    private static ParseResult parseSplitApk(
            ParseInput parseInput,
            String[] separateProcesses,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser,
            int flags,
            int splitIndex,
            String[] outError
    ) throws XmlPullParserException, IOException, PackageParserException {
        AttributeSet attrs = parser;

        // We parsed manifest tag earlier; just skip past it
        PackageParser.parsePackageSplitNames(parser, attrs);

        int type;

        boolean foundApp = false;

        int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(PackageParser.TAG_APPLICATION)) {
                if (foundApp) {
                    if (PackageParser.RIGID_PARSER) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                "<manifest> has more than one <application>"
                        );
                    } else {
                        Slog.w(TAG, "<manifest> has more than one <application>");
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                }

                foundApp = true;
                ParseResult parseResult = parseSplitApplication(parseInput, separateProcesses,
                        parsingPackage, res,
                        parser, flags,
                        splitIndex, outError);
                if (!parseResult.isSuccess()) {
                    return parseResult;
                }

            } else if (PackageParser.RIGID_PARSER) {
                return parseInput.error(
                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        "Bad element under <manifest>: " + parser.getName()
                );

            } else {
                Slog.w(TAG, "Unknown element under <manifest>: " + parser.getName()
                        + " at " + parsingPackage.getBaseCodePath() + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                continue;
            }
        }

        if (!foundApp) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_EMPTY,
                    "<manifest> does not contain an <application>"
            );
        }

        return parseInput.success(parsingPackage);
    }

    /**
     * Parse the {@code application} XML tree at the current parse location in a
     * <em>split APK</em> manifest.
     * <p>
     * Note that split APKs have many more restrictions on what they're capable
     * of doing, so many valid features of a base APK have been carefully
     * omitted here.
     */
    private static ParseResult parseSplitApplication(
            ParseInput parseInput,
            String[] separateProcesses,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser,
            int flags,
            int splitIndex,
            String[] outError
    ) throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestApplication);

        parsingPackage.setSplitHasCode(splitIndex, sa.getBoolean(
                R.styleable.AndroidManifestApplication_hasCode, true));

        final String classLoaderName = sa.getString(
                R.styleable.AndroidManifestApplication_classLoader);
        if (classLoaderName == null || ClassLoaderFactory.isValidClassLoaderName(classLoaderName)) {
            parsingPackage.setSplitClassLoaderName(splitIndex, classLoaderName);
        } else {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "Invalid class loader name: " + classLoaderName
            );
        }

        final int innerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            ComponentParseUtils.ParsedComponent parsedComponent = null;

            String tagName = parser.getName();
            switch (tagName) {
                case "activity":
                    ComponentParseUtils.ParsedActivity activity =
                            ComponentParseUtils.parseActivity(separateProcesses,
                                    parsingPackage,
                                    res, parser, flags,
                                    outError,
                                    false,
                                    parsingPackage.isBaseHardwareAccelerated());
                    if (activity == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    parsingPackage.addActivity(activity);
                    parsedComponent = activity;
                    break;
                case "receiver":
                    activity = ComponentParseUtils.parseActivity(
                            separateProcesses, parsingPackage,
                            res, parser, flags, outError,
                            true, false);
                    if (activity == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    parsingPackage.addReceiver(activity);
                    parsedComponent = activity;
                    break;
                case "service":
                    ComponentParseUtils.ParsedService s = ComponentParseUtils.parseService(
                            separateProcesses,
                            parsingPackage,
                            res, parser, flags, outError
                    );
                    if (s == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    parsingPackage.addService(s);
                    parsedComponent = s;
                    break;
                case "provider":
                    ComponentParseUtils.ParsedProvider p = ComponentParseUtils.parseProvider(
                            separateProcesses,
                            parsingPackage,
                            res, parser, flags, outError);
                    if (p == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    parsingPackage.addProvider(p);
                    parsedComponent = p;
                    break;
                case "activity-alias":
                    activity = ComponentParseUtils.parseActivityAlias(
                            parsingPackage,
                            res,
                            parser,
                            outError
                    );
                    if (activity == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    parsingPackage.addActivity(activity);
                    parsedComponent = activity;
                    break;
                case "meta-data":
                    // note: application meta-data is stored off to the side, so it can
                    // remain null in the primary copy (we like to avoid extra copies because
                    // it can be large)
                    Bundle appMetaData = parseMetaData(parsingPackage, res, parser,
                            parsingPackage.getAppMetaData(),
                            outError);
                    if (appMetaData == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    parsingPackage.setAppMetaData(appMetaData);
                    break;
                case "uses-static-library":
                    ParseResult parseResult = parseUsesStaticLibrary(parseInput, parsingPackage,
                            res, parser);
                    if (!parseResult.isSuccess()) {
                        return parseResult;
                    }

                    break;
                case "uses-library":
                    sa = res.obtainAttributes(parser, R.styleable.AndroidManifestUsesLibrary);

                    // Note: don't allow this value to be a reference to a resource
                    // that may change.
                    String lname = sa.getNonResourceString(
                            R.styleable.AndroidManifestUsesLibrary_name);
                    boolean req = sa.getBoolean(
                            R.styleable.AndroidManifestUsesLibrary_required, true);

                    sa.recycle();

                    if (lname != null) {
                        lname = lname.intern();
                        if (req) {
                            // Upgrade to treat as stronger constraint
                            parsingPackage.addUsesLibrary(lname)
                                    .removeUsesOptionalLibrary(lname);
                        } else {
                            // Ignore if someone already defined as required
                            if (!ArrayUtils.contains(parsingPackage.getUsesLibraries(), lname)) {
                                parsingPackage.addUsesOptionalLibrary(lname);
                            }
                        }
                    }

                    XmlUtils.skipCurrentTag(parser);
                    break;
                case "uses-package":
                    // Dependencies for app installers; we don't currently try to
                    // enforce this.
                    XmlUtils.skipCurrentTag(parser);
                    break;
                default:
                    if (!PackageParser.RIGID_PARSER) {
                        Slog.w(TAG, "Unknown element under <application>: " + tagName
                                + " at " + parsingPackage.getBaseCodePath() + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    } else {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                "Bad element under <application>: " + tagName
                        );
                    }
            }

            if (parsedComponent != null && parsedComponent.getSplitName() == null) {
                // If the loaded component did not specify a split, inherit the split name
                // based on the split it is defined in.
                // This is used to later load the correct split when starting this
                // component.
                parsedComponent.setSplitName(parsingPackage.getSplitNames()[splitIndex]);
            }
        }

        return parseInput.success(parsingPackage);
    }

    private static ParseResult parseBaseApkTags(
            ParseInput parseInput,
            String[] separateProcesses,
            PackageParser.Callback callback,
            ParsingPackage parsingPackage,
            TypedArray manifestArray,
            Resources res,
            XmlResourceParser parser,
            int flags
    ) throws XmlPullParserException, IOException {
        int type;
        boolean foundApp = false;

        TypedArray sa = manifestArray;

        ParseResult sharedUserResult = parseSharedUser(parseInput, parsingPackage, sa);
        if (!sharedUserResult.isSuccess()) {
            return sharedUserResult;
        }

        parseManifestAttributes(sa, parsingPackage, flags);

        int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();

            // All methods return a boolean, even if they can't fail. This can be enforced
            // by making this final and not assigned, forcing the switch to assign success
            // once in every branch.
            final boolean success;
            ParseResult parseResult = null;

            // TODO(b/135203078): Either use all booleans or all ParseResults
            // TODO(b/135203078): Convert to instance methods to share variables
            switch (tagName) {
                case PackageParser.TAG_APPLICATION:
                    if (foundApp) {
                        if (PackageParser.RIGID_PARSER) {
                            return parseInput.error(
                                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                    "<manifest> has more than one <application>"
                            );
                        } else {
                            Slog.w(TAG, "<manifest> has more than one <application>");
                            XmlUtils.skipCurrentTag(parser);
                            success = true;
                        }
                    } else {
                        foundApp = true;
                        parseResult = parseBaseApplication(parseInput, separateProcesses,
                                callback,
                                parsingPackage, res, parser, flags);
                        success = parseResult.isSuccess();
                    }
                    break;
                case PackageParser.TAG_OVERLAY:
                    parseResult = parseOverlay(parseInput, parsingPackage, res, parser);
                    success = parseResult.isSuccess();
                    break;
                case PackageParser.TAG_KEY_SETS:
                    parseResult = parseKeySets(parseInput, parsingPackage, res, parser);
                    success = parseResult.isSuccess();
                    break;
                case PackageParser.TAG_PERMISSION_GROUP:
                    parseResult = parsePermissionGroup(parseInput, parsingPackage, res,
                            parser);
                    success = parseResult.isSuccess();
                    break;
                case PackageParser.TAG_PERMISSION:
                    parseResult = parsePermission(parseInput, parsingPackage, res, parser);
                    success = parseResult.isSuccess();
                    break;
                case PackageParser.TAG_PERMISSION_TREE:
                    parseResult = parsePermissionTree(parseInput, parsingPackage, res, parser);
                    success = parseResult.isSuccess();
                    break;
                case PackageParser.TAG_USES_PERMISSION:
                case PackageParser.TAG_USES_PERMISSION_SDK_M:
                case PackageParser.TAG_USES_PERMISSION_SDK_23:
                    parseResult = parseUsesPermission(parseInput, parsingPackage, res, parser,
                            callback);
                    success = parseResult.isSuccess();
                    break;
                case PackageParser.TAG_USES_CONFIGURATION:
                    success = parseUsesConfiguration(parsingPackage, res, parser);
                    break;
                case PackageParser.TAG_USES_FEATURE:
                    success = parseUsesFeature(parsingPackage, res, parser);
                    break;
                case PackageParser.TAG_FEATURE_GROUP:
                    success = parseFeatureGroup(parsingPackage, res, parser);
                    break;
                case PackageParser.TAG_USES_SDK:
                    parseResult = parseUsesSdk(parseInput, parsingPackage, res, parser);
                    success = parseResult.isSuccess();
                    break;
                case PackageParser.TAG_SUPPORT_SCREENS:
                    success = parseSupportScreens(parsingPackage, res, parser);
                    break;
                case PackageParser.TAG_PROTECTED_BROADCAST:
                    success = parseProtectedBroadcast(parsingPackage, res, parser);
                    break;
                case PackageParser.TAG_INSTRUMENTATION:
                    parseResult = parseInstrumentation(parseInput, parsingPackage, res,
                            parser);
                    success = parseResult.isSuccess();
                    break;
                case PackageParser.TAG_ORIGINAL_PACKAGE:
                    success = parseOriginalPackage(parsingPackage, res, parser);
                    break;
                case PackageParser.TAG_ADOPT_PERMISSIONS:
                    success = parseAdoptPermissions(parsingPackage, res, parser);
                    break;
                case PackageParser.TAG_USES_GL_TEXTURE:
                case PackageParser.TAG_COMPATIBLE_SCREENS:
                case PackageParser.TAG_SUPPORTS_INPUT:
                case PackageParser.TAG_EAT_COMMENT:
                    // Just skip this tag
                    XmlUtils.skipCurrentTag(parser);
                    success = true;
                    break;
                case PackageParser.TAG_RESTRICT_UPDATE:
                    success = parseRestrictUpdateHash(flags, parsingPackage, res, parser);
                    break;
                case PackageParser.TAG_QUERIES:
                    parseResult = parseQueries(parseInput, parsingPackage, res, parser);
                    success = parseResult.isSuccess();
                    break;
                default:
                    parseResult = parseUnknownTag(parseInput, parsingPackage, parser);
                    success = parseResult.isSuccess();
                    break;
            }

            if (parseResult != null && !parseResult.isSuccess()) {
                return parseResult;
            }

            if (!success) {
                return parseResult;
            }
        }

        if (!foundApp && ArrayUtils.size(parsingPackage.getInstrumentations()) == 0) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_EMPTY,
                    "<manifest> does not contain an <application> or <instrumentation>"
            );
        }

        convertNewPermissions(parsingPackage);

        convertSplitPermissions(parsingPackage);

        // At this point we can check if an application is not supporting densities and hence
        // cannot be windowed / resized. Note that an SDK version of 0 is common for
        // pre-Doughnut applications.
        if (parsingPackage.usesCompatibilityMode()) {
            adjustPackageToBeUnresizeableAndUnpipable(parsingPackage);
        }

        return parseInput.success(parsingPackage);
    }

    private static ParseResult parseUnknownTag(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {
        if (PackageParser.RIGID_PARSER) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "Bad element under <manifest>: " + parser.getName()
            );
        } else {
            Slog.w(TAG, "Unknown element under <manifest>: " + parser.getName()
                    + " at " + parsingPackage.getBaseCodePath() + " "
                    + parser.getPositionDescription());
            XmlUtils.skipCurrentTag(parser);
            return parseInput.success(parsingPackage);
        }
    }

    private static ParseResult parseSharedUser(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            TypedArray manifestArray
    ) {
        String str = manifestArray.getNonConfigurationString(
                R.styleable.AndroidManifest_sharedUserId, 0);
        if (TextUtils.isEmpty(str)) {
            return parseInput.success(parsingPackage);
        }

        String nameError = validateName(str, true, true);
        if (nameError != null && !"android".equals(parsingPackage.getPackageName())) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID,
                    "<manifest> specifies bad sharedUserId name \"" + str + "\": "
                            + nameError
            );
        }

        int sharedUserLabel = manifestArray.getResourceId(
                R.styleable.AndroidManifest_sharedUserLabel, 0);
        parsingPackage.setSharedUserId(str.intern())
                .setSharedUserLabel(sharedUserLabel);

        return parseInput.success(parsingPackage);
    }

    private static void parseManifestAttributes(
            TypedArray manifestArray,
            ParsingPackage parsingPackage,
            int flags
    ) {
        int installLocation = manifestArray.getInteger(R.styleable.AndroidManifest_installLocation,
                PackageParser.PARSE_DEFAULT_INSTALL_LOCATION);

        final int targetSandboxVersion = manifestArray.getInteger(
                R.styleable.AndroidManifest_targetSandboxVersion,
                PackageParser.PARSE_DEFAULT_TARGET_SANDBOX);

        parsingPackage.setInstallLocation(installLocation)
                .setTargetSandboxVersion(targetSandboxVersion);

        /* Set the global "on SD card" flag */
        parsingPackage.setExternalStorage((flags & PackageParser.PARSE_EXTERNAL_STORAGE) != 0);

        parsingPackage.setIsolatedSplitLoading(manifestArray.getBoolean(
                R.styleable.AndroidManifest_isolatedSplits, false));
    }

    private static ParseResult parseKeySets(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws XmlPullParserException, IOException {
        // we've encountered the 'key-sets' tag
        // all the keys and keysets that we want must be defined here
        // so we're going to iterate over the parser and pull out the things we want
        int outerDepth = parser.getDepth();
        int currentKeySetDepth = -1;
        int type;
        String currentKeySet = null;
        ArrayMap<String, PublicKey> publicKeys = new ArrayMap<>();
        ArraySet<String> upgradeKeySets = new ArraySet<>();
        ArrayMap<String, ArraySet<String>> definedKeySets =
                new ArrayMap<>();
        ArraySet<String> improperKeySets = new ArraySet<>();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG) {
                if (parser.getDepth() == currentKeySetDepth) {
                    currentKeySet = null;
                    currentKeySetDepth = -1;
                }
                continue;
            }
            String tagName = parser.getName();
            if (tagName.equals("key-set")) {
                if (currentKeySet != null) {
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "Improperly nested 'key-set' tag at " + parser.getPositionDescription()
                    );
                }
                final TypedArray sa = res.obtainAttributes(parser,
                        R.styleable.AndroidManifestKeySet);
                final String keysetName = sa.getNonResourceString(
                        R.styleable.AndroidManifestKeySet_name);
                definedKeySets.put(keysetName, new ArraySet<>());
                currentKeySet = keysetName;
                currentKeySetDepth = parser.getDepth();
                sa.recycle();
            } else if (tagName.equals("public-key")) {
                if (currentKeySet == null) {
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "Improperly nested 'key-set' tag at " + parser.getPositionDescription()
                    );
                }
                final TypedArray sa = res.obtainAttributes(parser,
                        R.styleable.AndroidManifestPublicKey);
                final String publicKeyName = sa.getNonResourceString(
                        R.styleable.AndroidManifestPublicKey_name);
                final String encodedKey = sa.getNonResourceString(
                        R.styleable.AndroidManifestPublicKey_value);
                if (encodedKey == null && publicKeys.get(publicKeyName) == null) {
                    sa.recycle();
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "'public-key' " + publicKeyName + " must define a public-key value"
                                    + " on first use at " + parser.getPositionDescription()
                    );
                } else if (encodedKey != null) {
                    PublicKey currentKey = PackageParser.parsePublicKey(encodedKey);
                    if (currentKey == null) {
                        Slog.w(TAG, "No recognized valid key in 'public-key' tag at "
                                + parser.getPositionDescription() + " key-set " + currentKeySet
                                + " will not be added to the package's defined key-sets.");
                        sa.recycle();
                        improperKeySets.add(currentKeySet);
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    if (publicKeys.get(publicKeyName) == null
                            || publicKeys.get(publicKeyName).equals(currentKey)) {

                        /* public-key first definition, or matches old definition */
                        publicKeys.put(publicKeyName, currentKey);
                    } else {
                        sa.recycle();
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                "Value of 'public-key' " + publicKeyName
                                        + " conflicts with previously defined value at "
                                        + parser.getPositionDescription()
                        );
                    }
                }
                definedKeySets.get(currentKeySet).add(publicKeyName);
                sa.recycle();
                XmlUtils.skipCurrentTag(parser);
            } else if (tagName.equals("upgrade-key-set")) {
                final TypedArray sa = res.obtainAttributes(parser,
                        R.styleable.AndroidManifestUpgradeKeySet);
                String name = sa.getNonResourceString(
                        R.styleable.AndroidManifestUpgradeKeySet_name);
                upgradeKeySets.add(name);
                sa.recycle();
                XmlUtils.skipCurrentTag(parser);
            } else if (PackageParser.RIGID_PARSER) {
                return parseInput.error(
                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        "Bad element under <key-sets>: " + parser.getName()
                                + " at " + parsingPackage.getBaseCodePath() + " "
                                + parser.getPositionDescription()
                );
            } else {
                Slog.w(TAG, "Unknown element under <key-sets>: " + parser.getName()
                        + " at " + parsingPackage.getBaseCodePath() + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                continue;
            }
        }
        String packageName = parsingPackage.getPackageName();
        Set<String> publicKeyNames = publicKeys.keySet();
        if (publicKeyNames.removeAll(definedKeySets.keySet())) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "Package" + packageName + " AndroidManifest.xml "
                            + "'key-set' and 'public-key' names must be distinct."
            );
        }

        for (ArrayMap.Entry<String, ArraySet<String>> e : definedKeySets.entrySet()) {
            final String keySetName = e.getKey();
            if (e.getValue().size() == 0) {
                Slog.w(TAG, "Package" + packageName + " AndroidManifest.xml "
                        + "'key-set' " + keySetName + " has no valid associated 'public-key'."
                        + " Not including in package's defined key-sets.");
                continue;
            } else if (improperKeySets.contains(keySetName)) {
                Slog.w(TAG, "Package" + packageName + " AndroidManifest.xml "
                        + "'key-set' " + keySetName + " contained improper 'public-key'"
                        + " tags. Not including in package's defined key-sets.");
                continue;
            }

            for (String s : e.getValue()) {
                parsingPackage.addKeySet(keySetName, publicKeys.get(s));
            }
        }
        if (parsingPackage.getKeySetMapping().keySet().containsAll(upgradeKeySets)) {
            parsingPackage.setUpgradeKeySets(upgradeKeySets);
        } else {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "Package" + packageName + " AndroidManifest.xml "
                            + "does not define all 'upgrade-key-set's ."
            );
        }

        return parseInput.success(parsingPackage);
    }

    public static boolean parsePackageItemInfo(String packageName, PackageItemInfo outInfo,
            String[] outError, String tag, TypedArray sa, boolean nameRequired,
            int nameRes, int labelRes, int iconRes, int roundIconRes, int logoRes, int bannerRes) {
        // This case can only happen in unit tests where we sometimes need to create fakes
        // of various package parser data structures.
        if (sa == null) {
            outError[0] = tag + " does not contain any attributes";
            return false;
        }

        String name = sa.getNonConfigurationString(nameRes, 0);
        if (name == null) {
            if (nameRequired) {
                outError[0] = tag + " does not specify android:name";
                return false;
            }
        } else {
            String outInfoName = buildClassName(packageName, name);
            if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(outInfoName)) {
                outError[0] = tag + " invalid android:name";
                return false;
            }
            outInfo.name = outInfoName;
            if (outInfoName == null) {
                return false;
            }
        }

        int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(roundIconRes, 0) : 0;
        if (roundIconVal != 0) {
            outInfo.icon = roundIconVal;
            outInfo.nonLocalizedLabel = null;
        } else {
            int iconVal = sa.getResourceId(iconRes, 0);
            if (iconVal != 0) {
                outInfo.icon = iconVal;
                outInfo.nonLocalizedLabel = null;
            }
        }

        int logoVal = sa.getResourceId(logoRes, 0);
        if (logoVal != 0) {
            outInfo.logo = logoVal;
        }

        int bannerVal = sa.getResourceId(bannerRes, 0);
        if (bannerVal != 0) {
            outInfo.banner = bannerVal;
        }

        TypedValue v = sa.peekValue(labelRes);
        if (v != null && (outInfo.labelRes = v.resourceId) == 0) {
            outInfo.nonLocalizedLabel = v.coerceToString();
        }

        outInfo.packageName = packageName;

        return true;
    }

    private static ParseResult parsePackageItemInfo(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            String tag,
            TypedArray sa,
            boolean nameRequired,
            int nameRes,
            int labelRes,
            int iconRes,
            int roundIconRes,
            int logoRes,
            int bannerRes
    ) {
        // This case can only happen in unit tests where we sometimes need to create fakes
        // of various package parser data structures.
        if (sa == null) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    tag + " does not contain any attributes"
            );
        }

        String name = sa.getNonConfigurationString(nameRes, 0);
        if (name == null) {
            if (nameRequired) {
                return parseInput.error(
                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        tag + " does not specify android:name"
                );
            }
        } else {
            String packageName = parsingPackage.getPackageName();
            String outInfoName = buildClassName(packageName, name);
            if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(outInfoName)) {
                return parseInput.error(
                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        tag + " invalid android:name"
                );
            } else if (outInfoName == null) {
                return parseInput.error(
                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        "Empty class name in package " + packageName
                );
            }

            parsingPackage.setName(outInfoName);
        }

        int roundIconVal = PackageParser.sUseRoundIcon ? sa.getResourceId(roundIconRes, 0) : 0;
        if (roundIconVal != 0) {
            parsingPackage.setIcon(roundIconVal)
                    .setNonLocalizedLabel(null);
        } else {
            int iconVal = sa.getResourceId(iconRes, 0);
            if (iconVal != 0) {
                parsingPackage.setIcon(iconVal)
                        .setNonLocalizedLabel(null);
            }
        }

        int logoVal = sa.getResourceId(logoRes, 0);
        if (logoVal != 0) {
            parsingPackage.setLogo(logoVal);
        }

        int bannerVal = sa.getResourceId(bannerRes, 0);
        if (bannerVal != 0) {
            parsingPackage.setBanner(bannerVal);
        }

        TypedValue v = sa.peekValue(labelRes);
        if (v != null) {
            parsingPackage.setLabelRes(v.resourceId);
            if (v.resourceId == 0) {
                parsingPackage.setNonLocalizedLabel(v.coerceToString());
            }
        }

        return parseInput.success(parsingPackage);
    }

    private static ParseResult parsePermissionGroup(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws XmlPullParserException, IOException {
        // TODO(b/135203078): Remove, replace with ParseResult
        String[] outError = new String[1];

        ComponentParseUtils.ParsedPermissionGroup parsedPermissionGroup =
                ComponentParseUtils.parsePermissionGroup(parsingPackage,
                        res, parser, outError);

        if (parsedPermissionGroup == null || outError[0] != null) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    outError[0]
            );
        }

        parsingPackage.addPermissionGroup(parsedPermissionGroup);

        return parseInput.success(parsingPackage);
    }

    private static ParseResult parsePermission(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws XmlPullParserException, IOException {
        // TODO(b/135203078): Remove, replace with ParseResult
        String[] outError = new String[1];

        ComponentParseUtils.ParsedPermission parsedPermission =
                ComponentParseUtils.parsePermission(parsingPackage,
                        res, parser, outError);

        if (parsedPermission == null || outError[0] != null) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    outError[0]
            );
        }

        parsingPackage.addPermission(parsedPermission);

        return parseInput.success(parsingPackage);
    }

    private static ParseResult parsePermissionTree(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws XmlPullParserException, IOException {
        // TODO(b/135203078): Remove, replace with ParseResult
        String[] outError = new String[1];

        ComponentParseUtils.ParsedPermission parsedPermission =
                ComponentParseUtils.parsePermissionTree(parsingPackage,
                        res, parser, outError);

        if (parsedPermission == null || outError[0] != null) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    outError[0]
            );
        }

        parsingPackage.addPermission(parsedPermission);

        return parseInput.success(parsingPackage);
    }

    private static ParseResult parseUsesPermission(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser,
            PackageParser.Callback callback
    )
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestUsesPermission);

        // Note: don't allow this value to be a reference to a resource
        // that may change.
        String name = sa.getNonResourceString(
                R.styleable.AndroidManifestUsesPermission_name);

        int maxSdkVersion = 0;
        TypedValue val = sa.peekValue(
                R.styleable.AndroidManifestUsesPermission_maxSdkVersion);
        if (val != null) {
            if (val.type >= TypedValue.TYPE_FIRST_INT && val.type <= TypedValue.TYPE_LAST_INT) {
                maxSdkVersion = val.data;
            }
        }

        final String requiredFeature = sa.getNonConfigurationString(
                R.styleable.AndroidManifestUsesPermission_requiredFeature, 0);

        final String requiredNotfeature = sa.getNonConfigurationString(
                R.styleable.AndroidManifestUsesPermission_requiredNotFeature,
                0);

        sa.recycle();

        XmlUtils.skipCurrentTag(parser);

        // Can only succeed from here on out
        ParseResult success = parseInput.success(parsingPackage);

        if (name == null) {
            return success;
        }

        if ((maxSdkVersion != 0) && (maxSdkVersion < Build.VERSION.RESOURCES_SDK_INT)) {
            return success;
        }

        // Only allow requesting this permission if the platform supports the given feature.
        if (requiredFeature != null && callback != null && !callback.hasFeature(requiredFeature)) {
            return success;
        }

        // Only allow requesting this permission if the platform doesn't support the given feature.
        if (requiredNotfeature != null && callback != null
                && callback.hasFeature(requiredNotfeature)) {
            return success;
        }

        if (!parsingPackage.getRequestedPermissions().contains(name)) {
            parsingPackage.addRequestedPermission(name.intern());
        } else {
            Slog.w(TAG, "Ignoring duplicate uses-permissions/uses-permissions-sdk-m: "
                    + name + " in package: " + parsingPackage.getPackageName() + " at: "
                    + parser.getPositionDescription());
        }

        return success;
    }

    private static boolean parseUsesConfiguration(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {
        ConfigurationInfo cPref = new ConfigurationInfo();
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestUsesConfiguration);
        cPref.reqTouchScreen = sa.getInt(
                R.styleable.AndroidManifestUsesConfiguration_reqTouchScreen,
                Configuration.TOUCHSCREEN_UNDEFINED);
        cPref.reqKeyboardType = sa.getInt(
                R.styleable.AndroidManifestUsesConfiguration_reqKeyboardType,
                Configuration.KEYBOARD_UNDEFINED);
        if (sa.getBoolean(
                R.styleable.AndroidManifestUsesConfiguration_reqHardKeyboard,
                false)) {
            cPref.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD;
        }
        cPref.reqNavigation = sa.getInt(
                R.styleable.AndroidManifestUsesConfiguration_reqNavigation,
                Configuration.NAVIGATION_UNDEFINED);
        if (sa.getBoolean(
                R.styleable.AndroidManifestUsesConfiguration_reqFiveWayNav,
                false)) {
            cPref.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV;
        }
        sa.recycle();
        parsingPackage.addConfigPreference(cPref);

        XmlUtils.skipCurrentTag(parser);
        return true;
    }

    private static boolean parseUsesFeature(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {
        FeatureInfo fi = parseFeatureInfo(res, parser);
        parsingPackage.addReqFeature(fi);

        if (fi.name == null) {
            ConfigurationInfo cPref = new ConfigurationInfo();
            cPref.reqGlEsVersion = fi.reqGlEsVersion;
            parsingPackage.addConfigPreference(cPref);
        }

        XmlUtils.skipCurrentTag(parser);
        return true;
    }

    private static FeatureInfo parseFeatureInfo(Resources res, AttributeSet attrs) {
        FeatureInfo fi = new FeatureInfo();
        TypedArray sa = res.obtainAttributes(attrs,
                R.styleable.AndroidManifestUsesFeature);
        // Note: don't allow this value to be a reference to a resource
        // that may change.
        fi.name = sa.getNonResourceString(R.styleable.AndroidManifestUsesFeature_name);
        fi.version = sa.getInt(R.styleable.AndroidManifestUsesFeature_version, 0);
        if (fi.name == null) {
            fi.reqGlEsVersion = sa.getInt(R.styleable.AndroidManifestUsesFeature_glEsVersion,
                    FeatureInfo.GL_ES_VERSION_UNDEFINED);
        }
        if (sa.getBoolean(R.styleable.AndroidManifestUsesFeature_required, true)) {
            fi.flags |= FeatureInfo.FLAG_REQUIRED;
        }
        sa.recycle();
        return fi;
    }

    private static boolean parseFeatureGroup(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {
        FeatureGroupInfo group = new FeatureGroupInfo();
        ArrayList<FeatureInfo> features = null;
        final int innerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > innerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            final String innerTagName = parser.getName();
            if (innerTagName.equals("uses-feature")) {
                FeatureInfo featureInfo = parseFeatureInfo(res, parser);
                // FeatureGroups are stricter and mandate that
                // any <uses-feature> declared are mandatory.
                featureInfo.flags |= FeatureInfo.FLAG_REQUIRED;
                features = ArrayUtils.add(features, featureInfo);
            } else {
                Slog.w(TAG,
                        "Unknown element under <feature-group>: " + innerTagName +
                                " at " + parsingPackage.getBaseCodePath() + " " +
                                parser.getPositionDescription());
            }
            XmlUtils.skipCurrentTag(parser);
        }

        if (features != null) {
            group.features = new FeatureInfo[features.size()];
            group.features = features.toArray(group.features);
        }

        parsingPackage.addFeatureGroup(group);
        return true;
    }

    private static ParseResult parseUsesSdk(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {
        if (PackageParser.SDK_VERSION > 0) {
            TypedArray sa = res.obtainAttributes(parser,
                    R.styleable.AndroidManifestUsesSdk);

            int minVers = 1;
            String minCode = null;
            int targetVers = 0;
            String targetCode = null;

            TypedValue val = sa.peekValue(R.styleable.AndroidManifestUsesSdk_minSdkVersion);
            if (val != null) {
                if (val.type == TypedValue.TYPE_STRING && val.string != null) {
                    minCode = val.string.toString();
                } else {
                    // If it's not a string, it's an integer.
                    minVers = val.data;
                }
            }

            val = sa.peekValue(R.styleable.AndroidManifestUsesSdk_targetSdkVersion);
            if (val != null) {
                if (val.type == TypedValue.TYPE_STRING && val.string != null) {
                    targetCode = val.string.toString();
                    if (minCode == null) {
                        minCode = targetCode;
                    }
                } else {
                    // If it's not a string, it's an integer.
                    targetVers = val.data;
                }
            } else {
                targetVers = minVers;
                targetCode = minCode;
            }

            sa.recycle();

            // TODO(b/135203078): Remove, replace with ParseResult
            String[] outError = new String[1];
            final int minSdkVersion = PackageParser.computeMinSdkVersion(minVers,
                    minCode,
                    PackageParser.SDK_VERSION, PackageParser.SDK_CODENAMES, outError);
            if (minSdkVersion < 0) {
                return parseInput.error(
                        PackageManager.INSTALL_FAILED_OLDER_SDK
                );
            }

            final int targetSdkVersion = PackageParser.computeTargetSdkVersion(
                    targetVers,
                    targetCode, PackageParser.SDK_CODENAMES, outError);
            if (targetSdkVersion < 0) {
                return parseInput.error(
                        PackageManager.INSTALL_FAILED_OLDER_SDK
                );
            }

            parsingPackage.setMinSdkVersion(minSdkVersion)
                    .setTargetSdkVersion(targetSdkVersion);
        }

        XmlUtils.skipCurrentTag(parser);
        return parseInput.success(parsingPackage);
    }

    private static boolean parseRestrictUpdateHash(
            int flags,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {
        if ((flags & PackageParser.PARSE_IS_SYSTEM_DIR) != 0) {
            TypedArray sa = res.obtainAttributes(parser,
                    R.styleable.AndroidManifestRestrictUpdate);
            final String hash = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestRestrictUpdate_hash,
                    0);
            sa.recycle();

            if (hash != null) {
                final int hashLength = hash.length();
                final byte[] hashBytes = new byte[hashLength / 2];
                for (int i = 0; i < hashLength; i += 2) {
                    hashBytes[i / 2] = (byte) ((Character.digit(hash.charAt(i), 16)
                            << 4)
                            + Character.digit(hash.charAt(i + 1), 16));
                }
                parsingPackage.setRestrictUpdateHash(hashBytes);
            } else {
                parsingPackage.setRestrictUpdateHash(null);
            }
        }

        XmlUtils.skipCurrentTag(parser);
        return true;
    }

    private static ParseResult parseQueries(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {

        final int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG
                || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }
            if (parser.getName().equals("intent")) {
                String[] outError = new String[1];
                ComponentParseUtils.ParsedQueriesIntentInfo intentInfo =
                        ComponentParseUtils.parsedParsedQueriesIntentInfo(
                                parsingPackage, res, parser, outError
                        );
                if (intentInfo == null) {
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            outError[0]
                    );
                }

                Uri data = null;
                String dataType = null;
                String host = "";
                final int numActions = intentInfo.countActions();
                final int numSchemes = intentInfo.countDataSchemes();
                final int numTypes = intentInfo.countDataTypes();
                final int numHosts = intentInfo.getHosts().length;
                if ((numSchemes == 0 && numTypes == 0 && numActions == 0)) {
                    outError[0] = "intent tags must contain either an action or data.";
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            outError[0]
                    );
                }
                if (numActions > 1) {
                    outError[0] = "intent tag may have at most one action.";
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            outError[0]
                    );
                }
                if (numTypes > 1) {
                    outError[0] = "intent tag may have at most one data type.";
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            outError[0]
                    );
                }
                if (numSchemes > 1) {
                    outError[0] = "intent tag may have at most one data scheme.";
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            outError[0]
                    );
                }
                if (numHosts > 1) {
                    outError[0] = "intent tag may have at most one data host.";
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            outError[0]
                    );
                }
                Intent intent = new Intent();
                for (int i = 0, max = intentInfo.countCategories(); i < max; i++) {
                    intent.addCategory(intentInfo.getCategory(i));
                }
                if (numHosts == 1) {
                    host = intentInfo.getHosts()[0];
                }
                if (numSchemes == 1) {
                    data = new Uri.Builder()
                            .scheme(intentInfo.getDataScheme(0))
                            .authority(host)
                            .build();
                }
                if (numTypes == 1) {
                    dataType = intentInfo.getDataType(0);
                }
                intent.setDataAndType(data, dataType);
                if (numActions == 1) {
                    intent.setAction(intentInfo.getAction(0));
                }
                parsingPackage.addQueriesIntent(intent);
            } else if (parser.getName().equals("package")) {
                final TypedArray sa = res.obtainAttributes(parser,
                        R.styleable.AndroidManifestQueriesPackage);
                final String packageName =
                        sa.getString(R.styleable.AndroidManifestQueriesPackage_name);
                if (TextUtils.isEmpty(packageName)) {
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "Package name is missing from package tag."
                    );
                }
                parsingPackage.addQueriesPackage(packageName.intern());
            }
        }
        return parseInput.success(parsingPackage);
    }

    /**
     * Parse the {@code application} XML tree at the current parse location in a
     * <em>base APK</em> manifest.
     * <p>
     * When adding new features, carefully consider if they should also be
     * supported by split APKs.
     *
     * @hide
     */
    public static ParseResult parseBaseApplication(
            ParseInput parseInput,
            String[] separateProcesses,
            PackageParser.Callback callback,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser,
            int flags
    ) throws XmlPullParserException, IOException {
        final String pkgName = parsingPackage.getPackageName();

        // TODO(b/135203078): Remove, replace with ParseResult
        String[] outError = new String[1];
        TypedArray sa = null;

        try {
            sa = res.obtainAttributes(parser,
                    R.styleable.AndroidManifestApplication);


            parsingPackage
                    .setIconRes(
                            sa.getResourceId(R.styleable.AndroidManifestApplication_icon, 0))
                    .setRoundIconRes(
                            sa.getResourceId(R.styleable.AndroidManifestApplication_roundIcon, 0));

            ParseResult result = parsePackageItemInfo(
                    parseInput,
                    parsingPackage,
                    "<application>",
                    sa, false /*nameRequired*/,
                    R.styleable.AndroidManifestApplication_name,
                    R.styleable.AndroidManifestApplication_label,
                    R.styleable.AndroidManifestApplication_icon,
                    R.styleable.AndroidManifestApplication_roundIcon,
                    R.styleable.AndroidManifestApplication_logo,
                    R.styleable.AndroidManifestApplication_banner
            );
            if (!result.isSuccess()) {
                return result;
            }

            String name = parsingPackage.getName();
            if (name != null) {
                parsingPackage.setClassName(name);
            }

            String manageSpaceActivity = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestApplication_manageSpaceActivity,
                    Configuration.NATIVE_CONFIG_VERSION);
            if (manageSpaceActivity != null) {
                String manageSpaceActivityName = buildClassName(pkgName, manageSpaceActivity);

                if (manageSpaceActivityName == null) {
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "Empty class name in package " + pkgName
                    );
                }

                parsingPackage.setManageSpaceActivityName(manageSpaceActivityName);
            }

            boolean allowBackup = sa.getBoolean(
                    R.styleable.AndroidManifestApplication_allowBackup, true);
            parsingPackage.setAllowBackup(allowBackup);

            if (allowBackup) {
                // backupAgent, killAfterRestore, fullBackupContent, backupInForeground,
                // and restoreAnyVersion are only relevant if backup is possible for the
                // given application.
                String backupAgent = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestApplication_backupAgent,
                        Configuration.NATIVE_CONFIG_VERSION);
                if (backupAgent != null) {
                    String backupAgentName = buildClassName(pkgName, backupAgent);
                    if (backupAgentName == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                "Empty class name in package " + pkgName
                        );
                    }

                    if (PackageParser.DEBUG_BACKUP) {
                        Slog.v(TAG, "android:backupAgent = " + backupAgentName
                                + " from " + pkgName + "+" + backupAgent);
                    }

                    parsingPackage.setBackupAgentName(backupAgentName);

                    parsingPackage.setKillAfterRestore(sa.getBoolean(
                            R.styleable.AndroidManifestApplication_killAfterRestore, true));

                    parsingPackage.setRestoreAnyVersion(sa.getBoolean(
                            R.styleable.AndroidManifestApplication_restoreAnyVersion, false));

                    parsingPackage.setFullBackupOnly(sa.getBoolean(
                            R.styleable.AndroidManifestApplication_fullBackupOnly, false));

                    parsingPackage.setBackupInForeground(sa.getBoolean(
                            R.styleable.AndroidManifestApplication_backupInForeground,
                            false));
                }

                TypedValue v = sa.peekValue(
                        R.styleable.AndroidManifestApplication_fullBackupContent);
                int fullBackupContent = 0;

                if (v != null) {
                    fullBackupContent = v.resourceId;

                    if (v.resourceId == 0) {
                        if (PackageParser.DEBUG_BACKUP) {
                            Slog.v(TAG, "fullBackupContent specified as boolean=" +
                                    (v.data == 0 ? "false" : "true"));
                        }
                        // "false" => -1, "true" => 0
                        fullBackupContent = v.data == 0 ? -1 : 0;
                    }

                    parsingPackage.setFullBackupContent(fullBackupContent);
                }
                if (PackageParser.DEBUG_BACKUP) {
                    Slog.v(TAG, "fullBackupContent=" + fullBackupContent + " for " + pkgName);
                }
            }

            parsingPackage
                    .setTheme(
                            sa.getResourceId(R.styleable.AndroidManifestApplication_theme, 0))
                    .setDescriptionRes(
                            sa.getResourceId(R.styleable.AndroidManifestApplication_description,
                                    0));

            if (sa.getBoolean(
                    R.styleable.AndroidManifestApplication_persistent,
                    false)) {
                // Check if persistence is based on a feature being present
                final String requiredFeature = sa.getNonResourceString(R.styleable
                        .AndroidManifestApplication_persistentWhenFeatureAvailable);
                parsingPackage.setPersistent(requiredFeature == null
                        || callback.hasFeature(requiredFeature));
            }

            boolean requiredForAllUsers = sa.getBoolean(
                    R.styleable.AndroidManifestApplication_requiredForAllUsers,
                    false);
            parsingPackage.setRequiredForAllUsers(requiredForAllUsers);

            String restrictedAccountType = sa.getString(R.styleable
                    .AndroidManifestApplication_restrictedAccountType);
            if (restrictedAccountType != null && restrictedAccountType.length() > 0) {
                parsingPackage.setRestrictedAccountType(restrictedAccountType);
            }

            String requiredAccountType = sa.getString(R.styleable
                    .AndroidManifestApplication_requiredAccountType);
            if (requiredAccountType != null && requiredAccountType.length() > 0) {
                parsingPackage.setRequiredAccountType(requiredAccountType);
            }

            parsingPackage.setForceQueryable(
                    sa.getBoolean(R.styleable.AndroidManifestApplication_forceQueryable, false)
            );

            boolean debuggable = sa.getBoolean(
                    R.styleable.AndroidManifestApplication_debuggable,
                    false
            );

            parsingPackage.setDebuggable(debuggable);

            if (debuggable) {
                // Debuggable implies profileable
                parsingPackage.setProfileableByShell(true);
            }

            parsingPackage.setVmSafeMode(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_vmSafeMode, false));

            boolean baseHardwareAccelerated = sa.getBoolean(
                    R.styleable.AndroidManifestApplication_hardwareAccelerated,
                    parsingPackage.getTargetSdkVersion()
                            >= Build.VERSION_CODES.ICE_CREAM_SANDWICH);
            parsingPackage.setBaseHardwareAccelerated(baseHardwareAccelerated);

            parsingPackage.setHasCode(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_hasCode, true));

            parsingPackage.setAllowTaskReparenting(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_allowTaskReparenting, false));

            parsingPackage.setAllowClearUserData(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_allowClearUserData, true));

            parsingPackage.setTestOnly(sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestApplication_testOnly,
                    false));

            parsingPackage.setLargeHeap(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_largeHeap, false));

            parsingPackage.setUsesCleartextTraffic(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_usesCleartextTraffic,
                    parsingPackage.getTargetSdkVersion() < Build.VERSION_CODES.P));

            parsingPackage.setSupportsRtl(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_supportsRtl,
                    false /* default is no RTL support*/));

            parsingPackage.setMultiArch(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_multiArch, false));

            parsingPackage.setExtractNativeLibs(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_extractNativeLibs, true));

            parsingPackage.setUseEmbeddedDex(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_useEmbeddedDex, false));

            parsingPackage.setDefaultToDeviceProtectedStorage(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_defaultToDeviceProtectedStorage,
                    false));

            parsingPackage.setDirectBootAware(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_directBootAware, false));

            if (sa.hasValueOrEmpty(R.styleable.AndroidManifestApplication_resizeableActivity)) {
                parsingPackage.setActivitiesResizeModeResizeable(sa.getBoolean(
                        R.styleable.AndroidManifestApplication_resizeableActivity, true));
            } else {
                parsingPackage.setActivitiesResizeModeResizeableViaSdkVersion(
                        parsingPackage.getTargetSdkVersion() >= Build.VERSION_CODES.N);
            }

            parsingPackage.setAllowClearUserDataOnFailedRestore(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_allowClearUserDataOnFailedRestore,
                    true));


            parsingPackage.setAllowAudioPlaybackCapture(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_allowAudioPlaybackCapture,
                    parsingPackage.getTargetSdkVersion() >= Build.VERSION_CODES.Q));

            parsingPackage.setRequestLegacyExternalStorage(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_requestLegacyExternalStorage,
                    parsingPackage.getTargetSdkVersion() < Build.VERSION_CODES.Q));

            parsingPackage
                    .setMaxAspectRatio(
                            sa.getFloat(R.styleable.AndroidManifestApplication_maxAspectRatio, 0))
                    .setMinAspectRatio(
                            sa.getFloat(R.styleable.AndroidManifestApplication_minAspectRatio, 0))
                    .setNetworkSecurityConfigRes(sa.getResourceId(
                            R.styleable.AndroidManifestApplication_networkSecurityConfig, 0))
                    .setCategory(sa.getInt(R.styleable.AndroidManifestApplication_appCategory,
                            ApplicationInfo.CATEGORY_UNDEFINED));

            String str;
            str = sa.getNonConfigurationString(
                    R.styleable.AndroidManifestApplication_permission, 0);
            parsingPackage.setPermission((str != null && str.length() > 0) ? str.intern() : null);

            if (parsingPackage.getTargetSdkVersion() >= Build.VERSION_CODES.FROYO) {
                str = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestApplication_taskAffinity,
                        Configuration.NATIVE_CONFIG_VERSION);
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                str = sa.getNonResourceString(
                        R.styleable.AndroidManifestApplication_taskAffinity);
            }
            String packageName = parsingPackage.getPackageName();
            String taskAffinity = PackageParser.buildTaskAffinityName(packageName,
                    packageName,
                    str, outError);

            if (outError[0] != null) {
                return parseInput.error(
                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        outError[0]
                );
            }

            parsingPackage.setTaskAffinity(taskAffinity);
            String factory = sa.getNonResourceString(
                    R.styleable.AndroidManifestApplication_appComponentFactory);
            if (factory != null) {
                String appComponentFactory = buildClassName(packageName, factory);
                if (appComponentFactory == null) {
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "Empty class name in package " + pkgName
                    );
                }

                parsingPackage.setAppComponentFactory(appComponentFactory);
            }

            parsingPackage.setUsesNonSdkApi(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_usesNonSdkApi, false));

            parsingPackage.setHasFragileUserData(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_hasFragileUserData, false));

            CharSequence pname;
            if (parsingPackage.getTargetSdkVersion() >= Build.VERSION_CODES.FROYO) {
                pname = sa.getNonConfigurationString(
                        R.styleable.AndroidManifestApplication_process,
                        Configuration.NATIVE_CONFIG_VERSION);
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                pname = sa.getNonResourceString(
                        R.styleable.AndroidManifestApplication_process);
            }
            String processName = PackageParser.buildProcessName(packageName, null, pname, flags,
                    separateProcesses, outError);

            if (outError[0] != null) {
                return parseInput.error(
                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        outError[0]
                );
            }

            parsingPackage
                    .setProcessName(processName)
                    .setEnabled(
                            sa.getBoolean(R.styleable.AndroidManifestApplication_enabled,
                                    true));

            parsingPackage.setIsGame(sa.getBoolean(
                    R.styleable.AndroidManifestApplication_isGame, false));

            boolean cantSaveState = sa.getBoolean(
                    R.styleable.AndroidManifestApplication_cantSaveState, false);
            parsingPackage.setCantSaveState(cantSaveState);
            if (cantSaveState) {
                // A heavy-weight application can not be in a custom process.
                // We can do direct compare because we intern all strings.
                if (processName != null && !processName.equals(packageName)) {
                    return parseInput.error(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "cantSaveState applications can not use custom processes"
                    );
                }
            }

            String classLoaderName = sa.getString(
                    R.styleable.AndroidManifestApplication_classLoader);
            parsingPackage
                    .setUiOptions(sa.getInt(R.styleable.AndroidManifestApplication_uiOptions, 0))
                    .setClassLoaderName(classLoaderName)
                    .setZygotePreloadName(
                            sa.getString(R.styleable.AndroidManifestApplication_zygotePreloadName));

            if (classLoaderName != null
                    && !ClassLoaderFactory.isValidClassLoaderName(classLoaderName)) {
                return parseInput.error(
                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        "Invalid class loader name: " + classLoaderName
                );
            }
        } finally {
            if (sa != null) {
                sa.recycle();
            }
        }

        final int innerDepth = parser.getDepth();
        int type;
        boolean hasActivityOrder = false;
        boolean hasReceiverOrder = false;
        boolean hasServiceOrder = false;

        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            switch (tagName) {
                case "activity":
                    ComponentParseUtils.ParsedActivity activity =
                            ComponentParseUtils.parseActivity(separateProcesses,
                                    parsingPackage,
                                    res, parser, flags,
                                    outError, false,
                                    parsingPackage.isBaseHardwareAccelerated());
                    if (activity == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    hasActivityOrder |= (activity.order != 0);
                    parsingPackage.addActivity(activity);
                    break;
                case "receiver":
                    activity = ComponentParseUtils.parseActivity(separateProcesses,
                            parsingPackage,
                            res, parser,
                            flags, outError,
                            true, false);
                    if (activity == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    hasReceiverOrder |= (activity.order != 0);
                    parsingPackage.addReceiver(activity);
                    break;
                case "service":
                    ComponentParseUtils.ParsedService s = ComponentParseUtils.parseService(
                            separateProcesses,
                            parsingPackage,
                            res, parser, flags,
                            outError);
                    if (s == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    hasServiceOrder |= (s.order != 0);
                    parsingPackage.addService(s);
                    break;
                case "provider":
                    ComponentParseUtils.ParsedProvider p = ComponentParseUtils.parseProvider(
                            separateProcesses,
                            parsingPackage,
                            res, parser, flags,
                            outError
                    );
                    if (p == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    parsingPackage.addProvider(p);
                    break;
                case "activity-alias":
                    activity = ComponentParseUtils.parseActivityAlias(
                            parsingPackage,
                            res,
                            parser,
                            outError
                    );
                    if (activity == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    hasActivityOrder |= (activity.order != 0);
                    parsingPackage.addActivity(activity);
                    break;
                case "meta-data":
                    // note: application meta-data is stored off to the side, so it can
                    // remain null in the primary copy (we like to avoid extra copies because
                    // it can be large)
                    Bundle appMetaData = parseMetaData(parsingPackage, res, parser,
                            parsingPackage.getAppMetaData(),
                            outError);
                    if (appMetaData == null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                outError[0]
                        );
                    }

                    parsingPackage.setAppMetaData(appMetaData);
                    break;
                case "static-library":
                    sa = res.obtainAttributes(parser,
                            R.styleable.AndroidManifestStaticLibrary);

                    // Note: don't allow this value to be a reference to a resource
                    // that may change.
                    String lname = sa.getNonResourceString(
                            R.styleable.AndroidManifestStaticLibrary_name);
                    final int version = sa.getInt(
                            R.styleable.AndroidManifestStaticLibrary_version, -1);
                    final int versionMajor = sa.getInt(
                            R.styleable.AndroidManifestStaticLibrary_versionMajor,
                            0);

                    sa.recycle();

                    // Since the app canot run without a static lib - fail if malformed
                    if (lname == null || version < 0) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                "Bad static-library declaration name: " + lname
                                        + " version: " + version
                        );
                    }

                    if (parsingPackage.getSharedUserId() != null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID,
                                "sharedUserId not allowed in static shared library"
                        );
                    }

                    if (parsingPackage.getStaticSharedLibName() != null) {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                "Multiple static-shared libs for package " + pkgName
                        );
                    }

                    parsingPackage.setStaticSharedLibName(lname.intern());
                    if (version >= 0) {
                        parsingPackage.setStaticSharedLibVersion(
                                PackageInfo.composeLongVersionCode(versionMajor, version));
                    } else {
                        parsingPackage.setStaticSharedLibVersion(version);
                    }
                    parsingPackage.setStaticSharedLibrary(true);

                    XmlUtils.skipCurrentTag(parser);

                    break;
                case "library":
                    sa = res.obtainAttributes(parser,
                            R.styleable.AndroidManifestLibrary);

                    // Note: don't allow this value to be a reference to a resource
                    // that may change.
                    lname = sa.getNonResourceString(
                            R.styleable.AndroidManifestLibrary_name);

                    sa.recycle();

                    if (lname != null) {
                        lname = lname.intern();
                        if (!ArrayUtils.contains(parsingPackage.getLibraryNames(), lname)) {
                            parsingPackage.addLibraryName(lname);
                        }
                    }

                    XmlUtils.skipCurrentTag(parser);

                    break;
                case "uses-static-library":
                    ParseResult parseResult = parseUsesStaticLibrary(parseInput, parsingPackage,
                            res, parser);
                    if (!parseResult.isSuccess()) {
                        return parseResult;
                    }
                    break;
                case "uses-library":
                    sa = res.obtainAttributes(parser,
                            R.styleable.AndroidManifestUsesLibrary);

                    // Note: don't allow this value to be a reference to a resource
                    // that may change.
                    lname = sa.getNonResourceString(
                            R.styleable.AndroidManifestUsesLibrary_name);
                    boolean req = sa.getBoolean(
                            R.styleable.AndroidManifestUsesLibrary_required,
                            true);

                    sa.recycle();

                    if (lname != null) {
                        lname = lname.intern();
                        if (req) {
                            parsingPackage.addUsesLibrary(lname);
                        } else {
                            parsingPackage.addUsesOptionalLibrary(lname);
                        }
                    }

                    XmlUtils.skipCurrentTag(parser);

                    break;
                case "uses-package":
                    // Dependencies for app installers; we don't currently try to
                    // enforce this.
                    XmlUtils.skipCurrentTag(parser);
                    break;
                case "profileable":
                    sa = res.obtainAttributes(parser,
                            R.styleable.AndroidManifestProfileable);
                    if (sa.getBoolean(
                            R.styleable.AndroidManifestProfileable_shell, false)) {
                        parsingPackage.setProfileableByShell(true);
                    }
                    XmlUtils.skipCurrentTag(parser);

                default:
                    if (!PackageParser.RIGID_PARSER) {
                        Slog.w(TAG, "Unknown element under <application>: " + tagName
                                + " at " + parsingPackage.getBaseCodePath() + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    } else {
                        return parseInput.error(
                                PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                                "Bad element under <application>: " + tagName
                        );
                    }
            }
        }

        if (TextUtils.isEmpty(parsingPackage.getStaticSharedLibName())) {
            // Add a hidden app detail activity to normal apps which forwards user to App Details
            // page.
            ComponentParseUtils.ParsedActivity a = generateAppDetailsHiddenActivity(
                    parsingPackage,
                    outError
            );
            // Ignore errors here
            parsingPackage.addActivity(a);
        }

        if (hasActivityOrder) {
            parsingPackage.sortActivities();
        }
        if (hasReceiverOrder) {
            parsingPackage.sortReceivers();
        }
        if (hasServiceOrder) {
            parsingPackage.sortServices();
        }
        // Must be ran after the entire {@link ApplicationInfo} has been fully processed and after
        // every activity info has had a chance to set it from its attributes.
        setMaxAspectRatio(parsingPackage);
        setMinAspectRatio(parsingPackage, callback);

        parsingPackage.setHasDomainUrls(hasDomainURLs(parsingPackage));

        return parseInput.success(parsingPackage);
    }

    private static ParseResult parseUsesStaticLibrary(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestUsesStaticLibrary);

        // Note: don't allow this value to be a reference to a resource that may change.
        String lname = sa.getNonResourceString(
                R.styleable.AndroidManifestUsesLibrary_name);
        final int version = sa.getInt(
                R.styleable.AndroidManifestUsesStaticLibrary_version, -1);
        String certSha256Digest = sa.getNonResourceString(com.android.internal.R.styleable
                .AndroidManifestUsesStaticLibrary_certDigest);
        sa.recycle();

        // Since an APK providing a static shared lib can only provide the lib - fail if malformed
        if (lname == null || version < 0 || certSha256Digest == null) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "Bad uses-static-library declaration name: " + lname + " version: "
                            + version + " certDigest" + certSha256Digest
            );
        }

        // Can depend only on one version of the same library
        List<String> usesStaticLibraries = parsingPackage.getUsesStaticLibraries();
        if (usesStaticLibraries != null && usesStaticLibraries.contains(lname)) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "Depending on multiple versions of static library " + lname
            );
        }

        lname = lname.intern();
        // We allow ":" delimiters in the SHA declaration as this is the format
        // emitted by the certtool making it easy for developers to copy/paste.
        certSha256Digest = certSha256Digest.replace(":", "").toLowerCase();

        // Fot apps targeting O-MR1 we require explicit enumeration of all certs.
        String[] additionalCertSha256Digests = EmptyArray.STRING;
        if (parsingPackage.getTargetSdkVersion() >= Build.VERSION_CODES.O_MR1) {
            // TODO(b/135203078): Remove, replace with ParseResult
            String[] outError = new String[1];
            additionalCertSha256Digests = parseAdditionalCertificates(res, parser, outError);
            if (additionalCertSha256Digests == null || outError[0] != null) {
                return parseInput.error(
                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        outError[0]
                );
            }
        } else {
            XmlUtils.skipCurrentTag(parser);
        }

        final String[] certSha256Digests = new String[additionalCertSha256Digests.length + 1];
        certSha256Digests[0] = certSha256Digest;
        System.arraycopy(additionalCertSha256Digests, 0, certSha256Digests,
                1, additionalCertSha256Digests.length);

        parsingPackage.addUsesStaticLibrary(lname)
                .addUsesStaticLibraryVersion(version)
                .addUsesStaticLibraryCertDigests(certSha256Digests);

        return parseInput.success(parsingPackage);
    }

    private static String[] parseAdditionalCertificates(
            Resources resources,
            XmlResourceParser parser,
            String[] outError
    ) throws XmlPullParserException, IOException {
        String[] certSha256Digests = EmptyArray.STRING;

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            final String nodeName = parser.getName();
            if (nodeName.equals("additional-certificate")) {
                final TypedArray sa = resources.obtainAttributes(parser, com.android.internal.
                        R.styleable.AndroidManifestAdditionalCertificate);
                String certSha256Digest = sa.getNonResourceString(com.android.internal.
                        R.styleable.AndroidManifestAdditionalCertificate_certDigest);
                sa.recycle();

                if (TextUtils.isEmpty(certSha256Digest)) {
                    outError[0] = "Bad additional-certificate declaration with empty"
                            + " certDigest:" + certSha256Digest;
                    XmlUtils.skipCurrentTag(parser);
                    sa.recycle();
                    return null;
                }

                // We allow ":" delimiters in the SHA declaration as this is the format
                // emitted by the certtool making it easy for developers to copy/paste.
                certSha256Digest = certSha256Digest.replace(":", "").toLowerCase();
                certSha256Digests = ArrayUtils.appendElement(String.class,
                        certSha256Digests, certSha256Digest);
            } else {
                XmlUtils.skipCurrentTag(parser);
            }
        }

        return certSha256Digests;
    }

    /**
     * Generate activity object that forwards user to App Details page automatically.
     * This activity should be invisible to user and user should not know or see it.
     *
     * @hide
     */
    @NonNull
    private static ComponentParseUtils.ParsedActivity generateAppDetailsHiddenActivity(
            ParsingPackage parsingPackage,
            String[] outError
    ) {
        String packageName = parsingPackage.getPackageName();
        String processName = parsingPackage.getProcessName();
        boolean hardwareAccelerated = parsingPackage.isBaseHardwareAccelerated();
        int uiOptions = parsingPackage.getUiOptions();

        // Build custom App Details activity info instead of parsing it from xml
        ComponentParseUtils.ParsedActivity activity = new ComponentParseUtils.ParsedActivity();
        activity.setPackageName(packageName);

        activity.theme = android.R.style.Theme_NoDisplay;
        activity.exported = true;
        activity.className = PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME;
        activity.setProcessName(processName, processName);
        activity.uiOptions = uiOptions;
        activity.taskAffinity = PackageParser.buildTaskAffinityName(packageName,
                packageName,
                ":app_details", outError);
        activity.enabled = true;
        activity.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
        activity.documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NONE;
        activity.maxRecents = ActivityTaskManager.getDefaultAppRecentsLimitStatic();
        activity.configChanges = PackageParser.getActivityConfigChanges(0, 0);
        activity.softInputMode = 0;
        activity.persistableMode = ActivityInfo.PERSIST_NEVER;
        activity.screenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
        activity.resizeMode = RESIZE_MODE_FORCE_RESIZEABLE;
        activity.lockTaskLaunchMode = 0;
        activity.directBootAware = false;
        activity.rotationAnimation = ROTATION_ANIMATION_UNSPECIFIED;
        activity.colorMode = ActivityInfo.COLOR_MODE_DEFAULT;
        if (hardwareAccelerated) {
            activity.flags |= ActivityInfo.FLAG_HARDWARE_ACCELERATED;
        }

        return activity;
    }

    /**
     * Check if one of the IntentFilter as both actions DEFAULT / VIEW and a HTTP/HTTPS data URI
     */
    private static boolean hasDomainURLs(
            ParsingPackage parsingPackage) {
        final List<ComponentParseUtils.ParsedActivity> activities = parsingPackage.getActivities();
        final int countActivities = activities.size();
        for (int n = 0; n < countActivities; n++) {
            ComponentParseUtils.ParsedActivity activity = activities.get(n);
            List<ComponentParseUtils.ParsedActivityIntentInfo> filters = activity.intents;
            if (filters == null) continue;
            final int countFilters = filters.size();
            for (int m = 0; m < countFilters; m++) {
                ComponentParseUtils.ParsedActivityIntentInfo aii = filters.get(m);
                if (!aii.hasAction(Intent.ACTION_VIEW)) continue;
                if (!aii.hasAction(Intent.ACTION_DEFAULT)) continue;
                if (aii.hasDataScheme(IntentFilter.SCHEME_HTTP) ||
                        aii.hasDataScheme(IntentFilter.SCHEME_HTTPS)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Sets the max aspect ratio of every child activity that doesn't already have an aspect
     * ratio set.
     */
    private static void setMaxAspectRatio(
            ParsingPackage parsingPackage) {
        // Default to (1.86) 16.7:9 aspect ratio for pre-O apps and unset for O and greater.
        // NOTE: 16.7:9 was the max aspect ratio Android devices can support pre-O per the CDD.
        float maxAspectRatio = parsingPackage.getTargetSdkVersion() < O
                ? PackageParser.DEFAULT_PRE_O_MAX_ASPECT_RATIO : 0;

        float packageMaxAspectRatio = parsingPackage.getMaxAspectRatio();
        if (packageMaxAspectRatio != 0) {
            // Use the application max aspect ration as default if set.
            maxAspectRatio = packageMaxAspectRatio;
        } else {
            Bundle appMetaData = parsingPackage.getAppMetaData();
            if (appMetaData != null && appMetaData.containsKey(
                    PackageParser.METADATA_MAX_ASPECT_RATIO)) {
                maxAspectRatio = appMetaData.getFloat(PackageParser.METADATA_MAX_ASPECT_RATIO,
                        maxAspectRatio);
            }
        }

        if (parsingPackage.getActivities() != null) {
            for (ComponentParseUtils.ParsedActivity activity : parsingPackage.getActivities()) {
                // If the max aspect ratio for the activity has already been set, skip.
                if (activity.hasMaxAspectRatio()) {
                    continue;
                }

                // By default we prefer to use a values defined on the activity directly than values
                // defined on the application. We do not check the styled attributes on the activity
                // as it would have already been set when we processed the activity. We wait to
                // process the meta data here since this method is called at the end of processing
                // the application and all meta data is guaranteed.
                final float activityAspectRatio = activity.metaData != null
                        ? activity.metaData.getFloat(PackageParser.METADATA_MAX_ASPECT_RATIO,
                        maxAspectRatio)
                        : maxAspectRatio;

                activity.setMaxAspectRatio(activity.resizeMode, activityAspectRatio);
            }
        }
    }

    /**
     * Sets the min aspect ratio of every child activity that doesn't already have an aspect
     * ratio set.
     */
    private static void setMinAspectRatio(
            ParsingPackage parsingPackage,
            PackageParser.Callback callback
    ) {
        final float minAspectRatio;
        float packageMinAspectRatio = parsingPackage.getMinAspectRatio();
        if (packageMinAspectRatio != 0) {
            // Use the application max aspect ration as default if set.
            minAspectRatio = packageMinAspectRatio;
        } else {
            // Default to (1.33) 4:3 aspect ratio for pre-Q apps and unset for Q and greater.
            // NOTE: 4:3 was the min aspect ratio Android devices can support pre-Q per the CDD,
            // except for watches which always supported 1:1.
            minAspectRatio = parsingPackage.getTargetSdkVersion() >= Build.VERSION_CODES.Q
                    ? 0
                    : (callback != null && callback.hasFeature(FEATURE_WATCH))
                            ? PackageParser.DEFAULT_PRE_Q_MIN_ASPECT_RATIO_WATCH
                            : PackageParser.DEFAULT_PRE_Q_MIN_ASPECT_RATIO;
        }

        if (parsingPackage.getActivities() != null) {
            for (ComponentParseUtils.ParsedActivity activity : parsingPackage.getActivities()) {
                if (activity.hasMinAspectRatio()) {
                    continue;
                }
                activity.setMinAspectRatio(activity.resizeMode, minAspectRatio);
            }
        }
    }

    private static ParseResult parseOverlay(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {

        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestResourceOverlay);
        String target = sa.getString(
                R.styleable.AndroidManifestResourceOverlay_targetPackage);
        String targetName = sa.getString(
                R.styleable.AndroidManifestResourceOverlay_targetName);
        String category = sa.getString(
                R.styleable.AndroidManifestResourceOverlay_category);
        int priority = sa.getInt(R.styleable.AndroidManifestResourceOverlay_priority,
                0);
        boolean isStatic = sa.getBoolean(
                R.styleable.AndroidManifestResourceOverlay_isStatic, false);

        if (target == null) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "<overlay> does not specify a target package"
            );
        }

        if (priority < 0 || priority > 9999) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "<overlay> priority must be between 0 and 9999"
            );
        }

        // check to see if overlay should be excluded based on system property condition
        String propName = sa.getString(
                R.styleable.AndroidManifestResourceOverlay_requiredSystemPropertyName);
        String propValue = sa.getString(
                R.styleable.AndroidManifestResourceOverlay_requiredSystemPropertyValue);
        if (!checkOverlayRequiredSystemProperty(propName, propValue)) {
            Slog.i(TAG, "Skipping target and overlay pair " + target + " and "
                    + parsingPackage.getBaseCodePath()
                    + ": overlay ignored due to required system property: "
                    + propName + " with value: " + propValue);
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "Skipping target and overlay pair " + target + " and "
                            + parsingPackage.getBaseCodePath()
                            + ": overlay ignored due to required system property: "
                            + propName + " with value: " + propValue
            );
        }

        parsingPackage
                .setIsOverlay(true)
                .setOverlayTarget(target)
                .setOverlayTargetName(targetName)
                .setOverlayCategory(category)
                .setOverlayPriority(priority)
                .setOverlayIsStatic(isStatic);

        sa.recycle();

        XmlUtils.skipCurrentTag(parser);
        return parseInput.success(parsingPackage);
    }

    private static boolean parseProtectedBroadcast(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestProtectedBroadcast);

        // Note: don't allow this value to be a reference to a resource
        // that may change.
        String name = sa.getNonResourceString(R.styleable.AndroidManifestProtectedBroadcast_name);

        sa.recycle();

        if (name != null) {
            parsingPackage.addProtectedBroadcast(name);
        }

        XmlUtils.skipCurrentTag(parser);
        return true;
    }

    private static boolean parseSupportScreens(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestSupportsScreens);

        int requiresSmallestWidthDp = sa.getInteger(
                R.styleable.AndroidManifestSupportsScreens_requiresSmallestWidthDp,
                0);
        int compatibleWidthLimitDp = sa.getInteger(
                R.styleable.AndroidManifestSupportsScreens_compatibleWidthLimitDp,
                0);
        int largestWidthLimitDp = sa.getInteger(
                R.styleable.AndroidManifestSupportsScreens_largestWidthLimitDp,
                0);

        // This is a trick to get a boolean and still able to detect
        // if a value was actually set.
        parsingPackage
                .setSupportsSmallScreens(
                        sa.getInteger(R.styleable.AndroidManifestSupportsScreens_smallScreens, 1))
                .setSupportsNormalScreens(
                        sa.getInteger(R.styleable.AndroidManifestSupportsScreens_normalScreens, 1))
                .setSupportsLargeScreens(
                        sa.getInteger(R.styleable.AndroidManifestSupportsScreens_largeScreens, 1))
                .setSupportsXLargeScreens(
                        sa.getInteger(R.styleable.AndroidManifestSupportsScreens_xlargeScreens, 1))
                .setResizeable(
                        sa.getInteger(R.styleable.AndroidManifestSupportsScreens_resizeable, 1))
                .setAnyDensity(
                        sa.getInteger(R.styleable.AndroidManifestSupportsScreens_anyDensity, 1))
                .setRequiresSmallestWidthDp(requiresSmallestWidthDp)
                .setCompatibleWidthLimitDp(compatibleWidthLimitDp)
                .setLargestWidthLimitDp(largestWidthLimitDp);

        sa.recycle();

        XmlUtils.skipCurrentTag(parser);
        return true;
    }

    private static ParseResult parseInstrumentation(
            ParseInput parseInput,
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws XmlPullParserException, IOException {
        // TODO(b/135203078): Remove, replace with ParseResult
        String[] outError = new String[1];

        ComponentParseUtils.ParsedInstrumentation parsedInstrumentation =
                ComponentParseUtils.parseInstrumentation(parsingPackage,
                        res, parser, outError);

        if (parsedInstrumentation == null || outError[0] != null) {
            return parseInput.error(
                    PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    outError[0]
            );
        }

        parsingPackage.addInstrumentation(parsedInstrumentation);

        return parseInput.success(parsingPackage);
    }

    private static boolean parseOriginalPackage(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestOriginalPackage);

        String orig = sa.getNonConfigurationString(
                R.styleable.AndroidManifestOriginalPackage_name,
                0);
        if (!parsingPackage.getPackageName().equals(orig)) {
            if (parsingPackage.getOriginalPackages() == null) {
                parsingPackage.setRealPackage(parsingPackage.getPackageName());
            }
            parsingPackage.addOriginalPackage(orig);
        }

        sa.recycle();

        XmlUtils.skipCurrentTag(parser);
        return true;
    }

    private static boolean parseAdoptPermissions(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser
    ) throws IOException, XmlPullParserException {
        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestOriginalPackage);

        String name = sa.getNonConfigurationString(
                R.styleable.AndroidManifestOriginalPackage_name,
                0);

        sa.recycle();

        if (name != null) {
            parsingPackage.addAdoptPermission(name);
        }

        XmlUtils.skipCurrentTag(parser);
        return true;
    }

    private static void convertNewPermissions(
            ParsingPackage packageToParse) {
        final int NP = PackageParser.NEW_PERMISSIONS.length;
        StringBuilder newPermsMsg = null;
        for (int ip = 0; ip < NP; ip++) {
            final PackageParser.NewPermissionInfo npi
                    = PackageParser.NEW_PERMISSIONS[ip];
            if (packageToParse.getTargetSdkVersion() >= npi.sdkVersion) {
                break;
            }
            if (!packageToParse.getRequestedPermissions().contains(npi.name)) {
                if (newPermsMsg == null) {
                    newPermsMsg = new StringBuilder(128);
                    newPermsMsg.append(packageToParse.getPackageName());
                    newPermsMsg.append(": compat added ");
                } else {
                    newPermsMsg.append(' ');
                }
                newPermsMsg.append(npi.name);
                packageToParse.addRequestedPermission(npi.name);
                packageToParse.addImplicitPermission(npi.name);
            }
        }
        if (newPermsMsg != null) {
            Slog.i(TAG, newPermsMsg.toString());
        }
    }

    private static void convertSplitPermissions(ParsingPackage packageToParse) {
        List<SplitPermissionInfoParcelable> splitPermissions;

        try {
            splitPermissions = ActivityThread.getPermissionManager().getSplitPermissions();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        final int listSize = splitPermissions.size();
        for (int is = 0; is < listSize; is++) {
            final SplitPermissionInfoParcelable spi = splitPermissions.get(is);
            List<String> requestedPermissions = packageToParse.getRequestedPermissions();
            if (packageToParse.getTargetSdkVersion() >= spi.getTargetSdk()
                    || !requestedPermissions.contains(spi.getSplitPermission())) {
                continue;
            }
            final List<String> newPerms = spi.getNewPermissions();
            for (int in = 0; in < newPerms.size(); in++) {
                final String perm = newPerms.get(in);
                if (!requestedPermissions.contains(perm)) {
                    packageToParse.addRequestedPermission(perm);
                    packageToParse.addImplicitPermission(perm);
                }
            }
        }
    }

    private static boolean checkOverlayRequiredSystemProperty(String propName, String propValue) {
        if (TextUtils.isEmpty(propName) || TextUtils.isEmpty(propValue)) {
            if (!TextUtils.isEmpty(propName) || !TextUtils.isEmpty(propValue)) {
                // malformed condition - incomplete
                Slog.w(TAG, "Disabling overlay - incomplete property :'" + propName
                        + "=" + propValue + "' - require both requiredSystemPropertyName"
                        + " AND requiredSystemPropertyValue to be specified.");
                return false;
            }
            // no valid condition set - so no exclusion criteria, overlay will be included.
            return true;
        }

        // check property value - make sure it is both set and equal to expected value
        final String currValue = SystemProperties.get(propName);
        return (currValue != null && currValue.equals(propValue));
    }

    /**
     * This is a pre-density application which will get scaled - instead of being pixel perfect.
     * This type of application is not resizable.
     *
     * @param parsingPackage The package which needs to be marked as unresizable.
     */
    private static void adjustPackageToBeUnresizeableAndUnpipable(
            ParsingPackage parsingPackage) {
        if (parsingPackage.getActivities() != null) {
            for (ComponentParseUtils.ParsedActivity a : parsingPackage.getActivities()) {
                a.resizeMode = RESIZE_MODE_UNRESIZEABLE;
                a.flags &= ~FLAG_SUPPORTS_PICTURE_IN_PICTURE;
            }
        }
    }

    private static String validateName(String name, boolean requireSeparator,
            boolean requireFilename) {
        final int N = name.length();
        boolean hasSep = false;
        boolean front = true;
        for (int i = 0; i < N; i++) {
            final char c = name.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) {
                front = false;
                continue;
            }
            if (!front) {
                if ((c >= '0' && c <= '9') || c == '_') {
                    continue;
                }
            }
            if (c == '.') {
                hasSep = true;
                front = true;
                continue;
            }
            return "bad character '" + c + "'";
        }
        if (requireFilename && !FileUtils.isValidExtFilename(name)) {
            return "Invalid filename";
        }
        return hasSep || !requireSeparator
                ? null : "must have at least one '.' separator";
    }

    public static Bundle parseMetaData(
            ParsingPackage parsingPackage,
            Resources res,
            XmlResourceParser parser, Bundle data, String[] outError)
            throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(parser,
                R.styleable.AndroidManifestMetaData);

        if (data == null) {
            data = new Bundle();
        }

        String name = sa.getNonConfigurationString(
                R.styleable.AndroidManifestMetaData_name, 0);
        if (name == null) {
            outError[0] = "<meta-data> requires an android:name attribute";
            sa.recycle();
            return null;
        }

        name = name.intern();

        TypedValue v = sa.peekValue(
                R.styleable.AndroidManifestMetaData_resource);
        if (v != null && v.resourceId != 0) {
            //Slog.i(TAG, "Meta data ref " + name + ": " + v);
            data.putInt(name, v.resourceId);
        } else {
            v = sa.peekValue(
                    R.styleable.AndroidManifestMetaData_value);
            //Slog.i(TAG, "Meta data " + name + ": " + v);
            if (v != null) {
                if (v.type == TypedValue.TYPE_STRING) {
                    CharSequence cs = v.coerceToString();
                    data.putString(name, cs != null ? cs.toString() : null);
                } else if (v.type == TypedValue.TYPE_INT_BOOLEAN) {
                    data.putBoolean(name, v.data != 0);
                } else if (v.type >= TypedValue.TYPE_FIRST_INT
                        && v.type <= TypedValue.TYPE_LAST_INT) {
                    data.putInt(name, v.data);
                } else if (v.type == TypedValue.TYPE_FLOAT) {
                    data.putFloat(name, v.getFloat());
                } else {
                    if (!PackageParser.RIGID_PARSER) {
                        Slog.w(TAG,
                                "<meta-data> only supports string, integer, float, color, "
                                        + "boolean, and resource reference types: "
                                        + parser.getName() + " at "
                                        + parsingPackage.getBaseCodePath() + " "
                                        + parser.getPositionDescription());
                    } else {
                        outError[0] =
                                "<meta-data> only supports string, integer, float, color, "
                                        + "boolean, and resource reference types";
                        data = null;
                    }
                }
            } else {
                outError[0] = "<meta-data> requires an android:value or android:resource attribute";
                data = null;
            }
        }

        sa.recycle();

        XmlUtils.skipCurrentTag(parser);

        return data;
    }

    /**
     * Collect certificates from all the APKs described in the given package,
     * populating {@link AndroidPackageWrite#setSigningDetails(SigningDetails)}. Also asserts that
     * all APK contents are signed correctly and consistently.
     */
    public static void collectCertificates(AndroidPackage pkg, boolean skipVerify)
            throws PackageParserException {
        pkg.mutate().setSigningDetails(SigningDetails.UNKNOWN);

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "collectCertificates");
        try {
            pkg.mutate().setSigningDetails(collectCertificates(
                    pkg.getBaseCodePath(),
                    skipVerify,
                    pkg.isStaticSharedLibrary(),
                    pkg.getSigningDetails()
            ));

            String[] splitCodePaths = pkg.getSplitCodePaths();
            if (!ArrayUtils.isEmpty(splitCodePaths)) {
                for (int i = 0; i < splitCodePaths.length; i++) {
                    pkg.mutate().setSigningDetails(collectCertificates(
                            splitCodePaths[i],
                            skipVerify,
                            pkg.isStaticSharedLibrary(),
                            pkg.getSigningDetails()
                    ));
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    public static SigningDetails collectCertificates(
            String baseCodePath,
            boolean skipVerify,
            boolean isStaticSharedLibrary,
            @NonNull SigningDetails existingSigningDetails
    ) throws PackageParserException {
        int minSignatureScheme = SigningDetails.SignatureSchemeVersion.JAR;
        if (isStaticSharedLibrary) {
            // must use v2 signing scheme
            minSignatureScheme = SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2;
        }
        SigningDetails verified;
        if (skipVerify) {
            // systemDir APKs are already trusted, save time by not verifying
            verified = ApkSignatureVerifier.unsafeGetCertsWithoutVerification(
                    baseCodePath, minSignatureScheme);
        } else {
            verified = ApkSignatureVerifier.verify(baseCodePath, minSignatureScheme);
        }

        // Verify that entries are signed consistently with the first pkg
        // we encountered. Note that for splits, certificates may have
        // already been populated during an earlier parse of a base APK.
        if (existingSigningDetails == SigningDetails.UNKNOWN) {
            return verified;
        } else {
            if (!Signature.areExactMatch(existingSigningDetails.signatures, verified.signatures)) {
                throw new PackageParserException(
                        INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                        baseCodePath + " has mismatched certificates");
            }

            return existingSigningDetails;
        }
    }

    @Nullable
    public static String buildClassName(String pkg, CharSequence clsSeq) {
        if (clsSeq == null || clsSeq.length() <= 0) {
            return null;
        }
        String cls = clsSeq.toString();
        char c = cls.charAt(0);
        if (c == '.') {
            return pkg + cls;
        }
        if (cls.indexOf('.') < 0) {
            StringBuilder b = new StringBuilder(pkg);
            b.append('.');
            b.append(cls);
            return b.toString();
        }
        return cls;
    }

    public interface ParseInput {
        ParseResult success(ParsingPackage result);

        ParseResult error(int parseError);

        ParseResult error(int parseError, String errorMessage);
    }

    public static class ParseResult implements ParseInput {

        private static final boolean DEBUG_FILL_STACK_TRACE = false;

        private ParsingPackage result;

        private int parseError;
        private String errorMessage;

        public ParseInput reset() {
            this.result = null;
            this.parseError = PackageManager.INSTALL_SUCCEEDED;
            this.errorMessage = null;
            return this;
        }

        @Override
        public ParseResult success(ParsingPackage result) {
            if (parseError != PackageManager.INSTALL_SUCCEEDED || errorMessage != null) {
                throw new IllegalStateException("Cannot set to success after set to error");
            }
            this.result = result;
            return this;
        }

        @Override
        public ParseResult error(int parseError) {
            return error(parseError, null);
        }

        @Override
        public ParseResult error(int parseError, String errorMessage) {
            this.parseError = parseError;
            this.errorMessage = errorMessage;

            if (DEBUG_FILL_STACK_TRACE) {
                this.errorMessage += Arrays.toString(new Exception().getStackTrace());
            }

            return this;
        }

        public ParsingPackage getResultAndNull() {
            ParsingPackage result = this.result;
            this.result = null;
            return result;
        }

        public boolean isSuccess() {
            return parseError == PackageManager.INSTALL_SUCCEEDED;
        }

        public int getParseError() {
            return parseError;
        }

        public String getErrorMessage() {
            return errorMessage;
        }
    }
}
