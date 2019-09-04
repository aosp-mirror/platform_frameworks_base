/*
 * Copyright (C) 2017 The Android Open Source Project
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

import java.net.HttpCookie;
import java.util.List;

/** @hide */
public class Media2HTTPService {
    private static final String TAG = "Media2HTTPService";
    private List<HttpCookie> mCookies;
    private Boolean mCookieStoreInitialized = new Boolean(false);

    public Media2HTTPService(List<HttpCookie> cookies) {
        mCookies = cookies;
        Log.v(TAG, "Media2HTTPService(" + this + "): Cookies: " + cookies);
    }

    public Media2HTTPConnection makeHTTPConnection() {

        synchronized (mCookieStoreInitialized) {
            Media2Utils.storeCookies(mCookies);
        }

        return new Media2HTTPConnection();
    }

    /* package private */ static Media2HTTPService createHTTPService(String path) {
        return createHTTPService(path, null);
    }

    // when cookies are provided
    static Media2HTTPService createHTTPService(String path, List<HttpCookie> cookies) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return (new Media2HTTPService(cookies));
        } else if (path.startsWith("widevine://")) {
            Log.d(TAG, "Widevine classic is no longer supported");
        }

        return null;
    }
}
