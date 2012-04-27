/*
 * Copyright (C) 2007 The Android Open Source Project
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

import android.content.Context;

/**
 * This class allows developers to determine whether any WebView used in the
 * application has stored any of the following types of browsing data and
 * to clear any such stored data for all WebViews in the application.
 * <ul>
 *  <li>Username/password pairs entered into web forms</li>
 *  <li>HTTP authentication username/password pairs</li>
 *  <li>Data entered into text fields (e.g. for autocomplete suggestions)</li>
 * </ul>
 */
public class WebViewDatabase {
    // TODO: deprecate/hide this.
    protected static final String LOGTAG = "webviewdatabase";

    /**
     * @hide Only for use by WebViewProvider implementations.
     */
    protected WebViewDatabase() {
    }

    public static synchronized WebViewDatabase getInstance(Context context) {
        return WebViewFactory.getProvider().getWebViewDatabase(context);
    }

    /**
     * Gets whether there are any username/password combinations
     * from web pages saved.
     *
     * @return true if there are any username/passwords used in web
     *         forms saved
     */
    public boolean hasUsernamePassword() {
        throw new MustOverrideException();
    }

    /**
     * Clears any username/password combinations saved from web forms.
     */
    public void clearUsernamePassword() {
        throw new MustOverrideException();
    }

    /**
     * Gets whether there are any HTTP authentication username/password combinations saved.
     *
     * @return true if there are any HTTP authentication username/passwords saved
     */
    public boolean hasHttpAuthUsernamePassword() {
        throw new MustOverrideException();
    }

    /**
     * Clears any HTTP authentication username/passwords that are saved.
     */
    public void clearHttpAuthUsernamePassword() {
        throw new MustOverrideException();
    }

    /**
     * Gets whether there is any previously-entered form data saved.
     *
     * @return true if there is form data saved
     */
    public boolean hasFormData() {
        throw new MustOverrideException();
    }

    /**
     * Clears any stored previously-entered form data.
     */
    public void clearFormData() {
        throw new MustOverrideException();
    }
}
