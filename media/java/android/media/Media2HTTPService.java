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

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
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
            // Only need to do it once for all connections
            if ( !mCookieStoreInitialized )  {
                CookieHandler cookieHandler = CookieHandler.getDefault();
                if (cookieHandler == null) {
                    cookieHandler = new CookieManager();
                    CookieHandler.setDefault(cookieHandler);
                    Log.v(TAG, "makeHTTPConnection: CookieManager created: " + cookieHandler);
                } else {
                    Log.v(TAG, "makeHTTPConnection: CookieHandler (" + cookieHandler + ") exists.");
                }

                // Applying the bootstrapping cookies
                if ( mCookies != null ) {
                    if ( cookieHandler instanceof CookieManager ) {
                        CookieManager cookieManager = (CookieManager)cookieHandler;
                        CookieStore store = cookieManager.getCookieStore();
                        for ( HttpCookie cookie : mCookies ) {
                            try {
                                store.add(null, cookie);
                            } catch ( Exception e ) {
                                Log.v(TAG, "makeHTTPConnection: CookieStore.add" + e);
                            }
                            //for extended debugging when needed
                            //Log.v(TAG, "MediaHTTPConnection adding Cookie[" + cookie.getName() +
                            //        "]: " + cookie);
                        }
                    } else {
                        Log.w(TAG, "makeHTTPConnection: The installed CookieHandler is not a "
                                + "CookieManager. Can’t add the provided cookies to the cookie "
                                + "store.");
                    }
                }   // mCookies

                mCookieStoreInitialized = true;

                Log.v(TAG, "makeHTTPConnection(" + this + "): cookieHandler: " + cookieHandler +
                        " Cookies: " + mCookies);
            }   // mCookieStoreInitialized
        }   // synchronized

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
