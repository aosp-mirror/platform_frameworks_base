/*
 * Copyright (C) 2014 The Android Open Source Project
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

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.annotation.UiThreadTest;
import android.support.test.filters.FlakyTest;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.View;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

import com.android.systemui.SysuiTestCase;

@SmallTest
@RunWith(AndroidJUnit4.class)
@FlakyTest
public class NotificationContentViewTest extends SysuiTestCase {

    NotificationContentView mView;

    @Before
    @UiThreadTest
    public void setup() {
        mView = new NotificationContentView(mContext, null);
        ExpandableNotificationRow row = new ExpandableNotificationRow(mContext, null);
        ExpandableNotificationRow mockRow = spy(row);
        doNothing().when(mockRow).updateBackgroundAlpha(anyFloat());
        doReturn(10).when(mockRow).getIntrinsicHeight();

        mView.setContainingNotification(mockRow);
        mView.setHeights(10, 20, 30, 40);

        mView.setContractedChild(createViewWithHeight(10));
        mView.setExpandedChild(createViewWithHeight(20));
        mView.setHeadsUpChild(createViewWithHeight(30));
        mView.setAmbientChild(createViewWithHeight(40));

        mView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        mView.layout(0, 0, mView.getMeasuredWidth(), mView.getMeasuredHeight());
    }

    private View createViewWithHeight(int height) {
        View view = new View(mContext, null);
        view.setMinimumHeight(height);
        return view;
    }

    @Test
    @UiThreadTest
    public void animationStartType_getsClearedAfterUpdatingVisibilitiesWithoutAnimation() {
        mView.setHeadsUp(true);
        mView.setDark(true, false, 0);
        mView.setDark(false, true, 0);
        mView.setHeadsUpAnimatingAway(true);
        Assert.assertFalse(mView.isAnimatingVisibleType());
    }
}
