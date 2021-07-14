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

package com.android.server.usage;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;

import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManagerInternal;
import android.content.Context;
import android.os.RemoteException;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class UsageStatsServiceTest {
    private static final long TIMEOUT = 5000;

    private UsageStatsService mService;

    private MockitoSession mMockingSession;
    @Mock
    private Context mContext;

    private static class TestInjector extends UsageStatsService.Injector {
        AppStandbyInternal getAppStandbyController(Context context) {
            return mock(AppStandbyInternal.class);
        }
    }

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .strictness(Strictness.LENIENT)
                .startMocking();
        IActivityManager activityManager = ActivityManager.getService();
        spyOn(activityManager);
        try {
            doNothing().when(activityManager).registerUidObserver(any(), anyInt(), anyInt(), any());
        } catch (RemoteException e) {
            fail("registerUidObserver threw exception: " + e.getMessage());
        }
        mService = new UsageStatsService(mContext, new TestInjector());
        spyOn(mService);
        doNothing().when(mService).publishBinderServices();
        mService.onStart();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void testUsageEventListener() throws Exception {
        TestUsageEventListener listener = new TestUsageEventListener();
        UsageStatsManagerInternal usmi = LocalServices.getService(UsageStatsManagerInternal.class);
        usmi.registerListener(listener);

        UsageEvents.Event event = new UsageEvents.Event(UsageEvents.Event.CONFIGURATION_CHANGE, 10);
        usmi.reportEvent("com.android.test", 10, event.getEventType());
        listener.setExpectation(10, event);
        listener.mCountDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS);

        usmi.unregisterListener(listener);
        listener.reset();

        usmi.reportEvent("com.android.test", 0, UsageEvents.Event.CHOOSER_ACTION);
        Thread.sleep(TIMEOUT);
        assertNull(listener.mLastReceivedEvent);
    }

    private static class TestUsageEventListener implements
            UsageStatsManagerInternal.UsageEventListener {
        UsageEvents.Event mLastReceivedEvent;
        int mLastReceivedUserId;
        UsageEvents.Event mExpectedEvent;
        int mExpectedUserId;
        CountDownLatch mCountDownLatch;

        @Override
        public void onUsageEvent(int userId, UsageEvents.Event event) {
            mLastReceivedUserId = userId;
            mLastReceivedEvent = event;
            if (mCountDownLatch != null && userId == mExpectedUserId
                    && event.getEventType() == mExpectedEvent.getEventType()) {
                mCountDownLatch.countDown();
            }
        }

        private void setExpectation(int userId, UsageEvents.Event event) {
            mExpectedUserId = userId;
            mExpectedEvent = event;
            mCountDownLatch = new CountDownLatch(1);
        }

        private void reset() {
            mLastReceivedUserId = mExpectedUserId = -1;
            mLastReceivedEvent = mExpectedEvent = null;
            mCountDownLatch = null;
        }
    }
}
