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

import android.content.res.Configuration;
import android.hardware.display.DisplayManagerGlobal;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.view.Display;
import android.view.DisplayInfo;

import java.util.ArrayList;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.DisplayAdjustments.DEFAULT_DISPLAY_ADJUSTMENTS;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
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

        exitingAppWindow.removeImmediately();
    }

    @Test
    public void testForAllWindows_WithAppImeTarget() throws Exception {
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

    @Test
    public void testForAllWindows_WithStatusBarImeTarget() throws Exception {

        sWm.mInputMethodTarget = sStatusBarWindow;

        final ArrayList<WindowState> windows = new ArrayList();

        // Test forward traversal.
        sDisplayContent.forAllWindows(w -> {windows.add(w);}, false /* traverseTopToBottom */);

        assertEquals(sWallpaperWindow, windows.get(0));
        assertEquals(sChildAppWindowBelow, windows.get(1));
        assertEquals(sAppWindow, windows.get(2));
        assertEquals(sChildAppWindowAbove, windows.get(3));
        assertEquals(sDockedDividerWindow, windows.get(4));
        assertEquals(sStatusBarWindow, windows.get(5));
        assertEquals(sImeWindow, windows.get(6));
        assertEquals(sImeDialogWindow, windows.get(7));
        assertEquals(sNavBarWindow, windows.get(8));

        // Test backward traversal.
        windows.clear();
        sDisplayContent.forAllWindows(w -> {windows.add(w);}, true /* traverseTopToBottom */);

        assertEquals(sWallpaperWindow, windows.get(8));
        assertEquals(sChildAppWindowBelow, windows.get(7));
        assertEquals(sAppWindow, windows.get(6));
        assertEquals(sChildAppWindowAbove, windows.get(5));
        assertEquals(sDockedDividerWindow, windows.get(4));
        assertEquals(sStatusBarWindow, windows.get(3));
        assertEquals(sImeWindow, windows.get(2));
        assertEquals(sImeDialogWindow, windows.get(1));
        assertEquals(sNavBarWindow, windows.get(0));

        // Clean-up
        sWm.mInputMethodTarget = null;
    }

    @Test
    public void testForAllWindows_WithInBetweenWindowToken() throws Exception {
        // This window is set-up to be z-ordered between some windows that go in the same token like
        // the nav bar and status bar.
        final WindowState voiceInteractionWindow = createWindow(null, TYPE_VOICE_INTERACTION,
                sDisplayContent, "voiceInteractionWindow");

        final ArrayList<WindowState> windows = new ArrayList();

        // Test forward traversal.
        sDisplayContent.forAllWindows(w -> {windows.add(w);}, false /* traverseTopToBottom */);

        assertEquals(sWallpaperWindow, windows.get(0));
        assertEquals(sChildAppWindowBelow, windows.get(1));
        assertEquals(sAppWindow, windows.get(2));
        assertEquals(sChildAppWindowAbove, windows.get(3));
        assertEquals(sDockedDividerWindow, windows.get(4));
        assertEquals(voiceInteractionWindow, windows.get(5));
        assertEquals(sStatusBarWindow, windows.get(6));
        assertEquals(sNavBarWindow, windows.get(7));
        assertEquals(sImeWindow, windows.get(8));
        assertEquals(sImeDialogWindow, windows.get(9));

        // Test backward traversal.
        windows.clear();
        sDisplayContent.forAllWindows(w -> {windows.add(w);}, true /* traverseTopToBottom */);

        assertEquals(sWallpaperWindow, windows.get(9));
        assertEquals(sChildAppWindowBelow, windows.get(8));
        assertEquals(sAppWindow, windows.get(7));
        assertEquals(sChildAppWindowAbove, windows.get(6));
        assertEquals(sDockedDividerWindow, windows.get(5));
        assertEquals(voiceInteractionWindow, windows.get(4));
        assertEquals(sStatusBarWindow, windows.get(3));
        assertEquals(sNavBarWindow, windows.get(2));
        assertEquals(sImeWindow, windows.get(1));
        assertEquals(sImeDialogWindow, windows.get(0));

        voiceInteractionWindow.removeImmediately();
    }

    /**
     * This tests stack movement between displays and proper stack's, task's and app token's display
     * container references updates.
     */
    @Test
    public void testMoveStackBetweenDisplays() throws Exception {
        // Create a second display.
        final DisplayContent dc = createNewDisplay();

        // Add stack with activity.
        final TaskStack stack = createTaskStackOnDisplay(dc);
        assertEquals(dc.getDisplayId(), stack.getDisplayContent().getDisplayId());
        assertEquals(dc, stack.getParent().getParent());
        assertEquals(dc, stack.getDisplayContent());

        final Task task = createTaskInStack(stack, 0 /* userId */);
        final TestAppWindowToken token = new TestAppWindowToken(dc);
        task.addChild(token, 0);
        assertEquals(dc, task.getDisplayContent());
        assertEquals(dc, token.getDisplayContent());

        // Move stack to first display.
        sDisplayContent.moveStackToDisplay(stack);
        assertEquals(sDisplayContent.getDisplayId(), stack.getDisplayContent().getDisplayId());
        assertEquals(sDisplayContent, stack.getParent().getParent());
        assertEquals(sDisplayContent, stack.getDisplayContent());
        assertEquals(sDisplayContent, task.getDisplayContent());
        assertEquals(sDisplayContent, token.getDisplayContent());
    }

    /**
     * This tests override configuration updates for display content.
     */
    @Test
    public void testDisplayOverrideConfigUpdate() throws Exception {
        final int displayId = sDisplayContent.getDisplayId();
        final Configuration currentOverrideConfig = sDisplayContent.getOverrideConfiguration();

        // Create new, slightly changed override configuration and apply it to the display.
        final Configuration newOverrideConfig = new Configuration(currentOverrideConfig);
        newOverrideConfig.densityDpi += 120;
        newOverrideConfig.fontScale += 0.3;

        sWm.setNewDisplayOverrideConfiguration(newOverrideConfig, displayId);

        // Check that override config is applied.
        assertEquals(newOverrideConfig, sDisplayContent.getOverrideConfiguration());
    }

    /**
     * This tests global configuration updates when default display config is updated.
     */
    @Test
    public void testDefaultDisplayOverrideConfigUpdate() throws Exception {
        final Configuration currentOverrideConfig = sDisplayContent.getOverrideConfiguration();

        // Create new, slightly changed override configuration and apply it to the display.
        final Configuration newOverrideConfig = new Configuration(currentOverrideConfig);
        newOverrideConfig.densityDpi += 120;
        newOverrideConfig.fontScale += 0.3;

        sWm.setNewDisplayOverrideConfiguration(newOverrideConfig, DEFAULT_DISPLAY);

        // Check that global configuration is updated, as we've updated default display's config.
        Configuration globalConfig = sWm.mRoot.getConfiguration();
        assertEquals(newOverrideConfig.densityDpi, globalConfig.densityDpi);
        assertEquals(newOverrideConfig.fontScale, globalConfig.fontScale, 0.1 /* delta */);

        // Return back to original values.
        sWm.setNewDisplayOverrideConfiguration(currentOverrideConfig, DEFAULT_DISPLAY);
        globalConfig = sWm.mRoot.getConfiguration();
        assertEquals(currentOverrideConfig.densityDpi, globalConfig.densityDpi);
        assertEquals(currentOverrideConfig.fontScale, globalConfig.fontScale, 0.1 /* delta */);
    }
}
