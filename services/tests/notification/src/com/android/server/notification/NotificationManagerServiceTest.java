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

import static junit.framework.Assert.fail;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.app.IOnNotificationChannelCreatedListener;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.os.Binder;
import android.os.Handler;
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
    private NotificationManagerService mNotificationManagerService;
    private INotificationManager mBinderService;

    @Before
    public void setUp() throws Exception {
        final Context context = InstrumentationRegistry.getTargetContext();
        mNotificationManagerService = new NotificationManagerService(context);

        // MockPackageManager - default returns ApplicationInfo with matching calling UID
        final IPackageManager mockPackageManager = mock(IPackageManager.class);
        final ApplicationInfo applicationInfo = new ApplicationInfo();
        applicationInfo.uid = Binder.getCallingUid();
        when(mockPackageManager.getApplicationInfo(any(), anyInt(), anyInt()))
                .thenReturn(applicationInfo);
        mNotificationManagerService.setPackageManager(mockPackageManager);
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
}
