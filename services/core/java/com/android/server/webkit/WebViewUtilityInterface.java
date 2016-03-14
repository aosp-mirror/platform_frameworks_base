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

import android.webkit.WebViewProviderInfo;
import android.content.Context;
import android.content.pm.PackageInfo;

/**
 * Utility interface for the WebViewUpdateService.
 * This interface provides a way to test the WebView preparation mechanism - during normal use this
 * interface is implemented using calls to the Android framework, but by providing an alternative
 * implementation we can test the WebView preparation logic without reaching other framework code.
 * @hide
 */
public interface WebViewUtilityInterface {
    public WebViewProviderInfo[] getWebViewPackages();
    public int onWebViewProviderChanged(PackageInfo packageInfo);

    public String getUserChosenWebViewProvider(Context context);
    public void updateUserSetting(Context context, String newProviderName);
    public void killPackageDependents(String packageName);
}
