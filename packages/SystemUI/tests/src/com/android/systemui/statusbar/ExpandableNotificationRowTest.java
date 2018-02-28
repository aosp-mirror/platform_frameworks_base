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

package com.android.systemui.statusbar;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.ArraySet;
import android.view.NotificationHeaderView;
import android.view.View;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.AboveShelfChangedListener;
import com.android.systemui.statusbar.stack.NotificationChildrenContainer;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ExpandableNotificationRowTest extends SysuiTestCase {

    private ExpandableNotificationRow mGroup;
    private NotificationTestHelper mNotificationTestHelper;

    @Before
    public void setUp() throws Exception {
        mNotificationTestHelper = new NotificationTestHelper(mContext);
        mGroup = mNotificationTestHelper.createGroup();
    }

    @Test
    public void testGroupSummaryNotShowingIconWhenPublic() {
        mGroup.setSensitive(true, true);
        mGroup.setHideSensitiveForIntrinsicHeight(true);
        Assert.assertTrue(mGroup.isSummaryWithChildren());
        Assert.assertFalse(mGroup.isShowingIcon());
    }

    @Test
    public void testNotificationHeaderVisibleWhenAnimating() {
        mGroup.setSensitive(true, true);
        mGroup.setHideSensitive(true, false, 0, 0);
        mGroup.setHideSensitive(false, true, 0, 0);
        Assert.assertTrue(mGroup.getChildrenContainer().getVisibleHeader().getVisibility()
                == View.VISIBLE);
    }

    @Test
    public void testUserLockedResetEvenWhenNoChildren() {
        mGroup.setUserLocked(true);
        mGroup.removeAllChildren();
        mGroup.setUserLocked(false);
        Assert.assertFalse("The childrencontainer should not be userlocked but is, the state "
                + "seems out of sync.", mGroup.getChildrenContainer().isUserLocked());
    }

    @Test
    public void testReinflatedOnDensityChange() {
        mGroup.setUserLocked(true);
        mGroup.removeAllChildren();
        mGroup.setUserLocked(false);
        NotificationChildrenContainer mockContainer = mock(NotificationChildrenContainer.class);
        mGroup.setChildrenContainer(mockContainer);
        mGroup.onDensityOrFontScaleChanged();
        verify(mockContainer).reInflateViews(any(), any());
    }

    @Test
    public void testIconColorShouldBeUpdatedWhenSensitive() throws Exception {
        ExpandableNotificationRow row = spy(mNotificationTestHelper.createRow());
        row.setSensitive(true, true);
        row.setHideSensitive(true, false, 0, 0);
        verify(row).updateShelfIconColor();
    }

    @Test
    public void testIconColorShouldBeUpdatedWhenSettingDark() throws Exception {
        ExpandableNotificationRow row = spy(mNotificationTestHelper.createRow());
        row.setDark(true, false, 0);
        verify(row).updateShelfIconColor();
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
        Assert.assertTrue("Should play sounds by default.", mGroup.isSoundEffectsEnabled());
        mGroup.setDark(true /* dark */, false /* fade */, 0 /* delay */);
        mGroup.setSecureStateProvider(()-> false);
        Assert.assertFalse("Shouldn't play sounds when dark and trusted.",
                mGroup.isSoundEffectsEnabled());
        mGroup.setSecureStateProvider(()-> true);
        Assert.assertTrue("Should always play sounds when not trusted.",
                mGroup.isSoundEffectsEnabled());
    }

    @Test
    public void testShowAppOps_noHeader() {
        // public notification is custom layout - no header
        mGroup.setSensitive(true, true);
        mGroup.setAppOpsOnClickListener(null);
        mGroup.showAppOpsIcons(null);
    }

    @Test
    public void testShowAppOpsIcons_header() {
        NotificationHeaderView mockHeader = mock(NotificationHeaderView.class);

        NotificationContentView publicLayout = mock(NotificationContentView.class);
        mGroup.setPublicLayout(publicLayout);
        NotificationContentView privateLayout = mock(NotificationContentView.class);
        mGroup.setPrivateLayout(privateLayout);
        NotificationChildrenContainer mockContainer = mock(NotificationChildrenContainer.class);
        when(mockContainer.getNotificationChildCount()).thenReturn(1);
        when(mockContainer.getHeaderView()).thenReturn(mockHeader);
        mGroup.setChildrenContainer(mockContainer);

        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(AppOpsManager.OP_ANSWER_PHONE_CALLS);
        mGroup.showAppOpsIcons(ops);

        verify(mockHeader, times(1)).showAppOpsIcons(ops);
        verify(privateLayout, times(1)).showAppOpsIcons(ops);
        verify(publicLayout, times(1)).showAppOpsIcons(ops);

    }

    @Test
    public void testAppOpsOnClick() {
        ExpandableNotificationRow.OnAppOpsClickListener l = mock(
                ExpandableNotificationRow.OnAppOpsClickListener.class);
        View view = mock(View.class);

        mGroup.setAppOpsOnClickListener(l);

        mGroup.getAppOpsOnClickListener().onClick(view);
        verify(l, times(1)).onClick(any(), anyInt(), anyInt(), any());
    }
}
