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

import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.os.RemoteException;

/**
 * @hide
 */
@SystemApi
public final class WebViewUpdateService {

    @UnsupportedAppUsage
    private WebViewUpdateService () {}

    /**
     * Fetch all packages that could potentially implement WebView.
     */
    public static WebViewProviderInfo[] getAllWebViewPackages() {
        if (Flags.updateServiceIpcWrapper()) {
            if (WebViewFactory.isWebViewSupported()) {
                return WebViewUpdateManager.getInstance().getAllWebViewPackages();
            } else {
                return new WebViewProviderInfo[0];
            }
        } else {
            IWebViewUpdateService service = getUpdateService();
            if (service == null) {
                return new WebViewProviderInfo[0];
            }
            try {
                return service.getAllWebViewPackages();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Fetch all packages that could potentially implement WebView and are currently valid.
     *
     * <p>Note that this will be filtered by the caller's package visibility; callers should
     * have QUERY_ALL_PACKAGES permission to ensure that the list is complete.
     */
    @RequiresPermission(android.Manifest.permission.INTERACT_ACROSS_USERS)
    public static WebViewProviderInfo[] getValidWebViewPackages() {
        if (Flags.updateServiceIpcWrapper()) {
            if (WebViewFactory.isWebViewSupported()) {
                return WebViewUpdateManager.getInstance().getValidWebViewPackages();
            } else {
                return new WebViewProviderInfo[0];
            }
        } else {
            IWebViewUpdateService service = getUpdateService();
            if (service == null) {
                return new WebViewProviderInfo[0];
            }
            try {
                return service.getValidWebViewPackages();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Used by DevelopmentSetting to get the name of the WebView provider currently in use.
     */
    public static String getCurrentWebViewPackageName() {
        if (Flags.updateServiceIpcWrapper()) {
            if (WebViewFactory.isWebViewSupported()) {
                return WebViewUpdateManager.getInstance().getCurrentWebViewPackageName();
            } else {
                return null;
            }
        } else {
            IWebViewUpdateService service = getUpdateService();
            if (service == null) {
                return null;
            }
            try {
                return service.getCurrentWebViewPackageName();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private static IWebViewUpdateService getUpdateService() {
        return WebViewFactory.getUpdateService();
    }
}
