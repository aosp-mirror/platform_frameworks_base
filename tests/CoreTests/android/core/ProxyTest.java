/*
 * Copyright (C) 2010 The Android Open Source Project
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

package android.core;

import org.apache.http.HttpHost;

import android.content.Context;
import android.net.Proxy;
import android.test.AndroidTestCase;

/**
 * Proxy tests
 */
public class ProxyTest extends AndroidTestCase {
    private Context mContext;
    private HttpHost mHttpHost;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mContext = getContext();
        mHttpHost = null;
        String proxyHost = Proxy.getHost(mContext);
        int proxyPort = Proxy.getPort(mContext);
        if (proxyHost != null) {
            mHttpHost = new HttpHost(proxyHost, proxyPort, "http");
        }
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();
    }

    /**
     * Bad url parameter should not cause any exception.
     */
    public void testProxyGetPreferredHttpHost_UrlBad() throws Exception {
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, null));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, ""));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "bad:"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "bad"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "bad:\\"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "bad://#"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "://#"));
    }

    /**
     * Proxy (if available) should be returned when url parameter is not localhost.
     */
    public void testProxyGetPreferredHttpHost_UrlNotlLocalhost() throws Exception {
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "http://"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "http://example.com"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "http://example.com/"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "http://192.168.0.1/"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "file:///foo/bar"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "rtsp://example.com"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "rtsp://example.com/"));
        assertEquals(mHttpHost, Proxy.getPreferredHttpHost(mContext, "javascript:alert(1)"));
    }

    /**
     * No proxy should be returned when url parameter is localhost.
     */
    public void testProxyGetPreferredHttpHost_UrlLocalhost() throws Exception {
        assertNull(Proxy.getPreferredHttpHost(mContext, "http://localhost"));
        assertNull(Proxy.getPreferredHttpHost(mContext, "http://localhost/"));
        assertNull(Proxy.getPreferredHttpHost(mContext, "http://localhost/hej.html"));
        assertNull(Proxy.getPreferredHttpHost(mContext, "http://127.0.0.1"));
        assertNull(Proxy.getPreferredHttpHost(mContext, "http://127.0.0.1/"));
        assertNull(Proxy.getPreferredHttpHost(mContext, "http://127.0.0.1/hej.html"));
        assertNull(Proxy.getPreferredHttpHost(mContext, "http://127.0.0.1:80/"));
        assertNull(Proxy.getPreferredHttpHost(mContext, "http://127.0.0.1:8080/"));
        assertNull(Proxy.getPreferredHttpHost(mContext, "rtsp://127.0.0.1/"));
        assertNull(Proxy.getPreferredHttpHost(mContext, "rtsp://localhost/"));
        assertNull(Proxy.getPreferredHttpHost(mContext, "https://localhost/"));
    }
}
