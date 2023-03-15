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

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.NotificationHeaderView;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.LegacySourceType;
import com.android.systemui.statusbar.notification.SourceType;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationHeaderViewWrapper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
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
    public void testLowPriorityHeaderCleared() {
        mGroup.setIsLowPriority(true);
        NotificationHeaderView lowPriorityHeaderView =
                mChildrenContainer.getLowPriorityViewWrapper().getNotificationHeader();
        Assert.assertEquals(View.VISIBLE, lowPriorityHeaderView.getVisibility());
        Assert.assertSame(mChildrenContainer, lowPriorityHeaderView.getParent());
        mGroup.setIsLowPriority(false);
        Assert.assertNull(lowPriorityHeaderView.getParent());
        Assert.assertNull(mChildrenContainer.getLowPriorityViewWrapper());
    }

    @Test
    public void testRecreateNotificationHeader_hasHeader() {
        mChildrenContainer.recreateNotificationHeader(null, false);
        Assert.assertNotNull("Children container must have a header after recreation",
                mChildrenContainer.getCurrentHeaderView());
    }

    @Test
    public void addNotification_shouldResetOnScrollRoundness() throws Exception {
        ExpandableNotificationRow row = mNotificationTestHelper.createRowWithRoundness(
                /* topRoundness = */ 1f,
                /* bottomRoundness = */ 1f,
                /* sourceType = */ LegacySourceType.OnScroll);

        mChildrenContainer.addNotification(row, 0);

        Assert.assertEquals(0f, row.getTopRoundness(), /* delta = */ 0f);
        Assert.assertEquals(0f, row.getBottomRoundness(), /* delta = */ 0f);
    }

    @Test
    public void addNotification_shouldNotResetOtherRoundness() throws Exception {
        ExpandableNotificationRow row1 = mNotificationTestHelper.createRowWithRoundness(
                /* topRoundness = */ 1f,
                /* bottomRoundness = */ 1f,
                /* sourceType = */ LegacySourceType.DefaultValue);
        ExpandableNotificationRow row2 = mNotificationTestHelper.createRowWithRoundness(
                /* topRoundness = */ 1f,
                /* bottomRoundness = */ 1f,
                /* sourceType = */ LegacySourceType.OnDismissAnimation);

        mChildrenContainer.addNotification(row1, 0);
        mChildrenContainer.addNotification(row2, 0);

        Assert.assertEquals(1f, row1.getTopRoundness(), /* delta = */ 0f);
        Assert.assertEquals(1f, row1.getBottomRoundness(), /* delta = */ 0f);
        Assert.assertEquals(1f, row2.getTopRoundness(), /* delta = */ 0f);
        Assert.assertEquals(1f, row2.getBottomRoundness(), /* delta = */ 0f);
    }

    @Test
    public void applyRoundnessAndInvalidate_should_be_immediately_applied_on_last_child_legacy() {
        mChildrenContainer.useRoundnessSourceTypes(false);
        List<ExpandableNotificationRow> children = mChildrenContainer.getAttachedChildren();
        ExpandableNotificationRow notificationRow = children.get(children.size() - 1);
        Assert.assertEquals(0f, mChildrenContainer.getBottomRoundness(), 0.001f);
        Assert.assertEquals(0f, notificationRow.getBottomRoundness(), 0.001f);

        mChildrenContainer.requestBottomRoundness(1f, SourceType.from(""), false);

        Assert.assertEquals(1f, mChildrenContainer.getBottomRoundness(), 0.001f);
        Assert.assertEquals(1f, notificationRow.getBottomRoundness(), 0.001f);
    }

    @Test
    public void applyRoundnessAndInvalidate_should_be_immediately_applied_on_last_child() {
        mChildrenContainer.useRoundnessSourceTypes(true);
        List<ExpandableNotificationRow> children = mChildrenContainer.getAttachedChildren();
        ExpandableNotificationRow notificationRow = children.get(children.size() - 1);
        Assert.assertEquals(0f, mChildrenContainer.getBottomRoundness(), 0.001f);
        Assert.assertEquals(0f, notificationRow.getBottomRoundness(), 0.001f);

        mChildrenContainer.requestBottomRoundness(1f, SourceType.from(""), false);

        Assert.assertEquals(1f, mChildrenContainer.getBottomRoundness(), 0.001f);
        Assert.assertEquals(1f, notificationRow.getBottomRoundness(), 0.001f);
    }

    @Test
    public void applyRoundnessAndInvalidate_should_be_immediately_applied_on_header() {
        mChildrenContainer.useRoundnessSourceTypes(true);

        NotificationHeaderViewWrapper header = mChildrenContainer.getNotificationHeaderWrapper();
        Assert.assertEquals(0f, header.getTopRoundness(), 0.001f);

        mChildrenContainer.requestTopRoundness(1f, SourceType.from(""), false);

        Assert.assertEquals(1f, header.getTopRoundness(), 0.001f);
    }

    @Test
    public void applyRoundnessAndInvalidate_should_be_immediately_applied_on_headerLowPriority() {
        mChildrenContainer.useRoundnessSourceTypes(true);
        mChildrenContainer.setIsLowPriority(true);

        NotificationHeaderViewWrapper header = mChildrenContainer.getNotificationHeaderWrapper();
        Assert.assertEquals(0f, header.getTopRoundness(), 0.001f);

        mChildrenContainer.requestTopRoundness(1f, SourceType.from(""), false);

        Assert.assertEquals(1f, header.getTopRoundness(), 0.001f);
    }
}
