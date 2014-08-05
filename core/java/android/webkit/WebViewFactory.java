/*
 * Copyright (C) 2012 The Android Open Source Project
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

import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.StrictMode;
import android.os.SystemProperties;
import android.util.AndroidRuntimeException;
import android.util.Log;

/**
 * Top level factory, used creating all the main WebView implementation classes.
 *
 * @hide
 */
public final class WebViewFactory {

    private static final String CHROMIUM_WEBVIEW_FACTORY =
            "com.android.webview.chromium.WebViewChromiumFactoryProvider";

    private static final String LOGTAG = "WebViewFactory";

    private static final boolean DEBUG = false;

    // Cache the factory both for efficiency, and ensure any one process gets all webviews from the
    // same provider.
    private static WebViewFactoryProvider sProviderInstance;
    private static final Object sProviderLock = new Object();

    public static boolean isExperimentalWebViewAvailable() {
        // TODO: Remove callers of this method then remove it.
        return false;  // Hide the toggle in Developer Settings.
    }

    /** @hide */
    public static void setUseExperimentalWebView(boolean enable) {
        // TODO: Remove callers of this method then remove it.
    }

    /** @hide */
    public static boolean useExperimentalWebView() {
        // TODO: Remove callers of this method then remove it.
        return true;
    }

    /** @hide */
    public static boolean isUseExperimentalWebViewSet() {
        // TODO: Remove callers of this method then remove it.
        return false;  // User has not modifed Developer Settings
    }

    public static String getWebViewPackageName() {
        // TODO: Make this dynamic based on resource configuration.
        return "com.android.webview";
    }

    static WebViewFactoryProvider getProvider() {
        synchronized (sProviderLock) {
            // For now the main purpose of this function (and the factory abstraction) is to keep
            // us honest and minimize usage of WebView internals when binding the proxy.
            if (sProviderInstance != null) return sProviderInstance;

            Class<WebViewFactoryProvider> providerClass;
            try {
                providerClass = getFactoryClass();
            } catch (ClassNotFoundException e) {
                Log.e(LOGTAG, "error loading provider", e);
                throw new AndroidRuntimeException(e);
            }

            StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
            try {
                sProviderInstance = providerClass.newInstance();
                if (DEBUG) Log.v(LOGTAG, "Loaded provider: " + sProviderInstance);
                return sProviderInstance;
            } catch (Exception e) {
                Log.e(LOGTAG, "error instantiating provider", e);
                throw new AndroidRuntimeException(e);
            } finally {
                StrictMode.setThreadPolicy(oldPolicy);
            }
        }
    }

    private static Class<WebViewFactoryProvider> getFactoryClass() throws ClassNotFoundException {
        try {
            Context webViewContext = AppGlobals.getInitialApplication().createPackageContext(
                    getWebViewPackageName(),
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader clazzLoader = webViewContext.getClassLoader();
            return (Class<WebViewFactoryProvider>) Class.forName(CHROMIUM_WEBVIEW_FACTORY, true,
                                                                 clazzLoader);
        } catch (PackageManager.NameNotFoundException e) {
            throw new ClassNotFoundException("Chromium WebView package does not exist", e);
        }
    }
}
