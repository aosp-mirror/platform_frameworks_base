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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;
import org.junit.runner.RunWith;

import android.content.res.Configuration;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

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
        exitingAppToken.getTask().mStack.mExitingAppTokens.add(exitingAppToken);

        assertForAllWindowsOrder(Arrays.asList(
                sWallpaperWindow,
                exitingAppWindow,
                sChildAppWindowBelow,
                sAppWindow,
                sChildAppWindowAbove,
                sDockedDividerWindow,
                sStatusBarWindow,
                sNavBarWindow,
                sImeWindow,
                sImeDialogWindow));
    }

    @Test
    public void testForAllWindows_WithAppImeTarget() throws Exception {
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, sDisplayContent, "imeAppTarget");

        sWm.mInputMethodTarget = imeAppTarget;

        assertForAllWindowsOrder(Arrays.asList(
                sWallpaperWindow,
                sChildAppWindowBelow,
                sAppWindow,
                sChildAppWindowAbove,
                imeAppTarget,
                sImeWindow,
                sImeDialogWindow,
                sDockedDividerWindow,
                sStatusBarWindow,
                sNavBarWindow));
    }

    @Test
    public void testForAllWindows_WithChildWindowImeTarget() throws Exception {
        sWm.mInputMethodTarget = sChildAppWindowAbove;

        assertForAllWindowsOrder(Arrays.asList(
                sWallpaperWindow,
                sChildAppWindowBelow,
                sAppWindow,
                sChildAppWindowAbove,
                sImeWindow,
                sImeDialogWindow,
                sDockedDividerWindow,
                sStatusBarWindow,
                sNavBarWindow));
    }

    @Test
    public void testForAllWindows_WithStatusBarImeTarget() throws Exception {
        sWm.mInputMethodTarget = sStatusBarWindow;

        assertForAllWindowsOrder(Arrays.asList(
                sWallpaperWindow,
                sChildAppWindowBelow,
                sAppWindow,
                sChildAppWindowAbove,
                sDockedDividerWindow,
                sStatusBarWindow,
                sImeWindow,
                sImeDialogWindow,
                sNavBarWindow));
    }

    @Test
    public void testForAllWindows_WithInBetweenWindowToken() throws Exception {
        // This window is set-up to be z-ordered between some windows that go in the same token like
        // the nav bar and status bar.
        final WindowState voiceInteractionWindow = createWindow(null, TYPE_VOICE_INTERACTION,
                sDisplayContent, "voiceInteractionWindow");

        assertForAllWindowsOrder(Arrays.asList(
                sWallpaperWindow,
                sChildAppWindowBelow,
                sAppWindow,
                sChildAppWindowAbove,
                sDockedDividerWindow,
                voiceInteractionWindow,
                sStatusBarWindow,
                sNavBarWindow,
                sImeWindow,
                sImeDialogWindow));
    }

    @Test
    public void testComputeImeTarget() throws Exception {
        // Verify that an app window can be an ime target.
        final WindowState appWin = createWindow(null, TYPE_APPLICATION, sDisplayContent, "appWin");
        appWin.setHasSurface(true);
        assertTrue(appWin.canBeImeTarget());
        sWm.mInputMethodTargetCandidate = appWin.mClient.asBinder();
        WindowState imeTarget = sDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(appWin, imeTarget);

        // Verify that an child window can be an ime target.
        final WindowState childWin = createWindow(appWin,
                TYPE_APPLICATION_ATTACHED_DIALOG, "childWin");
        childWin.setHasSurface(true);
        assertTrue(childWin.canBeImeTarget());
        imeTarget = sDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(childWin, imeTarget);

        final WindowState appWin2 =
                createWindow(null, TYPE_APPLICATION, sDisplayContent, "appWin2");
        appWin2.setHasSurface(true);
        assertTrue(appWin2.canBeImeTarget());
        // Verify that the IME target isn't adjusted since mInputMethodTargetCandidate didn't change
        // to the new app.
        imeTarget = sDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertNotEquals(appWin2, imeTarget);

        sWm.mInputMethodTargetCandidate = appWin2.mClient.asBinder();
        // Verify app is not IME target since its token is set as a candidate.
        imeTarget = sDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(appWin2, imeTarget);
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

    private void assertForAllWindowsOrder(List<WindowState> expectedWindows) {
        final LinkedList<WindowState> actualWindows = new LinkedList();

        // Test forward traversal.
        sDisplayContent.forAllWindows(actualWindows::addLast, false /* traverseTopToBottom */);
        assertEquals(expectedWindows.size(), actualWindows.size());
        for (WindowState w : expectedWindows) {
            assertEquals(w, actualWindows.pollFirst());
        }
        assertTrue(actualWindows.isEmpty());

        // Test backward traversal.
        sDisplayContent.forAllWindows(actualWindows::addLast, true /* traverseTopToBottom */);
        assertEquals(expectedWindows.size(), actualWindows.size());
        for (WindowState w : expectedWindows) {
            assertEquals(w, actualWindows.pollLast());
        }
        assertTrue(actualWindows.isEmpty());
    }
}
