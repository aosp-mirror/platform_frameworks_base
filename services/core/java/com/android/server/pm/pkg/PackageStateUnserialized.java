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

import static java.util.Collections.emptyList;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.PackageManager;
import android.content.pm.SharedLibraryInfo;
import android.text.TextUtils;

import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.server.pm.PackageSetting;

import java.util.ArrayList;
import java.util.List;

/**
 * For use by {@link PackageSetting} to maintain functionality that used to exist in PackageParser.
 *
 * It is assumed that anything inside the package was not cached or written to disk, so none of
 * these fields are either. They must be set on every boot from other state on the device.
 *
 * These fields are also not copied into any cloned PackageSetting, to preserve the old behavior
 * where they would be lost implicitly by re-generating the package object.
 * @hide
 */
@DataClass(genSetters = true, genConstructor = false, genBuilder = false)
@DataClass.Suppress({"setLastPackageUsageTimeInMills", "setPackageSetting", "setUsesLibraryInfos"})
public class PackageStateUnserialized {

    private boolean hiddenUntilInstalled;

    @NonNull
    private List<SharedLibraryWrapper> usesLibraryInfos = emptyList();

    @NonNull
    private List<String> usesLibraryFiles = emptyList();

    private boolean updatedSystemApp;
    private boolean apkInUpdatedApex;

    @NonNull
    private volatile long[] lastPackageUsageTimeInMills;

    @Nullable
    private String overrideSeInfo;

    @NonNull
    private String seInfo;

    // TODO: Remove in favor of finer grained change notification
    @NonNull
    private final PackageSetting mPackageSetting;

    @Nullable
    private String mApexModuleName;

    public PackageStateUnserialized(@NonNull PackageSetting packageSetting) {
        mPackageSetting = packageSetting;
    }

    @NonNull
    public PackageStateUnserialized addUsesLibraryInfo(@NonNull SharedLibraryWrapper value) {
        usesLibraryInfos = CollectionUtils.add(usesLibraryInfos, value);
        return this;
    }

    @NonNull
    public PackageStateUnserialized addUsesLibraryFile(@NonNull String value) {
        usesLibraryFiles = CollectionUtils.add(usesLibraryFiles, value);
        return this;
    }

    private long[] lazyInitLastPackageUsageTimeInMills() {
        return new long[PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT];
    }

    public PackageStateUnserialized setLastPackageUsageTimeInMills(int reason, long time) {
        if (reason < 0) {
            return this;
        }
        if (reason >= PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT) {
            return this;
        }
        getLastPackageUsageTimeInMills()[reason] = time;
        // TODO(b/236180425): This method does not notify snapshot changes because it's called too
        //  frequently, causing too many re-takes. This should be moved to a separate data structure
        //  or merged with the general UsageStats to avoid tracking heavily mutated data in the
        //  package data snapshot.
        return this;
    }

    public long getLatestPackageUseTimeInMills() {
        long latestUse = 0L;
        for (long use : getLastPackageUsageTimeInMills()) {
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
            latestUse = Math.max(latestUse, getLastPackageUsageTimeInMills()[reason]);
        }
        return latestUse;
    }

    public void updateFrom(PackageStateUnserialized other) {
        this.hiddenUntilInstalled = other.hiddenUntilInstalled;

        if (!other.usesLibraryInfos.isEmpty()) {
            this.usesLibraryInfos = new ArrayList<>(other.usesLibraryInfos);
        }

        if (!other.usesLibraryFiles.isEmpty()) {
            this.usesLibraryFiles = new ArrayList<>(other.usesLibraryFiles);
        }

        this.updatedSystemApp = other.updatedSystemApp;
        this.apkInUpdatedApex = other.apkInUpdatedApex;
        this.lastPackageUsageTimeInMills = other.lastPackageUsageTimeInMills;
        this.overrideSeInfo = other.overrideSeInfo;
        this.seInfo = other.seInfo;
        this.mApexModuleName = other.mApexModuleName;
        mPackageSetting.onChanged();
    }

    public @NonNull List<SharedLibraryInfo> getNonNativeUsesLibraryInfos() {
        var list = new ArrayList<SharedLibraryInfo>();
        usesLibraryInfos = getUsesLibraryInfos();
        for (int index = 0; index < usesLibraryInfos.size(); index++) {
            var library = usesLibraryInfos.get(index);
            if (!library.isNative()) {
                list.add(library.getInfo());
            }

        }
        return list;
    }

    public PackageStateUnserialized setHiddenUntilInstalled(boolean value) {
        hiddenUntilInstalled = value;
        mPackageSetting.onChanged();
        return this;
    }

    public PackageStateUnserialized setUsesLibraryInfos(@NonNull List<SharedLibraryInfo> value) {
        var list = new ArrayList<SharedLibraryWrapper>();
        for (int index = 0; index < value.size(); index++) {
            list.add(new SharedLibraryWrapper(value.get(index)));
        }
        usesLibraryInfos = list;
        mPackageSetting.onChanged();
        return this;
    }

    public PackageStateUnserialized setUsesLibraryFiles(@NonNull List<String> value) {
        usesLibraryFiles = value;
        mPackageSetting.onChanged();
        return this;
    }

    public PackageStateUnserialized setUpdatedSystemApp(boolean value) {
        updatedSystemApp = value;
        mPackageSetting.onChanged();
        return this;
    }

    public PackageStateUnserialized setApkInUpdatedApex(boolean value) {
        apkInUpdatedApex = value;
        mPackageSetting.onChanged();
        return this;
    }

    public PackageStateUnserialized setLastPackageUsageTimeInMills(@NonNull long... value) {
        lastPackageUsageTimeInMills = value;
        mPackageSetting.onChanged();
        return this;
    }

    public PackageStateUnserialized setOverrideSeInfo(@Nullable String value) {
        overrideSeInfo = value;
        mPackageSetting.onChanged();
        return this;
    }

    @NonNull
    public PackageStateUnserialized setSeInfo(@NonNull String value) {
        seInfo = TextUtils.safeIntern(value);
        mPackageSetting.onChanged();
        return this;
    }

    @NonNull
    public PackageStateUnserialized setApexModuleName(@NonNull String value) {
        mApexModuleName = value;
        mPackageSetting.onChanged();
        return this;
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/services/core/java/com/android/server/pm/pkg/PackageStateUnserialized.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    @DataClass.Generated.Member
    public boolean isHiddenUntilInstalled() {
        return hiddenUntilInstalled;
    }

    @DataClass.Generated.Member
    public @NonNull List<SharedLibraryWrapper> getUsesLibraryInfos() {
        return usesLibraryInfos;
    }

    @DataClass.Generated.Member
    public @NonNull List<String> getUsesLibraryFiles() {
        return usesLibraryFiles;
    }

    @DataClass.Generated.Member
    public boolean isUpdatedSystemApp() {
        return updatedSystemApp;
    }

    @DataClass.Generated.Member
    public boolean isApkInUpdatedApex() {
        return apkInUpdatedApex;
    }

    @DataClass.Generated.Member
    public @NonNull long[] getLastPackageUsageTimeInMills() {
        long[] _lastPackageUsageTimeInMills = lastPackageUsageTimeInMills;
        if (_lastPackageUsageTimeInMills == null) {
            synchronized(this) {
                _lastPackageUsageTimeInMills = lastPackageUsageTimeInMills;
                if (_lastPackageUsageTimeInMills == null) {
                    _lastPackageUsageTimeInMills = lastPackageUsageTimeInMills = lazyInitLastPackageUsageTimeInMills();
                }
            }
        }
        return _lastPackageUsageTimeInMills;
    }

    @DataClass.Generated.Member
    public @Nullable String getOverrideSeInfo() {
        return overrideSeInfo;
    }

    @DataClass.Generated.Member
    public @NonNull String getSeInfo() {
        return seInfo;
    }

    @DataClass.Generated.Member
    public @NonNull PackageSetting getPackageSetting() {
        return mPackageSetting;
    }

    @DataClass.Generated.Member
    public @Nullable String getApexModuleName() {
        return mApexModuleName;
    }

    @DataClass.Generated(
            time = 1671483772254L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/services/core/java/com/android/server/pm/pkg/PackageStateUnserialized.java",
            inputSignatures = "private  boolean hiddenUntilInstalled\nprivate @android.annotation.NonNull java.util.List<com.android.server.pm.pkg.SharedLibraryWrapper> usesLibraryInfos\nprivate @android.annotation.NonNull java.util.List<java.lang.String> usesLibraryFiles\nprivate  boolean updatedSystemApp\nprivate  boolean apkInUpdatedApex\nprivate volatile @android.annotation.NonNull long[] lastPackageUsageTimeInMills\nprivate @android.annotation.Nullable java.lang.String overrideSeInfo\nprivate @android.annotation.NonNull java.lang.String seInfo\nprivate final @android.annotation.NonNull com.android.server.pm.PackageSetting mPackageSetting\nprivate @android.annotation.Nullable java.lang.String mApexModuleName\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageStateUnserialized addUsesLibraryInfo(com.android.server.pm.pkg.SharedLibraryWrapper)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageStateUnserialized addUsesLibraryFile(java.lang.String)\nprivate  long[] lazyInitLastPackageUsageTimeInMills()\npublic  com.android.server.pm.pkg.PackageStateUnserialized setLastPackageUsageTimeInMills(int,long)\npublic  long getLatestPackageUseTimeInMills()\npublic  long getLatestForegroundPackageUseTimeInMills()\npublic  void updateFrom(com.android.server.pm.pkg.PackageStateUnserialized)\npublic @android.annotation.NonNull java.util.List<android.content.pm.SharedLibraryInfo> getNonNativeUsesLibraryInfos()\npublic  com.android.server.pm.pkg.PackageStateUnserialized setHiddenUntilInstalled(boolean)\npublic  com.android.server.pm.pkg.PackageStateUnserialized setUsesLibraryInfos(java.util.List<android.content.pm.SharedLibraryInfo>)\npublic  com.android.server.pm.pkg.PackageStateUnserialized setUsesLibraryFiles(java.util.List<java.lang.String>)\npublic  com.android.server.pm.pkg.PackageStateUnserialized setUpdatedSystemApp(boolean)\npublic  com.android.server.pm.pkg.PackageStateUnserialized setApkInUpdatedApex(boolean)\npublic  com.android.server.pm.pkg.PackageStateUnserialized setLastPackageUsageTimeInMills(long)\npublic  com.android.server.pm.pkg.PackageStateUnserialized setOverrideSeInfo(java.lang.String)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageStateUnserialized setSeInfo(java.lang.String)\npublic @android.annotation.NonNull com.android.server.pm.pkg.PackageStateUnserialized setApexModuleName(java.lang.String)\nclass PackageStateUnserialized extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genSetters=true, genConstructor=false, genBuilder=false)")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
