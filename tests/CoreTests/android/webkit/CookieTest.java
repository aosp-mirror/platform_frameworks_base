/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.test.AndroidTestCase;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;

public class CookieTest extends AndroidTestCase {

    /**
     * To run these tests: $ mmm frameworks/base/tests/CoreTests/android && adb
     * remount && adb sync $ adb shell am instrument -w \ -e class
     * android.webkit.CookieTest \
     * android.core/android.test.InstrumentationTestRunner
     */

    private CookieManager mCookieManager;

    @Override
    public void setContext(Context context) {
        assertTrue(mContext == null);
        super.setContext(context);
        CookieSyncManager.createInstance(context);
        mCookieManager = CookieManager.getInstance();
        mCookieManager.removeAllCookie();
    }

    public void testParse() {
        mCookieManager.removeAllCookie();
        String url = "http://www.foo.com";

        // basic
        mCookieManager.setCookie(url, "a=b");
        String cookie = mCookieManager.getCookie(url);
        assertTrue(cookie.equals("a=b"));

        // quoted
        mCookieManager.setCookie(url, "c=\"d;\"");
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie.equals("a=b; c=\"d;\""));
    }

    public void testDomain() {
        mCookieManager.removeAllCookie();
        String url = "http://www.foo.com";

        // basic
        mCookieManager.setCookie(url, "a=b");
        String cookie = mCookieManager.getCookie(url);
        assertTrue(cookie.equals("a=b"));

        // no cross domain cookie
        cookie = mCookieManager.getCookie("http://bar.com");
        assertTrue(cookie == null);

        // more than one cookie
        mCookieManager.setCookie(url, "c=d; domain=.foo.com");
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie.equals("a=b; c=d"));

        // host cookie should not be accessible from a sub-domain.
        cookie = mCookieManager.getCookie("http://bar.www.foo.com");
        assertTrue(cookie.equals("c=d"));

        // test setting a domain= that doesn't start w/ a dot, should
        // treat it as a domain cookie, as if there was a pre-pended dot.
        mCookieManager.setCookie(url, "e=f; domain=www.foo.com");
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie.equals("a=b; c=d; e=f"));
        cookie = mCookieManager.getCookie("http://sub.www.foo.com");
        assertTrue(cookie.equals("c=d; e=f"));
        cookie = mCookieManager.getCookie("http://foo.com");
        assertTrue(cookie.equals("c=d"));
    }

    public void testSubDomain() {
        mCookieManager.removeAllCookie();
        String url_abcd = "http://a.b.c.d.com";
        String url_bcd = "http://b.c.d.com";
        String url_cd = "http://c.d.com";
        String url_d = "http://d.com";

        mCookieManager.setCookie(url_abcd, "a=1; domain=.a.b.c.d.com");
        mCookieManager.setCookie(url_abcd, "b=2; domain=.b.c.d.com");
        mCookieManager.setCookie(url_abcd, "c=3; domain=.c.d.com");
        mCookieManager.setCookie(url_abcd, "d=4; domain=.d.com");

        String cookie = mCookieManager.getCookie(url_abcd);
        assertTrue(cookie.equals("a=1; b=2; c=3; d=4"));
        cookie = mCookieManager.getCookie(url_bcd);
        assertTrue(cookie.equals("b=2; c=3; d=4"));
        cookie = mCookieManager.getCookie(url_cd);
        assertTrue(cookie.equals("c=3; d=4"));
        cookie = mCookieManager.getCookie(url_d);
        assertTrue(cookie.equals("d=4"));

        // check that the same cookie can exist on different sub-domains.
        mCookieManager.setCookie(url_bcd, "x=bcd; domain=.b.c.d.com");
        mCookieManager.setCookie(url_bcd, "x=cd; domain=.c.d.com");
        cookie = mCookieManager.getCookie(url_bcd);
        assertTrue(cookie.equals("b=2; c=3; d=4; x=bcd; x=cd"));
        cookie = mCookieManager.getCookie(url_cd);
        assertTrue(cookie.equals("c=3; d=4; x=cd"));
    }

    public void testInvalidDomain() {
        mCookieManager.removeAllCookie();
        String url = "http://foo.bar.com";

        mCookieManager.setCookie(url, "a=1; domain=.yo.foo.bar.com");
        String cookie = mCookieManager.getCookie(url);
        assertTrue(cookie == null);

        mCookieManager.setCookie(url, "b=2; domain=.foo.com");
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie == null);

        mCookieManager.setCookie(url, "c=3; domain=.bar.foo.com");
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie == null);

        mCookieManager.setCookie(url, "d=4; domain=.foo.bar.com.net");
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie == null);

        mCookieManager.setCookie(url, "e=5; domain=.ar.com");
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie == null);

        mCookieManager.setCookie(url, "f=6; domain=.com");
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie == null);

        mCookieManager.setCookie(url, "g=7; domain=.co.uk");
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie == null);

        mCookieManager.setCookie(url, "h=8; domain=.foo.bar.com.com");
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie == null);
    }

    public void testPath() {
        mCookieManager.removeAllCookie();
        String url = "http://www.foo.com";

        mCookieManager.setCookie(url, "a=b; path=/wee");
        String cookie = mCookieManager.getCookie(url + "/wee");
        assertTrue(cookie.equals("a=b"));
        cookie = mCookieManager.getCookie(url + "/wee/");
        assertTrue(cookie.equals("a=b"));
        cookie = mCookieManager.getCookie(url + "/wee/hee");
        assertTrue(cookie.equals("a=b"));
        cookie = mCookieManager.getCookie(url + "/wee/hee/more");
        assertTrue(cookie.equals("a=b"));
        cookie = mCookieManager.getCookie(url + "/weehee");
        assertTrue(cookie == null);
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie == null);

        mCookieManager.setCookie(url, "a=c; path=");
        cookie = mCookieManager.getCookie(url + "/wee");
        assertTrue(cookie.equals("a=b; a=c"));
        cookie = mCookieManager.getCookie(url);
        assertTrue(cookie.equals("a=c"));
    }
}
