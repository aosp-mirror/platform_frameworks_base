/*
 * Copyright (C) 2018 The Android Open Source Project
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


package com.android.systemui.statusbar;

import static com.android.systemui.dump.LogBufferHelperKt.logcatLogBuffer;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_CONTRACTED;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.spy;

import android.app.ActivityManager;
import android.app.Notification;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.res.R;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.NotificationEntryBuilder;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.policy.HeadsUpManagerLogger;
import com.android.systemui.util.concurrency.DelayableExecutor;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.settings.FakeGlobalSettings;
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
public class AlertingNotificationManagerTest extends SysuiTestCase {
    @Rule
    public MockitoRule rule = MockitoJUnit.rule();

    private static final String TEST_PACKAGE_NAME = "test";
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
    }

    private static class TestableAlertingNotificationManager extends AlertingNotificationManager {
        private AlertEntry mLastCreatedEntry;

        private TestableAlertingNotificationManager(SystemClock systemClock,
                DelayableExecutor executor) {
            super(new HeadsUpManagerLogger(logcatLogBuffer()), systemClock, executor);
            mMinimumDisplayTime = TEST_MINIMUM_DISPLAY_TIME;
            mAutoDismissTime = TEST_AUTO_DISMISS_TIME;
            mStickyForSomeTimeAutoDismissTime = TEST_STICKY_AUTO_DISMISS_TIME;
        }

        @Override
        protected void onAlertEntryAdded(AlertEntry alertEntry) {}

        @Override
        protected void onAlertEntryRemoved(AlertEntry alertEntry) {}

        @Override
        protected AlertEntry createAlertEntry() {
            mLastCreatedEntry = spy(super.createAlertEntry());
            return mLastCreatedEntry;
        }

        @Override
        public int getContentFlag() {
            return FLAG_CONTENT_VIEW_CONTRACTED;
        }
    }

    protected AlertingNotificationManager createAlertingNotificationManager() {
        return new TestableAlertingNotificationManager(mSystemClock, mExecutor);
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


    @Test
    public void testShowNotification_addsEntry() {
        final AlertingNotificationManager alm = createAlertingNotificationManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        alm.showNotification(entry);

        assertTrue(alm.isAlerting(entry.getKey()));
        assertTrue(alm.hasNotifications());
        assertEquals(entry, alm.getEntry(entry.getKey()));
    }

    @Test
    public void testShowNotification_autoDismisses() {
        final AlertingNotificationManager alm = createAlertingNotificationManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        alm.showNotification(entry);
        mSystemClock.advanceTime(TEST_AUTO_DISMISS_TIME * 3 / 2);

        assertFalse(alm.isAlerting(entry.getKey()));
    }

    @Test
    public void testRemoveNotification_removeDeferred() {
        final AlertingNotificationManager alm = createAlertingNotificationManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        alm.showNotification(entry);

        final boolean removedImmediately = alm.removeNotification(
                entry.getKey(), /* releaseImmediately = */ false);
        assertFalse(removedImmediately);
        assertTrue(alm.isAlerting(entry.getKey()));
    }

    @Test
    public void testRemoveNotification_forceRemove() {
        final AlertingNotificationManager alm = createAlertingNotificationManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        alm.showNotification(entry);

        final boolean removedImmediately = alm.removeNotification(
                entry.getKey(), /* releaseImmediately = */ true);
        assertTrue(removedImmediately);
        assertFalse(alm.isAlerting(entry.getKey()));
    }

    @Test
    public void testReleaseAllImmediately() {
        final AlertingNotificationManager alm = createAlertingNotificationManager();
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
        final AlertingNotificationManager alm = createAlertingNotificationManager();
        final NotificationEntry entry = createEntry(/* id = */ 0);

        alm.showNotification(entry);

        // The entry has just been added so we should not remove immediately.
        assertFalse(alm.canRemoveImmediately(entry.getKey()));
    }
}
