/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static org.junit.Assert.assertThrows;

import android.os.SystemClock;
import android.text.format.DateUtils;

import junit.framework.TestCase;

public class TokenBucketTest extends TestCase {

    static final int FILL_DELTA_VERY_SHORT  = 1;
    static final int FILL_DELTA_VERY_LONG   = Integer.MAX_VALUE;

    public void testArgumentValidation() {
        assertThrow(() -> new TokenBucket(0, 1, 1));
        assertThrow(() -> new TokenBucket(1, 0, 1));
        assertThrow(() -> new TokenBucket(1, 1, 0));
        assertThrow(() -> new TokenBucket(0, 1));
        assertThrow(() -> new TokenBucket(1, 0));
        assertThrow(() -> new TokenBucket(-1, 1, 1));
        assertThrow(() -> new TokenBucket(1, -1, 1));
        assertThrow(() -> new TokenBucket(1, 1, -1));
        assertThrow(() -> new TokenBucket(-1, 1));
        assertThrow(() -> new TokenBucket(1, -1));

        new TokenBucket(1000, 100, 0);
        new TokenBucket(1000, 100, 10);
        new TokenBucket(5000, 50);
        new TokenBucket(5000, 1);
    }

    public void testInitialCapacity() {
        drain(new TokenBucket(FILL_DELTA_VERY_LONG, 1), 1);
        drain(new TokenBucket(FILL_DELTA_VERY_LONG, 10), 10);
        drain(new TokenBucket(FILL_DELTA_VERY_LONG, 1000), 1000);

        drain(new TokenBucket(FILL_DELTA_VERY_LONG, 10, 0), 0);
        drain(new TokenBucket(FILL_DELTA_VERY_LONG, 10, 3), 3);
        drain(new TokenBucket(FILL_DELTA_VERY_LONG, 10, 10), 10);

        drain(new TokenBucket(FILL_DELTA_VERY_LONG, 10, 100), 10);

        drain(new TokenBucket((int) DateUtils.MINUTE_IN_MILLIS, 50), 50);
        drain(new TokenBucket((int) DateUtils.HOUR_IN_MILLIS, 10), 10);
        drain(new TokenBucket((int) DateUtils.DAY_IN_MILLIS, 200), 200);
    }

    public void testReset() {
        TokenBucket tb = new TokenBucket(FILL_DELTA_VERY_LONG, 100, 10);
        drain(tb, 10);

        tb.reset(50);
        drain(tb, 50);

        tb.reset(50);
        getOneByOne(tb, 10);
        assertTrue(tb.has());

        tb.reset(30);
        drain(tb, 30);
    }

    public void testFill() throws Exception {
        int delta = 50;
        TokenBucket tb = new TokenBucket(delta, 10, 0);

        assertEmpty(tb);

        Thread.sleep(3 * delta / 2);

        assertTrue(tb.has());
    }

    public void testRefill() throws Exception {
        TokenBucket tb = new TokenBucket(FILL_DELTA_VERY_SHORT, 10, 10);

        assertEquals(5, tb.get(5));
        assertEquals(5, tb.get(5));

        while (tb.available() < 10) {
            Thread.sleep(2);
        }

        assertEquals(10, tb.get(10));

        while (tb.available() < 10) {
            Thread.sleep(2);
        }

        assertEquals(10, tb.get(100));
    }

    public void testAverage() throws Exception {
        final int delta = 3;
        final int want = 60;

        long start = SystemClock.elapsedRealtime();
        TokenBucket tb = new TokenBucket(delta, 20, 0);

        for (int i = 0; i < want; i++) {
            while (!tb.has()) {
                Thread.sleep(5 * delta);
            }
            tb.get();
        }

        assertDuration(want * delta, SystemClock.elapsedRealtime() - start);
    }

    public void testBurst() throws Exception {
        final int delta = 2;
        final int capacity = 20;
        final int want = 100;

        long start = SystemClock.elapsedRealtime();
        TokenBucket tb = new TokenBucket(delta, capacity, 0);

        int total = 0;
        while (total < want) {
            while (!tb.has()) {
                Thread.sleep(capacity * delta - 2);
            }
            total += tb.get(tb.available());
        }

        assertDuration(total * delta, SystemClock.elapsedRealtime() - start);
    }

    static void getOneByOne(TokenBucket tb, int n) {
        while (n > 0) {
            assertTrue(tb.has());
            assertTrue(tb.available() >= n);
            assertTrue(tb.get());
            assertTrue(tb.available() >= n - 1);
            n--;
        }
    }

    void assertEmpty(TokenBucket tb) {
        assertFalse(tb.has());
        assertEquals(0, tb.available());
        assertFalse(tb.get());
    }

    void drain(TokenBucket tb, int n) {
        getOneByOne(tb, n);
        assertEmpty(tb);
    }

    void assertDuration(long expected, long elapsed) {
        String msg = String.format(
                "expected elapsed time at least %d ms, but was %d ms", expected, elapsed);
        elapsed += 1; // one millisecond extra guard
        assertTrue(msg, elapsed >= expected);
    }

    void assertThrow(Fn fn) {
        assertThrows(Throwable.class, () -> {
            fn.call();
        });
    }

    interface Fn { void call(); }
}
