/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.systemui.statusbar.policy;

import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.os.*;
import android.service.notification.StatusBarNotification;
import com.android.systemui.SwipeHelper;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationData;
import com.android.systemui.statusbar.phone.PhoneStatusBar;

import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Test the Heads Up Notification.
 *
 * Specifically the policy that a notificaiton must remain visibile for a minimum period of time.
 */
public class HeadsUpNotificationTest extends SysuiTestCase {
    private static final String TAG = "HeadsUpNotificationTest";

    private static int TOUCH_SENSITIVITY = 100;
    private static int NOTIFICATION_DECAY = 10000;
    private static int MINIMUM_DISPLAY_TIME = 3000;
    private static int SNOOZE_TIME = 60000;
    private static long TOO_SOON = 1000L;  // less than MINIMUM_DISPLAY_TIME
    private static long LATER = 5000L;  // more than MINIMUM_DISPLAY_TIME
    private static long REMAINING_VISIBILITY = MINIMUM_DISPLAY_TIME - TOO_SOON;

    protected HeadsUpNotificationView mHeadsUp;

    @Mock protected PhoneStatusBar mMockStatusBar;
    @Mock private HeadsUpNotificationView.Clock mClock;
    @Mock private SwipeHelper mMockSwipeHelper;
    @Mock private HeadsUpNotificationView.EdgeSwipeHelper mMockEdgeSwipeHelper;

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        MockitoAnnotations.initMocks(this);

        mHeadsUp = new HeadsUpNotificationView(mContext,
                mClock, mMockSwipeHelper, mMockEdgeSwipeHelper,
                NOTIFICATION_DECAY, MINIMUM_DISPLAY_TIME, TOUCH_SENSITIVITY, SNOOZE_TIME);
        mHeadsUp.setBar(mMockStatusBar);
    }

    private NotificationData.Entry makeNotification(String key) {
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        when(sbn.getKey()).thenReturn(key);
        return new NotificationData.Entry(sbn, null);
    }

    public void testPostAndDecay() {
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpClose();
        Mockito.verify(mMockStatusBar, times(1)).scheduleHeadsUpOpen();
        ArgumentCaptor<Long> decayArg = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(mMockStatusBar).scheduleHeadsUpDecay(decayArg.capture());
        // New notification gets a full decay time.
        assertEquals(NOTIFICATION_DECAY, (long) decayArg.getValue());
    }

    public void testPostAndDeleteTooSoon() {
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(TOO_SOON);
        mHeadsUp.removeNotification(a.key);
        ArgumentCaptor<Long> decayArg = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpClose();
        Mockito.verify(mMockStatusBar).scheduleHeadsUpDecay(decayArg.capture());
        // Leave the window up for the balance of the minumum time.
        assertEquals(REMAINING_VISIBILITY, (long) decayArg.getValue());
    }

    public void testPostAndDeleteLater() {
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(LATER);
        mHeadsUp.removeNotification(a.key);
        // Delete closes immediately if the minimum time window is satisfied.
        Mockito.verify(mMockStatusBar, times(1)).scheduleHeadsUpClose();
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpDecay(anyInt());
    }

    // This is a bad test.  It should not care that there is a call to scheduleHeadsUpClose(),
    // but it happens that there will be one, so it is important that it happen before the
    // call to scheduleHeadsUpOpen(), so that the final state is open.
    // Maybe mMockStatusBar should instead be a fake that tracks the open/closed state.
    public void testPostAndReplaceTooSoon() {
        InOrder callOrder = inOrder(mMockStatusBar);
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(TOO_SOON);
        NotificationData.Entry b = makeNotification("b");
        mHeadsUp.showNotification(b);
        Mockito.verify(mMockStatusBar, times(1)).scheduleHeadsUpClose();
        ArgumentCaptor<Long> decayArg = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(mMockStatusBar, times(1)).scheduleHeadsUpDecay(decayArg.capture());
        // New notification gets a full decay time.
        assertEquals(NOTIFICATION_DECAY, (long) decayArg.getValue());

        // Make sure close was called before open, so that the heads up stays open.
        callOrder.verify(mMockStatusBar).scheduleHeadsUpClose();
        callOrder.verify(mMockStatusBar).scheduleHeadsUpOpen();
    }

    public void testPostAndUpdateAlertAgain() {
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(TOO_SOON);
        mHeadsUp.updateNotification(a, true);
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpClose();
        ArgumentCaptor<Long> decayArg = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(mMockStatusBar, times(1)).scheduleHeadsUpDecay(decayArg.capture());
        // Alert again gets a full decay time.
        assertEquals(NOTIFICATION_DECAY, (long) decayArg.getValue());
    }

    public void testPostAndUpdateAlertAgainFastFail() {
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(TOO_SOON);
        NotificationData.Entry a_prime = makeNotification("a");
        mHeadsUp.updateNotification(a_prime, true);
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpClose();
        ArgumentCaptor<Long> decayArg = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(mMockStatusBar, times(1)).scheduleHeadsUpDecay(decayArg.capture());
        // Alert again gets a full decay time.
        assertEquals(NOTIFICATION_DECAY, (long) decayArg.getValue());
    }

    public void testPostAndUpdateNoAlertAgain() {
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(TOO_SOON);
        mHeadsUp.updateNotification(a, false);
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpClose();
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpDecay(anyInt());
    }

    public void testPostAndUpdateNoAlertAgainFastFail() {
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(TOO_SOON);
        NotificationData.Entry a_prime = makeNotification("a");
        mHeadsUp.updateNotification(a_prime, false);
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpClose();
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpDecay(anyInt());
    }

    public void testPostAndUpdateLowPriorityTooSoon() {
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(TOO_SOON);
        mHeadsUp.release();
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpClose();
        ArgumentCaptor<Long> decayArg = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(mMockStatusBar, times(1)).scheduleHeadsUpDecay(decayArg.capture());
        // Down grade on update leaves the window up for the balance of the minumum time.
        assertEquals(REMAINING_VISIBILITY, (long) decayArg.getValue());
    }

    public void testPostAndUpdateLowPriorityTooSoonFastFail() {
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(TOO_SOON);
        NotificationData.Entry a_prime = makeNotification("a");
        mHeadsUp.updateNotification(a_prime, false);
        mHeadsUp.release();
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpClose();
        ArgumentCaptor<Long> decayArg = ArgumentCaptor.forClass(Long.class);
        Mockito.verify(mMockStatusBar, times(1)).scheduleHeadsUpDecay(decayArg.capture());
        // Down grade on update leaves the window up for the balance of the minumum time.
        assertEquals(REMAINING_VISIBILITY, (long) decayArg.getValue());
    }

    public void testPostAndUpdateLowPriorityLater() {
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(LATER);
        mHeadsUp.release();
        // Down grade on update closes immediately if the minimum time window is satisfied.
        Mockito.verify(mMockStatusBar, times(1)).scheduleHeadsUpClose();
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpDecay(anyInt());
    }

    public void testPostAndUpdateLowPriorityLaterFastFail() {
        when(mClock.currentTimeMillis()).thenReturn(0L);
        NotificationData.Entry a = makeNotification("a");
        mHeadsUp.showNotification(a);
        reset(mMockStatusBar);

        when(mClock.currentTimeMillis()).thenReturn(LATER);
        NotificationData.Entry a_prime = makeNotification("a");
        mHeadsUp.updateNotification(a_prime, false);
        mHeadsUp.release();
        // Down grade on update closes immediately if the minimum time window is satisfied.
        Mockito.verify(mMockStatusBar, times(1)).scheduleHeadsUpClose();
        Mockito.verify(mMockStatusBar, never()).scheduleHeadsUpDecay(anyInt());
    }
}
