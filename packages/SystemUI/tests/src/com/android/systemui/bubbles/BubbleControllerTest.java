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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationData;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.StatusBarWindowController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubbleControllerTest extends SysuiTestCase {

    @Mock
    private NotificationEntryManager mNotificationEntryManager;
    @Mock
    private WindowManager mWindowManager;
    @Mock
    private IActivityManager mActivityManager;
    @Mock
    private DozeParameters mDozeParameters;
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

    @Mock
    private NotificationData mNotificationData;
    @Mock
    private BubbleController.BubbleStateChangeListener mBubbleStateChangeListener;
    @Mock
    private BubbleController.BubbleExpandListener mBubbleExpandListener;

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
        mRow = mNotificationTestHelper.createBubble();
        mRow2 = mNotificationTestHelper.createBubble();
        mNoChannelRow = mNotificationTestHelper.createBubble();

        // Return non-null notification data from the NEM
        when(mNotificationEntryManager.getNotificationData()).thenReturn(mNotificationData);
        when(mNotificationData.getChannel(mRow.getEntry().key)).thenReturn(mRow.getEntry().channel);
        when(mNotificationData.getChannel(mNoChannelRow.getEntry().key)).thenReturn(null);

        mBubbleData = new BubbleData();
        mBubbleController = new TestableBubbleController(mContext, mStatusBarWindowController,
                mBubbleData);
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

        mBubbleController.removeBubble(mRow.getEntry().key);
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

        mBubbleController.dismissStack();
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
        mBubbleController.removeBubble(stackView.getExpandedBubbleView().getKey());
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow2.getEntry().key);

        // Make sure next bubble is selected
        assertEquals(mRow.getEntry(), stackView.getExpandedBubbleView().getEntry());
        verify(mBubbleExpandListener).onBubbleExpandChanged(true, mRow.getEntry().key);

        // Dismiss that one
        mBubbleController.removeBubble(stackView.getExpandedBubbleView().getKey());

        // Make sure state changes and collapse happens
        verify(mBubbleExpandListener).onBubbleExpandChanged(false, mRow.getEntry().key);
        verify(mBubbleStateChangeListener).onHasBubblesChanged(false);
        assertFalse(mBubbleController.hasBubbles());
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

    static class TestableBubbleController extends BubbleController {

        TestableBubbleController(Context context,
                StatusBarWindowController statusBarWindowController, BubbleData data) {
            super(context, statusBarWindowController, data);
        }

        @Override
        public boolean shouldAutoBubbleForFlags(Context c, NotificationEntry entry) {
            return entry.notification.getNotification().getBubbleMetadata() != null;
        }
    }
}
