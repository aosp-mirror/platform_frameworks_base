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

package com.android.systemui.statusbar.notification.row.wrapper;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.statusbar.notification.row.NotificationTestHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class NotificationViewWrapperTest extends SysuiTestCase {

    private View mView;
    private ExpandableNotificationRow mRow;
    private TestableNotificationViewWrapper mNotificationViewWrapper;

    @Before
    public void setup() throws Exception {
        mView = mock(View.class);
        when(mView.getContext()).thenReturn(mContext);
        NotificationTestHelper helper = new NotificationTestHelper(mContext, mDependency);
        mRow = helper.createRow();
        mNotificationViewWrapper = new TestableNotificationViewWrapper(mContext, mView, mRow);
    }

    @Test
    public void childrenNeedInversion_doesntCrash_whenOpacity() {
        LinearLayout viewGroup = new LinearLayout(mContext);
        TextView textView = new TextView(mContext);
        textView.setTextColor(0xcc000000);
        viewGroup.addView(textView);

        mNotificationViewWrapper.childrenNeedInversion(0xcc000000, viewGroup);
    }

    static class TestableNotificationViewWrapper extends NotificationViewWrapper {
        protected TestableNotificationViewWrapper(Context ctx, View view,
                ExpandableNotificationRow row) {
            super(ctx, view, row);
        }
    }
}
