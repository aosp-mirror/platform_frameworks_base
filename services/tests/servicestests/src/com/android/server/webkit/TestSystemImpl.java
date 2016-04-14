/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.webkit;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.webkit.WebViewProviderInfo;

import java.util.HashMap;

public class TestSystemImpl implements SystemInterface {
    private String mUserProvider = null;
    private final WebViewProviderInfo[] mPackageConfigs;
    HashMap<String, PackageInfo> mPackages = new HashMap();
    private boolean mFallbackLogicEnabled;
    private final int mNumRelros;
    private final boolean mIsDebuggable;

    public TestSystemImpl(WebViewProviderInfo[] packageConfigs, boolean fallbackLogicEnabled,
            int numRelros, boolean isDebuggable) {
        mPackageConfigs = packageConfigs;
        mFallbackLogicEnabled = fallbackLogicEnabled;
        mNumRelros = numRelros;
        mIsDebuggable = isDebuggable;
    }

    @Override
    public WebViewProviderInfo[] getWebViewPackages() {
        return mPackageConfigs;
    }

    @Override
    public int onWebViewProviderChanged(PackageInfo packageInfo) {
        return mNumRelros;
    }

    @Override
    public String getUserChosenWebViewProvider(Context context) { return mUserProvider; }

    @Override
    public void updateUserSetting(Context context, String newProviderName) {
        mUserProvider = newProviderName;
    }

    @Override
    public void killPackageDependents(String packageName) {}

    @Override
    public boolean isFallbackLogicEnabled() {
        return mFallbackLogicEnabled;
    }

    @Override
    public void enableFallbackLogic(boolean enable) {
        mFallbackLogicEnabled = enable;
    }

    @Override
    public void uninstallAndDisablePackageForAllUsers(Context context, String packageName) {
        enablePackageForAllUsers(context, packageName, false);
    }

    @Override
    public void enablePackageForAllUsers(Context context, String packageName, boolean enable) {
        enablePackageForUser(packageName, enable, 0);
    }

    @Override
    public void enablePackageForUser(String packageName, boolean enable, int userId) {
        PackageInfo packageInfo = mPackages.get(packageName);
        if (packageInfo == null) {
            throw new IllegalArgumentException("There is no package called " + packageName);
        }
        packageInfo.applicationInfo.enabled = enable;
        setPackageInfo(packageInfo);
    }

    @Override
    public boolean systemIsDebuggable() { return mIsDebuggable; }

    @Override
    public PackageInfo getPackageInfoForProvider(WebViewProviderInfo info) throws
            NameNotFoundException {
        PackageInfo ret = mPackages.get(info.packageName);
        if (ret == null) throw new NameNotFoundException(info.packageName);
        return ret;
    }

    public void setPackageInfo(PackageInfo pi) {
        mPackages.put(pi.packageName, pi);
    }

    public void removePackageInfo(String packageName) {
        mPackages.remove(packageName);
    }

    @Override
    public int getFactoryPackageVersion(String packageName) {
        return 0;
    }
}
