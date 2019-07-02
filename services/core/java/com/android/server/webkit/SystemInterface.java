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
import android.webkit.UserPackage;
import android.webkit.WebViewProviderInfo;

import java.util.List;

/**
 * System interface for the WebViewUpdateService.
 * This interface provides a way to test the WebView preparation mechanism - during normal use this
 * interface is implemented using calls to the Android framework, but by providing an alternative
 * implementation we can test the WebView preparation logic without reaching other framework code.
 *
 * @hide
 */
public interface SystemInterface {
    public WebViewProviderInfo[] getWebViewPackages();
    public int onWebViewProviderChanged(PackageInfo packageInfo);
    public long getFactoryPackageVersion(String packageName) throws NameNotFoundException;

    public String getUserChosenWebViewProvider(Context context);
    public void updateUserSetting(Context context, String newProviderName);
    public void killPackageDependents(String packageName);

    public boolean isFallbackLogicEnabled();
    public void enableFallbackLogic(boolean enable);

    public void enablePackageForAllUsers(Context context, String packageName, boolean enable);

    public boolean systemIsDebuggable();
    public PackageInfo getPackageInfoForProvider(WebViewProviderInfo configInfo)
            throws NameNotFoundException;
    /**
     * Get the PackageInfos of all users for the package represented by {@param configInfo}.
     * @return an array of UserPackages for a certain package, each UserPackage being belonging to a
     *         certain user. The returned array can contain null PackageInfos if the given package
     *         is uninstalled for some user.
     */
    public List<UserPackage> getPackageInfoForProviderAllUsers(Context context,
            WebViewProviderInfo configInfo);

    public int getMultiProcessSetting(Context context);
    public void setMultiProcessSetting(Context context, int value);
    public void notifyZygote(boolean enableMultiProcess);
    /** Start the zygote if it's not already running. */
    public void ensureZygoteStarted();
    public boolean isMultiProcessDefaultEnabled();
}
