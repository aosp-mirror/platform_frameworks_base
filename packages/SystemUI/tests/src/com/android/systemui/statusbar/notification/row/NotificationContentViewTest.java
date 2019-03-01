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

import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.graphics.drawable.Icon;
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

    private Icon mActionIcon;

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
        assertFalse(mView.isAnimatingVisibleType());
    }

    @Test
    @UiThreadTest
    public void testShowAppOpsIcons() {
        NotificationHeaderView mockContracted = mock(NotificationHeaderView.class);
        when(mockContracted.findViewById(com.android.internal.R.id.notification_header))
                .thenReturn(mockContracted);
        NotificationHeaderView mockExpanded = mock(NotificationHeaderView.class);
        when(mockExpanded.findViewById(com.android.internal.R.id.notification_header))
                .thenReturn(mockExpanded);
        NotificationHeaderView mockHeadsUp = mock(NotificationHeaderView.class);
        when(mockHeadsUp.findViewById(com.android.internal.R.id.notification_header))
                .thenReturn(mockHeadsUp);
        NotificationHeaderView mockAmbient = mock(NotificationHeaderView.class);
        when(mockAmbient.findViewById(com.android.internal.R.id.notification_header))
                .thenReturn(mockAmbient);

        mView.setContractedChild(mockContracted);
        mView.setExpandedChild(mockExpanded);
        mView.setHeadsUpChild(mockHeadsUp);
        mView.setAmbientChild(mockAmbient);

        ArraySet<Integer> ops = new ArraySet<>();
        ops.add(AppOpsManager.OP_ANSWER_PHONE_CALLS);
        mView.showAppOpsIcons(ops);

        verify(mockContracted, times(1)).showAppOpsIcons(ops);
        verify(mockExpanded, times(1)).showAppOpsIcons(ops);
        verify(mockAmbient, never()).showAppOpsIcons(ops);
        verify(mockHeadsUp, times(1)).showAppOpsIcons(any());
    }
}
