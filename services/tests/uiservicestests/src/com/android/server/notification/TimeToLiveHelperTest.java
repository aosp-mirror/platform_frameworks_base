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
package com.android.server.notification;

import static com.android.server.notification.TimeToLiveHelper.EXTRA_KEY;
import static com.google.common.truth.Truth.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.TestableLooper;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;
import com.android.server.UiServiceTestCase;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.quality.Strictness;

@SmallTest
@RunWith(AndroidJUnit4.class)
@SuppressLint("GuardedBy") // It's ok for this test to access guarded methods from the service.
public class TimeToLiveHelperTest extends UiServiceTestCase {

    TimeToLiveHelper mHelper;
    @Mock
    NotificationManagerPrivate mNm;
    @Mock
    AlarmManager mAm;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mContext.addMockSystemService(AlarmManager.class, mAm);
        mHelper = new TimeToLiveHelper(mNm, mContext);
    }

    @After
    public void tearDown() {
        mHelper.destroy();
    }

    private NotificationRecord getRecord(String tag, int timeoutAfter) {
        NotificationChannel channel = new NotificationChannel("id", "name",
                NotificationManager.IMPORTANCE_HIGH);
        Notification.Builder nb = new Notification.Builder(mContext, channel.getId())
                .setContentTitle("foo")
                .setSmallIcon(android.R.drawable.sym_def_app_icon)
                .setTimeoutAfter(timeoutAfter);

        StatusBarNotification sbn = new StatusBarNotification(mPkg, mPkg, 8, tag, mUid, 0,
                nb.build(), UserHandle.getUserHandleForUid(mUid), null, 0);
        return new NotificationRecord(mContext, sbn, channel);
    }

    @Test
    public void testTimeout() {
        mHelper.scheduleTimeoutLocked(getRecord("testTimeout", 1), 1);

        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(2L), any());
        assertThat(mHelper.mKeys).hasSize(1);
    }

    @Test
    public void testTimeoutExpires() {
        NotificationRecord r = getRecord("testTimeoutExpires", 1);

        mHelper.scheduleTimeoutLocked(r, 1);
        ArgumentCaptor<PendingIntent> captor = ArgumentCaptor.forClass(PendingIntent.class);
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(2L), captor.capture());

        mHelper.mNotificationTimeoutReceiver.onReceive(mContext, captor.getValue().getIntent());

        assertThat(mHelper.mKeys).isEmpty();
    }

    @Test
    public void testTimeoutExpires_twoEntries() {
        NotificationRecord first = getRecord("testTimeoutFirst", 1);
        NotificationRecord later = getRecord("testTimeoutSecond", 2);

        mHelper.scheduleTimeoutLocked(first, 1);
        mHelper.scheduleTimeoutLocked(later, 1);

        ArgumentCaptor<PendingIntent> captorSet = ArgumentCaptor.forClass(PendingIntent.class);
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(2L), captorSet.capture());

        ArgumentCaptor<PendingIntent> captorNewSet = ArgumentCaptor.forClass(PendingIntent.class);
        mHelper.mNotificationTimeoutReceiver.onReceive(mContext, captorSet.getValue().getIntent());

        assertThat(mHelper.mKeys).hasSize(1);
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(3L), captorNewSet.capture());
        assertThat(captorSet.getValue().getIntent().getStringExtra(EXTRA_KEY))
                .isEqualTo(first.getKey());
    }

    @Test
    public void testTimeout_earlierEntryAddedSecond() {
        NotificationRecord later = getRecord("testTimeoutSecond", 2);
        mHelper.scheduleTimeoutLocked(later, 1);

        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(3L), any());
        assertThat(mHelper.mKeys).hasSize(1);

        NotificationRecord first = getRecord("testTimeoutFirst", 1);
        ArgumentCaptor<PendingIntent> captorSet = ArgumentCaptor.forClass(PendingIntent.class);
        ArgumentCaptor<PendingIntent> captorCancel = ArgumentCaptor.forClass(PendingIntent.class);

        mHelper.scheduleTimeoutLocked(first, 1);

        assertThat(mHelper.mKeys).hasSize(2);
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(2L), captorSet.capture());
        assertThat(captorSet.getValue().getIntent().getStringExtra(EXTRA_KEY))
                .isEqualTo(first.getKey());
        assertThat(mHelper.mKeys.first().second).isEqualTo(first.getKey());

        verify(mAm).cancel(captorCancel.capture());
        assertThat(captorCancel.getValue().getIntent().getStringExtra(EXTRA_KEY))
                .isEqualTo(later.getKey());
    }

    @Test
    public void testTimeout_earlierEntryAddedFirst() {
        NotificationRecord first = getRecord("testTimeoutFirst", 1);
        NotificationRecord later = getRecord("testTimeoutSecond", 2);

        mHelper.scheduleTimeoutLocked(first, 1);
        mHelper.scheduleTimeoutLocked(later, 1);

        assertThat(mHelper.mKeys).hasSize(2);
        assertThat(mHelper.mKeys.first().second).isEqualTo(first.getKey());
        verify(mAm, never()).cancel((PendingIntent) any());
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(2L), any());
    }

    @Test
    public void testTimeout_updateEarliestEntry() {
        NotificationRecord first = getRecord("testTimeoutFirst", 1);

        mHelper.scheduleTimeoutLocked(first, 1);
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(2L), any());

        NotificationRecord firstUpdated = getRecord("testTimeoutFirst", 3);
        ArgumentCaptor<PendingIntent> captorSet = ArgumentCaptor.forClass(PendingIntent.class);
        ArgumentCaptor<PendingIntent> captorCancel = ArgumentCaptor.forClass(PendingIntent.class);

        mHelper.scheduleTimeoutLocked(firstUpdated, 1);

        assertThat(mHelper.mKeys).hasSize(1);

        // cancel original alarm
        verify(mAm).cancel(captorCancel.capture());
        assertThat(captorCancel.getValue().getIntent().getStringExtra(EXTRA_KEY))
                .isEqualTo(first.getKey());

        // schedule later alarm
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(4L), captorSet.capture());
        assertThat(captorSet.getValue().getIntent().getStringExtra(EXTRA_KEY))
                .isEqualTo(first.getKey());
    }

    @Test
    public void testTimeout_twoEntries_updateEarliestEntry() {
        NotificationRecord first = getRecord("testTimeoutFirst", 1);
        NotificationRecord later = getRecord("testTimeoutSecond", 2);

        mHelper.scheduleTimeoutLocked(first, 1);
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(2L), any());

        mHelper.scheduleTimeoutLocked(later, 1);

        NotificationRecord firstUpdated = getRecord("testTimeoutFirst", 3);
        ArgumentCaptor<PendingIntent> captorSet = ArgumentCaptor.forClass(PendingIntent.class);
        ArgumentCaptor<PendingIntent> captorCancel = ArgumentCaptor.forClass(PendingIntent.class);

        mHelper.scheduleTimeoutLocked(firstUpdated, 1);

        assertThat(mHelper.mKeys).hasSize(2);
        assertThat(mHelper.mKeys.first().second).isEqualTo(later.getKey());

        // "first" was canceled because it's now later
        verify(mAm).cancel(captorCancel.capture());
        assertThat(captorCancel.getValue().getIntent().getStringExtra(EXTRA_KEY))
                .isEqualTo(first.getKey());

        // "later" is now the first entry, and needs the matching alarm
        verify(mAm).setExactAndAllowWhileIdle(anyInt(), eq(3L), captorSet.capture());
        assertThat(captorSet.getValue().getIntent().getStringExtra(EXTRA_KEY))
                .isEqualTo(later.getKey());
    }
}
