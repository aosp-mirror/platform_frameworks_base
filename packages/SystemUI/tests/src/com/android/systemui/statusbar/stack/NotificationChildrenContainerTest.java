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

package com.android.systemui.statusbar.stack;

import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.view.NotificationHeaderView;
import android.view.View;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.ExpandableNotificationRow;
import com.android.systemui.statusbar.NotificationTestHelper;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper(setAsMainLooper = true)
public class NotificationChildrenContainerTest extends SysuiTestCase {

    private ExpandableNotificationRow mGroup;
    private int mId;
    private NotificationTestHelper mNotificationTestHelper;

    @Before
    public void setUp() throws Exception {
        mNotificationTestHelper = new NotificationTestHelper(mContext);
        mGroup = mNotificationTestHelper.createGroup();
    }

    @Test
    public void testLowPriorityHeaderCleared() {
        mGroup.setIsLowPriority(true);
        NotificationChildrenContainer childrenContainer = mGroup.getChildrenContainer();
        NotificationHeaderView lowPriorityHeaderView = childrenContainer.getLowPriorityHeaderView();
        Assert.assertTrue(lowPriorityHeaderView.getVisibility() == View.VISIBLE);
        Assert.assertTrue(lowPriorityHeaderView.getParent() == childrenContainer);
        mGroup.setIsLowPriority(false);
        Assert.assertTrue(lowPriorityHeaderView.getParent() == null);
        Assert.assertTrue(childrenContainer.getLowPriorityHeaderView() == null);
    }

    @Test
    public void testRecreateNotificationHeader_hasHeader() {
        NotificationChildrenContainer childrenContainer = mGroup.getChildrenContainer();
        childrenContainer.recreateNotificationHeader(null);
        Assert.assertNotNull("Children container must have a header after recreation",
                childrenContainer.getCurrentHeaderView());
    }
}
