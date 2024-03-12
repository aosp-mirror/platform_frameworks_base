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

package com.android.server.notification;

import static android.app.Notification.FLAG_FOREGROUND_SERVICE;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.service.notification.NotificationListenerService.REASON_CANCEL;
import static android.service.notification.NotificationListenerService.REASON_GROUP_SUMMARY_CANCELED;
import static android.service.notification.NotificationStats.DISMISSAL_BUBBLE;
import static android.service.notification.NotificationStats.DISMISSAL_OTHER;

import static com.android.server.notification.NotificationRecordLogger.NotificationCancelledEvent.NOTIFICATION_CANCEL_CLICK;
import static com.android.server.notification.NotificationRecordLogger.NotificationCancelledEvent.NOTIFICATION_CANCEL_GROUP_SUMMARY_CANCELED;
import static com.android.server.notification.NotificationRecordLogger.NotificationCancelledEvent.NOTIFICATION_CANCEL_USER_OTHER;
import static com.android.server.notification.NotificationRecordLogger.NotificationReportedEvent.NOTIFICATION_POSTED;
import static com.android.server.notification.NotificationRecordLogger.NotificationReportedEvent.NOTIFICATION_UPDATED;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Notification;
import android.app.NotificationChannel;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;
import com.android.internal.util.FrameworkStatsLog;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;


@SmallTest
@RunWith(AndroidJUnit4.class)
public class NotificationRecordLoggerTest extends UiServiceTestCase {
    private static final int UID = 9999;
    private static final String CHANNEL_ID = "NotificationRecordLoggerTestChannelId";

    private NotificationRecord getNotification(int id, String tag) {
        final String packageName = mContext.getPackageName();
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, CHANNEL_ID, IMPORTANCE_DEFAULT);
        Notification.Builder nb = new Notification.Builder(mContext, channel.getId());
        StatusBarNotification sbn = new StatusBarNotification(packageName, packageName, id, tag,
                UID, 0, nb.build(), new UserHandle(UID), null,
                0);
        return new NotificationRecord(mContext, sbn, channel);
    }

    private NotificationRecordLogger.NotificationRecordPair getNotificationRecordPair(int id,
            String tag) {
        return new NotificationRecordLogger.NotificationRecordPair(getNotification(id, tag),
                null);
    }

    @Test
    public void testSmallHash() {
        assertEquals(0, SmallHash.hash(0));
        final int maxHash = SmallHash.MAX_HASH;
        assertEquals(0,
                SmallHash.hash(maxHash));
        assertEquals(0,
                SmallHash.hash(17 * maxHash));
        assertEquals(maxHash - 1,
                SmallHash.hash(maxHash - 1));
        assertEquals(maxHash - 1,
                SmallHash.hash(-1));
    }

    @Test
    public void testGetNotificationIdHash() {
        assertEquals(0,
                getNotificationRecordPair(0, null).getNotificationIdHash());
        assertEquals(1,
                getNotificationRecordPair(1, null).getNotificationIdHash());
        assertEquals(SmallHash.MAX_HASH - 1,
                getNotificationRecordPair(-1, null).getNotificationIdHash());
        final String tag = "someTag";
        final int hash = SmallHash.hash(tag.hashCode());
        assertEquals(hash, getNotificationRecordPair(0, tag).getNotificationIdHash());
        // We xor the tag and hashcode together before compressing the range. The order of
        // operations doesn't matter if id is small.
        assertEquals(1 ^ hash,
                getNotificationRecordPair(1, tag).getNotificationIdHash());
        // But it does matter for an id with more 1 bits than fit in the small hash.
        assertEquals(
                SmallHash.hash(-1 ^ tag.hashCode()),
                getNotificationRecordPair(-1, tag).getNotificationIdHash());
        assertNotEquals(-1 ^ hash,
                SmallHash.hash(-1 ^ tag.hashCode()));
    }

    @Test
    public void testGetChannelIdHash() {
        assertEquals(
                SmallHash.hash(CHANNEL_ID.hashCode()),
                getNotificationRecordPair(0, null).getChannelIdHash());
        assertNotEquals(
                SmallHash.hash(CHANNEL_ID.hashCode()),
                CHANNEL_ID.hashCode());
    }

    @Test
    public void testGetGroupIdHash() {
        NotificationRecordLogger.NotificationRecordPair p = getNotificationRecordPair(
                0, null);
        assertEquals(0, p.getGroupIdHash());
        final String group = "someGroup";
        p.r.setOverrideGroupKey(group);
        assertEquals(
                SmallHash.hash(group.hashCode()),
                p.getGroupIdHash());
    }

    @Test
    public void testIsForegroundService() {
        NotificationRecordLogger.NotificationRecordPair p = getNotificationRecordPair(
                0, null);
        assertFalse(NotificationRecordLogger.isForegroundService(p.r));

        // Set foreground service
        p.r.getSbn().getNotification().flags |= FLAG_FOREGROUND_SERVICE;
        assertTrue(NotificationRecordLogger.isForegroundService(p.r));
    }


    @Test
    public void testIsNonDismissible_hasFlagNoDismiss_shouldReturnTrue() {
        // Given: a notification pair's notification has flag FLAG_NO_DISMISS
        NotificationRecordLogger.NotificationRecordPair p = getNotificationRecordPair(
                0, null);
        p.r.getNotification().flags |= Notification.FLAG_NO_DISMISS;

        // When: check the value of isNonDismissible()
        // Then: should return true
        assertTrue(NotificationRecordLogger.isNonDismissible(p.r));
    }

    @Test
    public void testIsNonDismissible_noFlagNoDismiss_shouldReturnFalse() {
        // Given: a notification pair's notification doesn't have flag FLAG_NO_DISMISS
        NotificationRecordLogger.NotificationRecordPair p = getNotificationRecordPair(
                0, null);
        p.r.getNotification().flags &= ~Notification.FLAG_NO_DISMISS;

        // When: check the value of isNonDismissible()
        // Then: should return false
        assertFalse(NotificationRecordLogger.isNonDismissible(p.r));
    }

    @Test
    public void testGetFsiState_isUpdate_zero() {
        final int fsiState = NotificationRecordLogger.getFsiState(
                /* hasFullScreenIntent= */ true,
                /* hasFsiRequestedButDeniedFlag= */ true,
                /* eventType= */ NOTIFICATION_UPDATED);
        assertEquals(0, fsiState);
    }

    @Test
    public void testGetFsiState_hasFsi_allowedEnum() {
        final int fsiState = NotificationRecordLogger.getFsiState(
                /* hasFullScreenIntent= */ true,
                /* hasFsiRequestedButDeniedFlag= */ false,
                /* eventType= */ NOTIFICATION_POSTED);
        assertEquals(FrameworkStatsLog.NOTIFICATION_REPORTED__FSI_STATE__FSI_ALLOWED, fsiState);
    }

    @Test
    public void testGetFsiState_fsiPermissionDenied_deniedEnum() {
        final int fsiState = NotificationRecordLogger.getFsiState(
                /* hasFullScreenIntent= */ false,
                /* hasFsiRequestedButDeniedFlag= */ true,
                /* eventType= */ NOTIFICATION_POSTED);
        assertEquals(FrameworkStatsLog.NOTIFICATION_REPORTED__FSI_STATE__FSI_DENIED, fsiState);
    }

    @Test
    public void testGetFsiState_noFsi_noFsiEnum() {
        final int fsiState = NotificationRecordLogger.getFsiState(
                /* hasFullScreenIntent= */ false,
                /* hasFsiRequestedButDeniedFlag= */ false,
                /* eventType= */ NOTIFICATION_POSTED);
        assertEquals(FrameworkStatsLog.NOTIFICATION_REPORTED__FSI_STATE__NO_FSI, fsiState);
    }

    @Test
    public void testBubbleGroupSummaryDismissal() {
        assertEquals(NOTIFICATION_CANCEL_GROUP_SUMMARY_CANCELED,
                NotificationRecordLogger.NotificationCancelledEvent.fromCancelReason(
                REASON_GROUP_SUMMARY_CANCELED, DISMISSAL_BUBBLE));
    }

    @Test
    public void testOtherNotificationCancel() {
        assertEquals(NOTIFICATION_CANCEL_USER_OTHER,
                NotificationRecordLogger.NotificationCancelledEvent.fromCancelReason(
                        REASON_CANCEL, DISMISSAL_OTHER));
    }

    @Test
    public void testGetAgeInMinutes() {
        long postTimeMs = Duration.ofMinutes(5).toMillis();
        long whenMs = Duration.ofMinutes(2).toMillis();
        int age = NotificationRecordLogger.getAgeInMinutes(postTimeMs, whenMs);
        assertThat(age).isEqualTo(3);
    }
}
