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
     * DevelopmentSettings uses this to notify WebViewUpdateService that a
     * new provider has been selected by the user.
     */
    void changeProviderAndSetting(String newProvider);

    /**
     * DevelopmentSettings uses this to get the current available WebView
     * providers (to display as choices to the user).
     */
    WebViewProviderInfo[] getValidWebViewPackages();

    /**
     * Used by DevelopmentSetting to get the name of the WebView provider currently in use.
     */
    String getCurrentWebViewPackageName();
}
