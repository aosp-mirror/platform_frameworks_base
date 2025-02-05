/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test the RateLimitingCache class.
 */
@RunWith(AndroidJUnit4.class)
public class RateLimitingCacheTest {

    private int mCounter = 0;

    @Before
    public void before() {
        mCounter = -1;
    }

    RateLimitingCache.ValueFetcher<Integer> mFetcher = () -> {
        return ++mCounter;
    };

    /**
     * Test zero period passed into RateLimitingCache. A new value should be returned for each
     * time the cache's get() is invoked.
     */
    @Test
    public void testTtl_Zero() {
        TestRateLimitingCache<Integer> s = new TestRateLimitingCache<>(0);

        int first = s.get(mFetcher);
        assertEquals(first, 0);
        int second = s.get(mFetcher);
        assertEquals(second, 1);
        s.advanceTime(20);
        int third = s.get(mFetcher);
        assertEquals(third, 2);
    }

    /**
     * Test a period of 100ms passed into RateLimitingCache. A new value should not be fetched
     * any more frequently than every 100ms.
     */
    @Test
    public void testTtl_100() {
        TestRateLimitingCache<Integer> s = new TestRateLimitingCache<>(100);

        int first = s.get(mFetcher);
        assertEquals(first, 0);
        int second = s.get(mFetcher);
        // Too early to change
        assertEquals(second, 0);
        s.advanceTime(150);
        int third = s.get(mFetcher);
        // Changed by now
        assertEquals(third, 1);
        int fourth = s.get(mFetcher);
        // Too early to change again
        assertEquals(fourth, 1);
    }

    /**
     * Test a negative period passed into RateLimitingCache. A new value should only be fetched the
     * first call to get().
     */
    @Test
    public void testTtl_Negative() {
        TestRateLimitingCache<Integer> s = new TestRateLimitingCache<>(-1);

        int first = s.get(mFetcher);
        assertEquals(first, 0);
        s.advanceTime(200);
        // Should return the original value every time
        int second = s.get(mFetcher);
        assertEquals(second, 0);
    }

    /**
     * Test making tons of calls to the speed-limiter and make sure number of fetches does not
     * exceed expected number of fetches.
     */
    @Test
    public void testTtl_Spam() {
        TestRateLimitingCache<Integer> s = new TestRateLimitingCache<>(100);
        assertCount(s, 1000, 7, 15);
    }

    /**
     * Test rate-limiting across multiple periods and make sure the expected number of fetches is
     * within the specified rate.
     */
    @Test
    public void testRate_10hz() {
        TestRateLimitingCache<Integer> s = new TestRateLimitingCache<>(1000, 10);
        // At 10 per second, 2 seconds should not exceed about 30, assuming overlap into left and
        // right windows that allow 10 each
        assertCount(s, 2000, 20, 33);
    }

    /**
     * Helper to make repeated calls every 5 millis to verify the number of expected fetches for
     * the given parameters.
     * @param cache the cache object
     * @param period the period for which to make get() calls
     * @param minCount the lower end of the expected number of fetches, with a margin for error
     * @param maxCount the higher end of the expected number of fetches, with a margin for error
     */
    private void assertCount(TestRateLimitingCache<Integer> cache, long period,
            int minCount, int maxCount) {
        long startTime = cache.getTime();
        while (cache.getTime() < startTime + period) {
            int value = cache.get(mFetcher);
            cache.advanceTime(5);
        }
        int latest = cache.get(mFetcher);
        assertTrue("Latest should be between " + minCount + " and " + maxCount
                        + " but is " + latest, latest <= maxCount && latest >= minCount);
    }

    private static class TestRateLimitingCache<Value> extends RateLimitingCache<Value> {
        private long mTime;

        public TestRateLimitingCache(long periodMillis) {
            super(periodMillis);
        }

        public TestRateLimitingCache(long periodMillis, int count) {
            super(periodMillis, count);
        }

        public void advanceTime(long time) {
            mTime += time;
        }

        @Override
        public long getTime() {
            return mTime;
        }
    }
}
