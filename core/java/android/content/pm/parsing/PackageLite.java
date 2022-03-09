/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageInfo;
import android.content.pm.VerifierInfo;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Lightweight parsed details about a single package.
 *
 * @hide
 */
@DataClass(genConstructor = false, genConstDefs = false)
public class PackageLite {
    /** Name of the package as used to identify it in the system */
    private final @NonNull String mPackageName;
    /**
     * Path where this package was found on disk. For monolithic packages
     * this is path to single base APK file; for cluster packages this is
     * path to the cluster directory.
     */
    private final @NonNull String mPath;
    /** Path of base APK */
    private final @NonNull String mBaseApkPath;
    /** Paths of any split APKs, ordered by parsed splitName */
    private final @Nullable String[] mSplitApkPaths;
    /** Names of any split APKs, ordered by parsed splitName */
    private final @Nullable String[] mSplitNames;
    /** Dependencies of any split APKs, ordered by parsed splitName */
    private final @Nullable String[] mUsesSplitNames;
    private final @Nullable String[] mConfigForSplit;
    /** Indicate the types of the required split are necessary for base APK to run */
    private final @Nullable Set<String> mBaseRequiredSplitTypes;
    /** Indicate the types of the required split are necessary for split APKs to run */
    private final @Nullable Set<String>[] mRequiredSplitTypes;
    /** Split type of any split APKs, ordered by parsed splitName */
    private final @Nullable Set<String>[] mSplitTypes;
    /** Major and minor version number of this package */
    private final int mVersionCodeMajor;
    private final int mVersionCode;
    private final int mTargetSdk;
    /** Revision code of base APK */
    private final int mBaseRevisionCode;
    /** Revision codes of any split APKs, ordered by parsed splitName */
    private final @Nullable int[] mSplitRevisionCodes;
    /**
     * Indicate the install location of this package
     *
     * @see {@link PackageInfo#INSTALL_LOCATION_AUTO}
     * @see {@link PackageInfo#INSTALL_LOCATION_INTERNAL_ONLY}
     * @see {@link PackageInfo#INSTALL_LOCATION_PREFER_EXTERNAL}
     */
    private final int mInstallLocation;
    /** Information about a package verifiers as used during package verification */
    private final @NonNull VerifierInfo[] mVerifiers;

    /** Indicate whether any split APKs that are features. Ordered by splitName */
    private final @Nullable boolean[] mIsFeatureSplits;
    /** Indicate whether each split should be load into their own Context objects */
    private final boolean mIsolatedSplits;
    /**
     * Indicate whether this package requires at least one split (either feature or resource)
     * to be present in order to function
     */
    private final boolean mSplitRequired;
    /** Indicate whether this app is coreApp */
    private final boolean mCoreApp;
    /** Indicate whether this app can be debugged */
    private final boolean mDebuggable;
    /** Indicate whether this app needs to be loaded into other applications' processes */
    private final boolean mMultiArch;
    /** Indicate whether the 32 bit version of the ABI should be used */
    private final boolean mUse32bitAbi;
    /** Indicate whether installer extracts native libraries */
    private final boolean mExtractNativeLibs;
    /** Indicate whether this app is profileable by Shell */
    private final boolean mProfileableByShell;
    /**
     * Indicate whether this package wants to run the dex within its APK but not extracted
     * or locally compiled variants.
     */
    private final boolean mUseEmbeddedDex;
    /**
     * Indicates if this package is a sdk.
     */
    private final boolean mIsSdkLibrary;

    public PackageLite(String path, String baseApkPath, ApkLite baseApk,
            String[] splitNames, boolean[] isFeatureSplits, String[] usesSplitNames,
            String[] configForSplit, String[] splitApkPaths, int[] splitRevisionCodes,
            int targetSdk, Set<String>[] requiredSplitTypes, Set<String>[] splitTypes) {
        // The following paths may be different from the path in ApkLite because we
        // move or rename the APK files. Use parameters to indicate the correct paths.
        mPath = path;
        mBaseApkPath = baseApkPath;
        mPackageName = baseApk.getPackageName();
        mVersionCode = baseApk.getVersionCode();
        mVersionCodeMajor = baseApk.getVersionCodeMajor();
        mInstallLocation = baseApk.getInstallLocation();
        mVerifiers = baseApk.getVerifiers();
        mBaseRevisionCode = baseApk.getRevisionCode();
        mCoreApp = baseApk.isCoreApp();
        mDebuggable = baseApk.isDebuggable();
        mMultiArch = baseApk.isMultiArch();
        mUse32bitAbi = baseApk.isUse32bitAbi();
        mExtractNativeLibs = baseApk.isExtractNativeLibs();
        mIsolatedSplits = baseApk.isIsolatedSplits();
        mUseEmbeddedDex = baseApk.isUseEmbeddedDex();
        mBaseRequiredSplitTypes = baseApk.getRequiredSplitTypes();
        mRequiredSplitTypes = requiredSplitTypes;
        mSplitRequired = (baseApk.isSplitRequired() || hasAnyRequiredSplitTypes());
        mProfileableByShell = baseApk.isProfileableByShell();
        mIsSdkLibrary = baseApk.isIsSdkLibrary();
        mSplitNames = splitNames;
        mSplitTypes = splitTypes;
        mIsFeatureSplits = isFeatureSplits;
        mUsesSplitNames = usesSplitNames;
        mConfigForSplit = configForSplit;
        mSplitApkPaths = splitApkPaths;
        mSplitRevisionCodes = splitRevisionCodes;
        mTargetSdk = targetSdk;
    }

    /**
     * Return code path to the base APK file, and split APK files if any.
     */
    public List<String> getAllApkPaths() {
        final ArrayList<String> paths = new ArrayList<>();
        paths.add(mBaseApkPath);
        if (!ArrayUtils.isEmpty(mSplitApkPaths)) {
            Collections.addAll(paths, mSplitApkPaths);
        }
        return paths;
    }

    /**
     * Return {@link #mVersionCode} and {@link #mVersionCodeMajor} combined together as a
     * single long value. The {@link #mVersionCodeMajor} is placed in the upper 32 bits.
     */
    public long getLongVersionCode() {
        return PackageInfo.composeLongVersionCode(mVersionCodeMajor, mVersionCode);
    }

    /**
     * Return if requiredSplitTypes presents in the package.
     */
    private boolean hasAnyRequiredSplitTypes() {
        if (!CollectionUtils.isEmpty(mBaseRequiredSplitTypes)) {
            return true;
        }
        return ArrayUtils.find(mRequiredSplitTypes, r -> !CollectionUtils.isEmpty(r)) != null;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/parsing/PackageLite.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /**
     * Name of the package as used to identify it in the system
     */
    @DataClass.Generated.Member
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /**
     * Path where this package was found on disk. For monolithic packages
     * this is path to single base APK file; for cluster packages this is
     * path to the cluster directory.
     */
    @DataClass.Generated.Member
    public @NonNull String getPath() {
        return mPath;
    }

    /**
     * Path of base APK
     */
    @DataClass.Generated.Member
    public @NonNull String getBaseApkPath() {
        return mBaseApkPath;
    }

    /**
     * Paths of any split APKs, ordered by parsed splitName
     */
    @DataClass.Generated.Member
    public @Nullable String[] getSplitApkPaths() {
        return mSplitApkPaths;
    }

    /**
     * Names of any split APKs, ordered by parsed splitName
     */
    @DataClass.Generated.Member
    public @Nullable String[] getSplitNames() {
        return mSplitNames;
    }

    /**
     * Dependencies of any split APKs, ordered by parsed splitName
     */
    @DataClass.Generated.Member
    public @Nullable String[] getUsesSplitNames() {
        return mUsesSplitNames;
    }

    @DataClass.Generated.Member
    public @Nullable String[] getConfigForSplit() {
        return mConfigForSplit;
    }

    /**
     * Indicate the types of the required split are necessary for base APK to run
     */
    @DataClass.Generated.Member
    public @Nullable Set<String> getBaseRequiredSplitTypes() {
        return mBaseRequiredSplitTypes;
    }

    /**
     * Indicate the types of the required split are necessary for split APKs to run
     */
    @DataClass.Generated.Member
    public @Nullable Set<String>[] getRequiredSplitTypes() {
        return mRequiredSplitTypes;
    }

    /**
     * Split type of any split APKs, ordered by parsed splitName
     */
    @DataClass.Generated.Member
    public @Nullable Set<String>[] getSplitTypes() {
        return mSplitTypes;
    }

    /**
     * Major and minor version number of this package
     */
    @DataClass.Generated.Member
    public int getVersionCodeMajor() {
        return mVersionCodeMajor;
    }

    @DataClass.Generated.Member
    public int getVersionCode() {
        return mVersionCode;
    }

    @DataClass.Generated.Member
    public int getTargetSdk() {
        return mTargetSdk;
    }

    /**
     * Revision code of base APK
     */
    @DataClass.Generated.Member
    public int getBaseRevisionCode() {
        return mBaseRevisionCode;
    }

    /**
     * Revision codes of any split APKs, ordered by parsed splitName
     */
    @DataClass.Generated.Member
    public @Nullable int[] getSplitRevisionCodes() {
        return mSplitRevisionCodes;
    }

    /**
     * Indicate the install location of this package
     *
     * @see {@link PackageInfo#INSTALL_LOCATION_AUTO}
     * @see {@link PackageInfo#INSTALL_LOCATION_INTERNAL_ONLY}
     * @see {@link PackageInfo#INSTALL_LOCATION_PREFER_EXTERNAL}
     */
    @DataClass.Generated.Member
    public int getInstallLocation() {
        return mInstallLocation;
    }

    /**
     * Information about a package verifiers as used during package verification
     */
    @DataClass.Generated.Member
    public @NonNull VerifierInfo[] getVerifiers() {
        return mVerifiers;
    }

    /**
     * Indicate whether any split APKs that are features. Ordered by splitName
     */
    @DataClass.Generated.Member
    public @Nullable boolean[] getIsFeatureSplits() {
        return mIsFeatureSplits;
    }

    /**
     * Indicate whether each split should be load into their own Context objects
     */
    @DataClass.Generated.Member
    public boolean isIsolatedSplits() {
        return mIsolatedSplits;
    }

    /**
     * Indicate whether this package requires at least one split (either feature or resource)
     * to be present in order to function
     */
    @DataClass.Generated.Member
    public boolean isSplitRequired() {
        return mSplitRequired;
    }

    /**
     * Indicate whether this app is coreApp
     */
    @DataClass.Generated.Member
    public boolean isCoreApp() {
        return mCoreApp;
    }

    /**
     * Indicate whether this app can be debugged
     */
    @DataClass.Generated.Member
    public boolean isDebuggable() {
        return mDebuggable;
    }

    /**
     * Indicate whether this app needs to be loaded into other applications' processes
     */
    @DataClass.Generated.Member
    public boolean isMultiArch() {
        return mMultiArch;
    }

    /**
     * Indicate whether the 32 bit version of the ABI should be used
     */
    @DataClass.Generated.Member
    public boolean isUse32bitAbi() {
        return mUse32bitAbi;
    }

    /**
     * Indicate whether installer extracts native libraries
     */
    @DataClass.Generated.Member
    public boolean isExtractNativeLibs() {
        return mExtractNativeLibs;
    }

    /**
     * Indicate whether this app is profileable by Shell
     */
    @DataClass.Generated.Member
    public boolean isProfileableByShell() {
        return mProfileableByShell;
    }

    /**
     * Indicate whether this package wants to run the dex within its APK but not extracted
     * or locally compiled variants.
     */
    @DataClass.Generated.Member
    public boolean isUseEmbeddedDex() {
        return mUseEmbeddedDex;
    }

    /**
     * Indicates if this package is a sdk.
     */
    @DataClass.Generated.Member
    public boolean isIsSdkLibrary() {
        return mIsSdkLibrary;
    }

    @DataClass.Generated(
            time = 1643132127068L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/content/pm/parsing/PackageLite.java",
            inputSignatures =
                    "private final @android.annotation.NonNull java.lang.String mPackageName\nprivate final @android.annotation.NonNull java.lang.String mPath\nprivate final @android.annotation.NonNull java.lang.String mBaseApkPath\nprivate final @android.annotation.Nullable java.lang.String[] mSplitApkPaths\nprivate final @android.annotation.Nullable java.lang.String[] mSplitNames\nprivate final @android.annotation.Nullable java.lang.String[] mUsesSplitNames\nprivate final @android.annotation.Nullable java.lang.String[] mConfigForSplit\nprivate final @android.annotation.Nullable java.util.Set<java.lang.String> mBaseRequiredSplitTypes\nprivate final @android.annotation.Nullable java.util.Set<java.lang.String>[] mRequiredSplitTypes\nprivate final @android.annotation.Nullable java.util.Set<java.lang.String>[] mSplitTypes\nprivate final  int mVersionCodeMajor\nprivate final  int mVersionCode\nprivate final  int mTargetSdk\nprivate final  int mBaseRevisionCode\nprivate final @android.annotation.Nullable int[] mSplitRevisionCodes\nprivate final  int mInstallLocation\nprivate final @android.annotation.NonNull android.content.pm.VerifierInfo[] mVerifiers\nprivate final @android.annotation.Nullable boolean[] mIsFeatureSplits\nprivate final  boolean mIsolatedSplits\nprivate final  boolean mSplitRequired\nprivate final  boolean mCoreApp\nprivate final  boolean mDebuggable\nprivate final  boolean mMultiArch\nprivate final  boolean mUse32bitAbi\nprivate final  boolean mExtractNativeLibs\nprivate final  boolean mProfileableByShell\nprivate final  boolean mUseEmbeddedDex\nprivate final  boolean mIsSdkLibrary\npublic  java.util.List<java.lang.String> getAllApkPaths()\npublic  long getLongVersionCode()\nprivate  boolean hasAnyRequiredSplitTypes()\nclass PackageLite extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genConstructor=false, genConstDefs=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
