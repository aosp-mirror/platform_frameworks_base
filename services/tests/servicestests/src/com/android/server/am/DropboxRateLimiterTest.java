/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.am;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;

import org.junit.Before;
import org.junit.Test;

/**
 * Test class for {@link DropboxRateLimiter}.
 *
 * Build/Install/Run:
 *  atest DropboxRateLimiterTest
 */
public class DropboxRateLimiterTest {
    private DropboxRateLimiter mRateLimiter;
    private TestClock mClock;

    @Before
    public void setUp() {
        mClock = new TestClock();
        mRateLimiter = new DropboxRateLimiter(mClock);
    }

    @Test
    public void testMultipleProcesses() {
        // The first 5 entries should not be rate limited.
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
        // Different processes and tags should not get rate limited either.
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process2"));
        assertFalse(mRateLimiter.shouldRateLimit("tag2", "process"));
        // The 6th entry of the same process should be rate limited.
        assertTrue(mRateLimiter.shouldRateLimit("tag", "process"));
    }

    @Test
    public void testBufferClearing() throws Exception {
        // The first 5 entries should not be rate limited.
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
        // The 6th entry of the same process should be rate limited.
        assertTrue(mRateLimiter.shouldRateLimit("tag", "process"));

        // After 11 seconds there should be nothing left in the buffer and the same type of entry
        // should not get rate limited anymore.
        mClock.setOffsetMillis(11000);

        assertFalse(mRateLimiter.shouldRateLimit("tag", "process"));
    }

    private static class TestClock implements DropboxRateLimiter.Clock {
        long mOffsetMillis = 0L;

        public long uptimeMillis() {
            return mOffsetMillis + SystemClock.uptimeMillis();
        }

        public void setOffsetMillis(long millis) {
            mOffsetMillis = millis;
        }
    }
}
