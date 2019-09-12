/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui;

import static com.android.systemui.ForegroundServiceNotificationListener.ForegroundServiceLifetimeExtender.MIN_FGS_TIME_MS;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.service.notification.StatusBarNotification;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ForegroundServiceNotificationListenerTest extends SysuiTestCase {
    private ForegroundServiceNotificationListener.ForegroundServiceLifetimeExtender mExtender =
            new ForegroundServiceNotificationListener.ForegroundServiceLifetimeExtender();
    private StatusBarNotification mSbn;
    private NotificationEntry mEntry;
    private Notification mNotif;

    @Before
    public void setup() {
        mNotif = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text")
                .build();

        mSbn = mock(StatusBarNotification.class);
        when(mSbn.getNotification()).thenReturn(mNotif);

        mEntry = new NotificationEntry(mSbn);
    }

    /**
     * ForegroundServiceLifetimeExtenderTest
     */
    @Test
    public void testShouldExtendLifetime_should_foreground() {
        // Extend the lifetime of a FGS notification iff it has not been visible
        // for the minimum time
        mNotif.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        when(mSbn.getPostTime()).thenReturn(System.currentTimeMillis());
        assertTrue(mExtender.shouldExtendLifetime(mEntry));
    }

    @Test
    public void testShouldExtendLifetime_shouldNot_foreground() {
        mNotif.flags |= Notification.FLAG_FOREGROUND_SERVICE;
        when(mSbn.getPostTime()).thenReturn(System.currentTimeMillis() - MIN_FGS_TIME_MS - 1);
        assertFalse(mExtender.shouldExtendLifetime(mEntry));
    }

    @Test
    public void testShouldExtendLifetime_shouldNot_notForeground() {
        mNotif.flags = 0;
        when(mSbn.getPostTime()).thenReturn(System.currentTimeMillis() - MIN_FGS_TIME_MS - 1);
        assertFalse(mExtender.shouldExtendLifetime(mEntry));
    }
}
