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
import android.util.Log;

import dalvik.system.PathClassLoader;

/**
 * Top level factory, used creating all the main WebView implementation classes.
 */
class WebViewFactory {
    // Default Provider factory class name.
    // TODO: When the Chromium powered WebView is ready, it should be the default factory class.
    private static final String DEFAULT_WEBVIEW_FACTORY = "android.webkit.WebViewClassic$Factory";
    private static final String CHROMIUM_WEBVIEW_FACTORY =
            "com.android.webviewchromium.WebViewChromiumFactoryProvider";
    private static final String CHROMIUM_WEBVIEW_JAR = "/system/framework/webviewchromium.jar";

    private static final String LOGTAG = "WebViewFactory";

    private static final boolean DEBUG = false;

    // Cache the factory both for efficiency, and ensure any one process gets all webviews from the
    // same provider.
    private static WebViewFactoryProvider sProviderInstance;
    private static final Object sProviderLock = new Object();

    static WebViewFactoryProvider getProvider() {
        synchronized (sProviderLock) {
            // For now the main purpose of this function (and the factory abstraction) is to keep
            // us honest and minimize usage of WebViewClassic internals when binding the proxy.
            if (sProviderInstance != null) return sProviderInstance;

            // For debug builds, we allow a system property to specify that we should use the
            // Chromium powered WebView. This enables us to switch between implementations
            // at runtime. For user (release) builds, don't allow this.
            if (Build.IS_DEBUGGABLE && SystemProperties.getBoolean("webview.use_chromium", false)) {
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
                try {
                    sProviderInstance = loadChromiumProvider();
                    if (DEBUG) Log.v(LOGTAG, "Loaded Chromium provider: " + sProviderInstance);
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            }

            if (sProviderInstance == null) {
                if (DEBUG) Log.v(LOGTAG, "Falling back to default provider: "
                        + DEFAULT_WEBVIEW_FACTORY);
                sProviderInstance = getFactoryByName(DEFAULT_WEBVIEW_FACTORY,
                        WebViewFactory.class.getClassLoader());
                if (sProviderInstance == null) {
                    if (DEBUG) Log.v(LOGTAG, "Falling back to explicit linkage");
                    sProviderInstance = new WebViewClassic.Factory();
                }
            }
            return sProviderInstance;
        }
    }

    // TODO: This allows us to have the legacy and Chromium WebView coexist for development
    // and side-by-side testing. After transition, remove this when no longer required.
    private static WebViewFactoryProvider loadChromiumProvider() {
        ClassLoader clazzLoader = new PathClassLoader(CHROMIUM_WEBVIEW_JAR, null,
                WebViewFactory.class.getClassLoader());
        return getFactoryByName(CHROMIUM_WEBVIEW_FACTORY, clazzLoader);
    }

    private static WebViewFactoryProvider getFactoryByName(String providerName,
            ClassLoader loader) {
        try {
            if (DEBUG) Log.v(LOGTAG, "attempt to load class " + providerName);
            Class<?> c = Class.forName(providerName, true, loader);
            if (DEBUG) Log.v(LOGTAG, "instantiating factory");
            return (WebViewFactoryProvider) c.newInstance();
        } catch (ClassNotFoundException e) {
            Log.e(LOGTAG, "error loading " + providerName, e);
        } catch (IllegalAccessException e) {
            Log.e(LOGTAG, "error loading " + providerName, e);
        } catch (InstantiationException e) {
            Log.e(LOGTAG, "error loading " + providerName, e);
        }
        return null;
    }
}
