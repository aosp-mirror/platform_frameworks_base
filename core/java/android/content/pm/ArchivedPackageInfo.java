/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Intent;

import com.android.internal.util.DataClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Contains fields required for archived package installation,
 * i.e. installation without an APK.
 */
@DataClass(genBuilder = false, genConstructor = false, genSetters = true)
@FlaggedApi(Flags.FLAG_ARCHIVING)
public final class ArchivedPackageInfo {
    /** Name of the package as used to identify it in the system */
    private @NonNull String mPackageName;
    /** Signing certificates used to sign the package. */
    private @NonNull SigningInfo mSigningInfo;
    /**
     * The version number of the package, as specified by the &lt;manifest&gt;tag's
     * {@link android.R.styleable#AndroidManifest_versionCode versionCode} attribute.
     */
    private int mVersionCode = 0;
    /**
     * The major version number of the package, as specified by the &lt;manifest&gt;tag's
     * {@link android.R.styleable#AndroidManifest_versionCode versionCodeMajor} attribute.
     */
    private int mVersionCodeMajor = 0;
    /**
     * This is the SDK version number that the application is targeting, as specified by the
     * &lt;manifest&gt; tag's {@link android.R.styleable#AndroidManifestUsesSdk_targetSdkVersion}
     * attribute.
     */
    private int mTargetSdkVersion = 0;
    /**
     * Package data will default to device protected storage. Specified by the &lt;manifest&gt;
     * tag's {@link android.R.styleable#AndroidManifestApplication_defaultToDeviceProtectedStorage}
     * attribute.
     */
    private @Nullable String mDefaultToDeviceProtectedStorage;
    /**
     * If {@code true} this app would like to run under the legacy storage model. Specified by the
     * &lt;manifest&gt; tag's
     * {@link android.R.styleable#AndroidManifestApplication_requestLegacyExternalStorage}
     * attribute.
     */
    private @Nullable String mRequestLegacyExternalStorage;
    /**
     * If {@code true} the user is prompted to keep the app's data on uninstall. Specified by the
     * &lt;manifest&gt; tag's
     * {@link android.R.styleable#AndroidManifestApplication_hasFragileUserData} attribute.
     */
    private @Nullable String mUserDataFragile;
    /**
     * List of the package's activities that specify {@link Intent#ACTION_MAIN} and
     * {@link Intent#CATEGORY_LAUNCHER}.
     * @see LauncherApps#getActivityList
     */
    private @NonNull List<ArchivedActivityInfo> mLauncherActivities;

    public ArchivedPackageInfo(@NonNull String packageName, @NonNull SigningInfo signingInfo,
            @NonNull List<ArchivedActivityInfo> launcherActivities) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(signingInfo);
        Objects.requireNonNull(launcherActivities);
        this.mPackageName = packageName;
        this.mSigningInfo = signingInfo;
        this.mLauncherActivities = launcherActivities;
    }

    /**
     * Constructs the archived package from parcel.
     * @hide
     */
    public ArchivedPackageInfo(@NonNull ArchivedPackageParcel parcel) {
        mPackageName = parcel.packageName;
        mSigningInfo = new SigningInfo(parcel.signingDetails);
        mVersionCode = parcel.versionCode;
        mVersionCodeMajor = parcel.versionCodeMajor;
        mTargetSdkVersion = parcel.targetSdkVersion;
        mDefaultToDeviceProtectedStorage = parcel.defaultToDeviceProtectedStorage;
        mRequestLegacyExternalStorage = parcel.requestLegacyExternalStorage;
        mUserDataFragile = parcel.userDataFragile;
        mLauncherActivities = new ArrayList<>();
        if (parcel.archivedActivities != null) {
            for (var activityParcel : parcel.archivedActivities) {
                mLauncherActivities.add(new ArchivedActivityInfo(activityParcel));
            }
        }
    }

    /* @hide */
    ArchivedPackageParcel getParcel() {
        var parcel = new ArchivedPackageParcel();
        parcel.packageName = mPackageName;
        parcel.signingDetails = mSigningInfo.getSigningDetails();
        parcel.versionCode = mVersionCode;
        parcel.versionCodeMajor = mVersionCodeMajor;
        parcel.targetSdkVersion = mTargetSdkVersion;
        parcel.defaultToDeviceProtectedStorage = mDefaultToDeviceProtectedStorage;
        parcel.requestLegacyExternalStorage = mRequestLegacyExternalStorage;
        parcel.userDataFragile = mUserDataFragile;

        parcel.archivedActivities = new ArchivedActivityParcel[mLauncherActivities.size()];
        for (int i = 0, size = parcel.archivedActivities.length; i < size; ++i) {
            parcel.archivedActivities[i] = mLauncherActivities.get(i).getParcel();
        }

        return parcel;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/content/pm/ArchivedPackageInfo.java
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
     * Signing certificates used to sign the package.
     */
    @DataClass.Generated.Member
    public @NonNull SigningInfo getSigningInfo() {
        return mSigningInfo;
    }

    /**
     * The version number of the package, as specified by the &lt;manifest&gt;tag's
     * {@link android.R.styleable#AndroidManifest_versionCode versionCode} attribute.
     */
    @DataClass.Generated.Member
    public int getVersionCode() {
        return mVersionCode;
    }

    /**
     * The major version number of the package, as specified by the &lt;manifest&gt;tag's
     * {@link android.R.styleable#AndroidManifest_versionCode versionCodeMajor} attribute.
     */
    @DataClass.Generated.Member
    public int getVersionCodeMajor() {
        return mVersionCodeMajor;
    }

    /**
     * This is the SDK version number that the application is targeting, as specified by the
     * &lt;manifest&gt; tag's {@link android.R.styleable#AndroidManifestUsesSdk_targetSdkVersion}
     * attribute.
     */
    @DataClass.Generated.Member
    public int getTargetSdkVersion() {
        return mTargetSdkVersion;
    }

    /**
     * Package data will default to device protected storage. Specified by the &lt;manifest&gt;
     * tag's {@link android.R.styleable#AndroidManifestApplication_defaultToDeviceProtectedStorage}
     * attribute.
     */
    @DataClass.Generated.Member
    public @Nullable String getDefaultToDeviceProtectedStorage() {
        return mDefaultToDeviceProtectedStorage;
    }

    /**
     * If {@code true} this app would like to run under the legacy storage model. Specified by the
     * &lt;manifest&gt; tag's
     * {@link android.R.styleable#AndroidManifestApplication_requestLegacyExternalStorage}
     * attribute.
     */
    @DataClass.Generated.Member
    public @Nullable String getRequestLegacyExternalStorage() {
        return mRequestLegacyExternalStorage;
    }

    /**
     * If {@code true} the user is prompted to keep the app's data on uninstall. Specified by the
     * &lt;manifest&gt; tag's
     * {@link android.R.styleable#AndroidManifestApplication_hasFragileUserData} attribute.
     */
    @DataClass.Generated.Member
    public @Nullable String getUserDataFragile() {
        return mUserDataFragile;
    }

    /**
     * List of the package's activities that specify {@link Intent#ACTION_MAIN} and
     * {@link Intent#CATEGORY_LAUNCHER}.
     *
     * @see LauncherApps#getActivityList
     */
    @DataClass.Generated.Member
    public @NonNull List<ArchivedActivityInfo> getLauncherActivities() {
        return mLauncherActivities;
    }

    /**
     * Name of the package as used to identify it in the system
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedPackageInfo setPackageName(@NonNull String value) {
        mPackageName = value;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mPackageName);
        return this;
    }

    /**
     * Signing certificates used to sign the package.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedPackageInfo setSigningInfo(@NonNull SigningInfo value) {
        mSigningInfo = value;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mSigningInfo);
        return this;
    }

    /**
     * The version number of the package, as specified by the &lt;manifest&gt;tag's
     * {@link android.R.styleable#AndroidManifest_versionCode versionCode} attribute.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedPackageInfo setVersionCode( int value) {
        mVersionCode = value;
        return this;
    }

    /**
     * The major version number of the package, as specified by the &lt;manifest&gt;tag's
     * {@link android.R.styleable#AndroidManifest_versionCode versionCodeMajor} attribute.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedPackageInfo setVersionCodeMajor( int value) {
        mVersionCodeMajor = value;
        return this;
    }

    /**
     * This is the SDK version number that the application is targeting, as specified by the
     * &lt;manifest&gt; tag's {@link android.R.styleable#AndroidManifestUsesSdk_targetSdkVersion}
     * attribute.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedPackageInfo setTargetSdkVersion( int value) {
        mTargetSdkVersion = value;
        return this;
    }

    /**
     * Package data will default to device protected storage. Specified by the &lt;manifest&gt;
     * tag's {@link android.R.styleable#AndroidManifestApplication_defaultToDeviceProtectedStorage}
     * attribute.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedPackageInfo setDefaultToDeviceProtectedStorage(@NonNull String value) {
        mDefaultToDeviceProtectedStorage = value;
        return this;
    }

    /**
     * If {@code true} this app would like to run under the legacy storage model. Specified by the
     * &lt;manifest&gt; tag's
     * {@link android.R.styleable#AndroidManifestApplication_requestLegacyExternalStorage}
     * attribute.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedPackageInfo setRequestLegacyExternalStorage(@NonNull String value) {
        mRequestLegacyExternalStorage = value;
        return this;
    }

    /**
     * If {@code true} the user is prompted to keep the app's data on uninstall. Specified by the
     * &lt;manifest&gt; tag's
     * {@link android.R.styleable#AndroidManifestApplication_hasFragileUserData} attribute.
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedPackageInfo setUserDataFragile(@NonNull String value) {
        mUserDataFragile = value;
        return this;
    }

    /**
     * List of the package's activities that specify {@link Intent#ACTION_MAIN} and
     * {@link Intent#CATEGORY_LAUNCHER}.
     *
     * @see LauncherApps#getActivityList
     */
    @DataClass.Generated.Member
    public @NonNull ArchivedPackageInfo setLauncherActivities(@NonNull List<ArchivedActivityInfo> value) {
        mLauncherActivities = value;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mLauncherActivities);
        return this;
    }

    @DataClass.Generated(
            time = 1698789995536L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/content/pm/ArchivedPackageInfo.java",
            inputSignatures = "private @android.annotation.NonNull java.lang.String mPackageName\nprivate @android.annotation.NonNull android.content.pm.SigningInfo mSigningInfo\nprivate  int mVersionCode\nprivate  int mVersionCodeMajor\nprivate  int mTargetSdkVersion\nprivate @android.annotation.Nullable java.lang.String mDefaultToDeviceProtectedStorage\nprivate @android.annotation.Nullable java.lang.String mRequestLegacyExternalStorage\nprivate @android.annotation.Nullable java.lang.String mUserDataFragile\nprivate @android.annotation.NonNull java.util.List<android.content.pm.ArchivedActivityInfo> mLauncherActivities\n  android.content.pm.ArchivedPackageParcel getParcel()\nclass ArchivedPackageInfo extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genBuilder=false, genConstructor=false, genSetters=true)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
