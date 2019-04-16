/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.webkit;

import android.content.pm.PackageInfo;
import android.webkit.WebViewProviderInfo;
import android.webkit.WebViewProviderResponse;

/**
 * Private service to wait for the updatable WebView to be ready for use.
 * @hide
 */
interface IWebViewUpdateService {

    /**
     * Used by the relro file creator to notify the service that it's done.
     */
    void notifyRelroCreationCompleted();

    /**
     * Used by WebViewFactory to block loading of WebView code until
     * preparations are complete. Returns the package used as WebView provider.
     */
    WebViewProviderResponse waitForAndGetProvider();

    /**
     * DevelopmentSettings uses this to notify WebViewUpdateService that a new provider has been
     * selected by the user. Returns the provider we end up switching to, this could be different to
     * the one passed as argument to this method since the Dev Setting calling this method could be
     * stale. I.e. the Dev setting could be letting the user choose uninstalled/disabled packages,
     * it would then try to update the provider to such a package while in reality the update
     * service would switch to another one.
     */
    String changeProviderAndSetting(String newProvider);

    /**
     * DevelopmentSettings uses this to get the current available WebView
     * providers (to display as choices to the user).
     */
    WebViewProviderInfo[] getValidWebViewPackages();

    /**
     * Fetch all packages that could potentially implement WebView.
     */
    WebViewProviderInfo[] getAllWebViewPackages();

    /**
     * Used by DevelopmentSetting to get the name of the WebView provider currently in use.
     */
    @UnsupportedAppUsage
    String getCurrentWebViewPackageName();

    /**
     * Used by public API for debugging purposes.
     */
    PackageInfo getCurrentWebViewPackage();

    /**
     * Used by Settings to determine whether a certain package can be enabled/disabled by the user -
     * the package should not be modifiable in this way if it is a fallback package.
     */
    @UnsupportedAppUsage
    boolean isFallbackPackage(String packageName);

    /**
     * Enable or disable the WebView package fallback mechanism.
     */
    void enableFallbackLogic(boolean enable);

    /**
     * Used by Settings to determine whether multiprocess is enabled.
     */
    boolean isMultiProcessEnabled();

    /**
     * Used by Settings to enable/disable multiprocess.
     */
    void enableMultiProcess(boolean enable);
}
