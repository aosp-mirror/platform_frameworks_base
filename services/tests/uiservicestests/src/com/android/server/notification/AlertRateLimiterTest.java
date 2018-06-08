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
package com.android.server.notification;

import static com.android.server.notification.AlertRateLimiter.ALLOWED_ALERT_INTERVAL;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class AlertRateLimiterTest extends UiServiceTestCase {

    private long mTestStartTime;
    private
    AlertRateLimiter mLimiter;

    @Before
    public void setUp() {
        mTestStartTime = 1225731600000L;
        mLimiter = new AlertRateLimiter();
    }

    @Test
    public void testFirstAlertAllowed() throws Exception {
        assertFalse(mLimiter.shouldRateLimitAlert(mTestStartTime));
    }

    @Test
    public void testAllowedAfterSecond() throws Exception {
        assertFalse(mLimiter.shouldRateLimitAlert(mTestStartTime));
        assertFalse(mLimiter.shouldRateLimitAlert(mTestStartTime + ALLOWED_ALERT_INTERVAL));
    }

    @Test
    public void testAllowedAfterSecondEvenWithBlockedEntries() throws Exception {
        assertFalse(mLimiter.shouldRateLimitAlert(mTestStartTime));
        assertTrue(mLimiter.shouldRateLimitAlert(mTestStartTime + ALLOWED_ALERT_INTERVAL - 1));
        assertFalse(mLimiter.shouldRateLimitAlert(mTestStartTime + ALLOWED_ALERT_INTERVAL));
    }

    @Test
    public void testAllowedDisallowedBeforeSecond() throws Exception {
        assertFalse(mLimiter.shouldRateLimitAlert(mTestStartTime));
        assertTrue(mLimiter.shouldRateLimitAlert(mTestStartTime + ALLOWED_ALERT_INTERVAL - 1));
    }

    @Test
    public void testDisallowedTimePast() throws Exception {
        assertFalse(mLimiter.shouldRateLimitAlert(mTestStartTime));
        assertTrue(mLimiter.shouldRateLimitAlert(mTestStartTime - ALLOWED_ALERT_INTERVAL));
    }
}
