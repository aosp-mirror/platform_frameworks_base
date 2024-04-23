/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.selinux;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.internal.os.Clock;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

@RunWith(AndroidJUnit4.class)
public class RateLimiterTest {

    private final MockClock mMockClock = new MockClock();

    @Test
    public void testRateLimiter_1QPS() {
        RateLimiter rateLimiter = new RateLimiter(mMockClock, Duration.ofSeconds(1));

        // First acquire is granted.
        assertThat(rateLimiter.tryAcquire()).isTrue();
        // Next acquire is negated because it's too soon.
        assertThat(rateLimiter.tryAcquire()).isFalse();
        // Wait >=1 seconds.
        mMockClock.currentTimeMillis += Duration.ofSeconds(1).toMillis();
        assertThat(rateLimiter.tryAcquire()).isTrue();
    }

    @Test
    public void testRateLimiter_3QPS() {
        RateLimiter rateLimiter =
                new RateLimiter(
                        mMockClock,
                        Duration.ofSeconds(1).dividedBy(3).truncatedTo(ChronoUnit.MILLIS));

        assertThat(rateLimiter.tryAcquire()).isTrue();
        mMockClock.currentTimeMillis += Duration.ofSeconds(1).dividedBy(2).toMillis();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        mMockClock.currentTimeMillis += Duration.ofSeconds(1).dividedBy(3).toMillis();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        mMockClock.currentTimeMillis += Duration.ofSeconds(1).dividedBy(4).toMillis();
        assertThat(rateLimiter.tryAcquire()).isFalse();
    }

    @Test
    public void testRateLimiter_infiniteQPS() {
        RateLimiter rateLimiter = new RateLimiter(mMockClock, Duration.ofMillis(0));

        // so many permits.
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();

        mMockClock.currentTimeMillis += Duration.ofSeconds(10).toMillis();
        // still so many permits.
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();

        mMockClock.currentTimeMillis += Duration.ofDays(-10).toMillis();
        // only going backwards in time you will stop the permits.
        assertThat(rateLimiter.tryAcquire()).isFalse();
        assertThat(rateLimiter.tryAcquire()).isFalse();
        assertThat(rateLimiter.tryAcquire()).isFalse();
    }

    @Test
    public void testRateLimiter_negativeQPS() {
        RateLimiter rateLimiter = new RateLimiter(mMockClock, Duration.ofMillis(-10));

        // Negative QPS is effectively turning of the rate limiter.
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        mMockClock.currentTimeMillis += Duration.ofSeconds(1000).toMillis();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
        assertThat(rateLimiter.tryAcquire()).isTrue();
    }

    private static final class MockClock extends Clock {

        public long currentTimeMillis = 0;

        @Override
        public long currentTimeMillis() {
            return currentTimeMillis;
        }
    }
}
