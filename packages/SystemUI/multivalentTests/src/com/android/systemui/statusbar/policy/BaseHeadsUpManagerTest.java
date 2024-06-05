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

import static com.android.systemui.log.LogBufferHelperKt.logcatLogBuffer;

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

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.FlagsParameterization;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.shared.NotificationThrottleHun;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.FakeGlobalSettings;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import platform.test.runner.parameterized.ParameterizedAndroidJunit4;
import platform.test.runner.parameterized.Parameters;

import java.util.List;

@SmallTest
@TestableLooper.RunWithLooper
@RunWith(ParameterizedAndroidJunit4.class)
public class BaseHeadsUpManagerTest extends SysuiTestCase {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    static final int TEST_TOUCH_ACCEPTANCE_TIME = 200;
    static final int TEST_A11Y_AUTO_DISMISS_TIME = 1_000;

    private UiEventLoggerFake mUiEventLoggerFake = new UiEventLoggerFake();
    private final HeadsUpManagerLogger mLogger = spy(new HeadsUpManagerLogger(logcatLogBuffer()));

    @Mock private DumpManager dumpManager;
    private AvalancheController mAvalancheController;

    @Mock private AccessibilityManagerWrapper mAccessibilityMgr;

    protected static final int TEST_MINIMUM_DISPLAY_TIME = 400;
    protected static final int TEST_AUTO_DISMISS_TIME = 600;
    protected static final int TEST_STICKY_AUTO_DISMISS_TIME = 800;
    // Number of notifications to use in tests requiring multiple notifications
    private static final int TEST_NUM_NOTIFICATIONS = 4;

    protected final FakeGlobalSettings mGlobalSettings = new FakeGlobalSettings();
    protected final FakeSystemClock mSystemClock = new FakeSystemClock();
    protected final FakeExecutor mExecutor = new FakeExecutor(mSystemClock);

    @Mock protected ExpandableNotificationRow mRow;

    static {
        assertThat(TEST_MINIMUM_DISPLAY_TIME).isLessThan(TEST_AUTO_DISMISS_TIME);
        assertThat(TEST_AUTO_DISMISS_TIME).isLessThan(TEST_STICKY_AUTO_DISMISS_TIME);
        assertThat(TEST_STICKY_AUTO_DISMISS_TIME).isLessThan(TEST_A11Y_AUTO_DISMISS_TIME);
    }

    private BaseHeadsUpManager createHeadsUpManager() {
        return new TestableHeadsUpManager(mContext, mLogger, mExecutor, mGlobalSettings,
                mSystemClock, mAccessibilityMgr, mUiEventLoggerFake, mAvalancheController);
    }

    private NotificationEntry createStickyEntry(int id) {
        final Notification notif = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setFullScreenIntent(mock(PendingIntent.class), /* highPriority */ true)
                .build();
        return HeadsUpManagerTestUtil.createEntry(id, notif);
    }

    private NotificationEntry createStickyForSomeTimeEntry(int id) {
        final Notification notif = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setFlag(FLAG_FSI_REQUESTED_BUT_DENIED, true)
                .build();
        return HeadsUpManagerTestUtil.createEntry(id, notif);
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

    @Parameters(name = "{0}")
    public static List<FlagsParameterization> getFlags() {
        return FlagsParameterization.allCombinationsOf(NotificationThrottleHun.FLAG_NAME);
    }

    public BaseHeadsUpManagerTest(FlagsParameterization flags) {
        mSetFlagsRule.setFlagsParameterization(flags);
    }

    @Override
    public void SysuiSetup() throws Exception {
        super.SysuiSetup();
        mAvalancheController = new AvalancheController(dumpManager, mUiEventLoggerFake);
    }

    @Test
    public void testHasNotifications_headsUpManagerMapNotEmpty_true() {
        final BaseHeadsUpManager bhum = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);
        bhum.showNotification(entry);

        assertThat(bhum.mHeadsUpEntryMap).isNotEmpty();
        assertThat(bhum.hasNotifications()).isTrue();
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    public void testHasNotifications_avalancheMapNotEmpty_true() {
        final BaseHeadsUpManager bhum = createHeadsUpManager();
        final NotificationEntry notifEntry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0,
                mContext);
        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = bhum.createHeadsUpEntry(notifEntry);
        mAvalancheController.addToNext(headsUpEntry, () -> {});

        assertThat(mAvalancheController.getWaitingEntryList()).isNotEmpty();
        assertThat(bhum.hasNotifications()).isTrue();
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    public void testHasNotifications_false() {
        final BaseHeadsUpManager bhum = createHeadsUpManager();
        assertThat(bhum.mHeadsUpEntryMap).isEmpty();
        assertThat(mAvalancheController.getWaitingEntryList()).isEmpty();
        assertThat(bhum.hasNotifications()).isFalse();
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    public void testGetHeadsUpEntryList_includesAvalancheEntryList() {
        final BaseHeadsUpManager bhum = createHeadsUpManager();
        final NotificationEntry notifEntry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0,
                mContext);
        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = bhum.createHeadsUpEntry(notifEntry);
        mAvalancheController.addToNext(headsUpEntry, () -> {});

        assertThat(bhum.getHeadsUpEntryList()).contains(headsUpEntry);
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    public void testGetHeadsUpEntry_returnsAvalancheEntry() {
        final BaseHeadsUpManager bhum = createHeadsUpManager();
        final NotificationEntry notifEntry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0,
                mContext);
        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = bhum.createHeadsUpEntry(notifEntry);
        mAvalancheController.addToNext(headsUpEntry, () -> {});

        assertThat(bhum.getHeadsUpEntry(notifEntry.getKey())).isEqualTo(headsUpEntry);
    }

    @Test
    public void testShowNotification_addsEntry() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        alm.showNotification(entry);

        assertTrue(alm.isHeadsUpEntry(entry.getKey()));
        assertTrue(alm.hasNotifications());
        assertEquals(entry, alm.getEntry(entry.getKey()));
    }

    @Test
    public void testShowNotification_autoDismisses() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        alm.showNotification(entry);
        mSystemClock.advanceTime(TEST_AUTO_DISMISS_TIME * 3 / 2);

        assertFalse(alm.isHeadsUpEntry(entry.getKey()));
    }

    @Test
    public void testRemoveNotification_removeDeferred() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        alm.showNotification(entry);

        final boolean removedImmediately = alm.removeNotification(
                entry.getKey(), /* releaseImmediately = */ false);
        assertFalse(removedImmediately);
        assertTrue(alm.isHeadsUpEntry(entry.getKey()));
    }

    @Test
    public void testRemoveNotification_forceRemove() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        alm.showNotification(entry);

        final boolean removedImmediately = alm.removeNotification(
                entry.getKey(), /* releaseImmediately = */ true);
        assertTrue(removedImmediately);
        assertFalse(alm.isHeadsUpEntry(entry.getKey()));
    }

    @Test
    public void testReleaseAllImmediately() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        for (int i = 0; i < TEST_NUM_NOTIFICATIONS; i++) {
            final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(i, mContext);
            entry.setRow(mRow);
            alm.showNotification(entry);
        }

        alm.releaseAllImmediately();

        assertEquals(0, alm.getAllEntries().count());
    }

    @Test
    public void testCanRemoveImmediately_notShownLongEnough() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        alm.showNotification(entry);

        // The entry has just been added so we should not remove immediately.
        assertFalse(alm.canRemoveImmediately(entry.getKey()));
    }

    @Test
    public void testHunRemovedLogging() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0,
                mContext);
        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = mock(
                BaseHeadsUpManager.HeadsUpEntry.class);
        headsUpEntry.mEntry = notifEntry;

        hum.onEntryRemoved(headsUpEntry);

        verify(mLogger, times(1)).logNotificationActuallyRemoved(eq(notifEntry));
    }

    @Test
    public void testShouldHeadsUpBecomePinned_hasFSI_notUnpinned_true() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry =
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id = */ 0, mContext);

        // Add notifEntry to ANM mAlertEntries map and make it NOT unpinned
        hum.showNotification(notifEntry);

        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(
                notifEntry.getKey());
        headsUpEntry.mWasUnpinned = false;

        assertTrue(hum.shouldHeadsUpBecomePinned(notifEntry));
    }

    @Test
    public void testShouldHeadsUpBecomePinned_wasUnpinned_false() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry =
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id = */ 0, mContext);

        // Add notifEntry to ANM mAlertEntries map and make it unpinned
        hum.showNotification(notifEntry);

        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(
                notifEntry.getKey());
        headsUpEntry.mWasUnpinned = true;

        assertFalse(hum.shouldHeadsUpBecomePinned(notifEntry));
    }

    @Test
    public void testShouldHeadsUpBecomePinned_noFSI_false() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        assertFalse(hum.shouldHeadsUpBecomePinned(entry));
    }


    @Test
    public void testShowNotification_autoDismissesIncludingTouchAcceptanceDelay() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME / 2 + TEST_AUTO_DISMISS_TIME);

        assertTrue(hum.isHeadsUpEntry(entry.getKey()));
    }


    @Test
    public void testShowNotification_autoDismissesWithDefaultTimeout() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2);

        assertFalse(hum.isHeadsUpEntry(entry.getKey()));
    }


    @Test
    public void testShowNotification_stickyForSomeTime_autoDismissesWithStickyTimeout() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyForSomeTimeEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_AUTO_DISMISS_TIME + TEST_STICKY_AUTO_DISMISS_TIME) / 2);

        assertTrue(hum.isHeadsUpEntry(entry.getKey()));
    }


    @Test
    public void testShowNotification_sticky_neverAutoDismisses() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME + 2 * TEST_A11Y_AUTO_DISMISS_TIME);

        assertTrue(hum.isHeadsUpEntry(entry.getKey()));
    }


    @Test
    public void testShowNotification_autoDismissesWithAccessibilityTimeout() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);
        useAccessibilityTimeout(true);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2);

        assertTrue(hum.isHeadsUpEntry(entry.getKey()));
    }


    @Test
    public void testShowNotification_stickyForSomeTime_autoDismissesWithAccessibilityTimeout() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyForSomeTimeEntry(/* id = */ 0);
        useAccessibilityTimeout(true);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_STICKY_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2);

        assertTrue(hum.isHeadsUpEntry(entry.getKey()));
    }


    @Test
    public void testRemoveNotification_beforeMinimumDisplayTime() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);

        final boolean removedImmediately = hum.removeNotification(
                entry.getKey(), /* releaseImmediately = */ false);
        assertFalse(removedImmediately);
        assertTrue(hum.isHeadsUpEntry(entry.getKey()));

        mSystemClock.advanceTime((TEST_MINIMUM_DISPLAY_TIME + TEST_AUTO_DISMISS_TIME) / 2);

        assertFalse(hum.isHeadsUpEntry(entry.getKey()));
    }


    @Test
    public void testRemoveNotification_afterMinimumDisplayTime() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);
        mSystemClock.advanceTime((TEST_MINIMUM_DISPLAY_TIME + TEST_AUTO_DISMISS_TIME) / 2);

        assertTrue(hum.isHeadsUpEntry(entry.getKey()));

        final boolean removedImmediately = hum.removeNotification(
                entry.getKey(), /* releaseImmediately = */ false);
        assertTrue(removedImmediately);
        assertFalse(hum.isHeadsUpEntry(entry.getKey()));
    }


    @Test
    public void testRemoveNotification_releaseImmediately() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0, mContext);

        hum.showNotification(entry);

        final boolean removedImmediately = hum.removeNotification(
                entry.getKey(), /* releaseImmediately = */ true);
        assertTrue(removedImmediately);
        assertFalse(hum.isHeadsUpEntry(entry.getKey()));
    }


    @Test
    public void testIsSticky_rowPinnedAndExpanded_true() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0,
                mContext);
        when(mRow.isPinned()).thenReturn(true);
        notifEntry.setRow(mRow);

        hum.showNotification(notifEntry);

        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(
                notifEntry.getKey());
        headsUpEntry.setExpanded(true);

        assertTrue(hum.isSticky(notifEntry.getKey()));
    }

    @Test
    public void testIsSticky_remoteInputActive_true() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0,
                mContext);

        hum.showNotification(notifEntry);

        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(
                notifEntry.getKey());
        headsUpEntry.mRemoteInputActive = true;

        assertTrue(hum.isSticky(notifEntry.getKey()));
    }

    @Test
    public void testIsSticky_hasFullScreenIntent_true() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry =
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id = */ 0, mContext);

        hum.showNotification(notifEntry);

        assertTrue(hum.isSticky(notifEntry.getKey()));
    }


    @Test
    public void testIsSticky_stickyForSomeTime_false() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyForSomeTimeEntry(/* id = */ 0);

        hum.showNotification(entry);

        assertFalse(hum.isSticky(entry.getKey()));
    }


    @Test
    public void testIsSticky_false() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0,
                mContext);

        hum.showNotification(notifEntry);

        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(
                notifEntry.getKey());
        headsUpEntry.setExpanded(false);
        headsUpEntry.mRemoteInputActive = false;

        assertFalse(hum.isSticky(notifEntry.getKey()));
    }

    @Test
    public void testCompareTo_withNullEntries() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry alertEntry = new NotificationEntryBuilder().setTag("alert").build();

        hum.showNotification(alertEntry);

        assertThat(hum.compare(alertEntry, null)).isLessThan(0);
        assertThat(hum.compare(null, alertEntry)).isGreaterThan(0);
        assertThat(hum.compare(null, null)).isEqualTo(0);
    }

    @Test
    public void testCompareTo_withNonAlertEntries() {
        final BaseHeadsUpManager hum = createHeadsUpManager();

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
        final BaseHeadsUpManager hum = createHeadsUpManager();

        final BaseHeadsUpManager.HeadsUpEntry ongoingCall = hum.new HeadsUpEntry(
                new NotificationEntryBuilder()
                        .setSbn(HeadsUpManagerTestUtil.createSbn(/* id = */ 0,
                                new Notification.Builder(mContext, "")
                                        .setCategory(Notification.CATEGORY_CALL)
                                        .setOngoing(true)))
                        .build());

        final BaseHeadsUpManager.HeadsUpEntry activeRemoteInput = hum.new HeadsUpEntry(
                HeadsUpManagerTestUtil.createEntry(/* id = */ 1, mContext));
        activeRemoteInput.mRemoteInputActive = true;

        assertThat(ongoingCall.compareTo(activeRemoteInput)).isLessThan(0);
        assertThat(activeRemoteInput.compareTo(ongoingCall)).isGreaterThan(0);
    }

    @Test
    public void testAlertEntryCompareTo_incomingCallLessThanActiveRemoteInput() {
        final BaseHeadsUpManager hum = createHeadsUpManager();

        final Person person = new Person.Builder().setName("person").build();
        final PendingIntent intent = mock(PendingIntent.class);
        final BaseHeadsUpManager.HeadsUpEntry incomingCall = hum.new HeadsUpEntry(
                new NotificationEntryBuilder()
                        .setSbn(HeadsUpManagerTestUtil.createSbn(/* id = */ 0,
                                new Notification.Builder(mContext, "")
                                        .setStyle(Notification.CallStyle
                                                .forIncomingCall(person, intent, intent))))
                        .build());

        final BaseHeadsUpManager.HeadsUpEntry activeRemoteInput = hum.new HeadsUpEntry(
                HeadsUpManagerTestUtil.createEntry(/* id = */ 1, mContext));
        activeRemoteInput.mRemoteInputActive = true;

        assertThat(incomingCall.compareTo(activeRemoteInput)).isLessThan(0);
        assertThat(activeRemoteInput.compareTo(incomingCall)).isGreaterThan(0);
    }

    @Test
    @EnableFlags(NotificationThrottleHun.FLAG_NAME)
    public void testPinEntry_logsPeek_throttleEnabled() {
        final BaseHeadsUpManager hum = createHeadsUpManager();

        // Needs full screen intent in order to be pinned
        final BaseHeadsUpManager.HeadsUpEntry entryToPin = hum.new HeadsUpEntry(
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id = */ 0, mContext));

        // Note: the standard way to show a notification would be calling showNotification rather
        // than onAlertEntryAdded. However, in practice showNotification in effect adds
        // the notification and then updates it; in order to not log twice, the entry needs
        // to have a functional ExpandableNotificationRow that can keep track of whether it's
        // pinned or not (via isRowPinned()). That feels like a lot to pull in to test this one bit.
        hum.onEntryAdded(entryToPin);

        assertEquals(2, mUiEventLoggerFake.numLogs());
        assertEquals(AvalancheController.ThrottleEvent.SHOWN.getId(),
                mUiEventLoggerFake.eventId(0));
        assertEquals(BaseHeadsUpManager.NotificationPeekEvent.NOTIFICATION_PEEK.getId(),
                mUiEventLoggerFake.eventId(1));
    }

    @Test
    @DisableFlags(NotificationThrottleHun.FLAG_NAME)
    public void testPinEntry_logsPeek_throttleDisabled() {
        final BaseHeadsUpManager hum = createHeadsUpManager();

        // Needs full screen intent in order to be pinned
        final BaseHeadsUpManager.HeadsUpEntry entryToPin = hum.new HeadsUpEntry(
                HeadsUpManagerTestUtil.createFullScreenIntentEntry(/* id = */ 0, mContext));

        // Note: the standard way to show a notification would be calling showNotification rather
        // than onAlertEntryAdded. However, in practice showNotification in effect adds
        // the notification and then updates it; in order to not log twice, the entry needs
        // to have a functional ExpandableNotificationRow that can keep track of whether it's
        // pinned or not (via isRowPinned()). That feels like a lot to pull in to test this one bit.
        hum.onEntryAdded(entryToPin);

        assertEquals(1, mUiEventLoggerFake.numLogs());
        assertEquals(BaseHeadsUpManager.NotificationPeekEvent.NOTIFICATION_PEEK.getId(),
                mUiEventLoggerFake.eventId(0));
    }

    @Test
    public void testSetUserActionMayIndirectlyRemove() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = HeadsUpManagerTestUtil.createEntry(/* id = */ 0,
                mContext);

        hum.showNotification(notifEntry);

        assertFalse(hum.canRemoveImmediately(notifEntry.getKey()));

        hum.setUserActionMayIndirectlyRemove(notifEntry);

        assertTrue(hum.canRemoveImmediately(notifEntry.getKey()));
    }
}
