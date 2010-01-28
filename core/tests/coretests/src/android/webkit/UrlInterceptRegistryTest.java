/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.test.AndroidTestCase;
import android.util.Log;
import android.webkit.CacheManager.CacheResult;
import android.webkit.PluginData;
import android.webkit.UrlInterceptHandler;

import java.util.LinkedList;
import java.util.Map;

public class UrlInterceptRegistryTest extends AndroidTestCase {

    /**
     * To run these tests: $ mmm
     * frameworks/base/tests/CoreTests/android && adb remount && adb
     * sync $ adb shell am instrument -w  -e class \
     * android.webkit.UrlInterceptRegistryTest \
     * android.core/android.test.InstrumentationTestRunner
     */

    private static class MockUrlInterceptHandler implements UrlInterceptHandler {
        private PluginData mData;
        private String mUrl;

        public MockUrlInterceptHandler(PluginData data, String url) {
            mData = data;
            mUrl = url;
        }

        public CacheResult service(String url, Map<String, String> headers) {
            return null;
        }

        public PluginData getPluginData(String url,
                                        Map<String,
                                        String> headers) {
            if (mUrl.equals(url)) {
                return mData;
            }

            return null;
        }
    }

    public void testGetPluginData() {
        PluginData data = new PluginData(null, 0 , null, 200);
        String url = new String("url1");
        MockUrlInterceptHandler handler1 =
                new MockUrlInterceptHandler(data, url);

        data = new PluginData(null, 0 , null, 404);
        url = new String("url2");
        MockUrlInterceptHandler handler2 =
                new MockUrlInterceptHandler(data, url);

        assertTrue(UrlInterceptRegistry.registerHandler(handler1));
        assertTrue(UrlInterceptRegistry.registerHandler(handler2));

        data = UrlInterceptRegistry.getPluginData("url1", null);
        assertTrue(data != null);
        assertTrue(data.getStatusCode() == 200);

        data = UrlInterceptRegistry.getPluginData("url2", null);
        assertTrue(data != null);
        assertTrue(data.getStatusCode() == 404);

        assertTrue(UrlInterceptRegistry.unregisterHandler(handler1));
        assertTrue(UrlInterceptRegistry.unregisterHandler(handler2));

    }
}
