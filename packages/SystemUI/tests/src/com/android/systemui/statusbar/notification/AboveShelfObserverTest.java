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

package com.android.systemui.statusbar.notification;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.widget.FrameLayout;

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
public class AboveShelfObserverTest extends SysuiTestCase {

    private AboveShelfObserver mObserver;
    private FrameLayout mHostLayout;
    private NotificationTestHelper mNotificationTestHelper;
    private AboveShelfObserver.HasViewAboveShelfChangedListener mListener;

    @Before
    public void setUp() throws Exception {
        com.android.systemui.util.Assert.sMainLooper = TestableLooper.get(this).getLooper();
        mNotificationTestHelper = new NotificationTestHelper(getContext());
        mHostLayout = new FrameLayout(getContext());
        mObserver = new AboveShelfObserver(mHostLayout);
        ExpandableNotificationRow row = mNotificationTestHelper.createRow();
        row.setAboveShelfChangedListener(mObserver);
        mHostLayout.addView(row);
        row = mNotificationTestHelper.createRow();
        row.setAboveShelfChangedListener(mObserver);
        mHostLayout.addView(row);
        mListener = mock(AboveShelfObserver.HasViewAboveShelfChangedListener.class);
    }

    @Test
    public void testObserverChangesWhenGoingAbove() {
        ExpandableNotificationRow row = (ExpandableNotificationRow) mHostLayout.getChildAt(0);
        mObserver.setListener(mListener);
        row.setHeadsUp(true);
        verify(mListener).onHasViewsAboveShelfChanged(true);
    }

    @Test
    public void testObserverChangesWhenGoingBelow() {
        ExpandableNotificationRow row = (ExpandableNotificationRow) mHostLayout.getChildAt(0);
        row.setHeadsUp(true);
        mObserver.setListener(mListener);
        row.setHeadsUp(false);
        verify(mListener).onHasViewsAboveShelfChanged(false);
    }

    @Test
    public void testStaysAboveWhenOneGoesAway() {
        ExpandableNotificationRow row = (ExpandableNotificationRow) mHostLayout.getChildAt(0);
        row.setHeadsUp(true);
        row = (ExpandableNotificationRow) mHostLayout.getChildAt(1);
        row.setHeadsUp(true);
        row.setHeadsUp(false);
        Assert.assertTrue("There are still views above the shelf but removing one cleared it",
                mObserver.hasViewsAboveShelf());
    }
}

