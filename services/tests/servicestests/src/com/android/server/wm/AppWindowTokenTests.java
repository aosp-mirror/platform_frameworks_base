/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.server.wm;

import org.junit.Test;
import org.junit.Ignore;
import org.junit.runner.RunWith;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.Surface;
import android.view.WindowManager;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@link AppWindowToken} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.AppWindowTokenTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AppWindowTokenTests extends WindowTestsBase {

    @Test
    public void testAddWindow_Order() throws Exception {
        final TestAppWindowToken token = new TestAppWindowToken(sDisplayContent);

        assertEquals(0, token.getWindowsCount());

        final WindowState win1 = createWindow(null, TYPE_APPLICATION, token, "win1");
        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, token,
                "startingWin");
        final WindowState baseWin = createWindow(null, TYPE_BASE_APPLICATION, token, "baseWin");
        final WindowState win4 = createWindow(null, TYPE_APPLICATION, token, "win4");

        // Should not contain the windows that were added above.
        assertEquals(4, token.getWindowsCount());
        assertTrue(token.hasWindow(win1));
        assertTrue(token.hasWindow(startingWin));
        assertTrue(token.hasWindow(baseWin));
        assertTrue(token.hasWindow(win4));

        // The starting window should be on-top of all other windows.
        assertEquals(startingWin, token.getLastChild());

        // The base application window should be below all other windows.
        assertEquals(baseWin, token.getFirstChild());
        token.removeImmediately();
    }

    @Test
    public void testFindMainWindow() throws Exception {
        final TestAppWindowToken token = new TestAppWindowToken(sDisplayContent);

        assertNull(token.findMainWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, token, "window1");
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, token, "window11");
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, token, "window12");
        assertEquals(window1, token.findMainWindow());
        window1.mAnimatingExit = true;
        assertEquals(window1, token.findMainWindow());
        final WindowState window2 = createWindow(null, TYPE_APPLICATION_STARTING, token, "window2");
        assertEquals(window2, token.findMainWindow());
        token.removeImmediately();
    }

    @Test
    public void testLandscapeSeascapeRotationByApp() throws Exception {
        // Some plumbing to get the service ready for rotation updates.
        sWm.mDisplayReady = true;
        sWm.mDisplayEnabled = true;

        // Create an app window with token on a display.
        final TaskStack stack = createTaskStackOnDisplay(sDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final TestAppWindowToken appWindowToken = new TestAppWindowToken(sDisplayContent);
        task.addChild(appWindowToken, 0);
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow");
        final TestWindowState appWindow = new TestWindowState(attrs, appWindowToken);
        appWindowToken.addWindow(appWindow);

        // Set initial orientation and update.
        appWindowToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        sWm.updateOrientationFromAppTokens(sDisplayContent.getOverrideConfiguration(), null,
                sDisplayContent.getDisplayId());
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, sWm.mLastOrientation);
        appWindow.resizeReported = false;

        // Update the orientation to perform 180 degree rotation and check that resize was reported.
        appWindowToken.setOrientation(SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        sWm.updateOrientationFromAppTokens(sDisplayContent.getOverrideConfiguration(), null,
                sDisplayContent.getDisplayId());
        sWm.mRoot.performSurfacePlacement(false /* recoveringMemory */);
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, sWm.mLastOrientation);
        assertTrue(appWindow.resizeReported);
        appWindow.removeImmediately();
    }

    @Test
    @Ignore
    // TODO(b/35034729): Need to fix before re-enabling
    public void testLandscapeSeascapeRotationByPolicy() throws Exception {
        // Some plumbing to get the service ready for rotation updates.
        sWm.mDisplayReady = true;
        sWm.mDisplayEnabled = true;

        // Create an app window with token on a display.
        final TaskStack stack = createTaskStackOnDisplay(sDisplayContent);
        final Task task = createTaskInStack(stack, 0 /* userId */);
        final TestAppWindowToken appWindowToken = new TestAppWindowToken(sDisplayContent);
        task.addChild(appWindowToken, 0);
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow");
        final TestWindowState appWindow = new TestWindowState(attrs, appWindowToken);
        appWindowToken.addWindow(appWindow);

        // Set initial orientation and update.
        ((TestWindowManagerPolicy) sWm.mPolicy).rotationToReport = Surface.ROTATION_90;
        sWm.updateRotation(false, false);
        appWindow.resizeReported = false;

        // Update the rotation to perform 180 degree rotation and check that resize was reported.
        ((TestWindowManagerPolicy) sWm.mPolicy).rotationToReport = Surface.ROTATION_270;
        sWm.updateRotation(false, false);
        sWm.mRoot.performSurfacePlacement(false /* recoveringMemory */);
        assertTrue(appWindow.resizeReported);
        appWindow.removeImmediately();
    }
}
