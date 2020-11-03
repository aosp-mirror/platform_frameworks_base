/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.window;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.animation.Animator;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Rect;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarDeviceProvisionedController;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.statusbar.FlingAnimationUtils;
import com.android.systemui.tests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class OverlayPanelViewControllerTest extends SysuiTestCase {
    private TestOverlayPanelViewController mOverlayPanelViewController;
    private ViewGroup mBaseLayout;

    @Mock
    private OverlayViewGlobalStateController mOverlayViewGlobalStateController;
    @Mock
    private FlingAnimationUtils.Builder mFlingAnimationUtilsBuilder;
    @Mock
    private FlingAnimationUtils mFlingAnimationUtils;
    @Mock
    private CarDeviceProvisionedController mCarDeviceProvisionedController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mBaseLayout = (ViewGroup) LayoutInflater.from(mContext).inflate(
                R.layout.overlay_view_controller_test, /* root= */ null);

        when(mFlingAnimationUtilsBuilder.setMaxLengthSeconds(anyFloat())).thenReturn(
                mFlingAnimationUtilsBuilder);
        when(mFlingAnimationUtilsBuilder.setSpeedUpFactor(anyFloat())).thenReturn(
                mFlingAnimationUtilsBuilder);
        when(mFlingAnimationUtilsBuilder.build()).thenReturn(mFlingAnimationUtils);
        mOverlayPanelViewController = new TestOverlayPanelViewController(
                getContext(),
                getContext().getOrCreateTestableResources().getResources(),
                R.id.overlay_view_controller_stub,
                mOverlayViewGlobalStateController,
                mFlingAnimationUtilsBuilder,
                mCarDeviceProvisionedController);
    }

    @Test
    public void toggle_notInflated_inflates() {
        assertThat(mOverlayPanelViewController.isInflated()).isFalse();

        mOverlayPanelViewController.toggle();

        verify(mOverlayViewGlobalStateController).inflateView(mOverlayPanelViewController);
    }

    @Test
    public void toggle_inflated_doesNotInflate() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        assertThat(mOverlayPanelViewController.isInflated()).isTrue();

        mOverlayPanelViewController.toggle();

        verify(mOverlayViewGlobalStateController, never()).inflateView(mOverlayPanelViewController);
    }

    @Test
    public void toggle_notExpanded_panelExpands() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setPanelExpanded(false);

        mOverlayPanelViewController.toggle();

        assertThat(mOverlayPanelViewController.mAnimateExpandPanelCalled).isTrue();
    }

    @Test
    public void toggle_expanded_panelCollapses() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setPanelExpanded(true);

        mOverlayPanelViewController.toggle();

        assertThat(mOverlayPanelViewController.mAnimateCollapsePanelCalled).isTrue();
    }

    @Test
    public void animateCollapsePanel_shouldNotAnimateCollapsePanel_doesNotCollapse() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setShouldAnimateCollapsePanel(false);

        mOverlayPanelViewController.animateCollapsePanel();

        assertThat(mOverlayPanelViewController.mAnimateCollapsePanelCalled).isTrue();
        assertThat(mOverlayPanelViewController.mOnAnimateCollapsePanelCalled).isFalse();
    }

    @Test
    public void animateCollapsePanel_isNotExpanded_doesNotCollapse() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setShouldAnimateCollapsePanel(true);
        mOverlayPanelViewController.setPanelExpanded(false);

        mOverlayPanelViewController.animateCollapsePanel();

        assertThat(mOverlayPanelViewController.mAnimateCollapsePanelCalled).isTrue();
        assertThat(mOverlayPanelViewController.mOnAnimateCollapsePanelCalled).isFalse();
    }

    @Test
    public void animateCollapsePanel_isNotVisible_doesNotCollapse() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setShouldAnimateCollapsePanel(true);
        mOverlayPanelViewController.setPanelExpanded(true);
        mOverlayPanelViewController.setPanelVisible(false);

        mOverlayPanelViewController.animateCollapsePanel();

        assertThat(mOverlayPanelViewController.mAnimateCollapsePanelCalled).isTrue();
        assertThat(mOverlayPanelViewController.mOnAnimateCollapsePanelCalled).isFalse();
    }

    @Test
    public void animateCollapsePanel_collapses() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setShouldAnimateCollapsePanel(true);
        mOverlayPanelViewController.setPanelExpanded(true);
        mOverlayPanelViewController.setPanelVisible(true);

        mOverlayPanelViewController.animateCollapsePanel();

        assertThat(mOverlayPanelViewController.mOnAnimateCollapsePanelCalled).isTrue();
    }

    @Test
    public void animateCollapsePanel_withOverlayFromTopBar_collapsesTowardsTopBar() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        // Mock a panel that has layout size 50 and where the panel is opened.
        int size = 50;
        mockPanelWithSize(size);
        mOverlayPanelViewController.getLayout().setClipBounds(
                new Rect(0, 0, size, size));
        mOverlayPanelViewController.setShouldAnimateCollapsePanel(true);
        mOverlayPanelViewController.setPanelExpanded(true);
        mOverlayPanelViewController.setPanelVisible(true);
        mOverlayPanelViewController.setOverlayDirection(
                OverlayPanelViewController.OVERLAY_FROM_TOP_BAR);

        mOverlayPanelViewController.animateCollapsePanel();

        ArgumentCaptor<Float> endValueCaptor = ArgumentCaptor.forClass(Float.class);
        verify(mFlingAnimationUtils).apply(
                any(Animator.class), anyFloat(), endValueCaptor.capture(), anyFloat());
        assertThat(endValueCaptor.getValue().intValue()).isEqualTo(0);
    }

    @Test
    public void animateCollapsePanel_withOverlayFromBottomBar_collapsesTowardsBottomBar() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        // Mock a panel that has layout size 50 and where the panel is opened.
        int size = 50;
        mockPanelWithSize(size);
        mOverlayPanelViewController.getLayout().setClipBounds(
                new Rect(0, 0, size, size));
        mOverlayPanelViewController.setShouldAnimateCollapsePanel(true);
        mOverlayPanelViewController.setPanelExpanded(true);
        mOverlayPanelViewController.setPanelVisible(true);
        mOverlayPanelViewController.setOverlayDirection(
                OverlayPanelViewController.OVERLAY_FROM_BOTTOM_BAR);

        mOverlayPanelViewController.animateCollapsePanel();

        ArgumentCaptor<Float> endValueCaptor = ArgumentCaptor.forClass(Float.class);
        verify(mFlingAnimationUtils).apply(
                any(Animator.class), anyFloat(), endValueCaptor.capture(), anyFloat());
        assertThat(endValueCaptor.getValue().intValue()).isEqualTo(
                mOverlayPanelViewController.getLayout().getHeight());
    }

    @Test
    public void animateExpandPanel_shouldNotAnimateExpandPanel_doesNotExpand() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setShouldAnimateExpandPanel(false);

        mOverlayPanelViewController.animateExpandPanel();

        assertThat(mOverlayPanelViewController.mAnimateExpandPanelCalled).isTrue();
        assertThat(mOverlayPanelViewController.mOnAnimateExpandPanelCalled).isFalse();
    }

    @Test
    public void animateExpandPanel_userNotSetup_doesNotExpand() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setShouldAnimateExpandPanel(true);
        when(mCarDeviceProvisionedController.isCurrentUserFullySetup()).thenReturn(false);

        mOverlayPanelViewController.animateExpandPanel();

        assertThat(mOverlayPanelViewController.mAnimateExpandPanelCalled).isTrue();
        assertThat(mOverlayPanelViewController.mOnAnimateExpandPanelCalled).isFalse();
    }

    @Test
    public void animateExpandPanel_expands() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setShouldAnimateExpandPanel(true);
        when(mCarDeviceProvisionedController.isCurrentUserFullySetup()).thenReturn(true);

        mOverlayPanelViewController.animateExpandPanel();

        assertThat(mOverlayPanelViewController.mOnAnimateExpandPanelCalled).isTrue();
    }

    @Test
    public void animateExpandPanel_withOverlayFromTopBar_expandsToBottom() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        // Mock a panel that has layout size 50 and where the panel is not opened.
        int size = 50;
        mockPanelWithSize(size);
        mOverlayPanelViewController.getLayout().setClipBounds(
                new Rect(0, 0, size, 0));
        mOverlayPanelViewController.setShouldAnimateExpandPanel(true);
        when(mCarDeviceProvisionedController.isCurrentUserFullySetup()).thenReturn(true);
        mOverlayPanelViewController.setOverlayDirection(
                OverlayPanelViewController.OVERLAY_FROM_TOP_BAR);

        mOverlayPanelViewController.animateExpandPanel();

        ArgumentCaptor<Float> endValueCaptor = ArgumentCaptor.forClass(Float.class);
        verify(mFlingAnimationUtils).apply(
                any(Animator.class), anyFloat(), endValueCaptor.capture(), anyFloat());
        assertThat(endValueCaptor.getValue().intValue()).isEqualTo(
                mOverlayPanelViewController.getLayout().getHeight());
    }

    @Test
    public void animateExpandPanel_withOverlayFromBottomBar_expandsToTop() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        // Mock a panel that has layout size 50 and where the panel is not opened.
        int size = 50;
        mockPanelWithSize(size);
        mOverlayPanelViewController.getLayout().setClipBounds(
                new Rect(0, size, size, size));
        mOverlayPanelViewController.setShouldAnimateExpandPanel(true);
        when(mCarDeviceProvisionedController.isCurrentUserFullySetup()).thenReturn(true);
        mOverlayPanelViewController.setOverlayDirection(
                OverlayPanelViewController.OVERLAY_FROM_BOTTOM_BAR);

        mOverlayPanelViewController.animateExpandPanel();

        ArgumentCaptor<Float> endValueCaptor = ArgumentCaptor.forClass(Float.class);
        verify(mFlingAnimationUtils).apply(
                any(Animator.class), anyFloat(), endValueCaptor.capture(), anyFloat());
        assertThat(endValueCaptor.getValue().intValue()).isEqualTo(0);
    }

    @Test
    public void animateExpandPanel_setsPanelVisible() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setShouldAnimateExpandPanel(true);
        when(mCarDeviceProvisionedController.isCurrentUserFullySetup()).thenReturn(true);

        mOverlayPanelViewController.animateExpandPanel();

        assertThat(mOverlayPanelViewController.isPanelVisible()).isTrue();
    }

    @Test
    public void animateExpandPanel_setsPanelExpanded() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.setShouldAnimateExpandPanel(true);
        when(mCarDeviceProvisionedController.isCurrentUserFullySetup()).thenReturn(true);

        mOverlayPanelViewController.animateExpandPanel();

        assertThat(mOverlayPanelViewController.isPanelExpanded()).isTrue();
    }

    @Test
    public void setPanelVisible_setTrue_windowNotVisible_setsWindowVisible() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        when(mOverlayViewGlobalStateController.isWindowVisible()).thenReturn(false);

        mOverlayPanelViewController.setPanelVisible(true);

        verify(mOverlayViewGlobalStateController).showView(mOverlayPanelViewController);
    }

    @Test
    public void setPanelVisible_setTrue_windowVisible_doesNotSetWindowVisible() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        when(mOverlayViewGlobalStateController.isWindowVisible()).thenReturn(true);

        mOverlayPanelViewController.setPanelVisible(true);

        verify(mOverlayViewGlobalStateController, never()).showView(mOverlayPanelViewController);
    }

    @Test
    public void setPanelVisible_setTrue_setLayoutVisible() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.getLayout().setVisibility(View.INVISIBLE);

        mOverlayPanelViewController.setPanelVisible(true);

        assertThat(mOverlayPanelViewController.getLayout().getVisibility()).isEqualTo(View.VISIBLE);
    }

    @Test
    public void setPanelVisible_setFalse_windowVisible_setsWindowNotVisible() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        when(mOverlayViewGlobalStateController.isWindowVisible()).thenReturn(true);

        mOverlayPanelViewController.setPanelVisible(false);

        verify(mOverlayViewGlobalStateController).hideView(mOverlayPanelViewController);
    }

    @Test
    public void setPanelVisible_setFalse_windowNotVisible_doesNotSetWindowNotVisible() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        when(mOverlayViewGlobalStateController.isWindowVisible()).thenReturn(false);

        mOverlayPanelViewController.setPanelVisible(false);

        verify(mOverlayViewGlobalStateController, never()).hideView(mOverlayPanelViewController);
    }

    @Test
    public void setPanelVisible_setFalse_setLayoutInvisible() {
        mOverlayPanelViewController.inflate(mBaseLayout);
        mOverlayPanelViewController.getLayout().setVisibility(View.VISIBLE);

        mOverlayPanelViewController.setPanelVisible(false);

        assertThat(mOverlayPanelViewController.getLayout().getVisibility()).isEqualTo(
                View.INVISIBLE);
    }

    @Test
    public void dragOpenTouchListener_isNotInflated_inflatesView() {
        when(mCarDeviceProvisionedController.isCurrentUserFullySetup()).thenReturn(true);
        assertThat(mOverlayPanelViewController.isInflated()).isFalse();

        mOverlayPanelViewController.getDragOpenTouchListener().onTouch(/* v= */ null,
                MotionEvent.obtain(/* downTime= */ 200, /* eventTime= */ 300,
                        MotionEvent.ACTION_MOVE, /* x= */ 0, /* y= */ 0, /* metaState= */ 0));

        verify(mOverlayViewGlobalStateController).inflateView(mOverlayPanelViewController);
    }

    private void mockPanelWithSize(int size) {
        mOverlayPanelViewController.getLayout().setLeftTopRightBottom(0, 0, size, size);
    }

    private static class TestOverlayPanelViewController extends OverlayPanelViewController {

        boolean mOnAnimateCollapsePanelCalled;
        boolean mAnimateCollapsePanelCalled;
        boolean mOnAnimateExpandPanelCalled;
        boolean mAnimateExpandPanelCalled;
        boolean mOnCollapseAnimationEndCalled;
        boolean mOnExpandAnimationEndCalled;
        boolean mOnOpenScrollStartEnd;
        List<Integer> mOnScrollHeights;
        private boolean mShouldAnimateCollapsePanel;
        private boolean mShouldAnimateExpandPanel;
        private boolean mShouldAllowClosingScroll;

        TestOverlayPanelViewController(
                Context context,
                Resources resources,
                int stubId,
                OverlayViewGlobalStateController overlayViewGlobalStateController,
                FlingAnimationUtils.Builder flingAnimationUtilsBuilder,
                CarDeviceProvisionedController carDeviceProvisionedController) {
            super(context, resources, stubId, overlayViewGlobalStateController,
                    flingAnimationUtilsBuilder,
                    carDeviceProvisionedController);

            mOnScrollHeights = new ArrayList<>();
        }

        public void setShouldAnimateCollapsePanel(boolean shouldAnimate) {
            mShouldAnimateCollapsePanel = shouldAnimate;
        }

        @Override
        protected boolean shouldAnimateCollapsePanel() {
            return mShouldAnimateCollapsePanel;
        }

        @Override
        protected void animateCollapsePanel() {
            super.animateCollapsePanel();
            mAnimateCollapsePanelCalled = true;
        }

        @Override
        protected void onAnimateCollapsePanel() {
            mOnAnimateCollapsePanelCalled = true;
        }

        public void setShouldAnimateExpandPanel(boolean shouldAnimate) {
            mShouldAnimateExpandPanel = shouldAnimate;
        }

        @Override
        protected boolean shouldAnimateExpandPanel() {
            return mShouldAnimateExpandPanel;
        }

        @Override
        protected void animateExpandPanel() {
            super.animateExpandPanel();
            mAnimateExpandPanelCalled = true;
        }

        @Override
        protected void onAnimateExpandPanel() {
            mOnAnimateExpandPanelCalled = true;
        }

        @Override
        protected void onCollapseAnimationEnd() {
            mOnCollapseAnimationEndCalled = true;
        }

        @Override
        protected void onExpandAnimationEnd() {
            mOnExpandAnimationEndCalled = true;
        }

        @Override
        protected void onScroll(int height) {
            mOnScrollHeights.add(height);
        }

        @Override
        protected void onOpenScrollStart() {
            mOnOpenScrollStartEnd = true;
        }

        public void setShouldAllowClosingScroll(boolean shouldAllow) {
            mShouldAllowClosingScroll = shouldAllow;
        }

        @Override
        protected boolean shouldAllowClosingScroll() {
            return mShouldAllowClosingScroll;
        }
    }
}
