/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.content.pm;

import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.FLAG_SUPPORTS_PICTURE_IN_PICTURE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_FORCE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ActivityInfo.RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.content.pm.ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.ApplicationInfo.FLAG_SUSPENDED;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE;
import static android.content.pm.PackageManager.FEATURE_WATCH;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_BAD_MANIFEST;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_NOT_APK;
import static android.content.pm.PackageManager.INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION;
import static android.os.Build.VERSION_CODES.O;
import static android.os.Trace.TRACE_TAG_PACKAGE_MANAGER;
import static android.view.WindowManager.LayoutParams.ROTATION_ANIMATION_UNSPECIFIED;

import android.annotation.IntDef;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.annotation.TestApi;
import android.apex.ApexInfo;
import android.app.ActivityTaskManager;
import android.app.ActivityThread;
import android.app.ResourcesManager;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.ComponentName;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.permission.SplitPermissionInfoParcelable;
import android.content.pm.split.DefaultSplitAssetLoader;
import android.content.pm.split.SplitAssetDependencyLoader;
import android.content.pm.split.SplitAssetLoader;
import android.content.res.ApkAssets;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.content.res.XmlResourceParser;
import android.os.Build;
import android.os.Bundle;
import android.os.FileUtils;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PatternMatcher;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.Trace;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.PackageUtils;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TypedValue;
import android.util.apk.ApkSignatureVerifier;
import android.view.Display;
import android.view.Gravity;

import com.android.internal.R;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.ClassLoaderFactory;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;
import libcore.util.EmptyArray;
import libcore.util.HexEncoding;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Constructor;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.spec.EncodedKeySpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Parser for package files (APKs) on disk. This supports apps packaged either
 * as a single "monolithic" APK, or apps packaged as a "cluster" of multiple
 * APKs in a single directory.
 * <p>
 * Apps packaged as multiple APKs always consist of a single "base" APK (with a
 * {@code null} split name) and zero or more "split" APKs (with unique split
 * names). Any subset of those split APKs are a valid install, as long as the
 * following constraints are met:
 * <ul>
 * <li>All APKs must have the exact same package name, version code, and signing
 * certificates.
 * <li>All APKs must have unique split names.
 * <li>All installations must contain a single base APK.
 * </ul>
 *
 * @hide
 */
public class PackageParser {

    public static final boolean DEBUG_JAR = false;
    public static final boolean DEBUG_PARSER = false;
    public static final boolean DEBUG_BACKUP = false;
    public static final boolean LOG_PARSE_TIMINGS = Build.IS_DEBUGGABLE;
    public static final int LOG_PARSE_TIMINGS_THRESHOLD_MS = 100;

    private static final String PROPERTY_CHILD_PACKAGES_ENABLED =
            "persist.sys.child_packages_enabled";

    public static final boolean MULTI_PACKAGE_APK_ENABLED = Build.IS_DEBUGGABLE &&
            SystemProperties.getBoolean(PROPERTY_CHILD_PACKAGES_ENABLED, false);

    public static final float DEFAULT_PRE_O_MAX_ASPECT_RATIO = 1.86f;
    public static final float DEFAULT_PRE_Q_MIN_ASPECT_RATIO = 1.333f;
    public static final float DEFAULT_PRE_Q_MIN_ASPECT_RATIO_WATCH = 1f;

    private static final int DEFAULT_MIN_SDK_VERSION = 1;
    private static final int DEFAULT_TARGET_SDK_VERSION = 0;

    // TODO: switch outError users to PackageParserException
    // TODO: refactor "codePath" to "apkPath"

    /** File name in an APK for the Android manifest. */
    public static final String ANDROID_MANIFEST_FILENAME = "AndroidManifest.xml";

    /** Path prefix for apps on expanded storage */
    public static final String MNT_EXPAND = "/mnt/expand/";

    public static final String TAG_ADOPT_PERMISSIONS = "adopt-permissions";
    public static final String TAG_APPLICATION = "application";
    public static final String TAG_COMPATIBLE_SCREENS = "compatible-screens";
    public static final String TAG_EAT_COMMENT = "eat-comment";
    public static final String TAG_FEATURE_GROUP = "feature-group";
    public static final String TAG_INSTRUMENTATION = "instrumentation";
    public static final String TAG_KEY_SETS = "key-sets";
    public static final String TAG_MANIFEST = "manifest";
    public static final String TAG_ORIGINAL_PACKAGE = "original-package";
    public static final String TAG_OVERLAY = "overlay";
    public static final String TAG_PACKAGE = "package";
    public static final String TAG_PACKAGE_VERIFIER = "package-verifier";
    public static final String TAG_ATTRIBUTION = "attribution";
    public static final String TAG_PERMISSION = "permission";
    public static final String TAG_PERMISSION_GROUP = "permission-group";
    public static final String TAG_PERMISSION_TREE = "permission-tree";
    public static final String TAG_PROTECTED_BROADCAST = "protected-broadcast";
    public static final String TAG_QUERIES = "queries";
    public static final String TAG_RESTRICT_UPDATE = "restrict-update";
    public static final String TAG_SUPPORT_SCREENS = "supports-screens";
    public static final String TAG_SUPPORTS_INPUT = "supports-input";
    public static final String TAG_USES_CONFIGURATION = "uses-configuration";
    public static final String TAG_USES_FEATURE = "uses-feature";
    public static final String TAG_USES_GL_TEXTURE = "uses-gl-texture";
    public static final String TAG_USES_PERMISSION = "uses-permission";
    public static final String TAG_USES_PERMISSION_SDK_23 = "uses-permission-sdk-23";
    public static final String TAG_USES_PERMISSION_SDK_M = "uses-permission-sdk-m";
    public static final String TAG_USES_SDK = "uses-sdk";
    public static final String TAG_USES_SPLIT = "uses-split";

    public static final String METADATA_MAX_ASPECT_RATIO = "android.max_aspect";
    public static final String METADATA_ACTIVITY_WINDOW_LAYOUT_AFFINITY =
            "android.activity_window_layout_affinity";

    /**
     * Bit mask of all the valid bits that can be set in recreateOnConfigChanges.
     * @hide
     */
    private static final int RECREATE_ON_CONFIG_CHANGES_MASK =
            ActivityInfo.CONFIG_MCC | ActivityInfo.CONFIG_MNC;

    // These are the tags supported by child packages
    public static final Set<String> CHILD_PACKAGE_TAGS = new ArraySet<>();
    static {
        CHILD_PACKAGE_TAGS.add(TAG_APPLICATION);
        CHILD_PACKAGE_TAGS.add(TAG_COMPATIBLE_SCREENS);
        CHILD_PACKAGE_TAGS.add(TAG_EAT_COMMENT);
        CHILD_PACKAGE_TAGS.add(TAG_FEATURE_GROUP);
        CHILD_PACKAGE_TAGS.add(TAG_INSTRUMENTATION);
        CHILD_PACKAGE_TAGS.add(TAG_SUPPORT_SCREENS);
        CHILD_PACKAGE_TAGS.add(TAG_SUPPORTS_INPUT);
        CHILD_PACKAGE_TAGS.add(TAG_USES_CONFIGURATION);
        CHILD_PACKAGE_TAGS.add(TAG_USES_FEATURE);
        CHILD_PACKAGE_TAGS.add(TAG_USES_GL_TEXTURE);
        CHILD_PACKAGE_TAGS.add(TAG_USES_PERMISSION);
        CHILD_PACKAGE_TAGS.add(TAG_USES_PERMISSION_SDK_23);
        CHILD_PACKAGE_TAGS.add(TAG_USES_PERMISSION_SDK_M);
        CHILD_PACKAGE_TAGS.add(TAG_USES_SDK);
    }

    public static final boolean LOG_UNSAFE_BROADCASTS = false;

    // Set of broadcast actions that are safe for manifest receivers
    public static final Set<String> SAFE_BROADCASTS = new ArraySet<>();
    static {
        SAFE_BROADCASTS.add(Intent.ACTION_BOOT_COMPLETED);
    }

    /** @hide */
    public static final String APK_FILE_EXTENSION = ".apk";
    /** @hide */
    public static final String APEX_FILE_EXTENSION = ".apex";

    /** @hide */
    public static class NewPermissionInfo {
        @UnsupportedAppUsage
        public final String name;
        @UnsupportedAppUsage
        public final int sdkVersion;
        public final int fileVersion;

        public NewPermissionInfo(String name, int sdkVersion, int fileVersion) {
            this.name = name;
            this.sdkVersion = sdkVersion;
            this.fileVersion = fileVersion;
        }
    }

    /**
     * List of new permissions that have been added since 1.0.
     * NOTE: These must be declared in SDK version order, with permissions
     * added to older SDKs appearing before those added to newer SDKs.
     * If sdkVersion is 0, then this is not a permission that we want to
     * automatically add to older apps, but we do want to allow it to be
     * granted during a platform update.
     * @hide
     */
    @UnsupportedAppUsage
    public static final PackageParser.NewPermissionInfo NEW_PERMISSIONS[] =
        new PackageParser.NewPermissionInfo[] {
            new PackageParser.NewPermissionInfo(android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    android.os.Build.VERSION_CODES.DONUT, 0),
            new PackageParser.NewPermissionInfo(android.Manifest.permission.READ_PHONE_STATE,
                    android.os.Build.VERSION_CODES.DONUT, 0)
    };

    /**
     * @deprecated callers should move to explicitly passing around source path.
     */
    @Deprecated
    public String mArchiveSourcePath;

    public String[] mSeparateProcesses;
    private boolean mOnlyCoreApps;
    private DisplayMetrics mMetrics;
    @UnsupportedAppUsage
    public Callback mCallback;
    private File mCacheDir;

    public static final int SDK_VERSION = Build.VERSION.SDK_INT;
    public static final String[] SDK_CODENAMES = Build.VERSION.ACTIVE_CODENAMES;

    public int mParseError = PackageManager.INSTALL_SUCCEEDED;

    public static boolean sCompatibilityModeEnabled = true;
    public static boolean sUseRoundIcon = false;

    public static final int PARSE_DEFAULT_INSTALL_LOCATION =
            PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
    public static final int PARSE_DEFAULT_TARGET_SANDBOX = 1;

    static class ParsePackageItemArgs {
        final Package owner;
        final String[] outError;
        final int nameRes;
        final int labelRes;
        final int iconRes;
        final int roundIconRes;
        final int logoRes;
        final int bannerRes;

        String tag;
        TypedArray sa;

        ParsePackageItemArgs(Package _owner, String[] _outError,
                int _nameRes, int _labelRes, int _iconRes, int _roundIconRes, int _logoRes,
                int _bannerRes) {
            owner = _owner;
            outError = _outError;
            nameRes = _nameRes;
            labelRes = _labelRes;
            iconRes = _iconRes;
            logoRes = _logoRes;
            bannerRes = _bannerRes;
            roundIconRes = _roundIconRes;
        }
    }

    /** @hide */
    @VisibleForTesting
    public static class ParseComponentArgs extends ParsePackageItemArgs {
        final String[] sepProcesses;
        final int processRes;
        final int descriptionRes;
        final int enabledRes;
        int flags;

        public ParseComponentArgs(Package _owner, String[] _outError,
                int _nameRes, int _labelRes, int _iconRes, int _roundIconRes, int _logoRes,
                int _bannerRes,
                String[] _sepProcesses, int _processRes,
                int _descriptionRes, int _enabledRes) {
            super(_owner, _outError, _nameRes, _labelRes, _iconRes, _roundIconRes, _logoRes,
                    _bannerRes);
            sepProcesses = _sepProcesses;
            processRes = _processRes;
            descriptionRes = _descriptionRes;
            enabledRes = _enabledRes;
        }
    }

    /**
     * Lightweight parsed details about a single package.
     */
    public static class PackageLite {
        @UnsupportedAppUsage
        public final String packageName;
        public final int versionCode;
        public final int versionCodeMajor;
        @UnsupportedAppUsage
        public final int installLocation;
        public final VerifierInfo[] verifiers;

        /** Names of any split APKs, ordered by parsed splitName */
        public final String[] splitNames;

        /** Names of any split APKs that are features. Ordered by splitName */
        public final boolean[] isFeatureSplits;

        /** Dependencies of any split APKs, ordered by parsed splitName */
        public final String[] usesSplitNames;
        public final String[] configForSplit;

        /**
         * Path where this package was found on disk. For monolithic packages
         * this is path to single base APK file; for cluster packages this is
         * path to the cluster directory.
         */
        public final String codePath;

        /** Path of base APK */
        public final String baseCodePath;
        /** Paths of any split APKs, ordered by parsed splitName */
        public final String[] splitCodePaths;

        /** Revision code of base APK */
        public final int baseRevisionCode;
        /** Revision codes of any split APKs, ordered by parsed splitName */
        public final int[] splitRevisionCodes;

        public final boolean coreApp;
        public final boolean debuggable;
        public final boolean multiArch;
        public final boolean use32bitAbi;
        public final boolean extractNativeLibs;
        public final boolean isolatedSplits;

        public PackageLite(String codePath, ApkLite baseApk, String[] splitNames,
                boolean[] isFeatureSplits, String[] usesSplitNames, String[] configForSplit,
                String[] splitCodePaths, int[] splitRevisionCodes) {
            this.packageName = baseApk.packageName;
            this.versionCode = baseApk.versionCode;
            this.versionCodeMajor = baseApk.versionCodeMajor;
            this.installLocation = baseApk.installLocation;
            this.verifiers = baseApk.verifiers;
            this.splitNames = splitNames;
            this.isFeatureSplits = isFeatureSplits;
            this.usesSplitNames = usesSplitNames;
            this.configForSplit = configForSplit;
            this.codePath = codePath;
            this.baseCodePath = baseApk.codePath;
            this.splitCodePaths = splitCodePaths;
            this.baseRevisionCode = baseApk.revisionCode;
            this.splitRevisionCodes = splitRevisionCodes;
            this.coreApp = baseApk.coreApp;
            this.debuggable = baseApk.debuggable;
            this.multiArch = baseApk.multiArch;
            this.use32bitAbi = baseApk.use32bitAbi;
            this.extractNativeLibs = baseApk.extractNativeLibs;
            this.isolatedSplits = baseApk.isolatedSplits;
        }

        public List<String> getAllCodePaths() {
            ArrayList<String> paths = new ArrayList<>();
            paths.add(baseCodePath);
            if (!ArrayUtils.isEmpty(splitCodePaths)) {
                Collections.addAll(paths, splitCodePaths);
            }
            return paths;
        }
    }

    /**
     * Lightweight parsed details about a single APK file.
     */
    public static class ApkLite {
        public final String codePath;
        public final String packageName;
        public final String splitName;
        public boolean isFeatureSplit;
        public final String configForSplit;
        public final String usesSplitName;
        public final int versionCode;
        public final int versionCodeMajor;
        public final int revisionCode;
        public final int installLocation;
        public final int minSdkVersion;
        public final int targetSdkVersion;
        public final VerifierInfo[] verifiers;
        public final SigningDetails signingDetails;
        public final boolean coreApp;
        public final boolean debuggable;
        public final boolean multiArch;
        public final boolean use32bitAbi;
        public final boolean extractNativeLibs;
        public final boolean isolatedSplits;
        public final boolean isSplitRequired;
        public final boolean useEmbeddedDex;
        public final String targetPackageName;
        public final boolean overlayIsStatic;
        public final int overlayPriority;
        public final int rollbackDataPolicy;

        public ApkLite(String codePath, String packageName, String splitName,
                boolean isFeatureSplit,
                String configForSplit, String usesSplitName, boolean isSplitRequired,
                int versionCode, int versionCodeMajor,
                int revisionCode, int installLocation, List<VerifierInfo> verifiers,
                SigningDetails signingDetails, boolean coreApp,
                boolean debuggable, boolean multiArch, boolean use32bitAbi,
                boolean useEmbeddedDex, boolean extractNativeLibs, boolean isolatedSplits,
                String targetPackageName, boolean overlayIsStatic, int overlayPriority,
                int minSdkVersion, int targetSdkVersion, int rollbackDataPolicy) {
            this.codePath = codePath;
            this.packageName = packageName;
            this.splitName = splitName;
            this.isFeatureSplit = isFeatureSplit;
            this.configForSplit = configForSplit;
            this.usesSplitName = usesSplitName;
            this.versionCode = versionCode;
            this.versionCodeMajor = versionCodeMajor;
            this.revisionCode = revisionCode;
            this.installLocation = installLocation;
            this.signingDetails = signingDetails;
            this.verifiers = verifiers.toArray(new VerifierInfo[verifiers.size()]);
            this.coreApp = coreApp;
            this.debuggable = debuggable;
            this.multiArch = multiArch;
            this.use32bitAbi = use32bitAbi;
            this.useEmbeddedDex = useEmbeddedDex;
            this.extractNativeLibs = extractNativeLibs;
            this.isolatedSplits = isolatedSplits;
            this.isSplitRequired = isSplitRequired;
            this.targetPackageName = targetPackageName;
            this.overlayIsStatic = overlayIsStatic;
            this.overlayPriority = overlayPriority;
            this.minSdkVersion = minSdkVersion;
            this.targetSdkVersion = targetSdkVersion;
            this.rollbackDataPolicy = rollbackDataPolicy;
        }

        public long getLongVersionCode() {
            return PackageInfo.composeLongVersionCode(versionCodeMajor, versionCode);
        }
    }

    /**
     * Cached parse state for new components.
     *
     * Allows reuse of the same parse argument records to avoid GC pressure.  Lifetime is carefully
     * scoped to the parsing of a single application element.
     */
    private static class CachedComponentArgs {
        ParseComponentArgs mActivityArgs;
        ParseComponentArgs mActivityAliasArgs;
        ParseComponentArgs mServiceArgs;
        ParseComponentArgs mProviderArgs;
    }

    /**
     * Cached state for parsing instrumentation to avoid GC pressure.
     *
     * Must be manually reset to null for each new manifest.
     */
    private ParsePackageItemArgs mParseInstrumentationArgs;

    /** If set to true, we will only allow package files that exactly match
     *  the DTD.  Otherwise, we try to get as much from the package as we
     *  can without failing.  This should normally be set to false, to
     *  support extensions to the DTD in future versions. */
    public static final boolean RIGID_PARSER = false;

    private static final String TAG = "PackageParser";

    @UnsupportedAppUsage
    public PackageParser() {
        mMetrics = new DisplayMetrics();
        mMetrics.setToDefaults();
    }

    @UnsupportedAppUsage
    public void setSeparateProcesses(String[] procs) {
        mSeparateProcesses = procs;
    }

    /**
     * Flag indicating this parser should only consider apps with
     * {@code coreApp} manifest attribute to be valid apps. This is useful when
     * creating a minimalist boot environment.
     */
    public void setOnlyCoreApps(boolean onlyCoreApps) {
        mOnlyCoreApps = onlyCoreApps;
    }

    public void setDisplayMetrics(DisplayMetrics metrics) {
        mMetrics = metrics;
    }

    /**
     * Sets the cache directory for this package parser.
     */
    public void setCacheDir(File cacheDir) {
        mCacheDir = cacheDir;
    }

    /**
     * Callback interface for retrieving information that may be needed while parsing
     * a package.
     */
    public interface Callback {
        boolean hasFeature(String feature);
    }

    /**
     * Standard implementation of {@link Callback} on top of the public {@link PackageManager}
     * class.
     */
    public static final class CallbackImpl implements Callback {
        private final PackageManager mPm;

        public CallbackImpl(PackageManager pm) {
            mPm = pm;
        }

        @Override public boolean hasFeature(String feature) {
            return mPm.hasSystemFeature(feature);
        }
    }

    /**
     * Set the {@link Callback} that can be used while parsing.
     */
    public void setCallback(Callback cb) {
        mCallback = cb;
    }

    public static final boolean isApkFile(File file) {
        return isApkPath(file.getName());
    }

    public static boolean isApkPath(String path) {
        return path.endsWith(APK_FILE_EXTENSION);
    }

    /**
     * Returns true if the package is installed and not hidden, or if the caller
     * explicitly wanted all uninstalled and hidden packages as well.
     * @param appInfo The applicationInfo of the app being checked.
     */
    private static boolean checkUseInstalledOrHidden(int flags, PackageUserState state,
            ApplicationInfo appInfo) {
        // Returns false if the package is hidden system app until installed.
        if ((flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) == 0
                && !state.installed
                && appInfo != null && appInfo.hiddenUntilInstalled) {
            return false;
        }

        // If available for the target user, or trying to match uninstalled packages and it's
        // a system app.
        return state.isAvailable(flags)
                || (appInfo != null && appInfo.isSystemApp()
                        && ((flags & PackageManager.MATCH_KNOWN_PACKAGES) != 0
                        || (flags & PackageManager.MATCH_HIDDEN_UNTIL_INSTALLED_COMPONENTS) != 0));
    }

    public static boolean isAvailable(PackageUserState state) {
        return checkUseInstalledOrHidden(0, state, null);
    }

    /**
     * Generate and return the {@link PackageInfo} for a parsed package.
     *
     * @param p the parsed package.
     * @param flags indicating which optional information is included.
     */
    @UnsupportedAppUsage
    public static PackageInfo generatePackageInfo(PackageParser.Package p,
            int[] gids, int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state) {

        return generatePackageInfo(p, gids, flags, firstInstallTime, lastUpdateTime,
                grantedPermissions, state, UserHandle.getCallingUserId());
    }

    @UnsupportedAppUsage
    public static PackageInfo generatePackageInfo(PackageParser.Package p,
            int[] gids, int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId) {

        return generatePackageInfo(p, null, gids, flags, firstInstallTime, lastUpdateTime,
                grantedPermissions, state, userId);
    }

    /**
     * PackageInfo generator specifically for apex files.
     *
     * @param pkg Package to generate info from. Should be derived from an apex.
     * @param apexInfo Apex info relating to the package.
     * @return PackageInfo
     * @throws PackageParserException
     */
    public static PackageInfo generatePackageInfo(
            PackageParser.Package pkg, ApexInfo apexInfo, int flags) {
        return generatePackageInfo(pkg, apexInfo, EmptyArray.INT, flags, 0, 0,
                Collections.emptySet(), new PackageUserState(), UserHandle.getCallingUserId());
    }

    private static PackageInfo generatePackageInfo(PackageParser.Package p, ApexInfo apexInfo,
            int gids[], int flags, long firstInstallTime, long lastUpdateTime,
            Set<String> grantedPermissions, PackageUserState state, int userId) {
        if (!checkUseInstalledOrHidden(flags, state, p.applicationInfo) || !p.isMatch(flags)) {
            return null;
        }
        PackageInfo pi = new PackageInfo();
        pi.packageName = p.packageName;
        pi.splitNames = p.splitNames;
        pi.versionCode = p.mVersionCode;
        pi.versionCodeMajor = p.mVersionCodeMajor;
        pi.baseRevisionCode = p.baseRevisionCode;
        pi.splitRevisionCodes = p.splitRevisionCodes;
        pi.versionName = p.mVersionName;
        pi.sharedUserId = p.mSharedUserId;
        pi.sharedUserLabel = p.mSharedUserLabel;
        pi.applicationInfo = generateApplicationInfo(p, flags, state, userId);
        pi.installLocation = p.installLocation;
        pi.isStub = p.isStub;
        pi.coreApp = p.coreApp;
        if ((pi.applicationInfo.flags&ApplicationInfo.FLAG_SYSTEM) != 0
                || (pi.applicationInfo.flags&ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
            pi.requiredForAllUsers = p.mRequiredForAllUsers;
        }
        pi.restrictedAccountType = p.mRestrictedAccountType;
        pi.requiredAccountType = p.mRequiredAccountType;
        pi.overlayTarget = p.mOverlayTarget;
        pi.targetOverlayableName = p.mOverlayTargetName;
        pi.overlayCategory = p.mOverlayCategory;
        pi.overlayPriority = p.mOverlayPriority;
        pi.mOverlayIsStatic = p.mOverlayIsStatic;
        pi.compileSdkVersion = p.mCompileSdkVersion;
        pi.compileSdkVersionCodename = p.mCompileSdkVersionCodename;
        pi.firstInstallTime = firstInstallTime;
        pi.lastUpdateTime = lastUpdateTime;
        if ((flags&PackageManager.GET_GIDS) != 0) {
            pi.gids = gids;
        }
        if ((flags&PackageManager.GET_CONFIGURATIONS) != 0) {
            int N = p.configPreferences != null ? p.configPreferences.size() : 0;
            if (N > 0) {
                pi.configPreferences = new ConfigurationInfo[N];
                p.configPreferences.toArray(pi.configPreferences);
            }
            N = p.reqFeatures != null ? p.reqFeatures.size() : 0;
            if (N > 0) {
                pi.reqFeatures = new FeatureInfo[N];
                p.reqFeatures.toArray(pi.reqFeatures);
            }
            N = p.featureGroups != null ? p.featureGroups.size() : 0;
            if (N > 0) {
                pi.featureGroups = new FeatureGroupInfo[N];
                p.featureGroups.toArray(pi.featureGroups);
            }
        }
        if ((flags & PackageManager.GET_ACTIVITIES) != 0) {
            final int N = p.activities.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final Activity a = p.activities.get(i);
                    if (state.isMatch(a.info, flags)) {
                        if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(a.className)) {
                            continue;
                        }
                        res[num++] = generateActivityInfo(a, flags, state, userId);
                    }
                }
                pi.activities = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_RECEIVERS) != 0) {
            final int N = p.receivers.size();
            if (N > 0) {
                int num = 0;
                final ActivityInfo[] res = new ActivityInfo[N];
                for (int i = 0; i < N; i++) {
                    final Activity a = p.receivers.get(i);
                    if (state.isMatch(a.info, flags)) {
                        res[num++] = generateActivityInfo(a, flags, state, userId);
                    }
                }
                pi.receivers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_SERVICES) != 0) {
            final int N = p.services.size();
            if (N > 0) {
                int num = 0;
                final ServiceInfo[] res = new ServiceInfo[N];
                for (int i = 0; i < N; i++) {
                    final Service s = p.services.get(i);
                    if (state.isMatch(s.info, flags)) {
                        res[num++] = generateServiceInfo(s, flags, state, userId);
                    }
                }
                pi.services = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags & PackageManager.GET_PROVIDERS) != 0) {
            final int N = p.providers.size();
            if (N > 0) {
                int num = 0;
                final ProviderInfo[] res = new ProviderInfo[N];
                for (int i = 0; i < N; i++) {
                    final Provider pr = p.providers.get(i);
                    if (state.isMatch(pr.info, flags)) {
                        res[num++] = generateProviderInfo(pr, flags, state, userId);
                    }
                }
                pi.providers = ArrayUtils.trimToSize(res, num);
            }
        }
        if ((flags&PackageManager.GET_INSTRUMENTATION) != 0) {
            int N = p.instrumentation.size();
            if (N > 0) {
                pi.instrumentation = new InstrumentationInfo[N];
                for (int i=0; i<N; i++) {
                    pi.instrumentation[i] = generateInstrumentationInfo(
                            p.instrumentation.get(i), flags);
                }
            }
        }
        if ((flags&PackageManager.GET_PERMISSIONS) != 0) {
            int N = p.permissions.size();
            if (N > 0) {
                pi.permissions = new PermissionInfo[N];
                for (int i=0; i<N; i++) {
                    pi.permissions[i] = generatePermissionInfo(p.permissions.get(i), flags);
                }
            }
            N = p.requestedPermissions.size();
            if (N > 0) {
                pi.requestedPermissions = new String[N];
                pi.requestedPermissionsFlags = new int[N];
                for (int i=0; i<N; i++) {
                    final String perm = p.requestedPermissions.get(i);
                    pi.requestedPermissions[i] = perm;
                    // The notion of required permissions is deprecated but for compatibility.
                    pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_REQUIRED;
                    if (grantedPermissions != null && grantedPermissions.contains(perm)) {
                        pi.requestedPermissionsFlags[i] |= PackageInfo.REQUESTED_PERMISSION_GRANTED;
                    }
                }
            }
        }

        if (apexInfo != null) {
            File apexFile = new File(apexInfo.modulePath);

            pi.applicationInfo.sourceDir = apexFile.getPath();
            pi.applicationInfo.publicSourceDir = apexFile.getPath();
            if (apexInfo.isFactory) {
                pi.applicationInfo.flags |= ApplicationInfo.FLAG_SYSTEM;
            } else {
                pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_SYSTEM;
            }
            if (apexInfo.isActive) {
                pi.applicationInfo.flags |= ApplicationInfo.FLAG_INSTALLED;
            } else {
                pi.applicationInfo.flags &= ~ApplicationInfo.FLAG_INSTALLED;
            }
            pi.isApex = true;
        }

        // deprecated method of getting signing certificates
        if ((flags & PackageManager.GET_SIGNATURES) != 0) {
            if (p.mSigningDetails.hasPastSigningCertificates()) {
                // Package has included signing certificate rotation information.  Return the oldest
                // cert so that programmatic checks keep working even if unaware of key rotation.
                pi.signatures = new Signature[1];
                pi.signatures[0] = p.mSigningDetails.pastSigningCertificates[0];
            } else if (p.mSigningDetails.hasSignatures()) {
                // otherwise keep old behavior
                int numberOfSigs = p.mSigningDetails.signatures.length;
                pi.signatures = new Signature[numberOfSigs];
                System.arraycopy(p.mSigningDetails.signatures, 0, pi.signatures, 0, numberOfSigs);
            }
        }

        // replacement for GET_SIGNATURES
        if ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0) {
            if (p.mSigningDetails != SigningDetails.UNKNOWN) {
                // only return a valid SigningInfo if there is signing information to report
                pi.signingInfo = new SigningInfo(p.mSigningDetails);
            } else {
                pi.signingInfo = null;
            }
        }
        return pi;
    }

    public static final int PARSE_MUST_BE_APK = 1 << 0;
    public static final int PARSE_IGNORE_PROCESSES = 1 << 1;
    public static final int PARSE_EXTERNAL_STORAGE = 1 << 3;
    public static final int PARSE_IS_SYSTEM_DIR = 1 << 4;
    public static final int PARSE_COLLECT_CERTIFICATES = 1 << 5;
    public static final int PARSE_ENFORCE_CODE = 1 << 6;
    public static final int PARSE_CHATTY = 1 << 31;

    @IntDef(flag = true, prefix = { "PARSE_" }, value = {
            PARSE_CHATTY,
            PARSE_COLLECT_CERTIFICATES,
            PARSE_ENFORCE_CODE,
            PARSE_EXTERNAL_STORAGE,
            PARSE_IGNORE_PROCESSES,
            PARSE_IS_SYSTEM_DIR,
            PARSE_MUST_BE_APK,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ParseFlags {}

    public static final Comparator<String> sSplitNameComparator = new SplitNameComparator();

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
    public static PackageLite parsePackageLite(File packageFile, int flags)
            throws PackageParserException {
        if (packageFile.isDirectory()) {
            return parseClusterPackageLite(packageFile, flags);
        } else {
            return parseMonolithicPackageLite(packageFile, flags);
        }
    }

    private static PackageLite parseMonolithicPackageLite(File packageFile, int flags)
            throws PackageParserException {
        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parseApkLite");
        final ApkLite baseApk = parseApkLite(packageFile, flags);
        final String packagePath = packageFile.getAbsolutePath();
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        return new PackageLite(packagePath, baseApk, null, null, null, null, null, null);
    }

    static PackageLite parseClusterPackageLite(File packageDir, int flags)
            throws PackageParserException {
        final File[] files = packageDir.listFiles();
        if (ArrayUtils.isEmpty(files)) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_NOT_APK,
                    "No packages found in split");
        }
        // Apk directory is directly nested under the current directory
        if (files.length == 1 && files[0].isDirectory()) {
            return parseClusterPackageLite(files[0], flags);
        }

        String packageName = null;
        int versionCode = 0;

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "parseApkLite");
        final ArrayMap<String, ApkLite> apks = new ArrayMap<>();
        for (File file : files) {
            if (isApkFile(file)) {
                final ApkLite lite = parseApkLite(file, flags);

                // Assert that all package names and version codes are
                // consistent with the first one we encounter.
                if (packageName == null) {
                    packageName = lite.packageName;
                    versionCode = lite.versionCode;
                } else {
                    if (!packageName.equals(lite.packageName)) {
                        throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                                "Inconsistent package " + lite.packageName + " in " + file
                                + "; expected " + packageName);
                    }
                    if (versionCode != lite.versionCode) {
                        throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                                "Inconsistent version " + lite.versionCode + " in " + file
                                + "; expected " + versionCode);
                    }
                }

                // Assert that each split is defined only once
                if (apks.put(lite.splitName, lite) != null) {
                    throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                            "Split name " + lite.splitName
                            + " defined more than once; most recent was " + file);
                }
            }
        }
        Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);

        final ApkLite baseApk = apks.remove(null);
        if (baseApk == null) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
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
        String[] splitClassLoaderNames = null;
        if (size > 0) {
            splitNames = new String[size];
            isFeatureSplits = new boolean[size];
            usesSplitNames = new String[size];
            configForSplits = new String[size];
            splitCodePaths = new String[size];
            splitRevisionCodes = new int[size];

            splitNames = apks.keySet().toArray(splitNames);
            Arrays.sort(splitNames, sSplitNameComparator);

            for (int i = 0; i < size; i++) {
                final ApkLite apk = apks.get(splitNames[i]);
                usesSplitNames[i] = apk.usesSplitName;
                isFeatureSplits[i] = apk.isFeatureSplit;
                configForSplits[i] = apk.configForSplit;
                splitCodePaths[i] = apk.codePath;
                splitRevisionCodes[i] = apk.revisionCode;
            }
        }

        final String codePath = packageDir.getAbsolutePath();
        return new PackageLite(codePath, baseApk, splitNames, isFeatureSplits, usesSplitNames,
                configForSplits, splitCodePaths, splitRevisionCodes);
    }

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
     * must be done separately in {@link #collectCertificates(Package, boolean)}.
     *
     * If {@code useCaches} is true, the package parser might return a cached
     * result from a previous parse of the same {@code packageFile} with the same
     * {@code flags}. Note that this method does not check whether {@code packageFile}
     * has changed since the last parse, it's up to callers to do so.
     *
     * @see #parsePackageLite(File, int)
     */
    @UnsupportedAppUsage
    public Package parsePackage(File packageFile, int flags, boolean useCaches)
            throws PackageParserException {
        if (packageFile.isDirectory()) {
            return parseClusterPackage(packageFile, flags);
        } else {
            return parseMonolithicPackage(packageFile, flags);
        }
    }

    /**
     * Equivalent to {@link #parsePackage(File, int, boolean)} with {@code useCaches == false}.
     */
    @UnsupportedAppUsage
    public Package parsePackage(File packageFile, int flags) throws PackageParserException {
        return parsePackage(packageFile, flags, false /* useCaches */);
    }

    /**
     * Parse all APKs contained in the given directory, treating them as a
     * single package. This also performs sanity checking, such as requiring
     * identical package name and version codes, a single base APK, and unique
     * split names.
     * <p>
     * Note that this <em>does not</em> perform signature verification; that
     * must be done separately in
     * {@link #collectCertificates(Package, boolean)} .
     */
    private Package parseClusterPackage(File packageDir, int flags) throws PackageParserException {
        final PackageLite lite = parseClusterPackageLite(packageDir, 0);
        if (mOnlyCoreApps && !lite.coreApp) {
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
            final Package pkg = parseBaseApk(baseApk, assets, flags);
            if (pkg == null) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NOT_APK,
                        "Failed to parse base APK: " + baseApk);
            }

            if (!ArrayUtils.isEmpty(lite.splitNames)) {
                final int num = lite.splitNames.length;
                pkg.splitNames = lite.splitNames;
                pkg.splitCodePaths = lite.splitCodePaths;
                pkg.splitRevisionCodes = lite.splitRevisionCodes;
                pkg.splitFlags = new int[num];
                pkg.splitPrivateFlags = new int[num];
                pkg.applicationInfo.splitNames = pkg.splitNames;
                pkg.applicationInfo.splitDependencies = splitDependencies;
                pkg.applicationInfo.splitClassLoaderNames = new String[num];

                for (int i = 0; i < num; i++) {
                    final AssetManager splitAssets = assetLoader.getSplitAssetManager(i);
                    parseSplitApk(pkg, i, splitAssets, flags);
                }
            }

            pkg.setCodePath(lite.codePath);
            pkg.setUse32bitAbi(lite.use32bitAbi);
            return pkg;
        } finally {
            IoUtils.closeQuietly(assetLoader);
        }
    }

    /**
     * Parse the given APK file, treating it as as a single monolithic package.
     * <p>
     * Note that this <em>does not</em> perform signature verification; that
     * must be done separately in
     * {@link #collectCertificates(Package, boolean)}.
     */
    @UnsupportedAppUsage
    public Package parseMonolithicPackage(File apkFile, int flags) throws PackageParserException {
        final PackageLite lite = parseMonolithicPackageLite(apkFile, flags);
        if (mOnlyCoreApps) {
            if (!lite.coreApp) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                        "Not a coreApp: " + apkFile);
            }
        }

        final SplitAssetLoader assetLoader = new DefaultSplitAssetLoader(lite, flags);
        try {
            final Package pkg = parseBaseApk(apkFile, assetLoader.getBaseAssetManager(), flags);
            pkg.setCodePath(apkFile.getCanonicalPath());
            pkg.setUse32bitAbi(lite.use32bitAbi);
            return pkg;
        } catch (IOException e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to get path: " + apkFile, e);
        } finally {
            IoUtils.closeQuietly(assetLoader);
        }
    }

    private Package parseBaseApk(File apkFile, AssetManager assets, int flags)
            throws PackageParserException {
        final String apkPath = apkFile.getAbsolutePath();

        String volumeUuid = null;
        if (apkPath.startsWith(MNT_EXPAND)) {
            final int end = apkPath.indexOf('/', MNT_EXPAND.length());
            volumeUuid = apkPath.substring(MNT_EXPAND.length(), end);
        }

        mParseError = PackageManager.INSTALL_SUCCEEDED;
        mArchiveSourcePath = apkFile.getAbsolutePath();

        if (DEBUG_JAR) Slog.d(TAG, "Scanning base APK: " + apkPath);

        XmlResourceParser parser = null;
        try {
            final int cookie = assets.findCookieForPath(apkPath);
            if (cookie == 0) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                        "Failed adding asset path: " + apkPath);
            }
            parser = assets.openXmlResourceParser(cookie, ANDROID_MANIFEST_FILENAME);
            final Resources res = new Resources(assets, mMetrics, null);

            final String[] outError = new String[1];
            final Package pkg = parseBaseApk(apkPath, res, parser, flags, outError);
            if (pkg == null) {
                throw new PackageParserException(mParseError,
                        apkPath + " (at " + parser.getPositionDescription() + "): " + outError[0]);
            }

            pkg.setVolumeUuid(volumeUuid);
            pkg.setApplicationVolumeUuid(volumeUuid);
            pkg.setBaseCodePath(apkPath);
            pkg.setSigningDetails(SigningDetails.UNKNOWN);

            return pkg;

        } catch (PackageParserException e) {
            throw e;
        } catch (Exception e) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
                    "Failed to read manifest from " + apkPath, e);
        } finally {
            IoUtils.closeQuietly(parser);
        }
    }

    private void parseSplitApk(Package pkg, int splitIndex, AssetManager assets, int flags)
            throws PackageParserException {
        final String apkPath = pkg.splitCodePaths[splitIndex];

        mParseError = PackageManager.INSTALL_SUCCEEDED;
        mArchiveSourcePath = apkPath;

        if (DEBUG_JAR) Slog.d(TAG, "Scanning split APK: " + apkPath);

        final Resources res;
        XmlResourceParser parser = null;
        try {
            // This must always succeed, as the path has been added to the AssetManager before.
            final int cookie = assets.findCookieForPath(apkPath);
            if (cookie == 0) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_MANIFEST,
                        "Failed adding asset path: " + apkPath);
            }

            parser = assets.openXmlResourceParser(cookie, ANDROID_MANIFEST_FILENAME);
            res = new Resources(assets, mMetrics, null);

            final String[] outError = new String[1];
            pkg = parseSplitApk(pkg, res, parser, flags, splitIndex, outError);
            if (pkg == null) {
                throw new PackageParserException(mParseError,
                        apkPath + " (at " + parser.getPositionDescription() + "): " + outError[0]);
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
     * Parse the manifest of a <em>split APK</em>.
     * <p>
     * Note that split APKs have many more restrictions on what they're capable
     * of doing, so many valid features of a base APK have been carefully
     * omitted here.
     */
    private Package parseSplitApk(Package pkg, Resources res, XmlResourceParser parser, int flags,
            int splitIndex, String[] outError) throws XmlPullParserException, IOException,
            PackageParserException {
        AttributeSet attrs = parser;

        // We parsed manifest tag earlier; just skip past it
        parsePackageSplitNames(parser, attrs);

        mParseInstrumentationArgs = null;

        int type;

        boolean foundApp = false;

        int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();
            if (tagName.equals(TAG_APPLICATION)) {
                if (foundApp) {
                    if (RIGID_PARSER) {
                        outError[0] = "<manifest> has more than one <application>";
                        mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                        return null;
                    } else {
                        Slog.w(TAG, "<manifest> has more than one <application>");
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                }

                foundApp = true;
                if (!parseSplitApplication(pkg, res, parser, flags, splitIndex, outError)) {
                    return null;
                }

            } else if (RIGID_PARSER) {
                outError[0] = "Bad element under <manifest>: "
                    + parser.getName();
                mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return null;

            } else {
                Slog.w(TAG, "Unknown element under <manifest>: " + parser.getName()
                        + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                continue;
            }
        }

        if (!foundApp) {
            outError[0] = "<manifest> does not contain an <application>";
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_EMPTY;
        }

        return pkg;
    }

    /** Parses the public keys from the set of signatures. */
    public static ArraySet<PublicKey> toSigningKeys(Signature[] signatures)
            throws CertificateException {
        ArraySet<PublicKey> keys = new ArraySet<>(signatures.length);
        for (int i = 0; i < signatures.length; i++) {
            keys.add(signatures[i].getPublicKey());
        }
        return keys;
    }

    /**
     * Collect certificates from all the APKs described in the given package,
     * populating {@link Package#mSigningDetails}. Also asserts that all APK
     * contents are signed correctly and consistently.
     */
    @UnsupportedAppUsage
    public static void collectCertificates(Package pkg, boolean skipVerify)
            throws PackageParserException {
        collectCertificatesInternal(pkg, skipVerify);
        final int childCount = (pkg.childPackages != null) ? pkg.childPackages.size() : 0;
        for (int i = 0; i < childCount; i++) {
            Package childPkg = pkg.childPackages.get(i);
            childPkg.mSigningDetails = pkg.mSigningDetails;
        }
    }

    private static void collectCertificatesInternal(Package pkg, boolean skipVerify)
            throws PackageParserException {
        pkg.mSigningDetails = SigningDetails.UNKNOWN;

        Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "collectCertificates");
        try {
            collectCertificates(pkg, new File(pkg.baseCodePath), skipVerify);

            if (!ArrayUtils.isEmpty(pkg.splitCodePaths)) {
                for (int i = 0; i < pkg.splitCodePaths.length; i++) {
                    collectCertificates(pkg, new File(pkg.splitCodePaths[i]), skipVerify);
                }
            }
        } finally {
            Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
        }
    }

    @UnsupportedAppUsage
    private static void collectCertificates(Package pkg, File apkFile, boolean skipVerify)
            throws PackageParserException {
        final String apkPath = apkFile.getAbsolutePath();

        int minSignatureScheme = ApkSignatureVerifier.getMinimumSignatureSchemeVersionForTargetSdk(
                pkg.applicationInfo.targetSdkVersion);
        if (pkg.applicationInfo.isStaticSharedLibrary()) {
            // must use v2 signing scheme
            minSignatureScheme = SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2;
        }
        SigningDetails verified;
        if (skipVerify) {
            // systemDir APKs are already trusted, save time by not verifying
            verified = ApkSignatureVerifier.unsafeGetCertsWithoutVerification(
                        apkPath, minSignatureScheme);
        } else {
            verified = ApkSignatureVerifier.verify(apkPath, minSignatureScheme);
        }

        // Verify that entries are signed consistently with the first pkg
        // we encountered. Note that for splits, certificates may have
        // already been populated during an earlier parse of a base APK.
        if (pkg.mSigningDetails == SigningDetails.UNKNOWN) {
            pkg.mSigningDetails = verified;
        } else {
            if (!Signature.areExactMatch(pkg.mSigningDetails.signatures, verified.signatures)) {
                throw new PackageParserException(
                        INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES,
                        apkPath + " has mismatched certificates");
            }
        }
    }

    private static AssetManager newConfiguredAssetManager() {
        AssetManager assetManager = new AssetManager();
        assetManager.setConfiguration(0, 0, null, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Build.VERSION.RESOURCES_SDK_INT);
        return assetManager;
    }

    /**
     * Utility method that retrieves lightweight details about a single APK
     * file, including package name, split name, and install location.
     *
     * @param apkFile path to a single APK
     * @param flags optional parse flags, such as
     *            {@link #PARSE_COLLECT_CERTIFICATES}
     */
    public static ApkLite parseApkLite(File apkFile, int flags)
            throws PackageParserException {
        return parseApkLiteInner(apkFile, null, null, flags);
    }

    /**
     * Utility method that retrieves lightweight details about a single APK
     * file, including package name, split name, and install location.
     *
     * @param fd already open file descriptor of an apk file
     * @param debugPathName arbitrary text name for this file, for debug output
     * @param flags optional parse flags, such as
     *            {@link #PARSE_COLLECT_CERTIFICATES}
     */
    public static ApkLite parseApkLite(FileDescriptor fd, String debugPathName, int flags)
            throws PackageParserException {
        return parseApkLiteInner(null, fd, debugPathName, flags);
    }

    private static ApkLite parseApkLiteInner(File apkFile, FileDescriptor fd, String debugPathName,
            int flags) throws PackageParserException {
        final String apkPath = fd != null ? debugPathName : apkFile.getAbsolutePath();

        XmlResourceParser parser = null;
        ApkAssets apkAssets = null;
        try {
            try {
                apkAssets = fd != null
                        ? ApkAssets.loadFromFd(fd, debugPathName, 0 /* flags */, null /* assets */)
                        : ApkAssets.loadFromPath(apkPath);
            } catch (IOException e) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_NOT_APK,
                        "Failed to parse " + apkPath);
            }

            parser = apkAssets.openXml(ANDROID_MANIFEST_FILENAME);

            final SigningDetails signingDetails;
            if ((flags & PARSE_COLLECT_CERTIFICATES) != 0) {
                // TODO: factor signature related items out of Package object
                final Package tempPkg = new Package((String) null);
                final boolean skipVerify = (flags & PARSE_IS_SYSTEM_DIR) != 0;
                Trace.traceBegin(TRACE_TAG_PACKAGE_MANAGER, "collectCertificates");
                try {
                    collectCertificates(tempPkg, apkFile, skipVerify);
                } finally {
                    Trace.traceEnd(TRACE_TAG_PACKAGE_MANAGER);
                }
                signingDetails = tempPkg.mSigningDetails;
            } else {
                signingDetails = SigningDetails.UNKNOWN;
            }

            final AttributeSet attrs = parser;
            return parseApkLite(apkPath, parser, attrs, signingDetails);

        } catch (XmlPullParserException | IOException | RuntimeException e) {
            Slog.w(TAG, "Failed to parse " + apkPath, e);
            throw new PackageParserException(INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION,
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

    public static String validateName(String name, boolean requireSeparator,
            boolean requireFilename) {
        final int N = name.length();
        boolean hasSep = false;
        boolean front = true;
        for (int i=0; i<N; i++) {
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

    /**
     * @deprecated Use {@link android.content.pm.parsing.ApkLiteParseUtils#parsePackageSplitNames}
     */
    @Deprecated
    public static Pair<String, String> parsePackageSplitNames(XmlPullParser parser,
            AttributeSet attrs) throws IOException, XmlPullParserException,
            PackageParserException {

        int type;
        while ((type = parser.next()) != XmlPullParser.START_TAG
                && type != XmlPullParser.END_DOCUMENT) {
        }

        if (type != XmlPullParser.START_TAG) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "No start tag found");
        }
        if (!parser.getName().equals(TAG_MANIFEST)) {
            throw new PackageParserException(INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                    "No <manifest> tag");
        }

        final String packageName = attrs.getAttributeValue(null, "package");
        if (!"android".equals(packageName)) {
            final String error = validateName(packageName, true, true);
            if (error != null) {
                throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
                        "Invalid manifest package: " + error);
            }
        }

        String splitName = attrs.getAttributeValue(null, "split");
        if (splitName != null) {
            if (splitName.length() == 0) {
                splitName = null;
            } else {
                final String error = validateName(splitName, false, false);
                if (error != null) {
                    throw new PackageParserException(INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME,
                            "Invalid manifest split: " + error);
                }
            }
        }

        return Pair.create(packageName.intern(),
                (splitName != null) ? splitName.intern() : splitName);
    }

    private static ApkLite parseApkLite(String codePath, XmlPullParser parser, AttributeSet attrs,
            SigningDetails signingDetails)
            throws IOException, XmlPullParserException, PackageParserException {
        final Pair<String, String> packageSplit = parsePackageSplitNames(parser, attrs);

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
        int rollbackDataPolicy = 0;

        String requiredSystemPropertyName = null;
        String requiredSystemPropertyValue = null;

        for (int i = 0; i < attrs.getAttributeCount(); i++) {
            final String attr = attrs.getAttributeName(i);
            if (attr.equals("installLocation")) {
                installLocation = attrs.getAttributeIntValue(i,
                        PARSE_DEFAULT_INSTALL_LOCATION);
            } else if (attr.equals("versionCode")) {
                versionCode = attrs.getAttributeIntValue(i, 0);
            } else if (attr.equals("versionCodeMajor")) {
                versionCodeMajor = attrs.getAttributeIntValue(i, 0);
            } else if (attr.equals("revisionCode")) {
                revisionCode = attrs.getAttributeIntValue(i, 0);
            } else if (attr.equals("coreApp")) {
                coreApp = attrs.getAttributeBooleanValue(i, false);
            } else if (attr.equals("isolatedSplits")) {
                isolatedSplits = attrs.getAttributeBooleanValue(i, false);
            } else if (attr.equals("configForSplit")) {
                configForSplit = attrs.getAttributeValue(i);
            } else if (attr.equals("isFeatureSplit")) {
                isFeatureSplit = attrs.getAttributeBooleanValue(i, false);
            } else if (attr.equals("isSplitRequired")) {
                isSplitRequired = attrs.getAttributeBooleanValue(i, false);
            }
        }

        // Only search the tree when the tag is the direct child of <manifest> tag
        int type;
        final int searchDepth = parser.getDepth() + 1;

        final List<VerifierInfo> verifiers = new ArrayList<VerifierInfo>();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() >= searchDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getDepth() != searchDepth) {
                continue;
            }

            if (TAG_PACKAGE_VERIFIER.equals(parser.getName())) {
                final VerifierInfo verifier = parseVerifier(attrs);
                if (verifier != null) {
                    verifiers.add(verifier);
                }
            } else if (TAG_APPLICATION.equals(parser.getName())) {
                for (int i = 0; i < attrs.getAttributeCount(); ++i) {
                    final String attr = attrs.getAttributeName(i);
                    if ("debuggable".equals(attr)) {
                        debuggable = attrs.getAttributeBooleanValue(i, false);
                    }
                    if ("multiArch".equals(attr)) {
                        multiArch = attrs.getAttributeBooleanValue(i, false);
                    }
                    if ("use32bitAbi".equals(attr)) {
                        use32bitAbi = attrs.getAttributeBooleanValue(i, false);
                    }
                    if ("extractNativeLibs".equals(attr)) {
                        extractNativeLibs = attrs.getAttributeBooleanValue(i, true);
                    }
                    if ("useEmbeddedDex".equals(attr)) {
                        useEmbeddedDex = attrs.getAttributeBooleanValue(i, false);
                    }
                    if (attr.equals("rollbackDataPolicy")) {
                        rollbackDataPolicy = attrs.getAttributeIntValue(i, 0);
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
            } else if (TAG_USES_SPLIT.equals(parser.getName())) {
                if (usesSplitName != null) {
                    Slog.w(TAG, "Only one <uses-split> permitted. Ignoring others.");
                    continue;
                }

                usesSplitName = attrs.getAttributeValue(ANDROID_RESOURCES, "name");
                if (usesSplitName == null) {
                    throw new PackageParserException(
                            PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED,
                            "<uses-split> tag requires 'android:name' attribute");
                }
            } else if (TAG_USES_SDK.equals(parser.getName())) {
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
        if (!checkRequiredSystemProperties(requiredSystemPropertyName,
                requiredSystemPropertyValue)) {
            Slog.i(TAG, "Skipping target and overlay pair " + targetPackage + " and "
                    + codePath + ": overlay ignored due to required system property: "
                    + requiredSystemPropertyName + " with value: " + requiredSystemPropertyValue);
            targetPackage = null;
            overlayIsStatic = false;
            overlayPriority = 0;
        }

        return new ApkLite(codePath, packageSplit.first, packageSplit.second, isFeatureSplit,
                configForSplit, usesSplitName, isSplitRequired, versionCode, versionCodeMajor,
                revisionCode, installLocation, verifiers, signingDetails, coreApp, debuggable,
                multiArch, use32bitAbi, useEmbeddedDex, extractNativeLibs, isolatedSplits,
                targetPackage, overlayIsStatic, overlayPriority, minSdkVersion, targetSdkVersion,
                rollbackDataPolicy);
    }

    /**
     * Parses a child package and adds it to the parent if successful. If you add
     * new tags that need to be supported by child packages make sure to add them
     * to {@link #CHILD_PACKAGE_TAGS}.
     *
     * @param parentPkg The parent that contains the child
     * @param res Resources against which to resolve values
     * @param parser Parser of the manifest
     * @param flags Flags about how to parse
     * @param outError Human readable error if parsing fails
     * @return True of parsing succeeded.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private boolean parseBaseApkChild(Package parentPkg, Resources res, XmlResourceParser parser,
            int flags, String[] outError) throws XmlPullParserException, IOException {
        // Make sure we have a valid child package name
        String childPackageName = parser.getAttributeValue(null, "package");
        if (validateName(childPackageName, true, false) != null) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
            return false;
        }

        // Child packages must be unique
        if (childPackageName.equals(parentPkg.packageName)) {
            String message = "Child package name cannot be equal to parent package name: "
                    + parentPkg.packageName;
            Slog.w(TAG, message);
            outError[0] = message;
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        // Child packages must be unique
        if (parentPkg.hasChildPackage(childPackageName)) {
            String message = "Duplicate child package:" + childPackageName;
            Slog.w(TAG, message);
            outError[0] = message;
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        // Go ahead and parse the child
        Package childPkg = new Package(childPackageName);

        // Child package inherits parent version code/name/target SDK
        childPkg.mVersionCode = parentPkg.mVersionCode;
        childPkg.baseRevisionCode = parentPkg.baseRevisionCode;
        childPkg.mVersionName = parentPkg.mVersionName;
        childPkg.applicationInfo.targetSdkVersion = parentPkg.applicationInfo.targetSdkVersion;
        childPkg.applicationInfo.minSdkVersion = parentPkg.applicationInfo.minSdkVersion;

        childPkg = parseBaseApkCommon(childPkg, CHILD_PACKAGE_TAGS, res, parser, flags, outError);
        if (childPkg == null) {
            // If we got null then error was set during child parsing
            return false;
        }

        // Set the parent-child relation
        if (parentPkg.childPackages == null) {
            parentPkg.childPackages = new ArrayList<>();
        }
        parentPkg.childPackages.add(childPkg);
        childPkg.parentPackage = parentPkg;

        return true;
    }

    /**
     * Parse the manifest of a <em>base APK</em>. When adding new features you
     * need to consider whether they should be supported by split APKs and child
     * packages.
     *
     * @param apkPath The package apk file path
     * @param res The resources from which to resolve values
     * @param parser The manifest parser
     * @param flags Flags how to parse
     * @param outError Human readable error message
     * @return Parsed package or null on error.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.P, trackingBug = 115609023)
    private Package parseBaseApk(String apkPath, Resources res, XmlResourceParser parser, int flags,
            String[] outError) throws XmlPullParserException, IOException {
        final String splitName;
        final String pkgName;

        try {
            Pair<String, String> packageSplit = parsePackageSplitNames(parser, parser);
            pkgName = packageSplit.first;
            splitName = packageSplit.second;

            if (!TextUtils.isEmpty(splitName)) {
                outError[0] = "Expected base APK, but found split " + splitName;
                mParseError = PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
                return null;
            }
        } catch (PackageParserException e) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_BAD_PACKAGE_NAME;
            return null;
        }

        final Package pkg = new Package(pkgName);

        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifest);

        pkg.mVersionCode = sa.getInteger(
                com.android.internal.R.styleable.AndroidManifest_versionCode, 0);
        pkg.mVersionCodeMajor = sa.getInteger(
                com.android.internal.R.styleable.AndroidManifest_versionCodeMajor, 0);
        pkg.applicationInfo.setVersionCode(pkg.getLongVersionCode());
        pkg.baseRevisionCode = sa.getInteger(
                com.android.internal.R.styleable.AndroidManifest_revisionCode, 0);
        pkg.mVersionName = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifest_versionName, 0);
        if (pkg.mVersionName != null) {
            pkg.mVersionName = pkg.mVersionName.intern();
        }

        pkg.coreApp = parser.getAttributeBooleanValue(null, "coreApp", false);

        final boolean isolatedSplits = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifest_isolatedSplits, false);
        if (isolatedSplits) {
            pkg.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_ISOLATED_SPLIT_LOADING;
        }

        pkg.mCompileSdkVersion = sa.getInteger(
                com.android.internal.R.styleable.AndroidManifest_compileSdkVersion, 0);
        pkg.applicationInfo.compileSdkVersion = pkg.mCompileSdkVersion;
        pkg.mCompileSdkVersionCodename = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifest_compileSdkVersionCodename, 0);
        if (pkg.mCompileSdkVersionCodename != null) {
            pkg.mCompileSdkVersionCodename = pkg.mCompileSdkVersionCodename.intern();
        }
        pkg.applicationInfo.compileSdkVersionCodename = pkg.mCompileSdkVersionCodename;

        sa.recycle();

        return parseBaseApkCommon(pkg, null, res, parser, flags, outError);
    }

    /**
     * This is the common parsing routing for handling parent and child
     * packages in a base APK. The difference between parent and child
     * parsing is that some tags are not supported by child packages as
     * well as some manifest attributes are ignored. The implementation
     * assumes the calling code has already handled the manifest tag if needed
     * (this applies to the parent only).
     *
     * @param pkg The package which to populate
     * @param acceptedTags Which tags to handle, null to handle all
     * @param res Resources against which to resolve values
     * @param parser Parser of the manifest
     * @param flags Flags about how to parse
     * @param outError Human readable error if parsing fails
     * @return The package if parsing succeeded or null.
     *
     * @throws XmlPullParserException
     * @throws IOException
     */
    private Package parseBaseApkCommon(Package pkg, Set<String> acceptedTags, Resources res,
            XmlResourceParser parser, int flags, String[] outError) throws XmlPullParserException,
            IOException {
        mParseInstrumentationArgs = null;

        int type;
        boolean foundApp = false;

        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifest);

        String str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifest_sharedUserId, 0);
        if (str != null && str.length() > 0) {
            String nameError = validateName(str, true, true);
            if (nameError != null && !"android".equals(pkg.packageName)) {
                outError[0] = "<manifest> specifies bad sharedUserId name \""
                    + str + "\": " + nameError;
                mParseError = PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID;
                return null;
            }
            pkg.mSharedUserId = str.intern();
            pkg.mSharedUserLabel = sa.getResourceId(
                    com.android.internal.R.styleable.AndroidManifest_sharedUserLabel, 0);
        }

        pkg.installLocation = sa.getInteger(
                com.android.internal.R.styleable.AndroidManifest_installLocation,
                PARSE_DEFAULT_INSTALL_LOCATION);
        pkg.applicationInfo.installLocation = pkg.installLocation;

        final int targetSandboxVersion = sa.getInteger(
                com.android.internal.R.styleable.AndroidManifest_targetSandboxVersion,
                PARSE_DEFAULT_TARGET_SANDBOX);
        pkg.applicationInfo.targetSandboxVersion = targetSandboxVersion;

        /* Set the global "on SD card" flag */
        if ((flags & PARSE_EXTERNAL_STORAGE) != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_EXTERNAL_STORAGE;
        }

        // Resource boolean are -1, so 1 means we don't know the value.
        int supportsSmallScreens = 1;
        int supportsNormalScreens = 1;
        int supportsLargeScreens = 1;
        int supportsXLargeScreens = 1;
        int resizeable = 1;
        int anyDensity = 1;

        int outerDepth = parser.getDepth();
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String tagName = parser.getName();

            if (acceptedTags != null && !acceptedTags.contains(tagName)) {
                Slog.w(TAG, "Skipping unsupported element under <manifest>: "
                        + tagName + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                continue;
            }

            if (tagName.equals(TAG_APPLICATION)) {
                if (foundApp) {
                    if (RIGID_PARSER) {
                        outError[0] = "<manifest> has more than one <application>";
                        mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                        return null;
                    } else {
                        Slog.w(TAG, "<manifest> has more than one <application>");
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                }

                foundApp = true;
                if (!parseBaseApplication(pkg, res, parser, flags, outError)) {
                    return null;
                }
            } else if (tagName.equals(TAG_OVERLAY)) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestResourceOverlay);
                pkg.mOverlayTarget = sa.getString(
                        com.android.internal.R.styleable.AndroidManifestResourceOverlay_targetPackage);
                pkg.mOverlayTargetName = sa.getString(
                        com.android.internal.R.styleable.AndroidManifestResourceOverlay_targetName);
                pkg.mOverlayCategory = sa.getString(
                        com.android.internal.R.styleable.AndroidManifestResourceOverlay_category);
                pkg.mOverlayPriority = sa.getInt(
                        com.android.internal.R.styleable.AndroidManifestResourceOverlay_priority,
                        0);
                pkg.mOverlayIsStatic = sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestResourceOverlay_isStatic,
                        false);
                final String propName = sa.getString(
                        com.android.internal.R.styleable
                        .AndroidManifestResourceOverlay_requiredSystemPropertyName);
                final String propValue = sa.getString(
                        com.android.internal.R.styleable
                        .AndroidManifestResourceOverlay_requiredSystemPropertyValue);
                sa.recycle();

                if (pkg.mOverlayTarget == null) {
                    outError[0] = "<overlay> does not specify a target package";
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return null;
                }

                if (pkg.mOverlayPriority < 0 || pkg.mOverlayPriority > 9999) {
                    outError[0] = "<overlay> priority must be between 0 and 9999";
                    mParseError =
                        PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return null;
                }

                // check to see if overlay should be excluded based on system property condition
                if (!checkRequiredSystemProperties(propName, propValue)) {
                    Slog.i(TAG, "Skipping target and overlay pair " + pkg.mOverlayTarget + " and "
                        + pkg.baseCodePath+ ": overlay ignored due to required system property: "
                        + propName + " with value: " + propValue);
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_SKIPPED;
                    return null;
                }

                pkg.applicationInfo.privateFlags |=
                    ApplicationInfo.PRIVATE_FLAG_IS_RESOURCE_OVERLAY;

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals(TAG_KEY_SETS)) {
                if (!parseKeySets(pkg, res, parser, outError)) {
                    return null;
                }
            } else if (tagName.equals(TAG_PERMISSION_GROUP)) {
                if (!parsePermissionGroup(pkg, flags, res, parser, outError)) {
                    return null;
                }
            } else if (tagName.equals(TAG_PERMISSION)) {
                if (!parsePermission(pkg, res, parser, outError)) {
                    return null;
                }
            } else if (tagName.equals(TAG_PERMISSION_TREE)) {
                if (!parsePermissionTree(pkg, res, parser, outError)) {
                    return null;
                }
            } else if (tagName.equals(TAG_USES_PERMISSION)) {
                if (!parseUsesPermission(pkg, res, parser)) {
                    return null;
                }
            } else if (tagName.equals(TAG_USES_PERMISSION_SDK_M)
                    || tagName.equals(TAG_USES_PERMISSION_SDK_23)) {
                if (!parseUsesPermission(pkg, res, parser)) {
                    return null;
                }
            } else if (tagName.equals(TAG_USES_CONFIGURATION)) {
                ConfigurationInfo cPref = new ConfigurationInfo();
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration);
                cPref.reqTouchScreen = sa.getInt(
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration_reqTouchScreen,
                        Configuration.TOUCHSCREEN_UNDEFINED);
                cPref.reqKeyboardType = sa.getInt(
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration_reqKeyboardType,
                        Configuration.KEYBOARD_UNDEFINED);
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration_reqHardKeyboard,
                        false)) {
                    cPref.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_HARD_KEYBOARD;
                }
                cPref.reqNavigation = sa.getInt(
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration_reqNavigation,
                        Configuration.NAVIGATION_UNDEFINED);
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestUsesConfiguration_reqFiveWayNav,
                        false)) {
                    cPref.reqInputFeatures |= ConfigurationInfo.INPUT_FEATURE_FIVE_WAY_NAV;
                }
                sa.recycle();
                pkg.configPreferences = ArrayUtils.add(pkg.configPreferences, cPref);

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals(TAG_USES_FEATURE)) {
                FeatureInfo fi = parseUsesFeature(res, parser);
                pkg.reqFeatures = ArrayUtils.add(pkg.reqFeatures, fi);

                if (fi.name == null) {
                    ConfigurationInfo cPref = new ConfigurationInfo();
                    cPref.reqGlEsVersion = fi.reqGlEsVersion;
                    pkg.configPreferences = ArrayUtils.add(pkg.configPreferences, cPref);
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals(TAG_FEATURE_GROUP)) {
                FeatureGroupInfo group = new FeatureGroupInfo();
                ArrayList<FeatureInfo> features = null;
                final int innerDepth = parser.getDepth();
                while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                        && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
                    if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                        continue;
                    }

                    final String innerTagName = parser.getName();
                    if (innerTagName.equals("uses-feature")) {
                        FeatureInfo featureInfo = parseUsesFeature(res, parser);
                        // FeatureGroups are stricter and mandate that
                        // any <uses-feature> declared are mandatory.
                        featureInfo.flags |= FeatureInfo.FLAG_REQUIRED;
                        features = ArrayUtils.add(features, featureInfo);
                    } else {
                        Slog.w(TAG, "Unknown element under <feature-group>: " + innerTagName +
                                " at " + mArchiveSourcePath + " " +
                                parser.getPositionDescription());
                    }
                    XmlUtils.skipCurrentTag(parser);
                }

                if (features != null) {
                    group.features = new FeatureInfo[features.size()];
                    group.features = features.toArray(group.features);
                }
                pkg.featureGroups = ArrayUtils.add(pkg.featureGroups, group);

            } else if (tagName.equals(TAG_USES_SDK)) {
                if (SDK_VERSION > 0) {
                    sa = res.obtainAttributes(parser,
                            com.android.internal.R.styleable.AndroidManifestUsesSdk);

                    int minVers = 1;
                    String minCode = null;
                    int targetVers = 0;
                    String targetCode = null;

                    TypedValue val = sa.peekValue(
                            com.android.internal.R.styleable.AndroidManifestUsesSdk_minSdkVersion);
                    if (val != null) {
                        if (val.type == TypedValue.TYPE_STRING && val.string != null) {
                            minCode = val.string.toString();
                        } else {
                            // If it's not a string, it's an integer.
                            minVers = val.data;
                        }
                    }

                    val = sa.peekValue(
                            com.android.internal.R.styleable.AndroidManifestUsesSdk_targetSdkVersion);
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

                    final int minSdkVersion = PackageParser.computeMinSdkVersion(minVers, minCode,
                            SDK_VERSION, SDK_CODENAMES, outError);
                    if (minSdkVersion < 0) {
                        mParseError = PackageManager.INSTALL_FAILED_OLDER_SDK;
                        return null;
                    }

                    final int targetSdkVersion = PackageParser.computeTargetSdkVersion(targetVers,
                            targetCode, SDK_CODENAMES, outError);
                    if (targetSdkVersion < 0) {
                        mParseError = PackageManager.INSTALL_FAILED_OLDER_SDK;
                        return null;
                    }

                    pkg.applicationInfo.minSdkVersion = minSdkVersion;
                    pkg.applicationInfo.targetSdkVersion = targetSdkVersion;
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals(TAG_SUPPORT_SCREENS)) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens);

                pkg.applicationInfo.requiresSmallestWidthDp = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_requiresSmallestWidthDp,
                        0);
                pkg.applicationInfo.compatibleWidthLimitDp = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_compatibleWidthLimitDp,
                        0);
                pkg.applicationInfo.largestWidthLimitDp = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_largestWidthLimitDp,
                        0);

                // This is a trick to get a boolean and still able to detect
                // if a value was actually set.
                supportsSmallScreens = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_smallScreens,
                        supportsSmallScreens);
                supportsNormalScreens = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_normalScreens,
                        supportsNormalScreens);
                supportsLargeScreens = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_largeScreens,
                        supportsLargeScreens);
                supportsXLargeScreens = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_xlargeScreens,
                        supportsXLargeScreens);
                resizeable = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_resizeable,
                        resizeable);
                anyDensity = sa.getInteger(
                        com.android.internal.R.styleable.AndroidManifestSupportsScreens_anyDensity,
                        anyDensity);

                sa.recycle();

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals(TAG_PROTECTED_BROADCAST)) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestProtectedBroadcast);

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                String name = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestProtectedBroadcast_name);

                sa.recycle();

                if (name != null) {
                    if (pkg.protectedBroadcasts == null) {
                        pkg.protectedBroadcasts = new ArrayList<String>();
                    }
                    if (!pkg.protectedBroadcasts.contains(name)) {
                        pkg.protectedBroadcasts.add(name.intern());
                    }
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals(TAG_INSTRUMENTATION)) {
                if (parseInstrumentation(pkg, res, parser, outError) == null) {
                    return null;
                }
            } else if (tagName.equals(TAG_ORIGINAL_PACKAGE)) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestOriginalPackage);

                String orig =sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestOriginalPackage_name, 0);
                if (!pkg.packageName.equals(orig)) {
                    if (pkg.mOriginalPackages == null) {
                        pkg.mOriginalPackages = new ArrayList<String>();
                        pkg.mRealPackage = pkg.packageName;
                    }
                    pkg.mOriginalPackages.add(orig);
                }

                sa.recycle();

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals(TAG_ADOPT_PERMISSIONS)) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestOriginalPackage);

                String name = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestOriginalPackage_name, 0);

                sa.recycle();

                if (name != null) {
                    if (pkg.mAdoptPermissions == null) {
                        pkg.mAdoptPermissions = new ArrayList<String>();
                    }
                    pkg.mAdoptPermissions.add(name);
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals(TAG_USES_GL_TEXTURE)) {
                // Just skip this tag
                XmlUtils.skipCurrentTag(parser);
                continue;

            } else if (tagName.equals(TAG_COMPATIBLE_SCREENS)) {
                // Just skip this tag
                XmlUtils.skipCurrentTag(parser);
                continue;
            } else if (tagName.equals(TAG_SUPPORTS_INPUT)) {//
                XmlUtils.skipCurrentTag(parser);
                continue;

            } else if (tagName.equals(TAG_EAT_COMMENT)) {
                // Just skip this tag
                XmlUtils.skipCurrentTag(parser);
                continue;

            } else if (tagName.equals(TAG_PACKAGE)) {
                if (!MULTI_PACKAGE_APK_ENABLED) {
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                }
                if (!parseBaseApkChild(pkg, res, parser, flags, outError)) {
                    // If parsing a child failed the error is already set
                    return null;
                }

            } else if (tagName.equals(TAG_RESTRICT_UPDATE)) {
                if ((flags & PARSE_IS_SYSTEM_DIR) != 0) {
                    sa = res.obtainAttributes(parser,
                            com.android.internal.R.styleable.AndroidManifestRestrictUpdate);
                    final String hash = sa.getNonConfigurationString(
                            com.android.internal.R.styleable.AndroidManifestRestrictUpdate_hash, 0);
                    sa.recycle();

                    pkg.restrictUpdateHash = null;
                    if (hash != null) {
                        final int hashLength = hash.length();
                        final byte[] hashBytes = new byte[hashLength / 2];
                        for (int i = 0; i < hashLength; i += 2){
                            hashBytes[i/2] = (byte) ((Character.digit(hash.charAt(i), 16) << 4)
                                    + Character.digit(hash.charAt(i + 1), 16));
                        }
                        pkg.restrictUpdateHash = hashBytes;
                    }
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (RIGID_PARSER) {
                outError[0] = "Bad element under <manifest>: "
                    + parser.getName();
                mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return null;

            } else {
                Slog.w(TAG, "Unknown element under <manifest>: " + parser.getName()
                        + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                continue;
            }
        }

        if (!foundApp && pkg.instrumentation.size() == 0) {
            outError[0] = "<manifest> does not contain an <application> or <instrumentation>";
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_EMPTY;
        }

        final int NP = PackageParser.NEW_PERMISSIONS.length;
        StringBuilder newPermsMsg = null;
        for (int ip=0; ip<NP; ip++) {
            final PackageParser.NewPermissionInfo npi
                    = PackageParser.NEW_PERMISSIONS[ip];
            if (pkg.applicationInfo.targetSdkVersion >= npi.sdkVersion) {
                break;
            }
            if (!pkg.requestedPermissions.contains(npi.name)) {
                if (newPermsMsg == null) {
                    newPermsMsg = new StringBuilder(128);
                    newPermsMsg.append(pkg.packageName);
                    newPermsMsg.append(": compat added ");
                } else {
                    newPermsMsg.append(' ');
                }
                newPermsMsg.append(npi.name);
                pkg.requestedPermissions.add(npi.name);
                pkg.implicitPermissions.add(npi.name);
            }
        }
        if (newPermsMsg != null) {
            Slog.i(TAG, newPermsMsg.toString());
        }

        List<SplitPermissionInfoParcelable> splitPermissions;

        try {
            splitPermissions = ActivityThread.getPermissionManager().getSplitPermissions();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        final int listSize = splitPermissions.size();
        for (int is = 0; is < listSize; is++) {
            final SplitPermissionInfoParcelable spi = splitPermissions.get(is);
            if (pkg.applicationInfo.targetSdkVersion >= spi.getTargetSdk()
                    || !pkg.requestedPermissions.contains(spi.getSplitPermission())) {
                continue;
            }
            final List<String> newPerms = spi.getNewPermissions();
            for (int in = 0; in < newPerms.size(); in++) {
                final String perm = newPerms.get(in);
                if (!pkg.requestedPermissions.contains(perm)) {
                    pkg.requestedPermissions.add(perm);
                    pkg.implicitPermissions.add(perm);
                }
            }
        }

        if (supportsSmallScreens < 0 || (supportsSmallScreens > 0
                && pkg.applicationInfo.targetSdkVersion
                        >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_SMALL_SCREENS;
        }
        if (supportsNormalScreens != 0) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_NORMAL_SCREENS;
        }
        if (supportsLargeScreens < 0 || (supportsLargeScreens > 0
                && pkg.applicationInfo.targetSdkVersion
                        >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_LARGE_SCREENS;
        }
        if (supportsXLargeScreens < 0 || (supportsXLargeScreens > 0
                && pkg.applicationInfo.targetSdkVersion
                        >= android.os.Build.VERSION_CODES.GINGERBREAD)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_XLARGE_SCREENS;
        }
        if (resizeable < 0 || (resizeable > 0
                && pkg.applicationInfo.targetSdkVersion
                        >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS;
        }
        if (anyDensity < 0 || (anyDensity > 0
                && pkg.applicationInfo.targetSdkVersion
                        >= android.os.Build.VERSION_CODES.DONUT)) {
            pkg.applicationInfo.flags |= ApplicationInfo.FLAG_SUPPORTS_SCREEN_DENSITIES;
        }

        // At this point we can check if an application is not supporting densities and hence
        // cannot be windowed / resized. Note that an SDK version of 0 is common for
        // pre-Doughnut applications.
        if (pkg.applicationInfo.usesCompatibilityMode()) {
            adjustPackageToBeUnresizeableAndUnpipable(pkg);
        }

        return pkg;
    }

    /**
     * Returns {@code true} if both the property name and value are empty or if the given system
     * property is set to the specified value. Properties can be one or more, and if properties are
     * more than one, they must be separated by comma, and count of names and values must be equal,
     * and also every given system property must be set to the corresponding value.
     * In all other cases, returns {@code false}
     */
    public static boolean checkRequiredSystemProperties(@Nullable String rawPropNames,
            @Nullable String rawPropValues) {
        if (TextUtils.isEmpty(rawPropNames) || TextUtils.isEmpty(rawPropValues)) {
            if (!TextUtils.isEmpty(rawPropNames) || !TextUtils.isEmpty(rawPropValues)) {
                // malformed condition - incomplete
                Slog.w(TAG, "Disabling overlay - incomplete property :'" + rawPropNames
                        + "=" + rawPropValues + "' - require both requiredSystemPropertyName"
                        + " AND requiredSystemPropertyValue to be specified.");
                return false;
            }
            // no valid condition set - so no exclusion criteria, overlay will be included.
            return true;
        }

        final String[] propNames = rawPropNames.split(",");
        final String[] propValues = rawPropValues.split(",");

        if (propNames.length != propValues.length) {
            Slog.w(TAG, "Disabling overlay - property :'" + rawPropNames
                    + "=" + rawPropValues + "' - require both requiredSystemPropertyName"
                    + " AND requiredSystemPropertyValue lists to have the same size.");
            return false;
        }
        for (int i = 0; i < propNames.length; i++) {
            // Check property value: make sure it is both set and equal to expected value
            final String currValue = SystemProperties.get(propNames[i]);
            if (!TextUtils.equals(currValue, propValues[i])) {
                return false;
            }
        }
        return true;
    }

    /**
     * This is a pre-density application which will get scaled - instead of being pixel perfect.
     * This type of application is not resizable.
     *
     * @param pkg The package which needs to be marked as unresizable.
     */
    private void adjustPackageToBeUnresizeableAndUnpipable(Package pkg) {
        for (Activity a : pkg.activities) {
            a.info.resizeMode = RESIZE_MODE_UNRESIZEABLE;
            a.info.flags &= ~FLAG_SUPPORTS_PICTURE_IN_PICTURE;
        }
    }

    /**

    /**
     * Matches a given {@code targetCode} against a set of release codeNames. Target codes can
     * either be of the form {@code [codename]}" (e.g {@code "Q"}) or of the form
     * {@code [codename].[fingerprint]} (e.g {@code "Q.cafebc561"}).
     */
    private static boolean matchTargetCode(@NonNull String[] codeNames,
            @NonNull String targetCode) {
        final String targetCodeName;
        final int targetCodeIdx = targetCode.indexOf('.');
        if (targetCodeIdx == -1) {
            targetCodeName = targetCode;
        } else {
            targetCodeName = targetCode.substring(0, targetCodeIdx);
        }
        return ArrayUtils.contains(codeNames, targetCodeName);
    }

    /**
     * Computes the targetSdkVersion to use at runtime. If the package is not
     * compatible with this platform, populates {@code outError[0]} with an
     * error message.
     * <p>
     * If {@code targetCode} is not specified, e.g. the value is {@code null},
     * then the {@code targetVers} will be returned unmodified.
     * <p>
     * Otherwise, the behavior varies based on whether the current platform
     * is a pre-release version, e.g. the {@code platformSdkCodenames} array
     * has length > 0:
     * <ul>
     * <li>If this is a pre-release platform and the value specified by
     * {@code targetCode} is contained within the array of allowed pre-release
     * codenames, this method will return {@link Build.VERSION_CODES#CUR_DEVELOPMENT}.
     * <li>If this is a released platform, this method will return -1 to
     * indicate that the package is not compatible with this platform.
     * </ul>
     *
     * @param targetVers targetSdkVersion number, if specified in the
     *                   application manifest, or 0 otherwise
     * @param targetCode targetSdkVersion code, if specified in the application
     *                   manifest, or {@code null} otherwise
     * @param platformSdkCodenames array of allowed pre-release SDK codenames
     *                             for this platform
     * @param outError output array to populate with error, if applicable
     * @return the targetSdkVersion to use at runtime, or -1 if the package is
     *         not compatible with this platform
     * @hide Exposed for unit testing only.
     */
    @TestApi
    public static int computeTargetSdkVersion(@IntRange(from = 0) int targetVers,
            @Nullable String targetCode, @NonNull String[] platformSdkCodenames,
            @NonNull String[] outError) {
        // If it's a release SDK, return the version number unmodified.
        if (targetCode == null) {
            return targetVers;
        }

        // If it's a pre-release SDK and the codename matches this platform, it
        // definitely targets this SDK.
        if (matchTargetCode(platformSdkCodenames, targetCode)) {
            return Build.VERSION_CODES.CUR_DEVELOPMENT;
        }

        // Otherwise, we're looking at an incompatible pre-release SDK.
        if (platformSdkCodenames.length > 0) {
            outError[0] = "Requires development platform " + targetCode
                    + " (current platform is any of "
                    + Arrays.toString(platformSdkCodenames) + ")";
        } else {
            outError[0] = "Requires development platform " + targetCode
                    + " but this is a release platform.";
        }
        return -1;
    }

    /**
     * Computes the minSdkVersion to use at runtime. If the package is not
     * compatible with this platform, populates {@code outError[0]} with an
     * error message.
     * <p>
     * If {@code minCode} is not specified, e.g. the value is {@code null},
     * then behavior varies based on the {@code platformSdkVersion}:
     * <ul>
     * <li>If the platform SDK version is greater than or equal to the
     * {@code minVers}, returns the {@code mniVers} unmodified.
     * <li>Otherwise, returns -1 to indicate that the package is not
     * compatible with this platform.
     * </ul>
     * <p>
     * Otherwise, the behavior varies based on whether the current platform
     * is a pre-release version, e.g. the {@code platformSdkCodenames} array
     * has length > 0:
     * <ul>
     * <li>If this is a pre-release platform and the value specified by
     * {@code targetCode} is contained within the array of allowed pre-release
     * codenames, this method will return {@link Build.VERSION_CODES#CUR_DEVELOPMENT}.
     * <li>If this is a released platform, this method will return -1 to
     * indicate that the package is not compatible with this platform.
     * </ul>
     *
     * @param minVers minSdkVersion number, if specified in the application
     *                manifest, or 1 otherwise
     * @param minCode minSdkVersion code, if specified in the application
     *                manifest, or {@code null} otherwise
     * @param platformSdkVersion platform SDK version number, typically
     *                           Build.VERSION.SDK_INT
     * @param platformSdkCodenames array of allowed prerelease SDK codenames
     *                             for this platform
     * @param outError output array to populate with error, if applicable
     * @return the minSdkVersion to use at runtime, or -1 if the package is not
     *         compatible with this platform
     * @hide Exposed for unit testing only.
     */
    @TestApi
    public static int computeMinSdkVersion(@IntRange(from = 1) int minVers,
            @Nullable String minCode, @IntRange(from = 1) int platformSdkVersion,
            @NonNull String[] platformSdkCodenames, @NonNull String[] outError) {
        // If it's a release SDK, make sure we meet the minimum SDK requirement.
        if (minCode == null) {
            if (minVers <= platformSdkVersion) {
                return minVers;
            }

            // We don't meet the minimum SDK requirement.
            outError[0] = "Requires newer sdk version #" + minVers
                    + " (current version is #" + platformSdkVersion + ")";
            return -1;
        }

        // If it's a pre-release SDK and the codename matches this platform, we
        // definitely meet the minimum SDK requirement.
        if (matchTargetCode(platformSdkCodenames, minCode)) {
            return Build.VERSION_CODES.CUR_DEVELOPMENT;
        }

        // Otherwise, we're looking at an incompatible pre-release SDK.
        if (platformSdkCodenames.length > 0) {
            outError[0] = "Requires development platform " + minCode
                    + " (current platform is any of "
                    + Arrays.toString(platformSdkCodenames) + ")";
        } else {
            outError[0] = "Requires development platform " + minCode
                    + " but this is a release platform.";
        }
        return -1;
    }

    private FeatureInfo parseUsesFeature(Resources res, AttributeSet attrs) {
        FeatureInfo fi = new FeatureInfo();
        TypedArray sa = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestUsesFeature);
        // Note: don't allow this value to be a reference to a resource
        // that may change.
        fi.name = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestUsesFeature_name);
        fi.version = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestUsesFeature_version, 0);
        if (fi.name == null) {
            fi.reqGlEsVersion = sa.getInt(
                        com.android.internal.R.styleable.AndroidManifestUsesFeature_glEsVersion,
                        FeatureInfo.GL_ES_VERSION_UNDEFINED);
        }
        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestUsesFeature_required, true)) {
            fi.flags |= FeatureInfo.FLAG_REQUIRED;
        }
        sa.recycle();
        return fi;
    }

    private boolean parseUsesStaticLibrary(Package pkg, Resources res, XmlResourceParser parser,
            String[] outError) throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestUsesStaticLibrary);

        // Note: don't allow this value to be a reference to a resource that may change.
        String lname = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestUsesLibrary_name);
        final int version = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestUsesStaticLibrary_version, -1);
        String certSha256Digest = sa.getNonResourceString(com.android.internal.R.styleable
                .AndroidManifestUsesStaticLibrary_certDigest);
        sa.recycle();

        // Since an APK providing a static shared lib can only provide the lib - fail if malformed
        if (lname == null || version < 0 || certSha256Digest == null) {
            outError[0] = "Bad uses-static-library declaration name: " + lname + " version: "
                    + version + " certDigest" + certSha256Digest;
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            XmlUtils.skipCurrentTag(parser);
            return false;
        }

        // Can depend only on one version of the same library
        if (pkg.usesStaticLibraries != null && pkg.usesStaticLibraries.contains(lname)) {
            outError[0] = "Depending on multiple versions of static library " + lname;
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            XmlUtils.skipCurrentTag(parser);
            return false;
        }

        lname = lname.intern();
        // We allow ":" delimiters in the SHA declaration as this is the format
        // emitted by the certtool making it easy for developers to copy/paste.
        certSha256Digest = certSha256Digest.replace(":", "").toLowerCase();

        // Fot apps targeting O-MR1 we require explicit enumeration of all certs.
        String[] additionalCertSha256Digests = EmptyArray.STRING;
        if (pkg.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.O_MR1) {
            additionalCertSha256Digests = parseAdditionalCertificates(res, parser, outError);
            if (additionalCertSha256Digests == null) {
                return false;
            }
        } else {
            XmlUtils.skipCurrentTag(parser);
        }

        final String[] certSha256Digests = new String[additionalCertSha256Digests.length + 1];
        certSha256Digests[0] = certSha256Digest;
        System.arraycopy(additionalCertSha256Digests, 0, certSha256Digests,
                1, additionalCertSha256Digests.length);

        pkg.usesStaticLibraries = ArrayUtils.add(pkg.usesStaticLibraries, lname);
        pkg.usesStaticLibrariesVersions = ArrayUtils.appendLong(
                pkg.usesStaticLibrariesVersions, version, true);
        pkg.usesStaticLibrariesCertDigests = ArrayUtils.appendElement(String[].class,
                pkg.usesStaticLibrariesCertDigests, certSha256Digests, true);

        return true;
    }

    private String[] parseAdditionalCertificates(Resources resources, XmlResourceParser parser,
            String[] outError) throws XmlPullParserException, IOException {
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
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
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

    private boolean parseUsesPermission(Package pkg, Resources res, XmlResourceParser parser)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestUsesPermission);

        // Note: don't allow this value to be a reference to a resource
        // that may change.
        String name = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestUsesPermission_name);

        int maxSdkVersion = 0;
        TypedValue val = sa.peekValue(
                com.android.internal.R.styleable.AndroidManifestUsesPermission_maxSdkVersion);
        if (val != null) {
            if (val.type >= TypedValue.TYPE_FIRST_INT && val.type <= TypedValue.TYPE_LAST_INT) {
                maxSdkVersion = val.data;
            }
        }

        final String requiredFeature = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestUsesPermission_requiredFeature, 0);

        final String requiredNotfeature = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestUsesPermission_requiredNotFeature, 0);

        sa.recycle();

        XmlUtils.skipCurrentTag(parser);

        if (name == null) {
            return true;
        }

        if ((maxSdkVersion != 0) && (maxSdkVersion < Build.VERSION.RESOURCES_SDK_INT)) {
            return true;
        }

        // Only allow requesting this permission if the platform supports the given feature.
        if (requiredFeature != null && mCallback != null && !mCallback.hasFeature(requiredFeature)) {
            return true;
        }

        // Only allow requesting this permission if the platform doesn't support the given feature.
        if (requiredNotfeature != null && mCallback != null
                && mCallback.hasFeature(requiredNotfeature)) {
            return true;
        }

        int index = pkg.requestedPermissions.indexOf(name);
        if (index == -1) {
            pkg.requestedPermissions.add(name.intern());
        } else {
            Slog.w(TAG, "Ignoring duplicate uses-permissions/uses-permissions-sdk-m: "
                    + name + " in package: " + pkg.packageName + " at: "
                    + parser.getPositionDescription());
        }

        return true;
    }

    public static String buildClassName(String pkg, CharSequence clsSeq,
            String[] outError) {
        if (clsSeq == null || clsSeq.length() <= 0) {
            outError[0] = "Empty class name in package " + pkg;
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

    private static String buildCompoundName(String pkg,
            CharSequence procSeq, String type, String[] outError) {
        String proc = procSeq.toString();
        char c = proc.charAt(0);
        if (pkg != null && c == ':') {
            if (proc.length() < 2) {
                outError[0] = "Bad " + type + " name " + proc + " in package " + pkg
                        + ": must be at least two characters";
                return null;
            }
            String subName = proc.substring(1);
            String nameError = validateName(subName, false, false);
            if (nameError != null) {
                outError[0] = "Invalid " + type + " name " + proc + " in package "
                        + pkg + ": " + nameError;
                return null;
            }
            return pkg + proc;
        }
        String nameError = validateName(proc, true, false);
        if (nameError != null && !"system".equals(proc)) {
            outError[0] = "Invalid " + type + " name " + proc + " in package "
                    + pkg + ": " + nameError;
            return null;
        }
        return proc;
    }

    public static String buildProcessName(String pkg, String defProc,
            CharSequence procSeq, int flags, String[] separateProcesses,
            String[] outError) {
        if ((flags&PARSE_IGNORE_PROCESSES) != 0 && !"system".equals(procSeq)) {
            return defProc != null ? defProc : pkg;
        }
        if (separateProcesses != null) {
            for (int i=separateProcesses.length-1; i>=0; i--) {
                String sp = separateProcesses[i];
                if (sp.equals(pkg) || sp.equals(defProc) || sp.equals(procSeq)) {
                    return pkg;
                }
            }
        }
        if (procSeq == null || procSeq.length() <= 0) {
            return defProc;
        }
        return TextUtils.safeIntern(buildCompoundName(pkg, procSeq, "process", outError));
    }

    public static String buildTaskAffinityName(String pkg, String defProc,
            CharSequence procSeq, String[] outError) {
        if (procSeq == null) {
            return defProc;
        }
        if (procSeq.length() <= 0) {
            return null;
        }
        return buildCompoundName(pkg, procSeq, "taskAffinity", outError);
    }

    private boolean parseKeySets(Package owner, Resources res,
            XmlResourceParser parser, String[] outError)
            throws XmlPullParserException, IOException {
        // we've encountered the 'key-sets' tag
        // all the keys and keysets that we want must be defined here
        // so we're going to iterate over the parser and pull out the things we want
        int outerDepth = parser.getDepth();
        int currentKeySetDepth = -1;
        int type;
        String currentKeySet = null;
        ArrayMap<String, PublicKey> publicKeys = new ArrayMap<String, PublicKey>();
        ArraySet<String> upgradeKeySets = new ArraySet<String>();
        ArrayMap<String, ArraySet<String>> definedKeySets = new ArrayMap<String, ArraySet<String>>();
        ArraySet<String> improperKeySets = new ArraySet<String>();
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
                    outError[0] = "Improperly nested 'key-set' tag at "
                            + parser.getPositionDescription();
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }
                final TypedArray sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestKeySet);
                final String keysetName = sa.getNonResourceString(
                    com.android.internal.R.styleable.AndroidManifestKeySet_name);
                definedKeySets.put(keysetName, new ArraySet<String>());
                currentKeySet = keysetName;
                currentKeySetDepth = parser.getDepth();
                sa.recycle();
            } else if (tagName.equals("public-key")) {
                if (currentKeySet == null) {
                    outError[0] = "Improperly nested 'key-set' tag at "
                            + parser.getPositionDescription();
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }
                final TypedArray sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestPublicKey);
                final String publicKeyName = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestPublicKey_name);
                final String encodedKey = sa.getNonResourceString(
                            com.android.internal.R.styleable.AndroidManifestPublicKey_value);
                if (encodedKey == null && publicKeys.get(publicKeyName) == null) {
                    outError[0] = "'public-key' " + publicKeyName + " must define a public-key value"
                            + " on first use at " + parser.getPositionDescription();
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    sa.recycle();
                    return false;
                } else if (encodedKey != null) {
                    PublicKey currentKey = parsePublicKey(encodedKey);
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
                        outError[0] = "Value of 'public-key' " + publicKeyName
                               + " conflicts with previously defined value at "
                               + parser.getPositionDescription();
                        mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                        sa.recycle();
                        return false;
                    }
                }
                definedKeySets.get(currentKeySet).add(publicKeyName);
                sa.recycle();
                XmlUtils.skipCurrentTag(parser);
            } else if (tagName.equals("upgrade-key-set")) {
                final TypedArray sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestUpgradeKeySet);
                String name = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestUpgradeKeySet_name);
                upgradeKeySets.add(name);
                sa.recycle();
                XmlUtils.skipCurrentTag(parser);
            } else if (RIGID_PARSER) {
                outError[0] = "Bad element under <key-sets>: " + parser.getName()
                        + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription();
                mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return false;
            } else {
                Slog.w(TAG, "Unknown element under <key-sets>: " + parser.getName()
                        + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
                continue;
            }
        }
        Set<String> publicKeyNames = publicKeys.keySet();
        if (publicKeyNames.removeAll(definedKeySets.keySet())) {
            outError[0] = "Package" + owner.packageName + " AndroidManifext.xml "
                    + "'key-set' and 'public-key' names must be distinct.";
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }
        owner.mKeySetMapping = new ArrayMap<String, ArraySet<PublicKey>>();
        for (ArrayMap.Entry<String, ArraySet<String>> e: definedKeySets.entrySet()) {
            final String keySetName = e.getKey();
            if (e.getValue().size() == 0) {
                Slog.w(TAG, "Package" + owner.packageName + " AndroidManifext.xml "
                        + "'key-set' " + keySetName + " has no valid associated 'public-key'."
                        + " Not including in package's defined key-sets.");
                continue;
            } else if (improperKeySets.contains(keySetName)) {
                Slog.w(TAG, "Package" + owner.packageName + " AndroidManifext.xml "
                        + "'key-set' " + keySetName + " contained improper 'public-key'"
                        + " tags. Not including in package's defined key-sets.");
                continue;
            }
            owner.mKeySetMapping.put(keySetName, new ArraySet<PublicKey>());
            for (String s : e.getValue()) {
                owner.mKeySetMapping.get(keySetName).add(publicKeys.get(s));
            }
        }
        if (owner.mKeySetMapping.keySet().containsAll(upgradeKeySets)) {
            owner.mUpgradeKeySets = upgradeKeySets;
        } else {
            outError[0] ="Package" + owner.packageName + " AndroidManifext.xml "
                   + "does not define all 'upgrade-key-set's .";
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }
        return true;
    }

    private boolean parsePermissionGroup(Package owner, int flags, Resources res,
            XmlResourceParser parser, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup);

        int requestDetailResourceId = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_requestDetail, 0);
        int backgroundRequestResourceId = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_backgroundRequest,
                0);
        int backgroundRequestDetailResourceId = sa.getResourceId(
                com.android.internal.R.styleable
                        .AndroidManifestPermissionGroup_backgroundRequestDetail, 0);

        PermissionGroup perm = new PermissionGroup(owner, requestDetailResourceId,
                backgroundRequestResourceId, backgroundRequestDetailResourceId);

        if (!parsePackageItemInfo(owner, perm.info, outError,
                "<permission-group>", sa, true /*nameRequired*/,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_name,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_label,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_icon,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_roundIcon,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_logo,
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_banner)) {
            sa.recycle();
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        perm.info.descriptionRes = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_description,
                0);
        perm.info.requestRes = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_request, 0);
        perm.info.flags = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_permissionGroupFlags, 0);
        perm.info.priority = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestPermissionGroup_priority, 0);

        sa.recycle();

        if (!parseAllMetaData(res, parser, "<permission-group>", perm,
                outError)) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        owner.permissionGroups.add(perm);

        return true;
    }

    private boolean parsePermission(Package owner, Resources res,
            XmlResourceParser parser, String[] outError)
        throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestPermission);

        String backgroundPermission = null;
        if (sa.hasValue(
                com.android.internal.R.styleable.AndroidManifestPermission_backgroundPermission)) {
            if ("android".equals(owner.packageName)) {
                backgroundPermission = sa.getNonResourceString(
                        com.android.internal.R.styleable
                                .AndroidManifestPermission_backgroundPermission);
            } else {
                Slog.w(TAG, owner.packageName + " defines a background permission. Only the "
                        + "'android' package can do that.");
            }
        }

        Permission perm = new Permission(owner, backgroundPermission);
        if (!parsePackageItemInfo(owner, perm.info, outError,
                "<permission>", sa, true /*nameRequired*/,
                com.android.internal.R.styleable.AndroidManifestPermission_name,
                com.android.internal.R.styleable.AndroidManifestPermission_label,
                com.android.internal.R.styleable.AndroidManifestPermission_icon,
                com.android.internal.R.styleable.AndroidManifestPermission_roundIcon,
                com.android.internal.R.styleable.AndroidManifestPermission_logo,
                com.android.internal.R.styleable.AndroidManifestPermission_banner)) {
            sa.recycle();
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        // Note: don't allow this value to be a reference to a resource
        // that may change.
        perm.info.group = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestPermission_permissionGroup);
        if (perm.info.group != null) {
            perm.info.group = perm.info.group.intern();
        }

        perm.info.descriptionRes = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestPermission_description,
                0);

        perm.info.requestRes = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestPermission_request, 0);

        perm.info.protectionLevel = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestPermission_protectionLevel,
                PermissionInfo.PROTECTION_NORMAL);

        perm.info.flags = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestPermission_permissionFlags, 0);

        // For now only platform runtime permissions can be restricted
        if (!perm.info.isRuntime() || !"android".equals(perm.info.packageName)) {
            perm.info.flags &= ~PermissionInfo.FLAG_HARD_RESTRICTED;
            perm.info.flags &= ~PermissionInfo.FLAG_SOFT_RESTRICTED;
        } else {
            // The platform does not get to specify conflicting permissions
            if ((perm.info.flags & PermissionInfo.FLAG_HARD_RESTRICTED) != 0
                    && (perm.info.flags & PermissionInfo.FLAG_SOFT_RESTRICTED) != 0) {
                throw new IllegalStateException("Permission cannot be both soft and hard"
                        + " restricted: " + perm.info.name);
            }
        }

        sa.recycle();

        if (perm.info.protectionLevel == -1) {
            outError[0] = "<permission> does not specify protectionLevel";
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        perm.info.protectionLevel = PermissionInfo.fixProtectionLevel(perm.info.protectionLevel);

        if (perm.info.getProtectionFlags() != 0) {
            if ( (perm.info.protectionLevel&PermissionInfo.PROTECTION_FLAG_INSTANT) == 0
                    && (perm.info.protectionLevel&PermissionInfo.PROTECTION_FLAG_RUNTIME_ONLY) == 0
                    && (perm.info.protectionLevel&PermissionInfo.PROTECTION_MASK_BASE) !=
                    PermissionInfo.PROTECTION_SIGNATURE) {
                outError[0] = "<permission>  protectionLevel specifies a non-instant flag but is "
                        + "not based on signature type";
                mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                return false;
            }
        }

        if (!parseAllMetaData(res, parser, "<permission>", perm, outError)) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        owner.permissions.add(perm);

        return true;
    }

    private boolean parsePermissionTree(Package owner, Resources res,
            XmlResourceParser parser, String[] outError)
        throws XmlPullParserException, IOException {
        Permission perm = new Permission(owner, (String) null);

        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestPermissionTree);

        if (!parsePackageItemInfo(owner, perm.info, outError,
                "<permission-tree>", sa, true /*nameRequired*/,
                com.android.internal.R.styleable.AndroidManifestPermissionTree_name,
                com.android.internal.R.styleable.AndroidManifestPermissionTree_label,
                com.android.internal.R.styleable.AndroidManifestPermissionTree_icon,
                com.android.internal.R.styleable.AndroidManifestPermissionTree_roundIcon,
                com.android.internal.R.styleable.AndroidManifestPermissionTree_logo,
                com.android.internal.R.styleable.AndroidManifestPermissionTree_banner)) {
            sa.recycle();
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        sa.recycle();

        int index = perm.info.name.indexOf('.');
        if (index > 0) {
            index = perm.info.name.indexOf('.', index+1);
        }
        if (index < 0) {
            outError[0] = "<permission-tree> name has less than three segments: "
                + perm.info.name;
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        perm.info.descriptionRes = 0;
        perm.info.requestRes = 0;
        perm.info.protectionLevel = PermissionInfo.PROTECTION_NORMAL;
        perm.tree = true;

        if (!parseAllMetaData(res, parser, "<permission-tree>", perm,
                outError)) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        owner.permissions.add(perm);

        return true;
    }

    private Instrumentation parseInstrumentation(Package owner, Resources res,
            XmlResourceParser parser, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestInstrumentation);

        if (mParseInstrumentationArgs == null) {
            mParseInstrumentationArgs = new ParsePackageItemArgs(owner, outError,
                    com.android.internal.R.styleable.AndroidManifestInstrumentation_name,
                    com.android.internal.R.styleable.AndroidManifestInstrumentation_label,
                    com.android.internal.R.styleable.AndroidManifestInstrumentation_icon,
                    com.android.internal.R.styleable.AndroidManifestInstrumentation_roundIcon,
                    com.android.internal.R.styleable.AndroidManifestInstrumentation_logo,
                    com.android.internal.R.styleable.AndroidManifestInstrumentation_banner);
            mParseInstrumentationArgs.tag = "<instrumentation>";
        }

        mParseInstrumentationArgs.sa = sa;

        Instrumentation a = new Instrumentation(mParseInstrumentationArgs,
                new InstrumentationInfo());
        if (outError[0] != null) {
            sa.recycle();
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        String str;
        // Note: don't allow this value to be a reference to a resource
        // that may change.
        str = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestInstrumentation_targetPackage);
        a.info.targetPackage = str != null ? str.intern() : null;

        str = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestInstrumentation_targetProcesses);
        a.info.targetProcesses = str != null ? str.intern() : null;

        a.info.handleProfiling = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestInstrumentation_handleProfiling,
                false);

        a.info.functionalTest = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestInstrumentation_functionalTest,
                false);

        sa.recycle();

        if (a.info.targetPackage == null) {
            outError[0] = "<instrumentation> does not specify targetPackage";
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        if (!parseAllMetaData(res, parser, "<instrumentation>", a,
                outError)) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return null;
        }

        owner.instrumentation.add(a);

        return a;
    }

    /**
     * Parse the {@code application} XML tree at the current parse location in a
     * <em>base APK</em> manifest.
     * <p>
     * When adding new features, carefully consider if they should also be
     * supported by split APKs.
     */
    @UnsupportedAppUsage
    private boolean parseBaseApplication(Package owner, Resources res,
            XmlResourceParser parser, int flags, String[] outError)
        throws XmlPullParserException, IOException {
        final ApplicationInfo ai = owner.applicationInfo;
        final String pkgName = owner.applicationInfo.packageName;

        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestApplication);

        ai.iconRes = sa.getResourceId(
            com.android.internal.R.styleable.AndroidManifestApplication_icon, 0);
        ai.roundIconRes = sa.getResourceId(
            com.android.internal.R.styleable.AndroidManifestApplication_roundIcon, 0);

        if (!parsePackageItemInfo(owner, ai, outError,
                "<application>", sa, false /*nameRequired*/,
                com.android.internal.R.styleable.AndroidManifestApplication_name,
                com.android.internal.R.styleable.AndroidManifestApplication_label,
                com.android.internal.R.styleable.AndroidManifestApplication_icon,
                com.android.internal.R.styleable.AndroidManifestApplication_roundIcon,
                com.android.internal.R.styleable.AndroidManifestApplication_logo,
                com.android.internal.R.styleable.AndroidManifestApplication_banner)) {
            sa.recycle();
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        if (ai.name != null) {
            ai.className = ai.name;
        }

        String manageSpaceActivity = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestApplication_manageSpaceActivity,
                Configuration.NATIVE_CONFIG_VERSION);
        if (manageSpaceActivity != null) {
            ai.manageSpaceActivityName = buildClassName(pkgName, manageSpaceActivity,
                    outError);
        }

        boolean allowBackup = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_allowBackup, true);
        if (allowBackup) {
            ai.flags |= ApplicationInfo.FLAG_ALLOW_BACKUP;

            // backupAgent, killAfterRestore, fullBackupContent, backupInForeground,
            // and restoreAnyVersion are only relevant if backup is possible for the
            // given application.
            String backupAgent = sa.getNonConfigurationString(
                    com.android.internal.R.styleable.AndroidManifestApplication_backupAgent,
                    Configuration.NATIVE_CONFIG_VERSION);
            if (backupAgent != null) {
                ai.backupAgentName = buildClassName(pkgName, backupAgent, outError);
                if (DEBUG_BACKUP) {
                    Slog.v(TAG, "android:backupAgent = " + ai.backupAgentName
                            + " from " + pkgName + "+" + backupAgent);
                }

                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestApplication_killAfterRestore,
                        true)) {
                    ai.flags |= ApplicationInfo.FLAG_KILL_AFTER_RESTORE;
                }
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestApplication_restoreAnyVersion,
                        false)) {
                    ai.flags |= ApplicationInfo.FLAG_RESTORE_ANY_VERSION;
                }
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestApplication_fullBackupOnly,
                        false)) {
                    ai.flags |= ApplicationInfo.FLAG_FULL_BACKUP_ONLY;
                }
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestApplication_backupInForeground,
                        false)) {
                    ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_BACKUP_IN_FOREGROUND;
                }
            }

            TypedValue v = sa.peekValue(
                    com.android.internal.R.styleable.AndroidManifestApplication_fullBackupContent);
            if (v != null && (ai.fullBackupContent = v.resourceId) == 0) {
                if (DEBUG_BACKUP) {
                    Slog.v(TAG, "fullBackupContent specified as boolean=" +
                            (v.data == 0 ? "false" : "true"));
                }
                // "false" => -1, "true" => 0
                ai.fullBackupContent = (v.data == 0 ? -1 : 0);
            }
            if (DEBUG_BACKUP) {
                Slog.v(TAG, "fullBackupContent=" + ai.fullBackupContent + " for " + pkgName);
            }
        }

        ai.theme = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestApplication_theme, 0);
        ai.descriptionRes = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestApplication_description, 0);

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_persistent,
                false)) {
            // Check if persistence is based on a feature being present
            final String requiredFeature = sa.getNonResourceString(com.android.internal.R.styleable
                    .AndroidManifestApplication_persistentWhenFeatureAvailable);
            if (requiredFeature == null || mCallback.hasFeature(requiredFeature)) {
                ai.flags |= ApplicationInfo.FLAG_PERSISTENT;
            }
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_requiredForAllUsers,
                false)) {
            owner.mRequiredForAllUsers = true;
        }

        String restrictedAccountType = sa.getString(com.android.internal.R.styleable
                .AndroidManifestApplication_restrictedAccountType);
        if (restrictedAccountType != null && restrictedAccountType.length() > 0) {
            owner.mRestrictedAccountType = restrictedAccountType;
        }

        String requiredAccountType = sa.getString(com.android.internal.R.styleable
                .AndroidManifestApplication_requiredAccountType);
        if (requiredAccountType != null && requiredAccountType.length() > 0) {
            owner.mRequiredAccountType = requiredAccountType;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_debuggable,
                false)) {
            ai.flags |= ApplicationInfo.FLAG_DEBUGGABLE;
            // Debuggable implies profileable
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_PROFILEABLE_BY_SHELL;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_vmSafeMode,
                false)) {
            ai.flags |= ApplicationInfo.FLAG_VM_SAFE_MODE;
        }

        owner.baseHardwareAccelerated = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_hardwareAccelerated,
                owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.ICE_CREAM_SANDWICH);
        if (owner.baseHardwareAccelerated) {
            ai.flags |= ApplicationInfo.FLAG_HARDWARE_ACCELERATED;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_hasCode,
                true)) {
            ai.flags |= ApplicationInfo.FLAG_HAS_CODE;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_allowTaskReparenting,
                false)) {
            ai.flags |= ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_allowClearUserData,
                true)) {
            ai.flags |= ApplicationInfo.FLAG_ALLOW_CLEAR_USER_DATA;
        }

        // The parent package controls installation, hence specify test only installs.
        if (owner.parentPackage == null) {
            if (sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestApplication_testOnly,
                    false)) {
                ai.flags |= ApplicationInfo.FLAG_TEST_ONLY;
            }
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_largeHeap,
                false)) {
            ai.flags |= ApplicationInfo.FLAG_LARGE_HEAP;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_usesCleartextTraffic,
                owner.applicationInfo.targetSdkVersion < Build.VERSION_CODES.P)) {
            ai.flags |= ApplicationInfo.FLAG_USES_CLEARTEXT_TRAFFIC;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_supportsRtl,
                false /* default is no RTL support*/)) {
            ai.flags |= ApplicationInfo.FLAG_SUPPORTS_RTL;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_multiArch,
                false)) {
            ai.flags |= ApplicationInfo.FLAG_MULTIARCH;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_extractNativeLibs,
                true)) {
            ai.flags |= ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS;
        }

        if (sa.getBoolean(
                R.styleable.AndroidManifestApplication_useEmbeddedDex,
                false)) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_USE_EMBEDDED_DEX;
        }

        if (sa.getBoolean(
                R.styleable.AndroidManifestApplication_defaultToDeviceProtectedStorage,
                false)) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_DEFAULT_TO_DEVICE_PROTECTED_STORAGE;
        }
        if (sa.getBoolean(
                R.styleable.AndroidManifestApplication_directBootAware,
                false)) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_DIRECT_BOOT_AWARE;
        }

        if (sa.hasValueOrEmpty(R.styleable.AndroidManifestApplication_resizeableActivity)) {
            if (sa.getBoolean(R.styleable.AndroidManifestApplication_resizeableActivity, true)) {
                ai.privateFlags |= PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE;
            } else {
                ai.privateFlags |= PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE;
            }
        } else if (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.N) {
            ai.privateFlags |= PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable
                        .AndroidManifestApplication_allowClearUserDataOnFailedRestore,
                true)) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_ALLOW_CLEAR_USER_DATA_ON_FAILED_RESTORE;
        }

        if (sa.getBoolean(
                R.styleable.AndroidManifestApplication_allowAudioPlaybackCapture,
                owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.Q)) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_ALLOW_AUDIO_PLAYBACK_CAPTURE;
        }

        if (sa.getBoolean(
                R.styleable.AndroidManifestApplication_requestLegacyExternalStorage,
                owner.applicationInfo.targetSdkVersion < Build.VERSION_CODES.Q)) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_REQUEST_LEGACY_EXTERNAL_STORAGE;
        }

        if (sa.getBoolean(
                R.styleable.AndroidManifestApplication_allowNativeHeapPointerTagging, true)) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_ALLOW_NATIVE_HEAP_POINTER_TAGGING;
        }

        ai.maxAspectRatio = sa.getFloat(R.styleable.AndroidManifestApplication_maxAspectRatio, 0);
        ai.minAspectRatio = sa.getFloat(R.styleable.AndroidManifestApplication_minAspectRatio, 0);

        ai.networkSecurityConfigRes = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestApplication_networkSecurityConfig,
                0);
        ai.category = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestApplication_appCategory,
                ApplicationInfo.CATEGORY_UNDEFINED);

        String str;
        str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestApplication_permission, 0);
        ai.permission = (str != null && str.length() > 0) ? str.intern() : null;

        if (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.FROYO) {
            str = sa.getNonConfigurationString(
                    com.android.internal.R.styleable.AndroidManifestApplication_taskAffinity,
                    Configuration.NATIVE_CONFIG_VERSION);
        } else {
            // Some older apps have been seen to use a resource reference
            // here that on older builds was ignored (with a warning).  We
            // need to continue to do this for them so they don't break.
            str = sa.getNonResourceString(
                    com.android.internal.R.styleable.AndroidManifestApplication_taskAffinity);
        }
        ai.taskAffinity = buildTaskAffinityName(ai.packageName, ai.packageName,
                str, outError);
        String factory = sa.getNonResourceString(
                com.android.internal.R.styleable.AndroidManifestApplication_appComponentFactory);
        if (factory != null) {
            ai.appComponentFactory = buildClassName(ai.packageName, factory, outError);
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_usesNonSdkApi, false)) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_USES_NON_SDK_API;
        }

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_hasFragileUserData,
                false)) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_HAS_FRAGILE_USER_DATA;
        }

        if (outError[0] == null) {
            CharSequence pname;
            if (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.FROYO) {
                pname = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestApplication_process,
                        Configuration.NATIVE_CONFIG_VERSION);
            } else {
                // Some older apps have been seen to use a resource reference
                // here that on older builds was ignored (with a warning).  We
                // need to continue to do this for them so they don't break.
                pname = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestApplication_process);
            }
            ai.processName = buildProcessName(ai.packageName, null, pname,
                    flags, mSeparateProcesses, outError);

            ai.enabled = sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestApplication_enabled, true);

            if (sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestApplication_isGame, false)) {
                ai.flags |= ApplicationInfo.FLAG_IS_GAME;
            }

            if (sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestApplication_cantSaveState,
                    false)) {
                ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE;

                // A heavy-weight application can not be in a custom process.
                // We can do direct compare because we intern all strings.
                if (ai.processName != null && !ai.processName.equals(ai.packageName)) {
                    outError[0] = "cantSaveState applications can not use custom processes";
                }
            }
        }

        ai.uiOptions = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestApplication_uiOptions, 0);

        ai.classLoaderName = sa.getString(
            com.android.internal.R.styleable.AndroidManifestApplication_classLoader);
        if (ai.classLoaderName != null
                && !ClassLoaderFactory.isValidClassLoaderName(ai.classLoaderName)) {
            outError[0] = "Invalid class loader name: " + ai.classLoaderName;
        }

        ai.zygotePreloadName = sa.getString(
                com.android.internal.R.styleable.AndroidManifestApplication_zygotePreloadName);

        sa.recycle();

        if (outError[0] != null) {
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        final int innerDepth = parser.getDepth();
        // IMPORTANT: These must only be cached for a single <application> to avoid components
        // getting added to the wrong package.
        final CachedComponentArgs cachedArgs = new CachedComponentArgs();
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
            if (tagName.equals("activity")) {
                Activity a = parseActivity(owner, res, parser, flags, outError, cachedArgs, false,
                        owner.baseHardwareAccelerated);
                if (a == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                hasActivityOrder |= (a.order != 0);
                owner.activities.add(a);

            } else if (tagName.equals("receiver")) {
                Activity a = parseActivity(owner, res, parser, flags, outError, cachedArgs,
                        true, false);
                if (a == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                hasReceiverOrder |= (a.order != 0);
                owner.receivers.add(a);

            } else if (tagName.equals("service")) {
                Service s = parseService(owner, res, parser, flags, outError, cachedArgs);
                if (s == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                hasServiceOrder |= (s.order != 0);
                owner.services.add(s);

            } else if (tagName.equals("provider")) {
                Provider p = parseProvider(owner, res, parser, flags, outError, cachedArgs);
                if (p == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.providers.add(p);

            } else if (tagName.equals("activity-alias")) {
                Activity a = parseActivityAlias(owner, res, parser, flags, outError, cachedArgs);
                if (a == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                hasActivityOrder |= (a.order != 0);
                owner.activities.add(a);

            } else if (parser.getName().equals("meta-data")) {
                // note: application meta-data is stored off to the side, so it can
                // remain null in the primary copy (we like to avoid extra copies because
                // it can be large)
                if ((owner.mAppMetaData = parseMetaData(res, parser, owner.mAppMetaData,
                        outError)) == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }
            } else if (tagName.equals("static-library")) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestStaticLibrary);

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                final String lname = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestStaticLibrary_name);
                final int version = sa.getInt(
                        com.android.internal.R.styleable.AndroidManifestStaticLibrary_version, -1);
                final int versionMajor = sa.getInt(
                        com.android.internal.R.styleable.AndroidManifestStaticLibrary_versionMajor,
                        0);

                sa.recycle();

                // Since the app canot run without a static lib - fail if malformed
                if (lname == null || version < 0) {
                    outError[0] = "Bad static-library declaration name: " + lname
                            + " version: " + version;
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    XmlUtils.skipCurrentTag(parser);
                    return false;
                }

                if (owner.mSharedUserId != null) {
                    outError[0] = "sharedUserId not allowed in static shared library";
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_BAD_SHARED_USER_ID;
                    XmlUtils.skipCurrentTag(parser);
                    return false;
                }

                if (owner.staticSharedLibName != null) {
                    outError[0] = "Multiple static-shared libs for package " + pkgName;
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    XmlUtils.skipCurrentTag(parser);
                    return false;
                }

                owner.staticSharedLibName = lname.intern();
                if (version >= 0) {
                    owner.staticSharedLibVersion =
                            PackageInfo.composeLongVersionCode(versionMajor, version);
                } else {
                    owner.staticSharedLibVersion = version;
                }
                ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_STATIC_SHARED_LIBRARY;

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("library")) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestLibrary);

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                String lname = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestLibrary_name);

                sa.recycle();

                if (lname != null) {
                    lname = lname.intern();
                    if (!ArrayUtils.contains(owner.libraryNames, lname)) {
                        owner.libraryNames = ArrayUtils.add(
                                owner.libraryNames, lname);
                    }
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("uses-static-library")) {
                if (!parseUsesStaticLibrary(owner, res, parser, outError)) {
                    return false;
                }

            } else if (tagName.equals("uses-library")) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestUsesLibrary);

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                String lname = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestUsesLibrary_name);
                boolean req = sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestUsesLibrary_required,
                        true);

                sa.recycle();

                if (lname != null) {
                    lname = lname.intern();
                    if (req) {
                        owner.usesLibraries = ArrayUtils.add(owner.usesLibraries, lname);
                    } else {
                        owner.usesOptionalLibraries = ArrayUtils.add(
                                owner.usesOptionalLibraries, lname);
                    }
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("uses-package")) {
                // Dependencies for app installers; we don't currently try to
                // enforce this.
                XmlUtils.skipCurrentTag(parser);
            } else if (tagName.equals("profileable")) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestProfileable);
                if (sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestProfileable_shell, false)) {
                    ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_PROFILEABLE_BY_SHELL;
                }
                XmlUtils.skipCurrentTag(parser);
            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <application>: " + tagName
                            + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <application>: " + tagName;
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }
            }
        }

        if (TextUtils.isEmpty(owner.staticSharedLibName)) {
            // Add a hidden app detail activity to normal apps which forwards user to App Details
            // page.
            Activity a = generateAppDetailsHiddenActivity(owner, flags, outError,
                    owner.baseHardwareAccelerated);
            owner.activities.add(a);
        }

        if (hasActivityOrder) {
            Collections.sort(owner.activities, (a1, a2) -> Integer.compare(a2.order, a1.order));
        }
        if (hasReceiverOrder) {
            Collections.sort(owner.receivers,  (r1, r2) -> Integer.compare(r2.order, r1.order));
        }
        if (hasServiceOrder) {
            Collections.sort(owner.services,  (s1, s2) -> Integer.compare(s2.order, s1.order));
        }
        // Must be ran after the entire {@link ApplicationInfo} has been fully processed and after
        // every activity info has had a chance to set it from its attributes.
        setMaxAspectRatio(owner);
        setMinAspectRatio(owner);

        if (hasDomainURLs(owner)) {
            owner.applicationInfo.privateFlags |= ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS;
        } else {
            owner.applicationInfo.privateFlags &= ~ApplicationInfo.PRIVATE_FLAG_HAS_DOMAIN_URLS;
        }

        return true;
    }

    /**
     * Check if one of the IntentFilter as both actions DEFAULT / VIEW and a HTTP/HTTPS data URI
     */
    private static boolean hasDomainURLs(Package pkg) {
        if (pkg == null || pkg.activities == null) return false;
        final ArrayList<Activity> activities = pkg.activities;
        final int countActivities = activities.size();
        for (int n=0; n<countActivities; n++) {
            Activity activity = activities.get(n);
            ArrayList<ActivityIntentInfo> filters = activity.intents;
            if (filters == null) continue;
            final int countFilters = filters.size();
            for (int m=0; m<countFilters; m++) {
                ActivityIntentInfo aii = filters.get(m);
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
     * Parse the {@code application} XML tree at the current parse location in a
     * <em>split APK</em> manifest.
     * <p>
     * Note that split APKs have many more restrictions on what they're capable
     * of doing, so many valid features of a base APK have been carefully
     * omitted here.
     */
    private boolean parseSplitApplication(Package owner, Resources res, XmlResourceParser parser,
            int flags, int splitIndex, String[] outError)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestApplication);

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestApplication_hasCode, true)) {
            owner.splitFlags[splitIndex] |= ApplicationInfo.FLAG_HAS_CODE;
        }

        final String classLoaderName = sa.getString(
                com.android.internal.R.styleable.AndroidManifestApplication_classLoader);
        if (classLoaderName == null || ClassLoaderFactory.isValidClassLoaderName(classLoaderName)) {
            owner.applicationInfo.splitClassLoaderNames[splitIndex] = classLoaderName;
        } else {
            outError[0] = "Invalid class loader name: " + classLoaderName;
            mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
            return false;
        }

        final int innerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > innerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            ComponentInfo parsedComponent = null;

            // IMPORTANT: These must only be cached for a single <application> to avoid components
            // getting added to the wrong package.
            final CachedComponentArgs cachedArgs = new CachedComponentArgs();
            String tagName = parser.getName();
            if (tagName.equals("activity")) {
                Activity a = parseActivity(owner, res, parser, flags, outError, cachedArgs, false,
                        owner.baseHardwareAccelerated);
                if (a == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.activities.add(a);
                parsedComponent = a.info;

            } else if (tagName.equals("receiver")) {
                Activity a = parseActivity(owner, res, parser, flags, outError, cachedArgs,
                        true, false);
                if (a == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.receivers.add(a);
                parsedComponent = a.info;

            } else if (tagName.equals("service")) {
                Service s = parseService(owner, res, parser, flags, outError, cachedArgs);
                if (s == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.services.add(s);
                parsedComponent = s.info;

            } else if (tagName.equals("provider")) {
                Provider p = parseProvider(owner, res, parser, flags, outError, cachedArgs);
                if (p == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.providers.add(p);
                parsedComponent = p.info;

            } else if (tagName.equals("activity-alias")) {
                Activity a = parseActivityAlias(owner, res, parser, flags, outError, cachedArgs);
                if (a == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

                owner.activities.add(a);
                parsedComponent = a.info;

            } else if (parser.getName().equals("meta-data")) {
                // note: application meta-data is stored off to the side, so it can
                // remain null in the primary copy (we like to avoid extra copies because
                // it can be large)
                if ((owner.mAppMetaData = parseMetaData(res, parser, owner.mAppMetaData,
                        outError)) == null) {
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }

            } else if (tagName.equals("uses-static-library")) {
                if (!parseUsesStaticLibrary(owner, res, parser, outError)) {
                    return false;
                }

            } else if (tagName.equals("uses-library")) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestUsesLibrary);

                // Note: don't allow this value to be a reference to a resource
                // that may change.
                String lname = sa.getNonResourceString(
                        com.android.internal.R.styleable.AndroidManifestUsesLibrary_name);
                boolean req = sa.getBoolean(
                        com.android.internal.R.styleable.AndroidManifestUsesLibrary_required,
                        true);

                sa.recycle();

                if (lname != null) {
                    lname = lname.intern();
                    if (req) {
                        // Upgrade to treat as stronger constraint
                        owner.usesLibraries = ArrayUtils.add(owner.usesLibraries, lname);
                        owner.usesOptionalLibraries = ArrayUtils.remove(
                                owner.usesOptionalLibraries, lname);
                    } else {
                        // Ignore if someone already defined as required
                        if (!ArrayUtils.contains(owner.usesLibraries, lname)) {
                            owner.usesOptionalLibraries = ArrayUtils.add(
                                    owner.usesOptionalLibraries, lname);
                        }
                    }
                }

                XmlUtils.skipCurrentTag(parser);

            } else if (tagName.equals("uses-package")) {
                // Dependencies for app installers; we don't currently try to
                // enforce this.
                XmlUtils.skipCurrentTag(parser);

            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <application>: " + tagName
                            + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <application>: " + tagName;
                    mParseError = PackageManager.INSTALL_PARSE_FAILED_MANIFEST_MALFORMED;
                    return false;
                }
            }

            if (parsedComponent != null && parsedComponent.splitName == null) {
                // If the loaded component did not specify a split, inherit the split name
                // based on the split it is defined in.
                // This is used to later load the correct split when starting this
                // component.
                parsedComponent.splitName = owner.splitNames[splitIndex];
            }
        }

        return true;
    }

    private static boolean parsePackageItemInfo(Package owner, PackageItemInfo outInfo,
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
            String outInfoName
                = buildClassName(owner.applicationInfo.packageName, name, outError);
            if (PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME.equals(outInfoName)) {
                outError[0] = tag + " invalid android:name";
                return false;
            }
            outInfo.name = outInfoName;
            if (outInfoName == null) {
                return false;
            }
        }

        int roundIconVal = sUseRoundIcon ? sa.getResourceId(roundIconRes, 0) : 0;
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
        if (v != null && (outInfo.labelRes=v.resourceId) == 0) {
            outInfo.nonLocalizedLabel = v.coerceToString();
        }

        outInfo.packageName = owner.packageName;

        return true;
    }

    /**
     * Generate activity object that forwards user to App Details page automatically.
     * This activity should be invisible to user and user should not know or see it.
     */
    private @NonNull PackageParser.Activity generateAppDetailsHiddenActivity(
            PackageParser.Package owner, int flags, String[] outError,
            boolean hardwareAccelerated) {

        // Build custom App Details activity info instead of parsing it from xml
        Activity a = new Activity(owner, PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME,
                new ActivityInfo());
        a.owner = owner;
        a.setPackageName(owner.packageName);

        a.info.theme = android.R.style.Theme_NoDisplay;
        a.info.exported = true;
        a.info.name = PackageManager.APP_DETAILS_ACTIVITY_CLASS_NAME;
        a.info.processName = owner.applicationInfo.processName;
        a.info.uiOptions = a.info.applicationInfo.uiOptions;
        a.info.taskAffinity = buildTaskAffinityName(owner.packageName, owner.packageName,
                ":app_details", outError);
        a.info.enabled = true;
        a.info.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
        a.info.documentLaunchMode = ActivityInfo.DOCUMENT_LAUNCH_NONE;
        a.info.maxRecents = ActivityTaskManager.getDefaultAppRecentsLimitStatic();
        a.info.configChanges = getActivityConfigChanges(0, 0);
        a.info.softInputMode = 0;
        a.info.persistableMode = ActivityInfo.PERSIST_NEVER;
        a.info.screenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;
        a.info.resizeMode = RESIZE_MODE_FORCE_RESIZEABLE;
        a.info.lockTaskLaunchMode = 0;
        a.info.directBootAware = false;
        a.info.rotationAnimation = ROTATION_ANIMATION_UNSPECIFIED;
        a.info.colorMode = ActivityInfo.COLOR_MODE_DEFAULT;
        if (hardwareAccelerated) {
            a.info.flags |= ActivityInfo.FLAG_HARDWARE_ACCELERATED;
        }
        return a;
    }

    private Activity parseActivity(Package owner, Resources res,
            XmlResourceParser parser, int flags, String[] outError, CachedComponentArgs cachedArgs,
            boolean receiver, boolean hardwareAccelerated)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser, R.styleable.AndroidManifestActivity);

        if (cachedArgs.mActivityArgs == null) {
            cachedArgs.mActivityArgs = new ParseComponentArgs(owner, outError,
                    R.styleable.AndroidManifestActivity_name,
                    R.styleable.AndroidManifestActivity_label,
                    R.styleable.AndroidManifestActivity_icon,
                    R.styleable.AndroidManifestActivity_roundIcon,
                    R.styleable.AndroidManifestActivity_logo,
                    R.styleable.AndroidManifestActivity_banner,
                    mSeparateProcesses,
                    R.styleable.AndroidManifestActivity_process,
                    R.styleable.AndroidManifestActivity_description,
                    R.styleable.AndroidManifestActivity_enabled);
        }

        cachedArgs.mActivityArgs.tag = receiver ? "<receiver>" : "<activity>";
        cachedArgs.mActivityArgs.sa = sa;
        cachedArgs.mActivityArgs.flags = flags;

        Activity a = new Activity(cachedArgs.mActivityArgs, new ActivityInfo());
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        boolean setExported = sa.hasValue(R.styleable.AndroidManifestActivity_exported);
        if (setExported) {
            a.info.exported = sa.getBoolean(R.styleable.AndroidManifestActivity_exported, false);
        }

        a.info.theme = sa.getResourceId(R.styleable.AndroidManifestActivity_theme, 0);

        a.info.uiOptions = sa.getInt(R.styleable.AndroidManifestActivity_uiOptions,
                a.info.applicationInfo.uiOptions);

        String parentName = sa.getNonConfigurationString(
                R.styleable.AndroidManifestActivity_parentActivityName,
                Configuration.NATIVE_CONFIG_VERSION);
        if (parentName != null) {
            String parentClassName = buildClassName(a.info.packageName, parentName, outError);
            if (outError[0] == null) {
                a.info.parentActivityName = parentClassName;
            } else {
                Log.e(TAG, "Activity " + a.info.name + " specified invalid parentActivityName " +
                        parentName);
                outError[0] = null;
            }
        }

        String str;
        str = sa.getNonConfigurationString(R.styleable.AndroidManifestActivity_permission, 0);
        if (str == null) {
            a.info.permission = owner.applicationInfo.permission;
        } else {
            a.info.permission = str.length() > 0 ? str.toString().intern() : null;
        }

        str = sa.getNonConfigurationString(
                R.styleable.AndroidManifestActivity_taskAffinity,
                Configuration.NATIVE_CONFIG_VERSION);
        a.info.taskAffinity = buildTaskAffinityName(owner.applicationInfo.packageName,
                owner.applicationInfo.taskAffinity, str, outError);

        a.info.splitName =
                sa.getNonConfigurationString(R.styleable.AndroidManifestActivity_splitName, 0);

        a.info.flags = 0;
        if (sa.getBoolean(
                R.styleable.AndroidManifestActivity_multiprocess, false)) {
            a.info.flags |= ActivityInfo.FLAG_MULTIPROCESS;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_finishOnTaskLaunch, false)) {
            a.info.flags |= ActivityInfo.FLAG_FINISH_ON_TASK_LAUNCH;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_clearTaskOnLaunch, false)) {
            a.info.flags |= ActivityInfo.FLAG_CLEAR_TASK_ON_LAUNCH;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_noHistory, false)) {
            a.info.flags |= ActivityInfo.FLAG_NO_HISTORY;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_alwaysRetainTaskState, false)) {
            a.info.flags |= ActivityInfo.FLAG_ALWAYS_RETAIN_TASK_STATE;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_stateNotNeeded, false)) {
            a.info.flags |= ActivityInfo.FLAG_STATE_NOT_NEEDED;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_excludeFromRecents, false)) {
            a.info.flags |= ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_allowTaskReparenting,
                (owner.applicationInfo.flags&ApplicationInfo.FLAG_ALLOW_TASK_REPARENTING) != 0)) {
            a.info.flags |= ActivityInfo.FLAG_ALLOW_TASK_REPARENTING;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_finishOnCloseSystemDialogs, false)) {
            a.info.flags |= ActivityInfo.FLAG_FINISH_ON_CLOSE_SYSTEM_DIALOGS;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_showOnLockScreen, false)
                || sa.getBoolean(R.styleable.AndroidManifestActivity_showForAllUsers, false)) {
            a.info.flags |= ActivityInfo.FLAG_SHOW_FOR_ALL_USERS;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_immersive, false)) {
            a.info.flags |= ActivityInfo.FLAG_IMMERSIVE;
        }

        if (sa.getBoolean(R.styleable.AndroidManifestActivity_systemUserOnly, false)) {
            a.info.flags |= ActivityInfo.FLAG_SYSTEM_USER_ONLY;
        }

        if (!receiver) {
            if (sa.getBoolean(R.styleable.AndroidManifestActivity_hardwareAccelerated,
                    hardwareAccelerated)) {
                a.info.flags |= ActivityInfo.FLAG_HARDWARE_ACCELERATED;
            }

            a.info.launchMode = sa.getInt(
                    R.styleable.AndroidManifestActivity_launchMode, ActivityInfo.LAUNCH_MULTIPLE);
            a.info.documentLaunchMode = sa.getInt(
                    R.styleable.AndroidManifestActivity_documentLaunchMode,
                    ActivityInfo.DOCUMENT_LAUNCH_NONE);
            a.info.maxRecents = sa.getInt(
                    R.styleable.AndroidManifestActivity_maxRecents,
                    ActivityTaskManager.getDefaultAppRecentsLimitStatic());
            a.info.configChanges = getActivityConfigChanges(
                    sa.getInt(R.styleable.AndroidManifestActivity_configChanges, 0),
                    sa.getInt(R.styleable.AndroidManifestActivity_recreateOnConfigChanges, 0));
            a.info.softInputMode = sa.getInt(
                    R.styleable.AndroidManifestActivity_windowSoftInputMode, 0);

            a.info.persistableMode = sa.getInteger(
                    R.styleable.AndroidManifestActivity_persistableMode,
                    ActivityInfo.PERSIST_ROOT_ONLY);

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_allowEmbedded, false)) {
                a.info.flags |= ActivityInfo.FLAG_ALLOW_EMBEDDED;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_autoRemoveFromRecents, false)) {
                a.info.flags |= ActivityInfo.FLAG_AUTO_REMOVE_FROM_RECENTS;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_relinquishTaskIdentity, false)) {
                a.info.flags |= ActivityInfo.FLAG_RELINQUISH_TASK_IDENTITY;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_resumeWhilePausing, false)) {
                a.info.flags |= ActivityInfo.FLAG_RESUME_WHILE_PAUSING;
            }

            a.info.screenOrientation = sa.getInt(
                    R.styleable.AndroidManifestActivity_screenOrientation,
                    SCREEN_ORIENTATION_UNSPECIFIED);

            setActivityResizeMode(a.info, sa, owner);

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_supportsPictureInPicture,
                    false)) {
                a.info.flags |= FLAG_SUPPORTS_PICTURE_IN_PICTURE;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_alwaysFocusable, false)) {
                a.info.flags |= FLAG_ALWAYS_FOCUSABLE;
            }

            if (sa.hasValue(R.styleable.AndroidManifestActivity_maxAspectRatio)
                    && sa.getType(R.styleable.AndroidManifestActivity_maxAspectRatio)
                    == TypedValue.TYPE_FLOAT) {
                a.setMaxAspectRatio(sa.getFloat(R.styleable.AndroidManifestActivity_maxAspectRatio,
                        0 /*default*/));
            }

            if (sa.hasValue(R.styleable.AndroidManifestActivity_minAspectRatio)
                    && sa.getType(R.styleable.AndroidManifestActivity_minAspectRatio)
                    == TypedValue.TYPE_FLOAT) {
                a.setMinAspectRatio(sa.getFloat(R.styleable.AndroidManifestActivity_minAspectRatio,
                        0 /*default*/));
            }

            a.info.lockTaskLaunchMode =
                    sa.getInt(R.styleable.AndroidManifestActivity_lockTaskMode, 0);

            a.info.directBootAware = sa.getBoolean(
                    R.styleable.AndroidManifestActivity_directBootAware,
                    false);

            a.info.requestedVrComponent =
                sa.getString(R.styleable.AndroidManifestActivity_enableVrMode);

            a.info.rotationAnimation =
                sa.getInt(R.styleable.AndroidManifestActivity_rotationAnimation, ROTATION_ANIMATION_UNSPECIFIED);

            a.info.colorMode = sa.getInt(R.styleable.AndroidManifestActivity_colorMode,
                    ActivityInfo.COLOR_MODE_DEFAULT);

            if (sa.getBoolean(
                        R.styleable.AndroidManifestActivity_preferMinimalPostProcessing, false)) {
                a.info.flags |= ActivityInfo.FLAG_PREFER_MINIMAL_POST_PROCESSING;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_showWhenLocked, false)) {
                a.info.flags |= ActivityInfo.FLAG_SHOW_WHEN_LOCKED;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_turnScreenOn, false)) {
                a.info.flags |= ActivityInfo.FLAG_TURN_SCREEN_ON;
            }

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_inheritShowWhenLocked, false)) {
                a.info.privateFlags |= ActivityInfo.FLAG_INHERIT_SHOW_WHEN_LOCKED;
            }
        } else {
            a.info.launchMode = ActivityInfo.LAUNCH_MULTIPLE;
            a.info.configChanges = 0;

            if (sa.getBoolean(R.styleable.AndroidManifestActivity_singleUser, false)) {
                a.info.flags |= ActivityInfo.FLAG_SINGLE_USER;
            }

            a.info.directBootAware = sa.getBoolean(
                    R.styleable.AndroidManifestActivity_directBootAware,
                    false);
        }

        if (a.info.directBootAware) {
            owner.applicationInfo.privateFlags |=
                    ApplicationInfo.PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE;
        }

        // can't make this final; we may set it later via meta-data
        boolean visibleToEphemeral =
                sa.getBoolean(R.styleable.AndroidManifestActivity_visibleToInstantApps, false);
        if (visibleToEphemeral) {
            a.info.flags |= ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP;
            owner.visibleToInstantApps = true;
        }

        sa.recycle();

        if (receiver && (owner.applicationInfo.privateFlags
                &ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE) != 0) {
            // A heavy-weight application can not have receives in its main process
            // We can do direct compare because we intern all strings.
            if (a.info.processName == owner.packageName) {
                outError[0] = "Heavy-weight applications can not have receivers in main process";
            }
        }

        if (outError[0] != null) {
            return null;
        }

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ActivityIntentInfo intent = new ActivityIntentInfo(a);
                if (!parseIntent(res, parser, true /*allowGlobs*/, true /*allowAutoVerify*/,
                        intent, outError)) {
                    return null;
                }
                if (intent.countActions() == 0) {
                    Slog.w(TAG, "No actions in intent filter at "
                            + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                } else {
                    a.order = Math.max(intent.getOrder(), a.order);
                    a.intents.add(intent);
                }
                // adjust activity flags when we implicitly expose it via a browsable filter
                final int visibility = visibleToEphemeral
                        ? IntentFilter.VISIBILITY_EXPLICIT
                        : !receiver && isImplicitlyExposedIntent(intent)
                                ? IntentFilter.VISIBILITY_IMPLICIT
                                : IntentFilter.VISIBILITY_NONE;
                intent.setVisibilityToInstantApp(visibility);
                if (intent.isVisibleToInstantApp()) {
                    a.info.flags |= ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                }
                if (intent.isImplicitlyVisibleToInstantApp()) {
                    a.info.flags |= ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP;
                }
                if (LOG_UNSAFE_BROADCASTS && receiver
                        && (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.O)) {
                    for (int i = 0; i < intent.countActions(); i++) {
                        final String action = intent.getAction(i);
                        if (action == null || !action.startsWith("android.")) continue;
                        if (!SAFE_BROADCASTS.contains(action)) {
                            Slog.w(TAG, "Broadcast " + action + " may never be delivered to "
                                    + owner.packageName + " as requested at: "
                                    + parser.getPositionDescription());
                        }
                    }
                }
            } else if (!receiver && parser.getName().equals("preferred")) {
                ActivityIntentInfo intent = new ActivityIntentInfo(a);
                if (!parseIntent(res, parser, false /*allowGlobs*/, false /*allowAutoVerify*/,
                        intent, outError)) {
                    return null;
                }
                if (intent.countActions() == 0) {
                    Slog.w(TAG, "No actions in preferred at "
                            + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                } else {
                    if (owner.preferredActivityFilters == null) {
                        owner.preferredActivityFilters = new ArrayList<ActivityIntentInfo>();
                    }
                    owner.preferredActivityFilters.add(intent);
                }
                // adjust activity flags when we implicitly expose it via a browsable filter
                final int visibility = visibleToEphemeral
                        ? IntentFilter.VISIBILITY_EXPLICIT
                        : !receiver && isImplicitlyExposedIntent(intent)
                                ? IntentFilter.VISIBILITY_IMPLICIT
                                : IntentFilter.VISIBILITY_NONE;
                intent.setVisibilityToInstantApp(visibility);
                if (intent.isVisibleToInstantApp()) {
                    a.info.flags |= ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                }
                if (intent.isImplicitlyVisibleToInstantApp()) {
                    a.info.flags |= ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP;
                }
            } else if (parser.getName().equals("meta-data")) {
                if ((a.metaData = parseMetaData(res, parser, a.metaData,
                        outError)) == null) {
                    return null;
                }
            } else if (!receiver && parser.getName().equals("layout")) {
                parseLayout(res, parser, a);
            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Problem in package " + mArchiveSourcePath + ":");
                    if (receiver) {
                        Slog.w(TAG, "Unknown element under <receiver>: " + parser.getName()
                                + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                    } else {
                        Slog.w(TAG, "Unknown element under <activity>: " + parser.getName()
                                + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                    }
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    if (receiver) {
                        outError[0] = "Bad element under <receiver>: " + parser.getName();
                    } else {
                        outError[0] = "Bad element under <activity>: " + parser.getName();
                    }
                    return null;
                }
            }
        }

        resolveWindowLayout(a);

        if (!setExported) {
            a.info.exported = a.intents.size() > 0;
        }

        return a;
    }

    private void setActivityResizeMode(ActivityInfo aInfo, TypedArray sa, Package owner) {
        final boolean appExplicitDefault = (owner.applicationInfo.privateFlags
                & (PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE
                | PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_UNRESIZEABLE)) != 0;

        if (sa.hasValue(R.styleable.AndroidManifestActivity_resizeableActivity)
                || appExplicitDefault) {
            // Activity or app explicitly set if it is resizeable or not;
            final boolean appResizeable = (owner.applicationInfo.privateFlags
                    & PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE) != 0;
            if (sa.getBoolean(R.styleable.AndroidManifestActivity_resizeableActivity,
                    appResizeable)) {
                aInfo.resizeMode = RESIZE_MODE_RESIZEABLE;
            } else {
                aInfo.resizeMode = RESIZE_MODE_UNRESIZEABLE;
            }
            return;
        }

        if ((owner.applicationInfo.privateFlags
                & PRIVATE_FLAG_ACTIVITIES_RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) != 0) {
            // The activity or app didn't explicitly set the resizing option, however we want to
            // make it resize due to the sdk version it is targeting.
            aInfo.resizeMode = RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION;
            return;
        }

        // resize preference isn't set and target sdk version doesn't support resizing apps by
        // default. For the app to be resizeable if it isn't fixed orientation or immersive.
        if (aInfo.isFixedOrientationPortrait()) {
            aInfo.resizeMode = RESIZE_MODE_FORCE_RESIZABLE_PORTRAIT_ONLY;
        } else if (aInfo.isFixedOrientationLandscape()) {
            aInfo.resizeMode = RESIZE_MODE_FORCE_RESIZABLE_LANDSCAPE_ONLY;
        } else if (aInfo.isFixedOrientation()) {
            aInfo.resizeMode = RESIZE_MODE_FORCE_RESIZABLE_PRESERVE_ORIENTATION;
        } else {
            aInfo.resizeMode = RESIZE_MODE_FORCE_RESIZEABLE;
        }
    }

    /**
     * Sets every the max aspect ratio of every child activity that doesn't already have an aspect
     * ratio set.
     */
    private void setMaxAspectRatio(Package owner) {
        // Default to (1.86) 16.7:9 aspect ratio for pre-O apps and unset for O and greater.
        // NOTE: 16.7:9 was the max aspect ratio Android devices can support pre-O per the CDD.
        float maxAspectRatio = owner.applicationInfo.targetSdkVersion < O
                ? DEFAULT_PRE_O_MAX_ASPECT_RATIO : 0;

        if (owner.applicationInfo.maxAspectRatio != 0) {
            // Use the application max aspect ration as default if set.
            maxAspectRatio = owner.applicationInfo.maxAspectRatio;
        } else if (owner.mAppMetaData != null
                && owner.mAppMetaData.containsKey(METADATA_MAX_ASPECT_RATIO)) {
            maxAspectRatio = owner.mAppMetaData.getFloat(METADATA_MAX_ASPECT_RATIO, maxAspectRatio);
        }

        for (Activity activity : owner.activities) {
            // If the max aspect ratio for the activity has already been set, skip.
            if (activity.hasMaxAspectRatio()) {
                continue;
            }

            // By default we prefer to use a values defined on the activity directly than values
            // defined on the application. We do not check the styled attributes on the activity
            // as it would have already been set when we processed the activity. We wait to process
            // the meta data here since this method is called at the end of processing the
            // application and all meta data is guaranteed.
            final float activityAspectRatio = activity.metaData != null
                    ? activity.metaData.getFloat(METADATA_MAX_ASPECT_RATIO, maxAspectRatio)
                    : maxAspectRatio;

            activity.setMaxAspectRatio(activityAspectRatio);
        }
    }

    /**
     * Sets every the min aspect ratio of every child activity that doesn't already have an aspect
     * ratio set.
     */
    private void setMinAspectRatio(Package owner) {
        final float minAspectRatio;
        if (owner.applicationInfo.minAspectRatio != 0) {
            // Use the application max aspect ration as default if set.
            minAspectRatio = owner.applicationInfo.minAspectRatio;
        } else {
            // Default to (1.33) 4:3 aspect ratio for pre-Q apps and unset for Q and greater.
            // NOTE: 4:3 was the min aspect ratio Android devices can support pre-Q per the CDD,
            // except for watches which always supported 1:1.
            minAspectRatio = owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.Q
                    ? 0
                    : (mCallback != null && mCallback.hasFeature(FEATURE_WATCH))
                            ? DEFAULT_PRE_Q_MIN_ASPECT_RATIO_WATCH
                            : DEFAULT_PRE_Q_MIN_ASPECT_RATIO;
        }

        for (Activity activity : owner.activities) {
            if (activity.hasMinAspectRatio()) {
                continue;
            }
            activity.setMinAspectRatio(minAspectRatio);
        }
    }

    /**
     * @param configChanges The bit mask of configChanges fetched from AndroidManifest.xml.
     * @param recreateOnConfigChanges The bit mask recreateOnConfigChanges fetched from
     *                                AndroidManifest.xml.
     * @hide Exposed for unit testing only.
     */
    @TestApi
    public static int getActivityConfigChanges(int configChanges, int recreateOnConfigChanges) {
        return configChanges | ((~recreateOnConfigChanges) & RECREATE_ON_CONFIG_CHANGES_MASK);
    }

    private void parseLayout(Resources res, AttributeSet attrs, Activity a) {
        TypedArray sw = res.obtainAttributes(attrs,
                com.android.internal.R.styleable.AndroidManifestLayout);
        int width = -1;
        float widthFraction = -1f;
        int height = -1;
        float heightFraction = -1f;
        final int widthType = sw.getType(
                com.android.internal.R.styleable.AndroidManifestLayout_defaultWidth);
        if (widthType == TypedValue.TYPE_FRACTION) {
            widthFraction = sw.getFraction(
                    com.android.internal.R.styleable.AndroidManifestLayout_defaultWidth,
                    1, 1, -1);
        } else if (widthType == TypedValue.TYPE_DIMENSION) {
            width = sw.getDimensionPixelSize(
                    com.android.internal.R.styleable.AndroidManifestLayout_defaultWidth,
                    -1);
        }
        final int heightType = sw.getType(
                com.android.internal.R.styleable.AndroidManifestLayout_defaultHeight);
        if (heightType == TypedValue.TYPE_FRACTION) {
            heightFraction = sw.getFraction(
                    com.android.internal.R.styleable.AndroidManifestLayout_defaultHeight,
                    1, 1, -1);
        } else if (heightType == TypedValue.TYPE_DIMENSION) {
            height = sw.getDimensionPixelSize(
                    com.android.internal.R.styleable.AndroidManifestLayout_defaultHeight,
                    -1);
        }
        int gravity = sw.getInt(
                com.android.internal.R.styleable.AndroidManifestLayout_gravity,
                Gravity.CENTER);
        int minWidth = sw.getDimensionPixelSize(
                com.android.internal.R.styleable.AndroidManifestLayout_minWidth,
                -1);
        int minHeight = sw.getDimensionPixelSize(
                com.android.internal.R.styleable.AndroidManifestLayout_minHeight,
                -1);
        sw.recycle();
        a.info.windowLayout = new ActivityInfo.WindowLayout(width, widthFraction,
                height, heightFraction, gravity, minWidth, minHeight);
    }

    /**
     * Resolves values in {@link ActivityInfo.WindowLayout}.
     *
     * <p>{@link ActivityInfo.WindowLayout#windowLayoutAffinity} has a fallback metadata used in
     * Android R and some variants of pre-R.
     */
    private void resolveWindowLayout(Activity activity) {
        // There isn't a metadata for us to fall back. Whatever is in layout is correct.
        if (activity.metaData == null
                || !activity.metaData.containsKey(METADATA_ACTIVITY_WINDOW_LAYOUT_AFFINITY)) {
            return;
        }

        final ActivityInfo aInfo = activity.info;
        // Layout already specifies a value. We should just use that one.
        if (aInfo.windowLayout != null && aInfo.windowLayout.windowLayoutAffinity != null) {
            return;
        }

        String windowLayoutAffinity = activity.metaData.getString(
                METADATA_ACTIVITY_WINDOW_LAYOUT_AFFINITY);
        if (aInfo.windowLayout == null) {
            aInfo.windowLayout = new ActivityInfo.WindowLayout(-1 /* width */,
                    -1 /* widthFraction */, -1 /* height */, -1 /* heightFraction */,
                    Gravity.NO_GRAVITY, -1 /* minWidth */, -1 /* minHeight */);
        }
        aInfo.windowLayout.windowLayoutAffinity = windowLayoutAffinity;
    }

    private Activity parseActivityAlias(Package owner, Resources res,
            XmlResourceParser parser, int flags, String[] outError,
            CachedComponentArgs cachedArgs)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestActivityAlias);

        String targetActivity = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestActivityAlias_targetActivity,
                Configuration.NATIVE_CONFIG_VERSION);
        if (targetActivity == null) {
            outError[0] = "<activity-alias> does not specify android:targetActivity";
            sa.recycle();
            return null;
        }

        targetActivity = buildClassName(owner.applicationInfo.packageName,
                targetActivity, outError);
        if (targetActivity == null) {
            sa.recycle();
            return null;
        }

        if (cachedArgs.mActivityAliasArgs == null) {
            cachedArgs.mActivityAliasArgs = new ParseComponentArgs(owner, outError,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_name,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_label,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_icon,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_roundIcon,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_logo,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_banner,
                    mSeparateProcesses,
                    0,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_description,
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_enabled);
            cachedArgs.mActivityAliasArgs.tag = "<activity-alias>";
        }

        cachedArgs.mActivityAliasArgs.sa = sa;
        cachedArgs.mActivityAliasArgs.flags = flags;

        Activity target = null;

        final int NA = owner.activities.size();
        for (int i=0; i<NA; i++) {
            Activity t = owner.activities.get(i);
            if (targetActivity.equals(t.info.name)) {
                target = t;
                break;
            }
        }

        if (target == null) {
            outError[0] = "<activity-alias> target activity " + targetActivity
                    + " not found in manifest";
            sa.recycle();
            return null;
        }

        ActivityInfo info = new ActivityInfo();
        info.targetActivity = targetActivity;
        info.configChanges = target.info.configChanges;
        info.flags = target.info.flags;
        info.privateFlags = target.info.privateFlags;
        info.icon = target.info.icon;
        info.logo = target.info.logo;
        info.banner = target.info.banner;
        info.labelRes = target.info.labelRes;
        info.nonLocalizedLabel = target.info.nonLocalizedLabel;
        info.launchMode = target.info.launchMode;
        info.lockTaskLaunchMode = target.info.lockTaskLaunchMode;
        info.processName = target.info.processName;
        if (info.descriptionRes == 0) {
            info.descriptionRes = target.info.descriptionRes;
        }
        info.screenOrientation = target.info.screenOrientation;
        info.taskAffinity = target.info.taskAffinity;
        info.theme = target.info.theme;
        info.softInputMode = target.info.softInputMode;
        info.uiOptions = target.info.uiOptions;
        info.parentActivityName = target.info.parentActivityName;
        info.maxRecents = target.info.maxRecents;
        info.windowLayout = target.info.windowLayout;
        info.resizeMode = target.info.resizeMode;
        info.maxAspectRatio = target.info.maxAspectRatio;
        info.minAspectRatio = target.info.minAspectRatio;
        info.requestedVrComponent = target.info.requestedVrComponent;

        info.directBootAware = target.info.directBootAware;

        Activity a = new Activity(cachedArgs.mActivityAliasArgs, info);
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        final boolean setExported = sa.hasValue(
                com.android.internal.R.styleable.AndroidManifestActivityAlias_exported);
        if (setExported) {
            a.info.exported = sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestActivityAlias_exported, false);
        }

        String str;
        str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestActivityAlias_permission, 0);
        if (str != null) {
            a.info.permission = str.length() > 0 ? str.toString().intern() : null;
        }

        String parentName = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestActivityAlias_parentActivityName,
                Configuration.NATIVE_CONFIG_VERSION);
        if (parentName != null) {
            String parentClassName = buildClassName(a.info.packageName, parentName, outError);
            if (outError[0] == null) {
                a.info.parentActivityName = parentClassName;
            } else {
                Log.e(TAG, "Activity alias " + a.info.name +
                        " specified invalid parentActivityName " + parentName);
                outError[0] = null;
            }
        }

        // TODO add visibleToInstantApps attribute to activity alias
        final boolean visibleToEphemeral =
                ((a.info.flags & ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP) != 0);

        sa.recycle();

        if (outError[0] != null) {
            return null;
        }

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ActivityIntentInfo intent = new ActivityIntentInfo(a);
                if (!parseIntent(res, parser, true /*allowGlobs*/, true /*allowAutoVerify*/,
                        intent, outError)) {
                    return null;
                }
                if (intent.countActions() == 0) {
                    Slog.w(TAG, "No actions in intent filter at "
                            + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                } else {
                    a.order = Math.max(intent.getOrder(), a.order);
                    a.intents.add(intent);
                }
                // adjust activity flags when we implicitly expose it via a browsable filter
                final int visibility = visibleToEphemeral
                        ? IntentFilter.VISIBILITY_EXPLICIT
                        : isImplicitlyExposedIntent(intent)
                                ? IntentFilter.VISIBILITY_IMPLICIT
                                : IntentFilter.VISIBILITY_NONE;
                intent.setVisibilityToInstantApp(visibility);
                if (intent.isVisibleToInstantApp()) {
                    a.info.flags |= ActivityInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                }
                if (intent.isImplicitlyVisibleToInstantApp()) {
                    a.info.flags |= ActivityInfo.FLAG_IMPLICITLY_VISIBLE_TO_INSTANT_APP;
                }
            } else if (parser.getName().equals("meta-data")) {
                if ((a.metaData=parseMetaData(res, parser, a.metaData,
                        outError)) == null) {
                    return null;
                }
            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <activity-alias>: " + parser.getName()
                            + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <activity-alias>: " + parser.getName();
                    return null;
                }
            }
        }

        if (!setExported) {
            a.info.exported = a.intents.size() > 0;
        }

        return a;
    }

    private Provider parseProvider(Package owner, Resources res,
            XmlResourceParser parser, int flags, String[] outError,
            CachedComponentArgs cachedArgs)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestProvider);

        if (cachedArgs.mProviderArgs == null) {
            cachedArgs.mProviderArgs = new ParseComponentArgs(owner, outError,
                    com.android.internal.R.styleable.AndroidManifestProvider_name,
                    com.android.internal.R.styleable.AndroidManifestProvider_label,
                    com.android.internal.R.styleable.AndroidManifestProvider_icon,
                    com.android.internal.R.styleable.AndroidManifestProvider_roundIcon,
                    com.android.internal.R.styleable.AndroidManifestProvider_logo,
                    com.android.internal.R.styleable.AndroidManifestProvider_banner,
                    mSeparateProcesses,
                    com.android.internal.R.styleable.AndroidManifestProvider_process,
                    com.android.internal.R.styleable.AndroidManifestProvider_description,
                    com.android.internal.R.styleable.AndroidManifestProvider_enabled);
            cachedArgs.mProviderArgs.tag = "<provider>";
        }

        cachedArgs.mProviderArgs.sa = sa;
        cachedArgs.mProviderArgs.flags = flags;

        Provider p = new Provider(cachedArgs.mProviderArgs, new ProviderInfo());
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        boolean providerExportedDefault = false;

        if (owner.applicationInfo.targetSdkVersion < Build.VERSION_CODES.JELLY_BEAN_MR1) {
            // For compatibility, applications targeting API level 16 or lower
            // should have their content providers exported by default, unless they
            // specify otherwise.
            providerExportedDefault = true;
        }

        p.info.exported = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_exported,
                providerExportedDefault);

        String cpname = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestProvider_authorities, 0);

        p.info.isSyncable = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_syncable,
                false);

        String permission = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestProvider_permission, 0);
        String str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestProvider_readPermission, 0);
        if (str == null) {
            str = permission;
        }
        if (str == null) {
            p.info.readPermission = owner.applicationInfo.permission;
        } else {
            p.info.readPermission =
                str.length() > 0 ? str.toString().intern() : null;
        }
        str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestProvider_writePermission, 0);
        if (str == null) {
            str = permission;
        }
        if (str == null) {
            p.info.writePermission = owner.applicationInfo.permission;
        } else {
            p.info.writePermission =
                str.length() > 0 ? str.toString().intern() : null;
        }

        p.info.grantUriPermissions = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_grantUriPermissions,
                false);

        p.info.forceUriPermissions = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_forceUriPermissions,
                false);

        p.info.multiprocess = sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_multiprocess,
                false);

        p.info.initOrder = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestProvider_initOrder,
                0);

        p.info.splitName =
                sa.getNonConfigurationString(R.styleable.AndroidManifestProvider_splitName, 0);

        p.info.flags = 0;

        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestProvider_singleUser,
                false)) {
            p.info.flags |= ProviderInfo.FLAG_SINGLE_USER;
        }

        p.info.directBootAware = sa.getBoolean(
                R.styleable.AndroidManifestProvider_directBootAware,
                false);
        if (p.info.directBootAware) {
            owner.applicationInfo.privateFlags |=
                    ApplicationInfo.PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE;
        }

        final boolean visibleToEphemeral =
                sa.getBoolean(R.styleable.AndroidManifestProvider_visibleToInstantApps, false);
        if (visibleToEphemeral) {
            p.info.flags |= ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP;
            owner.visibleToInstantApps = true;
        }

        sa.recycle();

        if ((owner.applicationInfo.privateFlags&ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE)
                != 0) {
            // A heavy-weight application can not have providers in its main process
            // We can do direct compare because we intern all strings.
            if (p.info.processName == owner.packageName) {
                outError[0] = "Heavy-weight applications can not have providers in main process";
                return null;
            }
        }

        if (cpname == null) {
            outError[0] = "<provider> does not include authorities attribute";
            return null;
        }
        if (cpname.length() <= 0) {
            outError[0] = "<provider> has empty authorities attribute";
            return null;
        }
        p.info.authority = cpname.intern();

        if (!parseProviderTags(
                res, parser, visibleToEphemeral, p, outError)) {
            return null;
        }

        return p;
    }

    private boolean parseProviderTags(Resources res, XmlResourceParser parser,
            boolean visibleToEphemeral, Provider outInfo, String[] outError)
                    throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ProviderIntentInfo intent = new ProviderIntentInfo(outInfo);
                if (!parseIntent(res, parser, true /*allowGlobs*/, false /*allowAutoVerify*/,
                        intent, outError)) {
                    return false;
                }
                if (visibleToEphemeral) {
                    intent.setVisibilityToInstantApp(IntentFilter.VISIBILITY_EXPLICIT);
                    outInfo.info.flags |= ProviderInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                }
                outInfo.order = Math.max(intent.getOrder(), outInfo.order);
                outInfo.intents.add(intent);

            } else if (parser.getName().equals("meta-data")) {
                if ((outInfo.metaData=parseMetaData(res, parser,
                        outInfo.metaData, outError)) == null) {
                    return false;
                }

            } else if (parser.getName().equals("grant-uri-permission")) {
                TypedArray sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestGrantUriPermission);

                PatternMatcher pa = null;

                String str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestGrantUriPermission_path, 0);
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestGrantUriPermission_pathPrefix, 0);
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestGrantUriPermission_pathPattern, 0);
                if (str != null) {
                    pa = new PatternMatcher(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }

                sa.recycle();

                if (pa != null) {
                    if (outInfo.info.uriPermissionPatterns == null) {
                        outInfo.info.uriPermissionPatterns = new PatternMatcher[1];
                        outInfo.info.uriPermissionPatterns[0] = pa;
                    } else {
                        final int N = outInfo.info.uriPermissionPatterns.length;
                        PatternMatcher[] newp = new PatternMatcher[N+1];
                        System.arraycopy(outInfo.info.uriPermissionPatterns, 0, newp, 0, N);
                        newp[N] = pa;
                        outInfo.info.uriPermissionPatterns = newp;
                    }
                    outInfo.info.grantUriPermissions = true;
                } else {
                    if (!RIGID_PARSER) {
                        Slog.w(TAG, "Unknown element under <path-permission>: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    } else {
                        outError[0] = "No path, pathPrefix, or pathPattern for <path-permission>";
                        return false;
                    }
                }
                XmlUtils.skipCurrentTag(parser);

            } else if (parser.getName().equals("path-permission")) {
                TypedArray sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestPathPermission);

                PathPermission pa = null;

                String permission = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_permission, 0);
                String readPermission = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_readPermission, 0);
                if (readPermission == null) {
                    readPermission = permission;
                }
                String writePermission = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_writePermission, 0);
                if (writePermission == null) {
                    writePermission = permission;
                }

                boolean havePerm = false;
                if (readPermission != null) {
                    readPermission = readPermission.intern();
                    havePerm = true;
                }
                if (writePermission != null) {
                    writePermission = writePermission.intern();
                    havePerm = true;
                }

                if (!havePerm) {
                    if (!RIGID_PARSER) {
                        Slog.w(TAG, "No readPermission or writePermssion for <path-permission>: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    } else {
                        outError[0] = "No readPermission or writePermssion for <path-permission>";
                        return false;
                    }
                }

                String path = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_path, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_LITERAL, readPermission, writePermission);
                }

                path = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_pathPrefix, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_PREFIX, readPermission, writePermission);
                }

                path = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_pathPattern, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_SIMPLE_GLOB, readPermission, writePermission);
                }

                path = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestPathPermission_pathAdvancedPattern, 0);
                if (path != null) {
                    pa = new PathPermission(path,
                            PatternMatcher.PATTERN_ADVANCED_GLOB, readPermission, writePermission);
                }

                sa.recycle();

                if (pa != null) {
                    if (outInfo.info.pathPermissions == null) {
                        outInfo.info.pathPermissions = new PathPermission[1];
                        outInfo.info.pathPermissions[0] = pa;
                    } else {
                        final int N = outInfo.info.pathPermissions.length;
                        PathPermission[] newp = new PathPermission[N+1];
                        System.arraycopy(outInfo.info.pathPermissions, 0, newp, 0, N);
                        newp[N] = pa;
                        outInfo.info.pathPermissions = newp;
                    }
                } else {
                    if (!RIGID_PARSER) {
                        Slog.w(TAG, "No path, pathPrefix, or pathPattern for <path-permission>: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                        XmlUtils.skipCurrentTag(parser);
                        continue;
                    }
                    outError[0] = "No path, pathPrefix, or pathPattern for <path-permission>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <provider>: "
                            + parser.getName() + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <provider>: " + parser.getName();
                    return false;
                }
            }
        }
        return true;
    }

    private Service parseService(Package owner, Resources res,
            XmlResourceParser parser, int flags, String[] outError,
            CachedComponentArgs cachedArgs)
            throws XmlPullParserException, IOException {
        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestService);

        if (cachedArgs.mServiceArgs == null) {
            cachedArgs.mServiceArgs = new ParseComponentArgs(owner, outError,
                    com.android.internal.R.styleable.AndroidManifestService_name,
                    com.android.internal.R.styleable.AndroidManifestService_label,
                    com.android.internal.R.styleable.AndroidManifestService_icon,
                    com.android.internal.R.styleable.AndroidManifestService_roundIcon,
                    com.android.internal.R.styleable.AndroidManifestService_logo,
                    com.android.internal.R.styleable.AndroidManifestService_banner,
                    mSeparateProcesses,
                    com.android.internal.R.styleable.AndroidManifestService_process,
                    com.android.internal.R.styleable.AndroidManifestService_description,
                    com.android.internal.R.styleable.AndroidManifestService_enabled);
            cachedArgs.mServiceArgs.tag = "<service>";
        }

        cachedArgs.mServiceArgs.sa = sa;
        cachedArgs.mServiceArgs.flags = flags;

        Service s = new Service(cachedArgs.mServiceArgs, new ServiceInfo());
        if (outError[0] != null) {
            sa.recycle();
            return null;
        }

        boolean setExported = sa.hasValue(
                com.android.internal.R.styleable.AndroidManifestService_exported);
        if (setExported) {
            s.info.exported = sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestService_exported, false);
        }

        String str = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestService_permission, 0);
        if (str == null) {
            s.info.permission = owner.applicationInfo.permission;
        } else {
            s.info.permission = str.length() > 0 ? str.toString().intern() : null;
        }

        s.info.splitName =
                sa.getNonConfigurationString(R.styleable.AndroidManifestService_splitName, 0);

        s.info.mForegroundServiceType = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestService_foregroundServiceType,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE);

        s.info.flags = 0;
        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestService_stopWithTask,
                false)) {
            s.info.flags |= ServiceInfo.FLAG_STOP_WITH_TASK;
        }
        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestService_isolatedProcess,
                false)) {
            s.info.flags |= ServiceInfo.FLAG_ISOLATED_PROCESS;
        }
        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestService_externalService,
                false)) {
            s.info.flags |= ServiceInfo.FLAG_EXTERNAL_SERVICE;
        }
        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestService_useAppZygote,
                false)) {
            s.info.flags |= ServiceInfo.FLAG_USE_APP_ZYGOTE;
        }
        if (sa.getBoolean(
                com.android.internal.R.styleable.AndroidManifestService_singleUser,
                false)) {
            s.info.flags |= ServiceInfo.FLAG_SINGLE_USER;
        }

        s.info.directBootAware = sa.getBoolean(
                R.styleable.AndroidManifestService_directBootAware,
                false);
        if (s.info.directBootAware) {
            owner.applicationInfo.privateFlags |=
                    ApplicationInfo.PRIVATE_FLAG_PARTIALLY_DIRECT_BOOT_AWARE;
        }

        boolean visibleToEphemeral =
                sa.getBoolean(R.styleable.AndroidManifestService_visibleToInstantApps, false);
        if (visibleToEphemeral) {
            s.info.flags |= ServiceInfo.FLAG_VISIBLE_TO_INSTANT_APP;
            owner.visibleToInstantApps = true;
        }

        sa.recycle();

        if ((owner.applicationInfo.privateFlags&ApplicationInfo.PRIVATE_FLAG_CANT_SAVE_STATE)
                != 0) {
            // A heavy-weight application can not have services in its main process
            // We can do direct compare because we intern all strings.
            if (s.info.processName == owner.packageName) {
                outError[0] = "Heavy-weight applications can not have services in main process";
                return null;
            }
        }

        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("intent-filter")) {
                ServiceIntentInfo intent = new ServiceIntentInfo(s);
                if (!parseIntent(res, parser, true /*allowGlobs*/, false /*allowAutoVerify*/,
                        intent, outError)) {
                    return null;
                }
                if (visibleToEphemeral) {
                    intent.setVisibilityToInstantApp(IntentFilter.VISIBILITY_EXPLICIT);
                    s.info.flags |= ServiceInfo.FLAG_VISIBLE_TO_INSTANT_APP;
                }
                s.order = Math.max(intent.getOrder(), s.order);
                s.intents.add(intent);
            } else if (parser.getName().equals("meta-data")) {
                if ((s.metaData=parseMetaData(res, parser, s.metaData,
                        outError)) == null) {
                    return null;
                }
            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under <service>: "
                            + parser.getName() + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under <service>: " + parser.getName();
                    return null;
                }
            }
        }

        if (!setExported) {
            s.info.exported = s.intents.size() > 0;
        }

        return s;
    }

    private boolean isImplicitlyExposedIntent(IntentInfo intent) {
        return intent.hasCategory(Intent.CATEGORY_BROWSABLE)
                || intent.hasAction(Intent.ACTION_SEND)
                || intent.hasAction(Intent.ACTION_SENDTO)
                || intent.hasAction(Intent.ACTION_SEND_MULTIPLE);
    }

    private boolean parseAllMetaData(Resources res, XmlResourceParser parser, String tag,
            Component<?> outInfo, String[] outError) throws XmlPullParserException, IOException {
        int outerDepth = parser.getDepth();
        int type;
        while ((type=parser.next()) != XmlPullParser.END_DOCUMENT
               && (type != XmlPullParser.END_TAG
                       || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            if (parser.getName().equals("meta-data")) {
                if ((outInfo.metaData=parseMetaData(res, parser,
                        outInfo.metaData, outError)) == null) {
                    return false;
                }
            } else {
                if (!RIGID_PARSER) {
                    Slog.w(TAG, "Unknown element under " + tag + ": "
                            + parser.getName() + " at " + mArchiveSourcePath + " "
                            + parser.getPositionDescription());
                    XmlUtils.skipCurrentTag(parser);
                    continue;
                } else {
                    outError[0] = "Bad element under " + tag + ": " + parser.getName();
                    return false;
                }
            }
        }
        return true;
    }

    private Bundle parseMetaData(Resources res,
            XmlResourceParser parser, Bundle data, String[] outError)
            throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestMetaData);

        if (data == null) {
            data = new Bundle();
        }

        String name = sa.getNonConfigurationString(
                com.android.internal.R.styleable.AndroidManifestMetaData_name, 0);
        if (name == null) {
            outError[0] = "<meta-data> requires an android:name attribute";
            sa.recycle();
            return null;
        }

        name = name.intern();

        TypedValue v = sa.peekValue(
                com.android.internal.R.styleable.AndroidManifestMetaData_resource);
        if (v != null && v.resourceId != 0) {
            //Slog.i(TAG, "Meta data ref " + name + ": " + v);
            data.putInt(name, v.resourceId);
        } else {
            v = sa.peekValue(
                    com.android.internal.R.styleable.AndroidManifestMetaData_value);
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
                    if (!RIGID_PARSER) {
                        Slog.w(TAG, "<meta-data> only supports string, integer, float, color, boolean, and resource reference types: "
                                + parser.getName() + " at " + mArchiveSourcePath + " "
                                + parser.getPositionDescription());
                    } else {
                        outError[0] = "<meta-data> only supports string, integer, float, color, boolean, and resource reference types";
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

    private static VerifierInfo parseVerifier(AttributeSet attrs) {
        String packageName = null;
        String encodedPublicKey = null;

        final int attrCount = attrs.getAttributeCount();
        for (int i = 0; i < attrCount; i++) {
            final int attrResId = attrs.getAttributeNameResource(i);
            switch (attrResId) {
                case com.android.internal.R.attr.name:
                    packageName = attrs.getAttributeValue(i);
                    break;

                case com.android.internal.R.attr.publicKey:
                    encodedPublicKey = attrs.getAttributeValue(i);
                    break;
            }
        }

        if (packageName == null || packageName.length() == 0) {
            Slog.i(TAG, "verifier package name was null; skipping");
            return null;
        }

        final PublicKey publicKey = parsePublicKey(encodedPublicKey);
        if (publicKey == null) {
            Slog.i(TAG, "Unable to parse verifier public key for " + packageName);
            return null;
        }

        return new VerifierInfo(packageName, publicKey);
    }

    public static final PublicKey parsePublicKey(final String encodedPublicKey) {
        if (encodedPublicKey == null) {
            Slog.w(TAG, "Could not parse null public key");
            return null;
        }

        EncodedKeySpec keySpec;
        try {
            final byte[] encoded = Base64.decode(encodedPublicKey, Base64.DEFAULT);
            keySpec = new X509EncodedKeySpec(encoded);
        } catch (IllegalArgumentException e) {
            Slog.w(TAG, "Could not parse verifier public key; invalid Base64");
            return null;
        }

        /* First try the key as an RSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            Slog.wtf(TAG, "Could not parse public key: RSA KeyFactory not included in build");
        } catch (InvalidKeySpecException e) {
            // Not a RSA public key.
        }

        /* Now try it as a ECDSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("EC");
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            Slog.wtf(TAG, "Could not parse public key: EC KeyFactory not included in build");
        } catch (InvalidKeySpecException e) {
            // Not a ECDSA public key.
        }

        /* Now try it as a DSA key. */
        try {
            final KeyFactory keyFactory = KeyFactory.getInstance("DSA");
            return keyFactory.generatePublic(keySpec);
        } catch (NoSuchAlgorithmException e) {
            Slog.wtf(TAG, "Could not parse public key: DSA KeyFactory not included in build");
        } catch (InvalidKeySpecException e) {
            // Not a DSA public key.
        }

        /* Not a supported key type */
        return null;
    }

    public static final String ANDROID_RESOURCES
            = "http://schemas.android.com/apk/res/android";

    private boolean parseIntent(Resources res, XmlResourceParser parser, boolean allowGlobs,
            boolean allowAutoVerify, IntentInfo outInfo, String[] outError)
                    throws XmlPullParserException, IOException {

        TypedArray sa = res.obtainAttributes(parser,
                com.android.internal.R.styleable.AndroidManifestIntentFilter);

        int priority = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestIntentFilter_priority, 0);
        outInfo.setPriority(priority);

        int order = sa.getInt(
                com.android.internal.R.styleable.AndroidManifestIntentFilter_order, 0);
        outInfo.setOrder(order);

        TypedValue v = sa.peekValue(
                com.android.internal.R.styleable.AndroidManifestIntentFilter_label);
        if (v != null && (outInfo.labelRes=v.resourceId) == 0) {
            outInfo.nonLocalizedLabel = v.coerceToString();
        }

        int roundIconVal = sUseRoundIcon ? sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestIntentFilter_roundIcon, 0) : 0;
        if (roundIconVal != 0) {
            outInfo.icon = roundIconVal;
        } else {
            outInfo.icon = sa.getResourceId(
                    com.android.internal.R.styleable.AndroidManifestIntentFilter_icon, 0);
        }

        outInfo.logo = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestIntentFilter_logo, 0);

        outInfo.banner = sa.getResourceId(
                com.android.internal.R.styleable.AndroidManifestIntentFilter_banner, 0);

        if (allowAutoVerify) {
            outInfo.setAutoVerify(sa.getBoolean(
                    com.android.internal.R.styleable.AndroidManifestIntentFilter_autoVerify,
                    false));
        }

        sa.recycle();

        int outerDepth = parser.getDepth();
        int type;
        while ((type = parser.next()) != XmlPullParser.END_DOCUMENT
                && (type != XmlPullParser.END_TAG || parser.getDepth() > outerDepth)) {
            if (type == XmlPullParser.END_TAG || type == XmlPullParser.TEXT) {
                continue;
            }

            String nodeName = parser.getName();
            if (nodeName.equals("action")) {
                String value = parser.getAttributeValue(
                        ANDROID_RESOURCES, "name");
                if (value == null || value == "") {
                    outError[0] = "No value supplied for <android:name>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

                outInfo.addAction(value);
            } else if (nodeName.equals("category")) {
                String value = parser.getAttributeValue(
                        ANDROID_RESOURCES, "name");
                if (value == null || value == "") {
                    outError[0] = "No value supplied for <android:name>";
                    return false;
                }
                XmlUtils.skipCurrentTag(parser);

                outInfo.addCategory(value);

            } else if (nodeName.equals("data")) {
                sa = res.obtainAttributes(parser,
                        com.android.internal.R.styleable.AndroidManifestData);

                String str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_mimeType, 0);
                if (str != null) {
                    try {
                        outInfo.addDataType(str);
                    } catch (IntentFilter.MalformedMimeTypeException e) {
                        outError[0] = e.toString();
                        sa.recycle();
                        return false;
                    }
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_scheme, 0);
                if (str != null) {
                    outInfo.addDataScheme(str);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_ssp, 0);
                if (str != null) {
                    outInfo.addDataSchemeSpecificPart(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_sspPrefix, 0);
                if (str != null) {
                    outInfo.addDataSchemeSpecificPart(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_sspPattern, 0);
                if (str != null) {
                    if (!allowGlobs) {
                        outError[0] = "sspPattern not allowed here; ssp must be literal";
                        return false;
                    }
                    outInfo.addDataSchemeSpecificPart(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }

                String host = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_host, 0);
                String port = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_port, 0);
                if (host != null) {
                    outInfo.addDataAuthority(host, port);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_path, 0);
                if (str != null) {
                    outInfo.addDataPath(str, PatternMatcher.PATTERN_LITERAL);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_pathPrefix, 0);
                if (str != null) {
                    outInfo.addDataPath(str, PatternMatcher.PATTERN_PREFIX);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_pathPattern, 0);
                if (str != null) {
                    if (!allowGlobs) {
                        outError[0] = "pathPattern not allowed here; path must be literal";
                        return false;
                    }
                    outInfo.addDataPath(str, PatternMatcher.PATTERN_SIMPLE_GLOB);
                }

                str = sa.getNonConfigurationString(
                        com.android.internal.R.styleable.AndroidManifestData_pathAdvancedPattern, 0);
                if (str != null) {
                    if (!allowGlobs) {
                        outError[0] = "pathAdvancedPattern not allowed here; path must be literal";
                        return false;
                    }
                    outInfo.addDataPath(str, PatternMatcher.PATTERN_ADVANCED_GLOB);
                }

                sa.recycle();
                XmlUtils.skipCurrentTag(parser);
            } else if (!RIGID_PARSER) {
                Slog.w(TAG, "Unknown element under <intent-filter>: "
                        + parser.getName() + " at " + mArchiveSourcePath + " "
                        + parser.getPositionDescription());
                XmlUtils.skipCurrentTag(parser);
            } else {
                outError[0] = "Bad element under <intent-filter>: " + parser.getName();
                return false;
            }
        }

        outInfo.hasDefault = outInfo.hasCategory(Intent.CATEGORY_DEFAULT);

        if (DEBUG_PARSER) {
            final StringBuilder cats = new StringBuilder("Intent d=");
            cats.append(outInfo.hasDefault);
            cats.append(", cat=");

            final Iterator<String> it = outInfo.categoriesIterator();
            if (it != null) {
                while (it.hasNext()) {
                    cats.append(' ');
                    cats.append(it.next());
                }
            }
            Slog.d(TAG, cats.toString());
        }

        return true;
    }

    /**
     *  A container for signing-related data of an application package.
     * @hide
     */
    public static final class SigningDetails implements Parcelable {

        @IntDef({SigningDetails.SignatureSchemeVersion.UNKNOWN,
                SigningDetails.SignatureSchemeVersion.JAR,
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V2,
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V3,
                SigningDetails.SignatureSchemeVersion.SIGNING_BLOCK_V4})
        public @interface SignatureSchemeVersion {
            int UNKNOWN = 0;
            int JAR = 1;
            int SIGNING_BLOCK_V2 = 2;
            int SIGNING_BLOCK_V3 = 3;
            int SIGNING_BLOCK_V4 = 4;
        }

        @Nullable
        @UnsupportedAppUsage
        public final Signature[] signatures;
        @SignatureSchemeVersion
        public final int signatureSchemeVersion;
        @Nullable
        public final ArraySet<PublicKey> publicKeys;

        /**
         * APK Signature Scheme v3 includes support for adding a proof-of-rotation record that
         * contains two pieces of information:
         *   1) the past signing certificates
         *   2) the flags that APK wants to assign to each of the past signing certificates.
         *
         * This collection of {@code Signature} objects, each of which is formed from a former
         * signing certificate of this APK before it was changed by signing certificate rotation,
         * represents the first piece of information.  It is the APK saying to the rest of the
         * world: "hey if you trust the old cert, you can trust me!"  This is useful, if for
         * instance, the platform would like to determine whether or not to allow this APK to do
         * something it would've allowed it to do under the old cert (like upgrade).
         */
        @Nullable
        public final Signature[] pastSigningCertificates;

        /** special value used to see if cert is in package - not exposed to callers */
        private static final int PAST_CERT_EXISTS = 0;

        @IntDef(
                flag = true,
                value = {CertCapabilities.INSTALLED_DATA,
                        CertCapabilities.SHARED_USER_ID,
                        CertCapabilities.PERMISSION,
                        CertCapabilities.ROLLBACK})
        public @interface CertCapabilities {

            /** accept data from already installed pkg with this cert */
            int INSTALLED_DATA = 1;

            /** accept sharedUserId with pkg with this cert */
            int SHARED_USER_ID = 2;

            /** grant SIGNATURE permissions to pkgs with this cert */
            int PERMISSION = 4;

            /** allow pkg to update to one signed by this certificate */
            int ROLLBACK = 8;

            /** allow pkg to continue to have auth access gated by this cert */
            int AUTH = 16;
        }

        /** A representation of unknown signing details. Use instead of null. */
        public static final SigningDetails UNKNOWN =
                new SigningDetails(null, SignatureSchemeVersion.UNKNOWN, null, null);

        @VisibleForTesting
        public SigningDetails(Signature[] signatures,
                @SignatureSchemeVersion int signatureSchemeVersion,
                ArraySet<PublicKey> keys, Signature[] pastSigningCertificates) {
            this.signatures = signatures;
            this.signatureSchemeVersion = signatureSchemeVersion;
            this.publicKeys = keys;
            this.pastSigningCertificates = pastSigningCertificates;
        }

        public SigningDetails(Signature[] signatures,
                @SignatureSchemeVersion int signatureSchemeVersion,
                Signature[] pastSigningCertificates)
                throws CertificateException {
            this(signatures, signatureSchemeVersion, toSigningKeys(signatures),
                    pastSigningCertificates);
        }

        public SigningDetails(Signature[] signatures,
                @SignatureSchemeVersion int signatureSchemeVersion)
                throws CertificateException {
            this(signatures, signatureSchemeVersion, null);
        }

        public SigningDetails(SigningDetails orig) {
            if (orig != null) {
                if (orig.signatures != null) {
                    this.signatures = orig.signatures.clone();
                } else {
                    this.signatures = null;
                }
                this.signatureSchemeVersion = orig.signatureSchemeVersion;
                this.publicKeys = new ArraySet<>(orig.publicKeys);
                if (orig.pastSigningCertificates != null) {
                    this.pastSigningCertificates = orig.pastSigningCertificates.clone();
                } else {
                    this.pastSigningCertificates = null;
                }
            } else {
                this.signatures = null;
                this.signatureSchemeVersion = SignatureSchemeVersion.UNKNOWN;
                this.publicKeys = null;
                this.pastSigningCertificates = null;
            }
        }

        /**
         * Merges the signing lineage of this instance with the lineage in the provided {@code
         * otherSigningDetails} when one has the same or an ancestor signer of the other.
         *
         * <p>Merging two signing lineages will result in a new {@code SigningDetails} instance
         * containing the longest common lineage with the most restrictive capabilities. If the two
         * lineages contain the same signers with the same capabilities then the instance on which
         * this was invoked is returned without any changes. Similarly if neither instance has a
         * lineage, or if neither has the same or an ancestor signer then this instance is returned.
         *
         * Following are some example results of this method for lineages with signers A, B, C, D:
         * - lineage B merged with lineage A -> B returns lineage A -> B.
         * - lineage A -> B merged with lineage B -> C returns lineage A -> B -> C
         * - lineage A -> B with the {@code PERMISSION} capability revoked for A merged with
         *  lineage A -> B with the {@code SHARED_USER_ID} capability revoked for A returns
         *  lineage A -> B with both capabilities revoked for A.
         * - lineage A -> B -> C merged with lineage A -> B -> D would return the original lineage
         *  A -> B -> C since the current signer of both instances is not the same or in the
         *   lineage of the other.
         */
        public SigningDetails mergeLineageWith(SigningDetails otherSigningDetails) {
            if (!hasPastSigningCertificates()) {
                return otherSigningDetails.hasPastSigningCertificates()
                        && otherSigningDetails.hasAncestorOrSelf(this) ? otherSigningDetails : this;
            }
            if (!otherSigningDetails.hasPastSigningCertificates()) {
                return this;
            }
            // Use the utility method to determine which SigningDetails instance is the descendant
            // and to confirm that the signing lineage does not diverge.
            SigningDetails descendantSigningDetails = getDescendantOrSelf(otherSigningDetails);
            if (descendantSigningDetails == null) {
                return this;
            }
            return descendantSigningDetails == this ? mergeLineageWithAncestorOrSelf(
                    otherSigningDetails) : otherSigningDetails.mergeLineageWithAncestorOrSelf(this);
        }

        /**
         * Merges the signing lineage of this instance with the lineage of the ancestor (or same)
         * signer in the provided {@code otherSigningDetails}.
         */
        private SigningDetails mergeLineageWithAncestorOrSelf(SigningDetails otherSigningDetails) {
            // This method should only be called with instances that contain lineages.
            int index = pastSigningCertificates.length - 1;
            int otherIndex = otherSigningDetails.pastSigningCertificates.length - 1;
            if (index < 0 || otherIndex < 0) {
                return this;
            }

            List<Signature> mergedSignatures = new ArrayList<>();
            boolean capabilitiesModified = false;
            // If this is a descendant lineage then add all of the descendant signer(s) to the
            // merged lineage until the ancestor signer is reached.
            while (index >= 0 && !pastSigningCertificates[index].equals(
                    otherSigningDetails.pastSigningCertificates[otherIndex])) {
                mergedSignatures.add(new Signature(pastSigningCertificates[index--]));
            }
            // If the signing lineage was exhausted then the provided ancestor is not actually an
            // ancestor of this lineage.
            if (index < 0) {
                return this;
            }

            do {
                // Add the common signer to the merged lineage with the most restrictive
                // capabilities of the two lineages.
                Signature signature = pastSigningCertificates[index--];
                Signature ancestorSignature =
                        otherSigningDetails.pastSigningCertificates[otherIndex--];
                Signature mergedSignature = new Signature(signature);
                int mergedCapabilities = signature.getFlags() & ancestorSignature.getFlags();
                if (signature.getFlags() != mergedCapabilities) {
                    capabilitiesModified = true;
                    mergedSignature.setFlags(mergedCapabilities);
                }
                mergedSignatures.add(mergedSignature);
            } while (index >= 0 && otherIndex >= 0 && pastSigningCertificates[index].equals(
                    otherSigningDetails.pastSigningCertificates[otherIndex]));

            // If both lineages still have elements then their lineages have diverged; since this is
            // not supported return the invoking instance.
            if (index >= 0 && otherIndex >= 0) {
                return this;
            }

            // Add any remaining elements from either lineage that is not yet exhausted to the
            // the merged lineage.
            while (otherIndex >= 0) {
                mergedSignatures.add(new Signature(
                        otherSigningDetails.pastSigningCertificates[otherIndex--]));
            }
            while (index >= 0) {
                mergedSignatures.add(new Signature(pastSigningCertificates[index--]));
            }

            // if this lineage already contains all the elements in the ancestor and none of the
            // capabilities were changed then just return this instance.
            if (mergedSignatures.size() == pastSigningCertificates.length
                    && !capabilitiesModified) {
                return this;
            }
            // Since the signatures were added to the merged lineage from newest to oldest reverse
            // the list to ensure the oldest signer is at index 0.
            Collections.reverse(mergedSignatures);
            try {
                return new SigningDetails(new Signature[]{new Signature(signatures[0])},
                        signatureSchemeVersion, mergedSignatures.toArray(new Signature[0]));
            } catch (CertificateException e) {
                Slog.e(TAG, "Caught an exception creating the merged lineage: ", e);
                return this;
            }
        }

        /**
         * Returns whether this and the provided {@code otherSigningDetails} share a common
         * ancestor.
         *
         * <p>The two SigningDetails have a common ancestor if any of the following conditions are
         * met:
         * - If neither has a lineage and their current signer(s) are equal.
         * - If only one has a lineage and the signer of the other is the same or in the lineage.
         * - If both have a lineage and their current signers are the same or one is in the lineage
         * of the other, and their lineages do not diverge to different signers.
         */
        public boolean hasCommonAncestor(SigningDetails otherSigningDetails) {
            if (!hasPastSigningCertificates()) {
                // If this instance does not have a lineage then it must either be in the ancestry
                // of or the same signer of the otherSigningDetails.
                return otherSigningDetails.hasAncestorOrSelf(this);
            }
            if (!otherSigningDetails.hasPastSigningCertificates()) {
                return hasAncestorOrSelf(otherSigningDetails);
            }
            // If both have a lineage then use getDescendantOrSelf to obtain the descendant signing
            // details; a null return from that method indicates there is no common lineage between
            // the two or that they diverge at a point in the lineage.
            return getDescendantOrSelf(otherSigningDetails) != null;
        }

        /**
         * Returns the SigningDetails with a descendant (or same) signer after verifying the
         * descendant has the same, a superset, or a subset of the lineage of the ancestor.
         *
         * <p>If this instance and the provided {@code otherSigningDetails} do not share an
         * ancestry, or if their lineages diverge then null is returned to indicate there is no
         * valid descendant SigningDetails.
         */
        private SigningDetails getDescendantOrSelf(SigningDetails otherSigningDetails) {
            SigningDetails descendantSigningDetails;
            SigningDetails ancestorSigningDetails;
            if (hasAncestorOrSelf(otherSigningDetails)) {
                // If the otherSigningDetails has the same signer or a signer in the lineage of this
                // instance then treat this instance as the descendant.
                descendantSigningDetails = this;
                ancestorSigningDetails = otherSigningDetails;
            } else if (otherSigningDetails.hasAncestor(this)) {
                // The above check confirmed that the two instances do not have the same signer and
                // the signer of otherSigningDetails is not in this instance's lineage; if this
                // signer is in the otherSigningDetails lineage then treat this as the ancestor.
                descendantSigningDetails = otherSigningDetails;
                ancestorSigningDetails = this;
            } else {
                // The signers are not the same and neither has the current signer of the other in
                // its lineage; return null to indicate there is no descendant signer.
                return null;
            }
            // Once the descent (or same) signer is identified iterate through the ancestry until
            // the current signer of the ancestor is found.
            int descendantIndex = descendantSigningDetails.pastSigningCertificates.length - 1;
            int ancestorIndex = ancestorSigningDetails.pastSigningCertificates.length - 1;
            while (descendantIndex >= 0
                    && !descendantSigningDetails.pastSigningCertificates[descendantIndex].equals(
                    ancestorSigningDetails.pastSigningCertificates[ancestorIndex])) {
                descendantIndex--;
            }
            // Since the ancestry was verified above the descendant lineage should never be
            // exhausted, but if for some reason the ancestor signer is not found then return null.
            if (descendantIndex < 0) {
                return null;
            }
            // Once the common ancestor (or same) signer is found iterate over the lineage of both
            // to ensure that they are either the same or one is a subset of the other.
            do {
                descendantIndex--;
                ancestorIndex--;
            } while (descendantIndex >= 0 && ancestorIndex >= 0
                    && descendantSigningDetails.pastSigningCertificates[descendantIndex].equals(
                    ancestorSigningDetails.pastSigningCertificates[ancestorIndex]));

            // If both lineages still have elements then they diverge and cannot be considered a
            // valid common lineage.
            if (descendantIndex >= 0 && ancestorIndex >= 0) {
                return null;
            }
            // Since one or both of the lineages was exhausted they are either the same or one is a
            // subset of the other; return the valid descendant.
            return descendantSigningDetails;
        }

        /** Returns true if the signing details have one or more signatures. */
        public boolean hasSignatures() {
            return signatures != null && signatures.length > 0;
        }

        /** Returns true if the signing details have past signing certificates. */
        public boolean hasPastSigningCertificates() {
            return pastSigningCertificates != null && pastSigningCertificates.length > 0;
        }

        /**
         * Determines if the provided {@code oldDetails} is an ancestor of or the same as this one.
         * If the {@code oldDetails} signing certificate appears in our pastSigningCertificates,
         * then that means it has authorized a signing certificate rotation, which eventually leads
         * to our certificate, and thus can be trusted. If this method evaluates to true, this
         * SigningDetails object should be trusted if the previous one is.
         */
        public boolean hasAncestorOrSelf(SigningDetails oldDetails) {
            if (this == UNKNOWN || oldDetails == UNKNOWN) {
                return false;
            }
            if (oldDetails.signatures.length > 1) {

                // multiple-signer packages cannot rotate signing certs, so we just compare current
                // signers for an exact match
                return signaturesMatchExactly(oldDetails);
            } else {

                // we may have signing certificate rotation history, check to see if the oldDetails
                // was one of our old signing certificates
                return hasCertificate(oldDetails.signatures[0]);
            }
        }

        /**
         * Similar to {@code hasAncestorOrSelf}.  Returns true only if this {@code SigningDetails}
         * is a descendant of {@code oldDetails}, not if they're the same.  This is used to
         * determine if this object is newer than the provided one.
         */
        public boolean hasAncestor(SigningDetails oldDetails) {
            if (this == UNKNOWN || oldDetails == UNKNOWN) {
                return false;
            }
            if (this.hasPastSigningCertificates() && oldDetails.signatures.length == 1) {

                // the last entry in pastSigningCertificates is the current signer, ignore it
                for (int i = 0; i < pastSigningCertificates.length - 1; i++) {
                    if (pastSigningCertificates[i].equals(oldDetails.signatures[0])) {
                        return true;
                    }
                }
            }
            return false;
        }

        /**
         * Determines if the provided {@code oldDetails} is an ancestor of this one, and whether or
         * not this one grants it the provided capability, represented by the {@code flags}
         * parameter.  In the event of signing certificate rotation, a package may still interact
         * with entities signed by its old signing certificate and not want to break previously
         * functioning behavior.  The {@code flags} value determines which capabilities the app
         * signed by the newer signing certificate would like to continue to give to its previous
         * signing certificate(s).
         */
        public boolean checkCapability(SigningDetails oldDetails, @CertCapabilities int flags) {
            if (this == UNKNOWN || oldDetails == UNKNOWN) {
                return false;
            }
            if (oldDetails.signatures.length > 1) {

                // multiple-signer packages cannot rotate signing certs, so we must have an exact
                // match, which also means all capabilities are granted
                return signaturesMatchExactly(oldDetails);
            } else {

                // we may have signing certificate rotation history, check to see if the oldDetails
                // was one of our old signing certificates, and if we grant it the capability it's
                // requesting
                return hasCertificate(oldDetails.signatures[0], flags);
            }
        }

        /**
         * A special case of {@code checkCapability} which re-encodes both sets of signing
         * certificates to counteract a previous re-encoding.
         */
        public boolean checkCapabilityRecover(SigningDetails oldDetails,
                @CertCapabilities int flags) throws CertificateException {
            if (oldDetails == UNKNOWN || this == UNKNOWN) {
                return false;
            }
            if (hasPastSigningCertificates() && oldDetails.signatures.length == 1) {

                // signing certificates may have rotated, check entire history for effective match
                for (int i = 0; i < pastSigningCertificates.length; i++) {
                    if (Signature.areEffectiveMatch(
                            oldDetails.signatures[0],
                            pastSigningCertificates[i])
                            && pastSigningCertificates[i].getFlags() == flags) {
                        return true;
                    }
                }
            } else {
                return Signature.areEffectiveMatch(oldDetails.signatures, signatures);
            }
            return false;
        }

        /**
         * Determine if {@code signature} is in this SigningDetails' signing certificate history,
         * including the current signer.  Automatically returns false if this object has multiple
         * signing certificates, since rotation is only supported for single-signers; this is
         * enforced by {@code hasCertificateInternal}.
         */
        public boolean hasCertificate(Signature signature) {
            return hasCertificateInternal(signature, PAST_CERT_EXISTS);
        }

        /**
         * Determine if {@code signature} is in this SigningDetails' signing certificate history,
         * including the current signer, and whether or not it has the given permission.
         * Certificates which match our current signer automatically get all capabilities.
         * Automatically returns false if this object has multiple signing certificates, since
         * rotation is only supported for single-signers.
         */
        public boolean hasCertificate(Signature signature, @CertCapabilities int flags) {
            return hasCertificateInternal(signature, flags);
        }

        /** Convenient wrapper for calling {@code hasCertificate} with certificate's raw bytes. */
        public boolean hasCertificate(byte[] certificate) {
            Signature signature = new Signature(certificate);
            return hasCertificate(signature);
        }

        private boolean hasCertificateInternal(Signature signature, int flags) {
            if (this == UNKNOWN) {
                return false;
            }

            // only single-signed apps can have pastSigningCertificates
            if (hasPastSigningCertificates()) {

                // check all past certs, except for the current one, which automatically gets all
                // capabilities, since it is the same as the current signature
                for (int i = 0; i < pastSigningCertificates.length - 1; i++) {
                    if (pastSigningCertificates[i].equals(signature)) {
                        if (flags == PAST_CERT_EXISTS
                                || (flags & pastSigningCertificates[i].getFlags()) == flags) {
                            return true;
                        }
                    }
                }
            }

            // not in previous certs signing history, just check the current signer and make sure
            // we are singly-signed
            return signatures.length == 1 && signatures[0].equals(signature);
        }

        /**
         * Determines if the provided {@code sha256String} is an ancestor of this one, and whether
         * or not this one grants it the provided capability, represented by the {@code flags}
         * parameter.  In the event of signing certificate rotation, a package may still interact
         * with entities signed by its old signing certificate and not want to break previously
         * functioning behavior.  The {@code flags} value determines which capabilities the app
         * signed by the newer signing certificate would like to continue to give to its previous
         * signing certificate(s).
         *
         * @param sha256String A hex-encoded representation of a sha256 digest.  In the case of an
         *                     app with multiple signers, this represents the hex-encoded sha256
         *                     digest of the combined hex-encoded sha256 digests of each individual
         *                     signing certificate according to {@link
         *                     PackageUtils#computeSignaturesSha256Digest(Signature[])}
         */
        public boolean checkCapability(String sha256String, @CertCapabilities int flags) {
            if (this == UNKNOWN) {
                return false;
            }

            // first see if the hash represents a single-signer in our signing history
            byte[] sha256Bytes = sha256String == null
                    ? null : HexEncoding.decode(sha256String, false /* allowSingleChar */);
            if (hasSha256Certificate(sha256Bytes, flags)) {
                return true;
            }

            // Not in signing history, either represents multiple signatures or not a match.
            // Multiple signers can't rotate, so no need to check flags, just see if the SHAs match.
            // We already check the single-signer case above as part of hasSha256Certificate, so no
            // need to verify we have multiple signers, just run the old check
            // just consider current signing certs
            final String[] mSignaturesSha256Digests =
                    PackageUtils.computeSignaturesSha256Digests(signatures);
            final String mSignaturesSha256Digest =
                    PackageUtils.computeSignaturesSha256Digest(mSignaturesSha256Digests);
            return mSignaturesSha256Digest.equals(sha256String);
        }

        /**
         * Determine if the {@code sha256Certificate} is in this SigningDetails' signing certificate
         * history, including the current signer.  Automatically returns false if this object has
         * multiple signing certificates, since rotation is only supported for single-signers.
         */
        public boolean hasSha256Certificate(byte[] sha256Certificate) {
            return hasSha256CertificateInternal(sha256Certificate, PAST_CERT_EXISTS);
        }

        /**
         * Determine if the {@code sha256Certificate} certificate hash corresponds to a signing
         * certificate in this SigningDetails' signing certificate history, including the current
         * signer, and whether or not it has the given permission.  Certificates which match our
         * current signer automatically get all capabilities. Automatically returns false if this
         * object has multiple signing certificates, since rotation is only supported for
         * single-signers.
         */
        public boolean hasSha256Certificate(byte[] sha256Certificate, @CertCapabilities int flags) {
            return hasSha256CertificateInternal(sha256Certificate, flags);
        }

        private boolean hasSha256CertificateInternal(byte[] sha256Certificate, int flags) {
            if (this == UNKNOWN) {
                return false;
            }
            if (hasPastSigningCertificates()) {

                // check all past certs, except for the last one, which automatically gets all
                // capabilities, since it is the same as the current signature, and is checked below
                for (int i = 0; i < pastSigningCertificates.length - 1; i++) {
                    byte[] digest = PackageUtils.computeSha256DigestBytes(
                            pastSigningCertificates[i].toByteArray());
                    if (Arrays.equals(sha256Certificate, digest)) {
                        if (flags == PAST_CERT_EXISTS
                                || (flags & pastSigningCertificates[i].getFlags()) == flags) {
                            return true;
                        }
                    }
                }
            }

            // not in previous certs signing history, just check the current signer
            if (signatures.length == 1) {
                byte[] digest =
                        PackageUtils.computeSha256DigestBytes(signatures[0].toByteArray());
                return Arrays.equals(sha256Certificate, digest);
            }
            return false;
        }

        /** Returns true if the signatures in this and other match exactly. */
        public boolean signaturesMatchExactly(SigningDetails other) {
            return Signature.areExactMatch(this.signatures, other.signatures);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            boolean isUnknown = UNKNOWN == this;
            dest.writeBoolean(isUnknown);
            if (isUnknown) {
                return;
            }
            dest.writeTypedArray(this.signatures, flags);
            dest.writeInt(this.signatureSchemeVersion);
            dest.writeArraySet(this.publicKeys);
            dest.writeTypedArray(this.pastSigningCertificates, flags);
        }

        protected SigningDetails(Parcel in) {
            final ClassLoader boot = Object.class.getClassLoader();
            this.signatures = in.createTypedArray(Signature.CREATOR);
            this.signatureSchemeVersion = in.readInt();
            this.publicKeys = (ArraySet<PublicKey>) in.readArraySet(boot);
            this.pastSigningCertificates = in.createTypedArray(Signature.CREATOR);
        }

        public static final @android.annotation.NonNull Creator<SigningDetails> CREATOR = new Creator<SigningDetails>() {
            @Override
            public SigningDetails createFromParcel(Parcel source) {
                if (source.readBoolean()) {
                    return UNKNOWN;
                }
                return new SigningDetails(source);
            }

            @Override
            public SigningDetails[] newArray(int size) {
                return new SigningDetails[size];
            }
        };

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof SigningDetails)) return false;

            SigningDetails that = (SigningDetails) o;

            if (signatureSchemeVersion != that.signatureSchemeVersion) return false;
            if (!Signature.areExactMatch(signatures, that.signatures)) return false;
            if (publicKeys != null) {
                if (!publicKeys.equals((that.publicKeys))) {
                    return false;
                }
            } else if (that.publicKeys != null) {
                return false;
            }

            // can't use Signature.areExactMatch() because order matters with the past signing certs
            if (!Arrays.equals(pastSigningCertificates, that.pastSigningCertificates)) {
                return false;
            }
            // The capabilities for the past signing certs must match as well.
            for (int i = 0; i < pastSigningCertificates.length; i++) {
                if (pastSigningCertificates[i].getFlags()
                        != that.pastSigningCertificates[i].getFlags()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = +Arrays.hashCode(signatures);
            result = 31 * result + signatureSchemeVersion;
            result = 31 * result + (publicKeys != null ? publicKeys.hashCode() : 0);
            result = 31 * result + Arrays.hashCode(pastSigningCertificates);
            return result;
        }

        /**
         * Builder of {@code SigningDetails} instances.
         */
        public static class Builder {
            private Signature[] mSignatures;
            private int mSignatureSchemeVersion = SignatureSchemeVersion.UNKNOWN;
            private Signature[] mPastSigningCertificates;

            @UnsupportedAppUsage
            public Builder() {
            }

            /** get signing certificates used to sign the current APK */
            @UnsupportedAppUsage
            public Builder setSignatures(Signature[] signatures) {
                mSignatures = signatures;
                return this;
            }

            /** set the signature scheme version used to sign the APK */
            @UnsupportedAppUsage
            public Builder setSignatureSchemeVersion(int signatureSchemeVersion) {
                mSignatureSchemeVersion = signatureSchemeVersion;
                return this;
            }

            /** set the signing certificates by which the APK proved it can be authenticated */
            @UnsupportedAppUsage
            public Builder setPastSigningCertificates(Signature[] pastSigningCertificates) {
                mPastSigningCertificates = pastSigningCertificates;
                return this;
            }

            private void checkInvariants() {
                // must have signatures and scheme version set
                if (mSignatures == null) {
                    throw new IllegalStateException("SigningDetails requires the current signing"
                            + " certificates.");
                }
            }
            /** build a {@code SigningDetails} object */
            @UnsupportedAppUsage
            public SigningDetails build()
                    throws CertificateException {
                checkInvariants();
                return new SigningDetails(mSignatures, mSignatureSchemeVersion,
                        mPastSigningCertificates);
            }
        }
    }

    /**
     * Representation of a full package parsed from APK files on disk. A package
     * consists of a single base APK, and zero or more split APKs.
     *
     * Deprecated internally. Use AndroidPackage instead.
     */
    public final static class Package implements Parcelable {

        @UnsupportedAppUsage
        public String packageName;

        // The package name declared in the manifest as the package can be
        // renamed, for example static shared libs use synthetic package names.
        public String manifestPackageName;

        /** Names of any split APKs, ordered by parsed splitName */
        public String[] splitNames;

        // TODO: work towards making these paths invariant

        public String volumeUuid;

        /**
         * Path where this package was found on disk. For monolithic packages
         * this is path to single base APK file; for cluster packages this is
         * path to the cluster directory.
         */
        public String codePath;

        /** Path of base APK */
        public String baseCodePath;
        /** Paths of any split APKs, ordered by parsed splitName */
        public String[] splitCodePaths;

        /** Revision code of base APK */
        public int baseRevisionCode;
        /** Revision codes of any split APKs, ordered by parsed splitName */
        public int[] splitRevisionCodes;

        /** Flags of any split APKs; ordered by parsed splitName */
        public int[] splitFlags;

        /**
         * Private flags of any split APKs; ordered by parsed splitName.
         *
         * {@hide}
         */
        public int[] splitPrivateFlags;

        public boolean baseHardwareAccelerated;

        // For now we only support one application per package.
        @UnsupportedAppUsage
        public ApplicationInfo applicationInfo = new ApplicationInfo();

        @UnsupportedAppUsage
        public final ArrayList<Permission> permissions = new ArrayList<Permission>(0);
        @UnsupportedAppUsage
        public final ArrayList<PermissionGroup> permissionGroups = new ArrayList<PermissionGroup>(0);
        @UnsupportedAppUsage
        public final ArrayList<Activity> activities = new ArrayList<Activity>(0);
        @UnsupportedAppUsage
        public final ArrayList<Activity> receivers = new ArrayList<Activity>(0);
        @UnsupportedAppUsage
        public final ArrayList<Provider> providers = new ArrayList<Provider>(0);
        @UnsupportedAppUsage
        public final ArrayList<Service> services = new ArrayList<Service>(0);
        @UnsupportedAppUsage
        public final ArrayList<Instrumentation> instrumentation = new ArrayList<Instrumentation>(0);

        @UnsupportedAppUsage
        public final ArrayList<String> requestedPermissions = new ArrayList<String>();

        /** Permissions requested but not in the manifest. */
        public final ArrayList<String> implicitPermissions = new ArrayList<>();

        @UnsupportedAppUsage
        public ArrayList<String> protectedBroadcasts;

        public Package parentPackage;
        public ArrayList<Package> childPackages;

        public String staticSharedLibName = null;
        public long staticSharedLibVersion = 0;
        public ArrayList<String> libraryNames = null;
        @UnsupportedAppUsage
        public ArrayList<String> usesLibraries = null;
        public ArrayList<String> usesStaticLibraries = null;
        public long[] usesStaticLibrariesVersions = null;
        public String[][] usesStaticLibrariesCertDigests = null;
        @UnsupportedAppUsage
        public ArrayList<String> usesOptionalLibraries = null;
        @UnsupportedAppUsage
        public String[] usesLibraryFiles = null;
        public ArrayList<SharedLibraryInfo> usesLibraryInfos = null;

        public ArrayList<ActivityIntentInfo> preferredActivityFilters = null;

        public ArrayList<String> mOriginalPackages = null;
        public String mRealPackage = null;
        public ArrayList<String> mAdoptPermissions = null;

        // We store the application meta-data independently to avoid multiple unwanted references
        @UnsupportedAppUsage
        public Bundle mAppMetaData = null;

        // The version code declared for this package.
        @UnsupportedAppUsage
        public int mVersionCode;

        // The major version code declared for this package.
        public int mVersionCodeMajor;

        // Return long containing mVersionCode and mVersionCodeMajor.
        public long getLongVersionCode() {
            return PackageInfo.composeLongVersionCode(mVersionCodeMajor, mVersionCode);
        }

        // The version name declared for this package.
        @UnsupportedAppUsage
        public String mVersionName;

        // The shared user id that this package wants to use.
        @UnsupportedAppUsage
        public String mSharedUserId;

        // The shared user label that this package wants to use.
        @UnsupportedAppUsage
        public int mSharedUserLabel;

        // Signatures that were read from the package.
        @UnsupportedAppUsage
        @NonNull public SigningDetails mSigningDetails = SigningDetails.UNKNOWN;

        // For use by package manager service for quick lookup of
        // preferred up order.
        @UnsupportedAppUsage
        public int mPreferredOrder = 0;

        // For use by package manager to keep track of when a package was last used.
        public long[] mLastPackageUsageTimeInMills =
                new long[PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT];

        // // User set enabled state.
        // public int mSetEnabled = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        //
        // // Whether the package has been stopped.
        // public boolean mSetStopped = false;

        // Additional data supplied by callers.
        @UnsupportedAppUsage
        public Object mExtras;

        // Applications hardware preferences
        @UnsupportedAppUsage
        public ArrayList<ConfigurationInfo> configPreferences = null;

        // Applications requested features
        @UnsupportedAppUsage
        public ArrayList<FeatureInfo> reqFeatures = null;

        // Applications requested feature groups
        public ArrayList<FeatureGroupInfo> featureGroups = null;

        @UnsupportedAppUsage
        public int installLocation;

        public boolean coreApp;

        /* An app that's required for all users and cannot be uninstalled for a user */
        public boolean mRequiredForAllUsers;

        /* The restricted account authenticator type that is used by this application */
        public String mRestrictedAccountType;

        /* The required account type without which this application will not function */
        public String mRequiredAccountType;

        public String mOverlayTarget;
        public String mOverlayTargetName;
        public String mOverlayCategory;
        public int mOverlayPriority;
        public boolean mOverlayIsStatic;

        public int mCompileSdkVersion;
        public String mCompileSdkVersionCodename;

        /**
         * Data used to feed the KeySetManagerService
         */
        @UnsupportedAppUsage
        public ArraySet<String> mUpgradeKeySets;
        @UnsupportedAppUsage
        public ArrayMap<String, ArraySet<PublicKey>> mKeySetMapping;

        /**
         * The install time abi override for this package, if any.
         *
         * TODO: This seems like a horrible place to put the abiOverride because
         * this isn't something the packageParser parsers. However, this fits in with
         * the rest of the PackageManager where package scanning randomly pushes
         * and prods fields out of {@code this.applicationInfo}.
         */
        public String cpuAbiOverride;
        /**
         * The install time abi override to choose 32bit abi's when multiple abi's
         * are present. This is only meaningfull for multiarch applications.
         * The use32bitAbi attribute is ignored if cpuAbiOverride is also set.
         */
        public boolean use32bitAbi;

        public byte[] restrictUpdateHash;

        /** Set if the app or any of its components are visible to instant applications. */
        public boolean visibleToInstantApps;
        /** Whether or not the package is a stub and must be replaced by the full version. */
        public boolean isStub;

        @UnsupportedAppUsage
        public Package(String packageName) {
            this.packageName = packageName;
            this.manifestPackageName = packageName;
            applicationInfo.packageName = packageName;
            applicationInfo.uid = -1;
        }

        public void setApplicationVolumeUuid(String volumeUuid) {
            final UUID storageUuid = StorageManager.convert(volumeUuid);
            this.applicationInfo.volumeUuid = volumeUuid;
            this.applicationInfo.storageUuid = storageUuid;
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).applicationInfo.volumeUuid = volumeUuid;
                    childPackages.get(i).applicationInfo.storageUuid = storageUuid;
                }
            }
        }

        public void setApplicationInfoCodePath(String codePath) {
            this.applicationInfo.setCodePath(codePath);
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).applicationInfo.setCodePath(codePath);
                }
            }
        }

        /** @deprecated Forward locked apps no longer supported. Resource path not needed. */
        @Deprecated
        public void setApplicationInfoResourcePath(String resourcePath) {
            this.applicationInfo.setResourcePath(resourcePath);
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).applicationInfo.setResourcePath(resourcePath);
                }
            }
        }

        /** @deprecated Forward locked apps no longer supported. Resource path not needed. */
        @Deprecated
        public void setApplicationInfoBaseResourcePath(String resourcePath) {
            this.applicationInfo.setBaseResourcePath(resourcePath);
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).applicationInfo.setBaseResourcePath(resourcePath);
                }
            }
        }

        public void setApplicationInfoBaseCodePath(String baseCodePath) {
            this.applicationInfo.setBaseCodePath(baseCodePath);
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).applicationInfo.setBaseCodePath(baseCodePath);
                }
            }
        }

        public List<String> getChildPackageNames() {
            if (childPackages == null) {
                return null;
            }
            final int childCount = childPackages.size();
            final List<String> childPackageNames = new ArrayList<>(childCount);
            for (int i = 0; i < childCount; i++) {
                String childPackageName = childPackages.get(i).packageName;
                childPackageNames.add(childPackageName);
            }
            return childPackageNames;
        }

        public boolean hasChildPackage(String packageName) {
            final int childCount = (childPackages != null) ? childPackages.size() : 0;
            for (int i = 0; i < childCount; i++) {
                if (childPackages.get(i).packageName.equals(packageName)) {
                    return true;
                }
            }
            return false;
        }

        public void setApplicationInfoSplitCodePaths(String[] splitCodePaths) {
            this.applicationInfo.setSplitCodePaths(splitCodePaths);
            // Children have no splits
        }

        /** @deprecated Forward locked apps no longer supported. Resource path not needed. */
        @Deprecated
        public void setApplicationInfoSplitResourcePaths(String[] resroucePaths) {
            this.applicationInfo.setSplitResourcePaths(resroucePaths);
            // Children have no splits
        }

        public void setSplitCodePaths(String[] codePaths) {
            this.splitCodePaths = codePaths;
        }

        public void setCodePath(String codePath) {
            this.codePath = codePath;
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).codePath = codePath;
                }
            }
        }

        public void setBaseCodePath(String baseCodePath) {
            this.baseCodePath = baseCodePath;
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).baseCodePath = baseCodePath;
                }
            }
        }

        /** Sets signing details on the package and any of its children. */
        public void setSigningDetails(@NonNull SigningDetails signingDetails) {
            mSigningDetails = signingDetails;
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).mSigningDetails = signingDetails;
                }
            }
        }

        public void setVolumeUuid(String volumeUuid) {
            this.volumeUuid = volumeUuid;
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).volumeUuid = volumeUuid;
                }
            }
        }

        public void setApplicationInfoFlags(int mask, int flags) {
            applicationInfo.flags = (applicationInfo.flags & ~mask) | (mask & flags);
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).applicationInfo.flags =
                            (applicationInfo.flags & ~mask) | (mask & flags);
                }
            }
        }

        public void setUse32bitAbi(boolean use32bitAbi) {
            this.use32bitAbi = use32bitAbi;
            if (childPackages != null) {
                final int packageCount = childPackages.size();
                for (int i = 0; i < packageCount; i++) {
                    childPackages.get(i).use32bitAbi = use32bitAbi;
                }
            }
        }

        public boolean isLibrary() {
            return staticSharedLibName != null || !ArrayUtils.isEmpty(libraryNames);
        }

        public List<String> getAllCodePaths() {
            ArrayList<String> paths = new ArrayList<>();
            paths.add(baseCodePath);
            if (!ArrayUtils.isEmpty(splitCodePaths)) {
                Collections.addAll(paths, splitCodePaths);
            }
            return paths;
        }

        /**
         * Filtered set of {@link #getAllCodePaths()} that excludes
         * resource-only APKs.
         */
        public List<String> getAllCodePathsExcludingResourceOnly() {
            ArrayList<String> paths = new ArrayList<>();
            if ((applicationInfo.flags & ApplicationInfo.FLAG_HAS_CODE) != 0) {
                paths.add(baseCodePath);
            }
            if (!ArrayUtils.isEmpty(splitCodePaths)) {
                for (int i = 0; i < splitCodePaths.length; i++) {
                    if ((splitFlags[i] & ApplicationInfo.FLAG_HAS_CODE) != 0) {
                        paths.add(splitCodePaths[i]);
                    }
                }
            }
            return paths;
        }

        @UnsupportedAppUsage
        public void setPackageName(String newName) {
            packageName = newName;
            applicationInfo.packageName = newName;
            for (int i=permissions.size()-1; i>=0; i--) {
                permissions.get(i).setPackageName(newName);
            }
            for (int i=permissionGroups.size()-1; i>=0; i--) {
                permissionGroups.get(i).setPackageName(newName);
            }
            for (int i=activities.size()-1; i>=0; i--) {
                activities.get(i).setPackageName(newName);
            }
            for (int i=receivers.size()-1; i>=0; i--) {
                receivers.get(i).setPackageName(newName);
            }
            for (int i=providers.size()-1; i>=0; i--) {
                providers.get(i).setPackageName(newName);
            }
            for (int i=services.size()-1; i>=0; i--) {
                services.get(i).setPackageName(newName);
            }
            for (int i=instrumentation.size()-1; i>=0; i--) {
                instrumentation.get(i).setPackageName(newName);
            }
        }

        public boolean hasComponentClassName(String name) {
            for (int i=activities.size()-1; i>=0; i--) {
                if (name.equals(activities.get(i).className)) {
                    return true;
                }
            }
            for (int i=receivers.size()-1; i>=0; i--) {
                if (name.equals(receivers.get(i).className)) {
                    return true;
                }
            }
            for (int i=providers.size()-1; i>=0; i--) {
                if (name.equals(providers.get(i).className)) {
                    return true;
                }
            }
            for (int i=services.size()-1; i>=0; i--) {
                if (name.equals(services.get(i).className)) {
                    return true;
                }
            }
            for (int i=instrumentation.size()-1; i>=0; i--) {
                if (name.equals(instrumentation.get(i).className)) {
                    return true;
                }
            }
            return false;
        }

        /** @hide */
        public boolean isExternal() {
            return applicationInfo.isExternal();
        }

        /** @hide */
        public boolean isForwardLocked() {
            return false;
        }

        /** @hide */
        public boolean isOem() {
            return applicationInfo.isOem();
        }

        /** @hide */
        public boolean isVendor() {
            return applicationInfo.isVendor();
        }

        /** @hide */
        public boolean isProduct() {
            return applicationInfo.isProduct();
        }

        /** @hide */
        public boolean isSystemExt() {
            return applicationInfo.isSystemExt();
        }

        /** @hide */
        public boolean isOdm() {
            return applicationInfo.isOdm();
        }

        /** @hide */
        public boolean isPrivileged() {
            return applicationInfo.isPrivilegedApp();
        }

        /** @hide */
        public boolean isSystem() {
            return applicationInfo.isSystemApp();
        }

        /** @hide */
        public boolean isUpdatedSystemApp() {
            return applicationInfo.isUpdatedSystemApp();
        }

        /** @hide */
        public boolean canHaveOatDir() {
            // Nobody should be calling this method ever, but we can't rely on this.
            // Thus no logic here and a reasonable return value.
            return true;
        }

        public boolean isMatch(int flags) {
            if ((flags & PackageManager.MATCH_SYSTEM_ONLY) != 0) {
                return isSystem();
            }
            return true;
        }

        public long getLatestPackageUseTimeInMills() {
            long latestUse = 0L;
            for (long use : mLastPackageUsageTimeInMills) {
                latestUse = Math.max(latestUse, use);
            }
            return latestUse;
        }

        public long getLatestForegroundPackageUseTimeInMills() {
            int[] foregroundReasons = {
                PackageManager.NOTIFY_PACKAGE_USE_ACTIVITY,
                PackageManager.NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE
            };

            long latestUse = 0L;
            for (int reason : foregroundReasons) {
                latestUse = Math.max(latestUse, mLastPackageUsageTimeInMills[reason]);
            }
            return latestUse;
        }

        public String toString() {
            return "Package{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + packageName + "}";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public Package(Parcel dest) {
            // We use the boot classloader for all classes that we load.
            final ClassLoader boot = Object.class.getClassLoader();

            packageName = dest.readString().intern();
            manifestPackageName = dest.readString();
            splitNames = dest.readStringArray();
            volumeUuid = dest.readString();
            codePath = dest.readString();
            baseCodePath = dest.readString();
            splitCodePaths = dest.readStringArray();
            baseRevisionCode = dest.readInt();
            splitRevisionCodes = dest.createIntArray();
            splitFlags = dest.createIntArray();
            splitPrivateFlags = dest.createIntArray();
            baseHardwareAccelerated = (dest.readInt() == 1);
            applicationInfo = dest.readParcelable(boot);
            if (applicationInfo.permission != null) {
                applicationInfo.permission = applicationInfo.permission.intern();
            }

            // We don't serialize the "owner" package and the application info object for each of
            // these components, in order to save space and to avoid circular dependencies while
            // serialization. We need to fix them all up here.
            dest.readParcelableList(permissions, boot);
            fixupOwner(permissions);
            dest.readParcelableList(permissionGroups, boot);
            fixupOwner(permissionGroups);
            dest.readParcelableList(activities, boot);
            fixupOwner(activities);
            dest.readParcelableList(receivers, boot);
            fixupOwner(receivers);
            dest.readParcelableList(providers, boot);
            fixupOwner(providers);
            dest.readParcelableList(services, boot);
            fixupOwner(services);
            dest.readParcelableList(instrumentation, boot);
            fixupOwner(instrumentation);

            dest.readStringList(requestedPermissions);
            internStringArrayList(requestedPermissions);
            dest.readStringList(implicitPermissions);
            internStringArrayList(implicitPermissions);
            protectedBroadcasts = dest.createStringArrayList();
            internStringArrayList(protectedBroadcasts);

            parentPackage = dest.readParcelable(boot);

            childPackages = new ArrayList<>();
            dest.readParcelableList(childPackages, boot);
            if (childPackages.size() == 0) {
                childPackages = null;
            }

            staticSharedLibName = dest.readString();
            if (staticSharedLibName != null) {
                staticSharedLibName = staticSharedLibName.intern();
            }
            staticSharedLibVersion = dest.readLong();
            libraryNames = dest.createStringArrayList();
            internStringArrayList(libraryNames);
            usesLibraries = dest.createStringArrayList();
            internStringArrayList(usesLibraries);
            usesOptionalLibraries = dest.createStringArrayList();
            internStringArrayList(usesOptionalLibraries);
            usesLibraryFiles = dest.readStringArray();

            usesLibraryInfos = dest.createTypedArrayList(SharedLibraryInfo.CREATOR);

            final int libCount = dest.readInt();
            if (libCount > 0) {
                usesStaticLibraries = new ArrayList<>(libCount);
                dest.readStringList(usesStaticLibraries);
                internStringArrayList(usesStaticLibraries);
                usesStaticLibrariesVersions = new long[libCount];
                dest.readLongArray(usesStaticLibrariesVersions);
                usesStaticLibrariesCertDigests = new String[libCount][];
                for (int i = 0; i < libCount; i++) {
                    usesStaticLibrariesCertDigests[i] = dest.createStringArray();
                }
            }

            preferredActivityFilters = new ArrayList<>();
            dest.readParcelableList(preferredActivityFilters, boot);
            if (preferredActivityFilters.size() == 0) {
                preferredActivityFilters = null;
            }

            mOriginalPackages = dest.createStringArrayList();
            mRealPackage = dest.readString();
            mAdoptPermissions = dest.createStringArrayList();
            mAppMetaData = dest.readBundle();
            mVersionCode = dest.readInt();
            mVersionCodeMajor = dest.readInt();
            mVersionName = dest.readString();
            if (mVersionName != null) {
                mVersionName = mVersionName.intern();
            }
            mSharedUserId = dest.readString();
            if (mSharedUserId != null) {
                mSharedUserId = mSharedUserId.intern();
            }
            mSharedUserLabel = dest.readInt();

            mSigningDetails = dest.readParcelable(boot);

            mPreferredOrder = dest.readInt();

            // long[] packageUsageTimeMillis is not persisted because it isn't information that
            // is parsed from the APK.

            // Object mExtras is not persisted because it is not information that is read from
            // the APK, rather, it is supplied by callers.


            configPreferences = new ArrayList<>();
            dest.readParcelableList(configPreferences, boot);
            if (configPreferences.size() == 0) {
                configPreferences = null;
            }

            reqFeatures = new ArrayList<>();
            dest.readParcelableList(reqFeatures, boot);
            if (reqFeatures.size() == 0) {
                reqFeatures = null;
            }

            featureGroups = new ArrayList<>();
            dest.readParcelableList(featureGroups, boot);
            if (featureGroups.size() == 0) {
                featureGroups = null;
            }

            installLocation = dest.readInt();
            coreApp = (dest.readInt() == 1);
            mRequiredForAllUsers = (dest.readInt() == 1);
            mRestrictedAccountType = dest.readString();
            mRequiredAccountType = dest.readString();
            mOverlayTarget = dest.readString();
            mOverlayTargetName = dest.readString();
            mOverlayCategory = dest.readString();
            mOverlayPriority = dest.readInt();
            mOverlayIsStatic = (dest.readInt() == 1);
            mCompileSdkVersion = dest.readInt();
            mCompileSdkVersionCodename = dest.readString();
            mUpgradeKeySets = (ArraySet<String>) dest.readArraySet(boot);

            mKeySetMapping = readKeySetMapping(dest);

            cpuAbiOverride = dest.readString();
            use32bitAbi = (dest.readInt() == 1);
            restrictUpdateHash = dest.createByteArray();
            visibleToInstantApps = dest.readInt() == 1;
        }

        private static void internStringArrayList(List<String> list) {
            if (list != null) {
                final int N = list.size();
                for (int i = 0; i < N; ++i) {
                    list.set(i, list.get(i).intern());
                }
            }
        }

        /**
         * Sets the package owner and the the {@code applicationInfo} for every component
         * owner by this package.
         */
        public void fixupOwner(List<? extends Component<?>> list) {
            if (list != null) {
                for (Component<?> c : list) {
                    c.owner = this;
                    if (c instanceof Activity) {
                        ((Activity) c).info.applicationInfo = this.applicationInfo;
                    } else if (c instanceof Service) {
                        ((Service) c).info.applicationInfo = this.applicationInfo;
                    } else if (c instanceof Provider) {
                        ((Provider) c).info.applicationInfo = this.applicationInfo;
                    }
                }
            }
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeString(packageName);
            dest.writeString(manifestPackageName);
            dest.writeStringArray(splitNames);
            dest.writeString(volumeUuid);
            dest.writeString(codePath);
            dest.writeString(baseCodePath);
            dest.writeStringArray(splitCodePaths);
            dest.writeInt(baseRevisionCode);
            dest.writeIntArray(splitRevisionCodes);
            dest.writeIntArray(splitFlags);
            dest.writeIntArray(splitPrivateFlags);
            dest.writeInt(baseHardwareAccelerated ? 1 : 0);
            dest.writeParcelable(applicationInfo, flags);

            dest.writeParcelableList(permissions, flags);
            dest.writeParcelableList(permissionGroups, flags);
            dest.writeParcelableList(activities, flags);
            dest.writeParcelableList(receivers, flags);
            dest.writeParcelableList(providers, flags);
            dest.writeParcelableList(services, flags);
            dest.writeParcelableList(instrumentation, flags);

            dest.writeStringList(requestedPermissions);
            dest.writeStringList(implicitPermissions);
            dest.writeStringList(protectedBroadcasts);

            // TODO: This doesn't work: b/64295061
            dest.writeParcelable(parentPackage, flags);
            dest.writeParcelableList(childPackages, flags);

            dest.writeString(staticSharedLibName);
            dest.writeLong(staticSharedLibVersion);
            dest.writeStringList(libraryNames);
            dest.writeStringList(usesLibraries);
            dest.writeStringList(usesOptionalLibraries);
            dest.writeStringArray(usesLibraryFiles);
            dest.writeTypedList(usesLibraryInfos);

            if (ArrayUtils.isEmpty(usesStaticLibraries)) {
                dest.writeInt(-1);
            } else {
                dest.writeInt(usesStaticLibraries.size());
                dest.writeStringList(usesStaticLibraries);
                dest.writeLongArray(usesStaticLibrariesVersions);
                for (String[] usesStaticLibrariesCertDigest : usesStaticLibrariesCertDigests) {
                    dest.writeStringArray(usesStaticLibrariesCertDigest);
                }
            }

            dest.writeParcelableList(preferredActivityFilters, flags);

            dest.writeStringList(mOriginalPackages);
            dest.writeString(mRealPackage);
            dest.writeStringList(mAdoptPermissions);
            dest.writeBundle(mAppMetaData);
            dest.writeInt(mVersionCode);
            dest.writeInt(mVersionCodeMajor);
            dest.writeString(mVersionName);
            dest.writeString(mSharedUserId);
            dest.writeInt(mSharedUserLabel);

            dest.writeParcelable(mSigningDetails, flags);

            dest.writeInt(mPreferredOrder);

            // long[] packageUsageTimeMillis is not persisted because it isn't information that
            // is parsed from the APK.

            // Object mExtras is not persisted because it is not information that is read from
            // the APK, rather, it is supplied by callers.

            dest.writeParcelableList(configPreferences, flags);
            dest.writeParcelableList(reqFeatures, flags);
            dest.writeParcelableList(featureGroups, flags);

            dest.writeInt(installLocation);
            dest.writeInt(coreApp ? 1 : 0);
            dest.writeInt(mRequiredForAllUsers ? 1 : 0);
            dest.writeString(mRestrictedAccountType);
            dest.writeString(mRequiredAccountType);
            dest.writeString(mOverlayTarget);
            dest.writeString(mOverlayTargetName);
            dest.writeString(mOverlayCategory);
            dest.writeInt(mOverlayPriority);
            dest.writeInt(mOverlayIsStatic ? 1 : 0);
            dest.writeInt(mCompileSdkVersion);
            dest.writeString(mCompileSdkVersionCodename);
            dest.writeArraySet(mUpgradeKeySets);
            writeKeySetMapping(dest, mKeySetMapping);
            dest.writeString(cpuAbiOverride);
            dest.writeInt(use32bitAbi ? 1 : 0);
            dest.writeByteArray(restrictUpdateHash);
            dest.writeInt(visibleToInstantApps ? 1 : 0);
        }

        /**
         * Writes the keyset mapping to the provided package. {@code null} mappings are permitted.
         */
        private static void writeKeySetMapping(
                Parcel dest, ArrayMap<String, ArraySet<PublicKey>> keySetMapping) {
            if (keySetMapping == null) {
                dest.writeInt(-1);
                return;
            }

            final int N = keySetMapping.size();
            dest.writeInt(N);

            for (int i = 0; i < N; i++) {
                dest.writeString(keySetMapping.keyAt(i));
                ArraySet<PublicKey> keys = keySetMapping.valueAt(i);
                if (keys == null) {
                    dest.writeInt(-1);
                    continue;
                }

                final int M = keys.size();
                dest.writeInt(M);
                for (int j = 0; j < M; j++) {
                    dest.writeSerializable(keys.valueAt(j));
                }
            }
        }

        /**
         * Reads a keyset mapping from the given parcel at the given data position. May return
         * {@code null} if the serialized mapping was {@code null}.
         */
        private static ArrayMap<String, ArraySet<PublicKey>> readKeySetMapping(Parcel in) {
            final int N = in.readInt();
            if (N == -1) {
                return null;
            }

            ArrayMap<String, ArraySet<PublicKey>> keySetMapping = new ArrayMap<>();
            for (int i = 0; i < N; ++i) {
                String key = in.readString();
                final int M = in.readInt();
                if (M == -1) {
                    keySetMapping.put(key, null);
                    continue;
                }

                ArraySet<PublicKey> keys = new ArraySet<>(M);
                for (int j = 0; j < M; ++j) {
                    PublicKey pk = (PublicKey) in.readSerializable();
                    keys.add(pk);
                }

                keySetMapping.put(key, keys);
            }

            return keySetMapping;
        }

        public static final Parcelable.Creator CREATOR = new Parcelable.Creator<Package>() {
            public Package createFromParcel(Parcel in) {
                return new Package(in);
            }

            public Package[] newArray(int size) {
                return new Package[size];
            }
        };
    }

    public static abstract class Component<II extends IntentInfo> {
        @UnsupportedAppUsage
        public final ArrayList<II> intents;
        @UnsupportedAppUsage
        public final String className;

        @UnsupportedAppUsage
        public Bundle metaData;
        @UnsupportedAppUsage
        public Package owner;
        /** The order of this component in relation to its peers */
        public int order;

        ComponentName componentName;
        String componentShortName;

        public Component(Package owner, ArrayList<II> intents, String className) {
            this.owner = owner;
            this.intents = intents;
            this.className = className;
        }

        public Component(Package owner) {
            this.owner = owner;
            this.intents = null;
            this.className = null;
        }

        public Component(final ParsePackageItemArgs args, final PackageItemInfo outInfo) {
            owner = args.owner;
            intents = new ArrayList<II>(0);
            if (parsePackageItemInfo(args.owner, outInfo, args.outError, args.tag, args.sa,
                    true /*nameRequired*/, args.nameRes, args.labelRes, args.iconRes,
                    args.roundIconRes, args.logoRes, args.bannerRes)) {
                className = outInfo.name;
            } else {
                className = null;
            }
        }

        public Component(final ParseComponentArgs args, final ComponentInfo outInfo) {
            this(args, (PackageItemInfo)outInfo);
            if (args.outError[0] != null) {
                return;
            }

            if (args.processRes != 0) {
                CharSequence pname;
                if (owner.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.FROYO) {
                    pname = args.sa.getNonConfigurationString(args.processRes,
                            Configuration.NATIVE_CONFIG_VERSION);
                } else {
                    // Some older apps have been seen to use a resource reference
                    // here that on older builds was ignored (with a warning).  We
                    // need to continue to do this for them so they don't break.
                    pname = args.sa.getNonResourceString(args.processRes);
                }
                outInfo.processName = buildProcessName(owner.applicationInfo.packageName,
                        owner.applicationInfo.processName, pname,
                        args.flags, args.sepProcesses, args.outError);
            }

            if (args.descriptionRes != 0) {
                outInfo.descriptionRes = args.sa.getResourceId(args.descriptionRes, 0);
            }

            outInfo.enabled = args.sa.getBoolean(args.enabledRes, true);
        }

        public Component(Component<II> clone) {
            owner = clone.owner;
            intents = clone.intents;
            className = clone.className;
            componentName = clone.componentName;
            componentShortName = clone.componentShortName;
        }

        @UnsupportedAppUsage
        public ComponentName getComponentName() {
            if (componentName != null) {
                return componentName;
            }
            if (className != null) {
                componentName = new ComponentName(owner.applicationInfo.packageName,
                        className);
            }
            return componentName;
        }

        protected Component(Parcel in) {
            className = in.readString();
            metaData = in.readBundle();
            intents = createIntentsList(in);

            owner = null;
        }

        protected void writeToParcel(Parcel dest, int flags) {
            dest.writeString(className);
            dest.writeBundle(metaData);

            writeIntentsList(intents, dest, flags);
        }

        /**
         * <p>
         * Implementation note: The serialized form for the intent list also contains the name
         * of the concrete class that's stored in the list, and assumes that every element of the
         * list is of the same type. This is very similar to the original parcelable mechanism.
         * We cannot use that directly because IntentInfo extends IntentFilter, which is parcelable
         * and is public API. It also declares Parcelable related methods as final which means
         * we can't extend them. The approach of using composition instead of inheritance leads to
         * a large set of cascading changes in the PackageManagerService, which seem undesirable.
         *
         * <p>
         * <b>WARNING: </b> The list of objects returned by this function might need to be fixed up
         * to make sure their owner fields are consistent. See {@code fixupOwner}.
         */
        private static void writeIntentsList(ArrayList<? extends IntentInfo> list, Parcel out,
                                             int flags) {
            if (list == null) {
                out.writeInt(-1);
                return;
            }

            final int N = list.size();
            out.writeInt(N);

            // Don't bother writing the component name if the list is empty.
            if (N > 0) {
                IntentInfo info = list.get(0);
                out.writeString(info.getClass().getName());

                for (int i = 0; i < N;i++) {
                    list.get(i).writeIntentInfoToParcel(out, flags);
                }
            }
        }

        private static <T extends IntentInfo> ArrayList<T> createIntentsList(Parcel in) {
            int N = in.readInt();
            if (N == -1) {
                return null;
            }

            if (N == 0) {
                return new ArrayList<>(0);
            }

            String componentName = in.readString();
            final ArrayList<T> intentsList;
            try {
                final Class<T> cls = (Class<T>) Class.forName(componentName);
                final Constructor<T> cons = cls.getConstructor(Parcel.class);

                intentsList = new ArrayList<>(N);
                for (int i = 0; i < N; ++i) {
                    intentsList.add(cons.newInstance(in));
                }
            } catch (ReflectiveOperationException ree) {
                throw new AssertionError("Unable to construct intent list for: " + componentName);
            }

            return intentsList;
        }

        public void appendComponentShortName(StringBuilder sb) {
            ComponentName.appendShortString(sb, owner.applicationInfo.packageName, className);
        }

        public void printComponentShortName(PrintWriter pw) {
            ComponentName.printShortString(pw, owner.applicationInfo.packageName, className);
        }

        public void setPackageName(String packageName) {
            componentName = null;
            componentShortName = null;
        }
    }

    public final static class Permission extends Component<IntentInfo> implements Parcelable {
        @UnsupportedAppUsage
        public final PermissionInfo info;
        @UnsupportedAppUsage
        public boolean tree;
        @UnsupportedAppUsage
        public PermissionGroup group;

        /**
         * @hide
         */
        public Permission(Package owner, @Nullable String backgroundPermission) {
            super(owner);
            info = new PermissionInfo(backgroundPermission);
        }

        @UnsupportedAppUsage
        public Permission(Package _owner, PermissionInfo _info) {
            super(_owner);
            info = _info;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            return "Permission{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + info.name + "}";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeParcelable(info, flags);
            dest.writeInt(tree ? 1 : 0);
            dest.writeParcelable(group, flags);
        }

        /** @hide */
        public boolean isAppOp() {
            return info.isAppOp();
        }

        private Permission(Parcel in) {
            super(in);
            final ClassLoader boot = Object.class.getClassLoader();
            info = in.readParcelable(boot);
            if (info.group != null) {
                info.group = info.group.intern();
            }

            tree = (in.readInt() == 1);
            group = in.readParcelable(boot);
        }

        public static final Parcelable.Creator CREATOR = new Parcelable.Creator<Permission>() {
            public Permission createFromParcel(Parcel in) {
                return new Permission(in);
            }

            public Permission[] newArray(int size) {
                return new Permission[size];
            }
        };
    }

    public final static class PermissionGroup extends Component<IntentInfo> implements Parcelable {
        @UnsupportedAppUsage
        public final PermissionGroupInfo info;

        public PermissionGroup(Package owner, @StringRes int requestDetailResourceId,
                @StringRes int backgroundRequestResourceId,
                @StringRes int backgroundRequestDetailResourceId) {
            super(owner);
            info = new PermissionGroupInfo(requestDetailResourceId, backgroundRequestResourceId,
                    backgroundRequestDetailResourceId);
        }

        public PermissionGroup(Package _owner, PermissionGroupInfo _info) {
            super(_owner);
            info = _info;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            return "PermissionGroup{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + info.name + "}";
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeParcelable(info, flags);
        }

        private PermissionGroup(Parcel in) {
            super(in);
            info = in.readParcelable(Object.class.getClassLoader());
        }

        public static final Parcelable.Creator CREATOR = new Parcelable.Creator<PermissionGroup>() {
            public PermissionGroup createFromParcel(Parcel in) {
                return new PermissionGroup(in);
            }

            public PermissionGroup[] newArray(int size) {
                return new PermissionGroup[size];
            }
        };
    }

    private static boolean copyNeeded(int flags, Package p,
            PackageUserState state, Bundle metaData, int userId) {
        if (userId != UserHandle.USER_SYSTEM) {
            // We always need to copy for other users, since we need
            // to fix up the uid.
            return true;
        }
        if (state.enabled != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            boolean enabled = state.enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
            if (p.applicationInfo.enabled != enabled) {
                return true;
            }
        }
        boolean suspended = (p.applicationInfo.flags & FLAG_SUSPENDED) != 0;
        if (state.suspended != suspended) {
            return true;
        }
        if (!state.installed || state.hidden) {
            return true;
        }
        if (state.stopped) {
            return true;
        }
        if (state.instantApp != p.applicationInfo.isInstantApp()) {
            return true;
        }
        if ((flags & PackageManager.GET_META_DATA) != 0
                && (metaData != null || p.mAppMetaData != null)) {
            return true;
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) != 0
                && p.usesLibraryFiles != null) {
            return true;
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) != 0
                && p.usesLibraryInfos != null) {
            return true;
        }
        if (p.staticSharedLibName != null) {
            return true;
        }
        return false;
    }

    @UnsupportedAppUsage
    public static ApplicationInfo generateApplicationInfo(Package p, int flags,
            PackageUserState state) {
        return generateApplicationInfo(p, flags, state, UserHandle.getCallingUserId());
    }

    private static void updateApplicationInfo(ApplicationInfo ai, int flags,
            PackageUserState state) {
        // CompatibilityMode is global state.
        if (!sCompatibilityModeEnabled) {
            ai.disableCompatibilityMode();
        }
        if (state.installed) {
            ai.flags |= ApplicationInfo.FLAG_INSTALLED;
        } else {
            ai.flags &= ~ApplicationInfo.FLAG_INSTALLED;
        }
        if (state.suspended) {
            ai.flags |= ApplicationInfo.FLAG_SUSPENDED;
        } else {
            ai.flags &= ~ApplicationInfo.FLAG_SUSPENDED;
        }
        if (state.instantApp) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_INSTANT;
        } else {
            ai.privateFlags &= ~ApplicationInfo.PRIVATE_FLAG_INSTANT;
        }
        if (state.virtualPreload) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_VIRTUAL_PRELOAD;
        } else {
            ai.privateFlags &= ~ApplicationInfo.PRIVATE_FLAG_VIRTUAL_PRELOAD;
        }
        if (state.hidden) {
            ai.privateFlags |= ApplicationInfo.PRIVATE_FLAG_HIDDEN;
        } else {
            ai.privateFlags &= ~ApplicationInfo.PRIVATE_FLAG_HIDDEN;
        }
        if (state.enabled == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            ai.enabled = true;
        } else if (state.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED) {
            ai.enabled = (flags&PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS) != 0;
        } else if (state.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                || state.enabled == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) {
            ai.enabled = false;
        }
        ai.enabledSetting = state.enabled;
        if (ai.category == ApplicationInfo.CATEGORY_UNDEFINED) {
            ai.category = state.categoryHint;
        }
        if (ai.category == ApplicationInfo.CATEGORY_UNDEFINED) {
            ai.category = FallbackCategoryProvider.getFallbackCategory(ai.packageName);
        }
        ai.seInfoUser = SELinuxUtil.assignSeinfoUser(state);
        ai.resourceDirs = state.getAllOverlayPaths();
        ai.icon = (sUseRoundIcon && ai.roundIconRes != 0) ? ai.roundIconRes : ai.iconRes;
    }

    @UnsupportedAppUsage
    public static ApplicationInfo generateApplicationInfo(Package p, int flags,
            PackageUserState state, int userId) {
        if (p == null) return null;
        if (!checkUseInstalledOrHidden(flags, state, p.applicationInfo) || !p.isMatch(flags)) {
            return null;
        }
        if (!copyNeeded(flags, p, state, null, userId)
                && ((flags&PackageManager.GET_DISABLED_UNTIL_USED_COMPONENTS) == 0
                        || state.enabled != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED)) {
            // In this case it is safe to directly modify the internal ApplicationInfo state:
            // - CompatibilityMode is global state, so will be the same for every call.
            // - We only come in to here if the app should reported as installed; this is the
            // default state, and we will do a copy otherwise.
            // - The enable state will always be reported the same for the application across
            // calls; the only exception is for the UNTIL_USED mode, and in that case we will
            // be doing a copy.
            updateApplicationInfo(p.applicationInfo, flags, state);
            return p.applicationInfo;
        }

        // Make shallow copy so we can store the metadata/libraries safely
        ApplicationInfo ai = new ApplicationInfo(p.applicationInfo);
        ai.initForUser(userId);
        if ((flags & PackageManager.GET_META_DATA) != 0) {
            ai.metaData = p.mAppMetaData;
        }
        if ((flags & PackageManager.GET_SHARED_LIBRARY_FILES) != 0) {
            ai.sharedLibraryFiles = p.usesLibraryFiles;
            ai.sharedLibraryInfos = p.usesLibraryInfos;
        }
        if (state.stopped) {
            ai.flags |= ApplicationInfo.FLAG_STOPPED;
        } else {
            ai.flags &= ~ApplicationInfo.FLAG_STOPPED;
        }
        updateApplicationInfo(ai, flags, state);
        return ai;
    }

    public static ApplicationInfo generateApplicationInfo(ApplicationInfo ai, int flags,
            PackageUserState state, int userId) {
        if (ai == null) return null;
        if (!checkUseInstalledOrHidden(flags, state, ai)) {
            return null;
        }
        // This is only used to return the ResolverActivity; we will just always
        // make a copy.
        ai = new ApplicationInfo(ai);
        ai.initForUser(userId);
        if (state.stopped) {
            ai.flags |= ApplicationInfo.FLAG_STOPPED;
        } else {
            ai.flags &= ~ApplicationInfo.FLAG_STOPPED;
        }
        updateApplicationInfo(ai, flags, state);
        return ai;
    }

    @UnsupportedAppUsage
    public static final PermissionInfo generatePermissionInfo(
            Permission p, int flags) {
        if (p == null) return null;
        if ((flags&PackageManager.GET_META_DATA) == 0) {
            return p.info;
        }
        PermissionInfo pi = new PermissionInfo(p.info);
        pi.metaData = p.metaData;
        return pi;
    }

    @UnsupportedAppUsage
    public static final PermissionGroupInfo generatePermissionGroupInfo(
            PermissionGroup pg, int flags) {
        if (pg == null) return null;
        if ((flags&PackageManager.GET_META_DATA) == 0) {
            return pg.info;
        }
        PermissionGroupInfo pgi = new PermissionGroupInfo(pg.info);
        pgi.metaData = pg.metaData;
        return pgi;
    }

    public final static class Activity extends Component<ActivityIntentInfo> implements Parcelable {
        @UnsupportedAppUsage
        public final ActivityInfo info;
        private boolean mHasMaxAspectRatio;
        private boolean mHasMinAspectRatio;

        private boolean hasMaxAspectRatio() {
            return mHasMaxAspectRatio;
        }

        private boolean hasMinAspectRatio() {
            return mHasMinAspectRatio;
        }

        // To construct custom activity which does not exist in manifest
        Activity(final Package owner, final String className, final ActivityInfo info) {
            super(owner, new ArrayList<>(0), className);
            this.info = info;
            this.info.applicationInfo = owner.applicationInfo;
        }

        public Activity(final ParseComponentArgs args, final ActivityInfo _info) {
            super(args, _info);
            info = _info;
            info.applicationInfo = args.owner.applicationInfo;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }


        private void setMaxAspectRatio(float maxAspectRatio) {
            if (info.resizeMode == RESIZE_MODE_RESIZEABLE
                    || info.resizeMode == RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) {
                // Resizeable activities can be put in any aspect ratio.
                return;
            }

            if (maxAspectRatio < 1.0f && maxAspectRatio != 0) {
                // Ignore any value lesser than 1.0.
                return;
            }

            info.maxAspectRatio = maxAspectRatio;
            mHasMaxAspectRatio = true;
        }

        private void setMinAspectRatio(float minAspectRatio) {
            if (info.resizeMode == RESIZE_MODE_RESIZEABLE
                    || info.resizeMode == RESIZE_MODE_RESIZEABLE_VIA_SDK_VERSION) {
                // Resizeable activities can be put in any aspect ratio.
                return;
            }

            if (minAspectRatio < 1.0f && minAspectRatio != 0) {
                // Ignore any value lesser than 1.0.
                return;
            }

            info.minAspectRatio = minAspectRatio;
            mHasMinAspectRatio = true;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Activity{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeParcelable(info, flags | Parcelable.PARCELABLE_ELIDE_DUPLICATES);
            dest.writeBoolean(mHasMaxAspectRatio);
            dest.writeBoolean(mHasMinAspectRatio);
        }

        private Activity(Parcel in) {
            super(in);
            info = in.readParcelable(Object.class.getClassLoader());
            mHasMaxAspectRatio = in.readBoolean();
            mHasMinAspectRatio = in.readBoolean();

            for (ActivityIntentInfo aii : intents) {
                aii.activity = this;
                order = Math.max(aii.getOrder(), order);
            }

            if (info.permission != null) {
                info.permission = info.permission.intern();
            }
        }

        public static final Parcelable.Creator CREATOR = new Parcelable.Creator<Activity>() {
            public Activity createFromParcel(Parcel in) {
                return new Activity(in);
            }

            public Activity[] newArray(int size) {
                return new Activity[size];
            }
        };
    }

    @UnsupportedAppUsage
    public static final ActivityInfo generateActivityInfo(Activity a, int flags,
            PackageUserState state, int userId) {
        if (a == null) return null;
        if (!checkUseInstalledOrHidden(flags, state, a.owner.applicationInfo)) {
            return null;
        }
        if (!copyNeeded(flags, a.owner, state, a.metaData, userId)) {
            updateApplicationInfo(a.info.applicationInfo, flags, state);
            return a.info;
        }
        // Make shallow copies so we can store the metadata safely
        ActivityInfo ai = new ActivityInfo(a.info);
        ai.metaData = a.metaData;
        ai.applicationInfo = generateApplicationInfo(a.owner, flags, state, userId);
        return ai;
    }

    public static final ActivityInfo generateActivityInfo(ActivityInfo ai, int flags,
            PackageUserState state, int userId) {
        if (ai == null) return null;
        if (!checkUseInstalledOrHidden(flags, state, ai.applicationInfo)) {
            return null;
        }
        // This is only used to return the ResolverActivity; we will just always
        // make a copy.
        ai = new ActivityInfo(ai);
        ai.applicationInfo = generateApplicationInfo(ai.applicationInfo, flags, state, userId);
        return ai;
    }

    public final static class Service extends Component<ServiceIntentInfo> implements Parcelable {
        @UnsupportedAppUsage
        public final ServiceInfo info;

        public Service(final ParseComponentArgs args, final ServiceInfo _info) {
            super(args, _info);
            info = _info;
            info.applicationInfo = args.owner.applicationInfo;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Service{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeParcelable(info, flags | Parcelable.PARCELABLE_ELIDE_DUPLICATES);
        }

        private Service(Parcel in) {
            super(in);
            info = in.readParcelable(Object.class.getClassLoader());

            for (ServiceIntentInfo aii : intents) {
                aii.service = this;
                order = Math.max(aii.getOrder(), order);
            }

            if (info.permission != null) {
                info.permission = info.permission.intern();
            }
        }

        public static final Parcelable.Creator CREATOR = new Parcelable.Creator<Service>() {
            public Service createFromParcel(Parcel in) {
                return new Service(in);
            }

            public Service[] newArray(int size) {
                return new Service[size];
            }
        };
    }

    @UnsupportedAppUsage
    public static final ServiceInfo generateServiceInfo(Service s, int flags,
            PackageUserState state, int userId) {
        if (s == null) return null;
        if (!checkUseInstalledOrHidden(flags, state, s.owner.applicationInfo)) {
            return null;
        }
        if (!copyNeeded(flags, s.owner, state, s.metaData, userId)) {
            updateApplicationInfo(s.info.applicationInfo, flags, state);
            return s.info;
        }
        // Make shallow copies so we can store the metadata safely
        ServiceInfo si = new ServiceInfo(s.info);
        si.metaData = s.metaData;
        si.applicationInfo = generateApplicationInfo(s.owner, flags, state, userId);
        return si;
    }

    public final static class Provider extends Component<ProviderIntentInfo> implements Parcelable {
        @UnsupportedAppUsage
        public final ProviderInfo info;
        @UnsupportedAppUsage
        public boolean syncable;

        public Provider(final ParseComponentArgs args, final ProviderInfo _info) {
            super(args, _info);
            info = _info;
            info.applicationInfo = args.owner.applicationInfo;
            syncable = false;
        }

        @UnsupportedAppUsage
        public Provider(Provider existingProvider) {
            super(existingProvider);
            this.info = existingProvider.info;
            this.syncable = existingProvider.syncable;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Provider{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeParcelable(info, flags | Parcelable.PARCELABLE_ELIDE_DUPLICATES);
            dest.writeInt((syncable) ? 1 : 0);
        }

        private Provider(Parcel in) {
            super(in);
            info = in.readParcelable(Object.class.getClassLoader());
            syncable = (in.readInt() == 1);

            for (ProviderIntentInfo aii : intents) {
                aii.provider = this;
            }

            if (info.readPermission != null) {
                info.readPermission = info.readPermission.intern();
            }

            if (info.writePermission != null) {
                info.writePermission = info.writePermission.intern();
            }

            if (info.authority != null) {
                info.authority = info.authority.intern();
            }
        }

        public static final Parcelable.Creator CREATOR = new Parcelable.Creator<Provider>() {
            public Provider createFromParcel(Parcel in) {
                return new Provider(in);
            }

            public Provider[] newArray(int size) {
                return new Provider[size];
            }
        };
    }

    @UnsupportedAppUsage
    public static final ProviderInfo generateProviderInfo(Provider p, int flags,
            PackageUserState state, int userId) {
        if (p == null) return null;
        if (!checkUseInstalledOrHidden(flags, state, p.owner.applicationInfo)) {
            return null;
        }
        if (!copyNeeded(flags, p.owner, state, p.metaData, userId)
                && ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) != 0
                        || p.info.uriPermissionPatterns == null)) {
            updateApplicationInfo(p.info.applicationInfo, flags, state);
            return p.info;
        }
        // Make shallow copies so we can store the metadata safely
        ProviderInfo pi = new ProviderInfo(p.info);
        pi.metaData = p.metaData;
        if ((flags & PackageManager.GET_URI_PERMISSION_PATTERNS) == 0) {
            pi.uriPermissionPatterns = null;
        }
        pi.applicationInfo = generateApplicationInfo(p.owner, flags, state, userId);
        return pi;
    }

    public final static class Instrumentation extends Component<IntentInfo> implements
            Parcelable {
        @UnsupportedAppUsage
        public final InstrumentationInfo info;

        public Instrumentation(final ParsePackageItemArgs args, final InstrumentationInfo _info) {
            super(args, _info);
            info = _info;
        }

        public void setPackageName(String packageName) {
            super.setPackageName(packageName);
            info.packageName = packageName;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("Instrumentation{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeParcelable(info, flags);
        }

        private Instrumentation(Parcel in) {
            super(in);
            info = in.readParcelable(Object.class.getClassLoader());

            if (info.targetPackage != null) {
                info.targetPackage = info.targetPackage.intern();
            }

            if (info.targetProcesses != null) {
                info.targetProcesses = info.targetProcesses.intern();
            }
        }

        public static final Parcelable.Creator CREATOR = new Parcelable.Creator<Instrumentation>() {
            public Instrumentation createFromParcel(Parcel in) {
                return new Instrumentation(in);
            }

            public Instrumentation[] newArray(int size) {
                return new Instrumentation[size];
            }
        };
    }

    @UnsupportedAppUsage
    public static final InstrumentationInfo generateInstrumentationInfo(
            Instrumentation i, int flags) {
        if (i == null) return null;
        if ((flags&PackageManager.GET_META_DATA) == 0) {
            return i.info;
        }
        InstrumentationInfo ii = new InstrumentationInfo(i.info);
        ii.metaData = i.metaData;
        return ii;
    }

    public static abstract class IntentInfo extends IntentFilter {
        @UnsupportedAppUsage
        public boolean hasDefault;
        @UnsupportedAppUsage
        public int labelRes;
        @UnsupportedAppUsage
        public CharSequence nonLocalizedLabel;
        @UnsupportedAppUsage
        public int icon;
        @UnsupportedAppUsage
        public int logo;
        @UnsupportedAppUsage
        public int banner;
        public int preferred;

        @UnsupportedAppUsage
        protected IntentInfo() {
        }

        protected IntentInfo(Parcel dest) {
            super(dest);
            hasDefault = (dest.readInt() == 1);
            labelRes = dest.readInt();
            nonLocalizedLabel = dest.readCharSequence();
            icon = dest.readInt();
            logo = dest.readInt();
            banner = dest.readInt();
            preferred = dest.readInt();
        }


        public void writeIntentInfoToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeInt(hasDefault ? 1 : 0);
            dest.writeInt(labelRes);
            dest.writeCharSequence(nonLocalizedLabel);
            dest.writeInt(icon);
            dest.writeInt(logo);
            dest.writeInt(banner);
            dest.writeInt(preferred);
        }
    }

    public final static class ActivityIntentInfo extends IntentInfo {
        @UnsupportedAppUsage
        public Activity activity;

        public ActivityIntentInfo(Activity _activity) {
            activity = _activity;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ActivityIntentInfo{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            activity.appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }

        public ActivityIntentInfo(Parcel in) {
            super(in);
        }
    }

    public final static class ServiceIntentInfo extends IntentInfo {
        @UnsupportedAppUsage
        public Service service;

        public ServiceIntentInfo(Service _service) {
            service = _service;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ServiceIntentInfo{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            service.appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }

        public ServiceIntentInfo(Parcel in) {
            super(in);
        }
    }

    public static final class ProviderIntentInfo extends IntentInfo {
        @UnsupportedAppUsage
        public Provider provider;

        public ProviderIntentInfo(Provider provider) {
            this.provider = provider;
        }

        public String toString() {
            StringBuilder sb = new StringBuilder(128);
            sb.append("ProviderIntentInfo{");
            sb.append(Integer.toHexString(System.identityHashCode(this)));
            sb.append(' ');
            provider.appendComponentShortName(sb);
            sb.append('}');
            return sb.toString();
        }

        public ProviderIntentInfo(Parcel in) {
            super(in);
        }
    }

    /**
     * @hide
     */
    @UnsupportedAppUsage
    public static void setCompatibilityModeEnabled(boolean compatibilityModeEnabled) {
        sCompatibilityModeEnabled = compatibilityModeEnabled;
    }

    /**
     * @hide
     */
    public static void readConfigUseRoundIcon(Resources r) {
        if (r != null) {
            sUseRoundIcon = r.getBoolean(com.android.internal.R.bool.config_useRoundIcon);
            return;
        }

        ApplicationInfo androidAppInfo;
        try {
            androidAppInfo = ActivityThread.getPackageManager().getApplicationInfo(
                    "android", 0 /* flags */,
                UserHandle.myUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        Resources systemResources = Resources.getSystem();

        // Create in-flight as this overlayable resource is only used when config changes
        Resources overlayableRes = ResourcesManager.getInstance().getResources(null,
                null,
                null,
                androidAppInfo.resourceDirs,
                androidAppInfo.sharedLibraryFiles,
                Display.DEFAULT_DISPLAY,
                null,
                systemResources.getCompatibilityInfo(),
                systemResources.getClassLoader(),
                null);

        sUseRoundIcon = overlayableRes.getBoolean(com.android.internal.R.bool.config_useRoundIcon);
    }

    public static class PackageParserException extends Exception {
        public final int error;

        public PackageParserException(int error, String detailMessage) {
            super(detailMessage);
            this.error = error;
        }

        public PackageParserException(int error, String detailMessage, Throwable throwable) {
            super(detailMessage, throwable);
            this.error = error;
        }
    }
}
