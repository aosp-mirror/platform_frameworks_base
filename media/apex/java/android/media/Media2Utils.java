/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.media;

import android.util.Log;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.util.List;

/** @hide */
public class Media2Utils {
    private static final String TAG = "Media2Utils";

    private Media2Utils() {
    }

    /**
     * Ensures that an expression checking an argument is true.
     *
     * @param expression the expression to check
     * @param errorMessage the exception message to use if the check fails; will
     *     be converted to a string using {@link String#valueOf(Object)}
     * @throws IllegalArgumentException if {@code expression} is false
     */
    public static void checkArgument(boolean expression, String errorMessage) {
        if (!expression) {
            throw new IllegalArgumentException(errorMessage);
        }
    }

    public static synchronized void storeCookies(List<HttpCookie> cookies) {
        CookieHandler cookieHandler = CookieHandler.getDefault();
        if (cookieHandler == null) {
            cookieHandler = new CookieManager();
            CookieHandler.setDefault(cookieHandler);
            Log.v(TAG, "storeCookies: CookieManager created: " + cookieHandler);
        } else {
            Log.v(TAG, "storeCookies: CookieHandler (" + cookieHandler + ") exists.");
        }

        if (cookies != null) {
            if (cookieHandler instanceof CookieManager) {
                CookieManager cookieManager = (CookieManager)cookieHandler;
                CookieStore store = cookieManager.getCookieStore();
                for (HttpCookie cookie : cookies) {
                    try {
                        store.add(null, cookie);
                    } catch (Exception e) {
                        Log.v(TAG, "storeCookies: CookieStore.add" + cookie, e);
                    }
                }
            } else {
                Log.w(TAG, "storeCookies: The installed CookieHandler is not a CookieManager."
                        + " Can’t add the provided cookies to the cookie store.");
            }
        }   // cookies

        Log.v(TAG, "storeCookies: cookieHandler: " + cookieHandler + " Cookies: " + cookies);

    }
}
