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
 *
 * @hide
 */
public final class WebViewFactory {
    public static final String WEBVIEW_EXPERIMENTAL_PROPERTY = "persist.sys.webview.exp";
    private static final String DEPRECATED_CHROMIUM_PROPERTY = "webview.use_chromium";

    // Default Provider factory class name.
    // TODO: When the Chromium powered WebView is ready, it should be the default factory class.
    private static final String DEFAULT_WEBVIEW_FACTORY = "android.webkit.WebViewClassic$Factory";
    private static final String CHROMIUM_WEBVIEW_FACTORY =
            "com.android.webview.chromium.WebViewChromiumFactoryProvider";

    private static final String LOGTAG = "WebViewFactory";

    private static final boolean DEBUG = false;

    // Cache the factory both for efficiency, and ensure any one process gets all webviews from the
    // same provider.
    private static WebViewFactoryProvider sProviderInstance;
    private static final Object sProviderLock = new Object();

    public static boolean isExperimentalWebViewAvailable() {
        try {
            // Pass false so we don't initialize the class at this point, as this will be wasted if
            // it's not enabled.
            Class.forName(CHROMIUM_WEBVIEW_FACTORY, false, WebViewFactory.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static WebViewFactoryProvider getProvider() {
        synchronized (sProviderLock) {
            // For now the main purpose of this function (and the factory abstraction) is to keep
            // us honest and minimize usage of WebViewClassic internals when binding the proxy.
            if (sProviderInstance != null) return sProviderInstance;

            if (isExperimentalWebViewEnabled()) {
                StrictMode.ThreadPolicy oldPolicy = StrictMode.allowThreadDiskReads();
                try {
                    sProviderInstance = getFactoryByName(CHROMIUM_WEBVIEW_FACTORY);
                    if (DEBUG) Log.v(LOGTAG, "Loaded Chromium provider: " + sProviderInstance);
                } finally {
                    StrictMode.setThreadPolicy(oldPolicy);
                }
            }

            if (sProviderInstance == null) {
                if (DEBUG) Log.v(LOGTAG, "Falling back to default provider: "
                        + DEFAULT_WEBVIEW_FACTORY);
                sProviderInstance = getFactoryByName(DEFAULT_WEBVIEW_FACTORY);
                if (sProviderInstance == null) {
                    if (DEBUG) Log.v(LOGTAG, "Falling back to explicit linkage");
                    sProviderInstance = new WebViewClassic.Factory();
                }
            }
            return sProviderInstance;
        }
    }

    // For debug builds, we allow a system property to specify that we should use the
    // experimtanl Chromium powered WebView. This enables us to switch between
    // implementations at runtime. For user (release) builds, don't allow this.
    private static boolean isExperimentalWebViewEnabled() {
        if (!isExperimentalWebViewAvailable())
            return false;
        if (SystemProperties.getBoolean(DEPRECATED_CHROMIUM_PROPERTY, false)) {
            Log.w(LOGTAG, String.format("The property %s has been deprecated. Please use %s.",
                    DEPRECATED_CHROMIUM_PROPERTY, WEBVIEW_EXPERIMENTAL_PROPERTY));
            return true;
        }
        return SystemProperties.getBoolean(WEBVIEW_EXPERIMENTAL_PROPERTY, false);
    }

    private static WebViewFactoryProvider getFactoryByName(String providerName) {
        try {
            if (DEBUG) Log.v(LOGTAG, "attempt to load class " + providerName);
            Class<?> c = Class.forName(providerName);
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
