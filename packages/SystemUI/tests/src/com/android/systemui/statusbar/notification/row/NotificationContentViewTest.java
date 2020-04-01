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

package com.android.systemui.statusbar.notification.row;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.util.ArraySet;
import android.view.NotificationHeaderView;
import android.view.View;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
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
        mView.setHeights(10, 20, 30);

        mView.setContractedChild(createViewWithHeight(10));
        mView.setExpandedChild(createViewWithHeight(20));
        mView.setHeadsUpChild(createViewWithHeight(30));

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
    public void testShowAppOpsIcons() {
        View mockContracted = mock(View.class);
        when(mockContracted.findViewById(com.android.internal.R.id.mic))
                .thenReturn(mockContracted);
        View mockExpanded = mock(View.class);
        when(mockExpanded.findViewById(com.android.internal.R.id.mic))
                .thenReturn(mockExpanded);
        View mockHeadsUp = mock(View.class);
        when(mockHeadsUp.findViewById(com.android.internal.R.id.mic))
                .thenReturn(mockHeadsUp);

        mView.setContractedChild(mockContracted);
        mView.setExpandedChild(mockExpanded);
        mView.setHeadsUpChild(mockHeadsUp);

        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(AppOpsManager.OP_RECORD_AUDIO);
        mView.showAppOpsIcons(ops);

        verify(mockContracted, times(1)).setVisibility(View.VISIBLE);
        verify(mockExpanded, times(1)).setVisibility(View.VISIBLE);
        verify(mockHeadsUp, times(1)).setVisibility(View.VISIBLE);
    }
}
