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
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NotificationChildrenContainerTest extends SysuiTestCase {

    private ExpandableNotificationRow mGroup;
    private NotificationTestHelper mNotificationTestHelper;
    private NotificationChildrenContainer mChildrenContainer;

    @Before
    public void setUp() throws Exception {
        com.android.systemui.util.Assert.sMainLooper = TestableLooper.get(this).getLooper();
        mNotificationTestHelper = new NotificationTestHelper(mContext);
        mGroup = mNotificationTestHelper.createGroup();
        mChildrenContainer = mGroup.getChildrenContainer();
    }

    @Test
    public void testGetMaxAllowedVisibleChildren_ambient() {
        mGroup.setOnAmbient(true);
        Assert.assertEquals(mChildrenContainer.getMaxAllowedVisibleChildren(),
            NotificationChildrenContainer.NUMBER_OF_CHILDREN_WHEN_AMBIENT);
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
        NotificationHeaderView lowPriorityHeaderView = mChildrenContainer.getLowPriorityHeaderView();
        Assert.assertTrue(lowPriorityHeaderView.getVisibility() == View.VISIBLE);
        Assert.assertTrue(lowPriorityHeaderView.getParent() == mChildrenContainer);
        mGroup.setIsLowPriority(false);
        Assert.assertTrue(lowPriorityHeaderView.getParent() == null);
        Assert.assertTrue(mChildrenContainer.getLowPriorityHeaderView() == null);
    }

    @Test
    public void testRecreateNotificationHeader_hasHeader() {
        mChildrenContainer.recreateNotificationHeader(null);
        Assert.assertNotNull("Children container must have a header after recreation",
                mChildrenContainer.getCurrentHeaderView());
    }
}
