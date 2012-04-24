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

import android.net.ParseException;
import android.net.WebAddress;
import android.os.AsyncTask;
import android.util.Log;

class CookieManagerClassic extends CookieManager {

    private static CookieManagerClassic sRef;

    private static final String LOGTAG = "webkit";

    private int mPendingCookieOperations = 0;

    private CookieManagerClassic() {
    }

    public static synchronized CookieManagerClassic getInstance() {
        if (sRef == null) {
            sRef = new CookieManagerClassic();
        }
        return sRef;
    }

    @Override
    public synchronized void setAcceptCookie(boolean accept) {
        nativeSetAcceptCookie(accept);
    }

    @Override
    public synchronized boolean acceptCookie() {
        return nativeAcceptCookie();
    }

    @Override
    public void setCookie(String url, String value) {
        setCookie(url, value, false);
    }

    /**
     * See {@link #setCookie(String, String)}
     * @param url The URL for which the cookie is set
     * @param value The value of the cookie, as a string, using the format of
     *              the 'Set-Cookie' HTTP response header
     * @param privateBrowsing Whether to use the private browsing cookie jar
     */
    void setCookie(String url, String value, boolean privateBrowsing) {
        WebAddress uri;
        try {
            uri = new WebAddress(url);
        } catch (ParseException ex) {
            Log.e(LOGTAG, "Bad address: " + url);
            return;
        }

        nativeSetCookie(uri.toString(), value, privateBrowsing);
    }

    @Override
    public String getCookie(String url) {
        return getCookie(url, false);
    }

    @Override
    public String getCookie(String url, boolean privateBrowsing) {
        WebAddress uri;
        try {
            uri = new WebAddress(url);
        } catch (ParseException ex) {
            Log.e(LOGTAG, "Bad address: " + url);
            return null;
        }

        return nativeGetCookie(uri.toString(), privateBrowsing);
    }

    @Override
    public synchronized String getCookie(WebAddress uri) {
        return nativeGetCookie(uri.toString(), false);
    }

    /**
     * Waits for pending operations to completed.
     */
    void waitForCookieOperationsToComplete() {
        // Note that this function is applicable for both the java
        // and native http stacks, and works correctly with either.
        synchronized (this) {
            while (mPendingCookieOperations > 0) {
                try {
                    wait();
                } catch (InterruptedException e) { }
            }
        }
    }

    private synchronized void signalCookieOperationsComplete() {
        mPendingCookieOperations--;
        assert mPendingCookieOperations > -1;
        notify();
    }

    private synchronized void signalCookieOperationsStart() {
        mPendingCookieOperations++;
    }

    @Override
    public void removeSessionCookie() {
        signalCookieOperationsStart();
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... none) {
                nativeRemoveSessionCookie();
                signalCookieOperationsComplete();
                return null;
            }
        }.execute();
    }

    @Override
    public void removeAllCookie() {
        nativeRemoveAllCookie();
    }

    @Override
    public synchronized boolean hasCookies() {
        return hasCookies(false);
    }

    @Override
    public synchronized boolean hasCookies(boolean privateBrowsing) {
        return nativeHasCookies(privateBrowsing);
    }

    @Override
    public void removeExpiredCookie() {
        nativeRemoveExpiredCookie();
    }

    @Override
    protected void flushCookieStore() {
        nativeFlushCookieStore();
    }

    @Override
    protected boolean allowFileSchemeCookiesImpl() {
        return nativeAcceptFileSchemeCookies();
    }

    @Override
    protected void setAcceptFileSchemeCookiesImpl(boolean accept) {
        nativeSetAcceptFileSchemeCookies(accept);
    }

    // Native functions
    private static native boolean nativeAcceptCookie();
    private static native String nativeGetCookie(String url, boolean privateBrowsing);
    private static native boolean nativeHasCookies(boolean privateBrowsing);
    private static native void nativeRemoveAllCookie();
    private static native void nativeRemoveExpiredCookie();
    private static native void nativeRemoveSessionCookie();
    private static native void nativeSetAcceptCookie(boolean accept);
    private static native void nativeSetCookie(String url, String value, boolean privateBrowsing);
    private static native void nativeFlushCookieStore();
    private static native boolean nativeAcceptFileSchemeCookies();
    private static native void nativeSetAcceptFileSchemeCookies(boolean accept);
}
