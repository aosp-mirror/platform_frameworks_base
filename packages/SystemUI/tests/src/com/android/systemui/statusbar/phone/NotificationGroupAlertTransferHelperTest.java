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

package com.android.systemui.statusbar.phone;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.policy.HeadsUpManager;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.HashMap;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationGroupAlertTransferHelperTest extends SysuiTestCase {
    @Rule public MockitoRule rule = MockitoJUnit.rule();

    private NotificationGroupAlertTransferHelper mGroupAlertTransferHelper;
    private NotificationGroupManager mGroupManager;
    private HeadsUpManager mHeadsUpManager;
    @Mock private NotificationEntryManager mNotificationEntryManager;
    @Captor
    private ArgumentCaptor<NotificationEntryListener> mListenerCaptor;
    private NotificationEntryListener mNotificationEntryListener;
    private final HashMap<String, NotificationEntry> mPendingEntries = new HashMap<>();
    private final NotificationGroupTestHelper mGroupTestHelper =
            new NotificationGroupTestHelper(mContext);


    @Before
    public void setup() {
        mHeadsUpManager = new HeadsUpManager(mContext) {};

        when(mNotificationEntryManager.getPendingNotificationsIterator())
                .thenReturn(mPendingEntries.values());

        mGroupManager = new NotificationGroupManager(mock(StatusBarStateController.class));
        mDependency.injectTestDependency(NotificationGroupManager.class, mGroupManager);
        mGroupManager.setHeadsUpManager(mHeadsUpManager);

        mGroupAlertTransferHelper = new NotificationGroupAlertTransferHelper();
        mGroupAlertTransferHelper.setHeadsUpManager(mHeadsUpManager);

        mGroupAlertTransferHelper.bind(mNotificationEntryManager, mGroupManager);
        verify(mNotificationEntryManager).addNotificationEntryListener(mListenerCaptor.capture());
        mNotificationEntryListener = mListenerCaptor.getValue();
        mHeadsUpManager.addListener(mGroupAlertTransferHelper);
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransfersToChild() {
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mHeadsUpManager.showNotification(summaryEntry);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();

        // Summary will be suppressed because there is only one child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // A suppressed summary should transfer its alert state to the child.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.key));
        assertTrue(mHeadsUpManager.isAlerting(childEntry.key));
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransfersToChildButBackAgain() {
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry2 =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Add second child notification so that summary is no longer suppressed.
        mPendingEntries.put(childEntry2.key, childEntry2);
        mNotificationEntryListener.onPendingEntryAdded(childEntry2);
        mGroupManager.onEntryAdded(childEntry2);

        // The alert state should transfer back to the summary as there is now more than one
        // child and the summary should no longer be suppressed.
        assertTrue(mHeadsUpManager.isAlerting(summaryEntry.key));
        assertFalse(mHeadsUpManager.isAlerting(childEntry.key));
    }

    @Test
    public void testSuppressedSummaryHeadsUpDoesntTransferBackOnDozingChanged() {
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry2 =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Set dozing to true.
        mGroupAlertTransferHelper.onDozingChanged(true);

        // Add second child notification so that summary is no longer suppressed.
        mPendingEntries.put(childEntry2.key, childEntry2);
        mNotificationEntryListener.onPendingEntryAdded(childEntry2);
        mGroupManager.onEntryAdded(childEntry2);

        // Dozing changed so no reason to re-alert summary.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.key));
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransferDoesNotAlertChildIfUninflated() {
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mHeadsUpManager.showNotification(summaryEntry);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        when(childEntry.getRow().isInflationFlagSet(mHeadsUpManager.getContentFlag()))
            .thenReturn(false);

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Alert is immediately removed from summary, but we do not show child yet either as its
        // content is not inflated.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.key));
        assertFalse(mHeadsUpManager.isAlerting(childEntry.key));
        assertTrue(mGroupAlertTransferHelper.isAlertTransferPending(childEntry));
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransferAlertsChildOnInflation() {
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mHeadsUpManager.showNotification(summaryEntry);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        when(childEntry.getRow().isInflationFlagSet(mHeadsUpManager.getContentFlag()))
            .thenReturn(false);

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        when(childEntry.getRow().isInflationFlagSet(mHeadsUpManager.getContentFlag()))
            .thenReturn(true);
        mNotificationEntryListener.onEntryReinflated(childEntry);

        // Alert is immediately removed from summary, and we show child as its content is inflated.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.key));
        assertTrue(mHeadsUpManager.isAlerting(childEntry.key));
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransferBackAbortsChildInflation() {
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        when(childEntry.getRow().isInflationFlagSet(mHeadsUpManager.getContentFlag()))
            .thenReturn(false);
        NotificationEntry childEntry2 =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Add second child notification so that summary is no longer suppressed.
        mPendingEntries.put(childEntry2.key, childEntry2);
        mNotificationEntryListener.onPendingEntryAdded(childEntry2);
        mGroupManager.onEntryAdded(childEntry2);

        // Child entry finishes its inflation.
        when(childEntry.getRow().isInflationFlagSet(mHeadsUpManager.getContentFlag()))
            .thenReturn(true);
        mNotificationEntryListener.onEntryReinflated(childEntry);

        verify(childEntry.getRow(), times(1)).freeContentViewWhenSafe(mHeadsUpManager
            .getContentFlag());
        assertFalse(mHeadsUpManager.isAlerting(childEntry.key));
    }

    @Test
    public void testCleanUpPendingAlertInfo() {
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        when(childEntry.getRow().isInflationFlagSet(mHeadsUpManager.getContentFlag()))
            .thenReturn(false);
        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        mNotificationEntryListener.onEntryRemoved(childEntry, null, false);

        assertFalse(mGroupAlertTransferHelper.isAlertTransferPending(childEntry));
    }

    @Test
    public void testUpdateGroupChangeDoesNotTransfer() {
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        when(childEntry.getRow().isInflationFlagSet(mHeadsUpManager.getContentFlag()))
            .thenReturn(false);
        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Notify that entry changed groups.
        StatusBarNotification oldNotification = childEntry.notification;
        StatusBarNotification newSbn = spy(childEntry.notification.clone());
        doReturn("other_group").when(newSbn).getGroupKey();
        childEntry.notification = newSbn;
        mGroupManager.onEntryUpdated(childEntry, oldNotification);

        assertFalse(mGroupAlertTransferHelper.isAlertTransferPending(childEntry));
    }

    @Test
    public void testUpdateChildToSummaryDoesNotTransfer() {
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        when(childEntry.getRow().isInflationFlagSet(mHeadsUpManager.getContentFlag()))
            .thenReturn(false);
        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Update that child to a summary.
        StatusBarNotification oldNotification = childEntry.notification;
        childEntry.notification = mGroupTestHelper.createSummaryNotification(
                Notification.GROUP_ALERT_SUMMARY).notification;
        mGroupManager.onEntryUpdated(childEntry, oldNotification);

        assertFalse(mGroupAlertTransferHelper.isAlertTransferPending(childEntry));
    }
}
