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

    private static class Preloader {
        static WebViewFactoryProvider sPreloadedProvider;
        static {
            try {
                sPreloadedProvider = getFactoryClass().newInstance();
            } catch (Exception e) {
                Log.w(LOGTAG, "error preloading provider", e);
            }
        }
    }

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

            // This implicitly loads Preloader even if it wasn't preloaded at boot.
            if (Preloader.sPreloadedProvider != null &&
                Preloader.sPreloadedProvider.getClass() == providerClass) {
                sProviderInstance = Preloader.sPreloadedProvider;
                if (DEBUG) Log.v(LOGTAG, "Using preloaded provider: " + sProviderInstance);
                return sProviderInstance;
            }

            // The preloaded provider isn't the one we wanted; construct our own.
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
        return (Class<WebViewFactoryProvider>) Class.forName(CHROMIUM_WEBVIEW_FACTORY);
    }
}
