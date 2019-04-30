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

package com.android.systemui.bubbles;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.PendingIntent;
import android.graphics.drawable.Icon;
import android.os.UserHandle;
import android.service.notification.StatusBarNotification;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.bubbles.BubbleData.TimeSource;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubbleDataTest extends SysuiTestCase {

    private NotificationEntry mEntryA1;
    private NotificationEntry mEntryA2;
    private NotificationEntry mEntryA3;
    private NotificationEntry mEntryB1;
    private NotificationEntry mEntryB2;
    private NotificationEntry mEntryB3;
    private NotificationEntry mEntryC1;

    private Bubble mBubbleA1;
    private Bubble mBubbleA2;
    private Bubble mBubbleA3;
    private Bubble mBubbleB1;
    private Bubble mBubbleB2;
    private Bubble mBubbleB3;
    private Bubble mBubbleC1;

    private BubbleData mBubbleData;

    @Mock
    private TimeSource mTimeSource;
    @Mock
    private BubbleData.Listener mListener;
    @Mock
    private PendingIntent mExpandIntent;
    @Mock
    private PendingIntent mDeleteIntent;

    private NotificationTestHelper mNotificationTestHelper;

    @Before
    public void setUp() throws Exception {
        mNotificationTestHelper = new NotificationTestHelper(mContext);
        MockitoAnnotations.initMocks(this);

        mEntryA1 = createBubbleEntry(1, "a1", "package.a");
        mEntryA2 = createBubbleEntry(1, "a2", "package.a");
        mEntryA3 = createBubbleEntry(1, "a3", "package.a");
        mEntryB1 = createBubbleEntry(1, "b1", "package.b");
        mEntryB2 = createBubbleEntry(1, "b2", "package.b");
        mEntryB3 = createBubbleEntry(1, "b3", "package.b");
        mEntryC1 = createBubbleEntry(1, "c1", "package.c");

        mBubbleA1 = new Bubble(mEntryA1);
        mBubbleA2 = new Bubble(mEntryA2);
        mBubbleA3 = new Bubble(mEntryA3);
        mBubbleB1 = new Bubble(mEntryB1);
        mBubbleB2 = new Bubble(mEntryB2);
        mBubbleB3 = new Bubble(mEntryB3);
        mBubbleC1 = new Bubble(mEntryC1);

        mBubbleData = new BubbleData(getContext());

        // Used by BubbleData to set lastAccessedTime
        when(mTimeSource.currentTimeMillis()).thenReturn(1000L);
        mBubbleData.setTimeSource(mTimeSource);
    }

    private NotificationEntry createBubbleEntry(int userId, String notifKey, String packageName) {
        return createBubbleEntry(userId, notifKey, packageName, 1000);
    }

    private void setPostTime(NotificationEntry entry, long postTime) {
        when(entry.notification.getPostTime()).thenReturn(postTime);
    }

    private void setOngoing(NotificationEntry entry, boolean ongoing) {
        if (ongoing) {
            entry.notification.getNotification().flags |= Notification.FLAG_FOREGROUND_SERVICE;
        } else {
            entry.notification.getNotification().flags &= ~Notification.FLAG_FOREGROUND_SERVICE;
        }
    }

    /**
     * No ExpandableNotificationRow is required to test BubbleData. This setup is all that is
     * required for BubbleData functionality and verification. NotificationTestHelper is used only
     * as a convenience to create a Notification w/BubbleMetadata.
     */
    private NotificationEntry createBubbleEntry(int userId, String notifKey, String packageName,
            long postTime) {
        // BubbleMetadata
        Notification.BubbleMetadata bubbleMetadata = new Notification.BubbleMetadata.Builder()
                .setIntent(mExpandIntent)
                .setDeleteIntent(mDeleteIntent)
                .setIcon(Icon.createWithResource("", 0))
                .build();
        // Notification -> BubbleMetadata
        Notification notification = mNotificationTestHelper.createNotification(false,
                null /* groupKey */, bubbleMetadata);

        // StatusBarNotification
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        when(sbn.getKey()).thenReturn(notifKey);
        when(sbn.getUser()).thenReturn(new UserHandle(userId));
        when(sbn.getPackageName()).thenReturn(packageName);
        when(sbn.getPostTime()).thenReturn(postTime);
        when(sbn.getNotification()).thenReturn(notification);

        // NotificationEntry -> StatusBarNotification -> Notification -> BubbleMetadata
        return new NotificationEntry(sbn);
    }

    private void sendUpdatedEntryAtTime(NotificationEntry entry, long postTime) {
        setPostTime(entry, postTime);
        mBubbleData.notificationEntryUpdated(entry);
    }

    private void changeExpandedStateAtTime(boolean shouldBeExpanded, long time) {
        when(mTimeSource.currentTimeMillis()).thenReturn(time);
        mBubbleData.setExpanded(shouldBeExpanded);
    }

    @Test
    public void testAddBubble() {
        // Setup
        mBubbleData.setListener(mListener);

        // Test
        setPostTime(mEntryA1, 1000);
        mBubbleData.notificationEntryUpdated(mEntryA1);

        // Verify
        verify(mListener).onBubbleAdded(eq(mBubbleA1));
        verify(mListener).onSelectionChanged(eq(mBubbleA1));
        verify(mListener).apply();
    }

    @Test
    public void testRemoveBubble() {
        // Setup
        mBubbleData.notificationEntryUpdated(mEntryA1);
        mBubbleData.notificationEntryUpdated(mEntryA2);
        mBubbleData.notificationEntryUpdated(mEntryA3);
        mBubbleData.setListener(mListener);

        // Test
        mBubbleData.notificationEntryRemoved(mEntryA1, BubbleController.DISMISS_USER_GESTURE);

        // Verify
        verify(mListener).onBubbleRemoved(eq(mBubbleA1), eq(BubbleController.DISMISS_USER_GESTURE));
        verify(mListener).onSelectionChanged(eq(mBubbleA2));
        verify(mListener).apply();
    }

    @Test
    public void test_collapsed_addBubble_atMaxBubbles_expiresLeastActive() {
        // Given
        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryA2, 2000);
        sendUpdatedEntryAtTime(mEntryA3, 3000);
        sendUpdatedEntryAtTime(mEntryB1, 4000);
        sendUpdatedEntryAtTime(mEntryB2, 5000);
        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleA1);

        // When
        sendUpdatedEntryAtTime(mEntryC1, 6000);

        // Then
        // A2 is removed. A1 is oldest but is the selected bubble.
        assertThat(mBubbleData.getBubbles()).doesNotContain(mBubbleA2);
    }

    @Test
    public void test_collapsed_expand_whenEmpty_doesNothing() {
        assertThat(mBubbleData.hasBubbles()).isFalse();
        changeExpandedStateAtTime(true, 2000L);

        verify(mListener, never()).onExpandedChanged(anyBoolean());
        verify(mListener, never()).apply();
    }

    // New bubble while stack is collapsed
    @Test
    public void test_collapsed_addBubble() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        // When
        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryB1, 2000);
        sendUpdatedEntryAtTime(mEntryB2, 3000);
        sendUpdatedEntryAtTime(mEntryA2, 4000);

        // Then
        // New bubbles move to front when collapsed, bringing bubbles from the same app along
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB2, mBubbleB1));
    }

    // New bubble while collapsed with ongoing bubble present
    @Test
    public void test_collapsed_addBubble_withOngoing() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        // When
        setOngoing(mEntryA1, true);
        setPostTime(mEntryA1, 1000);
        mBubbleData.notificationEntryUpdated(mEntryA1);
        setPostTime(mEntryB1, 2000);
        mBubbleData.notificationEntryUpdated(mEntryB1);
        setPostTime(mEntryB2, 3000);
        mBubbleData.notificationEntryUpdated(mEntryB2);
        setPostTime(mEntryA2, 4000);
        mBubbleData.notificationEntryUpdated(mEntryA2);

        // Then
        // New bubbles move to front, but stay behind any ongoing bubbles.
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA1, mBubbleA2, mBubbleB2, mBubbleB1));
    }

    // Remove the selected bubble (middle bubble), while the stack is collapsed.
    @Test
    public void test_collapsed_removeBubble_selected() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        setPostTime(mEntryA1, 1000);
        mBubbleData.notificationEntryUpdated(mEntryA1);

        setPostTime(mEntryB1, 2000);
        mBubbleData.notificationEntryUpdated(mEntryB1);

        setPostTime(mEntryB2, 3000);
        mBubbleData.notificationEntryUpdated(mEntryB2);

        setPostTime(mEntryA2, 4000);
        mBubbleData.notificationEntryUpdated(mEntryA2);

        mBubbleData.setSelectedBubble(mBubbleB2);
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB2, mBubbleB1));

        // When
        mBubbleData.notificationEntryRemoved(mEntryB2, BubbleController.DISMISS_USER_GESTURE);

        // Then
        // (Selection remains in the same position)
        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleB1);
    }

    // Remove the selected bubble (last bubble), while the stack is collapsed.
    @Test
    public void test_collapsed_removeSelectedBubble_inLastPosition() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryB1, 2000);
        sendUpdatedEntryAtTime(mEntryB2, 3000);
        sendUpdatedEntryAtTime(mEntryA2, 4000);

        mBubbleData.setSelectedBubble(mBubbleB1);
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB2, mBubbleB1));

        // When
        mBubbleData.notificationEntryRemoved(mEntryB1, BubbleController.DISMISS_USER_GESTURE);

        // Then
        // (Selection is forced to move to previous)
        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleB2);
    }

    @Test
    public void test_collapsed_addBubble_ongoing() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        // When
        setPostTime(mEntryA1, 1000);
        mBubbleData.notificationEntryUpdated(mEntryA1);

        setPostTime(mEntryB1, 2000);
        mBubbleData.notificationEntryUpdated(mEntryB1);

        setPostTime(mEntryB2, 3000);
        setOngoing(mEntryB2, true);
        mBubbleData.notificationEntryUpdated(mEntryB2);

        setPostTime(mEntryA2, 4000);
        mBubbleData.notificationEntryUpdated(mEntryA2);

        // Then
        // New bubbles move to front, but stay behind any ongoing bubbles.
        // Does not break grouping. (A2 is inserted after B1, even though it's newer).
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB2, mBubbleB1, mBubbleA2, mBubbleA1));
    }

    @Test
    public void test_collapsed_removeBubble() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryB1, 2000);
        sendUpdatedEntryAtTime(mEntryB2, 3000);
        sendUpdatedEntryAtTime(mEntryA2, 4000);

        // When
        mBubbleData.notificationEntryRemoved(mEntryB2, BubbleController.DISMISS_USER_GESTURE);

        // Then
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB1));
    }

    @Test
    public void test_collapsed_updateBubble() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryB1, 2000);
        sendUpdatedEntryAtTime(mEntryB2, 3000);
        sendUpdatedEntryAtTime(mEntryA2, 4000);

        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB2, mBubbleB1));

        // When
        sendUpdatedEntryAtTime(mEntryB2, 5000);

        // Then
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB2, mBubbleB1, mBubbleA2, mBubbleA1));
    }

    @Test
    public void test_collapsed_updateBubble_withOngoing() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        setPostTime(mEntryA1, 1000);
        mBubbleData.notificationEntryUpdated(mEntryA1);

        setPostTime(mEntryB1, 2000);
        mBubbleData.notificationEntryUpdated(mEntryB1);

        setPostTime(mEntryB2, 3000);
        mBubbleData.notificationEntryUpdated(mEntryB2);

        setOngoing(mEntryA2, true);
        setPostTime(mEntryA2, 4000);
        mBubbleData.notificationEntryUpdated(mEntryA2);

        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB2, mBubbleB1));

        // When
        setPostTime(mEntryB1, 5000);
        mBubbleData.notificationEntryUpdated(mEntryB1);

        // Then
        // A2 remains in first position, due to being ongoing. B1 moves before B2, Group A
        // remains before group B.
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB1, mBubbleB2));
    }

    @Test
    public void test_collapse_afterUpdateWhileExpanded() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryB1, 2000);
        sendUpdatedEntryAtTime(mEntryB2, 3000);
        sendUpdatedEntryAtTime(mEntryA2, 4000);

        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleA1);

        changeExpandedStateAtTime(true, 5000L);
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB2, mBubbleB1));

        sendUpdatedEntryAtTime(mEntryB1, 6000);

        // (No reordering while expanded)
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB2, mBubbleB1));

        // When
        changeExpandedStateAtTime(false, 7000L);

        // Then
        // A1 moves to front on collapse, since it is the selected bubble (and most recently
        // accessed).
        // A2 moves next to A1 to maintain grouping.
        // B1 moves in front of B2, since it received an update while expanded
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA1, mBubbleA2, mBubbleB1, mBubbleB2));
    }

    @Test
    public void test_collapse_afterUpdateWhileExpanded_withOngoing() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryB1, 2000);

        setOngoing(mEntryB2, true);
        sendUpdatedEntryAtTime(mEntryB2, 3000);

        sendUpdatedEntryAtTime(mEntryA2, 4000);

        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleA1);

        changeExpandedStateAtTime(true, 5000L);
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB2, mBubbleB1, mBubbleA2, mBubbleA1));

        sendUpdatedEntryAtTime(mEntryA1, 6000);

        // No reordering if expanded
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB2, mBubbleB1, mBubbleA2, mBubbleA1));

        // When
        changeExpandedStateAtTime(false, 7000L);

        // Then
        // B2 remains in first position because it is ongoing.
        // B1 remains grouped with B2
        // A1 moves in front of A2, since it is more recently updated (and is selected).
        // B1 moves in front of B2, since it has more recent activity.
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB2, mBubbleB1, mBubbleA1, mBubbleA2));
    }

    @Test
    public void test_collapsed_removeLastBubble_clearsSelectedBubble() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryB1, 2000);
        sendUpdatedEntryAtTime(mEntryB2, 3000);
        sendUpdatedEntryAtTime(mEntryA2, 4000);

        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleA1);

        mBubbleData.notificationEntryRemoved(mEntryA1, BubbleController.DISMISS_USER_GESTURE);
        mBubbleData.notificationEntryRemoved(mEntryB1, BubbleController.DISMISS_USER_GESTURE);
        mBubbleData.notificationEntryRemoved(mEntryB2, BubbleController.DISMISS_USER_GESTURE);
        mBubbleData.notificationEntryRemoved(mEntryA2, BubbleController.DISMISS_USER_GESTURE);

        assertThat(mBubbleData.getSelectedBubble()).isNull();
    }

    @Test
    public void test_expanded_addBubble_atMaxBubbles_expiresLeastActive() {
        // Given
        sendUpdatedEntryAtTime(mEntryA1, 1000);
        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleA1);

        changeExpandedStateAtTime(true, 2000L);
        assertThat(mBubbleData.getSelectedBubble().getLastActivity()).isEqualTo(2000);

        sendUpdatedEntryAtTime(mEntryA2, 3000);
        sendUpdatedEntryAtTime(mEntryA3, 4000);
        sendUpdatedEntryAtTime(mEntryB1, 5000);
        sendUpdatedEntryAtTime(mEntryB2, 6000);
        sendUpdatedEntryAtTime(mEntryB3, 7000);


        // Then
        // A1 would be removed, but it is selected and expanded, so it should not go away.
        // Instead, fall through to removing A2 (the next oldest).
        assertThat(mBubbleData.getBubbles()).doesNotContain(mEntryA2);
    }

    @Test
    public void test_expanded_removeLastBubble_collapsesStack() {
        // Given
        setPostTime(mEntryA1, 1000);
        mBubbleData.notificationEntryUpdated(mEntryA1);

        setPostTime(mEntryB1, 2000);
        mBubbleData.notificationEntryUpdated(mEntryB1);

        setPostTime(mEntryB2, 3000);
        mBubbleData.notificationEntryUpdated(mEntryC1);

        mBubbleData.setExpanded(true);

        mBubbleData.notificationEntryRemoved(mEntryA1, BubbleController.DISMISS_USER_GESTURE);
        mBubbleData.notificationEntryRemoved(mEntryB1, BubbleController.DISMISS_USER_GESTURE);
        mBubbleData.notificationEntryRemoved(mEntryC1, BubbleController.DISMISS_USER_GESTURE);

        assertThat(mBubbleData.isExpanded()).isFalse();
        assertThat(mBubbleData.getSelectedBubble()).isNull();
    }

    // Bubbles do not reorder while expanded
    @Test
    public void test_expanded_selection_collapseToTop() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryA2, 2000);
        sendUpdatedEntryAtTime(mEntryB1, 3000);

        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB1, mBubbleA2, mBubbleA1));

        changeExpandedStateAtTime(true, 4000L);

        // regrouping only happens when collapsed (after new or update) or expanded->collapsed
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB1, mBubbleA2, mBubbleA1));

        changeExpandedStateAtTime(false, 6000L);

        // A1 is still selected and it's lastAccessed time has been updated
        // on collapse, sorting is applied, keeping the selected bubble at the front
        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleA1);
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA1, mBubbleA2, mBubbleB1));
    }

    // New bubble from new app while stack is expanded
    @Test
    public void test_expanded_addBubble_newApp() {
        // Given
        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryA2, 2000);
        sendUpdatedEntryAtTime(mEntryA3, 3000);
        sendUpdatedEntryAtTime(mEntryB1, 4000);
        sendUpdatedEntryAtTime(mEntryB2, 5000);

        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleA1);

        changeExpandedStateAtTime(true, 6000L);

        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleA1);
        assertThat(mBubbleData.getSelectedBubble().getLastActivity()).isEqualTo(6000L);

        // regrouping only happens when collapsed (after new or update) or expanded->collapsed
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB2, mBubbleB1, mBubbleA3, mBubbleA2, mBubbleA1));

        // When
        sendUpdatedEntryAtTime(mEntryC1, 7000);

        // Then
        // A2 is expired. A1 was oldest, but lastActivityTime is reset when expanded, since A1 is
        // selected.
        // C1 is added at the end since bubbles are expanded.
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB2, mBubbleB1, mBubbleA3, mBubbleA1, mBubbleC1));
    }

    // New bubble from existing app while stack is expanded
    @Test
    public void test_expanded_addBubble_existingApp() {
        // Given
        sendUpdatedEntryAtTime(mEntryB1, 1000);
        sendUpdatedEntryAtTime(mEntryB2, 2000);
        sendUpdatedEntryAtTime(mEntryA1, 3000);
        sendUpdatedEntryAtTime(mEntryA2, 4000);
        sendUpdatedEntryAtTime(mEntryA3, 5000);

        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleB1);

        changeExpandedStateAtTime(true, 6000L);

        // B1 is first (newest, since it's just been expanded and is selected)
        assertThat(mBubbleData.getSelectedBubble()).isEqualTo(mBubbleB1);
        assertThat(mBubbleData.getSelectedBubble().getLastActivity()).isEqualTo(6000L);

        // regrouping only happens when collapsed (after new or update) or while collapsing
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA3, mBubbleA2, mBubbleA1, mBubbleB2, mBubbleB1));

        // When
        sendUpdatedEntryAtTime(mEntryB3, 7000);

        // Then
        // (B2 is expired, B1 was oldest, but it's lastActivityTime is updated at the point when
        // the stack was expanded, since it is the selected bubble.
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA3, mBubbleA2, mBubbleA1, mBubbleB3, mBubbleB1));
    }

    // Updated bubble from existing app while stack is expanded
    @Test
    public void test_expanded_updateBubble_existingApp() {
        sendUpdatedEntryAtTime(mEntryA1, 1000);
        sendUpdatedEntryAtTime(mEntryA2, 2000);
        sendUpdatedEntryAtTime(mEntryB1, 3000);
        sendUpdatedEntryAtTime(mEntryB2, 4000);

        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB2, mBubbleB1, mBubbleA2, mBubbleA1));
        mBubbleData.setExpanded(true);

        sendUpdatedEntryAtTime(mEntryA1, 5000);

        // Does not reorder while expanded (for an update).
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleB2, mBubbleB1, mBubbleA2, mBubbleA1));
    }

    @Test
    public void test_expanded_updateBubble() {
        // Given
        assertThat(mBubbleData.hasBubbles()).isFalse();
        assertThat(mBubbleData.isExpanded()).isFalse();

        setPostTime(mEntryA1, 1000);
        mBubbleData.notificationEntryUpdated(mEntryA1);

        setPostTime(mEntryB1, 2000);
        mBubbleData.notificationEntryUpdated(mEntryB1);

        setPostTime(mEntryB2, 3000);
        mBubbleData.notificationEntryUpdated(mEntryB2);

        setPostTime(mEntryA2, 4000);
        mBubbleData.notificationEntryUpdated(mEntryA2);

        mBubbleData.setExpanded(true);
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB2, mBubbleB1));

        // When
        setPostTime(mEntryB1, 5000);
        mBubbleData.notificationEntryUpdated(mEntryB1);

        // Then
        // B1 remains in the same place due to being expanded
        assertThat(mBubbleData.getBubbles()).isEqualTo(
                ImmutableList.of(mBubbleA2, mBubbleA1, mBubbleB2, mBubbleB1));
    }
}
