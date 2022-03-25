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

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.view.NotificationHeaderView;
import android.view.View;
import android.view.ViewPropertyAnimator;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.R;
import com.android.internal.widget.NotificationExpandButton;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.media.dialog.MediaOutputDialogFactory;
import com.android.systemui.statusbar.notification.FeedbackIcon;

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
        mDependency.injectMockDependency(MediaOutputDialogFactory.class);

        mView = new NotificationContentView(mContext, null);
        ExpandableNotificationRow row = new ExpandableNotificationRow(mContext, null);
        ExpandableNotificationRow mockRow = spy(row);
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
    public void testSetFeedbackIcon() {
        View mockContracted = mock(NotificationHeaderView.class);
        when(mockContracted.findViewById(com.android.internal.R.id.feedback))
                .thenReturn(mockContracted);
        when(mockContracted.getContext()).thenReturn(mContext);
        View mockExpanded = mock(NotificationHeaderView.class);
        when(mockExpanded.findViewById(com.android.internal.R.id.feedback))
                .thenReturn(mockExpanded);
        when(mockExpanded.getContext()).thenReturn(mContext);
        View mockHeadsUp = mock(NotificationHeaderView.class);
        when(mockHeadsUp.findViewById(com.android.internal.R.id.feedback))
                .thenReturn(mockHeadsUp);
        when(mockHeadsUp.getContext()).thenReturn(mContext);

        mView.setContractedChild(mockContracted);
        mView.setExpandedChild(mockExpanded);
        mView.setHeadsUpChild(mockHeadsUp);

        mView.setFeedbackIcon(new FeedbackIcon(R.drawable.ic_feedback_alerted,
                R.string.notification_feedback_indicator_alerted));

        verify(mockContracted, times(1)).setVisibility(View.VISIBLE);
        verify(mockExpanded, times(1)).setVisibility(View.VISIBLE);
        verify(mockHeadsUp, times(1)).setVisibility(View.VISIBLE);
    }

    @Test
    @UiThreadTest
    public void testExpandButtonFocusIsCalled() {
        View mockContractedEB = mock(NotificationExpandButton.class);
        View mockContracted = mock(NotificationHeaderView.class);
        when(mockContracted.animate()).thenReturn(mock(ViewPropertyAnimator.class));
        when(mockContracted.findViewById(com.android.internal.R.id.expand_button)).thenReturn(
                mockContractedEB);
        when(mockContracted.getContext()).thenReturn(mContext);

        View mockExpandedEB = mock(NotificationExpandButton.class);
        View mockExpanded = mock(NotificationHeaderView.class);
        when(mockExpanded.animate()).thenReturn(mock(ViewPropertyAnimator.class));
        when(mockExpanded.findViewById(com.android.internal.R.id.expand_button)).thenReturn(
                mockExpandedEB);
        when(mockExpanded.getContext()).thenReturn(mContext);

        View mockHeadsUpEB = mock(NotificationExpandButton.class);
        View mockHeadsUp = mock(NotificationHeaderView.class);
        when(mockHeadsUp.animate()).thenReturn(mock(ViewPropertyAnimator.class));
        when(mockHeadsUp.findViewById(com.android.internal.R.id.expand_button)).thenReturn(
                mockHeadsUpEB);
        when(mockHeadsUp.getContext()).thenReturn(mContext);

        // Set up all 3 child forms
        mView.setContractedChild(mockContracted);
        mView.setExpandedChild(mockExpanded);
        mView.setHeadsUpChild(mockHeadsUp);

        // This is required to call requestAccessibilityFocus()
        mView.setFocusOnVisibilityChange();

        // The following will initialize the view and switch from not visible to expanded.
        // (heads-up is actually an alternate form of contracted, hence this enters expanded state)
        mView.setHeadsUp(true);

        verify(mockContractedEB, times(0)).requestAccessibilityFocus();
        verify(mockExpandedEB, times(1)).requestAccessibilityFocus();
        verify(mockHeadsUpEB, times(0)).requestAccessibilityFocus();
    }
}
