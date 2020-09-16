/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.app.NotificationChannel;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.ArraySet;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.AboveShelfChangedListener;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class ExpandableNotificationRowTest extends SysuiTestCase {

    private ExpandableNotificationRow mGroupRow;

    private NotificationTestHelper mNotificationTestHelper;
    boolean mHeadsUpAnimatingAway = false;

    @Rule public MockitoRule mockito = MockitoJUnit.rule();
    @Mock private NotificationBlockingHelperManager mBlockingHelperManager;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        mNotificationTestHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mGroupRow = mNotificationTestHelper.createGroup();
        mGroupRow.setHeadsUpAnimatingAwayListener(
                animatingAway -> mHeadsUpAnimatingAway = animatingAway);
        mDependency.injectTestDependency(
                NotificationBlockingHelperManager.class,
                mBlockingHelperManager);
    }

    @Test
    public void testGroupSummaryNotShowingIconWhenPublic() {
        mGroupRow.setSensitive(true, true);
        mGroupRow.setHideSensitiveForIntrinsicHeight(true);
        assertTrue(mGroupRow.isSummaryWithChildren());
        assertFalse(mGroupRow.isShowingIcon());
    }

    @Test
    public void testNotificationHeaderVisibleWhenAnimating() {
        mGroupRow.setSensitive(true, true);
        mGroupRow.setHideSensitive(true, false, 0, 0);
        mGroupRow.setHideSensitive(false, true, 0, 0);
        assertTrue(mGroupRow.getChildrenContainer().getVisibleHeader().getVisibility()
                == View.VISIBLE);
    }

    @Test
    public void testUserLockedResetEvenWhenNoChildren() {
        mGroupRow.setUserLocked(true);
        mGroupRow.removeAllChildren();
        mGroupRow.setUserLocked(false);
        assertFalse("The childrencontainer should not be userlocked but is, the state "
                + "seems out of sync.", mGroupRow.getChildrenContainer().isUserLocked());
    }

    @Test
    public void testReinflatedOnDensityChange() {
        mGroupRow.setUserLocked(true);
        mGroupRow.removeAllChildren();
        mGroupRow.setUserLocked(false);
        NotificationChildrenContainer mockContainer = mock(NotificationChildrenContainer.class);
        mGroupRow.setChildrenContainer(mockContainer);
        mGroupRow.onDensityOrFontScaleChanged();
        verify(mockContainer).reInflateViews(any(), any());
    }

    @Test
    public void testIconColorShouldBeUpdatedWhenSensitive() throws Exception {
        ExpandableNotificationRow row = spy(mNotificationTestHelper.createRow(
                FLAG_CONTENT_VIEW_ALL));
        row.setSensitive(true, true);
        row.setHideSensitive(true, false, 0, 0);
        verify(row).updateShelfIconColor();
    }

    @Test
    public void setNeedsRedactionFreesViewWhenFalse() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow(FLAG_CONTENT_VIEW_ALL);
        row.setNeedsRedaction(true);
        row.getPublicLayout().setVisibility(View.GONE);

        row.setNeedsRedaction(false);
        TestableLooper.get(this).processAllMessages();
        assertNull(row.getPublicLayout().getContractedChild());
    }

    @Test
    public void testAboveShelfChangedListenerCalled() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        AboveShelfChangedListener listener = mock(AboveShelfChangedListener.class);
        row.setAboveShelfChangedListener(listener);
        row.setHeadsUp(true);
        verify(listener).onAboveShelfStateChanged(true);
    }

    @Test
    public void testAboveShelfChangedListenerCalledPinned() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        AboveShelfChangedListener listener = mock(AboveShelfChangedListener.class);
        row.setAboveShelfChangedListener(listener);
        row.setPinned(true);
        verify(listener).onAboveShelfStateChanged(true);
    }

    @Test
    public void testAboveShelfChangedListenerCalledHeadsUpGoingAway() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        AboveShelfChangedListener listener = mock(AboveShelfChangedListener.class);
        row.setAboveShelfChangedListener(listener);
        row.setHeadsUpAnimatingAway(true);
        verify(listener).onAboveShelfStateChanged(true);
    }
    @Test
    public void testAboveShelfChangedListenerCalledWhenGoingBelow() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        row.setHeadsUp(true);
        AboveShelfChangedListener listener = mock(AboveShelfChangedListener.class);
        row.setAboveShelfChangedListener(listener);
        row.setAboveShelf(false);
        verify(listener).onAboveShelfStateChanged(false);
    }

    @Test
    public void testClickSound() throws Exception {
        assertTrue("Should play sounds by default.", mGroupRow.isSoundEffectsEnabled());
        StatusBarStateController mock = mNotificationTestHelper.getStatusBarStateController();
        when(mock.isDozing()).thenReturn(true);
        mGroupRow.setSecureStateProvider(()-> false);
        assertFalse("Shouldn't play sounds when dark and trusted.",
                mGroupRow.isSoundEffectsEnabled());
        mGroupRow.setSecureStateProvider(()-> true);
        assertTrue("Should always play sounds when not trusted.",
                mGroupRow.isSoundEffectsEnabled());
    }

    @Test
    public void testSetDismissed_longPressListenerRemoved() {
        ExpandableNotificationRow.LongPressListener listener =
                mock(ExpandableNotificationRow.LongPressListener.class);
        mGroupRow.setLongPressListener(listener);
        mGroupRow.doLongClickCallback(0,0);
        verify(listener, times(1)).onLongPress(eq(mGroupRow), eq(0), eq(0),
                any(NotificationMenuRowPlugin.MenuItem.class));
        reset(listener);

        mGroupRow.dismiss(true);
        mGroupRow.doLongClickCallback(0,0);
        verify(listener, times(0)).onLongPress(eq(mGroupRow), eq(0), eq(0),
                any(NotificationMenuRowPlugin.MenuItem.class));
    }

    @Test
    public void testShowAppOps_noHeader() {
        // public notification is custom layout - no header
        mGroupRow.setSensitive(true, true);
        mGroupRow.setAppOpsOnClickListener(null);
        mGroupRow.showAppOpsIcons(null);
    }

    @Test
    public void testShowAppOpsIcons_header() {
        NotificationContentView publicLayout = mock(NotificationContentView.class);
        mGroupRow.setPublicLayout(publicLayout);
        NotificationContentView privateLayout = mock(NotificationContentView.class);
        mGroupRow.setPrivateLayout(privateLayout);
        NotificationChildrenContainer mockContainer = mock(NotificationChildrenContainer.class);
        when(mockContainer.getNotificationChildCount()).thenReturn(1);
        mGroupRow.setChildrenContainer(mockContainer);

        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(AppOpsManager.OP_ANSWER_PHONE_CALLS);
        mGroupRow.showAppOpsIcons(ops);

        verify(mockContainer, times(1)).showAppOpsIcons(ops);
        verify(privateLayout, times(1)).showAppOpsIcons(ops);
        verify(publicLayout, times(1)).showAppOpsIcons(ops);

    }

    @Test
    public void testAppOpsOnClick() {
        ExpandableNotificationRow.OnAppOpsClickListener l = mock(
                ExpandableNotificationRow.OnAppOpsClickListener.class);
        View view = mock(View.class);

        mGroupRow.setAppOpsOnClickListener(l);

        mGroupRow.getAppOpsOnClickListener().onClick(view);
        verify(l, times(1)).onClick(any(), anyInt(), anyInt(), any());
    }

    @Test
    public void testHeadsUpAnimatingAwayListener() {
        mGroupRow.setHeadsUpAnimatingAway(true);
        Assert.assertEquals(true, mHeadsUpAnimatingAway);
        mGroupRow.setHeadsUpAnimatingAway(false);
        Assert.assertEquals(false, mHeadsUpAnimatingAway);
    }

    @Test
    public void testPerformDismissWithBlockingHelper_falseWhenBlockingHelperIsntShown() {
        when(mBlockingHelperManager.perhapsShowBlockingHelper(
                eq(mGroupRow), any(NotificationMenuRowPlugin.class))).thenReturn(false);

        assertFalse(
                mGroupRow.performDismissWithBlockingHelper(false /* fromAccessibility */));
    }

    @Test
    public void testPerformDismissWithBlockingHelper_doesntPerformOnGroupSummary() {
        ExpandableNotificationRow childRow = mGroupRow.getChildrenContainer().getViewAtPosition(0);
        when(mBlockingHelperManager.perhapsShowBlockingHelper(eq(childRow), any(NotificationMenuRowPlugin.class)))
                .thenReturn(true);

        assertTrue(
                childRow.performDismissWithBlockingHelper(false /* fromAccessibility */));

        verify(mBlockingHelperManager, times(1))
                .perhapsShowBlockingHelper(eq(childRow), any(NotificationMenuRowPlugin.class));
        verify(mBlockingHelperManager, times(0))
                .perhapsShowBlockingHelper(eq(mGroupRow), any(NotificationMenuRowPlugin.class));
    }

    @Test
    public void testIsBlockingHelperShowing_isCorrectlyUpdated() {
        mGroupRow.setBlockingHelperShowing(true);
        assertTrue(mGroupRow.isBlockingHelperShowing());

        mGroupRow.setBlockingHelperShowing(false);
        assertFalse(mGroupRow.isBlockingHelperShowing());
    }

    @Test
    public void testGetNumUniqueChildren_defaultChannel() {
        assertEquals(1, mGroupRow.getNumUniqueChannels());
    }

    @Test
    public void testGetNumUniqueChildren_multiChannel() {
        List<ExpandableNotificationRow> childRows =
                mGroupRow.getChildrenContainer().getAttachedChildren();
        // Give each child a unique channel id/name.
        int i = 0;
        for (ExpandableNotificationRow childRow : childRows) {
            modifyRanking(childRow.getEntry())
                    .setChannel(
                            new NotificationChannel(
                                    "id" + i, "dinnertime" + i, IMPORTANCE_DEFAULT))
                    .build();
            i++;
        }

        assertEquals(3, mGroupRow.getNumUniqueChannels());
    }

    @Test
    public void testIconScrollXAfterTranslationAndReset() throws Exception {
        mGroupRow.setTranslation(50);
        assertEquals(50, -mGroupRow.getEntry().getIcons().getShelfIcon().getScrollX());

        mGroupRow.resetTranslation();
        assertEquals(0, mGroupRow.getEntry().getIcons().getShelfIcon().getScrollX());
    }

    @Test
    public void testIsExpanded_userExpanded() {
        mGroupRow.setExpandable(true);
        Assert.assertFalse(mGroupRow.isExpanded());
        mGroupRow.setUserExpanded(true);
        Assert.assertTrue(mGroupRow.isExpanded());
    }

    @Test
    public void testGetIsNonblockable() throws Exception {
        ExpandableNotificationRow row =
                mNotificationTestHelper.createRow(mNotificationTestHelper.createNotification());

        assertFalse(row.getIsNonblockable());
    }

    @Test
    public void testGetIsNonblockable_oemLocked() throws Exception {
        ExpandableNotificationRow row =
                mNotificationTestHelper.createRow(mNotificationTestHelper.createNotification());
        row.getEntry().getChannel().setImportanceLockedByOEM(true);

        assertTrue(row.getIsNonblockable());
    }

    @Test
    public void testGetIsNonblockable_criticalDeviceFunction() throws Exception {
        ExpandableNotificationRow row =
                mNotificationTestHelper.createRow(mNotificationTestHelper.createNotification());
        row.getEntry().getChannel().setImportanceLockedByCriticalDeviceFunction(true);

        assertTrue(row.getIsNonblockable());
    }
}
