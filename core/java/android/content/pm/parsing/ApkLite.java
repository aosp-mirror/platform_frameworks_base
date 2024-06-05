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
import android.content.pm.ArchivedPackageParcel;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningDetails;
import android.content.pm.VerifierInfo;

import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;

import java.util.List;
import java.util.Set;

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
    /** Indicate the types of the required split are necessary for this package to run */
    private final @Nullable Set<String> mRequiredSplitTypes;
    /** Split types of this APK */
    private final @Nullable Set<String> mSplitTypes;

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
     * A comma separated list of system property names to control whether the overlay should be
     * excluded based on the system property condition.
     */
    private final @Nullable String mRequiredSystemPropertyName;
    /**
     * A comma separated list of system property values to control whether the overlay should be
     * excluded based on the system property condition.
     */
    private final @Nullable String mRequiredSystemPropertyValue;

    /**
     * Indicate the policy to deal with user data when rollback is committed
     *
     * @see {@link PackageManager#ROLLBACK_DATA_POLICY_RESTORE}
     * @see {@link PackageManager#ROLLBACK_DATA_POLICY_WIPE}
     * @see {@link PackageManager#ROLLBACK_DATA_POLICY_RETAIN}
     */
    private final int mRollbackDataPolicy;

    /**
     * Indicates if this app contains a {@link android.app.admin.DeviceAdminReceiver}.
     */
    private final boolean mHasDeviceAdminReceiver;

    /**
     * Indicates if this apk is a sdk.
     */
    private final boolean mIsSdkLibrary;

    /**
     * Indicates if this system app can be updated.
     */
    private final boolean mUpdatableSystem;

    /**
     * Name of the emergency installer for the designated system app.
     */
    private final @Nullable String mEmergencyInstaller;

    /**
     * Archival install info.
     */
    private final @Nullable ArchivedPackageParcel mArchivedPackage;

    public ApkLite(String path, String packageName, String splitName, boolean isFeatureSplit,
            String configForSplit, String usesSplitName, boolean isSplitRequired, int versionCode,
            int versionCodeMajor, int revisionCode, int installLocation,
            List<VerifierInfo> verifiers, SigningDetails signingDetails, boolean coreApp,
            boolean debuggable, boolean profileableByShell, boolean multiArch, boolean use32bitAbi,
            boolean useEmbeddedDex, boolean extractNativeLibs, boolean isolatedSplits,
            String targetPackageName, boolean overlayIsStatic, int overlayPriority,
            String requiredSystemPropertyName, String requiredSystemPropertyValue,
            int minSdkVersion, int targetSdkVersion, int rollbackDataPolicy,
            Set<String> requiredSplitTypes, Set<String> splitTypes,
            boolean hasDeviceAdminReceiver, boolean isSdkLibrary, boolean updatableSystem,
            String emergencyInstaller) {
        mPath = path;
        mPackageName = packageName;
        mSplitName = splitName;
        mSplitTypes = splitTypes;
        mFeatureSplit = isFeatureSplit;
        mConfigForSplit = configForSplit;
        mUsesSplitName = usesSplitName;
        mRequiredSplitTypes = requiredSplitTypes;
        mSplitRequired = (isSplitRequired || hasAnyRequiredSplitTypes());
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
        mRequiredSystemPropertyName = requiredSystemPropertyName;
        mRequiredSystemPropertyValue = requiredSystemPropertyValue;
        mMinSdkVersion = minSdkVersion;
        mTargetSdkVersion = targetSdkVersion;
        mRollbackDataPolicy = rollbackDataPolicy;
        mHasDeviceAdminReceiver = hasDeviceAdminReceiver;
        mIsSdkLibrary = isSdkLibrary;
        mUpdatableSystem = updatableSystem;
        mEmergencyInstaller = emergencyInstaller;
        mArchivedPackage = null;
    }

    public ApkLite(String path, ArchivedPackageParcel archivedPackage) {
        mPath = path;
        mPackageName = archivedPackage.packageName;
        mSplitName = null; // base.apk
        mSplitTypes = null;
        mFeatureSplit = false;
        mConfigForSplit = null;
        mUsesSplitName = null;
        mRequiredSplitTypes = null;
        mSplitRequired = hasAnyRequiredSplitTypes();
        mVersionCode = archivedPackage.versionCode;
        mVersionCodeMajor = archivedPackage.versionCodeMajor;
        mRevisionCode = 0;
        mInstallLocation = PackageInfo.INSTALL_LOCATION_UNSPECIFIED;
        mVerifiers = new VerifierInfo[]{};
        mSigningDetails = archivedPackage.signingDetails;
        mCoreApp = false;
        mDebuggable = false;
        mProfileableByShell = false;
        mMultiArch = false;
        mUse32bitAbi = false;
        mUseEmbeddedDex = false;
        mExtractNativeLibs = false;
        mIsolatedSplits = false;
        mTargetPackageName = null;
        mOverlayIsStatic = false;
        mOverlayPriority = 0;
        mRequiredSystemPropertyName = null;
        mRequiredSystemPropertyValue = null;
        mMinSdkVersion = ApkLiteParseUtils.DEFAULT_MIN_SDK_VERSION;
        mTargetSdkVersion = archivedPackage.targetSdkVersion;
        mRollbackDataPolicy = 0;
        mHasDeviceAdminReceiver = false;
        mIsSdkLibrary = false;
        mUpdatableSystem = true;
        mEmergencyInstaller = null;
        mArchivedPackage = archivedPackage;
    }

    /**
     * Return {@link #mVersionCode} and {@link #mVersionCodeMajor} combined together as a
     * single long value. The {@link #mVersionCodeMajor} is placed in the upper 32 bits.
     */
    public long getLongVersionCode() {
        return PackageInfo.composeLongVersionCode(mVersionCodeMajor, mVersionCode);
    }

    /**
     * Return if requiredSplitTypes presents.
     */
    private boolean hasAnyRequiredSplitTypes() {
        return !CollectionUtils.isEmpty(mRequiredSplitTypes);
    }



    // Code below generated by codegen v1.0.23.
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
     * Indicate the types of the required split are necessary for this package to run
     */
    @DataClass.Generated.Member
    public @Nullable Set<String> getRequiredSplitTypes() {
        return mRequiredSplitTypes;
    }

    /**
     * Split types of this APK
     */
    @DataClass.Generated.Member
    public @Nullable Set<String> getSplitTypes() {
        return mSplitTypes;
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
     * A comma separated list of system property names to control whether the overlay should be
     * excluded based on the system property condition.
     */
    @DataClass.Generated.Member
    public @Nullable String getRequiredSystemPropertyName() {
        return mRequiredSystemPropertyName;
    }

    /**
     * A comma separated list of system property values to control whether the overlay should be
     * excluded based on the system property condition.
     */
    @DataClass.Generated.Member
    public @Nullable String getRequiredSystemPropertyValue() {
        return mRequiredSystemPropertyValue;
    }

    /**
     * Indicate the policy to deal with user data when rollback is committed
     *
     * @see {@link PackageManager#ROLLBACK_DATA_POLICY_RESTORE}
     * @see {@link PackageManager#ROLLBACK_DATA_POLICY_WIPE}
     * @see {@link PackageManager#ROLLBACK_DATA_POLICY_RETAIN}
     */
    @DataClass.Generated.Member
    public int getRollbackDataPolicy() {
        return mRollbackDataPolicy;
    }

    /**
     * Indicates if this app contains a {@link android.app.admin.DeviceAdminReceiver}.
     */
    @DataClass.Generated.Member
    public boolean isHasDeviceAdminReceiver() {
        return mHasDeviceAdminReceiver;
    }

    /**
     * Indicates if this apk is a sdk.
     */
    @DataClass.Generated.Member
    public boolean isIsSdkLibrary() {
        return mIsSdkLibrary;
    }

    /**
     * Indicates if this system app can be updated.
     */
    @DataClass.Generated.Member
    public boolean isUpdatableSystem() {
        return mUpdatableSystem;
    }

    /**
     * Name of the emergency installer for the designated system app.
     */
    @DataClass.Generated.Member
    public @Nullable String getEmergencyInstaller() {
        return mEmergencyInstaller;
    }

    /**
     * Archival install info.
     */
    @DataClass.Generated.Member
    public @Nullable ArchivedPackageParcel getArchivedPackage() {
        return mArchivedPackage;
    }

    @DataClass.Generated(
            time = 1706896661616L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/content/pm/parsing/ApkLite.java",
            inputSignatures = "private final @android.annotation.NonNull java.lang.String mPackageName\nprivate final @android.annotation.NonNull java.lang.String mPath\nprivate final @android.annotation.Nullable java.lang.String mSplitName\nprivate final @android.annotation.Nullable java.lang.String mUsesSplitName\nprivate final @android.annotation.Nullable java.lang.String mConfigForSplit\nprivate final @android.annotation.Nullable java.util.Set<java.lang.String> mRequiredSplitTypes\nprivate final @android.annotation.Nullable java.util.Set<java.lang.String> mSplitTypes\nprivate final  int mVersionCodeMajor\nprivate final  int mVersionCode\nprivate final  int mRevisionCode\nprivate final  int mInstallLocation\nprivate final  int mMinSdkVersion\nprivate final  int mTargetSdkVersion\nprivate final @android.annotation.NonNull android.content.pm.VerifierInfo[] mVerifiers\nprivate final @android.annotation.NonNull android.content.pm.SigningDetails mSigningDetails\nprivate final  boolean mFeatureSplit\nprivate final  boolean mIsolatedSplits\nprivate final  boolean mSplitRequired\nprivate final  boolean mCoreApp\nprivate final  boolean mDebuggable\nprivate final  boolean mProfileableByShell\nprivate final  boolean mMultiArch\nprivate final  boolean mUse32bitAbi\nprivate final  boolean mExtractNativeLibs\nprivate final  boolean mUseEmbeddedDex\nprivate final @android.annotation.Nullable java.lang.String mTargetPackageName\nprivate final  boolean mOverlayIsStatic\nprivate final  int mOverlayPriority\nprivate final @android.annotation.Nullable java.lang.String mRequiredSystemPropertyName\nprivate final @android.annotation.Nullable java.lang.String mRequiredSystemPropertyValue\nprivate final  int mRollbackDataPolicy\nprivate final  boolean mHasDeviceAdminReceiver\nprivate final  boolean mIsSdkLibrary\nprivate final  boolean mUpdatableSystem\nprivate final @android.annotation.Nullable java.lang.String mEmergencyInstaller\nprivate final @android.annotation.Nullable android.content.pm.ArchivedPackageParcel mArchivedPackage\npublic  long getLongVersionCode()\nprivate  boolean hasAnyRequiredSplitTypes()\nclass ApkLite extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genConstructor=false, genConstDefs=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
