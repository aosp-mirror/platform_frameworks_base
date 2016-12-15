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

import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import static org.mockito.Matchers.anyString;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.IOnNotificationChannelCreatedListener;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Binder;
import android.os.Handler;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;
import java.util.concurrent.CountDownLatch;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationManagerServiceTest {
    private final String pkg = "com.android.server.notification";
    private final int uid = 0;
    private NotificationManagerService mNotificationManagerService;
    private INotificationManager mBinderService;
    private IPackageManager mPackageManager = mock(IPackageManager.class);

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        mNotificationManagerService = new NotificationManagerService(context);

        // MockPackageManager - default returns ApplicationInfo with matching calling UID
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = Binder.getCallingUid();
        when(mPackageManager.getApplicationInfo(any(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        mNotificationManagerService.setPackageManager(mPackageManager);
        mNotificationManagerService.setHandler(new Handler(context.getMainLooper()));

        // Tests call directly into the Binder.
        mBinderService = mNotificationManagerService.getBinderService();
    }

    @Test
    public void testCreateNotificationChannel_SuccessCallsListener() throws Exception {
        final NotificationChannel channel =
                new NotificationChannel("id", "name", NotificationManager.IMPORTANCE_DEFAULT);
        mNotificationManagerService.setRankingHelper(mock(RankingHelper.class));
        final CountDownLatch latch = new CountDownLatch(1);
        mBinderService.createNotificationChannel("test_pkg", channel,
                new IOnNotificationChannelCreatedListener.Stub() {
                    @Override public void onNotificationChannelCreated(
                            NotificationChannel channel) {
                        latch.countDown();
                    }});
        latch.await();
    }

    @Test
    public void testCreateNotificationChannel_FailureDoesNotCallListener() throws Exception {
        try {
            mBinderService.createNotificationChannel("test_pkg", null,
                    new IOnNotificationChannelCreatedListener.Stub() {
                        @Override public void onNotificationChannelCreated(
                                NotificationChannel channel) {
                            fail("Listener was triggered from failure.");
                        }});
            fail("Exception should be thrown immediately.");
        } catch (NullPointerException e) {
            // pass
        }
    }

    @Test
    public void testBlockedNotifications_suspended() throws Exception {
        NotificationUsageStats usageStats = mock(NotificationUsageStats.class);
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(true);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        NotificationRecord r = generateNotificationRecord(channel);
        NotificationManagerService.EnqueueNotificationRunnable enqueue =
                mNotificationManagerService.new EnqueueNotificationRunnable(UserHandle.USER_SYSTEM,
                        r);
        assertTrue(enqueue.isBlocked(r, usageStats));
        verify(usageStats, times(1)).registerSuspendedByAdmin(eq(r));
    }

    @Test
    public void testBlockedNotifications_blockedChannel() throws Exception {
        NotificationUsageStats usageStats = mock(NotificationUsageStats.class);
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        channel.setAllowed(false);
        NotificationRecord r = generateNotificationRecord(channel);
        NotificationManagerService.EnqueueNotificationRunnable enqueue =
                mNotificationManagerService.new EnqueueNotificationRunnable(UserHandle.USER_SYSTEM,
                        r);
        assertTrue(enqueue.isBlocked(r, usageStats));
        verify(usageStats, times(1)).registerBlocked(eq(r));
    }

    @Test
    public void testBlockedNotifications_blockedApp() throws Exception {
        NotificationUsageStats usageStats = mock(NotificationUsageStats.class);
        when(mPackageManager.isPackageSuspendedForUser(anyString(), anyInt())).thenReturn(false);

        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        NotificationRecord r = generateNotificationRecord(channel);
        r.setUserImportance(NotificationManager.IMPORTANCE_NONE);
        NotificationManagerService.EnqueueNotificationRunnable enqueue =
                mNotificationManagerService.new EnqueueNotificationRunnable(UserHandle.USER_SYSTEM,
                        r);
        assertTrue(enqueue.isBlocked(r, usageStats));
        verify(usageStats, times(1)).registerBlocked(eq(r));
    }

    private NotificationRecord generateNotificationRecord(NotificationChannel channel) {
        final Context context = InstrumentationRegistry.getTargetContext();
        Notification n = new Notification.Builder(context)
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setPriority(Notification.PRIORITY_HIGH)
                .build();
        StatusBarNotification sbn = new StatusBarNotification(pkg, pkg, channel, 1, "tag", uid, uid,
                n, UserHandle.SYSTEM, null, uid);
        return new NotificationRecord(context, sbn);
    }
}
