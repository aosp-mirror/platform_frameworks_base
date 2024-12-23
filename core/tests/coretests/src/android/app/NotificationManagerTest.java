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

package android.app;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atMost;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.InstantSource;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class NotificationManagerTest {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Context mContext;
    private NotificationManagerWithMockService mNotificationManager;
    private final FakeClock mClock = new FakeClock();

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mNotificationManager = new NotificationManagerWithMockService(mContext, mClock);
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void notify_rapidUpdate_isThrottled() throws Exception {
        Notification n = exampleNotification();

        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(5);
        }

        verify(mNotificationManager.mBackendService, atLeast(20)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
        verify(mNotificationManager.mBackendService, atMost(30)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void notify_reasonableUpdate_isNotThrottled() throws Exception {
        Notification n = exampleNotification();

        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(300);
        }

        verify(mNotificationManager.mBackendService, times(100)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void notify_rapidAdd_isNotThrottled() throws Exception {
        Notification n = exampleNotification();

        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(i, n);
            mClock.advanceByMillis(5);
        }

        verify(mNotificationManager.mBackendService, times(100)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void notifyAsPackage_rapidUpdate_isThrottled() throws Exception {
        Notification n = exampleNotification();

        for (int i = 0; i < 100; i++) {
            mNotificationManager.notifyAsPackage("some.package.name", "tag", 1, n);
            mClock.advanceByMillis(5);
        }

        verify(mNotificationManager.mBackendService, atLeast(20)).enqueueNotificationWithTag(
                eq("some.package.name"), any(), any(), anyInt(), any(), anyInt());
        verify(mNotificationManager.mBackendService, atMost(30)).enqueueNotificationWithTag(
                eq("some.package.name"), any(), any(), anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void cancel_unnecessaryAndRapid_isThrottled() throws Exception {

        for (int i = 0; i < 100; i++) {
            mNotificationManager.cancel(1);
            mClock.advanceByMillis(5);
        }

        verify(mNotificationManager.mBackendService, atLeast(20)).cancelNotificationWithTag(any(),
                any(), any(), anyInt(), anyInt());
        verify(mNotificationManager.mBackendService, atMost(30)).cancelNotificationWithTag(any(),
                any(), any(), anyInt(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void cancel_unnecessaryButReasonable_isNotThrottled() throws Exception {
        // Scenario: the app tries to repeatedly cancel a single notification, but at a reasonable
        // rate. Strange, but not what we're trying to block with NM_BINDER_PERF_THROTTLE_NOTIFY.
        for (int i = 0; i < 100; i++) {
            mNotificationManager.cancel(1);
            mClock.advanceByMillis(500);
        }

        verify(mNotificationManager.mBackendService, times(100)).cancelNotificationWithTag(any(),
                any(), any(), anyInt(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void cancel_necessaryAndRapid_isNotThrottled() throws Exception {
        // Scenario: the app posts and immediately cancels a bunch of notifications. Strange,
        // but not what we're trying to block with NM_BINDER_PERF_THROTTLE_NOTIFY.
        Notification n = exampleNotification();

        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mNotificationManager.cancel(1);
            mClock.advanceByMillis(5);
        }

        verify(mNotificationManager.mBackendService, times(100)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
        verify(mNotificationManager.mBackendService, times(100)).cancelNotificationWithTag(any(),
                any(), any(), anyInt(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void cancel_maybeNecessaryAndRapid_isNotThrottled() throws Exception {
        // Scenario: the app posted a lot of notifications, is killed, then restarts (so NM client
        // doesn't know about them), then cancels them one by one. We don't want to throttle this
        // case.
        for (int i = 0; i < 100; i++) {
            mNotificationManager.cancel(i);
            mClock.advanceByMillis(1);
        }

        verify(mNotificationManager.mBackendService, times(100)).cancelNotificationWithTag(any(),
                any(), any(), anyInt(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void enqueue_afterCancel_isNotUpdateAndIsNotThrottled() throws Exception {
        // First, hit the enqueue threshold.
        Notification n = exampleNotification();
        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(1);
        }
        verify(mNotificationManager.mBackendService, atMost(30)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
        reset(mNotificationManager.mBackendService);

        // Now cancel that notification and then post it again. That should work.
        mNotificationManager.cancel(1);
        mNotificationManager.notify(1, n);
        verify(mNotificationManager.mBackendService).enqueueNotificationWithTag(any(), any(), any(),
                anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void enqueue_afterCancelAsPackage_isNotUpdateAndIsNotThrottled() throws Exception {
        // First, hit the enqueue threshold.
        Notification n = exampleNotification();
        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(1);
        }
        verify(mNotificationManager.mBackendService, atMost(30)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
        reset(mNotificationManager.mBackendService);

        // Now cancel that notification and then post it again. That should work.
        mNotificationManager.cancelAsPackage(mContext.getPackageName(), /* tag= */ null, 1);
        mNotificationManager.notify(1, n);
        verify(mNotificationManager.mBackendService).enqueueNotificationWithTag(any(), any(), any(),
                anyInt(), any(), anyInt());
    }

    @Test
    @EnableFlags(Flags.FLAG_NM_BINDER_PERF_THROTTLE_NOTIFY)
    public void enqueue_afterCancelAll_isNotUpdateAndIsNotThrottled() throws Exception {
        // First, hit the enqueue threshold.
        Notification n = exampleNotification();
        for (int i = 0; i < 100; i++) {
            mNotificationManager.notify(1, n);
            mClock.advanceByMillis(1);
        }
        verify(mNotificationManager.mBackendService, atMost(30)).enqueueNotificationWithTag(any(),
                any(), any(), anyInt(), any(), anyInt());
        reset(mNotificationManager.mBackendService);

        // Now cancel all notifications and then post it again. That should work.
        mNotificationManager.cancelAll();
        mNotificationManager.notify(1, n);
        verify(mNotificationManager.mBackendService).enqueueNotificationWithTag(any(), any(), any(),
                anyInt(), any(), anyInt());
    }

    private Notification exampleNotification() {
        return new Notification.Builder(mContext, "channel")
                .setSmallIcon(android.R.drawable.star_big_on)
                .build();
    }

    private static class NotificationManagerWithMockService extends NotificationManager {

        private final INotificationManager mBackendService;

        NotificationManagerWithMockService(Context context, InstantSource clock) {
            super(context, clock);
            mBackendService = mock(INotificationManager.class);
        }

        @Override
        public INotificationManager service() {
            return mBackendService;
        }
    }

    private static class FakeClock implements InstantSource {

        private long mNowMillis = 441644400000L;

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(mNowMillis);
        }

        private void advanceByMillis(long millis) {
            mNowMillis += millis;
        }
    }
}
