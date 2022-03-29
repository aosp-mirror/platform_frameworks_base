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

package com.android.systemui.navigationbar;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;
import android.util.SparseArray;

import androidx.test.filters.SmallTest;

import com.android.systemui.Dependency;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.dump.DumpManager;
import com.android.systemui.model.SysUiState;
import com.android.systemui.recents.OverviewProxyService;
import com.android.systemui.statusbar.CommandQueue;
import com.android.systemui.statusbar.phone.AutoHideController;
import com.android.systemui.statusbar.phone.LightBarController;
import com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager;
import com.android.systemui.statusbar.policy.ConfigurationController;
import com.android.wm.shell.back.BackAnimation;
import com.android.wm.shell.pip.Pip;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Optional;

/** atest NavigationBarControllerTest */
@RunWith(AndroidTestingRunner.class)
@RunWithLooper
@SmallTest
public class NavigationBarControllerTest extends SysuiTestCase {

    private static final int SECONDARY_DISPLAY = 1;

    private NavigationBarController mNavigationBarController;
    private NavigationBar mDefaultNavBar;
    private NavigationBar mSecondaryNavBar;

    @Mock
    private CommandQueue mCommandQueue;
    @Mock
    private NavigationBarComponent.Factory mNavigationBarFactory;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mNavigationBarController = spy(
                new NavigationBarController(mContext,
                        mock(OverviewProxyService.class),
                        mock(NavigationModeController.class),
                        mock(SysUiState.class),
                        mCommandQueue,
                        Dependency.get(Dependency.MAIN_HANDLER),
                        mock(ConfigurationController.class),
                        mock(NavBarHelper.class),
                        mock(TaskbarDelegate.class),
                        mNavigationBarFactory,
                        mock(StatusBarKeyguardViewManager.class),
                        mock(DumpManager.class),
                        mock(AutoHideController.class),
                        mock(LightBarController.class),
                        Optional.of(mock(Pip.class)),
                        Optional.of(mock(BackAnimation.class))));
        initializeNavigationBars();
    }

    private void initializeNavigationBars() {
        mNavigationBarController.mNavigationBars = mock(SparseArray.class);
        mDefaultNavBar = mock(NavigationBar.class);
        mDefaultNavBar.mDisplayId = DEFAULT_DISPLAY;
        doReturn(mDefaultNavBar)
                .when(mNavigationBarController.mNavigationBars).get(DEFAULT_DISPLAY);

        mSecondaryNavBar = mock(NavigationBar.class);
        mSecondaryNavBar.mDisplayId = SECONDARY_DISPLAY;
        doReturn(mSecondaryNavBar)
                .when(mNavigationBarController.mNavigationBars).get(SECONDARY_DISPLAY);
    }

    @After
    public void tearDown() {
        mNavigationBarController = null;
        mDefaultNavBar = null;
        mSecondaryNavBar = null;
    }

    @Test
    public void testCreateNavigationBarsIncludeDefaultTrue() {
        // Tablets may be using taskbar and the logic is different
        mNavigationBarController.mIsTablet = false;
        doNothing().when(mNavigationBarController).createNavigationBar(any(), any(), any());

        mNavigationBarController.createNavigationBars(true, null);

        verify(mNavigationBarController).createNavigationBar(
                argThat(display -> display.getDisplayId() == DEFAULT_DISPLAY), any(), any());
    }

    @Test
    public void testCreateNavigationBarsIncludeDefaultFalse() {
        doNothing().when(mNavigationBarController).createNavigationBar(any(), any(), any());

        mNavigationBarController.createNavigationBars(false, null);

        verify(mNavigationBarController, never()).createNavigationBar(
                argThat(display -> display.getDisplayId() == DEFAULT_DISPLAY), any(), any());
    }

    // Tests if NPE occurs when call checkNavBarModes() with invalid display.
    @Test
    public void testCheckNavBarModesWithInvalidDisplay() {
        mNavigationBarController.checkNavBarModes(INVALID_DISPLAY);
    }

    @Test
    public void testCheckNavBarModesWithDefaultDisplay() {
        doNothing().when(mDefaultNavBar).checkNavBarModes();

        mNavigationBarController.checkNavBarModes(DEFAULT_DISPLAY);

        verify(mDefaultNavBar).checkNavBarModes();
    }

    @Test
    public void testCheckNavBarModesWithSecondaryDisplay() {
        doNothing().when(mSecondaryNavBar).checkNavBarModes();

        mNavigationBarController.checkNavBarModes(SECONDARY_DISPLAY);

        verify(mSecondaryNavBar).checkNavBarModes();
    }

    // Tests if NPE occurs when call finishBarAnimations() with invalid display.
    @Test
    public void testFinishBarAnimationsWithInvalidDisplay() {
        mNavigationBarController.finishBarAnimations(INVALID_DISPLAY);
    }

    @Test
    public void testFinishBarAnimationsWithDefaultDisplay() {
        doNothing().when(mDefaultNavBar).finishBarAnimations();

        mNavigationBarController.finishBarAnimations(DEFAULT_DISPLAY);

        verify(mDefaultNavBar).finishBarAnimations();
    }

    @Test
    public void testFinishBarAnimationsWithSecondaryDisplay() {
        doNothing().when(mSecondaryNavBar).finishBarAnimations();

        mNavigationBarController.finishBarAnimations(SECONDARY_DISPLAY);

        verify(mSecondaryNavBar).finishBarAnimations();
    }

    // Tests if NPE occurs when call touchAutoDim() with invalid display.
    @Test
    public void testTouchAutoDimWithInvalidDisplay() {
        mNavigationBarController.touchAutoDim(INVALID_DISPLAY);
    }

    @Test
    public void testTouchAutoDimWithDefaultDisplay() {
        doNothing().when(mDefaultNavBar).touchAutoDim();

        mNavigationBarController.touchAutoDim(DEFAULT_DISPLAY);

        verify(mDefaultNavBar).touchAutoDim();
    }

    @Test
    public void testTouchAutoDimWithSecondaryDisplay() {
        doNothing().when(mSecondaryNavBar).touchAutoDim();

        mNavigationBarController.touchAutoDim(SECONDARY_DISPLAY);

        verify(mSecondaryNavBar).touchAutoDim();
    }

    // Tests if NPE occurs when call transitionTo() with invalid display.
    @Test
    public void testTransitionToWithInvalidDisplay() {
        mNavigationBarController.transitionTo(INVALID_DISPLAY, 3, true);
    }

    @Test
    public void testTransitionToWithDefaultDisplay() {
        doNothing().when(mDefaultNavBar).transitionTo(anyInt(), anyBoolean());

        mNavigationBarController.transitionTo(DEFAULT_DISPLAY, 3, true);

        verify(mDefaultNavBar).transitionTo(eq(3), eq(true));
    }

    @Test
    public void testTransitionToWithSecondaryDisplay() {
        doNothing().when(mSecondaryNavBar).transitionTo(anyInt(), anyBoolean());

        mNavigationBarController.transitionTo(SECONDARY_DISPLAY, 3, true);

        verify(mSecondaryNavBar).transitionTo(eq(3), eq(true));
    }

    // Tests if NPE occurs when call disableAnimationsDuringHide() with invalid display.
    @Test
    public void testDisableAnimationsDuringHideWithInvalidDisplay() {
        mNavigationBarController.disableAnimationsDuringHide(INVALID_DISPLAY, 500L);
    }

    @Test
    public void testDisableAnimationsDuringHideWithDefaultDisplay() {
        doNothing().when(mDefaultNavBar).disableAnimationsDuringHide(anyLong());

        mNavigationBarController.disableAnimationsDuringHide(DEFAULT_DISPLAY, 500L);

        verify(mDefaultNavBar).disableAnimationsDuringHide(eq(500L));
    }

    @Test
    public void testDisableAnimationsDuringHideWithSecondaryDisplay() {
        doNothing().when(mSecondaryNavBar).disableAnimationsDuringHide(anyLong());

        mNavigationBarController.disableAnimationsDuringHide(SECONDARY_DISPLAY, 500L);

        verify(mSecondaryNavBar).disableAnimationsDuringHide(eq(500L));
    }

    @Test
    public void test3ButtonTaskbarFlagDisabledNoRegister() {
        verify(mCommandQueue, never()).addCallback(any(TaskbarDelegate.class));
    }
}
