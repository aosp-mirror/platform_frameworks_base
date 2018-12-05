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
 * limitations under the License.
 */

package com.android.systemui.statusbar.notification;

import android.content.Context;
import android.support.test.filters.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;
import android.view.View;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.wrapper.NotificationViewWrapper;
import com.android.systemui.util.Assert;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@SmallTest
@RunWithLooper
public class NotificationViewWrapperTest extends SysuiTestCase {

    @Test
    public void constructor_doesntUseViewContext() throws Exception {
        Assert.sMainLooper = TestableLooper.get(this).getLooper();
        new TestableNotificationViewWrapper(mContext,
                new View(mContext),
                new NotificationTestHelper(getContext()).createRow());
    }

    static class TestableNotificationViewWrapper extends NotificationViewWrapper {
        protected TestableNotificationViewWrapper(Context ctx, View view,
                ExpandableNotificationRow row) {
            super(ctx, view, row);
        }
    }
}
