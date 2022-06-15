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
import android.content.pm.PackageParser.SigningDetails;
import android.content.pm.VerifierInfo;

import com.android.internal.util.DataClass;

import java.util.List;

/**
 * Lightweight parsed details about a single APK file.
 *
 * @hide
 */
@DataClass(genConstructor = false, genConstDefs = false)
public class ApkLite {
    /** Name of the package as used to identify it in the system */
    private final @NonNull String mPackageName;
    /** Path where this APK file was found on disk */
    private final @NonNull String mPath;
    /** Split name of this APK */
    private final @Nullable String mSplitName;
    /** Dependencies of the split APK */
    /** Name of the split APK that this APK depends on */
    private final @Nullable String mUsesSplitName;
    /** Name of the split APK that this APK is a configuration for */
    private final @Nullable String mConfigForSplit;

    /** Major version number of this package */
    private final int mVersionCodeMajor;
    /** Minor version number of this package */
    private final int mVersionCode;
    /** Revision code of this APK */
    private final int mRevisionCode;
    /**
     * Indicate the install location of this package
     *
     * @see {@link PackageInfo#INSTALL_LOCATION_AUTO}
     * @see {@link PackageInfo#INSTALL_LOCATION_INTERNAL_ONLY}
     * @see {@link PackageInfo#INSTALL_LOCATION_PREFER_EXTERNAL}
     */
    private final int mInstallLocation;
    /** Indicate the minimum SDK version number that the app requires */
    private final int mMinSdkVersion;
    /** Indicate the SDK version number that the application is targeting */
    private final int mTargetSdkVersion;
    /** Information about a package verifiers as used during package verification */
    private final @NonNull VerifierInfo[] mVerifiers;
    /** Signing-related data of an application package */
    private final @NonNull SigningDetails mSigningDetails;

    /** Indicate whether this APK is a 'feature' split */
    private final boolean mFeatureSplit;
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
    /** Indicate whether this app is profileable by Shell */
    private final boolean mProfileableByShell;
    /** Indicate whether this app needs to be loaded into other applications' processes */
    private final boolean mMultiArch;
    /** Indicate whether the 32 bit version of the ABI should be used */
    private final boolean mUse32bitAbi;
    /** Indicate whether installer extracts native libraries */
    private final boolean mExtractNativeLibs;
    /**
     * Indicate whether this package wants to run the dex within its APK but not extracted
     * or locally compiled variants.
     */
    private final boolean mUseEmbeddedDex;

    /** Name of the overlay-able set of elements package */
    private final @Nullable String mTargetPackageName;
    /** Indicate whether the overlay is static */
    private final boolean mOverlayIsStatic;
    /** Indicate the priority of this overlay package */
    private final int mOverlayPriority;

    /**
     * Indicate the policy to deal with user data when rollback is committed
     *
     * @see {@link android.content.pm.PackageManager.RollbackDataPolicy#RESTORE}
     * @see {@link android.content.pm.PackageManager.RollbackDataPolicy#WIPE}
     * @see {@link android.content.pm.PackageManager.RollbackDataPolicy#RETAIN}
     */
    private final int mRollbackDataPolicy;

    public ApkLite(String path, String packageName, String splitName, boolean isFeatureSplit,
            String configForSplit, String usesSplitName, boolean isSplitRequired, int versionCode,
            int versionCodeMajor, int revisionCode, int installLocation,
            List<VerifierInfo> verifiers, SigningDetails signingDetails, boolean coreApp,
            boolean debuggable, boolean profileableByShell, boolean multiArch, boolean use32bitAbi,
            boolean useEmbeddedDex, boolean extractNativeLibs, boolean isolatedSplits,
            String targetPackageName, boolean overlayIsStatic, int overlayPriority,
            int minSdkVersion, int targetSdkVersion, int rollbackDataPolicy) {
        mPath = path;
        mPackageName = packageName;
        mSplitName = splitName;
        mFeatureSplit = isFeatureSplit;
        mConfigForSplit = configForSplit;
        mUsesSplitName = usesSplitName;
        mSplitRequired = isSplitRequired;
        mVersionCode = versionCode;
        mVersionCodeMajor = versionCodeMajor;
        mRevisionCode = revisionCode;
        mInstallLocation = installLocation;
        mVerifiers = verifiers.toArray(new VerifierInfo[verifiers.size()]);
        mSigningDetails = signingDetails;
        mCoreApp = coreApp;
        mDebuggable = debuggable;
        mProfileableByShell = profileableByShell;
        mMultiArch = multiArch;
        mUse32bitAbi = use32bitAbi;
        mUseEmbeddedDex = useEmbeddedDex;
        mExtractNativeLibs = extractNativeLibs;
        mIsolatedSplits = isolatedSplits;
        mTargetPackageName = targetPackageName;
        mOverlayIsStatic = overlayIsStatic;
        mOverlayPriority = overlayPriority;
        mMinSdkVersion = minSdkVersion;
        mTargetSdkVersion = targetSdkVersion;
        mRollbackDataPolicy = rollbackDataPolicy;
    }

    /**
     * Return {@link #mVersionCode} and {@link #mVersionCodeMajor} combined together as a
     * single long value. The {@link #mVersionCodeMajor} is placed in the upper 32 bits.
     */
    public long getLongVersionCode() {
        return PackageInfo.composeLongVersionCode(mVersionCodeMajor, mVersionCode);
    }



    // Code below generated by codegen v1.0.22.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/parsing/ApkLite.java
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
     * Path where this APK file was found on disk
     */
    @DataClass.Generated.Member
    public @NonNull String getPath() {
        return mPath;
    }

    /**
     * Split name of this APK
     */
    @DataClass.Generated.Member
    public @Nullable String getSplitName() {
        return mSplitName;
    }

    /**
     * Name of the split APK that this APK depends on
     */
    @DataClass.Generated.Member
    public @Nullable String getUsesSplitName() {
        return mUsesSplitName;
    }

    /**
     * Name of the split APK that this APK is a configuration for
     */
    @DataClass.Generated.Member
    public @Nullable String getConfigForSplit() {
        return mConfigForSplit;
    }

    /**
     * Major version number of this package
     */
    @DataClass.Generated.Member
    public int getVersionCodeMajor() {
        return mVersionCodeMajor;
    }

    /**
     * Minor version number of this package
     */
    @DataClass.Generated.Member
    public int getVersionCode() {
        return mVersionCode;
    }

    /**
     * Revision code of this APK
     */
    @DataClass.Generated.Member
    public int getRevisionCode() {
        return mRevisionCode;
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
     * Indicate the minimum SDK version number that the app requires
     */
    @DataClass.Generated.Member
    public int getMinSdkVersion() {
        return mMinSdkVersion;
    }

    /**
     * Indicate the SDK version number that the application is targeting
     */
    @DataClass.Generated.Member
    public int getTargetSdkVersion() {
        return mTargetSdkVersion;
    }

    /**
     * Information about a package verifiers as used during package verification
     */
    @DataClass.Generated.Member
    public @NonNull VerifierInfo[] getVerifiers() {
        return mVerifiers;
    }

    /**
     * Signing-related data of an application package
     */
    @DataClass.Generated.Member
    public @NonNull SigningDetails getSigningDetails() {
        return mSigningDetails;
    }

    /**
     * Indicate whether this APK is a 'feature' split
     */
    @DataClass.Generated.Member
    public boolean isFeatureSplit() {
        return mFeatureSplit;
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
     * Indicate whether this app is profileable by Shell
     */
    @DataClass.Generated.Member
    public boolean isProfileableByShell() {
        return mProfileableByShell;
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
     * Indicate whether this package wants to run the dex within its APK but not extracted
     * or locally compiled variants.
     */
    @DataClass.Generated.Member
    public boolean isUseEmbeddedDex() {
        return mUseEmbeddedDex;
    }

    /**
     * Name of the overlay-able set of elements package
     */
    @DataClass.Generated.Member
    public @Nullable String getTargetPackageName() {
        return mTargetPackageName;
    }

    /**
     * Indicate whether the overlay is static
     */
    @DataClass.Generated.Member
    public boolean isOverlayIsStatic() {
        return mOverlayIsStatic;
    }

    /**
     * Indicate the priority of this overlay package
     */
    @DataClass.Generated.Member
    public int getOverlayPriority() {
        return mOverlayPriority;
    }

    /**
     * Indicate the policy to deal with user data when rollback is committed
     *
     * @see {@link android.content.pm.PackageManager.RollbackDataPolicy#RESTORE}
     * @see {@link android.content.pm.PackageManager.RollbackDataPolicy#WIPE}
     * @see {@link android.content.pm.PackageManager.RollbackDataPolicy#RETAIN}
     */
    @DataClass.Generated.Member
    public int getRollbackDataPolicy() {
        return mRollbackDataPolicy;
    }

    @DataClass.Generated(
            time = 1610596637723L,
            codegenVersion = "1.0.22",
            sourceFile = "frameworks/base/core/java/android/content/pm/parsing/ApkLite.java",
            inputSignatures = "private final @android.annotation.NonNull java.lang.String mPackageName\nprivate final @android.annotation.NonNull java.lang.String mPath\nprivate final @android.annotation.Nullable java.lang.String mSplitName\nprivate final @android.annotation.Nullable java.lang.String mUsesSplitName\nprivate final @android.annotation.Nullable java.lang.String mConfigForSplit\nprivate final  int mVersionCodeMajor\nprivate final  int mVersionCode\nprivate final  int mRevisionCode\nprivate final  int mInstallLocation\nprivate final  int mMinSdkVersion\nprivate final  int mTargetSdkVersion\nprivate final @android.annotation.NonNull android.content.pm.VerifierInfo[] mVerifiers\nprivate final @android.annotation.NonNull android.content.pm.PackageParser.SigningDetails mSigningDetails\nprivate final  boolean mFeatureSplit\nprivate final  boolean mIsolatedSplits\nprivate final  boolean mSplitRequired\nprivate final  boolean mCoreApp\nprivate final  boolean mDebuggable\nprivate final  boolean mProfileableByShell\nprivate final  boolean mMultiArch\nprivate final  boolean mUse32bitAbi\nprivate final  boolean mExtractNativeLibs\nprivate final  boolean mUseEmbeddedDex\nprivate final @android.annotation.Nullable java.lang.String mTargetPackageName\nprivate final  boolean mOverlayIsStatic\nprivate final  int mOverlayPriority\nprivate final  int mRollbackDataPolicy\npublic  long getLongVersionCode()\nclass ApkLite extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genConstructor=false, genConstDefs=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
