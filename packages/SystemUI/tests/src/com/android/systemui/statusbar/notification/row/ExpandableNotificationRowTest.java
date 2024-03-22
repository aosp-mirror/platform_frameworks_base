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

import static android.app.Notification.FLAG_NO_CLEAR;
import static android.app.Notification.FLAG_ONGOING_EVENT;
import static android.app.NotificationManager.IMPORTANCE_DEFAULT;

import static com.android.systemui.statusbar.NotificationEntryHelper.modifyRanking;
import static com.android.systemui.statusbar.NotificationEntryHelper.modifySbn;
import static com.android.systemui.statusbar.notification.row.NotificationRowContentBinder.FLAG_CONTENT_VIEW_ALL;
import static com.android.systemui.statusbar.notification.row.NotificationTestHelper.PKG;
import static com.android.systemui.statusbar.notification.row.NotificationTestHelper.USER_HANDLE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Notification;
import android.app.NotificationChannel;
import android.content.Context;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.util.DisplayMetrics;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.internal.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.SysuiTestableContext;
import com.android.systemui.plugins.statusbar.NotificationMenuRowPlugin;
import com.android.systemui.plugins.statusbar.StatusBarStateController;
import com.android.systemui.statusbar.notification.AboveShelfChangedListener;
import com.android.systemui.statusbar.notification.FeedbackIcon;
import com.android.systemui.statusbar.notification.row.ExpandableView.OnHeightChangedListener;
import com.android.systemui.statusbar.notification.collection.NotificationEntry;
import com.android.systemui.statusbar.notification.stack.NotificationChildrenContainer;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class ExpandableNotificationRowTest extends SysuiTestCase {

    private ExpandableNotificationRow mGroupRow;
    private ExpandableNotificationRow mNotifRow;
    private ExpandableNotificationRow mPublicRow;

    private NotificationTestHelper mNotificationTestHelper;
    boolean mHeadsUpAnimatingAway = false;

    @Rule public MockitoRule mockito = MockitoJUnit.rule();

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        mNotificationTestHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mNotificationTestHelper.setDefaultInflationFlags(FLAG_CONTENT_VIEW_ALL);
        // create a standard private notification row
        Notification normalNotif = mNotificationTestHelper.createNotification();
        normalNotif.publicVersion = null;
        mNotifRow = mNotificationTestHelper.createRow(normalNotif);
        // create a notification row whose public version is identical
        Notification publicNotif = mNotificationTestHelper.createNotification();
        publicNotif.publicVersion = mNotificationTestHelper.createNotification();
        mPublicRow = mNotificationTestHelper.createRow(publicNotif);
        // create a group row
        mGroupRow = mNotificationTestHelper.createGroup();
        mGroupRow.setHeadsUpAnimatingAwayListener(
                animatingAway -> mHeadsUpAnimatingAway = animatingAway);

    }

    @Test
    public void testSetSensitiveOnNotifRowNotifiesOfHeightChange() throws InterruptedException {
        // GIVEN a sensitive notification row that's currently redacted
        measureAndLayout(mNotifRow);
        mNotifRow.setHideSensitiveForIntrinsicHeight(true);
        mNotifRow.setSensitive(true, true);
        assertThat(mNotifRow.getShowingLayout()).isSameInstanceAs(mNotifRow.getPublicLayout());
        assertThat(mNotifRow.getIntrinsicHeight()).isGreaterThan(0);

        // GIVEN that the row has a height change listener
        OnHeightChangedListener listener = mock(OnHeightChangedListener.class);
        mNotifRow.setOnHeightChangedListener(listener);

        // WHEN the row is set to no longer be sensitive
        mNotifRow.setSensitive(false, true);

        // VERIFY that the height change listener is invoked
        assertThat(mNotifRow.getShowingLayout()).isSameInstanceAs(mNotifRow.getPrivateLayout());
        assertThat(mNotifRow.getIntrinsicHeight()).isGreaterThan(0);
        verify(listener).onHeightChanged(eq(mNotifRow), eq(false));
    }

    @Test
    public void testSetSensitiveOnGroupRowNotifiesOfHeightChange() {
        // GIVEN a sensitive group row that's currently redacted
        measureAndLayout(mGroupRow);
        mGroupRow.setHideSensitiveForIntrinsicHeight(true);
        mGroupRow.setSensitive(true, true);
        assertThat(mGroupRow.getShowingLayout()).isSameInstanceAs(mGroupRow.getPublicLayout());
        assertThat(mGroupRow.getIntrinsicHeight()).isGreaterThan(0);

        // GIVEN that the row has a height change listener
        OnHeightChangedListener listener = mock(OnHeightChangedListener.class);
        mGroupRow.setOnHeightChangedListener(listener);

        // WHEN the row is set to no longer be sensitive
        mGroupRow.setSensitive(false, true);

        // VERIFY that the height change listener is invoked
        assertThat(mGroupRow.getShowingLayout()).isSameInstanceAs(mGroupRow.getPrivateLayout());
        assertThat(mGroupRow.getIntrinsicHeight()).isGreaterThan(0);
        verify(listener).onHeightChanged(eq(mGroupRow), eq(false));
    }

    @Test
    public void testSetSensitiveOnPublicRowDoesNotNotifyOfHeightChange() {
        // GIVEN a sensitive public row that's currently redacted
        measureAndLayout(mPublicRow);
        mPublicRow.setHideSensitiveForIntrinsicHeight(true);
        mPublicRow.setSensitive(true, true);
        assertThat(mPublicRow.getShowingLayout()).isSameInstanceAs(mPublicRow.getPublicLayout());
        assertThat(mPublicRow.getIntrinsicHeight()).isGreaterThan(0);

        // GIVEN that the row has a height change listener
        OnHeightChangedListener listener = mock(OnHeightChangedListener.class);
        mPublicRow.setOnHeightChangedListener(listener);

        // WHEN the row is set to no longer be sensitive
        mPublicRow.setSensitive(false, true);

        // VERIFY that the height change listener is not invoked, because the height didn't change
        assertThat(mPublicRow.getShowingLayout()).isSameInstanceAs(mPublicRow.getPrivateLayout());
        assertThat(mPublicRow.getIntrinsicHeight()).isGreaterThan(0);
        assertThat(mPublicRow.getPrivateLayout().getMinHeight())
                .isEqualTo(mPublicRow.getPublicLayout().getMinHeight());
        verify(listener, never()).onHeightChanged(eq(mPublicRow), eq(false));
    }

    private void measureAndLayout(ExpandableNotificationRow row) {
        DisplayMetrics dm = new DisplayMetrics();
        getContext().getDisplay().getRealMetrics(dm);
        int width = (int) Math.ceil(400f * dm.density);
        int height = (int) Math.ceil(600f * dm.density);

        row.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.UNSPECIFIED)
        );
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
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
        assertEquals(View.VISIBLE, mGroupRow.getChildrenContainer().getVisibleWrapper()
                .getNotificationHeader().getVisibility());
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
    public void testFeedback_noHeader() {
        // public notification is custom layout - no header
        mGroupRow.setSensitive(true, true);
        mGroupRow.setOnFeedbackClickListener(null);
        mGroupRow.setFeedbackIcon(null);
    }

    @Test
    public void testFeedback_header() {
        NotificationContentView publicLayout = mock(NotificationContentView.class);
        mGroupRow.setPublicLayout(publicLayout);
        NotificationContentView privateLayout = mock(NotificationContentView.class);
        mGroupRow.setPrivateLayout(privateLayout);
        NotificationChildrenContainer mockContainer = mock(NotificationChildrenContainer.class);
        when(mockContainer.getNotificationChildCount()).thenReturn(1);
        mGroupRow.setChildrenContainer(mockContainer);

        final boolean show = true;
        final FeedbackIcon icon = new FeedbackIcon(
                R.drawable.ic_feedback_alerted, R.string.notification_feedback_indicator_alerted);
        mGroupRow.setFeedbackIcon(icon);

        verify(mockContainer, times(1)).setFeedbackIcon(icon);
        verify(privateLayout, times(1)).setFeedbackIcon(icon);
        verify(publicLayout, times(1)).setFeedbackIcon(icon);
    }

    @Test
    public void testFeedbackOnClick() {
        ExpandableNotificationRow.CoordinateOnClickListener l = mock(
                ExpandableNotificationRow.CoordinateOnClickListener.class);
        View view = mock(View.class);

        mGroupRow.setOnFeedbackClickListener(l);

        mGroupRow.getFeedbackOnClickListener().onClick(view);
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
        mGroupRow.setDismissUsingRowTranslationX(false);
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
        row.setEntry(null);

        assertTrue(row.getIsNonblockable());

        NotificationEntry entry = mock(NotificationEntry.class);

        Mockito.doReturn(false, true).when(entry).isBlockable();
        row.setEntry(entry);
        assertTrue(row.getIsNonblockable());
        assertFalse(row.getIsNonblockable());
    }

    @Test
    public void testCanDismissNoClear() throws Exception {
        ExpandableNotificationRow row =
                mNotificationTestHelper.createRow(mNotificationTestHelper.createNotification());
        modifySbn(row.getEntry())
                .setFlag(mContext, FLAG_NO_CLEAR, true)
                .build();
        row.performDismiss(false);
        verify(mNotificationTestHelper.mOnUserInteractionCallback)
                .registerFutureDismissal(any(), anyInt());
    }

    @Test
    public void testCannotDismissOngoing() throws Exception {
        ExpandableNotificationRow row =
                mNotificationTestHelper.createRow(mNotificationTestHelper.createNotification());
        modifySbn(row.getEntry())
                .setFlag(mContext, FLAG_ONGOING_EVENT, true)
                .build();
        row.performDismiss(false);
        verify(mNotificationTestHelper.mOnUserInteractionCallback, never())
                .registerFutureDismissal(any(), anyInt());
    }

    @Test
    public void imageResolver_sameNotificationUser_usesContext() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRow(PKG,
                USER_HANDLE.getUid(1234), USER_HANDLE);

        assertThat(row.getImageResolver().getContext()).isSameInstanceAs(mContext);
    }

    @Test
    public void imageResolver_differentNotificationUser_createsUserContext() throws Exception {
        UserHandle user = new UserHandle(33);
        Context userContext = new SysuiTestableContext(mContext);
        mContext.prepareCreateContextAsUser(user, userContext);

        ExpandableNotificationRow row = mNotificationTestHelper.createRow(PKG,
                user.getUid(1234), user);

        assertThat(row.getImageResolver().getContext()).isSameInstanceAs(userContext);
    }
}
