/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyFloat;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.window.flags.Flags.explicitRefreshRateHints;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.hardware.display.DisplayManager;
import android.platform.test.annotations.Presubmit;
import android.view.Display.Mode;
import android.view.DisplayInfo;
import android.view.Surface;
import android.view.SurfaceControl;

import com.android.server.wm.RefreshRatePolicy.FrameRateVote;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * This file tests WM setting the priority on windows that is used in SF to determine at what
 * frame rate the Display should run. Any changes to the algorithm should be reflected in these
 * tests.
 *
 * Build/Install/Run: atest FrameRateSelectionPriority
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class FrameRateSelectionPriorityTests extends WindowTestsBase {
    private static final int LOW_MODE_ID = 3;

    private DisplayPolicy mDisplayPolicy = mock(DisplayPolicy.class);
    private RefreshRatePolicy mRefreshRatePolicy;
    private HighRefreshRateDenylist mDenylist = mock(HighRefreshRateDenylist.class);
    private FrameRateVote mTempFrameRateVote = new FrameRateVote();

    private static final FrameRateVote FRAME_RATE_VOTE_NONE = new FrameRateVote();
    private static final FrameRateVote FRAME_RATE_VOTE_60_EXACT =
            new FrameRateVote(60, Surface.FRAME_RATE_COMPATIBILITY_EXACT,
                    SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);
    private static final FrameRateVote FRAME_RATE_VOTE_60_PREFERRED =
            new FrameRateVote(60, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                    SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);

    WindowState createWindow(String name) {
        WindowState window = createWindow(null, TYPE_APPLICATION, name);
        when(window.mWmService.mDisplayManagerInternal.getRefreshRateSwitchingType())
                .thenReturn(DisplayManager.SWITCHING_TYPE_WITHIN_GROUPS);
        return window;
    }

    @Before
    public void setUp() {
        DisplayInfo di = new DisplayInfo(mDisplayInfo);
        Mode defaultMode = di.getDefaultMode();
        di.supportedModes = new Mode[] {
                new Mode(1, defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(), 90),
                new Mode(2, defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(), 70),
                new Mode(LOW_MODE_ID,
                        defaultMode.getPhysicalWidth(), defaultMode.getPhysicalHeight(), 60),
        };
        di.defaultModeId = 1;
        mRefreshRatePolicy = new RefreshRatePolicy(mWm, di, mDenylist);
        when(mDisplayPolicy.getRefreshRatePolicy()).thenReturn(mRefreshRatePolicy);
    }

    @Test
    public void basicTest() {
        final WindowState appWindow = createWindow("appWindow");
        assertNotNull("Window state is created", appWindow);

        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority doesn't change.
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        // Call the function a few times.
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        appWindow.updateFrameRateSelectionPriorityIfNeeded();

        // Since nothing changed in the priority state, the transaction should not be updating.
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionPriority(
                any(SurfaceControl.class), anyInt());
        verify(appWindow.getPendingTransaction(), never()).setFrameRate(
                any(SurfaceControl.class), anyInt(), anyInt(), anyInt());
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionStrategy(
                any(SurfaceControl.class), anyInt());
    }

    @Test
    public void testApplicationInFocusWithoutModeId() {
        final WindowState appWindow = createWindow("appWindow");
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        assertEquals(appWindow.getDisplayContent().getDisplayPolicy().getRefreshRatePolicy()
                .getPreferredModeId(appWindow), 0);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);
        assertFalse(appWindow.getDisplayContent().getDisplayPolicy().getRefreshRatePolicy()
                .updateFrameRateVote(appWindow));
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority stays MAX_VALUE.
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), RefreshRatePolicy.LAYER_PRIORITY_UNSET);

        // Application is in focus.
        appWindow.mToken.mDisplayContent.mCurrentFocus = appWindow;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority changes to 1.
        assertEquals(appWindow.mFrameRateSelectionPriority, 1);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);
        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), 1);
        verify(appWindow.getPendingTransaction(), never()).setFrameRate(
                any(SurfaceControl.class), anyInt(), anyInt(), anyInt());
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionStrategy(
                any(SurfaceControl.class), anyInt());
    }

    @Test
    public void testApplicationInFocusWithModeId() {
        final WindowState appWindow = createWindow("appWindow");
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        // Application is in focus.
        appWindow.mToken.mDisplayContent.mCurrentFocus = appWindow;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority changes.
        assertEquals(appWindow.mFrameRateSelectionPriority, 1);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);
        // Update the mode ID to a requested number.
        appWindow.mAttrs.preferredDisplayModeId = 1;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority changes.
        assertEquals(appWindow.mFrameRateSelectionPriority, 0);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_60_EXACT);

        // Remove the mode ID request.
        appWindow.mAttrs.preferredDisplayModeId = 0;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority changes.
        assertEquals(appWindow.mFrameRateSelectionPriority, 1);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        // Verify we called actions on Transactions correctly.
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), 0);
        verify(appWindow.getPendingTransaction(), times(2)).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), 1);
        verify(appWindow.getPendingTransaction(), times(1)).setFrameRate(
                eq(appWindow.getSurfaceControl()), anyFloat(),
                eq(Surface.FRAME_RATE_COMPATIBILITY_EXACT), eq(Surface.CHANGE_FRAME_RATE_ALWAYS));
        if (explicitRefreshRateHints()) {
            verify(appWindow.getPendingTransaction(), times(1)).setFrameRateSelectionStrategy(
                    appWindow.getSurfaceControl(),
                    SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);
        } else {
            verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionStrategy(
                    any(SurfaceControl.class), anyInt());
        }
    }

    @Test
    public void testApplicationNotInFocusWithModeId() {
        final WindowState appWindow = createWindow("appWindow");
        mDisplayContent.mCurrentFocus = appWindow;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        mDisplayContent.mCurrentFocus = null;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();

        // The window is not in focus.
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        // Update the mode ID to a requested number.
        appWindow.mAttrs.preferredDisplayModeId = 1;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority changes.
        assertEquals(appWindow.mFrameRateSelectionPriority, 2);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_60_EXACT);

        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), 2);
        verify(appWindow.getPendingTransaction(), times(1)).setFrameRate(
                eq(appWindow.getSurfaceControl()), anyFloat(),
                eq(Surface.FRAME_RATE_COMPATIBILITY_EXACT), eq(Surface.CHANGE_FRAME_RATE_ALWAYS));
        if (explicitRefreshRateHints()) {
            verify(appWindow.getPendingTransaction(), times(1)).setFrameRateSelectionStrategy(
                    appWindow.getSurfaceControl(),
                    SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);
        } else {
            verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionStrategy(
                    any(SurfaceControl.class), anyInt());
        }
    }

    @Test
    public void testApplicationNotInFocusWithoutModeId() {
        final WindowState appWindow = createWindow("appWindow");
        mDisplayContent.mCurrentFocus = appWindow;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        mDisplayContent.mCurrentFocus = null;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();

        // The window is not in focus.
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        // Make sure that the mode ID is not set.
        appWindow.mAttrs.preferredDisplayModeId = 0;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority doesn't change.
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        verify(appWindow.getPendingTransaction(), never()).setFrameRate(
                any(SurfaceControl.class), anyInt(), anyInt(), anyInt());
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionStrategy(
                any(SurfaceControl.class), anyInt());
    }

    @Test
    public void testDenyListPreferredRefreshRate() {
        final WindowState appWindow = createWindow("appWindow");
        assertNotNull("Window state is created", appWindow);
        when(appWindow.getDisplayContent().getDisplayPolicy()).thenReturn(mDisplayPolicy);

        appWindow.mAttrs.packageName = "com.android.test";
        when(mDenylist.isDenylisted("com.android.test")).thenReturn(true);

        assertEquals(0, mRefreshRatePolicy.getPreferredModeId(appWindow));

        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        assertEquals(RefreshRatePolicy.LAYER_PRIORITY_UNSET, appWindow.mFrameRateSelectionPriority);
        assertEquals(FRAME_RATE_VOTE_60_EXACT, appWindow.mFrameRateVote);

        // Call the function a few times.
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        appWindow.updateFrameRateSelectionPriorityIfNeeded();

        // Since nothing changed in the priority state, the transaction should not be updating.
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionPriority(
                any(SurfaceControl.class), anyInt());
        verify(appWindow.getPendingTransaction(), times(1)).setFrameRate(
                appWindow.getSurfaceControl(), 60,
                Surface.FRAME_RATE_COMPATIBILITY_EXACT, Surface.CHANGE_FRAME_RATE_ALWAYS);
        if (explicitRefreshRateHints()) {
            verify(appWindow.getPendingTransaction(), times(1)).setFrameRateSelectionStrategy(
                    appWindow.getSurfaceControl(),
                    SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);
        } else {
            verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionStrategy(
                    any(SurfaceControl.class), anyInt());
        }
    }

    @Test
    public void testSwitchingTypeNone() {
        final WindowState appWindow = createWindow("appWindow");
        when(appWindow.mWmService.mDisplayManagerInternal.getRefreshRateSwitchingType())
                .thenReturn(DisplayManager.SWITCHING_TYPE_NONE);

        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        // Update the mode ID to a requested number.
        appWindow.mAttrs.preferredDisplayModeId = 1;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();

        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        // Remove the mode ID request.
        appWindow.mAttrs.preferredDisplayModeId = 0;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();

        assertEquals(appWindow.mFrameRateVote, FRAME_RATE_VOTE_NONE);

        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        verify(appWindow.getPendingTransaction(), never()).setFrameRate(
                any(SurfaceControl.class), anyInt(), anyInt(), anyInt());
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionStrategy(
                any(SurfaceControl.class), anyInt());
    }

    @Test
    public void testAppPreferredRefreshRate() {
        final WindowState appWindow = createWindow("appWindow");
        assertNotNull("Window state is created", appWindow);
        when(appWindow.getDisplayContent().getDisplayPolicy()).thenReturn(mDisplayPolicy);

        appWindow.mAttrs.packageName = "com.android.test";
        appWindow.mAttrs.preferredRefreshRate = 60;

        assertEquals(0, mRefreshRatePolicy.getPreferredModeId(appWindow));

        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        assertEquals(RefreshRatePolicy.LAYER_PRIORITY_UNSET, appWindow.mFrameRateSelectionPriority);
        assertEquals(FRAME_RATE_VOTE_60_PREFERRED, appWindow.mFrameRateVote);

        // Call the function a few times.
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        appWindow.updateFrameRateSelectionPriorityIfNeeded();

        // Since nothing changed in the priority state, the transaction should not be updating.
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionPriority(
                any(SurfaceControl.class), anyInt());
        verify(appWindow.getPendingTransaction(), times(1)).setFrameRate(
                appWindow.getSurfaceControl(), 60,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT, Surface.CHANGE_FRAME_RATE_ALWAYS);
        if (explicitRefreshRateHints()) {
            verify(appWindow.getPendingTransaction(), times(1)).setFrameRateSelectionStrategy(
                    appWindow.getSurfaceControl(),
                    SurfaceControl.FRAME_RATE_SELECTION_STRATEGY_OVERRIDE_CHILDREN);
        } else {
            verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionStrategy(
                    any(SurfaceControl.class), anyInt());
        }
    }
}
