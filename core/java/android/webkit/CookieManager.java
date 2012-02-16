/*
 * Copyright (C) 2006 The Android Open Source Project
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
import android.net.http.AndroidHttpClient;
import android.os.AsyncTask;
import android.util.Log;


import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * Manages the cookies used by an application's {@link WebView} instances.
 * Cookies are manipulated according to RFC2109.
 */
public final class CookieManager {

    private static CookieManager sRef;

    private static final String LOGTAG = "webkit";

    private static final String DOMAIN = "domain";

    private static final String PATH = "path";

    private static final String EXPIRES = "expires";

    private static final String SECURE = "secure";

    private static final String MAX_AGE = "max-age";

    private static final String HTTP_ONLY = "httponly";

    private static final String HTTPS = "https";

    private static final char PERIOD = '.';

    private static final char COMMA = ',';

    private static final char SEMICOLON = ';';

    private static final char EQUAL = '=';

    private static final char PATH_DELIM = '/';

    private static final char QUESTION_MARK = '?';

    private static final char WHITE_SPACE = ' ';

    private static final char QUOTATION = '\"';

    private static final int SECURE_LENGTH = SECURE.length();

    private static final int HTTP_ONLY_LENGTH = HTTP_ONLY.length();

    // RFC2109 defines 4k as maximum size of a cookie
    private static final int MAX_COOKIE_LENGTH = 4 * 1024;

    // RFC2109 defines 20 as max cookie count per domain. As we track with base
    // domain, we allow 50 per base domain
    private static final int MAX_COOKIE_COUNT_PER_BASE_DOMAIN = 50;

    // RFC2109 defines 300 as max count of domains. As we track with base
    // domain, we set 200 as max base domain count
    private static final int MAX_DOMAIN_COUNT = 200;

    // max cookie count to limit RAM cookie takes less than 100k, it is based on
    // average cookie entry size is less than 100 bytes
    private static final int MAX_RAM_COOKIES_COUNT = 1000;

    //  max domain count to limit RAM cookie takes less than 100k,
    private static final int MAX_RAM_DOMAIN_COUNT = 15;

    private int mPendingCookieOperations = 0;

    /**
     * This contains a list of 2nd-level domains that aren't allowed to have
     * wildcards when combined with country-codes. For example: [.co.uk].
     */
    private final static String[] BAD_COUNTRY_2LDS =
          { "ac", "co", "com", "ed", "edu", "go", "gouv", "gov", "info",
            "lg", "ne", "net", "or", "org" };

    static {
        Arrays.sort(BAD_COUNTRY_2LDS);
    }

    /**
     * Package level class to be accessed by cookie sync manager
     */
    static class Cookie {
        static final byte MODE_NEW = 0;

        static final byte MODE_NORMAL = 1;

        static final byte MODE_DELETED = 2;

        static final byte MODE_REPLACED = 3;

        String domain;

        String path;

        String name;

        String value;

        long expires;

        long lastAcessTime;

        long lastUpdateTime;

        boolean secure;

        byte mode;

        Cookie() {
        }

        Cookie(String defaultDomain, String defaultPath) {
            domain = defaultDomain;
            path = defaultPath;
            expires = -1;
        }

        boolean exactMatch(Cookie in) {
            // An exact match means that domain, path, and name are equal. If
            // both values are null, the cookies match. If both values are
            // non-null, the cookies match. If one value is null and the other
            // is non-null, the cookies do not match (i.e. "foo=;" and "foo;")
            boolean valuesMatch = !((value == null) ^ (in.value == null));
            return domain.equals(in.domain) && path.equals(in.path) &&
                    name.equals(in.name) && valuesMatch;
        }

        boolean domainMatch(String urlHost) {
            if (domain.startsWith(".")) {
                if (urlHost.endsWith(domain.substring(1))) {
                    int len = domain.length();
                    int urlLen = urlHost.length();
                    if (urlLen > len - 1) {
                        // make sure bar.com doesn't match .ar.com
                        return urlHost.charAt(urlLen - len) == PERIOD;
                    }
                    return true;
                }
                return false;
            } else {
                // exact match if domain is not leading w/ dot
                return urlHost.equals(domain);
            }
        }

        boolean pathMatch(String urlPath) {
            if (urlPath.startsWith(path)) {
                int len = path.length();
                if (len == 0) {
                    Log.w(LOGTAG, "Empty cookie path");
                    return false;
                }
                int urlLen = urlPath.length();
                if (path.charAt(len-1) != PATH_DELIM && urlLen > len) {
                    // make sure /wee doesn't match /we
                    return urlPath.charAt(len) == PATH_DELIM;
                }
                return true;
            }
            return false;
        }

        public String toString() {
            return "domain: " + domain + "; path: " + path + "; name: " + name
                    + "; value: " + value;
        }
    }

    private static final CookieComparator COMPARATOR = new CookieComparator();

    private static final class CookieComparator implements Comparator<Cookie> {
        public int compare(Cookie cookie1, Cookie cookie2) {
            // According to RFC 2109, multiple cookies are ordered in a way such
            // that those with more specific Path attributes precede those with
            // less specific. Ordering with respect to other attributes (e.g.,
            // Domain) is unspecified.
            // As Set is not modified if the two objects are same, we do want to
            // assign different value for each cookie.
            int diff = cookie2.path.length() - cookie1.path.length();
            if (diff != 0) return diff;

            diff = cookie2.domain.length() - cookie1.domain.length();
            if (diff != 0) return diff;

            // If cookie2 has a null value, it should come later in
            // the list.
            if (cookie2.value == null) {
                // If both cookies have null values, fall back to using the name
                // difference.
                if (cookie1.value != null) {
                    return -1;
                }
            } else if (cookie1.value == null) {
                // Now we know that cookie2 does not have a null value, if
                // cookie1 has a null value, place it later in the list.
                return 1;
            }

            // Fallback to comparing the name to ensure consistent order.
            return cookie1.name.compareTo(cookie2.name);
        }
    }

    private CookieManager() {
    }

    protected Object clone() throws CloneNotSupportedException {
        throw new CloneNotSupportedException("doesn't implement Cloneable");
    }

    /**
     * Gets the singleton CookieManager instance. If this method is used
     * before the application instantiates a {@link WebView} instance,
     * {@link CookieSyncManager#createInstance(Context)} must be called
     * first.
     * 
     * @return The singleton CookieManager instance
     */
    public static synchronized CookieManager getInstance() {
        if (sRef == null) {
            sRef = new CookieManager();
        }
        return sRef;
    }

    /**
     * Sets whether the application's {@link WebView} instances should send and
     * accept cookies.
     * @param accept Whether {@link WebView} instances should send and accept
     *               cookies
     */
    public synchronized void setAcceptCookie(boolean accept) {
        nativeSetAcceptCookie(accept);
    }

    /**
     * Gets whether the application's {@link WebView} instances send and accept
     * cookies.
     * @return True if {@link WebView} instances send and accept cookies
     */
    public synchronized boolean acceptCookie() {
        return nativeAcceptCookie();
    }

    /**
     * Sets a cookie for the given URL. Any existing cookie with the same host,
     * path and name will be replaced with the new cookie. The cookie being set
     * must not have expired and must not be a session cookie, otherwise it
     * will be ignored.
     * @param url The URL for which the cookie is set
     * @param value The cookie as a string, using the format of the
     *              'Set-Cookie' HTTP response header
     */
    public void setCookie(String url, String value) {
        setCookie(url, value, false);
    }

    /**
     * See {@link setCookie(String, String)}
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

    /**
     * Gets the cookies for the given URL.
     * @param url The URL for which the cookies are requested
     * @return value The cookies as a string, using the format of the 'Cookie'
     *               HTTP request header
     */
    public String getCookie(String url) {
        return getCookie(url, false);
    }

    /**
     * See {@link getCookie(String)}
     * @param url The URL for which the cookies are requested
     * @param privateBrowsing Whether to use the private browsing cookie jar
     * @return value The cookies as a string, using the format of the 'Cookie'
     *               HTTP request header
     * @hide Used by Browser, no intention to publish.
     */
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

    /**
     * Get cookie(s) for a given uri so that it can be set to "cookie:" in http
     * request header.
     * @param uri The WebAddress for which the cookies are requested
     * @return value The cookies as a string, using the format of the 'Cookie'
     *               HTTP request header
     * @hide Used by RequestHandle, no intention to publish.
     */
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

    /**
     * Removes all session cookies, which are cookies without an expiration
     * date.
     */
    public void removeSessionCookie() {
        signalCookieOperationsStart();
        new AsyncTask<Void, Void, Void>() {
            protected Void doInBackground(Void... none) {
                nativeRemoveSessionCookie();
                signalCookieOperationsComplete();
                return null;
            }
        }.execute();
    }

    /**
     * Removes all cookies.
     */
    public void removeAllCookie() {
        nativeRemoveAllCookie();
    }

    /**
     * Gets whether there are stored cookies.
     * @return True if there are stored cookies.
     */
    public synchronized boolean hasCookies() {
        return hasCookies(false);
    }

    /**
     * See {@link hasCookies()}.
     * @param privateBrowsing Whether to use the private browsing cookie jar
     * @hide Used by Browser, no intention to publish.
     */
    public synchronized boolean hasCookies(boolean privateBrowsing) {
        return nativeHasCookies(privateBrowsing);
    }

    /**
     * Removes all expired cookies.
     */
    public void removeExpiredCookie() {
        nativeRemoveExpiredCookie();
    }

    /**
     * Package level api, called from CookieSyncManager
     *
     * Flush all cookies managed by the Chrome HTTP stack to flash.
     */
    void flushCookieStore() {
        nativeFlushCookieStore();
    }

    /**
     * Gets whether the application's {@link WebView} instances send and accept
     * cookies for file scheme URLs.
     * @return True if {@link WebView} instances send and accept cookies for
     *         file scheme URLs
     */
    public static boolean allowFileSchemeCookies() {
        return nativeAcceptFileSchemeCookies();
    }

    /**
     * Sets whether the application's {@link WebView} instances should send and
     * accept cookies for file scheme URLs.
     * Use of cookies with file scheme URLs is potentially insecure. Do not use
     * this feature unless you can be sure that no unintentional sharing of
     * cookie data can take place.
     * <p>
     * Note that calls to this method will have no effect if made after a
     * {@link WebView} or CookieManager instance has been created.
     */
    public static void setAcceptFileSchemeCookies(boolean accept) {
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
