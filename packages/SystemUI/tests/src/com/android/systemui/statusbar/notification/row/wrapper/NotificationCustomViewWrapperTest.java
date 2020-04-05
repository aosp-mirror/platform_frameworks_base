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

package com.android.systemui.statusbar.notification.row.wrapper;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;
import android.widget.RemoteViews;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;
import com.android.systemui.tests.R;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class NotificationCustomViewWrapperTest extends SysuiTestCase {

    private ExpandableNotificationRow mRow;

    @Before
    public void setUp() throws Exception {
        allowTestableLooperAsMainThread();
        NotificationTestHelper helper = new NotificationTestHelper(
                mContext,
                mDependency,
                TestableLooper.get(this));
        mRow = helper.createRow();
    }

    @Test
    public void testBackgroundPersists() {
        RemoteViews views = new RemoteViews(mContext.getPackageName(), R.layout.custom_view_dark);
        View v = views.apply(mContext, null);
        NotificationViewWrapper wrap = NotificationCustomViewWrapper.wrap(mContext, v, mRow);
        wrap.onContentUpdated(mRow);
        Assert.assertTrue("No background set, when applying custom background view",
                wrap.getCustomBackgroundColor() != 0);
        views.reapply(mContext, v);
        wrap.onReinflated();
        wrap.onContentUpdated(mRow);
        Assert.assertTrue("Reapplying a custom remote view lost it's background!",
                wrap.getCustomBackgroundColor() != 0);
    }

}
