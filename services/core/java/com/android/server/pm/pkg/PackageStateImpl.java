/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.pm.pkg;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.SigningInfo;
import android.content.pm.overlay.OverlayPaths;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.util.DataClass;
import com.android.server.pm.PackageManagerService;
import com.android.server.pm.PackageSetting;
import com.android.server.pm.Settings;
import com.android.server.pm.parsing.pkg.AndroidPackage;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Because a {@link PackageSetting} cannot be returned from {@link Settings} without holding the
 * {@link PackageManagerService#mLock}, this class serves as a memory snapshot of the state of a
 * single package, for use with {@link PackageManagerInternal#getPackageState(String)} and {@link
 * PackageManagerInternal#forEachPackageState(boolean, Consumer)}.
 *
 * @hide
 */
@DataClass(genConstructor = false)
@DataClass.Suppress({"mUserStates"})
public class PackageStateImpl implements PackageState {

    public static PackageState copy(@NonNull PackageStateInternal pkgSetting) {
        return new PackageStateImpl(pkgSetting, pkgSetting.getPkg());
    }

    private static class Booleans {
        @IntDef({
                SYSTEM,
                EXTERNAL_STORAGE,
                PRIVILEGED,
                OEM,
                VENDOR,
                PRODUCT,
                SYSTEM_EXT,
                REQUIRED_FOR_SYSTEM_USER,
                ODM,
                FORCE_QUERYABLE_OVERRIDE,
                HIDDEN_UNTIL_INSTALLED,
                INSTALL_PERMISSIONS_FIXED,
                UPDATE_AVAILABLE,
                UPDATED_SYSTEM_APP,
        })
        public @interface Flags {
        }

        private static final int SYSTEM = 1;
        private static final int EXTERNAL_STORAGE = 1 << 1;
        private static final int PRIVILEGED = 1 << 2;
        private static final int OEM = 1 << 3;
        private static final int VENDOR = 1 << 4;
        private static final int PRODUCT = 1 << 5;
        private static final int SYSTEM_EXT = 1 << 6;
        private static final int REQUIRED_FOR_SYSTEM_USER = 1 << 7;
        private static final int ODM = 1 << 8;
        private static final int FORCE_QUERYABLE_OVERRIDE = 1 << 9;
        private static final int HIDDEN_UNTIL_INSTALLED = 1 << 10;
        private static final int INSTALL_PERMISSIONS_FIXED = 1 << 11;
        private static final int UPDATE_AVAILABLE = 1 << 12;
        private static final int UPDATED_SYSTEM_APP = 1 << 13;
    }

    private int mBooleans;

    private void setBoolean(@Booleans.Flags int flag, boolean value) {
        if (value) {
            mBooleans |= flag;
        } else {
            mBooleans &= ~flag;
        }
    }

    private boolean getBoolean(@Booleans.Flags int flag) {
        return (mBooleans & flag) != 0;
    }

    @Nullable
    private final AndroidPackageApi mAndroidPackage;

    @NonNull
    private final String mPackageName;
    @Nullable
    private final String mVolumeUuid;
    private final int mAppId;
    private final int mCategoryOverride;
    @Nullable
    private final String mCpuAbiOverride;
    private final long mLastModifiedTime;
    private final long mLastUpdateTime;
    private final long mLongVersionCode;
    @NonNull
    private final Map<String, Set<String>> mMimeGroups;
    @NonNull
    private final File mPath;
    @Nullable
    private final String mPrimaryCpuAbi;
    @Nullable
    private final String mSecondaryCpuAbi;
    private final boolean mHasSharedUser;
    private final int mSharedUserAppId;
    @NonNull
    private final String[] mUsesSdkLibraries;
    @NonNull
    private final long[] mUsesSdkLibrariesVersionsMajor;
    @NonNull
    private final String[] mUsesStaticLibraries;
    @NonNull
    private final long[] mUsesStaticLibrariesVersions;
    @NonNull
    private final List<SharedLibraryInfo> mUsesLibraryInfos;
    @NonNull
    private final List<String> mUsesLibraryFiles;
    @NonNull
    private final long[] mLastPackageUsageTime;
    @NonNull
    private final SigningInfo mSigningInfo;
    @NonNull
    private final SparseArray<PackageUserState> mUserStates;

    private PackageStateImpl(@NonNull PackageState pkgState, @Nullable AndroidPackage pkg) {
        mAndroidPackage = pkg;

        setBoolean(Booleans.SYSTEM, pkgState.isSystem());
        setBoolean(Booleans.EXTERNAL_STORAGE, pkgState.isExternalStorage());
        setBoolean(Booleans.PRIVILEGED, pkgState.isPrivileged());
        setBoolean(Booleans.OEM, pkgState.isOem());
        setBoolean(Booleans.VENDOR, pkgState.isVendor());
        setBoolean(Booleans.PRODUCT, pkgState.isProduct());
        setBoolean(Booleans.SYSTEM_EXT, pkgState.isSystemExt());
        setBoolean(Booleans.REQUIRED_FOR_SYSTEM_USER, pkgState.isRequiredForSystemUser());
        setBoolean(Booleans.ODM, pkgState.isOdm());

        mPackageName = pkgState.getPackageName();
        mVolumeUuid = pkgState.getVolumeUuid();
        mAppId = pkgState.getAppId();
        mCategoryOverride = pkgState.getCategoryOverride();
        mCpuAbiOverride = pkgState.getCpuAbiOverride();
        mLastModifiedTime = pkgState.getLastModifiedTime();
        mLastUpdateTime = pkgState.getLastUpdateTime();
        mLongVersionCode = pkgState.getVersionCode();
        mMimeGroups = pkgState.getMimeGroups();
        mPath = pkgState.getPath();
        mPrimaryCpuAbi = pkgState.getPrimaryCpuAbi();
        mSecondaryCpuAbi = pkgState.getSecondaryCpuAbi();
        mHasSharedUser = pkgState.hasSharedUser();
        mSharedUserAppId = pkgState.getSharedUserAppId();
        mUsesSdkLibraries = pkgState.getUsesSdkLibraries();
        mUsesSdkLibrariesVersionsMajor = pkgState.getUsesSdkLibrariesVersionsMajor();
        mUsesStaticLibraries = pkgState.getUsesStaticLibraries();
        mUsesStaticLibrariesVersions = pkgState.getUsesStaticLibrariesVersions();
        mUsesLibraryInfos = pkgState.getUsesLibraryInfos();
        mUsesLibraryFiles = pkgState.getUsesLibraryFiles();
        setBoolean(Booleans.FORCE_QUERYABLE_OVERRIDE, pkgState.isForceQueryableOverride());
        setBoolean(Booleans.HIDDEN_UNTIL_INSTALLED, pkgState.isHiddenUntilInstalled());
        setBoolean(Booleans.INSTALL_PERMISSIONS_FIXED, pkgState.isInstallPermissionsFixed());
        setBoolean(Booleans.UPDATE_AVAILABLE, pkgState.isUpdateAvailable());
        mLastPackageUsageTime = pkgState.getLastPackageUsageTime();
        setBoolean(Booleans.UPDATED_SYSTEM_APP, pkgState.isUpdatedSystemApp());
        mSigningInfo = pkgState.getSigningInfo();

        SparseArray<? extends PackageUserState> userStates = pkgState.getUserStates();
        int userStatesSize = userStates.size();
        mUserStates = new SparseArray<>(userStatesSize);
        for (int index = 0; index < userStatesSize; index++) {
            mUserStates.put(mUserStates.keyAt(index),
                    UserStateImpl.copy(mUserStates.valueAt(index)));
        }
    }

    @Override
    public boolean isExternalStorage() {
        return getBoolean(Booleans.EXTERNAL_STORAGE);
    }

    @Override
    public boolean isForceQueryableOverride() {
        return getBoolean(Booleans.FORCE_QUERYABLE_OVERRIDE);
    }

    @Override
    public boolean isHiddenUntilInstalled() {
        return getBoolean(Booleans.HIDDEN_UNTIL_INSTALLED);
    }

    @Override
    public boolean isInstallPermissionsFixed() {
        return getBoolean(Booleans.INSTALL_PERMISSIONS_FIXED);
    }

    @Override
    public boolean isOdm() {
        return getBoolean(Booleans.ODM);
    }

    @Override
    public boolean isOem() {
        return getBoolean(Booleans.OEM);
    }

    @Override
    public boolean isPrivileged() {
        return getBoolean(Booleans.PRIVILEGED);
    }

    @Override
    public boolean isProduct() {
        return getBoolean(Booleans.PRODUCT);
    }

    @Override
    public boolean isRequiredForSystemUser() {
        return getBoolean(Booleans.REQUIRED_FOR_SYSTEM_USER);
    }

    @Override
    public boolean isSystem() {
        return getBoolean(Booleans.SYSTEM);
    }

    @Override
    public boolean isSystemExt() {
        return getBoolean(Booleans.SYSTEM_EXT);
    }

    @Override
    public boolean isUpdateAvailable() {
        return getBoolean(Booleans.UPDATE_AVAILABLE);
    }

    @Override
    public boolean isUpdatedSystemApp() {
        return getBoolean(Booleans.UPDATED_SYSTEM_APP);
    }

    @Override
    public boolean isVendor() {
        return getBoolean(Booleans.VENDOR);
    }

    @Override
    public long getVersionCode() {
        return mLongVersionCode;
    }

    @Override
    public boolean hasSharedUser() {
        return mHasSharedUser;
    }

    @Override
    public int getSharedUserAppId() {
        return mSharedUserAppId;
    }
    /**
     * @hide
     */
    @DataClass(genConstructor = false)
    public static class UserStateImpl implements PackageUserState {

        public static PackageUserState copy(@NonNull PackageUserState state) {
            return new UserStateImpl(state);
        }

        private static class Booleans {
            @IntDef({
                    HIDDEN,
                    INSTALLED,
                    INSTANT_APP,
                    NOT_LAUNCHED,
                    STOPPED,
                    SUSPENDED,
                    VIRTUAL_PRELOAD,
            })
            public @interface Flags {
            }

            private static final int HIDDEN = 1;
            private static final int INSTALLED = 1 << 1;
            private static final int INSTANT_APP = 1 << 2;
            private static final int NOT_LAUNCHED = 1 << 3;
            private static final int STOPPED = 1 << 4;
            private static final int SUSPENDED = 1 << 5;
            private static final int VIRTUAL_PRELOAD = 1 << 6;
        }

        private int mBooleans;

        private void setBoolean(@Booleans.Flags int flag, boolean value) {
            if (value) {
                mBooleans |= flag;
            } else {
                mBooleans &= ~flag;
            }
        }

        private boolean getBoolean(@Booleans.Flags int flag) {
            return (mBooleans & flag) != 0;
        }

        private final long mCeDataInode;
        @NonNull
        private final ArraySet<String> mDisabledComponents;
        @PackageManager.DistractionRestriction
        private final int mDistractionFlags;
        @NonNull
        private final ArraySet<String> mEnabledComponents;
        private final int mEnabledState;
        @Nullable
        private final String mHarmfulAppWarning;
        @PackageManager.InstallReason
        private final int mInstallReason;
        @Nullable
        private final String mLastDisableAppCaller;
        @NonNull
        private final OverlayPaths mOverlayPaths;
        @NonNull
        private final Map<String, OverlayPaths> mSharedLibraryOverlayPaths;
        @PackageManager.UninstallReason
        private final int mUninstallReason;
        @Nullable
        private final String mSplashScreenTheme;
        private final long mFirstInstallTime;

        private UserStateImpl(@NonNull PackageUserState userState) {
            mCeDataInode = userState.getCeDataInode();
            mDisabledComponents = userState.getDisabledComponents();
            mDistractionFlags = userState.getDistractionFlags();
            mEnabledComponents = userState.getEnabledComponents();
            mEnabledState = userState.getEnabledState();
            mHarmfulAppWarning = userState.getHarmfulAppWarning();
            mInstallReason = userState.getInstallReason();
            mLastDisableAppCaller = userState.getLastDisableAppCaller();
            mOverlayPaths = userState.getOverlayPaths();
            mSharedLibraryOverlayPaths = userState.getSharedLibraryOverlayPaths();
            mUninstallReason = userState.getUninstallReason();
            mSplashScreenTheme = userState.getSplashScreenTheme();
            setBoolean(Booleans.HIDDEN, userState.isHidden());
            setBoolean(Booleans.INSTALLED, userState.isInstalled());
            setBoolean(Booleans.INSTANT_APP, userState.isInstantApp());
            setBoolean(Booleans.NOT_LAUNCHED, userState.isNotLaunched());
            setBoolean(Booleans.STOPPED, userState.isStopped());
            setBoolean(Booleans.SUSPENDED, userState.isSuspended());
            setBoolean(Booleans.VIRTUAL_PRELOAD, userState.isVirtualPreload());
            mFirstInstallTime = userState.getFirstInstallTime();
        }

        @Override
        public boolean isHidden() {
            return getBoolean(Booleans.HIDDEN);
        }

        @Override
        public boolean isInstalled() {
            return getBoolean(Booleans.INSTALLED);
        }

        @Override
        public boolean isInstantApp() {
            return getBoolean(Booleans.INSTANT_APP);
        }

        @Override
        public boolean isNotLaunched() {
            return getBoolean(Booleans.NOT_LAUNCHED);
        }

        @Override
        public boolean isStopped() {
            return getBoolean(Booleans.STOPPED);
        }

        @Override
        public boolean isSuspended() {
            return getBoolean(Booleans.SUSPENDED);
        }

        @Override
        public boolean isVirtualPreload() {
            return getBoolean(Booleans.VIRTUAL_PRELOAD);
        }

        @Override
        public boolean isComponentEnabled(String componentName) {
            return mEnabledComponents.contains(componentName);
        }

        @Override
        public boolean isComponentDisabled(String componentName) {
            return mDisabledComponents.contains(componentName);
        }

        @Override
        public OverlayPaths getAllOverlayPaths() {
            if (mOverlayPaths == null && mSharedLibraryOverlayPaths == null) {
                return null;
            }
            final OverlayPaths.Builder newPaths = new OverlayPaths.Builder();
            newPaths.addAll(mOverlayPaths);
            if (mSharedLibraryOverlayPaths != null) {
                for (final OverlayPaths libOverlayPaths : mSharedLibraryOverlayPaths.values()) {
                    newPaths.addAll(libOverlayPaths);
                }
            }
            return newPaths.build();
        }



        // Code below generated by codegen v1.0.23.
        //
        // DO NOT MODIFY!
        // CHECKSTYLE:OFF Generated code
        //
        // To regenerate run:
        // $ codegen $ANDROID_BUILD_TOP/frameworks/base/services/core/java/com/android/server/pm/pkg/PackageStateImpl.java
        //
        // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
        //   Settings > Editor > Code Style > Formatter Control
        //@formatter:off


        @DataClass.Generated.Member
        public int getBooleans() {
            return mBooleans;
        }

        @DataClass.Generated.Member
        public long getCeDataInode() {
            return mCeDataInode;
        }

        @DataClass.Generated.Member
        public @NonNull
        ArraySet<String> getDisabledComponents() {
            return mDisabledComponents;
        }

        @DataClass.Generated.Member
        public @PackageManager.DistractionRestriction int getDistractionFlags() {
            return mDistractionFlags;
        }

        @DataClass.Generated.Member
        public @NonNull ArraySet<String> getEnabledComponents() {
            return mEnabledComponents;
        }

        @DataClass.Generated.Member
        public int getEnabledState() {
            return mEnabledState;
        }

        @DataClass.Generated.Member
        public @Nullable String getHarmfulAppWarning() {
            return mHarmfulAppWarning;
        }

        @DataClass.Generated.Member
        public @PackageManager.InstallReason int getInstallReason() {
            return mInstallReason;
        }

        @DataClass.Generated.Member
        public @Nullable String getLastDisableAppCaller() {
            return mLastDisableAppCaller;
        }

        @DataClass.Generated.Member
        public @NonNull OverlayPaths getOverlayPaths() {
            return mOverlayPaths;
        }

        @DataClass.Generated.Member
        public @NonNull Map<String,OverlayPaths> getSharedLibraryOverlayPaths() {
            return mSharedLibraryOverlayPaths;
        }

        @DataClass.Generated.Member
        public @PackageManager.UninstallReason int getUninstallReason() {
            return mUninstallReason;
        }

        @DataClass.Generated.Member
        public @Nullable String getSplashScreenTheme() {
            return mSplashScreenTheme;
        }

        @DataClass.Generated.Member
        public long getFirstInstallTime() {
            return mFirstInstallTime;
        }

        @DataClass.Generated.Member
        public @NonNull UserStateImpl setBooleans( int value) {
            mBooleans = value;
            return this;
        }

        @DataClass.Generated(
                time = 1644270981508L,
                codegenVersion = "1.0.23",
                sourceFile = "frameworks/base/services/core/java/com/android/server/pm/pkg/PackageStateImpl.java",
                inputSignatures = "private  int mBooleans\nprivate final  long mCeDataInode\nprivate final @android.annotation.NonNull java.util.Set<java.lang.String> mDisabledComponents\nprivate final @android.content.pm.PackageManager.DistractionRestriction int mDistractionFlags\nprivate final @android.annotation.NonNull java.util.Set<java.lang.String> mEnabledComponents\nprivate final  int mEnabledState\nprivate final @android.annotation.Nullable java.lang.String mHarmfulAppWarning\nprivate final @android.content.pm.PackageManager.InstallReason int mInstallReason\nprivate final @android.annotation.Nullable java.lang.String mLastDisableAppCaller\nprivate final @android.annotation.NonNull android.content.pm.overlay.OverlayPaths mOverlayPaths\nprivate final @android.annotation.NonNull java.util.Map<java.lang.String,android.content.pm.overlay.OverlayPaths> mSharedLibraryOverlayPaths\nprivate final @android.content.pm.PackageManager.UninstallReason int mUninstallReason\nprivate final @android.annotation.Nullable java.lang.String mSplashScreenTheme\nprivate final  long mFirstInstallTime\npublic static  com.android.server.pm.pkg.PackageUserState copy(com.android.server.pm.pkg.PackageUserState)\nprivate  void setBoolean(int,boolean)\nprivate  boolean getBoolean(int)\npublic @java.lang.Override boolean isHidden()\npublic @java.lang.Override boolean isInstalled()\npublic @java.lang.Override boolean isInstantApp()\npublic @java.lang.Override boolean isNotLaunched()\npublic @java.lang.Override boolean isStopped()\npublic @java.lang.Override boolean isSuspended()\npublic @java.lang.Override boolean isVirtualPreload()\npublic @java.lang.Override boolean isComponentEnabled(java.lang.String)\npublic @java.lang.Override boolean isComponentDisabled(java.lang.String)\npublic @java.lang.Override android.content.pm.overlay.OverlayPaths getAllOverlayPaths()\nclass UserStateImpl extends java.lang.Object implements [com.android.server.pm.pkg.PackageUserState]\nprivate static final  int HIDDEN\nprivate static final  int INSTALLED\nprivate static final  int INSTANT_APP\nprivate static final  int NOT_LAUNCHED\nprivate static final  int STOPPED\nprivate static final  int SUSPENDED\nprivate static final  int VIRTUAL_PRELOAD\nclass Booleans extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genConstructor=false)")
        @Deprecated
        private void __metadata() {}


        //@formatter:on
        // End of generated code

    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/services/core/java/com/android/server/pm/pkg/PackageStateImpl.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public int getBooleans() {
        return mBooleans;
    }

    @DataClass.Generated.Member
    public @Nullable AndroidPackageApi getAndroidPackage() {
        return mAndroidPackage;
    }

    @DataClass.Generated.Member
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    @DataClass.Generated.Member
    public @Nullable String getVolumeUuid() {
        return mVolumeUuid;
    }

    @DataClass.Generated.Member
    public int getAppId() {
        return mAppId;
    }

    @DataClass.Generated.Member
    public int getCategoryOverride() {
        return mCategoryOverride;
    }

    @DataClass.Generated.Member
    public @Nullable String getCpuAbiOverride() {
        return mCpuAbiOverride;
    }

    @DataClass.Generated.Member
    public long getLastModifiedTime() {
        return mLastModifiedTime;
    }

    @DataClass.Generated.Member
    public long getLastUpdateTime() {
        return mLastUpdateTime;
    }

    @DataClass.Generated.Member
    public long getLongVersionCode() {
        return mLongVersionCode;
    }

    @DataClass.Generated.Member
    public @NonNull Map<String,Set<String>> getMimeGroups() {
        return mMimeGroups;
    }

    @DataClass.Generated.Member
    public @NonNull File getPath() {
        return mPath;
    }

    @DataClass.Generated.Member
    public @Nullable String getPrimaryCpuAbi() {
        return mPrimaryCpuAbi;
    }

    @DataClass.Generated.Member
    public @Nullable String getSecondaryCpuAbi() {
        return mSecondaryCpuAbi;
    }

    @DataClass.Generated.Member
    public boolean isHasSharedUser() {
        return mHasSharedUser;
    }

    @DataClass.Generated.Member
    public @NonNull String[] getUsesSdkLibraries() {
        return mUsesSdkLibraries;
    }

    @DataClass.Generated.Member
    public @NonNull long[] getUsesSdkLibrariesVersionsMajor() {
        return mUsesSdkLibrariesVersionsMajor;
    }

    @DataClass.Generated.Member
    public @NonNull String[] getUsesStaticLibraries() {
        return mUsesStaticLibraries;
    }

    @DataClass.Generated.Member
    public @NonNull long[] getUsesStaticLibrariesVersions() {
        return mUsesStaticLibrariesVersions;
    }

    @DataClass.Generated.Member
    public @NonNull List<SharedLibraryInfo> getUsesLibraryInfos() {
        return mUsesLibraryInfos;
    }

    @DataClass.Generated.Member
    public @NonNull List<String> getUsesLibraryFiles() {
        return mUsesLibraryFiles;
    }

    @DataClass.Generated.Member
    public @NonNull long[] getLastPackageUsageTime() {
        return mLastPackageUsageTime;
    }

    @DataClass.Generated.Member
    public @NonNull SigningInfo getSigningInfo() {
        return mSigningInfo;
    }

    @DataClass.Generated.Member
    public @NonNull SparseArray<PackageUserState> getUserStates() {
        return mUserStates;
    }

    @DataClass.Generated.Member
    public @NonNull PackageStateImpl setBooleans( int value) {
        mBooleans = value;
        return this;
    }

    @DataClass.Generated(
            time = 1644270981543L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/services/core/java/com/android/server/pm/pkg/PackageStateImpl.java",
            inputSignatures = "private  int mBooleans\nprivate final @android.annotation.Nullable com.android.server.pm.pkg.AndroidPackageApi mAndroidPackage\nprivate final @android.annotation.NonNull java.lang.String mPackageName\nprivate final @android.annotation.Nullable java.lang.String mVolumeUuid\nprivate final  int mAppId\nprivate final  int mCategoryOverride\nprivate final @android.annotation.Nullable java.lang.String mCpuAbiOverride\nprivate final  long mLastModifiedTime\nprivate final  long mLastUpdateTime\nprivate final  long mLongVersionCode\nprivate final @android.annotation.NonNull java.util.Map<java.lang.String,java.util.Set<java.lang.String>> mMimeGroups\nprivate final @android.annotation.NonNull java.io.File mPath\nprivate final @android.annotation.Nullable java.lang.String mPrimaryCpuAbi\nprivate final @android.annotation.Nullable java.lang.String mSecondaryCpuAbi\nprivate final  boolean mHasSharedUser\nprivate final  int mSharedUserAppId\nprivate final @android.annotation.NonNull java.lang.String[] mUsesSdkLibraries\nprivate final @android.annotation.NonNull long[] mUsesSdkLibrariesVersionsMajor\nprivate final @android.annotation.NonNull java.lang.String[] mUsesStaticLibraries\nprivate final @android.annotation.NonNull long[] mUsesStaticLibrariesVersions\nprivate final @android.annotation.NonNull java.util.List<android.content.pm.SharedLibraryInfo> mUsesLibraryInfos\nprivate final @android.annotation.NonNull java.util.List<java.lang.String> mUsesLibraryFiles\nprivate final @android.annotation.NonNull long[] mLastPackageUsageTime\nprivate final @android.annotation.NonNull android.content.pm.SigningInfo mSigningInfo\nprivate final @android.annotation.NonNull android.util.SparseArray<com.android.server.pm.pkg.PackageUserState> mUserStates\npublic static  com.android.server.pm.pkg.PackageState copy(com.android.server.pm.pkg.PackageStateInternal)\nprivate  void setBoolean(int,boolean)\nprivate  boolean getBoolean(int)\npublic @java.lang.Override boolean isExternalStorage()\npublic @java.lang.Override boolean isForceQueryableOverride()\npublic @java.lang.Override boolean isHiddenUntilInstalled()\npublic @java.lang.Override boolean isInstallPermissionsFixed()\npublic @java.lang.Override boolean isOdm()\npublic @java.lang.Override boolean isOem()\npublic @java.lang.Override boolean isPrivileged()\npublic @java.lang.Override boolean isProduct()\npublic @java.lang.Override boolean isRequiredForSystemUser()\npublic @java.lang.Override boolean isSystem()\npublic @java.lang.Override boolean isSystemExt()\npublic @java.lang.Override boolean isUpdateAvailable()\npublic @java.lang.Override boolean isUpdatedSystemApp()\npublic @java.lang.Override boolean isVendor()\npublic @java.lang.Override long getVersionCode()\npublic @java.lang.Override boolean hasSharedUser()\npublic @java.lang.Override int getSharedUserAppId()\nclass PackageStateImpl extends java.lang.Object implements [com.android.server.pm.pkg.PackageState]\nprivate static final  int SYSTEM\nprivate static final  int EXTERNAL_STORAGE\nprivate static final  int PRIVILEGED\nprivate static final  int OEM\nprivate static final  int VENDOR\nprivate static final  int PRODUCT\nprivate static final  int SYSTEM_EXT\nprivate static final  int REQUIRED_FOR_SYSTEM_USER\nprivate static final  int ODM\nprivate static final  int FORCE_QUERYABLE_OVERRIDE\nprivate static final  int HIDDEN_UNTIL_INSTALLED\nprivate static final  int INSTALL_PERMISSIONS_FIXED\nprivate static final  int UPDATE_AVAILABLE\nprivate static final  int UPDATED_SYSTEM_APP\nclass Booleans extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genConstructor=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
