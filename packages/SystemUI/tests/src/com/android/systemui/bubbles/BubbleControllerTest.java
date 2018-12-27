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

import android.app.IActivityManager;
import android.content.Context;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.statusbar.phone.StatusBarWindowController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper(setAsMainLooper = true)
public class BubbleControllerTest extends SysuiTestCase {

    @Mock
    private WindowManager mWindowManager;
    @Mock
    private IActivityManager mActivityManager;
    @Mock
    private DozeParameters mDozeParameters;
    @Mock
    private FrameLayout mStatusBarView;

    private TestableBubbleController mBubbleController;
    private StatusBarWindowController mStatusBarWindowController;

    private NotificationTestHelper mNotificationTestHelper;
    private ExpandableNotificationRow mRow;
    private ExpandableNotificationRow mRow2;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // Bubbles get added to status bar window view
        mStatusBarWindowController = new StatusBarWindowController(mContext, mWindowManager,
                mActivityManager, mDozeParameters);
        mStatusBarWindowController.add(mStatusBarView, 120 /* height */);

        // Need notifications for bubbles
        mNotificationTestHelper = new NotificationTestHelper(mContext);
        mRow = mNotificationTestHelper.createBubble();
        mRow2 = mNotificationTestHelper.createBubble();

        mBubbleController = new TestableBubbleController(mContext, mStatusBarWindowController);
    }

    @Test
    public void testIsBubble() {
        assertTrue(mRow.getEntry().isBubble());
    }

    @Test
    public void testAddBubble() {
        mBubbleController.addBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());
    }

    @Test
    public void testHasBubbles() {
        assertFalse(mBubbleController.hasBubbles());
        mBubbleController.addBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());
    }

    @Test
    public void testRemoveBubble() {
        mBubbleController.addBubble(mRow.getEntry());
        assertTrue(mBubbleController.hasBubbles());

        mBubbleController.removeBubble(mRow.getEntry().key);
        assertFalse(mStatusBarWindowController.getBubblesShowing());
    }

    @Test
    public void testDismissStack() {
        mBubbleController.addBubble(mRow.getEntry());
        mBubbleController.addBubble(mRow2.getEntry());
        assertTrue(mBubbleController.hasBubbles());

        mBubbleController.dismissStack();
        assertFalse(mStatusBarWindowController.getBubblesShowing());
    }

    @Test
    public void testIsStackExpanded() {
        assertFalse(mBubbleController.isStackExpanded());
        mBubbleController.addBubble(mRow.getEntry());

        BubbleStackView stackView = mBubbleController.getStackView();
        stackView.animateExpansion(true /* expanded */);
        assertTrue(mBubbleController.isStackExpanded());

        stackView.animateExpansion(false /* expanded */);
        assertFalse(mBubbleController.isStackExpanded());
    }

    @Test
    public void testCollapseStack() {
        mBubbleController.addBubble(mRow.getEntry());
        mBubbleController.addBubble(mRow2.getEntry());

        BubbleStackView stackView = mBubbleController.getStackView();
        stackView.animateExpansion(true /* expanded */);
        assertTrue(mBubbleController.isStackExpanded());

        mBubbleController.collapseStack();
        assertFalse(mBubbleController.isStackExpanded());
    }

    static class TestableBubbleController extends BubbleController {

        TestableBubbleController(Context context,
                StatusBarWindowController statusBarWindowController) {
            super(context, statusBarWindowController);
        }
    }
}
