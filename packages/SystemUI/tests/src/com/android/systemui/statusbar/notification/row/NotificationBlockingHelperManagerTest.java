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

package com.android.systemui.statusbar.notification.row;

import static android.app.NotificationManager.IMPORTANCE_DEFAULT;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEGATIVE;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_NEUTRAL;
import static android.service.notification.NotificationListenerService.Ranking.USER_SENTIMENT_POSITIVE;

import static com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationChannel;
import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.View;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.bubbles.BubbleController;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.NotificationEntryManager;
import com.android.systemui.util.Assert;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link NotificationBlockingHelperManager}.
 */
@SmallTest
@FlakyTest
@org.junit.runner.RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class NotificationBlockingHelperManagerTest extends SysuiTestCase {

    private NotificationBlockingHelperManager mBlockingHelperManager;

    private NotificationTestHelper mHelper;

    @Mock private NotificationGutsManager mGutsManager;
    @Mock private NotificationEntryManager mEntryManager;
    @Mock private NotificationMenuRow mMenuRow;
    @Mock private NotificationMenuRowPlugin.MenuItem mMenuItem;

    @Before
    public void setUp() {
        Assert.sMainLooper = TestableLooper.get(this).getLooper();
        MockitoAnnotations.initMocks(this);
        mDependency.injectMockDependency(BubbleController.class);
        when(mGutsManager.openGuts(
                any(View.class),
                anyInt(),
                anyInt(),
                any(NotificationMenuRowPlugin.MenuItem.class)))
                .thenReturn(true);
        when(mMenuRow.getLongpressMenuItem(any(Context.class))).thenReturn(mMenuItem);
        mDependency.injectTestDependency(NotificationGutsManager.class, mGutsManager);
        mDependency.injectTestDependency(NotificationEntryManager.class, mEntryManager);
        mDependency.injectMockDependency(BubbleController.class);

        mHelper = new NotificationTestHelper(mContext);

        mBlockingHelperManager = new NotificationBlockingHelperManager(mContext);
        // By default, have the shade visible/expanded.
        mBlockingHelperManager.setNotificationShadeExpanded(1f);
    }

    @Test
    public void testDismissCurrentBlockingHelper_nullBlockingHelperRow() {
        // By default, this shouldn't dismiss (no pointers/vars set up!)
        assertFalse(mBlockingHelperManager.dismissCurrentBlockingHelper());
        assertTrue(mBlockingHelperManager.isBlockingHelperRowNull());
    }

    @Test
    public void testDismissCurrentBlockingHelper_withDetachedBlockingHelperRow() throws Exception {
        ExpandableNotificationRow row = createBlockableRowSpy();
        row.setBlockingHelperShowing(true);
        when(row.isAttachedToWindow()).thenReturn(false);
        mBlockingHelperManager.setBlockingHelperRowForTest(row);

        assertTrue(mBlockingHelperManager.dismissCurrentBlockingHelper());
        assertTrue(mBlockingHelperManager.isBlockingHelperRowNull());

        verify(mEntryManager, times(0)).updateNotifications();
    }

    @Test
    public void testDismissCurrentBlockingHelper_withAttachedBlockingHelperRow() throws Exception {
        ExpandableNotificationRow row = createBlockableRowSpy();
        row.setBlockingHelperShowing(true);
        when(row.isAttachedToWindow()).thenReturn(true);
        mBlockingHelperManager.setBlockingHelperRowForTest(row);

        assertTrue(mBlockingHelperManager.dismissCurrentBlockingHelper());
        assertTrue(mBlockingHelperManager.isBlockingHelperRowNull());

        verify(mEntryManager).updateNotifications();
    }

    @Test
    public void testPerhapsShowBlockingHelper_shown() throws Exception {
        ExpandableNotificationRow row = createBlockableRowSpy();
        row.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;

        assertTrue(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));

        verify(mGutsManager).openGuts(row, 0, 0, mMenuItem);
    }

    @Test
    public void testPerhapsShowBlockingHelper_notShownForMultiChannelGroup() throws Exception {
        ExpandableNotificationRow groupRow = createBlockableGroupRowSpy(10);
        int i = 0;
        for (ExpandableNotificationRow childRow : groupRow.getNotificationChildren()) {
            modifyRanking(childRow.getEntry())
                    .setChannel(
                            new NotificationChannel(
                                    Integer.toString(i++), "", IMPORTANCE_DEFAULT))
                    .build();
        }

        groupRow.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;

        assertFalse(mBlockingHelperManager.perhapsShowBlockingHelper(groupRow, mMenuRow));

        verify(mGutsManager, never()).openGuts(groupRow, 0, 0, mMenuItem);
    }

    @Test
    public void testPerhapsShowBlockingHelper_shownForLargeGroup() throws Exception {
        ExpandableNotificationRow groupRow = createBlockableGroupRowSpy(10);
        groupRow.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;

        assertTrue(mBlockingHelperManager.perhapsShowBlockingHelper(groupRow, mMenuRow));

        verify(mGutsManager).openGuts(groupRow, 0, 0, mMenuItem);
    }

    @Test
    public void testPerhapsShowBlockingHelper_shownForOnlyChildNotification()
            throws Exception {
        ExpandableNotificationRow groupRow = createBlockableGroupRowSpy(1);
        // Explicitly get the children container & call getViewAtPosition on it instead of the row
        // as other factors such as view expansion may cause us to get the parent row back instead
        // of the child row.
        ExpandableNotificationRow childRow = groupRow.getChildrenContainer().getViewAtPosition(0);
        childRow.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        assertFalse(childRow.getIsNonblockable());

        assertTrue(mBlockingHelperManager.perhapsShowBlockingHelper(childRow, mMenuRow));

        verify(mGutsManager).openGuts(childRow, 0, 0, mMenuItem);
    }

    @Test
    public void testPerhapsShowBlockingHelper_notShownDueToNeutralUserSentiment() throws Exception {
        ExpandableNotificationRow row = createBlockableRowSpy();
        row.getEntry().userSentiment = USER_SENTIMENT_NEUTRAL;

        assertFalse(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));
    }

    @Test
    public void testPerhapsShowBlockingHelper_notShownDueToPositiveUserSentiment()
            throws Exception {
        ExpandableNotificationRow row = createBlockableRowSpy();
        row.getEntry().userSentiment = USER_SENTIMENT_POSITIVE;

        assertFalse(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));
    }

    @Test
    public void testPerhapsShowBlockingHelper_notShownDueToShadeVisibility() throws Exception {
        ExpandableNotificationRow row = createBlockableRowSpy();
        row.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        // Hide the shade
        mBlockingHelperManager.setNotificationShadeExpanded(0f);

        assertFalse(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));
    }

    @Test
    public void testPerhapsShowBlockingHelper_notShownDueToNonblockability() throws Exception {
        ExpandableNotificationRow row = createBlockableRowSpy();
        when(row.getIsNonblockable()).thenReturn(true);
        row.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;

        assertFalse(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));
    }

    @Test
    public void testPerhapsShowBlockingHelper_notShownAsNotificationIsInMultipleChildGroup()
            throws Exception {
        ExpandableNotificationRow groupRow = createBlockableGroupRowSpy(2);
        // Explicitly get the children container & call getViewAtPosition on it instead of the row
        // as other factors such as view expansion may cause us to get the parent row back instead
        // of the child row.
        ExpandableNotificationRow childRow = groupRow.getChildrenContainer().getViewAtPosition(0);
        childRow.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;

        assertFalse(mBlockingHelperManager.perhapsShowBlockingHelper(childRow, mMenuRow));
    }

    @Test
    public void testBlockingHelperShowAndDismiss() throws Exception{
        ExpandableNotificationRow row = createBlockableRowSpy();
        row.getEntry().userSentiment = USER_SENTIMENT_NEGATIVE;
        when(row.isAttachedToWindow()).thenReturn(true);

        // Show check
        assertTrue(mBlockingHelperManager.perhapsShowBlockingHelper(row, mMenuRow));

        verify(mGutsManager).openGuts(row, 0, 0, mMenuItem);

        // Dismiss check
        assertTrue(mBlockingHelperManager.dismissCurrentBlockingHelper());
        assertTrue(mBlockingHelperManager.isBlockingHelperRowNull());

        verify(mEntryManager).updateNotifications();
    }

    @Test
    public void testNonBlockable_package() {
        mBlockingHelperManager.setNonBlockablePkgs(new String[] {"banana", "strawberry:pie"});

        assertFalse(mBlockingHelperManager.isNonblockable("orange", "pie"));

        assertTrue(mBlockingHelperManager.isNonblockable("banana", "pie"));
    }

    @Test
    public void testNonBlockable_channel() {
        mBlockingHelperManager.setNonBlockablePkgs(new String[] {"banana", "strawberry:pie"});

        assertFalse(mBlockingHelperManager.isNonblockable("strawberry", "shortcake"));

        assertTrue(mBlockingHelperManager.isNonblockable("strawberry", "pie"));
    }

    private ExpandableNotificationRow createBlockableRowSpy() throws Exception {
        ExpandableNotificationRow row = spy(mHelper.createRow());
        when(row.getIsNonblockable()).thenReturn(false);
        return row;
    }

    private ExpandableNotificationRow createBlockableGroupRowSpy(int numChildren) throws Exception {
        ExpandableNotificationRow row = spy(mHelper.createGroup(numChildren));
        when(row.getIsNonblockable()).thenReturn(false);
        return row;
    }
}
