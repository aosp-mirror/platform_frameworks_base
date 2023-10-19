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

package com.android.systemui.statusbar.policy;

import static android.app.Notification.FLAG_FSI_REQUESTED_BUT_DENIED;

import static com.android.systemui.dump.LogBufferHelperKt.logcatLogBuffer;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.R;
import com.android.systemui.statusbar.AlertingNotificationManager;
import com.android.systemui.statusbar.AlertingNotificationManagerTest;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class HeadsUpManagerTest extends AlertingNotificationManagerTest {
    private static final int TEST_TOUCH_ACCEPTANCE_TIME = 200;
    private static final int TEST_A11Y_AUTO_DISMISS_TIME = 1_000;
    private static final int TEST_A11Y_TIMEOUT_TIME = 3_000;

    private UiEventLoggerFake mUiEventLoggerFake = new UiEventLoggerFake();
    private final HeadsUpManagerLogger mLogger = spy(new HeadsUpManagerLogger(logcatLogBuffer()));
    @Mock private AccessibilityManagerWrapper mAccessibilityMgr;

    private final class TestableHeadsUpManager extends HeadsUpManager {
        TestableHeadsUpManager(Context context,
                HeadsUpManagerLogger logger,
                Handler handler,
                AccessibilityManagerWrapper accessibilityManagerWrapper,
                UiEventLogger uiEventLogger) {
            super(context, logger, handler, accessibilityManagerWrapper, uiEventLogger);
            mTouchAcceptanceDelay = TEST_TOUCH_ACCEPTANCE_TIME;
            mMinimumDisplayTime = TEST_MINIMUM_DISPLAY_TIME;
            mAutoDismissNotificationDecay = TEST_AUTO_DISMISS_TIME;
            mStickyDisplayTime = TEST_STICKY_AUTO_DISMISS_TIME;
        }
    }

    private HeadsUpManager createHeadsUpManager() {
        return new TestableHeadsUpManager(mContext, mLogger, mTestHandler, mAccessibilityMgr,
                mUiEventLoggerFake);
    }

    @Override
    protected AlertingNotificationManager createAlertingNotificationManager() {
        return createHeadsUpManager();
    }

    private NotificationEntry createStickyEntry(int id) {
        final Notification notif = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setFullScreenIntent(mock(PendingIntent.class), /* highPriority */ true)
                .build();
        return createEntry(id, notif);
    }

    private NotificationEntry createStickyForSomeTimeEntry(int id) {
        final Notification notif = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setFlag(FLAG_FSI_REQUESTED_BUT_DENIED, true)
                .build();
        return createEntry(id, notif);
    }

    private PendingIntent createFullScreenIntent() {
        return PendingIntent.getActivity(
                getContext(), 0, new Intent(getContext(), this.getClass()),
                PendingIntent.FLAG_MUTABLE_UNAUDITED);
    }

    private NotificationEntry createFullScreenIntentEntry(int id) {
        final Notification notif = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setFullScreenIntent(createFullScreenIntent(), /* highPriority */ true)
                .build();
        return createEntry(id, notif);
    }


    private void useAccessibilityTimeout(boolean use) {
        if (use) {
            doReturn(TEST_A11Y_AUTO_DISMISS_TIME).when(mAccessibilityMgr)
                    .getRecommendedTimeoutMillis(anyInt(), anyInt());
        } else {
            when(mAccessibilityMgr.getRecommendedTimeoutMillis(anyInt(), anyInt())).then(
                    i -> i.getArgument(0));
        }
    }


    @Before
    @Override
    public void setUp() {
        initMocks(this);
        super.setUp();

        assertThat(TEST_MINIMUM_DISPLAY_TIME).isLessThan(TEST_AUTO_DISMISS_TIME);
        assertThat(TEST_AUTO_DISMISS_TIME).isLessThan(TEST_STICKY_AUTO_DISMISS_TIME);
        assertThat(TEST_STICKY_AUTO_DISMISS_TIME).isLessThan(TEST_A11Y_AUTO_DISMISS_TIME);

        assertThat(TEST_TOUCH_ACCEPTANCE_TIME + TEST_AUTO_DISMISS_TIME).isLessThan(
                TEST_TIMEOUT_TIME);
        assertThat(TEST_TOUCH_ACCEPTANCE_TIME + TEST_STICKY_AUTO_DISMISS_TIME).isLessThan(
                TEST_TIMEOUT_TIME);
        assertThat(TEST_TOUCH_ACCEPTANCE_TIME + TEST_A11Y_AUTO_DISMISS_TIME).isLessThan(
                TEST_A11Y_TIMEOUT_TIME);
    }

    @After
    @Override
    public void tearDown() {
        super.tearDown();
    }

    @Test
    public void testHunRemovedLogging() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createEntry(/* id = */ 0);
        final HeadsUpManager.HeadsUpEntry headsUpEntry = mock(HeadsUpManager.HeadsUpEntry.class);
        headsUpEntry.mEntry = notifEntry;

        hum.onAlertEntryRemoved(headsUpEntry);

        verify(mLogger, times(1)).logNotificationActuallyRemoved(eq(notifEntry));
    }

    @Test
    public void testShouldHeadsUpBecomePinned_hasFSI_notUnpinned_true() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createFullScreenIntentEntry(/* id = */ 0);

        // Add notifEntry to ANM mAlertEntries map and make it NOT unpinned
        hum.showNotification(notifEntry);

        final HeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(notifEntry.getKey());
        headsUpEntry.wasUnpinned = false;

        assertTrue(hum.shouldHeadsUpBecomePinned(notifEntry));
    }

    @Test
    public void testShouldHeadsUpBecomePinned_wasUnpinned_false() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createFullScreenIntentEntry(/* id = */ 0);

        // Add notifEntry to ANM mAlertEntries map and make it unpinned
        hum.showNotification(notifEntry);

        final HeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(notifEntry.getKey());
        headsUpEntry.wasUnpinned = true;

        assertFalse(hum.shouldHeadsUpBecomePinned(notifEntry));
    }

    @Test
    public void testShouldHeadsUpBecomePinned_noFSI_false() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        assertFalse(hum.shouldHeadsUpBecomePinned(entry));
    }


    @Test
    public void testShowNotification_autoDismissesIncludingTouchAcceptanceDelay() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);

        final int pastJustAutoDismissMillis =
                TEST_TOUCH_ACCEPTANCE_TIME / 2 + TEST_AUTO_DISMISS_TIME;
        verifyAlertingAtTime(hum, entry, true, pastJustAutoDismissMillis, "just auto dismiss");
    }


    @Test
    public void testShowNotification_autoDismissesWithDefaultTimeout() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);

        final int pastDefaultTimeoutMillis = TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2;
        verifyAlertingAtTime(hum, entry, false, pastDefaultTimeoutMillis, "default timeout");
    }


    @Test
    public void testShowNotification_stickyForSomeTime_autoDismissesWithStickyTimeout() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyForSomeTimeEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);

        final int pastDefaultTimeoutMillis = TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_AUTO_DISMISS_TIME + TEST_STICKY_AUTO_DISMISS_TIME) / 2;
        verifyAlertingAtTime(hum, entry, true, pastDefaultTimeoutMillis, "default timeout");
    }


    @Test
    public void testShowNotification_sticky_neverAutoDismisses() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);

        final int pastLongestAutoDismissMillis =
                TEST_TOUCH_ACCEPTANCE_TIME + 2 * TEST_A11Y_AUTO_DISMISS_TIME;
        final Boolean[] wasAlerting = {null};
        final Runnable checkAlerting =
                () -> wasAlerting[0] = hum.isAlerting(entry.getKey());
        mTestHandler.postDelayed(checkAlerting, pastLongestAutoDismissMillis);
        TestableLooper.get(this).processMessages(1);

        assertTrue("Should still be alerting past longest auto-dismiss", wasAlerting[0]);
        assertTrue("Should still be alerting after processing",
                hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testShowNotification_autoDismissesWithAccessibilityTimeout() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);
        useAccessibilityTimeout(true);

        hum.showNotification(entry);

        final int pastDefaultTimeoutMillis = TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2;
        verifyAlertingAtTime(hum, entry, true, pastDefaultTimeoutMillis, "default timeout");
    }


    @Test
    public void testShowNotification_stickyForSomeTime_autoDismissesWithAccessibilityTimeout() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyForSomeTimeEntry(/* id = */ 0);
        useAccessibilityTimeout(true);

        hum.showNotification(entry);

        final int pastStickyTimeoutMillis = TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_STICKY_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2;
        verifyAlertingAtTime(hum, entry, true, pastStickyTimeoutMillis, "sticky timeout");
    }


    @Test
    public void testRemoveNotification_beforeMinimumDisplayTime() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);

        // Try to remove but defer, since the notification has not been shown long enough.
        final boolean removedImmediately = hum.removeNotification(
                entry.getKey(), false /* releaseImmediately */);

        assertFalse("HUN should not be removed before minimum display time", removedImmediately);
        assertTrue("HUN should still be alerting before minimum display time",
                hum.isAlerting(entry.getKey()));

        final int pastMinimumDisplayTimeMillis =
                (TEST_MINIMUM_DISPLAY_TIME + TEST_AUTO_DISMISS_TIME) / 2;
        verifyAlertingAtTime(hum, entry, false, pastMinimumDisplayTimeMillis,
                "minimum display time");
    }


    @Test
    public void testRemoveNotification_afterMinimumDisplayTime() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);

        // After the minimum display time:
        // 1. Check whether the notification is still alerting.
        // 2. Try to remove it and check whether the remove succeeded.
        // 3. Check whether it is still alerting after trying to remove it.
        final Boolean[] livedPastMinimumDisplayTime = {null};
        final Boolean[] removedAfterMinimumDisplayTime = {null};
        final Boolean[] livedPastRemoveAfterMinimumDisplayTime = {null};
        final Runnable pastMinimumDisplayTimeRunnable = () -> {
            livedPastMinimumDisplayTime[0] = hum.isAlerting(entry.getKey());
            removedAfterMinimumDisplayTime[0] = hum.removeNotification(
                    entry.getKey(), /* releaseImmediately = */ false);
            livedPastRemoveAfterMinimumDisplayTime[0] = hum.isAlerting(entry.getKey());
        };
        final int pastMinimumDisplayTimeMillis =
                (TEST_MINIMUM_DISPLAY_TIME + TEST_AUTO_DISMISS_TIME) / 2;
        mTestHandler.postDelayed(pastMinimumDisplayTimeRunnable, pastMinimumDisplayTimeMillis);
        // Wait until the minimum display time has passed before attempting removal.
        TestableLooper.get(this).processMessages(1);

        assertTrue("HUN should live past minimum display time",
                livedPastMinimumDisplayTime[0]);
        assertTrue("HUN should be removed immediately past minimum display time",
                removedAfterMinimumDisplayTime[0]);
        assertFalse("HUN should not live after being removed past minimum display time",
                livedPastRemoveAfterMinimumDisplayTime[0]);
        assertFalse(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testRemoveNotification_releaseImmediately() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        hum.showNotification(entry);

        // Remove forcibly with releaseImmediately = true.
        final boolean removedImmediately = hum.removeNotification(
                entry.getKey(), /* releaseImmediately = */ true);

        assertTrue(removedImmediately);
        assertFalse(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testIsSticky_rowPinnedAndExpanded_true() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createEntry(/* id = */ 0);
        when(mRow.isPinned()).thenReturn(true);
        notifEntry.setRow(mRow);

        hum.showNotification(notifEntry);

        final HeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(notifEntry.getKey());
        headsUpEntry.setExpanded(true);

        assertTrue(hum.isSticky(notifEntry.getKey()));
    }

    @Test
    public void testIsSticky_remoteInputActive_true() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createEntry(/* id = */ 0);

        hum.showNotification(notifEntry);

        final HeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(notifEntry.getKey());
        headsUpEntry.remoteInputActive = true;

        assertTrue(hum.isSticky(notifEntry.getKey()));
    }

    @Test
    public void testIsSticky_hasFullScreenIntent_true() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createFullScreenIntentEntry(/* id = */ 0);

        hum.showNotification(notifEntry);

        assertTrue(hum.isSticky(notifEntry.getKey()));
    }


    @Test
    public void testIsSticky_stickyForSomeTime_false() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyForSomeTimeEntry(/* id = */ 0);

        hum.showNotification(entry);

        assertFalse(hum.isSticky(entry.getKey()));
    }


    @Test
    public void testIsSticky_false() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createEntry(/* id = */ 0);

        hum.showNotification(notifEntry);

        final HeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(notifEntry.getKey());
        headsUpEntry.setExpanded(false);
        headsUpEntry.remoteInputActive = false;

        assertFalse(hum.isSticky(notifEntry.getKey()));
    }

    @Test
    public void testCompareTo_withNullEntries() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry alertEntry = new NotificationEntryBuilder().setTag("alert").build();

        hum.showNotification(alertEntry);

        assertThat(hum.compare(alertEntry, null)).isLessThan(0);
        assertThat(hum.compare(null, alertEntry)).isGreaterThan(0);
        assertThat(hum.compare(null, null)).isEqualTo(0);
    }

    @Test
    public void testCompareTo_withNonAlertEntries() {
        final HeadsUpManager hum = createHeadsUpManager();

        final NotificationEntry nonAlertEntry1 = new NotificationEntryBuilder().setTag(
                "nae1").build();
        final NotificationEntry nonAlertEntry2 = new NotificationEntryBuilder().setTag(
                "nae2").build();
        final NotificationEntry alertEntry = new NotificationEntryBuilder().setTag("alert").build();
        hum.showNotification(alertEntry);

        assertThat(hum.compare(alertEntry, nonAlertEntry1)).isLessThan(0);
        assertThat(hum.compare(nonAlertEntry1, alertEntry)).isGreaterThan(0);
        assertThat(hum.compare(nonAlertEntry1, nonAlertEntry2)).isEqualTo(0);
    }

    @Test
    public void testAlertEntryCompareTo_ongoingCallLessThanActiveRemoteInput() {
        final HeadsUpManager hum = createHeadsUpManager();

        final HeadsUpManager.HeadsUpEntry ongoingCall = hum.new HeadsUpEntry();
        ongoingCall.setEntry(new NotificationEntryBuilder()
                .setSbn(createSbn(/* id = */ 0,
                        new Notification.Builder(mContext, "")
                                .setCategory(Notification.CATEGORY_CALL)
                                .setOngoing(true)))
                .build());

        final HeadsUpManager.HeadsUpEntry activeRemoteInput = hum.new HeadsUpEntry();
        activeRemoteInput.setEntry(createEntry(/* id = */ 1));
        activeRemoteInput.remoteInputActive = true;

        assertThat(ongoingCall.compareTo(activeRemoteInput)).isLessThan(0);
        assertThat(activeRemoteInput.compareTo(ongoingCall)).isGreaterThan(0);
    }

    @Test
    public void testAlertEntryCompareTo_incomingCallLessThanActiveRemoteInput() {
        final HeadsUpManager hum = createHeadsUpManager();

        final HeadsUpManager.HeadsUpEntry incomingCall = hum.new HeadsUpEntry();
        final Person person = new Person.Builder().setName("person").build();
        final PendingIntent intent = mock(PendingIntent.class);
        incomingCall.setEntry(new NotificationEntryBuilder()
                .setSbn(createSbn(/* id = */ 0,
                        new Notification.Builder(mContext, "")
                                .setStyle(Notification.CallStyle
                                        .forIncomingCall(person, intent, intent))))
                .build());

        final HeadsUpManager.HeadsUpEntry activeRemoteInput = hum.new HeadsUpEntry();
        activeRemoteInput.setEntry(createEntry(/* id = */ 1));
        activeRemoteInput.remoteInputActive = true;

        assertThat(incomingCall.compareTo(activeRemoteInput)).isLessThan(0);
        assertThat(activeRemoteInput.compareTo(incomingCall)).isGreaterThan(0);
    }

    @Test
    public void testPinEntry_logsPeek() {
        final HeadsUpManager hum = createHeadsUpManager();

        // Needs full screen intent in order to be pinned
        final HeadsUpManager.HeadsUpEntry entryToPin = hum.new HeadsUpEntry();
        entryToPin.setEntry(createFullScreenIntentEntry(/* id = */ 0));

        // Note: the standard way to show a notification would be calling showNotification rather
        // than onAlertEntryAdded. However, in practice showNotification in effect adds
        // the notification and then updates it; in order to not log twice, the entry needs
        // to have a functional ExpandableNotificationRow that can keep track of whether it's
        // pinned or not (via isRowPinned()). That feels like a lot to pull in to test this one bit.
        hum.onAlertEntryAdded(entryToPin);

        assertEquals(1, mUiEventLoggerFake.numLogs());
        assertEquals(HeadsUpManager.NotificationPeekEvent.NOTIFICATION_PEEK.getId(),
                mUiEventLoggerFake.eventId(0));
    }

    @Test
    public void testSetUserActionMayIndirectlyRemove() {
        final HeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createEntry(/* id = */ 0);

        hum.showNotification(notifEntry);

        assertFalse(hum.canRemoveImmediately(notifEntry.getKey()));

        hum.setUserActionMayIndirectlyRemove(notifEntry);

        assertTrue(hum.canRemoveImmediately(notifEntry.getKey()));
    }
}
