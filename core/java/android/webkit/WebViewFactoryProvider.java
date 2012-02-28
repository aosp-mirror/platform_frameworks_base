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

/**
 * This is the main entry-point into the WebView back end implementations, which the WebView
 * proxy class uses to instantiate all the other objects as needed. The backend must provide an
 * implementation of this interface, and make it available to the WebView via mechanism TBD.
 * @hide
 */
public interface WebViewFactoryProvider {

    /**
     * Construct a new WebView provider.
     * @param webView the WebView instance bound to this implementation instance. Note it will not
     * necessarily be fully constructed at the point of this call: defer real initialization to
     * WebViewProvider.init().
     * @param privateAccess provides access into WebView internal methods.
     */
    WebViewProvider createWebView(WebView webView, WebView.PrivateAccess privateAccess);

    Statics getStatics();

    /**
     * This Interface provides glue for implementing the backend of WebView static methods which
     * cannot be implemented in-situ in the proxy class.
     */
    interface Statics {
        /**
         * Implements the API method:
         * {@link android.webkit.WebView#findAddress(String)}
         */
        String findAddress(String addr);

        /**
         * Implements the API methods:
         * {@link android.webkit.WebView#enablePlatformNotifications()}
         * {@link android.webkit.WebView#disablePlatformNotifications()}
         */
        void setPlatformNotificationsEnabled(boolean enable);
    }
}
