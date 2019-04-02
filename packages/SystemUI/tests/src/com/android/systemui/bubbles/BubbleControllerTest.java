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

package com.android.systemui.bubbles;

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Icon;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.NotificationVisibility;
import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.StatusBarWindowController;
import com.android.systemui.statusbar.policy.ConfigurationController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubbleControllerTest extends SysuiTestCase {

    // Some APIs rely on the app being foreground, check is via pkg name
    private static final String FOREGROUND_TEST_PKG_NAME = "com.android.systemui.tests";

    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private IActivityManager mActivityManager;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private ConfigurationController mConfigurationController;

    private FrameLayout mStatusBarView;
    @Captor
    private ArgumentCaptor<NotificationEntryListener> mEntryListenerCaptor;

    private TestableBubbleController mBubbleController;
    private StatusBarWindowController mStatusBarWindowController;
    private NotificationEntryListener mEntryListener;

    private NotificationTestHelper mNotificationTestHelper;
    private ExpandableNotificationRow mRow;
    private ExpandableNotificationRow mRow2;
    private ExpandableNotificationRow mNoChannelRow;
    private ExpandableNotificationRow mAutoExpandRow;
    private ExpandableNotificationRow mSuppressNotifRow;

    @Mock
    private NotificationData mNotificationData;
    @Mock
    private BubbleController.BubbleStateChangeListener mBubbleStateChangeListener;
    @Mock
    private BubbleController.BubbleExpandListener mBubbleExpandListener;
    @Mock
    NotificationVisibility mNotificationVisibility;

    @Mock
    private PendingIntent mDeleteIntent;

    private BubbleData mBubbleData;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mStatusBarView = new FrameLayout(mContext);
        mDependency.injectTestDependency(NotificationEntryManager.class, mNotificationEntryManager);

        // Bubbles get added to status bar window view
        mStatusBarWindowController = new StatusBarWindowController(mContext, mWindowManager,
                mActivityManager, mDozeParameters);
        mStatusBarWindowController.add(mStatusBarView, 120 /* height */);

        // Need notifications for bubbles
        mNotificationTestHelper = new NotificationTestHelper(mContext);
        mRow = mNotificationTestHelper.createBubble(mDeleteIntent);
        mRow2 = mNotificationTestHelper.createBubble(mDeleteIntent);
        mNoChannelRow = mNotificationTestHelper.createBubble(mDeleteIntent);

        // Some bubbles want to auto expand
        Notification.BubbleMetadata autoExpandMetadata =
                getBuilder().setAutoExpandBubble(true).build();
        mAutoExpandRow = mNotificationTestHelper.createBubble(autoExpandMetadata,
                FOREGROUND_TEST_PKG_NAME);

        // Some bubbles want to suppress notifs
        Notification.BubbleMetadata suppressNotifMetadata =
                getBuilder().setSuppressInitialNotification(true).build();
        mSuppressNotifRow = mNotificationTestHelper.createBubble(suppressNotifMetadata,
                FOREGROUND_TEST_PKG_NAME);

        // Return non-null notification data from the NEM
        when(mNotificationEntryManager.getNotificationData()).thenReturn(mNotificationData);
        when(mNotificationData.getChannel(mRow.getEntry().key)).thenReturn(mRow.getEntry().channel);
        when(mNotificationData.getChannel(mNoChannelRow.getEntry().key)).thenReturn(null);

        mBubbleData = new BubbleData();
        mBubbleController = new TestableBubbleController(mContext, mStatusBarWindowController,
                mBubbleData, mConfigurationController);
        mBubbleController.setBubbleStateChangeListener(mBubbleStateChangeListener);
        mBubbleController.setExpandListener(mBubbleExpandListener);

        // Get a reference to the BubbleController's entry listener
        verify(mNotificationEntryManager, atLeastOnce())
                .addNotificationEntryListener(mEntryListenerCaptor.capture());
        mEntryListener = mEntryListenerCaptor.getValue();
    }

    @Test
    public void testAddBubble() {
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        assertTrue(mBubbleController.hasBubbles());

        verify(mBubbleStateChangeListener).onHasBubblesChanged(true);
    }

    @Test
    public void testHasBubbles() {
        assertFalse(mBubbleController.hasBubbles());
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        assertTrue(mBubbleController.hasBubbles());
    }

    @Test
    public void testRemoveBubble() {
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        assertTrue(mBubbleController.hasBubbles());

        verify(mBubbleStateChangeListener).onHasBubblesChanged(true);

        mBubbleController.removeBubble(mRow.getEntry().key, BubbleController.DISMISS_USER_GESTURE);
        assertFalse(mStatusBarWindowController.getBubblesShowing());
        assertTrue(mRow.getEntry().isBubbleDismissed());
        verify(mNotificationEntryManager).updateNotifications();
        verify(mBubbleStateChangeListener).onHasBubblesChanged(false);
    }

    @Test
    public void testDismissStack() {
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        mBubbleController.updateBubble(mRow2.getEntry(), true /* updatePosition */);
        assertTrue(mBubbleController.hasBubbles());

        mBubbleController.dismissStack(BubbleController.DISMISS_USER_GESTURE);
        assertFalse(mStatusBarWindowController.getBubblesShowing());
        verify(mNotificationEntryManager).updateNotifications();
        assertTrue(mRow.getEntry().isBubbleDismissed());
        assertTrue(mRow2.getEntry().isBubbleDismissed());
    }

    @Test
    public void testExpandCollapseStack() {
        assertFalse(mBubbleController.isStackExpanded());

        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);

        // We should have bubbles & their notifs should show in the shade
        assertTrue(mBubbleController.hasBubbles());
        assertTrue(mRow.getEntry().showInShadeWhenBubble());
        assertFalse(mStatusBarWindowController.getBubbleExpanded());

        // Expand the stack
        BubbleStackView stackView = mBubbleController.getStackView();
        stackView.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().key);
        assertTrue(mStatusBarWindowController.getBubbleExpanded());

        // Make sure it's no longer in the shade
        assertFalse(mRow.getEntry().showInShadeWhenBubble());

        // Collapse
        stackView.collapseStack();
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getEntry().key);
        assertFalse(mBubbleController.isStackExpanded());
        assertFalse(mStatusBarWindowController.getBubbleExpanded());
    }

    @Test
    public void testCollapseAfterChangingExpandedBubble() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mEntryListener.onPendingEntryAdded(mRow2.getEntry());
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        mBubbleController.updateBubble(mRow2.getEntry(), true /* updatePosition */);

        // We should have bubbles & their notifs should show in the shade
        assertTrue(mBubbleController.hasBubbles());
        assertTrue(mRow.getEntry().showInShadeWhenBubble());
        assertTrue(mRow2.getEntry().showInShadeWhenBubble());

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        stackView.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow2.getEntry().key);

        // Last added is the one that is expanded
        assertEquals(mRow2.getEntry(), stackView.getExpandedBubbleView().getEntry());
        assertFalse(mRow2.getEntry().showInShadeWhenBubble());

        // Switch which bubble is expanded
        stackView.setExpandedBubble(mRow.getEntry());
        assertEquals(mRow.getEntry(), stackView.getExpandedBubbleView().getEntry());
        assertFalse(mRow.getEntry().showInShadeWhenBubble());

        // collapse for previous bubble
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow2.getEntry().key);
        // expand for selected bubble
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().key);

        // Collapse
        mBubbleController.collapseStack();
        assertFalse(mBubbleController.isStackExpanded());
    }

    @Test
    public void testExpansionRemovesShowInShade() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);

        // We should have bubbles & their notifs should show in the shade
        assertTrue(mBubbleController.hasBubbles());
        assertTrue(mRow.getEntry().showInShadeWhenBubble());

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        stackView.expandStack();
        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().key);

        // No longer show shade in notif after expansion
        assertFalse(mRow.getEntry().showInShadeWhenBubble());
    }

    @Test
    public void testRemoveLastExpandedCollapses() {
        // Mark it as a bubble and add it explicitly
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mEntryListener.onPendingEntryAdded(mRow2.getEntry());
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        mBubbleController.updateBubble(mRow2.getEntry(), true /* updatePosition */);
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true);

        // Expand
        BubbleStackView stackView = mBubbleController.getStackView();
        stackView.expandStack();

        assertTrue(mBubbleController.isStackExpanded());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow2.getEntry().key);

        // Last added is the one that is expanded
        assertEquals(mRow2.getEntry(), stackView.getExpandedBubbleView().getEntry());
        assertFalse(mRow2.getEntry().showInShadeWhenBubble());

        // Dismiss currently expanded
        mBubbleController.removeBubble(stackView.getExpandedBubbleView().getKey(),
                BubbleController.DISMISS_USER_GESTURE);
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow2.getEntry().key);

        // Make sure next bubble is selected
        assertEquals(mRow.getEntry(), stackView.getExpandedBubbleView().getEntry());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().key);

        // Dismiss that one
        mBubbleController.removeBubble(stackView.getExpandedBubbleView().getKey(),
                BubbleController.DISMISS_USER_GESTURE);

        // Make sure state changes and collapse happens
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getEntry().key);
        verify(mBubbleStateChangeListener).onHasBubblesChanged(false);
        assertFalse(mBubbleController.hasBubbles());
    }

    @Test
    public void testAutoExpand_FailsNotForeground() {
        assertFalse(mBubbleController.isStackExpanded());

        // Add the auto expand bubble
        mEntryListener.onPendingEntryAdded(mAutoExpandRow.getEntry());
        mBubbleController.updateBubble(mAutoExpandRow.getEntry(), true /* updatePosition */);

        // Expansion shouldn't change
        verify(mBubbleExpandListener, never()).onBubbleExpandChanged(false /* expanded */,
                mAutoExpandRow.getEntry().key);
        assertFalse(mBubbleController.isStackExpanded());

        // # of bubbles should change
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true /* hasBubbles */);
    }

    @Test
    public void testAutoExpand_SucceedsForeground() {
        final CountDownLatch latch = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                latch.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(BubblesTestActivity.BUBBLE_ACTIVITY_OPENED);
        mContext.registerReceiver(receiver, filter);

        assertFalse(mBubbleController.isStackExpanded());

        // Make ourselves foreground
        Intent i = new Intent(mContext, BubblesTestActivity.class);
        i.setFlags(FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(i);

        try {
            latch.await(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Add the auto expand bubble
        mEntryListener.onPendingEntryAdded(mAutoExpandRow.getEntry());
        mBubbleController.updateBubble(mAutoExpandRow.getEntry(), true /* updatePosition */);

        // Expansion should change
        verify(mBubbleExpandListener).onBubbleExpandChanged(true /* expanded */,
                mAutoExpandRow.getEntry().key);
        assertTrue(mBubbleController.isStackExpanded());

        // # of bubbles should change
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true /* hasBubbles */);
        mContext.unregisterReceiver(receiver);
    }

    @Test
    public void testSuppressNotif_FailsNotForeground() {
        // Add the suppress notif bubble
        mEntryListener.onPendingEntryAdded(mSuppressNotifRow.getEntry());
        mBubbleController.updateBubble(mSuppressNotifRow.getEntry(), true /* updatePosition */);

        // Should be a bubble & should show in shade because we weren't forground
        assertTrue(mSuppressNotifRow.getEntry().isBubble());
        assertTrue(mSuppressNotifRow.getEntry().showInShadeWhenBubble());

        // # of bubbles should change
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true /* hasBubbles */);
    }

    @Test
    public void testSuppressNotif_SucceedsForeground() {
        final CountDownLatch latch = new CountDownLatch(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                latch.countDown();
            }
        };
        IntentFilter filter = new IntentFilter(BubblesTestActivity.BUBBLE_ACTIVITY_OPENED);
        mContext.registerReceiver(receiver, filter);

        assertFalse(mBubbleController.isStackExpanded());

        // Make ourselves foreground
        Intent i = new Intent(mContext, BubblesTestActivity.class);
        i.setFlags(FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(i);

        try {
            latch.await(100, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Add the suppress notif bubble
        mEntryListener.onPendingEntryAdded(mSuppressNotifRow.getEntry());
        mBubbleController.updateBubble(mSuppressNotifRow.getEntry(), true /* updatePosition */);

        // Should be a bubble & should NOT show in shade because we were foreground
        assertTrue(mSuppressNotifRow.getEntry().isBubble());
        assertFalse(mSuppressNotifRow.getEntry().showInShadeWhenBubble());

        // # of bubbles should change
        verify(mBubbleStateChangeListener).onHasBubblesChanged(true /* hasBubbles */);
        mContext.unregisterReceiver(receiver);
    }

    @Test
    public void testExpandStackAndSelectBubble_removedFirst() {
        final String key = mRow.getEntry().key;

        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);

        assertTrue(mRow.getEntry().isBubble());

        // Simulate notification cancellation.
        mEntryListener.onEntryRemoved(mRow.getEntry(), null /* notificationVisibility (unused) */,
                false /* removedbyUser */);

        mBubbleController.expandStackAndSelectBubble(key);
    }

    @Test
    public void testMarkNewNotificationAsBubble() {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        assertTrue(mRow.getEntry().isBubble());
    }

    @Test
    public void testMarkNewNotificationAsShowInShade() {
        mEntryListener.onPendingEntryAdded(mRow.getEntry());
        assertTrue(mRow.getEntry().showInShadeWhenBubble());
    }

    @Test
    public void testDeleteIntent_removeBubble_aged() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        mBubbleController.removeBubble(mRow.getEntry().key, BubbleController.DISMISS_AGED);
        verify(mDeleteIntent, never()).send();
    }

    @Test
    public void testDeleteIntent_removeBubble_user() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        mBubbleController.removeBubble(mRow.getEntry().key, BubbleController.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(1)).send();
    }

    @Test
    public void testDeleteIntent_dismissStack() throws PendingIntent.CanceledException {
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        mBubbleController.updateBubble(mRow2.getEntry(), true /* updatePosition */);
        mBubbleController.dismissStack(BubbleController.DISMISS_USER_GESTURE);
        verify(mDeleteIntent, times(2)).send();
    }

    static class TestableBubbleController extends BubbleController {
        // Let's assume surfaces can be synchronized immediately.
        TestableBubbleController(Context context,
                StatusBarWindowController statusBarWindowController, BubbleData data,
                ConfigurationController configurationController) {
            super(context, statusBarWindowController, data, Runnable::run, configurationController);
        }

        @Override
        public boolean shouldAutoBubbleForFlags(Context c, NotificationEntry entry) {
            return entry.notification.getNotification().getBubbleMetadata() != null;
        }
    }

    /**
     * @return basic {@link android.app.Notification.BubbleMetadata.Builder}
     */
    private Notification.BubbleMetadata.Builder getBuilder() {
        Intent target = new Intent(mContext, BubblesTestActivity.class);
        PendingIntent bubbleIntent = PendingIntent.getActivity(mContext, 0, target, 0);
        return new Notification.BubbleMetadata.Builder()
                .setIntent(bubbleIntent)
                .setIcon(Icon.createWithResource(mContext, R.drawable.android));
    }
}
