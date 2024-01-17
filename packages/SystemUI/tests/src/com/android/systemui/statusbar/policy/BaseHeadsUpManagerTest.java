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
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED;
import static com.android.systemui.util.concurrency.MockExecutorHandlerKt.mockExecutorHandler;

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

import android.app.ActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.content.Context;
import android.content.Intent;
import android.graphics.Region;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.internal.logging.testing.UiEventLoggerFake;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.FakeGlobalSettings;
import com.android.systemui.util.settings.GlobalSettings;
import com.android.systemui.util.time.FakeSystemClock;
import com.android.systemui.util.time.SystemClock;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class BaseHeadsUpManagerTest extends SysuiTestCase {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private static final String TEST_PACKAGE_NAME = "BaseHeadsUpManagerTest";

    private static final int TEST_TOUCH_ACCEPTANCE_TIME = 200;
    private static final int TEST_A11Y_AUTO_DISMISS_TIME = 1_000;

    private UiEventLoggerFake mUiEventLoggerFake = new UiEventLoggerFake();
    private final HeadsUpManagerLogger mLogger = spy(new HeadsUpManagerLogger(logcatLogBuffer()));
    @Mock private AccessibilityManagerWrapper mAccessibilityMgr;

    private static final int TEST_UID = 0;

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

    private final class TestableHeadsUpManager extends BaseHeadsUpManager {

        private HeadsUpEntry mLastCreatedEntry;

        TestableHeadsUpManager(Context context,
                HeadsUpManagerLogger logger,
                DelayableExecutor executor,
                GlobalSettings globalSettings,
                SystemClock systemClock,
                AccessibilityManagerWrapper accessibilityManagerWrapper,
                UiEventLogger uiEventLogger) {
            super(context, logger, mockExecutorHandler(executor), globalSettings, systemClock,
                    executor, accessibilityManagerWrapper, uiEventLogger);

            mTouchAcceptanceDelay = TEST_TOUCH_ACCEPTANCE_TIME;
            mMinimumDisplayTime = TEST_MINIMUM_DISPLAY_TIME;
            mAutoDismissTime = TEST_AUTO_DISMISS_TIME;
            mStickyForSomeTimeAutoDismissTime = TEST_STICKY_AUTO_DISMISS_TIME;

        }

        @Override
        protected HeadsUpEntry createAlertEntry() {
            mLastCreatedEntry = spy(super.createAlertEntry());
            return mLastCreatedEntry;
        }

        @Override
        public int getContentFlag() {
            return FLAG_CONTENT_VIEW_CONTRACTED;
        }

        // The following are only implemented by HeadsUpManagerPhone. If you need them, use that.
        @Override
        public void addHeadsUpPhoneListener(@NonNull OnHeadsUpPhoneListenerChange listener) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void addSwipedOutNotification(@NonNull String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void extendHeadsUp() {
            throw new UnsupportedOperationException();
        }

        @Nullable
        @Override
        public Region getTouchableRegion() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isHeadsUpGoingAway() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void onExpandingFinished() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean removeNotification(@NonNull String key, boolean releaseImmediately,
                boolean animate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setAnimationStateHandler(@NonNull AnimationStateHandler handler) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setGutsShown(@NonNull NotificationEntry entry, boolean gutsShown) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setHeadsUpGoingAway(boolean headsUpGoingAway) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setRemoteInputActive(@NonNull NotificationEntry entry,
                boolean remoteInputActive) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setTrackingHeadsUp(boolean tracking) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean shouldSwallowClick(@NonNull String key) {
            throw new UnsupportedOperationException();
        }
    }

    protected StatusBarNotification createSbn(int id, Notification n) {
        return new StatusBarNotification(
                TEST_PACKAGE_NAME /* pkg */,
                TEST_PACKAGE_NAME,
                id,
                null /* tag */,
                TEST_UID,
                0 /* initialPid */,
                n,
                new UserHandle(ActivityManager.getCurrentUser()),
                null /* overrideGroupKey */,
                0 /* postTime */);
    }

    protected StatusBarNotification createSbn(int id, Notification.Builder n) {
        return createSbn(id, n.build());
    }

    protected StatusBarNotification createSbn(int id) {
        final Notification.Builder b = new Notification.Builder(mContext, "")
                .setSmallIcon(R.drawable.ic_person)
                .setContentTitle("Title")
                .setContentText("Text");
        return createSbn(id, b);
    }

    protected NotificationEntry createEntry(int id, Notification n) {
        return new NotificationEntryBuilder().setSbn(createSbn(id, n)).build();
    }

    protected NotificationEntry createEntry(int id) {
        return new NotificationEntryBuilder().setSbn(createSbn(id)).build();
    }


    private BaseHeadsUpManager createHeadsUpManager() {
        return new TestableHeadsUpManager(mContext, mLogger, mExecutor, mGlobalSettings,
                mSystemClock, mAccessibilityMgr, mUiEventLoggerFake);
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

    @Test
    public void testShowNotification_addsEntry() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        alm.showNotification(entry);

        assertTrue(alm.isAlerting(entry.getKey()));
        assertTrue(alm.hasNotifications());
        assertEquals(entry, alm.getEntry(entry.getKey()));
    }

    @Test
    public void testShowNotification_autoDismisses() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        alm.showNotification(entry);
        mSystemClock.advanceTime(TEST_AUTO_DISMISS_TIME * 3 / 2);

        assertFalse(alm.isAlerting(entry.getKey()));
    }

    @Test
    public void testRemoveNotification_removeDeferred() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        alm.showNotification(entry);

        final boolean removedImmediately = alm.removeNotification(
                entry.getKey(), /* releaseImmediately = */ false);
        assertFalse(removedImmediately);
        assertTrue(alm.isAlerting(entry.getKey()));
    }

    @Test
    public void testRemoveNotification_forceRemove() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        alm.showNotification(entry);

        final boolean removedImmediately = alm.removeNotification(
                entry.getKey(), /* releaseImmediately = */ true);
        assertTrue(removedImmediately);
        assertFalse(alm.isAlerting(entry.getKey()));
    }

    @Test
    public void testReleaseAllImmediately() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        for (int i = 0; i < TEST_NUM_NOTIFICATIONS; i++) {
            final NotificationEntry entry = createEntry(i);
            entry.setRow(mRow);
            alm.showNotification(entry);
        }

        alm.releaseAllImmediately();

        assertEquals(0, alm.getAllEntries().count());
    }

    @Test
    public void testCanRemoveImmediately_notShownLongEnough() {
        final BaseHeadsUpManager alm = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        alm.showNotification(entry);

        // The entry has just been added so we should not remove immediately.
        assertFalse(alm.canRemoveImmediately(entry.getKey()));
    }

    @Test
    public void testHunRemovedLogging() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createEntry(/* id = */ 0);
        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = mock(
                BaseHeadsUpManager.HeadsUpEntry.class);
        headsUpEntry.mEntry = notifEntry;

        hum.onAlertEntryRemoved(headsUpEntry);

        verify(mLogger, times(1)).logNotificationActuallyRemoved(eq(notifEntry));
    }

    @Test
    public void testShouldHeadsUpBecomePinned_hasFSI_notUnpinned_true() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createFullScreenIntentEntry(/* id = */ 0);

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
        final NotificationEntry notifEntry = createFullScreenIntentEntry(/* id = */ 0);

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
        final NotificationEntry entry = createEntry(/* id = */ 0);

        assertFalse(hum.shouldHeadsUpBecomePinned(entry));
    }


    @Test
    public void testShowNotification_autoDismissesIncludingTouchAcceptanceDelay() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME / 2 + TEST_AUTO_DISMISS_TIME);

        assertTrue(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testShowNotification_autoDismissesWithDefaultTimeout() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2);

        assertFalse(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testShowNotification_stickyForSomeTime_autoDismissesWithStickyTimeout() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyForSomeTimeEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_AUTO_DISMISS_TIME + TEST_STICKY_AUTO_DISMISS_TIME) / 2);

        assertTrue(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testShowNotification_sticky_neverAutoDismisses() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME + 2 * TEST_A11Y_AUTO_DISMISS_TIME);

        assertTrue(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testShowNotification_autoDismissesWithAccessibilityTimeout() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);
        useAccessibilityTimeout(true);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2);

        assertTrue(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testShowNotification_stickyForSomeTime_autoDismissesWithAccessibilityTimeout() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createStickyForSomeTimeEntry(/* id = */ 0);
        useAccessibilityTimeout(true);

        hum.showNotification(entry);
        mSystemClock.advanceTime(TEST_TOUCH_ACCEPTANCE_TIME
                + (TEST_STICKY_AUTO_DISMISS_TIME + TEST_A11Y_AUTO_DISMISS_TIME) / 2);

        assertTrue(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testRemoveNotification_beforeMinimumDisplayTime() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);

        final boolean removedImmediately = hum.removeNotification(
                entry.getKey(), /* releaseImmediately = */ false);
        assertFalse(removedImmediately);
        assertTrue(hum.isAlerting(entry.getKey()));

        mSystemClock.advanceTime((TEST_MINIMUM_DISPLAY_TIME + TEST_AUTO_DISMISS_TIME) / 2);

        assertFalse(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testRemoveNotification_afterMinimumDisplayTime() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);
        useAccessibilityTimeout(false);

        hum.showNotification(entry);
        mSystemClock.advanceTime((TEST_MINIMUM_DISPLAY_TIME + TEST_AUTO_DISMISS_TIME) / 2);

        assertTrue(hum.isAlerting(entry.getKey()));

        final boolean removedImmediately = hum.removeNotification(
                entry.getKey(), /* releaseImmediately = */ false);
        assertTrue(removedImmediately);
        assertFalse(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testRemoveNotification_releaseImmediately() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        hum.showNotification(entry);

        final boolean removedImmediately = hum.removeNotification(
                entry.getKey(), /* releaseImmediately = */ true);
        assertTrue(removedImmediately);
        assertFalse(hum.isAlerting(entry.getKey()));
    }


    @Test
    public void testIsSticky_rowPinnedAndExpanded_true() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createEntry(/* id = */ 0);
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
        final NotificationEntry notifEntry = createEntry(/* id = */ 0);

        hum.showNotification(notifEntry);

        final BaseHeadsUpManager.HeadsUpEntry headsUpEntry = hum.getHeadsUpEntry(
                notifEntry.getKey());
        headsUpEntry.mRemoteInputActive = true;

        assertTrue(hum.isSticky(notifEntry.getKey()));
    }

    @Test
    public void testIsSticky_hasFullScreenIntent_true() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createFullScreenIntentEntry(/* id = */ 0);

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
        final NotificationEntry notifEntry = createEntry(/* id = */ 0);

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

        final BaseHeadsUpManager.HeadsUpEntry ongoingCall = hum.new HeadsUpEntry();
        ongoingCall.setEntry(new NotificationEntryBuilder()
                .setSbn(createSbn(/* id = */ 0,
                        new Notification.Builder(mContext, "")
                                .setCategory(Notification.CATEGORY_CALL)
                                .setOngoing(true)))
                .build());

        final BaseHeadsUpManager.HeadsUpEntry activeRemoteInput = hum.new HeadsUpEntry();
        activeRemoteInput.setEntry(createEntry(/* id = */ 1));
        activeRemoteInput.mRemoteInputActive = true;

        assertThat(ongoingCall.compareTo(activeRemoteInput)).isLessThan(0);
        assertThat(activeRemoteInput.compareTo(ongoingCall)).isGreaterThan(0);
    }

    @Test
    public void testAlertEntryCompareTo_incomingCallLessThanActiveRemoteInput() {
        final BaseHeadsUpManager hum = createHeadsUpManager();

        final BaseHeadsUpManager.HeadsUpEntry incomingCall = hum.new HeadsUpEntry();
        final Person person = new Person.Builder().setName("person").build();
        final PendingIntent intent = mock(PendingIntent.class);
        incomingCall.setEntry(new NotificationEntryBuilder()
                .setSbn(createSbn(/* id = */ 0,
                        new Notification.Builder(mContext, "")
                                .setStyle(Notification.CallStyle
                                        .forIncomingCall(person, intent, intent))))
                .build());

        final BaseHeadsUpManager.HeadsUpEntry activeRemoteInput = hum.new HeadsUpEntry();
        activeRemoteInput.setEntry(createEntry(/* id = */ 1));
        activeRemoteInput.mRemoteInputActive = true;

        assertThat(incomingCall.compareTo(activeRemoteInput)).isLessThan(0);
        assertThat(activeRemoteInput.compareTo(incomingCall)).isGreaterThan(0);
    }

    @Test
    public void testPinEntry_logsPeek() {
        final BaseHeadsUpManager hum = createHeadsUpManager();

        // Needs full screen intent in order to be pinned
        final BaseHeadsUpManager.HeadsUpEntry entryToPin = hum.new HeadsUpEntry();
        entryToPin.setEntry(createFullScreenIntentEntry(/* id = */ 0));

        // Note: the standard way to show a notification would be calling showNotification rather
        // than onAlertEntryAdded. However, in practice showNotification in effect adds
        // the notification and then updates it; in order to not log twice, the entry needs
        // to have a functional ExpandableNotificationRow that can keep track of whether it's
        // pinned or not (via isRowPinned()). That feels like a lot to pull in to test this one bit.
        hum.onAlertEntryAdded(entryToPin);

        assertEquals(1, mUiEventLoggerFake.numLogs());
        assertEquals(BaseHeadsUpManager.NotificationPeekEvent.NOTIFICATION_PEEK.getId(),
                mUiEventLoggerFake.eventId(0));
    }

    @Test
    public void testSetUserActionMayIndirectlyRemove() {
        final BaseHeadsUpManager hum = createHeadsUpManager();
        final NotificationEntry notifEntry = createEntry(/* id = */ 0);

        hum.showNotification(notifEntry);

        assertFalse(hum.canRemoveImmediately(notifEntry.getKey()));

        hum.setUserActionMayIndirectlyRemove(notifEntry);

        assertTrue(hum.canRemoveImmediately(notifEntry.getKey()));
    }
}
