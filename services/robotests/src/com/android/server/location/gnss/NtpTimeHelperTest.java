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

package com.android.server.location.gnss;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import android.os.Looper;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.NtpTrustedTime;

import com.android.server.location.gnss.NtpTimeHelper.InjectNtpTimeCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link NtpTimeHelper}.
 */
@RunWith(RobolectricTestRunner.class)
@Presubmit
public class NtpTimeHelperTest {

    private static final long MOCK_NTP_TIME = 1519930775453L;
    @Mock
    private NtpTrustedTime mMockNtpTrustedTime;
    private NtpTimeHelper mNtpTimeHelper;
    private CountDownLatch mCountDownLatch;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mCountDownLatch = new CountDownLatch(1);
        InjectNtpTimeCallback callback =
                (time, timeReference, uncertainty) -> {
                    assertThat(time).isEqualTo(MOCK_NTP_TIME);
                    mCountDownLatch.countDown();
                };
        mNtpTimeHelper = new NtpTimeHelper(RuntimeEnvironment.application,
                Looper.myLooper(),
                callback, mMockNtpTrustedTime);
    }

    @Test
    public void handleInjectNtpTime_cachedAgeLow_injectTime() throws InterruptedException {
        NtpTrustedTime.TimeResult result = mock(NtpTrustedTime.TimeResult.class);
        doReturn(NtpTimeHelper.NTP_INTERVAL - 1).when(result).getAgeMillis();
        doReturn(MOCK_NTP_TIME).when(result).getTimeMillis();
        doReturn(result).when(mMockNtpTrustedTime).getCachedTimeResult();

        mNtpTimeHelper.retrieveAndInjectNtpTime();

        waitForTasksToBePostedOnHandlerAndRunThem();
        assertThat(mCountDownLatch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    public void handleInjectNtpTime_injectTimeFailed_injectTimeDelayed()
            throws InterruptedException {
        NtpTrustedTime.TimeResult result1 = mock(NtpTrustedTime.TimeResult.class);
        doReturn(NtpTimeHelper.NTP_INTERVAL + 1).when(result1).getAgeMillis();
        doReturn(result1).when(mMockNtpTrustedTime).getCachedTimeResult();
        doReturn(false).when(mMockNtpTrustedTime).forceRefresh();

        mNtpTimeHelper.retrieveAndInjectNtpTime();
        waitForTasksToBePostedOnHandlerAndRunThem();
        assertThat(mCountDownLatch.await(2, TimeUnit.SECONDS)).isFalse();

        doReturn(true).when(mMockNtpTrustedTime).forceRefresh();
        NtpTrustedTime.TimeResult result2 = mock(NtpTrustedTime.TimeResult.class);
        doReturn(1L).when(result2).getAgeMillis();
        doReturn(MOCK_NTP_TIME).when(result2).getTimeMillis();
        doReturn(result2).when(mMockNtpTrustedTime).getCachedTimeResult();
        SystemClock.sleep(NtpTimeHelper.RETRY_INTERVAL);

        waitForTasksToBePostedOnHandlerAndRunThem();
        assertThat(mCountDownLatch.await(2, TimeUnit.SECONDS)).isTrue();
    }

    /**
     * Since a thread is created in {@link NtpTimeHelper#retrieveAndInjectNtpTime} and the task to
     * be verified is posted in the thread, we have to wait for the task to be posted and then it
     * can be run.
     */
    private void waitForTasksToBePostedOnHandlerAndRunThem() throws InterruptedException {
        mCountDownLatch.await(1, TimeUnit.SECONDS);
        ShadowLooper.runUiThreadTasks();
    }
}

