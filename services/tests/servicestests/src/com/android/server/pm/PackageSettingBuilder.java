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

package com.android.server.pm;

import android.content.pm.SigningDetails;
import android.util.SparseArray;

import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageUserStateImpl;

import java.io.File;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class PackageSettingBuilder {
    private String mName;
    private String mRealName;
    private String mCodePath;
    private String mLegacyNativeLibraryPathString;
    private String mPrimaryCpuAbiString;
    private String mSecondaryCpuAbiString;
    private String mCpuAbiOverrideString;
    private long mPVersionCode;
    private int mPkgFlags;
    private int mPrivateFlags;
    private int mSharedUserId;
    private String mVolumeUuid;
    private int mAppId;
    private SparseArray<PackageUserStateImpl> mUserStates = new SparseArray<>();
    private AndroidPackage mPkg;
    private InstallSource mInstallSource;
    private Map<String, Set<String>> mMimeGroups;
    private SigningDetails mSigningDetails;
    private UUID mDomainSetId = UUID.randomUUID();

    public PackageSettingBuilder setPackage(AndroidPackage pkg) {
        this.mPkg = pkg;
        return this;
    }

    public PackageSettingBuilder setName(String name) {
        this.mName = name;
        return this;
    }

    public PackageSettingBuilder setAppId(int appId) {
        this.mAppId = appId;
        return this;
    }

    public PackageSettingBuilder setRealName(String realName) {
        this.mRealName = realName;
        return this;
    }

    public PackageSettingBuilder setCodePath(String codePath) {
        this.mCodePath = codePath;
        return this;
    }

    public PackageSettingBuilder setLegacyNativeLibraryPathString(
            String legacyNativeLibraryPathString) {
        this.mLegacyNativeLibraryPathString = legacyNativeLibraryPathString;
        return this;
    }

    public PackageSettingBuilder setPrimaryCpuAbiString(String primaryCpuAbiString) {
        this.mPrimaryCpuAbiString = primaryCpuAbiString;
        return this;
    }

    public PackageSettingBuilder setSecondaryCpuAbiString(String secondaryCpuAbiString) {
        this.mSecondaryCpuAbiString = secondaryCpuAbiString;
        return this;
    }

    public PackageSettingBuilder setCpuAbiOverrideString(String cpuAbiOverrideString) {
        this.mCpuAbiOverrideString = cpuAbiOverrideString;
        return this;
    }

    public PackageSettingBuilder setPVersionCode(long pVersionCode) {
        this.mPVersionCode = pVersionCode;
        return this;
    }

    public PackageSettingBuilder setPkgFlags(int pkgFlags) {
        this.mPkgFlags = pkgFlags;
        return this;
    }

    public PackageSettingBuilder setPrivateFlags(int privateFlags) {
        this.mPrivateFlags = privateFlags;
        return this;
    }

    public PackageSettingBuilder setSharedUserId(int sharedUserId) {
        this.mSharedUserId = sharedUserId;
        return this;
    }

    public PackageSettingBuilder setMimeGroups(Map<String, Set<String>> mimeGroups) {
        this.mMimeGroups = mimeGroups;
        return this;
    }

    public PackageSettingBuilder setVolumeUuid(String volumeUuid) {
        this.mVolumeUuid = volumeUuid;
        return this;
    }

    public PackageSettingBuilder setInstantAppUserState(int userId, boolean isInstant) {
        if (mUserStates.indexOfKey(userId) < 0) {
            mUserStates.put(userId, new PackageUserStateImpl());
        }
        mUserStates.get(userId).setInstantApp(isInstant);
        return this;
    }

    public PackageSettingBuilder setInstallState(int userId, boolean installed) {
        if (mUserStates.indexOfKey(userId) < 0) {
            mUserStates.put(userId, new PackageUserStateImpl());
        }
        mUserStates.get(userId).setInstalled(installed);
        return this;
    }

    public PackageSettingBuilder setInstallSource(InstallSource installSource) {
        mInstallSource = installSource;
        return this;
    }

    public PackageSettingBuilder setSigningDetails(
            SigningDetails signingDetails) {
        mSigningDetails = signingDetails;
        return this;
    }

    public PackageSettingBuilder setDomainSetId(UUID domainSetId) {
        mDomainSetId = domainSetId;
        return this;
    }

    public PackageSetting build() {
        final PackageSetting packageSetting = new PackageSetting(mName, mRealName,
                new File(mCodePath), mPkgFlags, mPrivateFlags, mDomainSetId)
                .setLegacyNativeLibraryPath(mLegacyNativeLibraryPathString)
                .setPrimaryCpuAbi(mPrimaryCpuAbiString)
                .setSecondaryCpuAbi(mSecondaryCpuAbiString)
                .setCpuAbiOverride(mCpuAbiOverrideString)
                .setLongVersionCode(mPVersionCode)
                .setSharedUserAppId(mSharedUserId)
                .setMimeGroups(mMimeGroups)
                .setSignatures(mSigningDetails != null
                        ? new PackageSignatures(mSigningDetails) : new PackageSignatures())
                .setPkg(mPkg)
                .setAppId(mAppId)
                .setVolumeUuid(this.mVolumeUuid);
        if (mInstallSource != null) {
            packageSetting.setInstallSource(mInstallSource);
        }
        for (int i = 0; i < mUserStates.size(); i++) {
            packageSetting.setUserState(mUserStates.keyAt(i), mUserStates.valueAt(i));
        }
        return packageSetting;
    }
}
