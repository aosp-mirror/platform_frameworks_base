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
 * CookieManager manages cookies according to RFC2109 spec.
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

    private Map<String, ArrayList<Cookie>> mCookieMap = new LinkedHashMap
            <String, ArrayList<Cookie>>(MAX_DOMAIN_COUNT, 0.75f, true);

    private boolean mAcceptCookie = true;

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
     * Get a singleton CookieManager. If this is called before any
     * {@link WebView} is created or outside of {@link WebView} context, the
     * caller needs to call {@link CookieSyncManager#createInstance(Context)}
     * first.
     * 
     * @return CookieManager
     */
    public static synchronized CookieManager getInstance() {
        if (sRef == null) {
            sRef = new CookieManager();
        }
        return sRef;
    }

    /**
     * Control whether cookie is enabled or disabled
     * @param accept TRUE if accept cookie
     */
    public synchronized void setAcceptCookie(boolean accept) {
        mAcceptCookie = accept;
    }

    /**
     * Return whether cookie is enabled
     * @return TRUE if accept cookie
     */
    public synchronized boolean acceptCookie() {
        return mAcceptCookie;
    }

    /**
     * Set cookie for a given url. The old cookie with same host/path/name will
     * be removed. The new cookie will be added if it is not expired or it does
     * not have expiration which implies it is session cookie.
     * @param url The url which cookie is set for
     * @param value The value for set-cookie: in http response header
     */
    public void setCookie(String url, String value) {
        WebAddress uri;
        try {
            uri = new WebAddress(url);
        } catch (ParseException ex) {
            Log.e(LOGTAG, "Bad address: " + url);
            return;
        }
        setCookie(uri, value);
    }

    /**
     * Set cookie for a given uri. The old cookie with same host/path/name will
     * be removed. The new cookie will be added if it is not expired or it does
     * not have expiration which implies it is session cookie.
     * @param uri The uri which cookie is set for
     * @param value The value for set-cookie: in http response header
     * @hide - hide this because it takes in a parameter of type WebAddress,
     * a system private class.
     */
    public synchronized void setCookie(WebAddress uri, String value) {
        if (value != null && value.length() > MAX_COOKIE_LENGTH) {
            return;
        }
        if (!mAcceptCookie || uri == null) {
            return;
        }
        if (DebugFlags.COOKIE_MANAGER) {
            Log.v(LOGTAG, "setCookie: uri: " + uri + " value: " + value);
        }

        String[] hostAndPath = getHostAndPath(uri);
        if (hostAndPath == null) {
            return;
        }
        
        // For default path, when setting a cookie, the spec says:
        //Path:   Defaults to the path of the request URL that generated the
        // Set-Cookie response, up to, but not including, the
        // right-most /.
        if (hostAndPath[1].length() > 1) {
            int index = hostAndPath[1].lastIndexOf(PATH_DELIM);
            hostAndPath[1] = hostAndPath[1].substring(0, 
                    index > 0 ? index : index + 1);
        }

        ArrayList<Cookie> cookies = null;
        try {
            cookies = parseCookie(hostAndPath[0], hostAndPath[1], value);
        } catch (RuntimeException ex) {
            Log.e(LOGTAG, "parse cookie failed for: " + value);
        }

        if (cookies == null || cookies.size() == 0) {
            return;
        }

        String baseDomain = getBaseDomain(hostAndPath[0]);
        ArrayList<Cookie> cookieList = mCookieMap.get(baseDomain);
        if (cookieList == null) {
            cookieList = CookieSyncManager.getInstance()
                    .getCookiesForDomain(baseDomain);
            mCookieMap.put(baseDomain, cookieList);
        }

        long now = System.currentTimeMillis();
        int size = cookies.size();
        for (int i = 0; i < size; i++) {
            Cookie cookie = cookies.get(i);

            boolean done = false;
            Iterator<Cookie> iter = cookieList.iterator();
            while (iter.hasNext()) {
                Cookie cookieEntry = iter.next();
                if (cookie.exactMatch(cookieEntry)) {
                    // expires == -1 means no expires defined. Otherwise
                    // negative means far future
                    if (cookie.expires < 0 || cookie.expires > now) {
                        // secure cookies can't be overwritten by non-HTTPS url
                        if (!cookieEntry.secure || HTTPS.equals(uri.mScheme)) {
                            cookieEntry.value = cookie.value;
                            cookieEntry.expires = cookie.expires;
                            cookieEntry.secure = cookie.secure;
                            cookieEntry.lastAcessTime = now;
                            cookieEntry.lastUpdateTime = now;
                            cookieEntry.mode = Cookie.MODE_REPLACED;
                        }
                    } else {
                        cookieEntry.lastUpdateTime = now;
                        cookieEntry.mode = Cookie.MODE_DELETED;
                    }
                    done = true;
                    break;
                }
            }

            // expires == -1 means no expires defined. Otherwise negative means
            // far future
            if (!done && (cookie.expires < 0 || cookie.expires > now)) {
                cookie.lastAcessTime = now;
                cookie.lastUpdateTime = now;
                cookie.mode = Cookie.MODE_NEW;
                if (cookieList.size() > MAX_COOKIE_COUNT_PER_BASE_DOMAIN) {
                    Cookie toDelete = new Cookie();
                    toDelete.lastAcessTime = now;
                    Iterator<Cookie> iter2 = cookieList.iterator();
                    while (iter2.hasNext()) {
                        Cookie cookieEntry2 = iter2.next();
                        if ((cookieEntry2.lastAcessTime < toDelete.lastAcessTime)
                                && cookieEntry2.mode != Cookie.MODE_DELETED) {
                            toDelete = cookieEntry2;
                        }
                    }
                    toDelete.mode = Cookie.MODE_DELETED;
                }
                cookieList.add(cookie);
            }
        }
    }

    /**
     * Get cookie(s) for a given url so that it can be set to "cookie:" in http
     * request header.
     * @param url The url needs cookie
     * @return The cookies in the format of NAME=VALUE [; NAME=VALUE]
     */
    public String getCookie(String url) {
        WebAddress uri;
        try {
            uri = new WebAddress(url);
        } catch (ParseException ex) {
            Log.e(LOGTAG, "Bad address: " + url);
            return null;
        }
        return getCookie(uri);
    }

    /**
     * Get cookie(s) for a given uri so that it can be set to "cookie:" in http
     * request header.
     * @param uri The uri needs cookie
     * @return The cookies in the format of NAME=VALUE [; NAME=VALUE]
     * @hide - hide this because it has a parameter of type WebAddress, which
     * is a system private class.
     */
    public synchronized String getCookie(WebAddress uri) {
        if (!mAcceptCookie || uri == null) {
            return null;
        }
   
        String[] hostAndPath = getHostAndPath(uri);
        if (hostAndPath == null) {
            return null;
        }

        String baseDomain = getBaseDomain(hostAndPath[0]);
        ArrayList<Cookie> cookieList = mCookieMap.get(baseDomain);
        if (cookieList == null) {
            cookieList = CookieSyncManager.getInstance()
                    .getCookiesForDomain(baseDomain);
            mCookieMap.put(baseDomain, cookieList);
        }

        long now = System.currentTimeMillis();
        boolean secure = HTTPS.equals(uri.mScheme);
        Iterator<Cookie> iter = cookieList.iterator();

        SortedSet<Cookie> cookieSet = new TreeSet<Cookie>(COMPARATOR);
        while (iter.hasNext()) {
            Cookie cookie = iter.next();
            if (cookie.domainMatch(hostAndPath[0]) &&
                    cookie.pathMatch(hostAndPath[1])
                    // expires == -1 means no expires defined. Otherwise
                    // negative means far future
                    && (cookie.expires < 0 || cookie.expires > now)
                    && (!cookie.secure || secure)
                    && cookie.mode != Cookie.MODE_DELETED) {
                cookie.lastAcessTime = now;
                cookieSet.add(cookie);
            }
        }

        StringBuilder ret = new StringBuilder(256);
        Iterator<Cookie> setIter = cookieSet.iterator();
        while (setIter.hasNext()) {
            Cookie cookie = setIter.next();
            if (ret.length() > 0) {
                ret.append(SEMICOLON);
                // according to RC2109, SEMICOLON is official separator,
                // but when log in yahoo.com, it needs WHITE_SPACE too.
                ret.append(WHITE_SPACE);
            }

            ret.append(cookie.name);
            if (cookie.value != null) {
                ret.append(EQUAL);
                ret.append(cookie.value);
            }
        }

        if (ret.length() > 0) {
            if (DebugFlags.COOKIE_MANAGER) {
                Log.v(LOGTAG, "getCookie: uri: " + uri + " value: " + ret);
            }
            return ret.toString();
        } else {
            if (DebugFlags.COOKIE_MANAGER) {
                Log.v(LOGTAG, "getCookie: uri: " + uri
                        + " But can't find cookie.");
            }
            return null;
        }
    }

    /**
     * Remove all session cookies, which are cookies without expiration date
     */
    public void removeSessionCookie() {
        final Runnable clearCache = new Runnable() {
            public void run() {
                synchronized(CookieManager.this) {
                    Collection<ArrayList<Cookie>> cookieList = mCookieMap.values();
                    Iterator<ArrayList<Cookie>> listIter = cookieList.iterator();
                    while (listIter.hasNext()) {
                        ArrayList<Cookie> list = listIter.next();
                        Iterator<Cookie> iter = list.iterator();
                        while (iter.hasNext()) {
                            Cookie cookie = iter.next();
                            if (cookie.expires == -1) {
                                iter.remove();
                            }
                        }
                    }
                    CookieSyncManager.getInstance().clearSessionCookies();
                }
            }
        };
        new Thread(clearCache).start();
    }

    /**
     * Remove all cookies
     */
    public void removeAllCookie() {
        final Runnable clearCache = new Runnable() {
            public void run() {
                synchronized(CookieManager.this) {
                    mCookieMap = new LinkedHashMap<String, ArrayList<Cookie>>(
                            MAX_DOMAIN_COUNT, 0.75f, true);
                    CookieSyncManager.getInstance().clearAllCookies();
                }
            }
        };
        new Thread(clearCache).start();
    }

    /**
     *  Return true if there are stored cookies.
     */
    public synchronized boolean hasCookies() {
        return CookieSyncManager.getInstance().hasCookies();
    }

    /**
     * Remove all expired cookies
     */
    public void removeExpiredCookie() {
        final Runnable clearCache = new Runnable() {
            public void run() {
                synchronized(CookieManager.this) {
                    long now = System.currentTimeMillis();
                    Collection<ArrayList<Cookie>> cookieList = mCookieMap.values();
                    Iterator<ArrayList<Cookie>> listIter = cookieList.iterator();
                    while (listIter.hasNext()) {
                        ArrayList<Cookie> list = listIter.next();
                        Iterator<Cookie> iter = list.iterator();
                        while (iter.hasNext()) {
                            Cookie cookie = iter.next();
                            // expires == -1 means no expires defined. Otherwise 
                            // negative means far future
                            if (cookie.expires > 0 && cookie.expires < now) {
                                iter.remove();
                            }
                        }
                    }
                    CookieSyncManager.getInstance().clearExpiredCookies(now);
                }
            }
        };
        new Thread(clearCache).start();
    }

    /**
     * Package level api, called from CookieSyncManager
     *
     * Get a list of cookies which are updated since a given time.
     * @param last The given time in millisec
     * @return A list of cookies
     */
    synchronized ArrayList<Cookie> getUpdatedCookiesSince(long last) {
        ArrayList<Cookie> cookies = new ArrayList<Cookie>();
        Collection<ArrayList<Cookie>> cookieList = mCookieMap.values();
        Iterator<ArrayList<Cookie>> listIter = cookieList.iterator();
        while (listIter.hasNext()) {
            ArrayList<Cookie> list = listIter.next();
            Iterator<Cookie> iter = list.iterator();
            while (iter.hasNext()) {
                Cookie cookie = iter.next();
                if (cookie.lastUpdateTime > last) {
                    cookies.add(cookie);
                }
            }
        }
        return cookies;
    }

    /**
     * Package level api, called from CookieSyncManager
     *
     * Delete a Cookie in the RAM
     * @param cookie Cookie to be deleted
     */
    synchronized void deleteACookie(Cookie cookie) {
        if (cookie.mode == Cookie.MODE_DELETED) {
            String baseDomain = getBaseDomain(cookie.domain);
            ArrayList<Cookie> cookieList = mCookieMap.get(baseDomain);
            if (cookieList != null) {
                cookieList.remove(cookie);
                if (cookieList.isEmpty()) {
                    mCookieMap.remove(baseDomain);
                }
            }
        }
    }

    /**
     * Package level api, called from CookieSyncManager
     *
     * Called after a cookie is synced to FLASH
     * @param cookie Cookie to be synced
     */
    synchronized void syncedACookie(Cookie cookie) {
        cookie.mode = Cookie.MODE_NORMAL;
    }

    /**
     * Package level api, called from CookieSyncManager
     *
     * Delete the least recent used domains if the total cookie count in RAM
     * exceeds the limit
     * @return A list of cookies which are removed from RAM
     */
    synchronized ArrayList<Cookie> deleteLRUDomain() {
        int count = 0;
        int byteCount = 0;
        int mapSize = mCookieMap.size();

        if (mapSize < MAX_RAM_DOMAIN_COUNT) {
            Collection<ArrayList<Cookie>> cookieLists = mCookieMap.values();
            Iterator<ArrayList<Cookie>> listIter = cookieLists.iterator();
            while (listIter.hasNext() && count < MAX_RAM_COOKIES_COUNT) {
                ArrayList<Cookie> list = listIter.next();
                if (DebugFlags.COOKIE_MANAGER) {
                    Iterator<Cookie> iter = list.iterator();
                    while (iter.hasNext() && count < MAX_RAM_COOKIES_COUNT) {
                        Cookie cookie = iter.next();
                        // 14 is 3 * sizeof(long) + sizeof(boolean)
                        // + sizeof(byte)
                        byteCount += cookie.domain.length()
                                + cookie.path.length()
                                + cookie.name.length()
                                + (cookie.value != null
                                        ? cookie.value.length()
                                        : 0)
                                + 14;
                        count++;
                    }
                } else {
                    count += list.size();
                }
            }
        }

        ArrayList<Cookie> retlist = new ArrayList<Cookie>();
        if (mapSize >= MAX_RAM_DOMAIN_COUNT || count >= MAX_RAM_COOKIES_COUNT) {
            if (DebugFlags.COOKIE_MANAGER) {
                Log.v(LOGTAG, count + " cookies used " + byteCount
                        + " bytes with " + mapSize + " domains");
            }
            Object[] domains = mCookieMap.keySet().toArray();
            int toGo = mapSize / 10 + 1;
            while (toGo-- > 0){
                String domain = domains[toGo].toString();
                if (DebugFlags.COOKIE_MANAGER) {
                    Log.v(LOGTAG, "delete domain: " + domain
                            + " from RAM cache");
                }
                retlist.addAll(mCookieMap.get(domain));
                mCookieMap.remove(domain);
            }
        }
        return retlist;
    }

    /**
     * Extract the host and path out of a uri
     * @param uri The given WebAddress
     * @return The host and path in the format of String[], String[0] is host
     *          which has at least two periods, String[1] is path which always
     *          ended with "/"
     */
    private String[] getHostAndPath(WebAddress uri) {
        if (uri.mHost != null && uri.mPath != null) {

            /*
             * The domain (i.e. host) portion of the cookie is supposed to be
             * case-insensitive. We will consistently return the domain in lower
             * case, which allows us to do the more efficient equals comparison
             * instead of equalIgnoreCase.
             *
             * See: http://www.ieft.org/rfc/rfc2965.txt (Section 3.3.3)
             */
            String[] ret = new String[2];
            ret[0] = uri.mHost.toLowerCase();
            ret[1] = uri.mPath;

            int index = ret[0].indexOf(PERIOD);
            if (index == -1) {
                if (uri.mScheme.equalsIgnoreCase("file")) {
                    // There is a potential bug where a local file path matches
                    // another file in the local web server directory. Still
                    // "localhost" is the best pseudo domain name.
                    ret[0] = "localhost";
                }
            } else if (index == ret[0].lastIndexOf(PERIOD)) {
                // cookie host must have at least two periods
                ret[0] = PERIOD + ret[0];
            }

            if (ret[1].charAt(0) != PATH_DELIM) {
                return null;
            }

            /*
             * find cookie path, e.g. for http://www.google.com, the path is "/"
             * for http://www.google.com/lab/, the path is "/lab"
             * for http://www.google.com/lab/foo, the path is "/lab/foo"
             * for http://www.google.com/lab?hl=en, the path is "/lab"
             * for http://www.google.com/lab.asp?hl=en, the path is "/lab.asp"
             * Note: the path from URI has at least one "/"
             * See:
             * http://www.unix.com.ua/rfc/rfc2109.html
             */
            index = ret[1].indexOf(QUESTION_MARK);
            if (index != -1) {
                ret[1] = ret[1].substring(0, index);
            }

            return ret;
        } else
            return null;
    }

    /**
     * Get the base domain for a give host. E.g. mail.google.com will return
     * google.com
     * @param host The give host
     * @return the base domain
     */
    private String getBaseDomain(String host) {
        int startIndex = 0;
        int nextIndex = host.indexOf(PERIOD);
        int lastIndex = host.lastIndexOf(PERIOD);
        while (nextIndex < lastIndex) {
            startIndex = nextIndex + 1;
            nextIndex = host.indexOf(PERIOD, startIndex);
        }
        if (startIndex > 0) {
            return host.substring(startIndex);
        } else {
            return host;
        }
    }

    /**
     * parseCookie() parses the cookieString which is a comma-separated list of
     * one or more cookies in the format of "NAME=VALUE; expires=DATE;
     * path=PATH; domain=DOMAIN_NAME; secure httponly" to a list of Cookies.
     * Here is a sample: IGDND=1, IGPC=ET=UB8TSNwtDmQ:AF=0; expires=Sun,
     * 17-Jan-2038 19:14:07 GMT; path=/ig; domain=.google.com, =,
     * PREF=ID=408909b1b304593d:TM=1156459854:LM=1156459854:S=V-vCAU6Sh-gobCfO;
     * expires=Sun, 17-Jan-2038 19:14:07 GMT; path=/; domain=.google.com which
     * contains 3 cookies IGDND, IGPC, PREF and an empty cookie
     * @param host The default host
     * @param path The default path
     * @param cookieString The string coming from "Set-Cookie:"
     * @return A list of Cookies
     */
    private ArrayList<Cookie> parseCookie(String host, String path,
            String cookieString) {
        ArrayList<Cookie> ret = new ArrayList<Cookie>();

        int index = 0;
        int length = cookieString.length();
        while (true) {
            Cookie cookie = null;

            // done
            if (index < 0 || index >= length) {
                break;
            }

            // skip white space
            if (cookieString.charAt(index) == WHITE_SPACE) {
                index++;
                continue;
            }

            /*
             * get NAME=VALUE; pair. detecting the end of a pair is tricky, it
             * can be the end of a string, like "foo=bluh", it can be semicolon
             * like "foo=bluh;path=/"; or it can be enclosed by \", like
             * "foo=\"bluh bluh\";path=/"
             *
             * Note: in the case of "foo=bluh, bar=bluh;path=/", we interpret
             * it as one cookie instead of two cookies.
             */
            int semicolonIndex = cookieString.indexOf(SEMICOLON, index);
            int equalIndex = cookieString.indexOf(EQUAL, index);
            cookie = new Cookie(host, path);

            // Cookies like "testcookie; path=/;" are valid and used
            // (lovefilm.se).
            // Look for 2 cases:
            // 1. "foo" or "foo;" where equalIndex is -1
            // 2. "foo; path=..." where the first semicolon is before an equal
            //    and a semicolon exists.
            if ((semicolonIndex != -1 && (semicolonIndex < equalIndex)) ||
                    equalIndex == -1) {
                // Fix up the index in case we have a string like "testcookie"
                if (semicolonIndex == -1) {
                    semicolonIndex = length;
                }
                cookie.name = cookieString.substring(index, semicolonIndex);
                cookie.value = null;
            } else {
                cookie.name = cookieString.substring(index, equalIndex);
                // Make sure we do not throw an exception if the cookie is like
                // "foo="
                if ((equalIndex < length - 1) &&
                        (cookieString.charAt(equalIndex + 1) == QUOTATION)) {
                    index = cookieString.indexOf(QUOTATION, equalIndex + 2);
                    if (index == -1) {
                        // bad format, force return
                        break;
                    }
                }
                // Get the semicolon index again in case it was contained within
                // the quotations.
                semicolonIndex = cookieString.indexOf(SEMICOLON, index);
                if (semicolonIndex == -1) {
                    semicolonIndex = length;
                }
                if (semicolonIndex - equalIndex > MAX_COOKIE_LENGTH) {
                    // cookie is too big, trim it
                    cookie.value = cookieString.substring(equalIndex + 1,
                            equalIndex + 1 + MAX_COOKIE_LENGTH);
                } else if (equalIndex + 1 == semicolonIndex
                        || semicolonIndex < equalIndex) {
                    // this is an unusual case like "foo=;" or "foo="
                    cookie.value = "";
                } else {
                    cookie.value = cookieString.substring(equalIndex + 1,
                            semicolonIndex);
                }
            }
            // get attributes
            index = semicolonIndex;
            while (true) {
                // done
                if (index < 0 || index >= length) {
                    break;
                }

                // skip white space and semicolon
                if (cookieString.charAt(index) == WHITE_SPACE
                        || cookieString.charAt(index) == SEMICOLON) {
                    index++;
                    continue;
                }

                // comma means next cookie
                if (cookieString.charAt(index) == COMMA) {
                    index++;
                    break;
                }

                // "secure" is a known attribute doesn't use "=";
                // while sites like live.com uses "secure="
                if (length - index >= SECURE_LENGTH
                        && cookieString.substring(index, index + SECURE_LENGTH).
                        equalsIgnoreCase(SECURE)) {
                    index += SECURE_LENGTH;
                    cookie.secure = true;
                    if (index == length) break;
                    if (cookieString.charAt(index) == EQUAL) index++;
                    continue;
                }

                // "httponly" is a known attribute doesn't use "=";
                // while sites like live.com uses "httponly="
                if (length - index >= HTTP_ONLY_LENGTH
                        && cookieString.substring(index,
                            index + HTTP_ONLY_LENGTH).
                        equalsIgnoreCase(HTTP_ONLY)) {
                    index += HTTP_ONLY_LENGTH;
                    if (index == length) break;
                    if (cookieString.charAt(index) == EQUAL) index++;
                    // FIXME: currently only parse the attribute
                    continue;
                }
                equalIndex = cookieString.indexOf(EQUAL, index);
                if (equalIndex > 0) {
                    String name = cookieString.substring(index, equalIndex)
                            .toLowerCase();
                    if (name.equals(EXPIRES)) {
                        int comaIndex = cookieString.indexOf(COMMA, equalIndex);

                        // skip ',' in (Wdy, DD-Mon-YYYY HH:MM:SS GMT) or
                        // (Weekday, DD-Mon-YY HH:MM:SS GMT) if it applies.
                        // "Wednesday" is the longest Weekday which has length 9
                        if ((comaIndex != -1) &&
                                (comaIndex - equalIndex <= 10)) {
                            index = comaIndex + 1;
                        }
                    }
                    semicolonIndex = cookieString.indexOf(SEMICOLON, index);
                    int commaIndex = cookieString.indexOf(COMMA, index);
                    if (semicolonIndex == -1 && commaIndex == -1) {
                        index = length;
                    } else if (semicolonIndex == -1) {
                        index = commaIndex;
                    } else if (commaIndex == -1) {
                        index = semicolonIndex;
                    } else {
                        index = Math.min(semicolonIndex, commaIndex);
                    }
                    String value =
                            cookieString.substring(equalIndex + 1, index);
                    
                    // Strip quotes if they exist
                    if (value.length() > 2 && value.charAt(0) == QUOTATION) {
                        int endQuote = value.indexOf(QUOTATION, 1);
                        if (endQuote > 0) {
                            value = value.substring(1, endQuote);
                        }
                    }
                    if (name.equals(EXPIRES)) {
                        try {
                            cookie.expires = AndroidHttpClient.parseDate(value);
                        } catch (IllegalArgumentException ex) {
                            Log.e(LOGTAG,
                                    "illegal format for expires: " + value);
                        }
                    } else if (name.equals(MAX_AGE)) {
                        try {
                            cookie.expires = System.currentTimeMillis() + 1000
                                    * Long.parseLong(value);
                        } catch (NumberFormatException ex) {
                            Log.e(LOGTAG,
                                    "illegal format for max-age: " + value);
                        }
                    } else if (name.equals(PATH)) {
                        // only allow non-empty path value
                        if (value.length() > 0) {
                            cookie.path = value;
                        }
                    } else if (name.equals(DOMAIN)) {
                        int lastPeriod = value.lastIndexOf(PERIOD);
                        if (lastPeriod == 0) {
                            // disallow cookies set for TLDs like [.com]
                            cookie.domain = null;
                            continue;
                        }
                        try {
                            Integer.parseInt(value.substring(lastPeriod + 1));
                            // no wildcard for ip address match
                            if (!value.equals(host)) {
                                // no cross-site cookie
                                cookie.domain = null;
                            }
                            continue;
                        } catch (NumberFormatException ex) {
                            // ignore the exception, value is a host name
                        }
                        value = value.toLowerCase();
                        if (value.charAt(0) != PERIOD) {
                            // pre-pended dot to make it as a domain cookie
                            value = PERIOD + value;
                            lastPeriod++;
                        }
                        if (host.endsWith(value.substring(1))) {
                            int len = value.length();
                            int hostLen = host.length();
                            if (hostLen > (len - 1)
                                    && host.charAt(hostLen - len) != PERIOD) {
                                // make sure the bar.com doesn't match .ar.com
                                cookie.domain = null;
                                continue;
                            }
                            // disallow cookies set on ccTLDs like [.co.uk]
                            if ((len == lastPeriod + 3)
                                    && (len >= 6 && len <= 8)) {
                                String s = value.substring(1, lastPeriod);
                                if (Arrays.binarySearch(BAD_COUNTRY_2LDS, s) >= 0) {
                                    cookie.domain = null;
                                    continue;
                                }
                            }
                            cookie.domain = value;
                        } else {
                            // no cross-site or more specific sub-domain cookie
                            cookie.domain = null;
                        }
                    }
                } else {
                    // bad format, force return
                    index = length;
                }
            }
            if (cookie != null && cookie.domain != null) {
                ret.add(cookie);
            }
        }
        return ret;
    }
}
