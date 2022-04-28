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

import static com.android.systemui.statusbar.notification.NotificationEntryManager.UNDEFINED_DISMISS_REASON;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_HEADS_UP;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.collection.legacy.NotificationGroupManagerLegacy;
import com.android.systemui.statusbar.notification.people.PeopleNotificationIdentifier;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline.BindCallback;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;
import com.android.systemui.statusbar.policy.HeadsUpManager;
import com.android.systemui.statusbar.policy.HeadsUpManagerLogger;
import com.android.wm.shell.bubbles.Bubbles;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.HashMap;
import java.util.Optional;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationGroupAlertTransferHelperTest extends SysuiTestCase {
    @Rule public MockitoRule rule = MockitoJUnit.rule();

    private NotificationGroupAlertTransferHelper mGroupAlertTransferHelper;
    private NotificationGroupManagerLegacy mGroupManager;
    private HeadsUpManager mHeadsUpManager;
    @Mock private NotificationEntryManager mNotificationEntryManager;
    @Mock private RowContentBindStage mBindStage;
    @Mock PeopleNotificationIdentifier mPeopleNotificationIdentifier;
    @Mock StatusBarStateController mStatusBarStateController;
    @Captor private ArgumentCaptor<NotificationEntryListener> mListenerCaptor;
    private NotificationEntryListener mNotificationEntryListener;
    private final HashMap<String, NotificationEntry> mPendingEntries = new HashMap<>();
    private final NotificationGroupTestHelper mGroupTestHelper =
            new NotificationGroupTestHelper(mContext);


    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mHeadsUpManager = new HeadsUpManager(mContext, mock(HeadsUpManagerLogger.class)) {};

        when(mNotificationEntryManager.getPendingNotificationsIterator())
                .thenReturn(mPendingEntries.values());

        mGroupManager = new NotificationGroupManagerLegacy(
                mStatusBarStateController,
                () -> mPeopleNotificationIdentifier,
                Optional.of(mock(Bubbles.class)),
                mock(DumpManager.class));
        mDependency.injectTestDependency(NotificationGroupManagerLegacy.class, mGroupManager);
        mGroupManager.setHeadsUpManager(mHeadsUpManager);

        when(mBindStage.getStageParams(any())).thenReturn(new RowContentBindParams());

        mGroupAlertTransferHelper = new NotificationGroupAlertTransferHelper(
                mBindStage, mStatusBarStateController, mGroupManager);
        mGroupAlertTransferHelper.setHeadsUpManager(mHeadsUpManager);

        mGroupAlertTransferHelper.bind(mNotificationEntryManager, mGroupManager);
        verify(mNotificationEntryManager).addNotificationEntryListener(mListenerCaptor.capture());
        mNotificationEntryListener = mListenerCaptor.getValue();
        mHeadsUpManager.addListener(mGroupAlertTransferHelper);
    }

    @After
    public void tearDown() {
        mHeadsUpManager.mHandler.removeCallbacksAndMessages(null);
    }

    private void mockHasHeadsUpContentView(NotificationEntry entry,
            boolean hasHeadsUpContentView) {
        RowContentBindParams params = new RowContentBindParams();
        if (hasHeadsUpContentView) {
            params.requireContentViews(FLAG_CONTENT_VIEW_HEADS_UP);
        }
        when(mBindStage.getStageParams(eq(entry))).thenReturn(params);
    }

    private void mockHasHeadsUpContentView(NotificationEntry entry) {
        mockHasHeadsUpContentView(entry, true);
    }

    private void mockIsPriority(NotificationEntry priorityEntry) {
        when(mPeopleNotificationIdentifier.getPeopleNotificationType(eq(priorityEntry)))
                .thenReturn(PeopleNotificationIdentifier.TYPE_IMPORTANT_PERSON);
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransfersToChild() {
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mHeadsUpManager.showNotification(summaryEntry);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();

        mockHasHeadsUpContentView(childEntry);

        // Summary will be suppressed because there is only one child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // A suppressed summary should transfer its alert state to the child.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertTrue(mHeadsUpManager.isAlerting(childEntry.getKey()));
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
        mPendingEntries.put(childEntry2.getKey(), childEntry2);
        mNotificationEntryListener.onPendingEntryAdded(childEntry2);
        mGroupManager.onEntryAdded(childEntry2);

        // The alert state should transfer back to the summary as there is now more than one
        // child and the summary should no longer be suppressed.
        assertTrue(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(childEntry.getKey()));
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
        mPendingEntries.put(childEntry2.getKey(), childEntry2);
        mNotificationEntryListener.onPendingEntryAdded(childEntry2);
        mGroupManager.onEntryAdded(childEntry2);

        // Dozing changed so no reason to re-alert summary.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransferDoesNotAlertChildIfUninflated() {
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mHeadsUpManager.showNotification(summaryEntry);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        mockHasHeadsUpContentView(childEntry, false);

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Alert is immediately removed from summary, but we do not show child yet either as its
        // content is not inflated.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(childEntry.getKey()));
        assertTrue(mGroupAlertTransferHelper.isAlertTransferPending(childEntry));
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransferAlertsChildOnInflation() {
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification();
        mHeadsUpManager.showNotification(summaryEntry);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification();
        mockHasHeadsUpContentView(childEntry, false);

        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Child entry finishes its inflation.
        ArgumentCaptor<BindCallback> callbackCaptor = ArgumentCaptor.forClass(BindCallback.class);
        verify(mBindStage).requestRebind(eq(childEntry), callbackCaptor.capture());
        callbackCaptor.getValue().onBindFinished(childEntry);

        // Alert is immediately removed from summary, and we show child as its content is inflated.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertTrue(mHeadsUpManager.isAlerting(childEntry.getKey()));
    }

    @Test
    public void testSuppressedSummaryHeadsUpTransferBackAbortsChildInflation() {
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        RowContentBindParams params = new RowContentBindParams();
        when(mBindStage.getStageParams(eq(childEntry))).thenReturn(params);

        NotificationEntry childEntry2 =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Add second child notification so that summary is no longer suppressed.
        mPendingEntries.put(childEntry2.getKey(), childEntry2);
        mNotificationEntryListener.onPendingEntryAdded(childEntry2);
        mGroupManager.onEntryAdded(childEntry2);

        // Child entry finishes its inflation.
        ArgumentCaptor<BindCallback> callbackCaptor = ArgumentCaptor.forClass(BindCallback.class);
        verify(mBindStage).requestRebind(eq(childEntry), callbackCaptor.capture());
        callbackCaptor.getValue().onBindFinished(childEntry);

        assertTrue((params.getContentViews() & FLAG_CONTENT_VIEW_HEADS_UP) == 0);
        assertFalse(mHeadsUpManager.isAlerting(childEntry.getKey()));
    }

    @Test
    public void testCleanUpPendingAlertInfo() {
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        mockHasHeadsUpContentView(childEntry, false);

        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        mNotificationEntryListener.onEntryRemoved(
                childEntry, null, false, UNDEFINED_DISMISS_REASON);

        assertFalse(mGroupAlertTransferHelper.isAlertTransferPending(childEntry));
    }

    @Test
    public void testUpdateGroupChangeDoesNotTransfer() {
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY);
        mockHasHeadsUpContentView(childEntry, false);

        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Notify that entry changed groups.
        StatusBarNotification oldNotification = childEntry.getSbn();
        StatusBarNotification newSbn = spy(childEntry.getSbn().clone());
        doReturn("other_group").when(newSbn).getGroupKey();
        childEntry.setSbn(newSbn);
        mGroupManager.onEntryUpdated(childEntry, oldNotification);

        assertFalse(mGroupAlertTransferHelper.isAlertTransferPending(childEntry));
    }

    @Test
    public void testUpdateChildToSummaryDoesNotTransfer() {
        final String tag = "fooTag";
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY, 47, tag);
        mockHasHeadsUpContentView(childEntry, false);

        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Update that child to a summary.
        StatusBarNotification oldNotification = childEntry.getSbn();
        childEntry.setSbn(
                mGroupTestHelper.createSummaryNotification(
                        Notification.GROUP_ALERT_SUMMARY, 47, tag).getSbn());
        mGroupManager.onEntryUpdated(childEntry, oldNotification);

        assertFalse(mGroupAlertTransferHelper.isAlertTransferPending(childEntry));
    }

    @Test
    public void testOverriddenSummaryHeadsUpTransfersToPriority() {
        // Creation order is oldest to newest, meaning the priority will be deemed newest
        int groupAlert = Notification.GROUP_ALERT_SUMMARY;
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification(groupAlert);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification(groupAlert);
        NotificationEntry priorityEntry = mGroupTestHelper.createChildNotification(groupAlert);
        mockIsPriority(priorityEntry);

        // summary gets heads up
        mHeadsUpManager.showNotification(summaryEntry);

        mockHasHeadsUpContentView(summaryEntry);
        mockHasHeadsUpContentView(priorityEntry);
        mockHasHeadsUpContentView(childEntry);

        // Summary will have an alertOverride.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(priorityEntry);
        mGroupManager.onEntryAdded(childEntry);

        // An overridden summary should transfer its alert state to the priority.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(childEntry.getKey()));
        assertTrue(mHeadsUpManager.isAlerting(priorityEntry.getKey()));
    }

    @Test
    public void testOverriddenSummaryHeadsUpTransferDoesNotAlertPriorityIfUninflated() {
        // Creation order is oldest to newest, meaning the priority will be deemed newest
        int groupAlert = Notification.GROUP_ALERT_SUMMARY;
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification(groupAlert);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification(groupAlert);
        NotificationEntry priorityEntry = mGroupTestHelper.createChildNotification(groupAlert);
        mockIsPriority(priorityEntry);

        // summary gets heads up
        mHeadsUpManager.showNotification(summaryEntry);

        mockHasHeadsUpContentView(summaryEntry);
        mockHasHeadsUpContentView(priorityEntry, false);
        mockHasHeadsUpContentView(childEntry);

        // Summary will have an alertOverride.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(priorityEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Alert is immediately removed from summary, but we do not show priority yet either as its
        // content is not inflated.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(childEntry.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(priorityEntry.getKey()));
        assertTrue(mGroupAlertTransferHelper.isAlertTransferPending(priorityEntry));
    }

    @Test
    public void testOverriddenSummaryHeadsUpTransfersToPriorityButBackAgain() {
        // Creation order is oldest to newest, meaning the child2 will ultimately be deemed newest
        int groupAlert = Notification.GROUP_ALERT_SUMMARY;
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification(groupAlert);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification(groupAlert);
        NotificationEntry priorityEntry = mGroupTestHelper.createChildNotification(groupAlert);
        NotificationEntry childEntry2 = mGroupTestHelper.createChildNotification(groupAlert);
        mockIsPriority(priorityEntry);

        // summary gets heads up
        mHeadsUpManager.showNotification(summaryEntry);

        mockHasHeadsUpContentView(summaryEntry);
        mockHasHeadsUpContentView(priorityEntry);
        mockHasHeadsUpContentView(childEntry);
        mockHasHeadsUpContentView(childEntry2);

        // Summary will have an alertOverride.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(priorityEntry);
        mGroupManager.onEntryAdded(childEntry);

        // An overridden summary should transfer its alert state to the priority.
        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(childEntry.getKey()));
        assertTrue(mHeadsUpManager.isAlerting(priorityEntry.getKey()));

        mGroupManager.onEntryAdded(childEntry2);

        // An overridden summary should transfer its alert state to the priority.
        assertTrue(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(childEntry.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(childEntry2.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(priorityEntry.getKey()));
    }

    @Test
    public void testOverriddenSuppressedSummaryHeadsUpTransfersToChildThenToPriority() {
        // Creation order is oldest to newest, meaning the priority will ultimately be deemed newest
        int groupAlert = Notification.GROUP_ALERT_SUMMARY;
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification(groupAlert);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification(groupAlert);
        NotificationEntry priorityEntry = mGroupTestHelper.createChildNotification(groupAlert);
        mockIsPriority(priorityEntry);

        // summary gets heads up
        mHeadsUpManager.showNotification(summaryEntry);

        mockHasHeadsUpContentView(summaryEntry);
        mockHasHeadsUpContentView(priorityEntry);
        mockHasHeadsUpContentView(childEntry);

        // Summary will be suppressed, and the child will receive the alert
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertTrue(mHeadsUpManager.isAlerting(childEntry.getKey()));

        // Alert should be transferred "back" from the child to the priority
        mGroupManager.onEntryAdded(priorityEntry);

        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(childEntry.getKey()));
        assertTrue(mHeadsUpManager.isAlerting(priorityEntry.getKey()));
    }

    @Test
    public void testOverriddenSuppressedSummaryHeadsUpTransfersToPriorityThenToChild() {
        // Creation order is oldest to newest, meaning the child will ultimately be deemed newest
        int groupAlert = Notification.GROUP_ALERT_SUMMARY;
        NotificationEntry summaryEntry = mGroupTestHelper.createSummaryNotification(groupAlert);
        NotificationEntry priorityEntry = mGroupTestHelper.createChildNotification(groupAlert);
        NotificationEntry childEntry = mGroupTestHelper.createChildNotification(groupAlert);
        mockIsPriority(priorityEntry);

        // summary gets heads up
        mHeadsUpManager.showNotification(summaryEntry);

        mockHasHeadsUpContentView(summaryEntry);
        mockHasHeadsUpContentView(priorityEntry);
        mockHasHeadsUpContentView(childEntry);

        // Summary will have alert override of the priority
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(priorityEntry);

        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertTrue(mHeadsUpManager.isAlerting(priorityEntry.getKey()));

        // Alert should be transferred "back" from the priority to the child (which is newer)
        mGroupManager.onEntryAdded(childEntry);

        assertFalse(mHeadsUpManager.isAlerting(summaryEntry.getKey()));
        assertTrue(mHeadsUpManager.isAlerting(childEntry.getKey()));
        assertFalse(mHeadsUpManager.isAlerting(priorityEntry.getKey()));
    }

}
