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

package com.android.server.pm.verify.pkg;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.TimeUnit;

@Presubmit
@RunWith(AndroidJUnit4.class)
@SmallTest
public class VerificationStatusTrackerTest {
    private static final String TEST_PACKAGE_NAME = "com.foo";
    private static final long TEST_REQUEST_START_TIME = 100L;
    private static final long TEST_TIMEOUT_DURATION_MILLIS = TimeUnit.MINUTES.toMillis(1);
    private static final long TEST_TIMEOUT_EXTENDED_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final long TEST_MAX_TIMEOUT_DURATION_MILLIS =
            TimeUnit.MINUTES.toMillis(10);

    @Mock
    VerifierController.Injector mInjector;
    private VerificationStatusTracker mTracker;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        when(mInjector.getVerificationRequestTimeoutMillis()).thenReturn(
                TEST_TIMEOUT_DURATION_MILLIS);
        when(mInjector.getMaxVerificationExtendedTimeoutMillis()).thenReturn(
                TEST_MAX_TIMEOUT_DURATION_MILLIS);
        // Mock time forward as the code continues to check for the current time
        when(mInjector.getCurrentTimeMillis())
                .thenReturn(TEST_REQUEST_START_TIME)
                .thenReturn(TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS - 1)
                .thenReturn(TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS)
                .thenReturn(TEST_REQUEST_START_TIME + TEST_MAX_TIMEOUT_DURATION_MILLIS - 100)
                .thenReturn(TEST_REQUEST_START_TIME + TEST_MAX_TIMEOUT_DURATION_MILLIS);
        mTracker = new VerificationStatusTracker(TEST_TIMEOUT_DURATION_MILLIS,
                TEST_MAX_TIMEOUT_DURATION_MILLIS, mInjector);
    }

    @Test
    public void testTimeout() {
        assertThat(mTracker.getTimeoutTime()).isEqualTo(
                TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS);
        // It takes two calls to set the timeout, because the timeout time hasn't been reached for
        // the first calls
        assertThat(mTracker.isTimeout()).isFalse();
        assertThat(mTracker.isTimeout()).isTrue();
    }

    @Test
    public void testTimeoutExtended() {
        assertThat(mTracker.getTimeoutTime()).isEqualTo(
                TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS);
        assertThat(mTracker.extendTimeRemaining(TEST_TIMEOUT_EXTENDED_MILLIS))
                .isEqualTo(TEST_TIMEOUT_EXTENDED_MILLIS);
        assertThat(mTracker.getTimeoutTime()).isEqualTo(
                TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS
                        + TEST_TIMEOUT_EXTENDED_MILLIS);

        // It would take 3 calls to set the timeout, because the timeout time hasn't been reached
        // for the first 2 time checks, but querying the remaining time also does a time check.
        assertThat(mTracker.isTimeout()).isFalse();
        assertThat(mTracker.getRemainingTime()).isGreaterThan(0);
        assertThat(mTracker.isTimeout()).isTrue();
        assertThat(mTracker.getRemainingTime()).isEqualTo(0);
    }

    @Test
    public void testTimeoutExtendedExceedsMax() {
        assertThat(mTracker.getTimeoutTime()).isEqualTo(
                TEST_REQUEST_START_TIME + TEST_TIMEOUT_DURATION_MILLIS);
        assertThat(mTracker.extendTimeRemaining(TEST_MAX_TIMEOUT_DURATION_MILLIS))
                .isEqualTo(TEST_MAX_TIMEOUT_DURATION_MILLIS - TEST_TIMEOUT_DURATION_MILLIS);
        assertThat(mTracker.getTimeoutTime()).isEqualTo(
                TEST_REQUEST_START_TIME + TEST_MAX_TIMEOUT_DURATION_MILLIS);
        // It takes 4 calls to set the timeout, because the timeout time hasn't been reached for
        // the first 3 calls
        assertThat(mTracker.isTimeout()).isFalse();
        assertThat(mTracker.isTimeout()).isFalse();
        assertThat(mTracker.isTimeout()).isFalse();
        assertThat(mTracker.isTimeout()).isTrue();
        assertThat(mTracker.getRemainingTime()).isEqualTo(0);
    }
}
