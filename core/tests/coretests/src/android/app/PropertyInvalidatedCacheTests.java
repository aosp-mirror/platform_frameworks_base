/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Test;

/**
 * Test for verifying the behavior of {@link PropertyInvalidatedCache}.  This test does
 * not use any actual binder calls - it is entirely self-contained.  This test also relies
 * on the test mode of {@link PropertyInvalidatedCache} because Android SELinux rules do
 * not grant test processes the permission to set system properties.
 * <p>
 * Build/Install/Run:
 *  atest FrameworksCoreTests:PropertyInvalidatedCacheTests
 */
@SmallTest
public class PropertyInvalidatedCacheTests {

    // This property is never set.  The test process does not have permission to set any
    // properties.
    static final String CACHE_PROPERTY = "cache_key.cache_test_a";

    // This class is a proxy for binder calls.  It contains a counter that increments
    // every time the class is queried.
    private static class ServerProxy {
        // The number of times this class was queried.
        private int mCount = 0;

        // A single query.  The key behavior is that the query count is incremented.
        boolean query(int x) {
            mCount++;
            return value(x);
        }

        // Return the expected value of an input, without incrementing the query count.
        boolean value(int x) {
            return x % 3 == 0;
        }

        // Verify the count.
        void verify(int x) {
            assertEquals(x, mCount);
        }
    }

    // Clear the test mode after every test, in case this process is used for other
    // tests. This also resets the test property map.
    @After
    public void tearDown() throws Exception {
        PropertyInvalidatedCache.setTestMode(false);
    }

    // This test is disabled pending an sepolicy change that allows any app to set the
    // test property.
    @Test
    public void testBasicCache() {

        // A stand-in for the binder.  The test verifies that calls are passed through to
        // this class properly.
        ServerProxy tester = new ServerProxy();

        // Create a cache that uses simple arithmetic to computer its values.
        PropertyInvalidatedCache<Integer, Boolean> testCache =
                new PropertyInvalidatedCache<>(4, CACHE_PROPERTY) {
                    @Override
                    public Boolean recompute(Integer x) {
                        return tester.query(x);
                    }
                    @Override
                    public boolean bypass(Integer x) {
                        return x % 13 == 0;
                    }
                };

        PropertyInvalidatedCache.setTestMode(true);
        PropertyInvalidatedCache.testPropertyName(CACHE_PROPERTY);

        tester.verify(0);
        assertEquals(tester.value(3), testCache.query(3));
        tester.verify(1);
        assertEquals(tester.value(3), testCache.query(3));
        tester.verify(2);
        testCache.invalidateCache();
        assertEquals(tester.value(3), testCache.query(3));
        tester.verify(3);
        assertEquals(tester.value(5), testCache.query(5));
        tester.verify(4);
        assertEquals(tester.value(5), testCache.query(5));
        tester.verify(4);
        assertEquals(tester.value(3), testCache.query(3));
        tester.verify(4);

        // Invalidate the cache, and verify that the next read on 3 goes to the server.
        testCache.invalidateCache();
        assertEquals(tester.value(3), testCache.query(3));
        tester.verify(5);

        // Test bypass.  The query for 13 always bypasses the cache.
        assertEquals(tester.value(12), testCache.query(12));
        assertEquals(tester.value(13), testCache.query(13));
        assertEquals(tester.value(14), testCache.query(14));
        tester.verify(8);
        assertEquals(tester.value(12), testCache.query(12));
        assertEquals(tester.value(13), testCache.query(13));
        assertEquals(tester.value(14), testCache.query(14));
        tester.verify(9);
    }

    @Test
    public void testDisableCache() {

        // A stand-in for the binder.  The test verifies that calls are passed through to
        // this class properly.
        ServerProxy tester = new ServerProxy();

        // Three caches, all using the same system property but one uses a different name.
        PropertyInvalidatedCache<Integer, Boolean> cache1 =
                new PropertyInvalidatedCache<>(4, CACHE_PROPERTY) {
                    @Override
                    public Boolean recompute(Integer x) {
                        return tester.query(x);
                    }
                };
        PropertyInvalidatedCache<Integer, Boolean> cache2 =
                new PropertyInvalidatedCache<>(4, CACHE_PROPERTY) {
                    @Override
                    public Boolean recompute(Integer x) {
                        return tester.query(x);
                    }
                };
        PropertyInvalidatedCache<Integer, Boolean> cache3 =
                new PropertyInvalidatedCache<>(4, CACHE_PROPERTY, "cache3") {
                    @Override
                    public Boolean recompute(Integer x) {
                        return tester.query(x);
                    }
                };

        // Caches are enabled upon creation.
        assertEquals(false, cache1.getDisabledState());
        assertEquals(false, cache2.getDisabledState());
        assertEquals(false, cache3.getDisabledState());

        // Disable the cache1 instance.  Only cache1 is disabled
        cache1.disableInstance();
        assertEquals(true, cache1.getDisabledState());
        assertEquals(false, cache2.getDisabledState());
        assertEquals(false, cache3.getDisabledState());

        // Disable cache1.  This will disable cache1 and cache2 because they share the
        // same name.  cache3 has a different name and will not be disabled.
        cache1.disableLocal();
        assertEquals(true, cache1.getDisabledState());
        assertEquals(true, cache2.getDisabledState());
        assertEquals(false, cache3.getDisabledState());

        // Create a new cache1.  Verify that the new instance is disabled.
        cache1 = new PropertyInvalidatedCache<>(4, CACHE_PROPERTY) {
                    @Override
                    public Boolean recompute(Integer x) {
                        return tester.query(x);
                    }
                };
        assertEquals(true, cache1.getDisabledState());

        // Remove the record of caches being locally disabled.  This is a clean-up step.
        cache1.clearDisableLocal();
        assertEquals(true, cache1.getDisabledState());
        assertEquals(true, cache2.getDisabledState());
        assertEquals(false, cache3.getDisabledState());

        // Create a new cache1.  Verify that the new instance is not disabled.
        cache1 = new PropertyInvalidatedCache<>(4, CACHE_PROPERTY) {
                    @Override
                    public Boolean recompute(Integer x) {
                        return tester.query(x);
                    }
                };
        assertEquals(false, cache1.getDisabledState());
    }

    private static final String UNSET_KEY = "Aiw7woh6ie4toh7W";

    private static class TestCache extends PropertyInvalidatedCache<Integer, String> {
        TestCache() {
            this(CACHE_PROPERTY);
        }

        TestCache(String key) {
            super(4, key);
            setTestMode(true);
            testPropertyName(key);
        }

        @Override
        public String recompute(Integer qv) {
            mRecomputeCount += 1;
            return "foo" + qv.toString();
        }

        int getRecomputeCount() {
            return mRecomputeCount;
        }

        private int mRecomputeCount = 0;
    }

    @Test
    public void testCacheRecompute() {
        TestCache cache = new TestCache();
        cache.invalidateCache();
        assertEquals(cache.isDisabledLocal(), false);
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

    @Test
    public void testCacheInitialState() {
        TestCache cache = new TestCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(2, cache.getRecomputeCount());
        cache.invalidateCache();
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(3, cache.getRecomputeCount());
    }

    @Test
    public void testCachePropertyUnset() {
        TestCache cache = new TestCache(UNSET_KEY);
        assertEquals("foo5", cache.query(5));
        assertEquals("foo5", cache.query(5));
        assertEquals(2, cache.getRecomputeCount());
    }

    @Test
    public void testCacheDisableState() {
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

    @Test
    public void testRefreshSameObject() {
        int[] refreshCount = new int[1];
        TestCache cache = new TestCache() {
            @Override
            public String refresh(String oldResult, Integer query) {
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

    @Test
    public void testRefreshInvalidateRace() {
        int[] refreshCount = new int[1];
        TestCache cache = new TestCache() {
            @Override
            public String refresh(String oldResult, Integer query) {
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

    @Test
    public void testLocalProcessDisable() {
        TestCache cache = new TestCache();
        assertEquals(cache.isDisabledLocal(), false);
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
