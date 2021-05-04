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

import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.view.WindowInsetsController;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.tests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class OverlayViewGlobalStateControllerTest extends SysuiTestCase {
    private static final int OVERLAY_VIEW_CONTROLLER_1_Z_ORDER = 0;
    private static final int OVERLAY_VIEW_CONTROLLER_2_Z_ORDER = 1;
    private static final int OVERLAY_PANEL_VIEW_CONTROLLER_Z_ORDER = 2;

    private OverlayViewGlobalStateController mOverlayViewGlobalStateController;
    private ViewGroup mBaseLayout;

    @Mock
    private SystemUIOverlayWindowController mSystemUIOverlayWindowController;
    @Mock
    private OverlayViewMediator mOverlayViewMediator;
    @Mock
    private OverlayViewController mOverlayViewController1;
    @Mock
    private OverlayViewController mOverlayViewController2;
    @Mock
    private OverlayPanelViewController mOverlayPanelViewController;
    @Mock
    private Runnable mRunnable;
    @Mock
    private WindowInsetsController mWindowInsetsController;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass= */ this);

        mBaseLayout = spy((ViewGroup) LayoutInflater.from(mContext).inflate(
                R.layout.overlay_view_global_state_controller_test, /* root= */ null));

        when(mBaseLayout.getWindowInsetsController()).thenReturn(mWindowInsetsController);

        when(mSystemUIOverlayWindowController.getBaseLayout()).thenReturn(mBaseLayout);

        mOverlayViewGlobalStateController = new OverlayViewGlobalStateController(
                mSystemUIOverlayWindowController);

        verify(mSystemUIOverlayWindowController).attach();
    }

    @Test
    public void registerMediator_overlayViewMediatorListenersRegistered() {
        mOverlayViewGlobalStateController.registerMediator(mOverlayViewMediator);

        verify(mOverlayViewMediator).registerListeners();
    }

    @Test
    public void registerMediator_overlayViewMediatorViewControllerSetup() {
        mOverlayViewGlobalStateController.registerMediator(mOverlayViewMediator);

        verify(mOverlayViewMediator).setupOverlayContentViewControllers();
    }

    @Test
    public void showView_nothingVisible_windowNotFocusable_shouldShowNavBar_navBarsVisible() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(false);
        when(mOverlayViewController1.shouldShowNavigationBarInsets()).thenReturn(true);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).show(navigationBars());
    }

    @Test
    public void showView_nothingVisible_windowNotFocusable_shouldHideNavBar_notHidden() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(false);
        when(mOverlayViewController1.shouldShowNavigationBarInsets()).thenReturn(false);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController, never()).hide(navigationBars());
    }

    @Test
    public void showView_nothingVisible_windowNotFocusable_shouldShowStatusBar_statusBarsVisible() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(false);
        when(mOverlayViewController1.shouldShowStatusBarInsets()).thenReturn(true);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).show(statusBars());
    }

    @Test
    public void showView_nothingVisible_windowNotFocusable_shouldHideStatusBar_notHidden() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(false);
        when(mOverlayViewController1.shouldShowStatusBarInsets()).thenReturn(false);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController, never()).hide(statusBars());
    }

    @Test
    public void showView_nothingAlreadyShown_shouldShowNavBarFalse_navigationBarsHidden() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowNavigationBarInsets()).thenReturn(false);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).hide(navigationBars());
    }

    @Test
    public void showView_nothingAlreadyShown_shouldShowNavBarTrue_navigationBarsShown() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowNavigationBarInsets()).thenReturn(true);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).show(navigationBars());
    }

    @Test
    public void showView_nothingAlreadyShown_shouldShowStatusBarFalse_statusBarsHidden() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowStatusBarInsets()).thenReturn(false);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).hide(statusBars());
    }

    @Test
    public void showView_nothingAlreadyShown_shouldShowStatusBarTrue_statusBarsShown() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowStatusBarInsets()).thenReturn(true);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).show(statusBars());
    }

    @Test
    public void showView_nothingAlreadyShown_fitsNavBarInsets_insetsAdjusted() {
        setupOverlayViewController1();
        when(mOverlayViewController1.getInsetTypesToFit()).thenReturn(navigationBars());

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mSystemUIOverlayWindowController).setFitInsetsTypes(navigationBars());
    }

    @Test
    public void showView_nothingAlreadyShown_windowIsSetVisible() {
        setupOverlayViewController1();

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mSystemUIOverlayWindowController).setWindowVisible(true);
    }

    @Test
    public void showView_nothingAlreadyShown_newHighestZOrder_isVisible() {
        setupOverlayViewController1();

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.containsKey(
                OVERLAY_VIEW_CONTROLLER_1_Z_ORDER)).isTrue();
    }

    @Test
    public void showView_nothingAlreadyShown_newHighestZOrder() {
        setupOverlayViewController1();

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mHighestZOrder).isEqualTo(
                mOverlayViewController1);
    }

    @Test
    public void showView_nothingAlreadyShown_descendantsFocusable() {
        setupOverlayViewController1();

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mOverlayViewController1).setAllowRotaryFocus(true);
    }

    @Test
    public void showView_newHighestZOrder() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mHighestZOrder).isEqualTo(
                mOverlayViewController2);
    }

    @Test
    public void showView_newHighestZOrder_shouldShowNavBarFalse_navigationBarsHidden() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        when(mOverlayViewController2.shouldShowNavigationBarInsets()).thenReturn(false);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        verify(mWindowInsetsController).hide(navigationBars());
    }

    @Test
    public void showView_newHighestZOrder_shouldShowNavBarTrue_navigationBarsShown() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        when(mOverlayViewController2.shouldShowNavigationBarInsets()).thenReturn(true);

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        verify(mWindowInsetsController).show(navigationBars());
    }

    @Test
    public void showView_newHighestZOrder_shouldShowStatusBarFalse_statusBarsHidden() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        when(mOverlayViewController2.shouldShowStatusBarInsets()).thenReturn(false);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        verify(mWindowInsetsController).hide(statusBars());
    }

    @Test
    public void showView_newHighestZOrder_shouldShowStatusBarTrue_statusBarsShown() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        when(mOverlayViewController2.shouldShowStatusBarInsets()).thenReturn(true);

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        verify(mWindowInsetsController).show(statusBars());
    }

    @Test
    public void showView_newHighestZOrder_fitsNavBarInsets_insetsAdjusted() {
        setupOverlayViewController1();
        when(mOverlayViewController1.getInsetTypesToFit()).thenReturn(statusBars());
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();
        when(mOverlayViewController2.getInsetTypesToFit()).thenReturn(navigationBars());
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        verify(mSystemUIOverlayWindowController).setFitInsetsTypes(navigationBars());
    }

    @Test
    public void showView_newHighestZOrder_correctViewsShown() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.keySet().toArray())
                .isEqualTo(Arrays.asList(OVERLAY_VIEW_CONTROLLER_1_Z_ORDER,
                        OVERLAY_VIEW_CONTROLLER_2_Z_ORDER).toArray());
    }

    @Test
    public void showView_newHighestZOrder_topDescendantsFocusable() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        verify(mOverlayViewController1).setAllowRotaryFocus(false);
        verify(mOverlayViewController2).setAllowRotaryFocus(true);
    }

    @Test
    public void showView_newHighestZOrder_refreshTopFocus() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        verify(mOverlayViewController1, never()).refreshRotaryFocusIfNeeded();
        verify(mOverlayViewController2).refreshRotaryFocusIfNeeded();
    }

    @Test
    public void showView_oldHighestZOrder() {
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mHighestZOrder).isEqualTo(
                mOverlayViewController2);
    }

    @Test
    public void showView_oldHighestZOrder_shouldShowNavBarFalse_navigationBarsHidden() {
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowNavigationBarInsets()).thenReturn(true);
        when(mOverlayViewController2.shouldShowNavigationBarInsets()).thenReturn(false);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).hide(navigationBars());
    }

    @Test
    public void showView_oldHighestZOrder_shouldShowNavBarTrue_navigationBarsShown() {
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowNavigationBarInsets()).thenReturn(false);
        when(mOverlayViewController2.shouldShowNavigationBarInsets()).thenReturn(true);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).show(navigationBars());
    }

    @Test
    public void showView_oldHighestZOrder_shouldShowStatusBarFalse_statusBarsHidden() {
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowStatusBarInsets()).thenReturn(true);
        when(mOverlayViewController2.shouldShowStatusBarInsets()).thenReturn(false);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).hide(statusBars());
    }

    @Test
    public void showView_oldHighestZOrder_shouldShowStatusBarTrue_statusBarsShown() {
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowStatusBarInsets()).thenReturn(false);
        when(mOverlayViewController2.shouldShowStatusBarInsets()).thenReturn(true);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).show(statusBars());
    }

    @Test
    public void showView_oldHighestZOrder_fitsNavBarInsets_insetsAdjusted() {
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.getInsetTypesToFit()).thenReturn(statusBars());
        when(mOverlayViewController2.getInsetTypesToFit()).thenReturn(navigationBars());

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mSystemUIOverlayWindowController).setFitInsetsTypes(navigationBars());
    }

    @Test
    public void showView_oldHighestZOrder_correctViewsShown() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.keySet().toArray())
                .isEqualTo(Arrays.asList(OVERLAY_VIEW_CONTROLLER_1_Z_ORDER,
                        OVERLAY_VIEW_CONTROLLER_2_Z_ORDER).toArray());
    }

    @Test
    public void showView_oldHighestZOrder_topDescendantsFocusable() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mOverlayViewController1).setAllowRotaryFocus(false);
        verify(mOverlayViewController2).setAllowRotaryFocus(true);
    }

    @Test
    public void showView_oldHighestZOrder_refreshTopFocus() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mOverlayViewController1, never()).refreshRotaryFocusIfNeeded();
        verify(mOverlayViewController2).refreshRotaryFocusIfNeeded();
    }

    @Test
    public void showView_somethingAlreadyShown_windowVisibleNotCalled() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        verify(mSystemUIOverlayWindowController, never()).setWindowVisible(true);
    }

    @Test
    public void showView_viewControllerNotInflated_inflateViewController() {
        setupOverlayViewController2();
        when(mOverlayViewController2.isInflated()).thenReturn(false);

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        verify(mOverlayViewController2).inflate(mBaseLayout);
    }

    @Test
    public void showView_viewControllerInflated_inflateViewControllerNotCalled() {
        setupOverlayViewController2();

        mOverlayViewGlobalStateController.showView(mOverlayViewController2, mRunnable);

        verify(mOverlayViewController2, never()).inflate(mBaseLayout);
    }

    @Test
    public void showView_panelViewController_inflateViewControllerNotCalled() {
        setupOverlayPanelViewController();

        mOverlayViewGlobalStateController.showView(mOverlayPanelViewController, mRunnable);

        verify(mOverlayPanelViewController, never()).inflate(mBaseLayout);
        verify(mOverlayPanelViewController, never()).isInflated();
    }

    @Test
    public void showView_showRunnableCalled() {
        setupOverlayViewController1();

        mOverlayViewGlobalStateController.showView(mOverlayViewController1, mRunnable);

        verify(mRunnable).run();
    }

    @Test
    public void hideView_viewControllerNotInflated_hideRunnableNotCalled() {
        when(mOverlayViewController2.isInflated()).thenReturn(false);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController2, mRunnable);

        verify(mRunnable, never()).run();
    }

    @Test
    public void hideView_nothingShown_hideRunnableNotCalled() {
        when(mOverlayViewController2.isInflated()).thenReturn(true);
        mOverlayViewGlobalStateController.mZOrderMap.clear();

        mOverlayViewGlobalStateController.hideView(mOverlayViewController2, mRunnable);

        verify(mRunnable, never()).run();
    }

    @Test
    public void hideView_viewControllerNotShown_hideRunnableNotCalled() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        when(mOverlayViewController2.isInflated()).thenReturn(true);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController2, mRunnable);

        verify(mRunnable, never()).run();
    }

    @Test
    public void hideView_viewControllerShown_hideRunnableCalled() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        verify(mRunnable).run();
    }

    @Test
    public void hideView_viewControllerOnlyShown_noHighestZOrder() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mHighestZOrder).isNull();
    }

    @Test
    public void hideView_viewControllerOnlyShown_nothingShown() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.isEmpty()).isTrue();
    }

    @Test
    public void hideView_viewControllerOnlyShown_viewControllerNotShown() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.containsKey(
                OVERLAY_VIEW_CONTROLLER_1_Z_ORDER)).isFalse();
    }

    @Test
    public void hideView_newHighestZOrder_twoViewsShown() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController2, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mHighestZOrder).isEqualTo(
                mOverlayViewController1);
    }

    @Test
    public void hideView_newHighestZOrder_threeViewsShown() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        setupOverlayPanelViewController();
        setOverlayViewControllerAsShowing(mOverlayPanelViewController);

        mOverlayViewGlobalStateController.hideView(mOverlayPanelViewController, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mHighestZOrder).isEqualTo(
                mOverlayViewController2);
    }

    @Test
    public void hideView_newHighestZOrder_shouldShowNavBarFalse_navigationBarHidden() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowNavigationBarInsets()).thenReturn(false);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController2, mRunnable);

        verify(mWindowInsetsController).hide(navigationBars());
    }

    @Test
    public void hideView_newHighestZOrder_shouldShowNavBarTrue_navigationBarShown() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowNavigationBarInsets()).thenReturn(true);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController2, mRunnable);

        verify(mWindowInsetsController).show(navigationBars());
    }

    @Test
    public void hideView_newHighestZOrder_shouldShowStatusBarFalse_statusBarHidden() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowStatusBarInsets()).thenReturn(false);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController2, mRunnable);

        verify(mWindowInsetsController).hide(statusBars());
    }

    @Test
    public void hideView_newHighestZOrder_shouldShowStatusBarTrue_statusBarShown() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController1.shouldShowStatusBarInsets()).thenReturn(true);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController2, mRunnable);

        verify(mWindowInsetsController).show(statusBars());
    }

    @Test
    public void hideView_newHighestZOrder_fitsNavBarInsets_insetsAdjusted() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.getInsetTypesToFit()).thenReturn(navigationBars());
        when(mOverlayViewController2.getInsetTypesToFit()).thenReturn(statusBars());

        mOverlayViewGlobalStateController.hideView(mOverlayViewController2, mRunnable);

        verify(mSystemUIOverlayWindowController).setFitInsetsTypes(navigationBars());
    }

    @Test
    public void hideView_oldHighestZOrder() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mHighestZOrder).isEqualTo(
                mOverlayViewController2);
    }

    @Test
    public void hideView_oldHighestZOrder_shouldShowNavBarFalse_navigationBarHidden() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldShowNavigationBarInsets()).thenReturn(false);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).hide(navigationBars());
    }

    @Test
    public void hideView_oldHighestZOrder_shouldShowNavBarTrue_navigationBarShown() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldShowNavigationBarInsets()).thenReturn(true);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).show(navigationBars());
    }

    @Test
    public void hideView_oldHighestZOrder_shouldShowStatusBarFalse_statusBarHidden() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldShowStatusBarInsets()).thenReturn(false);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).hide(statusBars());
    }

    @Test
    public void hideView_oldHighestZOrder_shouldShowStatusBarTrue_statusBarShown() {
        setupOverlayViewController1();
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldFocusWindow()).thenReturn(true);
        when(mOverlayViewController2.shouldShowStatusBarInsets()).thenReturn(true);
        reset(mWindowInsetsController);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).show(statusBars());
    }

    @Test
    public void hideView_oldHighestZOrder_fitsNavBarInsets_insetsAdjusted() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);
        when(mOverlayViewController1.getInsetTypesToFit()).thenReturn(statusBars());
        when(mOverlayViewController2.getInsetTypesToFit()).thenReturn(navigationBars());

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        verify(mSystemUIOverlayWindowController).setFitInsetsTypes(navigationBars());
    }

    @Test
    public void hideView_viewControllerNotOnlyShown_windowNotCollapsed() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        setupOverlayViewController2();
        setOverlayViewControllerAsShowing(mOverlayViewController2);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController2, mRunnable);

        verify(mSystemUIOverlayWindowController, never()).setWindowVisible(false);
    }

    @Test
    public void hideView_viewControllerOnlyShown_navigationBarShown() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).show(navigationBars());
    }

    @Test
    public void hideView_viewControllerOnlyShown_statusBarShown() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldFocusWindow()).thenReturn(true);
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        verify(mWindowInsetsController).show(statusBars());
    }

    @Test
    public void hideView_viewControllerOnlyShown_insetsAdjustedToDefault() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        verify(mSystemUIOverlayWindowController).setFitInsetsTypes(statusBars());
    }

    @Test
    public void hideView_viewControllerOnlyShown_windowCollapsed() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, mRunnable);

        verify(mSystemUIOverlayWindowController).setWindowVisible(false);
    }

    @Test
    public void setOccludedTrue_viewToHideWhenOccludedVisible_viewHidden() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        when(mOverlayViewController1.shouldShowWhenOccluded()).thenReturn(false);

        mOverlayViewGlobalStateController.setOccluded(true);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.containsValue(
                mOverlayViewController1)).isFalse();
    }

    @Test
    public void setOccludedTrue_viewToNotHideWhenOccludedVisible_viewShown() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        when(mOverlayViewController1.shouldShowWhenOccluded()).thenReturn(true);

        mOverlayViewGlobalStateController.setOccluded(true);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.containsValue(
                mOverlayViewController1)).isTrue();
    }

    @Test
    public void hideViewAndThenSetOccludedTrue_viewHiddenForOcclusion_viewHiddenAfterOcclusion() {
        setupOverlayViewController1();
        setOverlayViewControllerAsShowing(mOverlayViewController1);
        when(mOverlayViewController1.shouldShowWhenOccluded()).thenReturn(false);
        mOverlayViewGlobalStateController.setOccluded(true);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController1, /* runnable= */ null);
        mOverlayViewGlobalStateController.setOccluded(false);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.containsValue(
                mOverlayViewController1)).isFalse();
    }

    @Test
    public void setOccludedTrueAndThenShowView_viewToNotHideForOcclusion_viewShown() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldShowWhenOccluded()).thenReturn(true);

        mOverlayViewGlobalStateController.setOccluded(true);
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.containsValue(
                mOverlayViewController1)).isTrue();
    }

    @Test
    public void setOccludedTrueAndThenShowView_viewToHideForOcclusion_viewHidden() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldShowWhenOccluded()).thenReturn(false);

        mOverlayViewGlobalStateController.setOccluded(true);
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.containsValue(
                mOverlayViewController1)).isFalse();
    }

    @Test
    public void setOccludedFalse_viewShownAfterSetOccludedTrue_viewToHideForOcclusion_viewShown() {
        setupOverlayViewController1();
        when(mOverlayViewController1.shouldShowWhenOccluded()).thenReturn(false);
        mOverlayViewGlobalStateController.setOccluded(true);
        setOverlayViewControllerAsShowing(mOverlayViewController1);

        mOverlayViewGlobalStateController.setOccluded(false);

        assertThat(mOverlayViewGlobalStateController.mZOrderVisibleSortedMap.containsValue(
                mOverlayViewController1)).isTrue();
    }

    @Test
    public void inflateView_notInflated_inflates() {
        when(mOverlayViewController2.isInflated()).thenReturn(false);

        mOverlayViewGlobalStateController.inflateView(mOverlayViewController2);

        verify(mOverlayViewController2).inflate(mBaseLayout);
    }

    @Test
    public void inflateView_alreadyInflated_doesNotInflate() {
        when(mOverlayViewController2.isInflated()).thenReturn(true);

        mOverlayViewGlobalStateController.inflateView(mOverlayViewController2);

        verify(mOverlayViewController2, never()).inflate(mBaseLayout);
    }

    private void setupOverlayViewController1() {
        setupOverlayViewController(mOverlayViewController1, R.id.overlay_view_controller_stub_1,
                R.id.overlay_view_controller_1);
    }

    private void setupOverlayViewController2() {
        setupOverlayViewController(mOverlayViewController2, R.id.overlay_view_controller_stub_2,
                R.id.overlay_view_controller_2);
    }

    private void setupOverlayPanelViewController() {
        setupOverlayViewController(mOverlayPanelViewController, R.id.overlay_view_controller_stub_3,
                R.id.overlay_view_controller_3);
    }

    private void setupOverlayViewController(OverlayViewController overlayViewController,
            int stubId, int inflatedId) {
        ViewStub viewStub = mBaseLayout.findViewById(stubId);
        View layout;
        if (viewStub == null) {
            layout = mBaseLayout.findViewById(inflatedId);
        } else {
            layout = viewStub.inflate();
        }
        when(overlayViewController.getLayout()).thenReturn(layout);
        when(overlayViewController.isInflated()).thenReturn(true);
    }

    private void setOverlayViewControllerAsShowing(OverlayViewController overlayViewController) {
        mOverlayViewGlobalStateController.showView(overlayViewController, /* show= */ null);
        View layout = overlayViewController.getLayout();
        reset(mSystemUIOverlayWindowController);
        reset(overlayViewController);
        when(mSystemUIOverlayWindowController.getBaseLayout()).thenReturn(mBaseLayout);
        when(overlayViewController.getLayout()).thenReturn(layout);
        when(overlayViewController.isInflated()).thenReturn(true);
    }
}
