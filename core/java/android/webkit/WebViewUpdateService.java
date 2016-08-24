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

package android.webkit;

import android.annotation.SystemApi;
import android.os.RemoteException;

/**
 * @hide
 */
@SystemApi
public final class WebViewUpdateService {

    private WebViewUpdateService () {}

    /**
     * Fetch all packages that could potentially implement WebView.
     */
    public static WebViewProviderInfo[] getAllWebViewPackages() {
        try {
            return getUpdateService().getAllWebViewPackages();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Fetch all packages that could potentially implement WebView and are currently valid.
     */
    public static WebViewProviderInfo[] getValidWebViewPackages() {
        try {
            return getUpdateService().getValidWebViewPackages();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Used by DevelopmentSetting to get the name of the WebView provider currently in use.
     */
    public static String getCurrentWebViewPackageName() {
        try {
            return getUpdateService().getCurrentWebViewPackageName();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }
    }

    private static IWebViewUpdateService getUpdateService() {
        return WebViewFactory.getUpdateService();
    }
}
