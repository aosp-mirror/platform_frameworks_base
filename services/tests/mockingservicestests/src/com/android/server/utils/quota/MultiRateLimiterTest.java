/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.utils.quota;

import static com.google.common.truth.Truth.assertThat;

import android.testing.TestableContext;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;

import org.junit.Rule;
import org.junit.Test;

import java.time.Duration;

@SmallTest
public class MultiRateLimiterTest {

    private static final int USER_ID = 1;
    private static final String PACKAGE_NAME_1 = "com.android.package.one";
    private static final String PACKAGE_NAME_2 = "com.android.package.two";
    private static final String TAG = "tag";

    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext(), null);

    private final InjectorForTest mInjector = new InjectorForTest();

    private static class InjectorForTest extends QuotaTracker.Injector {
        Duration mElapsedTime = Duration.ZERO;

        @Override
        public long getElapsedRealtime() {
            return mElapsedTime.toMillis();
        }

        @Override
        public boolean isAlarmManagerReady() {
            return true;
        }
    }

    @Test
    public void testSingleRateLimit_belowLimit_isWithinQuota() {
        MultiRateLimiter multiRateLimiter = new MultiRateLimiter.Builder(mContext, mInjector)
                .addRateLimit(3, Duration.ofSeconds(20))
                .build();

        // Three quick events are within quota.
        mInjector.mElapsedTime = Duration.ZERO;
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(50);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(100);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
    }

    @Test
    public void testSingleRateLimit_aboveLimit_isNotWithinQuota() {
        MultiRateLimiter multiRateLimiter = new MultiRateLimiter.Builder(mContext, mInjector)
                .addRateLimit(3, Duration.ofSeconds(20))
                .build();

        mInjector.mElapsedTime = Duration.ZERO;
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(50);
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(100);
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(150);
        // We hit the limit, 4th event in under 20 seconds is not within quota.
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isFalse();
    }

    @Test
    public void testSingleRateLimit_afterGoingAboveQuotaAndWaitingWindow_isBackWithinQuota() {
        MultiRateLimiter multiRateLimiter = new MultiRateLimiter.Builder(mContext, mInjector)
                .addRateLimit(3, Duration.ofSeconds(20))
                .build();

        mInjector.mElapsedTime = Duration.ZERO;
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(50);
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(100);
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(150);
        // We hit the limit, 4th event in under 20 seconds is not within quota.
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isFalse();

        mInjector.mElapsedTime = Duration.ofSeconds(21);
        // 20 seconds have passed, we're again within quota.
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
    }

    @Test
    public void createMultipleRateLimits_testTheyLimitsAsExpected() {
        MultiRateLimiter multiRateLimiter = new MultiRateLimiter.Builder(mContext, mInjector)
                .addRateLimit(3, Duration.ofSeconds(20)) // 1st limit
                .addRateLimit(4, Duration.ofSeconds(40)) // 2nd limit
                .addRateLimit(5, Duration.ofSeconds(60)) // 3rd limit
                .build();

        // Testing the 1st limit
        mInjector.mElapsedTime = Duration.ZERO;
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(50);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(100);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(150);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isFalse();

        mInjector.mElapsedTime = Duration.ofSeconds(21);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        // Testing the 2nd limit
        mInjector.mElapsedTime = Duration.ofSeconds(35);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isFalse();

        mInjector.mElapsedTime = Duration.ofSeconds(42);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        // Testing the 3rd limit.
        mInjector.mElapsedTime = Duration.ofSeconds(43);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isFalse();

        mInjector.mElapsedTime = Duration.ofSeconds(62);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);
    }

    @Test
    public void createSingleRateLimit_testItLimitsOnlyGivenUptc() {
        MultiRateLimiter multiRateLimiter = new MultiRateLimiter.Builder(mContext, mInjector)
                .addRateLimit(3, Duration.ofSeconds(20))
                .build();

        mInjector.mElapsedTime = Duration.ZERO;
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_2, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(50);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_2, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(100);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_2, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofMillis(150);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isFalse();
        // Different userId - packageName - tag combination is still allowed.
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_2, TAG)).isTrue();

        mInjector.mElapsedTime = Duration.ofSeconds(21);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_2, TAG)).isTrue();
    }

    @Test
    public void clearRateLimiterForPackage_afterReachingQuota_quotaIsReset() {
        MultiRateLimiter multiRateLimiter = new MultiRateLimiter.Builder(mContext, mInjector)
                .addRateLimit(1, Duration.ofSeconds(100))
                .build();

        mInjector.mElapsedTime = Duration.ZERO;
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);

        mInjector.mElapsedTime = Duration.ofSeconds(1);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isFalse();

        multiRateLimiter.clear(USER_ID, PACKAGE_NAME_1);

        // Quota for that package is reset.
        mInjector.mElapsedTime = Duration.ofSeconds(1);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isTrue();

        // Quota is enforced again.
        mInjector.mElapsedTime = Duration.ofSeconds(1);
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_1, TAG);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_1, TAG)).isFalse();
    }

    @Test
    public void clearRateLimiterForPackage_doesntAffectOtherPackages() {
        MultiRateLimiter multiRateLimiter = new MultiRateLimiter.Builder(mContext, mInjector)
                .addRateLimit(1, Duration.ofSeconds(100))
                .build();

        mInjector.mElapsedTime = Duration.ZERO;
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_2, TAG)).isTrue();
        multiRateLimiter.noteEvent(USER_ID, PACKAGE_NAME_2, TAG);

        mInjector.mElapsedTime = Duration.ofSeconds(1);
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_2, TAG)).isFalse();

        multiRateLimiter.clear(USER_ID, PACKAGE_NAME_1);

        // Doesn't affect the other package.
        assertThat(multiRateLimiter.isWithinQuota(USER_ID, PACKAGE_NAME_2, TAG)).isFalse();
    }
}
