/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class ReviewNotificationPermissionsReceiverTest extends UiServiceTestCase {

    // Simple mock class that just overrides the reschedule and cancel behavior so that it's easy
    // to tell whether the receiver has sent requests to either reschedule or cancel the
    // notification (or both).
    private class MockReviewNotificationPermissionsReceiver
            extends ReviewNotificationPermissionsReceiver {
        boolean mCanceled = false;
        boolean mRescheduled = false;

        @Override
        protected void cancelNotification(Context context) {
            mCanceled = true;
        }

        @Override
        protected void rescheduleNotification(Context context) {
            mRescheduled = true;
        }
    }

    private MockReviewNotificationPermissionsReceiver mReceiver;
    private Intent mIntent;

    @Before
    public void setUp() {
        mReceiver = new MockReviewNotificationPermissionsReceiver();
        mIntent = new Intent();  // actions will be set in test cases
    }

    @Test
    public void testReceive_remindMeLater_firstTime() {
        // Test what happens when we receive a "remind me later" intent coming from
        // a previously-not-interacted notification
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                NotificationManagerService.REVIEW_NOTIF_STATE_SHOULD_SHOW);

        // set up Intent action
        mIntent.setAction(NotificationManagerService.REVIEW_NOTIF_ACTION_REMIND);

        // Upon receipt of the intent, the following things should happen:
        //   - notification rescheduled
        //   - notification explicitly canceled
        //   - settings state updated to indicate user has interacted
        mReceiver.onReceive(mContext, mIntent);
        assertTrue(mReceiver.mRescheduled);
        assertTrue(mReceiver.mCanceled);
        assertEquals(NotificationManagerService.REVIEW_NOTIF_STATE_USER_INTERACTED,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN));
    }

    @Test
    public void testReceive_remindMeLater_laterTimes() {
        // Test what happens when we receive a "remind me later" intent coming from
        // a previously-interacted notification that has been rescheduled
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                NotificationManagerService.REVIEW_NOTIF_STATE_RESHOWN);

        // set up Intent action
        mIntent.setAction(NotificationManagerService.REVIEW_NOTIF_ACTION_REMIND);

        // Upon receipt of the intent, the following things should still happen
        // regardless of the fact that the user has interacted before:
        //   - notification rescheduled
        //   - notification explicitly canceled
        //   - settings state still indicate user has interacted
        mReceiver.onReceive(mContext, mIntent);
        assertTrue(mReceiver.mRescheduled);
        assertTrue(mReceiver.mCanceled);
        assertEquals(NotificationManagerService.REVIEW_NOTIF_STATE_USER_INTERACTED,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN));
    }

    @Test
    public void testReceive_dismiss() {
        // Test that dismissing the notification does *not* reschedule the notification,
        // does cancel it, and writes that it has been dismissed to settings
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                NotificationManagerService.REVIEW_NOTIF_STATE_SHOULD_SHOW);

        // set up Intent action
        mIntent.setAction(NotificationManagerService.REVIEW_NOTIF_ACTION_DISMISS);

        // send intent, watch what happens
        mReceiver.onReceive(mContext, mIntent);
        assertFalse(mReceiver.mRescheduled);
        assertTrue(mReceiver.mCanceled);
        assertEquals(NotificationManagerService.REVIEW_NOTIF_STATE_DISMISSED,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN));
    }

    @Test
    public void testReceive_notificationCanceled_firstSwipe() {
        // Test the basic swipe away case: the first time the user swipes the notification
        // away, it will not have been interacted with yet, so make sure it's rescheduled
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                NotificationManagerService.REVIEW_NOTIF_STATE_SHOULD_SHOW);

        // set up Intent action, would be called from notification's delete intent
        mIntent.setAction(NotificationManagerService.REVIEW_NOTIF_ACTION_CANCELED);

        // send intent, make sure it gets:
        //   - rescheduled
        //   - not explicitly canceled, the notification was already canceled
        //   - noted that it's been interacted with
        mReceiver.onReceive(mContext, mIntent);
        assertTrue(mReceiver.mRescheduled);
        assertFalse(mReceiver.mCanceled);
        assertEquals(NotificationManagerService.REVIEW_NOTIF_STATE_USER_INTERACTED,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN));
    }

    @Test
    public void testReceive_notificationCanceled_secondSwipe() {
        // Test the swipe away case for a rescheduled notification: in this case
        // it should not be rescheduled anymore
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                NotificationManagerService.REVIEW_NOTIF_STATE_RESHOWN);

        // set up Intent action, would be called from notification's delete intent
        mIntent.setAction(NotificationManagerService.REVIEW_NOTIF_ACTION_CANCELED);

        // send intent, make sure it gets:
        //   - not rescheduled on the second+ swipe
        //   - not explicitly canceled, the notification was already canceled
        //   - mark as user interacted
        mReceiver.onReceive(mContext, mIntent);
        assertFalse(mReceiver.mRescheduled);
        assertFalse(mReceiver.mCanceled);
        assertEquals(NotificationManagerService.REVIEW_NOTIF_STATE_USER_INTERACTED,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN));
    }

    @Test
    public void testReceive_notificationCanceled_fromDismiss() {
        // Test that if the notification delete intent is called due to us canceling
        // the notification from the receiver, we don't do anything extra
        Settings.Global.putInt(mContext.getContentResolver(),
                Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                NotificationManagerService.REVIEW_NOTIF_STATE_DISMISSED);

        // set up Intent action, would be called from notification's delete intent
        mIntent.setAction(NotificationManagerService.REVIEW_NOTIF_ACTION_CANCELED);

        // nothing should happen, nothing at all
        mReceiver.onReceive(mContext, mIntent);
        assertFalse(mReceiver.mRescheduled);
        assertFalse(mReceiver.mCanceled);
        assertEquals(NotificationManagerService.REVIEW_NOTIF_STATE_DISMISSED,
                Settings.Global.getInt(mContext.getContentResolver(),
                        Settings.Global.REVIEW_PERMISSIONS_NOTIFICATION_STATE,
                        NotificationManagerService.REVIEW_NOTIF_STATE_UNKNOWN));
    }
}
