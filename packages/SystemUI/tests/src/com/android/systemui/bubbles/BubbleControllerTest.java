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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.IActivityManager;
import android.content.Context;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.NotificationEntryListener;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.statusbar.notification.collection.NotificationData;
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
    @Mock
    private FrameLayout mStatusBarView;
    @Captor
    private ArgumentCaptor<NotificationEntryListener> mEntryListenerCaptor;

    private TestableBubbleController mBubbleController;
    private StatusBarWindowController mStatusBarWindowController;
    private NotificationEntryListener mEntryListener;

    private NotificationTestHelper mNotificationTestHelper;
    private ExpandableNotificationRow mRow;
    private ExpandableNotificationRow mRow2;

    @Mock
    private NotificationData mNotificationData;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDependency.injectTestDependency(NotificationEntryManager.class, mNotificationEntryManager);

        // Bubbles get added to status bar window view
        mStatusBarWindowController = new StatusBarWindowController(mContext, mWindowManager,
                mActivityManager, mDozeParameters);
        mStatusBarWindowController.add(mStatusBarView, 120 /* height */);

        // Need notifications for bubbles
        mNotificationTestHelper = new NotificationTestHelper(mContext);
        mRow = mNotificationTestHelper.createBubble();
        mRow2 = mNotificationTestHelper.createBubble();

        // Return non-null notification data from the NEM
        when(mNotificationEntryManager.getNotificationData()).thenReturn(mNotificationData);
        when(mNotificationData.getChannel(mRow.getEntry().key)).thenReturn(mRow.getEntry().channel);

        mBubbleController = new TestableBubbleController(mContext, mStatusBarWindowController);

        // Get a reference to the BubbleController's entry listener
        verify(mNotificationEntryManager, atLeastOnce())
                .addNotificationEntryListener(mEntryListenerCaptor.capture());
        mEntryListener = mEntryListenerCaptor.getValue();
    }

    @Test
    public void testAddBubble() {
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        assertTrue(mBubbleController.hasBubbles());
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

        mBubbleController.removeBubble(mRow.getEntry().key);
        assertFalse(mStatusBarWindowController.getBubblesShowing());
        assertTrue(mRow.getEntry().isBubbleDismissed());
        verify(mNotificationEntryManager).updateNotifications();
    }

    @Test
    public void testDismissStack() {
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        mBubbleController.updateBubble(mRow2.getEntry(), true /* updatePosition */);
        assertTrue(mBubbleController.hasBubbles());

        mBubbleController.dismissStack();
        assertFalse(mStatusBarWindowController.getBubblesShowing());
        verify(mNotificationEntryManager).updateNotifications();
    }

    @Test
    public void testIsStackExpanded() {
        assertFalse(mBubbleController.isStackExpanded());
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);

        BubbleStackView stackView = mBubbleController.getStackView();
        stackView.expandStack();
        assertTrue(mBubbleController.isStackExpanded());

        stackView.collapseStack();
        assertFalse(mBubbleController.isStackExpanded());
    }

    @Test
    public void testCollapseStack() {
        mBubbleController.updateBubble(mRow.getEntry(), true /* updatePosition */);
        mBubbleController.updateBubble(mRow2.getEntry(), true /* updatePosition */);

        BubbleStackView stackView = mBubbleController.getStackView();
        stackView.expandStack();
        assertTrue(mBubbleController.isStackExpanded());

        mBubbleController.collapseStack();
        assertFalse(mBubbleController.isStackExpanded());
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
                StatusBarWindowController statusBarWindowController) {
            super(context, statusBarWindowController);
        }
    }
}
