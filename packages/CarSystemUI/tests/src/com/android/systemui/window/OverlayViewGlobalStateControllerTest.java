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

package com.android.systemui.window;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.navigationbar.car.CarNavigationBarController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class OverlayViewGlobalStateControllerTest extends SysuiTestCase {
    private static final String MOCK_OVERLAY_VIEW_CONTROLLER_NAME = "OverlayViewController";

    private OverlayViewGlobalStateController mOverlayViewGlobalStateController;
    private ViewGroup mBaseLayout;

    @Mock
    private CarNavigationBarController mCarNavigationBarController;
    @Mock
    private SystemUIOverlayWindowController mSystemUIOverlayWindowController;
    @Mock
    private OverlayViewMediator mOverlayViewMediator;
    @Mock
    private OverlayViewController mOverlayViewController;
    @Mock
    private Runnable mRunnable;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass= */ this);

        mOverlayViewGlobalStateController = new OverlayViewGlobalStateController(
                mCarNavigationBarController, mSystemUIOverlayWindowController);

        verify(mSystemUIOverlayWindowController).attach();

        mBaseLayout = new FrameLayout(mContext);

        when(mSystemUIOverlayWindowController.getBaseLayout()).thenReturn(mBaseLayout);
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
    public void showView_nothingAlreadyShown_navigationBarsHidden() {
        mOverlayViewGlobalStateController.showView(mOverlayViewController, mRunnable);

        verify(mCarNavigationBarController).hideBars();
    }

    @Test
    public void showView_nothingAlreadyShown_windowIsExpanded() {
        mOverlayViewGlobalStateController.showView(mOverlayViewController, mRunnable);

        verify(mSystemUIOverlayWindowController).setWindowVisible(true);
    }

    @Test
    public void showView_somethingAlreadyShown_navigationBarsHidden() {
        mOverlayViewGlobalStateController.mShownSet.add(MOCK_OVERLAY_VIEW_CONTROLLER_NAME);

        mOverlayViewGlobalStateController.showView(mOverlayViewController, mRunnable);

        verify(mCarNavigationBarController, never()).hideBars();
    }

    @Test
    public void showView_somethingAlreadyShown_windowIsExpanded() {
        mOverlayViewGlobalStateController.mShownSet.add(MOCK_OVERLAY_VIEW_CONTROLLER_NAME);

        mOverlayViewGlobalStateController.showView(mOverlayViewController, mRunnable);

        verify(mSystemUIOverlayWindowController, never()).setWindowVisible(true);
    }

    @Test
    public void showView_viewControllerNotInflated_inflateViewController() {
        when(mOverlayViewController.isInflated()).thenReturn(false);

        mOverlayViewGlobalStateController.showView(mOverlayViewController, mRunnable);

        verify(mOverlayViewController).inflate(mBaseLayout);
    }

    @Test
    public void showView_viewControllerInflated_inflateViewControllerNotCalled() {
        when(mOverlayViewController.isInflated()).thenReturn(true);

        mOverlayViewGlobalStateController.showView(mOverlayViewController, mRunnable);

        verify(mOverlayViewController, never()).inflate(mBaseLayout);
    }

    @Test
    public void showView_showRunnableCalled() {
        mOverlayViewGlobalStateController.showView(mOverlayViewController, mRunnable);

        verify(mRunnable).run();
    }

    @Test
    public void showView_overlayViewControllerAddedToShownSet() {
        mOverlayViewGlobalStateController.showView(mOverlayViewController, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mShownSet.contains(
                mOverlayViewController.getClass().getName())).isTrue();
    }

    @Test
    public void hideView_viewControllerNotInflated_hideRunnableNotCalled() {
        when(mOverlayViewController.isInflated()).thenReturn(false);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController, mRunnable);

        verify(mRunnable, never()).run();
    }

    @Test
    public void hideView_nothingShown_hideRunnableNotCalled() {
        when(mOverlayViewController.isInflated()).thenReturn(true);
        mOverlayViewGlobalStateController.mShownSet.clear();

        mOverlayViewGlobalStateController.hideView(mOverlayViewController, mRunnable);

        verify(mRunnable, never()).run();
    }

    @Test
    public void hideView_viewControllerNotShown_hideRunnableNotCalled() {
        when(mOverlayViewController.isInflated()).thenReturn(true);
        mOverlayViewGlobalStateController.mShownSet.add(MOCK_OVERLAY_VIEW_CONTROLLER_NAME);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController, mRunnable);

        verify(mRunnable, never()).run();
    }

    @Test
    public void hideView_viewControllerShown_hideRunnableCalled() {
        when(mOverlayViewController.isInflated()).thenReturn(true);
        mOverlayViewGlobalStateController.mShownSet.add(
                mOverlayViewController.getClass().getName());

        mOverlayViewGlobalStateController.hideView(mOverlayViewController, mRunnable);

        verify(mRunnable).run();
    }

    @Test
    public void hideView_viewControllerOnlyShown_nothingShown() {
        when(mOverlayViewController.isInflated()).thenReturn(true);
        mOverlayViewGlobalStateController.mShownSet.add(
                mOverlayViewController.getClass().getName());

        mOverlayViewGlobalStateController.hideView(mOverlayViewController, mRunnable);

        assertThat(mOverlayViewGlobalStateController.mShownSet.isEmpty()).isTrue();
    }

    @Test
    public void hideView_viewControllerNotOnlyShown_navigationBarNotShown() {
        when(mOverlayViewController.isInflated()).thenReturn(true);
        mOverlayViewGlobalStateController.mShownSet.add(
                mOverlayViewController.getClass().getName());
        mOverlayViewGlobalStateController.mShownSet.add(MOCK_OVERLAY_VIEW_CONTROLLER_NAME);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController, mRunnable);

        verify(mCarNavigationBarController, never()).showBars();
    }

    @Test
    public void hideView_viewControllerNotOnlyShown_windowNotCollapsed() {
        when(mOverlayViewController.isInflated()).thenReturn(true);
        mOverlayViewGlobalStateController.mShownSet.add(
                mOverlayViewController.getClass().getName());
        mOverlayViewGlobalStateController.mShownSet.add(MOCK_OVERLAY_VIEW_CONTROLLER_NAME);

        mOverlayViewGlobalStateController.hideView(mOverlayViewController, mRunnable);

        verify(mSystemUIOverlayWindowController, never()).setWindowVisible(false);
    }

    @Test
    public void hideView_viewControllerOnlyShown_navigationBarShown() {
        when(mOverlayViewController.isInflated()).thenReturn(true);
        mOverlayViewGlobalStateController.mShownSet.add(
                mOverlayViewController.getClass().getName());

        mOverlayViewGlobalStateController.hideView(mOverlayViewController, mRunnable);

        verify(mCarNavigationBarController).showBars();
    }

    @Test
    public void hideView_viewControllerOnlyShown_windowCollapsed() {
        when(mOverlayViewController.isInflated()).thenReturn(true);
        mOverlayViewGlobalStateController.mShownSet.add(
                mOverlayViewController.getClass().getName());

        mOverlayViewGlobalStateController.hideView(mOverlayViewController, mRunnable);

        verify(mSystemUIOverlayWindowController).setWindowVisible(false);
    }

    @Test
    public void inflateView_notInflated_inflates() {
        when(mOverlayViewController.isInflated()).thenReturn(false);

        mOverlayViewGlobalStateController.inflateView(mOverlayViewController);

        verify(mOverlayViewController).inflate(mBaseLayout);
    }

    @Test
    public void inflateView_alreadyInflated_doesNotInflate() {
        when(mOverlayViewController.isInflated()).thenReturn(true);

        mOverlayViewGlobalStateController.inflateView(mOverlayViewController);

        verify(mOverlayViewController, never()).inflate(mBaseLayout);
    }
}
