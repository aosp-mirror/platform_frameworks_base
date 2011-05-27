/*
 * Copyright (C) 2011 The Android Open Source Project
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

package android.net.http;

import java.io.File;
import java.net.CacheRequest;
import java.net.CacheResponse;
import java.net.ResponseCache;
import java.net.URI;
import java.net.URLConnection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import junit.framework.TestCase;

public final class HttpResponseCacheTest extends TestCase {

    private File cacheDir;

    @Override public void setUp() throws Exception {
        super.setUp();
        String tmp = System.getProperty("java.io.tmpdir");
        cacheDir = new File(tmp, "HttpCache-" + UUID.randomUUID());
    }

    @Override protected void tearDown() throws Exception {
        ResponseCache.setDefault(null);
        super.tearDown();
    }

    public void testInstall() throws Exception {
        HttpResponseCache installed = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
        assertNotNull(installed);
        assertSame(installed, ResponseCache.getDefault());
        assertSame(installed, HttpResponseCache.getDefault());
    }

    public void testSecondEquivalentInstallDoesNothing() throws Exception {
        HttpResponseCache first = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
        HttpResponseCache another = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
        assertSame(first, another);
    }

    public void testInstallClosesPreviouslyInstalled() throws Exception {
        HttpResponseCache first = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
        HttpResponseCache another = HttpResponseCache.install(cacheDir, 8 * 1024 * 1024);
        assertNotSame(first, another);
        try {
            first.flush();
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    public void testGetInstalledWithWrongTypeInstalled() {
        ResponseCache.setDefault(new ResponseCache() {
            @Override public CacheResponse get(URI uri, String requestMethod,
                    Map<String, List<String>> requestHeaders) {
                return null;
            }
            @Override public CacheRequest put(URI uri, URLConnection connection) {
                return null;
            }
        });
        assertNull(HttpResponseCache.getInstalled());
    }

    public void testCloseCloses() throws Exception {
        HttpResponseCache cache = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
        cache.close();
        try {
            cache.flush();
            fail();
        } catch (IllegalStateException expected) {
        }
    }

    public void testCloseUninstalls() throws Exception {
        HttpResponseCache cache = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
        cache.close();
        assertNull(ResponseCache.getDefault());
    }

    public void testDeleteUninstalls() throws Exception {
        HttpResponseCache cache = HttpResponseCache.install(cacheDir, 10 * 1024 * 1024);
        cache.delete();
        assertNull(ResponseCache.getDefault());
    }
}
