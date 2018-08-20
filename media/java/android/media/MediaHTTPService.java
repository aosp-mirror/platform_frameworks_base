/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.annotation.Nullable;
import android.annotation.UnsupportedAppUsage;
import android.os.IBinder;
import android.util.Log;

import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpCookie;
import java.util.List;

/** @hide */
public class MediaHTTPService extends IMediaHTTPService.Stub {
    private static final String TAG = "MediaHTTPService";
    @Nullable private List<HttpCookie> mCookies;
    private Boolean mCookieStoreInitialized = new Boolean(false);

    public MediaHTTPService(@Nullable List<HttpCookie> cookies) {
        mCookies = cookies;
        Log.v(TAG, "MediaHTTPService(" + this + "): Cookies: " + cookies);
    }

    public IMediaHTTPConnection makeHTTPConnection() {

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

        return new MediaHTTPConnection();
    }

    @UnsupportedAppUsage
    /* package private */static IBinder createHttpServiceBinderIfNecessary(
            String path) {
        return createHttpServiceBinderIfNecessary(path, null);
    }

    // when cookies are provided
    static IBinder createHttpServiceBinderIfNecessary(
            String path, List<HttpCookie> cookies) {
        if (path.startsWith("http://") || path.startsWith("https://")) {
            return (new MediaHTTPService(cookies)).asBinder();
        } else if (path.startsWith("widevine://")) {
            Log.d(TAG, "Widevine classic is no longer supported");
        }

        return null;
    }
}
