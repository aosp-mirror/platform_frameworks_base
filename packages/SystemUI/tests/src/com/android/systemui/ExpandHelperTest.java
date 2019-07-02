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

package com.android.systemui;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.animation.ObjectAnimator;
import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;

import com.android.systemui.statusbar.NotificationTestHelper;
import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow;
import com.android.systemui.util.Assert;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
public class ExpandHelperTest extends SysuiTestCase {

    private ExpandableNotificationRow mRow;
    private ExpandHelper mExpandHelper;
    private ExpandHelper.Callback mCallback;

    @Before
    public void setUp() throws Exception {
        Assert.sMainLooper = TestableLooper.get(this).getLooper();
        Context context = getContext();
        mRow = new NotificationTestHelper(context).createRow();
        mCallback = mock(ExpandHelper.Callback.class);
        mExpandHelper = new ExpandHelper(context, mCallback, 10, 100);
    }

    @Test
    @UiThreadTest
    public void testAnimationDoesntClearViewIfNewExpansionStarted() {
        when(mCallback.getMaxExpandHeight(any())).thenReturn(100);
        mExpandHelper.startExpanding(mRow, 0);
        mExpandHelper.finishExpanding(false, 0);
        mExpandHelper.startExpanding(mRow, 0);
        ObjectAnimator scaleAnimation = mExpandHelper.getScaleAnimation();
        scaleAnimation.end();
        mExpandHelper.updateExpansion();
    }

}
