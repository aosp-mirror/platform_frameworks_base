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

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.accessibility.MotionEventHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Tests for {@link DockTooltipView}. */
@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class DockTooltipViewTest extends SysuiTestCase {

    @Mock
    private WindowManager mWindowManager;

    private AccessibilityFloatingMenuView mMenuView;
    private DockTooltipView mDockTooltipView;
    private final Position mPlaceholderPosition = new Position(0.0f, 0.0f);
    private final MotionEventHelper mMotionEventHelper = new MotionEventHelper();

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final WindowManager wm = mContext.getSystemService(WindowManager.class);
        doAnswer(invocation -> wm.getMaximumWindowMetrics()).when(
                mWindowManager).getMaximumWindowMetrics();
        mContext.addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);

        mMenuView = spy(new AccessibilityFloatingMenuView(mContext, mPlaceholderPosition));
        mDockTooltipView = new DockTooltipView(mContext, mMenuView);
    }

    @Test
    public void showTooltip_success() {
        mDockTooltipView.show();

        verify(mMenuView).startTranslateXAnimation();
        verify(mWindowManager).addView(eq(mDockTooltipView), any(WindowManager.LayoutParams.class));
    }

    @Test
    public void hideTooltip_success() {
        mDockTooltipView.show();
        mDockTooltipView.hide();

        verify(mMenuView).stopTranslateXAnimation();
        verify(mWindowManager).removeView(mDockTooltipView);
    }

    @Test
    public void touchOutsideWhenToolTipViewShown_stopAnimation() {
        final MotionEvent outsideEvent =
                mMotionEventHelper.obtainMotionEvent(/* downTime= */ 0,
                        /* eventTime= */ 1,
                        MotionEvent.ACTION_OUTSIDE,
                        /* x= */ 0,
                        /* y= */ 0);

        mDockTooltipView.show();
        mDockTooltipView.dispatchTouchEvent(outsideEvent);

        verify(mMenuView).stopTranslateXAnimation();
    }

    @After
    public void tearDown() {
        mMotionEventHelper.recycleEvents();
    }
}
