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
 * limitations under the License
 */

package com.android.systemui.statusbar;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;

import android.content.Context;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_POSITIVE;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link NotificationBlockingHelperManager}.
 */
@SmallTest
@org.junit.runner.RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationBlockingHelperManagerTest extends SysuiTestCase {

    private NotificationBlockingHelperManager mBlockingHelperManager;

    private NotificationTestHelper mHelper;

    @Rule public MockitoRule mockito = MockitoJUnit.rule();
    @Mock private NotificationGutsManager mGutsManager;
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationMenuRow mMenuRow;
    @Mock private NotificationMenuRowPlugin.MenuItem mMenuItem;

    @Before
    public void setUp() {
        mBlockingHelperManager = new NotificationBlockingHelperManager(mContext);

        mHelper = new NotificationTestHelper(mContext);
        when(mGutsManager.openGuts(
                any(View.class),
                anyInt(),
                anyInt(),
                any(NotificationMenuRowPlugin.MenuItem.class)))
                .thenReturn(true);
        mDependency.injectTestDependency(NotificationGutsManager.class, mGutsManager);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        when(mMenuRow.getLongpressMenuItem(any(Context.class))).thenReturn(mMenuItem);
    }

    @Test
    public void testDismissCurrentBlockingHelper_nullBlockingHelperRow() {
        // By default, this shouldn't dismiss (no pointers/vars set up!)
        assertFalse(mBlockingHelperManager.dismissCurrentBlockingHelper());
        assertTrue(mBlockingHelperManager.isBlockingHelperRowNull());
    }

    @Test
    public void testDismissCurrentBlockingHelper_withDetachedBlockingHelperRow() throws Exception {
        ExpandableNotificationRow row = spy(mHelper.createRow());
        row.setBlockingHelperShowing(true);
        when(row.isAttachedToWindow()).thenReturn(false);
        mBlockingHelperManager.setBlockingHelperRowForTest(row);

        assertTrue(mBlockingHelperManager.dismissCurrentBlockingHelper());
        assertTrue(mBlockingHelperManager.isBlockingHelperRowNull());

        verify(mEntryManager, times(0)).updateNotifications();
    }

    @Test
    public void testDismissCurrentBlockingHelper_withAttachedBlockingHelperRow() throws Exception {
        ExpandableNotificationRow row = spy(mHelper.createRow());
        row.setBlockingHelperShowing(true);
        when(row.isAttachedToWindow()).thenReturn(true);
        mBlockingHelperManager.setBlockingHelperRowForTest(row);

        assertTrue(mBlockingHelperManager.dismissCurrentBlockingHelper());
        assertTrue(mBlockingHelperManager.isBlockingHelperRowNull());

        verify(mEntryManager).updateNotifications();
    }

    @Test
    public void testPerhapsShowBlockingHelper_shown() throws Exception {
        ExpandableNotificationRow row = mHelper.createRow();
        row.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        mBlockingHelperManager.setNotificationShadeExpanded(1f);

        assertTrue(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));

        verify(mGutsManager).openGuts(row, 0, 0, mMenuItem);
    }


    @Test
    public void testPerhapsShowBlockingHelper_shownForLargeGroup() throws Exception {
        ExpandableNotificationRow groupRow = mHelper.createGroup(10);
        groupRow.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        mBlockingHelperManager.setNotificationShadeExpanded(1f);

        assertTrue(mBlockingHelperManager.perhapsShowBlockingHelper(groupRow, mMenuRow));

        verify(mGutsManager).openGuts(groupRow, 0, 0, mMenuItem);
    }

    @Test
    public void testPerhapsShowBlockingHelper_shownForOnlyChildNotification()
            throws Exception {
        ExpandableNotificationRow groupRow = mHelper.createGroup(1);
        // Explicitly get the children container & call getViewAtPosition on it instead of the row
        // as other factors such as view expansion may cause us to get the parent row back instead
        // of the child row.
        ExpandableNotificationRow childRow = groupRow.getChildrenContainer().getViewAtPosition(0);
        childRow.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        mBlockingHelperManager.setNotificationShadeExpanded(1f);

        assertTrue(mBlockingHelperManager.perhapsShowBlockingHelper(childRow, mMenuRow));

        verify(mGutsManager).openGuts(childRow, 0, 0, mMenuItem);
    }

    @Test
    public void testPerhapsShowBlockingHelper_notShownDueToNeutralUserSentiment() throws Exception {
        ExpandableNotificationRow row = mHelper.createRow();
        row.getEntry().userSentiment = USER_SENTIMENT_NEUTRAL;
        mBlockingHelperManager.setNotificationShadeExpanded(1f);

        assertFalse(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));
    }

    @Test
    public void testPerhapsShowBlockingHelper_notShownDueToPositiveUserSentiment()
            throws Exception {
        ExpandableNotificationRow row = mHelper.createRow();
        row.getEntry().userSentiment = USER_SENTIMENT_POSITIVE;
        mBlockingHelperManager.setNotificationShadeExpanded(1f);

        assertFalse(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));
    }

    @Test
    public void testPerhapsShowBlockingHelper_notShownDueToShadeVisibility() throws Exception {
        ExpandableNotificationRow row = mHelper.createRow();
        row.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        // Hide the shade
        mBlockingHelperManager.setNotificationShadeExpanded(0f);

        assertFalse(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));
    }

    @Test
    public void testPerhapsShowBlockingHelper_notShownAsNotificationIsInMultipleChildGroup()
            throws Exception {
        ExpandableNotificationRow groupRow = mHelper.createGroup(2);
        // Explicitly get the children container & call getViewAtPosition on it instead of the row
        // as other factors such as view expansion may cause us to get the parent row back instead
        // of the child row.
        ExpandableNotificationRow childRow = groupRow.getChildrenContainer().getViewAtPosition(0);
        childRow.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        mBlockingHelperManager.setNotificationShadeExpanded(1f);

        assertFalse(mBlockingHelperManager.perhapsShowBlockingHelper(childRow, mMenuRow));
    }

    @Test
    public void testBlockingHelperShowAndDismiss() throws Exception{
        ExpandableNotificationRow row = spy(mHelper.createRow());
        row.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        when(row.isAttachedToWindow()).thenReturn(true);
        mBlockingHelperManager.setNotificationShadeExpanded(1f);

        // Show check
        assertTrue(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));

        verify(mGutsManager).openGuts(row, 0, 0, mMenuItem);

        // Dismiss check
        assertTrue(mBlockingHelperManager.dismissCurrentBlockingHelper());
        assertTrue(mBlockingHelperManager.isBlockingHelperRowNull());

        verify(mEntryManager).updateNotifications();
    }
}
