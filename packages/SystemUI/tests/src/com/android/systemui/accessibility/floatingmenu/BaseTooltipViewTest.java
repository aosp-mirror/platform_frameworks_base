/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.accessibility.floatingmenu;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.MotionEventHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link BaseTooltipView}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class BaseTooltipViewTest extends SysuiTestCase {

    @Mock
    private WindowManager mWindowManager;

    private AccessibilityFloatingMenuView mMenuView;
    private BaseTooltipView mToolTipView;

    private final Position mPlaceholderPosition = new Position(0.0f, 0.0f);
    private final MotionEventHelper mMotionEventHelper = new MotionEventHelper();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        doAnswer(invocation -> wm.getMaximumWindowMetrics()).when(
                mWindowManager).getMaximumWindowMetrics();
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);

        mMenuView = new AccessibilityFloatingMenuView(mContext, mPlaceholderPosition);
        mToolTipView = new BaseTooltipView(mContext, mMenuView);
    }

    @Test
    public void showToolTipView_success() {
        mToolTipView.show();

        verify(mWindowManager).addView(eq(mToolTipView), any(WindowManager.LayoutParams.class));
    }

    @Test
    public void touchOutsideWhenToolTipViewShown_dismiss() {
        final MotionEvent outsideEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0,
                        /* eventTime= */1,
                        MotionEvent.ACTION_OUTSIDE,
                        /* x= */ 0,
                        /* y= */ 0);

        mToolTipView.show();
        mToolTipView.dispatchTouchEvent(outsideEvent);

        verify(mWindowManager).removeView(mToolTipView);
    }

    @Test
    public void getAccessibilityActionList_matchResult() {
        final AccessibilityNodeInfo infos = new AccessibilityNodeInfo();
        mToolTipView.onInitializeAccessibilityNodeInfo(infos);

        assertThat(infos.getActionList().size()).isEqualTo(1);
    }

    @Test
    public void accessibilityAction_dismiss_success() {
        final BaseTooltipView tooltipView =
                spy(new BaseTooltipView(mContext, mMenuView));

        final boolean isActionPerformed =
                tooltipView.performAccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.getId(),
                        /* arguments= */ null);

        assertThat(isActionPerformed).isTrue();
        verify(tooltipView).hide();
    }

    @After
    public void tearDown() {
        mToolTipView.hide();
        mMotionEventHelper.recycleEvents();
    }
}
