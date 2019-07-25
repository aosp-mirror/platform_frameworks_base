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

import android.content.pm.PackageParser;
import android.os.UserHandle;

class ScanRequestBuilder {
    private final PackageParser.Package mPkg;
    private PackageParser.Package mOldPkg;
    private SharedUserSetting mSharedUserSetting;
    private PackageSetting mPkgSetting;
    private PackageSetting mDisabledPkgSetting;
    private PackageSetting mOriginalPkgSetting;
    private String mRealPkgName;
    private int mParseFlags;
    private int mScanFlags;
    private UserHandle mUser;
    private boolean mIsPlatformPackage;

    ScanRequestBuilder(PackageParser.Package pkg) {
        this.mPkg = pkg;
    }

    public ScanRequestBuilder setOldPkg(PackageParser.Package oldPkg) {
        this.mOldPkg = oldPkg;
        return this;
    }

    public ScanRequestBuilder setSharedUserSetting(SharedUserSetting sharedUserSetting) {
        this.mSharedUserSetting = sharedUserSetting;
        return this;
    }

    public ScanRequestBuilder setPkgSetting(PackageSetting pkgSetting) {
        this.mPkgSetting = pkgSetting;
        return this;
    }

    public ScanRequestBuilder setDisabledPkgSetting(PackageSetting disabledPkgSetting) {
        this.mDisabledPkgSetting = disabledPkgSetting;
        return this;
    }

    public ScanRequestBuilder setOriginalPkgSetting(PackageSetting originalPkgSetting) {
        this.mOriginalPkgSetting = originalPkgSetting;
        return this;
    }

    public ScanRequestBuilder setRealPkgName(String realPkgName) {
        this.mRealPkgName = realPkgName;
        return this;
    }

    public ScanRequestBuilder setParseFlags(int parseFlags) {
        this.mParseFlags = parseFlags;
        return this;
    }

    public ScanRequestBuilder addParseFlag(int parseFlag) {
        this.mParseFlags |= parseFlag;
        return this;
    }

    public ScanRequestBuilder setScanFlags(int scanFlags) {
        this.mScanFlags = scanFlags;
        return this;
    }

    public ScanRequestBuilder addScanFlag(int scanFlag) {
        this.mScanFlags |= scanFlag;
        return this;
    }

    public ScanRequestBuilder setUser(UserHandle user) {
        this.mUser = user;
        return this;
    }

    public ScanRequestBuilder setIsPlatformPackage(boolean isPlatformPackage) {
        this.mIsPlatformPackage = isPlatformPackage;
        return this;
    }

    PackageManagerService.ScanRequest build() {
        return new PackageManagerService.ScanRequest(
                mPkg, mSharedUserSetting, mOldPkg, mPkgSetting, mDisabledPkgSetting,
                mOriginalPkgSetting, mRealPkgName, mParseFlags, mScanFlags, mIsPlatformPackage,
                mUser);
    }
}
