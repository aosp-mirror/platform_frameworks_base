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

import android.util.Log;

/**
 * Top level factory, used creating all the main WebView implementation classes.
 */
class WebViewFactory {
    // Default Provider factory class name.
    private static final String DEFAULT_WEB_VIEW_FACTORY = "android.webkit.WebViewClassic$Factory";

    private static final String LOGTAG = "WebViewFactory";

    private static final boolean DEBUG = false;

    // Cache the factory both for efficiency, and ensure any one process gets all webviews from the
    // same provider.
    private static WebViewFactoryProvider sProviderInstance;

    static synchronized WebViewFactoryProvider getProvider() {
        // For now the main purpose of this function (and the factory abstraction) is to keep
        // us honest and minimize usage of WebViewClassic internals when binding the proxy.
        if (sProviderInstance != null) return sProviderInstance;

        sProviderInstance = getFactoryByName(DEFAULT_WEB_VIEW_FACTORY);
        if (sProviderInstance == null) {
            if (DEBUG) Log.v(LOGTAG, "Falling back to explicit linkage");
            sProviderInstance = new WebViewClassic.Factory();
        }
        return sProviderInstance;
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
