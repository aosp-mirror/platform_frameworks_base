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

package com.android.systemui.statusbar.notification.stack;

import static org.junit.Assert.assertNull;

import android.app.Notification;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.NotificationHeaderView;
import android.view.View;
import android.widget.RemoteViews;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.notification.row.shared.AsyncGroupHeaderViewInflation;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
//@DisableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
public class NotificationChildrenContainerTest extends SysuiTestCase {

    private ExpandableNotificationRow mGroup;
    private NotificationTestHelper mNotificationTestHelper;
    private NotificationChildrenContainer mChildrenContainer;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        mNotificationTestHelper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mGroup = mNotificationTestHelper.createGroup();
        mChildrenContainer = mGroup.getChildrenContainer();
    }

    @Test
    public void testGetMaxAllowedVisibleChildren_lowPriority() {
        mChildrenContainer.setIsLowPriority(true);
        Assert.assertEquals(mChildrenContainer.getMaxAllowedVisibleChildren(),
                NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED);
    }

    @Test
    public void testGetMaxAllowedVisibleChildren_headsUp() {
        mGroup.setHeadsUp(true);
        Assert.assertEquals(mChildrenContainer.getMaxAllowedVisibleChildren(),
                NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED);
    }

    @Test
    public void testGetMaxAllowedVisibleChildren_lowPriority_expandedChildren() {
        mChildrenContainer.setIsLowPriority(true);
        mChildrenContainer.setChildrenExpanded(true);
        Assert.assertEquals(mChildrenContainer.getMaxAllowedVisibleChildren(),
                NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED);
    }

    @Test
    public void testGetMaxAllowedVisibleChildren_lowPriority_userLocked() {
        mChildrenContainer.setIsLowPriority(true);
        mChildrenContainer.setUserLocked(true);
        Assert.assertEquals(mChildrenContainer.getMaxAllowedVisibleChildren(),
                NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_SYSTEM_EXPANDED);
    }

    @Test
    public void testGetMaxAllowedVisibleChildren_likeCollapsed() {
        Assert.assertEquals(mChildrenContainer.getMaxAllowedVisibleChildren(true),
                NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_COLLAPSED);
    }


    @Test
    public void testGetMaxAllowedVisibleChildren_expandedChildren() {
        mChildrenContainer.setChildrenExpanded(true);
        Assert.assertEquals(mChildrenContainer.getMaxAllowedVisibleChildren(),
                NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
    }

    @Test
    public void testGetMaxAllowedVisibleChildren_userLocked() {
        mGroup.setUserLocked(true);
        Assert.assertEquals(mChildrenContainer.getMaxAllowedVisibleChildren(),
                NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
    }

    @Test
    public void testShowingAsLowPriority_lowPriority() {
        mChildrenContainer.setIsLowPriority(true);
        Assert.assertTrue(mChildrenContainer.showingAsLowPriority());
    }

    @Test
    public void testShowingAsLowPriority_notLowPriority() {
        Assert.assertFalse(mChildrenContainer.showingAsLowPriority());
    }

    @Test
    public void testShowingAsLowPriority_lowPriority_expanded() {
        mChildrenContainer.setIsLowPriority(true);
        mGroup.setExpandable(true);
        mGroup.setUserExpanded(true, false);
        Assert.assertFalse(mChildrenContainer.showingAsLowPriority());
    }

    @Test
    public void testGetMaxAllowedVisibleChildren_userLocked_expandedChildren_lowPriority() {
        mGroup.setUserLocked(true);
        mGroup.setExpandable(true);
        mGroup.setUserExpanded(true);
        mChildrenContainer.setIsLowPriority(true);
        Assert.assertEquals(mChildrenContainer.getMaxAllowedVisibleChildren(),
                NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_CHILDREN_EXPANDED);
    }

    @Test
    @DisableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
    public void testLowPriorityHeaderCleared() {
        mGroup.setIsLowPriority(true);
        NotificationHeaderView lowPriorityHeaderView =
                mChildrenContainer.getLowPriorityViewWrapper().getNotificationHeader();
        Assert.assertEquals(View.VISIBLE, lowPriorityHeaderView.getVisibility());
        Assert.assertSame(mChildrenContainer, lowPriorityHeaderView.getParent());
        mGroup.setIsLowPriority(false);
        assertNull(lowPriorityHeaderView.getParent());
        assertNull(mChildrenContainer.getLowPriorityViewWrapper());
    }

    @Test
    @DisableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
    public void testRecreateNotificationHeader_hasHeader() {
        mChildrenContainer.recreateNotificationHeader(null, false);
        Assert.assertNotNull("Children container must have a header after recreation",
                mChildrenContainer.getCurrentHeaderView());
    }

    @Test
    @EnableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
    public void testSetLowPriorityWithAsyncInflation_noHeaderReInflation() {
        mChildrenContainer.setIsLowPriority(true);
        assertNull("We don't inflate header from the main thread with Async "
                + "Inflation enabled", mChildrenContainer.getCurrentHeaderView());
    }

    @Test
    @EnableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
    public void setLowPriorityBeforeLowPriorityHeaderSet() {

        //Given: the children container does not have a low-priority header, and is not low-priority
        assertNull(mChildrenContainer.getLowPriorityViewWrapper());
        mGroup.setIsLowPriority(false);

        //When: set the children container to be low-priority and set the low-priority header
        mGroup.setIsLowPriority(true);
        mGroup.setLowPriorityGroupHeader(createHeaderView(/* lowPriorityHeader= */ true));

        //Then: the low-priority group header should be visible
        NotificationHeaderView lowPriorityHeaderView =
                mChildrenContainer.getLowPriorityViewWrapper().getNotificationHeader();
        Assert.assertEquals(View.VISIBLE, lowPriorityHeaderView.getVisibility());
        Assert.assertSame(mChildrenContainer, lowPriorityHeaderView.getParent());

        //When: set the children container to be not low-priority and set the normal header
        mGroup.setIsLowPriority(false);
        mGroup.setGroupHeader(createHeaderView(/* lowPriorityHeader= */ false));

        //Then: the low-priority group header should not be visible , normal header should be
        // visible
        Assert.assertEquals(View.INVISIBLE, lowPriorityHeaderView.getVisibility());
        Assert.assertEquals(
                View.VISIBLE,
                mChildrenContainer.getNotificationHeaderWrapper().getNotificationHeader()
                        .getVisibility()
        );
    }

    @Test
    @EnableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
    public void changeLowPriorityAfterHeaderSet() {

        //Given: the children container does not have headers, and is not low-priority
        assertNull(mChildrenContainer.getLowPriorityViewWrapper());
        assertNull(mChildrenContainer.getNotificationHeaderWrapper());
        mGroup.setIsLowPriority(false);

        //When: set the set the normal header
        mGroup.setGroupHeader(createHeaderView(/* lowPriorityHeader= */ false));

        //Then: the group header should be visible
        NotificationHeaderView headerView =
                mChildrenContainer.getNotificationHeaderWrapper().getNotificationHeader();
        Assert.assertEquals(View.VISIBLE, headerView.getVisibility());
        Assert.assertSame(mChildrenContainer, headerView.getParent());

        //When: set the set the row to be low priority, and set the low-priority header
        mGroup.setIsLowPriority(true);
        mGroup.setLowPriorityGroupHeader(createHeaderView(/* lowPriorityHeader= */ true));

        //Then: the header view should not be visible, the low-priority group header should be
        // visible
        Assert.assertEquals(View.INVISIBLE, headerView.getVisibility());
        NotificationHeaderView lowPriorityHeaderView =
                mChildrenContainer.getLowPriorityViewWrapper().getNotificationHeader();
        Assert.assertEquals(View.VISIBLE, lowPriorityHeaderView.getVisibility());
    }

    @Test
    public void applyRoundnessAndInvalidate_should_be_immediately_applied_on_last_child() {
        List<ExpandableNotificationRow> children = mChildrenContainer.getAttachedChildren();
        ExpandableNotificationRow notificationRow = children.get(children.size() - 1);
        Assert.assertEquals(0f, mChildrenContainer.getBottomRoundness(), 0.001f);
        Assert.assertEquals(0f, notificationRow.getBottomRoundness(), 0.001f);

        mChildrenContainer.requestBottomRoundness(1f, SourceType.from(""), false);

        Assert.assertEquals(1f, mChildrenContainer.getBottomRoundness(), 0.001f);
        Assert.assertEquals(1f, notificationRow.getBottomRoundness(), 0.001f);
    }

    @Test
    @DisableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
    public void applyRoundnessAndInvalidate_should_be_immediately_applied_on_header() {
        NotificationHeaderViewWrapper header = mChildrenContainer.getNotificationHeaderWrapper();
        Assert.assertEquals(0f, header.getTopRoundness(), 0.001f);

        mChildrenContainer.requestTopRoundness(1f, SourceType.from(""), false);

        Assert.assertEquals(1f, header.getTopRoundness(), 0.001f);
    }

    @Test
    @DisableFlags(AsyncGroupHeaderViewInflation.FLAG_NAME)
    public void applyRoundnessAndInvalidate_should_be_immediately_applied_on_headerLowPriority() {
        mChildrenContainer.setIsLowPriority(true);

        NotificationHeaderViewWrapper header = mChildrenContainer.getNotificationHeaderWrapper();
        Assert.assertEquals(0f, header.getTopRoundness(), 0.001f);

        mChildrenContainer.requestTopRoundness(1f, SourceType.from(""), false);

        Assert.assertEquals(1f, header.getTopRoundness(), 0.001f);
    }

    private NotificationHeaderView createHeaderView(boolean lowPriority) {
        Notification notification = mNotificationTestHelper.createNotification();
        final Notification.Builder builder = Notification.Builder.recoverBuilder(getContext(),
                notification);
        RemoteViews headerRemoteViews;
        if (lowPriority) {
            headerRemoteViews = builder.makeLowPriorityContentView(true);
        } else {
            headerRemoteViews = builder.makeNotificationGroupHeader();
        }
        return (NotificationHeaderView) headerRemoteViews.apply(getContext(), mChildrenContainer);
    }
}
