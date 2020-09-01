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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.platform.test.annotations.Presubmit;

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

    @Test
    public void basicTest() {
        final WindowState appWindow = createWindow(null, TYPE_APPLICATION, "appWindow");
        assertNotNull("Window state is created", appWindow);
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);

        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority doesn't change.
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);

        // Call the function a few times.
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        appWindow.updateFrameRateSelectionPriorityIfNeeded();

        // Since nothing changed in the priority state, the transaction should not be updating.
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), RefreshRatePolicy.LAYER_PRIORITY_UNSET);
    }

    @Test
    public void testApplicationInFocusWithoutModeId() {
        final WindowState appWindow = createWindow(null, TYPE_APPLICATION, "appWindow");
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        assertEquals(appWindow.getDisplayContent().getDisplayPolicy().getRefreshRatePolicy()
                .getPreferredModeId(appWindow), 0);

        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority stays MAX_VALUE.
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), RefreshRatePolicy.LAYER_PRIORITY_UNSET);

        // Application is in focus.
        appWindow.mToken.mDisplayContent.mCurrentFocus = appWindow;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority changes to 1.
        assertEquals(appWindow.mFrameRateSelectionPriority, 1);
        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), 1);
    }

    @Test
    public void testApplicationInFocusWithModeId() {
        final WindowState appWindow = createWindow(null, TYPE_APPLICATION, "appWindow");
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);

        // Application is in focus.
        appWindow.mToken.mDisplayContent.mCurrentFocus = appWindow;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority changes.
        assertEquals(appWindow.mFrameRateSelectionPriority, 1);
        // Update the mode ID to a requested number.
        appWindow.mAttrs.preferredDisplayModeId = 1;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority changes.
        assertEquals(appWindow.mFrameRateSelectionPriority, 0);

        // Remove the mode ID request.
        appWindow.mAttrs.preferredDisplayModeId = 0;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority changes.
        assertEquals(appWindow.mFrameRateSelectionPriority, 1);

        // Verify we called actions on Transactions correctly.
        verify(appWindow.getPendingTransaction(), never()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), 0);
        verify(appWindow.getPendingTransaction(), times(2)).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), 1);
    }

    @Test
    public void testApplicationNotInFocusWithModeId() {
        final WindowState appWindow = createWindow(null, TYPE_APPLICATION, "appWindow");
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);

        final WindowState inFocusWindow = createWindow(null, TYPE_APPLICATION, "inFocus");
        appWindow.mToken.mDisplayContent.mCurrentFocus = inFocusWindow;

        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // The window is not in focus.
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);

        // Update the mode ID to a requested number.
        appWindow.mAttrs.preferredDisplayModeId = 1;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority changes.
        assertEquals(appWindow.mFrameRateSelectionPriority, 2);

        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), RefreshRatePolicy.LAYER_PRIORITY_UNSET);
        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), 2);
    }

    @Test
    public void testApplicationNotInFocusWithoutModeId() {
        final WindowState appWindow = createWindow(null, TYPE_APPLICATION, "appWindow");
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);

        final WindowState inFocusWindow = createWindow(null, TYPE_APPLICATION, "inFocus");
        appWindow.mToken.mDisplayContent.mCurrentFocus = inFocusWindow;

        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // The window is not in focus.
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);

        // Make sure that the mode ID is not set.
        appWindow.mAttrs.preferredDisplayModeId = 0;
        appWindow.updateFrameRateSelectionPriorityIfNeeded();
        // Priority doesn't change.
        assertEquals(appWindow.mFrameRateSelectionPriority, RefreshRatePolicy.LAYER_PRIORITY_UNSET);

        verify(appWindow.getPendingTransaction()).setFrameRateSelectionPriority(
                appWindow.getSurfaceControl(), RefreshRatePolicy.LAYER_PRIORITY_UNSET);
    }
}
