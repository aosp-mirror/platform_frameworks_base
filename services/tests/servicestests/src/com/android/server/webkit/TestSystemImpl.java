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
import android.content.pm.UserInfo;
import android.webkit.UserPackage;
import android.webkit.WebViewProviderInfo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TestSystemImpl implements SystemInterface {
    private String mUserProvider = null;
    private final WebViewProviderInfo[] mPackageConfigs;
    List<Integer> mUsers = new ArrayList<>();
    // Package -> [user, package]
    Map<String, Map<Integer, PackageInfo>> mPackages = new HashMap();
    private boolean mFallbackLogicEnabled;
    private final int mNumRelros;
    private final boolean mIsDebuggable;
    private int mMultiProcessSetting;
    private final boolean mMultiProcessDefault;

    public static final int PRIMARY_USER_ID = 0;

    public TestSystemImpl(WebViewProviderInfo[] packageConfigs, boolean fallbackLogicEnabled,
            int numRelros, boolean isDebuggable, boolean multiProcessDefault) {
        mPackageConfigs = packageConfigs;
        mFallbackLogicEnabled = fallbackLogicEnabled;
        mNumRelros = numRelros;
        mIsDebuggable = isDebuggable;
        mUsers.add(PRIMARY_USER_ID);
        mMultiProcessDefault = multiProcessDefault;
    }

    public void addUser(int userId) {
        mUsers.add(userId);
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
        for(int userId : mUsers) {
            enablePackageForUser(packageName, enable, userId);
        }
    }

    @Override
    public void enablePackageForUser(String packageName, boolean enable, int userId) {
        Map<Integer, PackageInfo> userPackages = mPackages.get(packageName);
        if (userPackages == null) {
            throw new IllegalArgumentException("There is no package called " + packageName);
        }
        PackageInfo packageInfo = userPackages.get(userId);
        packageInfo.applicationInfo.enabled = enable;
        setPackageInfoForUser(userId, packageInfo);
    }

    @Override
    public boolean systemIsDebuggable() { return mIsDebuggable; }

    @Override
    public PackageInfo getPackageInfoForProvider(WebViewProviderInfo info) throws
            NameNotFoundException {
        Map<Integer, PackageInfo> userPackages = mPackages.get(info.packageName);
        if (userPackages == null) throw new NameNotFoundException(info.packageName);
        PackageInfo ret = userPackages.get(PRIMARY_USER_ID);
        if (ret == null) throw new NameNotFoundException(info.packageName);
        return ret;
    }

    @Override
    public List<UserPackage> getPackageInfoForProviderAllUsers(
            Context context, WebViewProviderInfo info) {
        Map<Integer, PackageInfo> userPackages = mPackages.get(info.packageName);
        List<UserPackage> ret = new ArrayList();
        // Loop over defined users, and find the corresponding package for each user.
        for (int userId : mUsers) {
            ret.add(new UserPackage(createUserInfo(userId),
                    userPackages == null ? null : userPackages.get(userId)));
        }
        return ret;
    }

    private static UserInfo createUserInfo(int userId) {
        return new UserInfo(userId, "User nr. " + userId, 0 /* flags */);
    }

    /**
     * Set package for primary user.
     */
    public void setPackageInfo(PackageInfo pi) {
        setPackageInfoForUser(PRIMARY_USER_ID, pi);
    }

    public void setPackageInfoForUser(int userId, PackageInfo pi) {
        if (!mUsers.contains(userId)) {
            throw new IllegalArgumentException("User nr. " + userId + " doesn't exist");
        }
        if (!mPackages.containsKey(pi.packageName)) {
            mPackages.put(pi.packageName, new HashMap<Integer, PackageInfo>());
        }
        mPackages.get(pi.packageName).put(userId, pi);
    }

    /**
     * Removes the package {@param packageName} for the primary user.
     */
    public void removePackageInfo(String packageName) {
        mPackages.get(packageName).remove(PRIMARY_USER_ID);
    }

    @Override
    public long getFactoryPackageVersion(String packageName) throws NameNotFoundException {
        PackageInfo pi = null;
        Map<Integer, PackageInfo> userPackages = mPackages.get(packageName);
        if (userPackages == null) throw new NameNotFoundException();

        pi = userPackages.get(PRIMARY_USER_ID);
        if (pi != null && pi.applicationInfo.isSystemApp()) {
            return pi.applicationInfo.versionCode;
        }
        throw new NameNotFoundException();
    }

    @Override
    public int getMultiProcessSetting(Context context) {
        return mMultiProcessSetting;
    }

    @Override
    public void setMultiProcessSetting(Context context, int value) {
        mMultiProcessSetting = value;
    }

    @Override
    public void notifyZygote(boolean enableMultiProcess) {}

    @Override
    public boolean isMultiProcessDefaultEnabled() {
        return mMultiProcessDefault;
    }
}
