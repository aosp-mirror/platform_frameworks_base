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
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.NotifBindPipeline.BindCallback;
import com.android.systemui.statusbar.notification.row.RowContentBindParams;
import com.android.systemui.statusbar.notification.row.RowContentBindStage;
import com.android.systemui.statusbar.policy.HeadsUpManager;

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

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationGroupAlertTransferHelperTest extends SysuiTestCase {
    @Rule public MockitoRule rule = MockitoJUnit.rule();

    private NotificationGroupAlertTransferHelper mGroupAlertTransferHelper;
    private NotificationGroupManager mGroupManager;
    private HeadsUpManager mHeadsUpManager;
    @Mock private NotificationEntryManager mNotificationEntryManager;
    @Mock private RowContentBindStage mBindStage;
    @Captor private ArgumentCaptor<NotificationEntryListener> mListenerCaptor;
    private NotificationEntryListener mNotificationEntryListener;
    private final HashMap<String, NotificationEntry> mPendingEntries = new HashMap<>();
    private final NotificationGroupTestHelper mGroupTestHelper =
            new NotificationGroupTestHelper(mContext);


    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(BubbleController.class);
        mHeadsUpManager = new HeadsUpManager(mContext) {};

        when(mNotificationEntryManager.getPendingNotificationsIterator())
                .thenReturn(mPendingEntries.values());

        mGroupManager = new NotificationGroupManager(mock(StatusBarStateController.class));
        mDependency.injectTestDependency(NotificationGroupManager.class, mGroupManager);
        mGroupManager.setHeadsUpManager(mHeadsUpManager);

        when(mBindStage.getStageParams(any())).thenReturn(new RowContentBindParams());

        mGroupAlertTransferHelper = new NotificationGroupAlertTransferHelper(mBindStage);
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

        RowContentBindParams params = new RowContentBindParams();
        params.requireContentViews(FLAG_CONTENT_VIEW_HEADS_UP);
        when(mBindStage.getStageParams(eq(childEntry))).thenReturn(params);

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
        RowContentBindParams params = new RowContentBindParams();
        when(mBindStage.getStageParams(eq(childEntry))).thenReturn(params);

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
        RowContentBindParams params = new RowContentBindParams();
        when(mBindStage.getStageParams(eq(childEntry))).thenReturn(params);

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
        RowContentBindParams params = new RowContentBindParams();
        when(mBindStage.getStageParams(eq(childEntry))).thenReturn(params);

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
        RowContentBindParams params = new RowContentBindParams();
        when(mBindStage.getStageParams(eq(childEntry))).thenReturn(params);

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
        NotificationEntry summaryEntry =
                mGroupTestHelper.createSummaryNotification(Notification.GROUP_ALERT_SUMMARY);
        NotificationEntry childEntry =
                mGroupTestHelper.createChildNotification(Notification.GROUP_ALERT_SUMMARY, 47);
        RowContentBindParams params = new RowContentBindParams();
        when(mBindStage.getStageParams(eq(childEntry))).thenReturn(params);

        mHeadsUpManager.showNotification(summaryEntry);
        // Trigger a transfer of alert state from summary to child.
        mGroupManager.onEntryAdded(summaryEntry);
        mGroupManager.onEntryAdded(childEntry);

        // Update that child to a summary.
        StatusBarNotification oldNotification = childEntry.getSbn();
        childEntry.setSbn(
                mGroupTestHelper.createSummaryNotification(
                        Notification.GROUP_ALERT_SUMMARY, 47).getSbn());
        mGroupManager.onEntryUpdated(childEntry, oldNotification);

        assertFalse(mGroupAlertTransferHelper.isAlertTransferPending(childEntry));
    }
}
