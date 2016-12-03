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
import org.junit.runner.RunWith;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.util.ArrayList;

import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static org.junit.Assert.assertEquals;

/**
 * Tests for the {@link DisplayContent} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.DisplayContentTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class DisplayContentTests extends WindowTestsBase {

    @Test
    public void testForAllWindows() throws Exception {
        final WindowState exitingAppWindow = createWindow(null, TYPE_BASE_APPLICATION,
                sDisplayContent, "exiting app");
        final AppWindowToken exitingAppToken = exitingAppWindow.mAppToken;
        exitingAppToken.mIsExiting = true;
        exitingAppToken.mTask.mStack.mExitingAppTokens.add(exitingAppToken);

        final ArrayList<WindowState> windows = new ArrayList();

        // Test forward traversal.
        sDisplayContent.forAllWindows(w -> {windows.add(w);}, false /* traverseTopToBottom */);

        assertEquals(sWallpaperWindow, windows.get(0));
        assertEquals(exitingAppWindow, windows.get(1));
        assertEquals(sChildAppWindowBelow, windows.get(2));
        assertEquals(sAppWindow, windows.get(3));
        assertEquals(sChildAppWindowAbove, windows.get(4));
        assertEquals(sDockedDividerWindow, windows.get(5));
        assertEquals(sStatusBarWindow, windows.get(6));
        assertEquals(sNavBarWindow, windows.get(7));
        assertEquals(sImeWindow, windows.get(8));
        assertEquals(sImeDialogWindow, windows.get(9));

        // Test backward traversal.
        windows.clear();
        sDisplayContent.forAllWindows(w -> {windows.add(w);}, true /* traverseTopToBottom */);

        assertEquals(sWallpaperWindow, windows.get(9));
        assertEquals(exitingAppWindow, windows.get(8));
        assertEquals(sChildAppWindowBelow, windows.get(7));
        assertEquals(sAppWindow, windows.get(6));
        assertEquals(sChildAppWindowAbove, windows.get(5));
        assertEquals(sDockedDividerWindow, windows.get(4));
        assertEquals(sStatusBarWindow, windows.get(3));
        assertEquals(sNavBarWindow, windows.get(2));
        assertEquals(sImeWindow, windows.get(1));
        assertEquals(sImeDialogWindow, windows.get(0));
    }

    @Test
    public void testForAllWindows_WithImeTarget() throws Exception {
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, sDisplayContent, "imeAppTarget");

        sWm.mInputMethodTarget = imeAppTarget;

        final ArrayList<WindowState> windows = new ArrayList();

        // Test forward traversal.
        sDisplayContent.forAllWindows(w -> {windows.add(w);}, false /* traverseTopToBottom */);

        assertEquals(sWallpaperWindow, windows.get(0));
        assertEquals(sChildAppWindowBelow, windows.get(1));
        assertEquals(sAppWindow, windows.get(2));
        assertEquals(sChildAppWindowAbove, windows.get(3));
        assertEquals(imeAppTarget, windows.get(4));
        assertEquals(sImeWindow, windows.get(5));
        assertEquals(sImeDialogWindow, windows.get(6));
        assertEquals(sDockedDividerWindow, windows.get(7));
        assertEquals(sStatusBarWindow, windows.get(8));
        assertEquals(sNavBarWindow, windows.get(9));

        // Test backward traversal.
        windows.clear();
        sDisplayContent.forAllWindows(w -> {windows.add(w);}, true /* traverseTopToBottom */);

        assertEquals(sWallpaperWindow, windows.get(9));
        assertEquals(sChildAppWindowBelow, windows.get(8));
        assertEquals(sAppWindow, windows.get(7));
        assertEquals(sChildAppWindowAbove, windows.get(6));
        assertEquals(imeAppTarget, windows.get(5));
        assertEquals(sImeWindow, windows.get(4));
        assertEquals(sImeDialogWindow, windows.get(3));
        assertEquals(sDockedDividerWindow, windows.get(2));
        assertEquals(sStatusBarWindow, windows.get(1));
        assertEquals(sNavBarWindow, windows.get(0));

        // Clean-up
        sWm.mInputMethodTarget = null;
        imeAppTarget.removeImmediately();
    }
}
