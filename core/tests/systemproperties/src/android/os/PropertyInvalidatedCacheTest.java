/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.os;

import android.app.PropertyInvalidatedCache;
import android.test.suitebuilder.annotation.SmallTest;

import junit.framework.TestCase;

public class PropertyInvalidatedCacheTest extends TestCase {
    private static final String KEY = "sys.testkey";
    private static final String UNSET_KEY = "Aiw7woh6ie4toh7W";

    private static class TestCache extends PropertyInvalidatedCache<Integer, String> {
        TestCache() {
            this(KEY);
        }

        TestCache(String key) {
            super(4, key);
        }

        @Override
        protected String recompute(Integer qv) {
            mRecomputeCount += 1;
            return "foo" + qv.toString();
        }

        int getRecomputeCount() {
            return mRecomputeCount;
        }

        private int mRecomputeCount = 0;
    }

    @Override
    protected void setUp() {
        SystemProperties.set(KEY, "");
    }

    @SmallTest
    public void testCacheRecompute() throws Exception {
        TestCache cache = new TestCache();
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals(1, cache.getRecomputeCount());
        assertEquals("foo5", cache.query(5));
        assertEquals(1, cache.getRecomputeCount());
        assertEquals("foo6", cache.query(6));
        assertEquals(2, cache.getRecomputeCount());
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(3, cache.getRecomputeCount());
    }

    @SmallTest
    public void testCacheInitialState() throws Exception {
        TestCache cache = new TestCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(2, cache.getRecomputeCount());
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(3, cache.getRecomputeCount());
    }

    @SmallTest
    public void testCachePropertyUnset() throws Exception {
        TestCache cache = new TestCache(UNSET_KEY);
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(2, cache.getRecomputeCount());
    }

    @SmallTest
    public void testCacheDisableState() throws Exception {
        TestCache cache = new TestCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(2, cache.getRecomputeCount());
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(3, cache.getRecomputeCount());
        cache.disableSystemWide();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(5, cache.getRecomputeCount());
        cache.invalidateCache();  // Should not reenable
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(7, cache.getRecomputeCount());
    }

    @SmallTest
    public void testRefreshSameObject() throws Exception {
        int[] refreshCount = new int[1];
        TestCache cache = new TestCache() {
            @Override
            protected String refresh(String oldResult, Integer query) {
                refreshCount[0] += 1;
                return oldResult;
            }
        };
        cache.invalidateCache();
        String result1 = cache.query(5);
        assertEquals("foo5", result1);
        String result2 = cache.query(5);
        assertSame(result1, result2);
        assertEquals(1, cache.getRecomputeCount());
        assertEquals(1, refreshCount[0]);
        assertEquals("foo5", cache.query(5));
        assertEquals(2, refreshCount[0]);
    }

    @SmallTest
    public void testRefreshInvalidateRace() throws Exception {
        int[] refreshCount = new int[1];
        TestCache cache = new TestCache() {
            @Override
            protected String refresh(String oldResult, Integer query) {
                refreshCount[0] += 1;
                invalidateCache();
                return new String(oldResult);
            }
        };
        cache.invalidateCache();
        String result1 = cache.query(5);
        assertEquals("foo5", result1);
        String result2 = cache.query(5);
        assertEquals(result1, result2);
        assertNotSame(result1, result2);
        assertEquals(2, cache.getRecomputeCount());
    }

    @SmallTest
    public void testLocalProcessDisable() throws Exception {
        TestCache cache = new TestCache();
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals(1, cache.getRecomputeCount());
        assertEquals("foo5", cache.query(5));
        assertEquals(1, cache.getRecomputeCount());
        assertEquals(cache.isDisabledLocal(), false);
        cache.disableLocal();
        assertEquals(cache.isDisabledLocal(), true);
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(3, cache.getRecomputeCount());
    }

}
