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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Build.VERSION_CODES.Q;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.DisplayCutout.fromBoundingRect;
import static android.view.InsetsState.ITYPE_NAVIGATION_BAR;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.window.DisplayAreaOrganizer.FEATURE_WINDOWED_MAGNIFICATION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.same;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.DisplayContent.IME_TARGET_INPUT;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_FIXED_TRANSFORM;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import android.annotation.SuppressLint;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.app.servertransaction.FixedRotationAdjustmentsItem;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.metrics.LogMaker;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.DisplayMetrics;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IDisplayWindowRotationCallback;
import android.view.IDisplayWindowRotationController;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindowManager;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.View;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.server.policy.WindowManagerPolicy;
import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Tests for the {@link DisplayContent} class.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayContentTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class DisplayContentTests extends WindowTestsBase {

    @UseTestDisplay(addAllCommonWindows = true)
    @Test
    public void testForAllWindows() {
        final WindowState exitingAppWindow = createWindow(null, TYPE_BASE_APPLICATION,
                mDisplayContent, "exiting app");
        final ActivityRecord exitingApp = exitingAppWindow.mActivityRecord;
        // Wait until everything in animation handler get executed to prevent the exiting window
        // from being removed during WindowSurfacePlacer Traversal.
        waitUntilHandlersIdle();

        exitingApp.mIsExiting = true;
        exitingApp.getTask().getRootTask().mExitingActivities.add(exitingApp);

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                exitingAppWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                mDockedDividerWindow,
                mImeWindow,
                mImeDialogWindow,
                mStatusBarWindow,
                mNotificationShadeWindow,
                mNavBarWindow));
    }

    @UseTestDisplay(addAllCommonWindows = true)
    @Test
    public void testForAllWindows_WithAppImeTarget() {
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "imeAppTarget");

        mDisplayContent.setImeLayeringTarget(imeAppTarget);

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                imeAppTarget,
                mImeWindow,
                mImeDialogWindow,
                mDockedDividerWindow,
                mStatusBarWindow,
                mNotificationShadeWindow,
                mNavBarWindow));
    }

    @UseTestDisplay(addAllCommonWindows = true)
    @Test
    public void testForAllWindows_WithChildWindowImeTarget() throws Exception {
        mDisplayContent.setImeLayeringTarget(mChildAppWindowAbove);

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                mImeWindow,
                mImeDialogWindow,
                mDockedDividerWindow,
                mStatusBarWindow,
                mNotificationShadeWindow,
                mNavBarWindow));
    }

    @UseTestDisplay(addAllCommonWindows = true)
    @Test
    public void testForAllWindows_WithStatusBarImeTarget() throws Exception {
        mDisplayContent.setImeLayeringTarget(mStatusBarWindow);

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                mDockedDividerWindow,
                mStatusBarWindow,
                mImeWindow,
                mImeDialogWindow,
                mNotificationShadeWindow,
                mNavBarWindow));
    }

    @UseTestDisplay(addAllCommonWindows = true)
    @Test
    public void testForAllWindows_WithNotificationShadeImeTarget() throws Exception {
        mDisplayContent.setImeLayeringTarget(mNotificationShadeWindow);

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                mDockedDividerWindow,
                mStatusBarWindow,
                mNotificationShadeWindow,
                mImeWindow,
                mImeDialogWindow,
                mNavBarWindow));
    }

    @UseTestDisplay(addAllCommonWindows = true)
    @Test
    public void testForAllWindows_WithInBetweenWindowToken() {
        // This window is set-up to be z-ordered between some windows that go in the same token like
        // the nav bar and status bar.
        final WindowState voiceInteractionWindow = createWindow(null, TYPE_VOICE_INTERACTION,
                mDisplayContent, "voiceInteractionWindow");

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                mDockedDividerWindow,
                voiceInteractionWindow,
                mImeWindow,
                mImeDialogWindow,
                mStatusBarWindow,
                mNotificationShadeWindow,
                mNavBarWindow));
    }

    @UseTestDisplay(addAllCommonWindows = true)
    @Test
    public void testComputeImeTarget() {
        // Verify that an app window can be an ime target.
        final WindowState appWin = createWindow(null, TYPE_APPLICATION, mDisplayContent, "appWin");
        appWin.setHasSurface(true);
        assertTrue(appWin.canBeImeTarget());
        WindowState imeTarget = mDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(appWin, imeTarget);
        appWin.mHidden = false;

        // Verify that an child window can be an ime target.
        final WindowState childWin = createWindow(appWin,
                TYPE_APPLICATION_ATTACHED_DIALOG, "childWin");
        childWin.setHasSurface(true);
        assertTrue(childWin.canBeImeTarget());
        imeTarget = mDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(childWin, imeTarget);
    }

    @UseTestDisplay(addAllCommonWindows = true)
    @Test
    public void testComputeImeTarget_startingWindow() {
        ActivityRecord activity = createActivityRecord(mDisplayContent);

        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, activity,
                "startingWin");
        startingWin.setHasSurface(true);
        assertTrue(startingWin.canBeImeTarget());

        WindowState imeTarget = mDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(startingWin, imeTarget);
        startingWin.mHidden = false;

        // Verify that the starting window still be an ime target even an app window launching
        // behind it.
        final WindowState appWin = createWindow(null, TYPE_BASE_APPLICATION, activity, "appWin");
        appWin.setHasSurface(true);
        assertTrue(appWin.canBeImeTarget());

        imeTarget = mDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(startingWin, imeTarget);
        appWin.mHidden = false;

        // Verify that the starting window still be an ime target even the child window behind a
        // launching app window
        final WindowState childWin = createWindow(appWin,
                TYPE_APPLICATION_ATTACHED_DIALOG, "childWin");
        childWin.setHasSurface(true);
        assertTrue(childWin.canBeImeTarget());
        imeTarget = mDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(startingWin, imeTarget);
    }

    @UseTestDisplay(addAllCommonWindows = true)
    @Test
    public void testComputeImeTarget_placeImeToTheTargetRoot() {
        ActivityRecord activity = createActivityRecord(mDisplayContent);

        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, activity,
                "startingWin");
        startingWin.setHasSurface(true);
        assertTrue(startingWin.canBeImeTarget());
        DisplayArea.Tokens imeContainer = mDisplayContent.getImeContainer();

        WindowState imeTarget = mDisplayContent.computeImeTarget(true /* updateImeTarget */);
        verify(imeTarget.getRootDisplayArea()).placeImeContainer(imeContainer);
    }

    /**
     * This tests stack movement between displays and proper stack's, task's and app token's display
     * container references updates.
     */
    @Test
    public void testMoveStackBetweenDisplays() {
        // Create a second display.
        final DisplayContent dc = createNewDisplay();

        // Add stack with activity.
        final Task stack = createTaskStackOnDisplay(dc);
        assertEquals(dc.getDisplayId(), stack.getDisplayContent().getDisplayId());
        assertEquals(dc, stack.getDisplayContent());

        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ActivityRecord activity = createNonAttachedActivityRecord(dc);
        task.addChild(activity, 0);
        assertEquals(dc, task.getDisplayContent());
        assertEquals(dc, activity.getDisplayContent());

        // Move stack to first display.
        stack.reparent(mDisplayContent.getDefaultTaskDisplayArea(), true /* onTop */);
        assertEquals(mDisplayContent.getDisplayId(), stack.getDisplayContent().getDisplayId());
        assertEquals(mDisplayContent, stack.getDisplayContent());
        assertEquals(mDisplayContent, task.getDisplayContent());
        assertEquals(mDisplayContent, activity.getDisplayContent());
    }

    /**
     * This tests override configuration updates for display content.
     */
    @Test
    public void testDisplayOverrideConfigUpdate() {
        final Configuration currentOverrideConfig =
                mDisplayContent.getRequestedOverrideConfiguration();

        // Create new, slightly changed override configuration and apply it to the display.
        final Configuration newOverrideConfig = new Configuration(currentOverrideConfig);
        newOverrideConfig.densityDpi += 120;
        newOverrideConfig.fontScale += 0.3;

        mWm.setNewDisplayOverrideConfiguration(newOverrideConfig, mDisplayContent);

        // Check that override config is applied.
        assertEquals(newOverrideConfig, mDisplayContent.getRequestedOverrideConfiguration());
    }

    /**
     * This tests global configuration updates when default display config is updated.
     */
    @Test
    public void testDefaultDisplayOverrideConfigUpdate() {
        DisplayContent defaultDisplay = mWm.mRoot.getDisplayContent(DEFAULT_DISPLAY);
        final Configuration currentConfig = defaultDisplay.getConfiguration();

        // Create new, slightly changed override configuration and apply it to the display.
        final Configuration newOverrideConfig = new Configuration(currentConfig);
        newOverrideConfig.densityDpi += 120;
        newOverrideConfig.fontScale += 0.3;

        mWm.setNewDisplayOverrideConfiguration(newOverrideConfig, defaultDisplay);

        // Check that global configuration is updated, as we've updated default display's config.
        Configuration globalConfig = mWm.mRoot.getConfiguration();
        assertEquals(newOverrideConfig.densityDpi, globalConfig.densityDpi);
        assertEquals(newOverrideConfig.fontScale, globalConfig.fontScale, 0.1 /* delta */);

        // Return back to original values.
        mWm.setNewDisplayOverrideConfiguration(currentConfig, defaultDisplay);
        globalConfig = mWm.mRoot.getConfiguration();
        assertEquals(currentConfig.densityDpi, globalConfig.densityDpi);
        assertEquals(currentConfig.fontScale, globalConfig.fontScale, 0.1 /* delta */);
    }

    /**
     * Tests tapping on a stack in different display results in window gaining focus.
     */
    @Test
    public void testInputEventBringsCorrectDisplayInFocus() {
        DisplayContent dc0 = mWm.getDefaultDisplayContentLocked();
        // Create a second display
        final DisplayContent dc1 = createNewDisplay();

        // Add stack with activity.
        final Task stack0 = createTaskStackOnDisplay(dc0);
        final Task task0 = createTaskInStack(stack0, 0 /* userId */);
        final ActivityRecord activity = createNonAttachedActivityRecord(dc0);
        task0.addChild(activity, 0);
        dc0.configureDisplayPolicy();
        assertNotNull(dc0.mTapDetector);

        final Task stack1 = createTaskStackOnDisplay(dc1);
        final Task task1 = createTaskInStack(stack1, 0 /* userId */);
        final ActivityRecord activity1 = createNonAttachedActivityRecord(dc0);
        task1.addChild(activity1, 0);
        dc1.configureDisplayPolicy();
        assertNotNull(dc1.mTapDetector);

        // tap on primary display.
        tapOnDisplay(dc0);
        // Check focus is on primary display.
        assertEquals(mWm.mRoot.getTopFocusedDisplayContent().mCurrentFocus,
                dc0.findFocusedWindow());

        // Tap on secondary display.
        tapOnDisplay(dc1);
        // Check focus is on secondary.
        assertEquals(mWm.mRoot.getTopFocusedDisplayContent().mCurrentFocus,
                dc1.findFocusedWindow());
    }

    @Test
    public void testFocusedWindowMultipleDisplays() {
        doTestFocusedWindowMultipleDisplays(false /* perDisplayFocusEnabled */, Q);
    }

    @Test
    public void testFocusedWindowMultipleDisplaysPerDisplayFocusEnabled() {
        doTestFocusedWindowMultipleDisplays(true /* perDisplayFocusEnabled */, Q);
    }

    @Test
    public void testFocusedWindowMultipleDisplaysPerDisplayFocusEnabledLegacyApp() {
        doTestFocusedWindowMultipleDisplays(true /* perDisplayFocusEnabled */, P);
    }

    private void doTestFocusedWindowMultipleDisplays(boolean perDisplayFocusEnabled,
            int targetSdk) {
        mWm.mPerDisplayFocusEnabled = perDisplayFocusEnabled;

        // Create a focusable window and check that focus is calculated correctly
        final WindowState window1 =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "window1");
        window1.mActivityRecord.mTargetSdk = targetSdk;
        updateFocusedWindow();
        assertTrue(window1.isFocused());
        assertEquals(window1, mWm.mRoot.getTopFocusedDisplayContent().mCurrentFocus);

        // Check that a new display doesn't affect focus
        final DisplayContent dc = createNewDisplay();
        updateFocusedWindow();
        assertTrue(window1.isFocused());
        assertEquals(window1, mWm.mRoot.getTopFocusedDisplayContent().mCurrentFocus);

        // Add a window to the second display, and it should be focused
        final WindowState window2 = createWindow(null, TYPE_BASE_APPLICATION, dc, "window2");
        window2.mActivityRecord.mTargetSdk = targetSdk;
        updateFocusedWindow();
        assertTrue(window2.isFocused());
        assertEquals(perDisplayFocusEnabled && targetSdk >= Q, window1.isFocused());
        assertEquals(window2, mWm.mRoot.getTopFocusedDisplayContent().mCurrentFocus);

        // Move the first window to top including parents, and make sure focus is updated
        window1.getParent().positionChildAt(POSITION_TOP, window1, true);
        updateFocusedWindow();
        assertTrue(window1.isFocused());
        assertEquals(perDisplayFocusEnabled && targetSdk >= Q, window2.isFocused());
        assertEquals(window1, mWm.mRoot.getTopFocusedDisplayContent().mCurrentFocus);

        // Make sure top focused display not changed if there is a focused app.
        window1.mActivityRecord.mVisibleRequested = false;
        window1.getDisplayContent().setFocusedApp(window1.mActivityRecord);
        updateFocusedWindow();
        assertTrue(!window1.isFocused());
        assertEquals(window1.getDisplayId(),
                mWm.mRoot.getTopFocusedDisplayContent().getDisplayId());
    }

    @Test
    public void testShouldWaitForSystemDecorWindowsOnBoot_OnDefaultDisplay() {
        mWm.mSystemBooted = true;
        final DisplayContent defaultDisplay = mWm.getDefaultDisplayContentLocked();
        final WindowState[] windows = createNotDrawnWindowsOn(defaultDisplay,
                TYPE_WALLPAPER, TYPE_APPLICATION);
        final WindowState wallpaper = windows[0];
        assertTrue(wallpaper.mIsWallpaper);
        // By default WindowState#mWallpaperVisible is false.
        assertFalse(wallpaper.isVisible());

        // Verify waiting for windows to be drawn.
        assertTrue(defaultDisplay.shouldWaitForSystemDecorWindowsOnBoot());

        // Verify not waiting for drawn window and invisible wallpaper.
        setDrawnState(WindowStateAnimator.READY_TO_SHOW, wallpaper);
        setDrawnState(WindowStateAnimator.HAS_DRAWN, windows[1]);
        assertFalse(defaultDisplay.shouldWaitForSystemDecorWindowsOnBoot());
    }

    @Test
    public void testShouldWaitForSystemDecorWindowsOnBoot_OnSecondaryDisplay() {
        mWm.mSystemBooted = true;
        final DisplayContent secondaryDisplay = createNewDisplay();
        final WindowState[] windows = createNotDrawnWindowsOn(secondaryDisplay,
                TYPE_WALLPAPER, TYPE_APPLICATION);

        // Verify not waiting for display without system decorations.
        doReturn(false).when(secondaryDisplay).supportsSystemDecorations();
        assertFalse(secondaryDisplay.shouldWaitForSystemDecorWindowsOnBoot());

        // Verify waiting for non-drawn windows on display with system decorations.
        reset(secondaryDisplay);
        doReturn(true).when(secondaryDisplay).supportsSystemDecorations();
        assertTrue(secondaryDisplay.shouldWaitForSystemDecorWindowsOnBoot());

        // Verify not waiting for drawn windows on display with system decorations.
        setDrawnState(WindowStateAnimator.HAS_DRAWN, windows);
        assertFalse(secondaryDisplay.shouldWaitForSystemDecorWindowsOnBoot());
    }

    @Test
    public void testImeIsAttachedToDisplayForLetterboxedApp() {
        final DisplayContent dc = mDisplayContent;
        final WindowState ws = createWindow(null, TYPE_APPLICATION, dc, "app window");
        dc.setImeLayeringTarget(ws);

        // Adjust bounds so that matchesRootDisplayAreaBounds() returns false and
        // hence isLetterboxedAppWindow() returns true.
        ws.mActivityRecord.getConfiguration().windowConfiguration.setBounds(new Rect(1, 1, 1, 1));
        assertFalse("matchesRootDisplayAreaBounds() should return false",
                ws.matchesDisplayAreaBounds());
        assertTrue("isLetterboxedAppWindow() should return true", ws.isLetterboxedAppWindow());
        assertTrue("IME shouldn't be attached to app",
                dc.computeImeParent() != dc.getImeTarget(IME_TARGET_LAYERING).getWindow()
                        .mActivityRecord.getSurfaceControl());
        assertEquals("IME should be attached to display",
                dc.getImeContainer().getParent().getSurfaceControl(), dc.computeImeParent());
    }

    private WindowState[] createNotDrawnWindowsOn(DisplayContent displayContent, int... types) {
        final WindowState[] windows = new WindowState[types.length];
        for (int i = 0; i < types.length; i++) {
            final int type = types[i];
            windows[i] = createWindow(null /* parent */, type, displayContent, "window-" + type);
            windows[i].setHasSurface(true);
            windows[i].mWinAnimator.mDrawState = WindowStateAnimator.DRAW_PENDING;
        }
        return windows;
    }

    private static void setDrawnState(int state, WindowState... windows) {
        for (WindowState window : windows) {
            window.mHasSurface = state != WindowStateAnimator.NO_SURFACE;
            window.mWinAnimator.mDrawState = state;
        }
    }

    /**
     * This tests setting the maximum ui width on a display.
     */
    @Test
    public void testMaxUiWidth() {
        // Prevent base display metrics for test from being updated to the value of real display.
        final DisplayContent displayContent = createDisplayNoUpdateDisplayInfo();
        final int baseWidth = 1440;
        final int baseHeight = 2560;
        final int baseDensity = 300;

        displayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity);

        final int maxWidth = 300;
        final int resultingHeight = (maxWidth * baseHeight) / baseWidth;
        final int resultingDensity = baseDensity;

        displayContent.setMaxUiWidth(maxWidth);
        verifySizes(displayContent, maxWidth, resultingHeight, resultingDensity);

        // Assert setting values again does not change;
        displayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity);
        verifySizes(displayContent, maxWidth, resultingHeight, resultingDensity);

        final int smallerWidth = 200;
        final int smallerHeight = 400;
        final int smallerDensity = 100;

        // Specify smaller dimension, verify that it is honored
        displayContent.updateBaseDisplayMetrics(smallerWidth, smallerHeight, smallerDensity);
        verifySizes(displayContent, smallerWidth, smallerHeight, smallerDensity);

        // Verify that setting the max width to a greater value than the base width has no effect
        displayContent.setMaxUiWidth(maxWidth);
        verifySizes(displayContent, smallerWidth, smallerHeight, smallerDensity);
    }

    @Test
    public void testSetForcedSize() {
        // Prevent base display metrics for test from being updated to the value of real display.
        final DisplayContent displayContent = createDisplayNoUpdateDisplayInfo();
        final int baseWidth = 1280;
        final int baseHeight = 720;
        final int baseDensity = 320;

        displayContent.mInitialDisplayWidth = baseWidth;
        displayContent.mInitialDisplayHeight = baseHeight;
        displayContent.mInitialDisplayDensity = baseDensity;
        displayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity);

        final int forcedWidth = 1920;
        final int forcedHeight = 1080;

        // Verify that forcing the size is honored and the density doesn't change.
        displayContent.setForcedSize(forcedWidth, forcedHeight);
        verifySizes(displayContent, forcedWidth, forcedHeight, baseDensity);

        // Verify that forcing the size is idempotent.
        displayContent.setForcedSize(forcedWidth, forcedHeight);
        verifySizes(displayContent, forcedWidth, forcedHeight, baseDensity);
    }

    @Test
    public void testSetForcedSize_WithMaxUiWidth() {
        // Prevent base display metrics for test from being updated to the value of real display.
        final DisplayContent displayContent = createDisplayNoUpdateDisplayInfo();
        final int baseWidth = 1280;
        final int baseHeight = 720;
        final int baseDensity = 320;

        displayContent.mInitialDisplayWidth = baseWidth;
        displayContent.mInitialDisplayHeight = baseHeight;
        displayContent.mInitialDisplayDensity = baseDensity;
        displayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity);

        displayContent.setMaxUiWidth(baseWidth);

        final int forcedWidth = 1920;
        final int forcedHeight = 1080;

        // Verify that forcing bigger size doesn't work and density doesn't change.
        displayContent.setForcedSize(forcedWidth, forcedHeight);
        verifySizes(displayContent, baseWidth, baseHeight, baseDensity);

        // Verify that forcing the size is idempotent.
        displayContent.setForcedSize(forcedWidth, forcedHeight);
        verifySizes(displayContent, baseWidth, baseHeight, baseDensity);
    }

    @Test
    public void testDisplayCutout_rot0() {
        final DisplayContent dc = createNewDisplay();
        dc.mInitialDisplayWidth = 200;
        dc.mInitialDisplayHeight = 400;
        final Rect r = new Rect(80, 0, 120, 10);
        final DisplayCutout cutout = new WmDisplayCutout(
                fromBoundingRect(r.left, r.top, r.right, r.bottom, BOUNDS_POSITION_TOP), null)
                        .computeSafeInsets(200, 400).getDisplayCutout();

        dc.mInitialDisplayCutout = cutout;
        dc.getDisplayRotation().setRotation(Surface.ROTATION_0);
        dc.computeScreenConfiguration(new Configuration()); // recomputes dc.mDisplayInfo.

        assertEquals(cutout, dc.getDisplayInfo().displayCutout);
    }

    @Test
    public void testDisplayCutout_rot90() {
        // Prevent mInitialDisplayCutout from being updated from real display (e.g. null
        // if the device has no cutout).
        final DisplayContent dc = createDisplayNoUpdateDisplayInfo();
        // This test assumes it's a top cutout on a portrait display, so if it happens to be a
        // landscape display let's rotate it.
        if (dc.mInitialDisplayHeight < dc.mInitialDisplayWidth) {
            int tmp = dc.mInitialDisplayHeight;
            dc.mInitialDisplayHeight = dc.mInitialDisplayWidth;
            dc.mInitialDisplayWidth = tmp;
        }
        // Rotation may use real display info to compute bound, so here also uses the
        // same width and height.
        final int displayWidth = dc.mInitialDisplayWidth;
        final int displayHeight = dc.mInitialDisplayHeight;
        final float density = dc.mInitialDisplayDensity;
        final int cutoutWidth = 40;
        final int cutoutHeight = 10;
        final int left = (displayWidth - cutoutWidth) / 2;
        final int top = 0;
        final int right = (displayWidth + cutoutWidth) / 2;
        final int bottom = cutoutHeight;

        final Rect zeroRect = new Rect();
        final Rect[] bounds = new Rect[]{zeroRect, new Rect(left, top, right, bottom), zeroRect,
                zeroRect};
        final DisplayCutout.CutoutPathParserInfo info = new DisplayCutout.CutoutPathParserInfo(
                displayWidth, displayHeight, density, "", Surface.ROTATION_0, 1f);
        final DisplayCutout cutout = new WmDisplayCutout(
                DisplayCutout.constructDisplayCutout(bounds, Insets.NONE, info), null)
                        .computeSafeInsets(displayWidth, displayHeight).getDisplayCutout();

        dc.mInitialDisplayCutout = cutout;
        dc.getDisplayRotation().setRotation(Surface.ROTATION_90);
        dc.computeScreenConfiguration(new Configuration()); // recomputes dc.mDisplayInfo.

        // ----o----------      -------------
        // |   |     |   |      |
        // |   ------o   |      o---
        // |             |      |  |
        // |             |  ->  |  |
        // |             |      ---o
        // |             |      |
        // |             |      -------------
        final Rect[] bounds90 = new Rect[]{new Rect(top, left, bottom, right), zeroRect, zeroRect,
                zeroRect};
        final DisplayCutout.CutoutPathParserInfo info90 = new DisplayCutout.CutoutPathParserInfo(
                displayWidth, displayHeight, density, "", Surface.ROTATION_90, 1f);
        assertEquals(new WmDisplayCutout(
                        DisplayCutout.constructDisplayCutout(bounds90, Insets.NONE, info90), null)
                        .computeSafeInsets(displayHeight, displayWidth).getDisplayCutout(),
                dc.getDisplayInfo().displayCutout);
    }

    @Test
    public void testLayoutSeq_assignedDuringLayout() {
        final DisplayContent dc = createNewDisplay();
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, dc, "w");

        performLayout(dc);

        assertThat(win.mLayoutSeq, is(dc.mLayoutSeq));
    }

    @Test
    @SuppressLint("InlinedApi")
    public void testOrientationDefinedByKeyguard() {
        final DisplayContent dc = createNewDisplay();

        // When display content is created its configuration is not yet initialized, which could
        // cause unnecessary configuration propagation, so initialize it here.
        final Configuration config = new Configuration();
        dc.computeScreenConfiguration(config);
        dc.onRequestedOverrideConfigurationChanged(config);

        // Create a window that requests landscape orientation. It will define device orientation
        // by default.
        final WindowState window = createWindow(null /* parent */, TYPE_BASE_APPLICATION, dc, "w");
        window.mActivityRecord.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        final WindowState keyguard = createWindow(null, TYPE_NOTIFICATION_SHADE , dc, "keyguard");
        keyguard.mHasSurface = true;
        keyguard.mAttrs.screenOrientation = SCREEN_ORIENTATION_UNSPECIFIED;

        assertEquals("Screen orientation must be defined by the app window by default",
                SCREEN_ORIENTATION_LANDSCAPE, dc.getOrientation());

        keyguard.mAttrs.screenOrientation = SCREEN_ORIENTATION_PORTRAIT;
        assertEquals("Visible keyguard must influence device orientation",
                SCREEN_ORIENTATION_PORTRAIT, dc.getOrientation());

        mWm.setKeyguardGoingAway(true);
        assertEquals("Keyguard that is going away must not influence device orientation",
                SCREEN_ORIENTATION_LANDSCAPE, dc.getOrientation());
    }

    @Test
    public void testOrientationForAspectRatio() {
        final DisplayContent dc = createNewDisplay();

        // When display content is created its configuration is not yet initialized, which could
        // cause unnecessary configuration propagation, so initialize it here.
        final Configuration config = new Configuration();
        dc.computeScreenConfiguration(config);
        dc.onRequestedOverrideConfigurationChanged(config);

        // Create a window that requests a fixed orientation. It will define device orientation
        // by default.
        final WindowState window = createWindow(null /* parent */, TYPE_APPLICATION_OVERLAY, dc,
                "window");
        window.mHasSurface = true;
        window.mAttrs.screenOrientation = SCREEN_ORIENTATION_LANDSCAPE;

        // --------------------------------
        // Test non-close-to-square display
        // --------------------------------
        dc.mBaseDisplayWidth = 1000;
        dc.mBaseDisplayHeight = (int) (dc.mBaseDisplayWidth * dc.mCloseToSquareMaxAspectRatio * 2f);
        dc.configureDisplayPolicy();

        assertEquals("Screen orientation must be defined by the window by default.",
                window.mAttrs.screenOrientation, dc.getOrientation());

        // ----------------------------
        // Test close-to-square display - should be handled in the same way
        // ----------------------------
        dc.mBaseDisplayHeight = dc.mBaseDisplayWidth;
        dc.configureDisplayPolicy();

        assertEquals(
                "Screen orientation must be defined by the window even on close-to-square display.",
                window.mAttrs.screenOrientation, dc.getOrientation());
    }

    @Test
    public void testDisableDisplayInfoOverrideFromWindowManager() {
        final DisplayContent dc = createNewDisplay();

        assertTrue(dc.mShouldOverrideDisplayConfiguration);
        mWm.dontOverrideDisplayInfo(dc.getDisplayId());

        assertFalse(dc.mShouldOverrideDisplayConfiguration);
        verify(mWm.mDisplayManagerInternal, times(1))
                .setDisplayInfoOverrideFromWindowManager(dc.getDisplayId(), null);
    }

    @UseTestDisplay
    @Test
    public void testClearLastFocusWhenReparentingFocusedWindow() {
        final DisplayContent defaultDisplay = mWm.getDefaultDisplayContentLocked();
        final WindowState window = createWindow(null /* parent */, TYPE_BASE_APPLICATION,
                defaultDisplay, "window");
        defaultDisplay.mLastFocus = window;
        mDisplayContent.mCurrentFocus = window;
        mDisplayContent.reParentWindowToken(window.mToken);

        assertNull(defaultDisplay.mLastFocus);
    }

    @Test
    public void testGetPreferredOptionsPanelGravityFromDifferentDisplays() {
        final DisplayContent portraitDisplay = createNewDisplay();
        portraitDisplay.mInitialDisplayHeight = 2000;
        portraitDisplay.mInitialDisplayWidth = 1000;

        portraitDisplay.getDisplayRotation().setRotation(Surface.ROTATION_0);
        assertFalse(isOptionsPanelAtRight(portraitDisplay.getDisplayId()));
        portraitDisplay.getDisplayRotation().setRotation(ROTATION_90);
        assertTrue(isOptionsPanelAtRight(portraitDisplay.getDisplayId()));

        final DisplayContent landscapeDisplay = createNewDisplay();
        landscapeDisplay.mInitialDisplayHeight = 1000;
        landscapeDisplay.mInitialDisplayWidth = 2000;

        landscapeDisplay.getDisplayRotation().setRotation(Surface.ROTATION_0);
        assertTrue(isOptionsPanelAtRight(landscapeDisplay.getDisplayId()));
        landscapeDisplay.getDisplayRotation().setRotation(ROTATION_90);
        assertFalse(isOptionsPanelAtRight(landscapeDisplay.getDisplayId()));
    }

    @UseTestDisplay(addWindows = W_INPUT_METHOD)
    @Test
    public void testInputMethodTargetUpdateWhenSwitchingOnDisplays() {
        final DisplayContent newDisplay = createNewDisplay();

        final WindowState appWin = createWindow(null, TYPE_APPLICATION, mDisplayContent, "appWin");
        final Task stack = mDisplayContent.getTopRootTask();
        final ActivityRecord activity = stack.topRunningActivity();
        doReturn(true).when(activity).shouldBeVisibleUnchecked();

        final WindowState appWin1 = createWindow(null, TYPE_APPLICATION, newDisplay, "appWin1");
        final Task stack1 = newDisplay.getTopRootTask();
        final ActivityRecord activity1 = stack1.topRunningActivity();
        doReturn(true).when(activity1).shouldBeVisibleUnchecked();
        appWin.setHasSurface(true);
        appWin1.setHasSurface(true);

        // Set current input method window on default display, make sure the input method target
        // is appWin & null on the other display.
        mDisplayContent.setInputMethodWindowLocked(mImeWindow);
        newDisplay.setInputMethodWindowLocked(null);
        assertEquals("appWin should be IME target window",
                appWin, mDisplayContent.getImeTarget(IME_TARGET_LAYERING));
        assertNull("newDisplay Ime target: ", newDisplay.getImeTarget(IME_TARGET_LAYERING));

        // Switch input method window on new display & make sure the input method target also
        // switched as expected.
        newDisplay.setInputMethodWindowLocked(mImeWindow);
        mDisplayContent.setInputMethodWindowLocked(null);
        assertEquals("appWin1 should be IME target window", appWin1,
                newDisplay.getImeTarget(IME_TARGET_LAYERING));
        assertNull("default display Ime target: ",
                mDisplayContent.getImeTarget(IME_TARGET_LAYERING));
    }

    @UseTestDisplay(addWindows = W_INPUT_METHOD)
    @Test
    public void testInputMethodSet_listenOnDisplayAreaConfigurationChanged() {
        spyOn(mAtm);
        mDisplayContent.setInputMethodWindowLocked(mImeWindow);

        verify(mAtm).onImeWindowSetOnDisplayArea(
                mImeWindow.mSession.mPid, mDisplayContent.getImeContainer());
    }

    @Test
    public void testAllowsTopmostFullscreenOrientation() {
        final DisplayContent dc = createNewDisplay();
        dc.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_DISABLED);

        final Task stack = new TaskBuilder(mSupervisor)
                .setDisplay(dc)
                .setCreateActivity(true)
                .build();
        doReturn(true).when(stack).isVisible();

        final Task freeformStack = new TaskBuilder(mSupervisor)
                .setDisplay(dc)
                .setCreateActivity(true)
                .setWindowingMode(WINDOWING_MODE_FREEFORM)
                .build();
        doReturn(true).when(freeformStack).isVisible();
        freeformStack.getTopChild().setBounds(100, 100, 300, 400);

        assertTrue(dc.getDefaultTaskDisplayArea().isRootTaskVisible(WINDOWING_MODE_FREEFORM));

        freeformStack.getTopNonFinishingActivity().setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        stack.getTopNonFinishingActivity().setOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, dc.getOrientation());

        stack.getTopNonFinishingActivity().setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        freeformStack.getTopNonFinishingActivity().setOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, dc.getOrientation());
    }

    @Test
    public void testOnDescendantOrientationRequestChanged() {
        final DisplayContent dc = createNewDisplay();
        dc.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_DISABLED);
        final int newOrientation = getRotatedOrientation(dc);

        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(dc).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopMostTask().getTopNonFinishingActivity();
        dc.setFocusedApp(activity);

        activity.setRequestedOrientation(newOrientation);

        assertTrue("The display should be rotated.", dc.getRotation() % 2 == 1);
    }

    @Test
    public void testOnDescendantOrientationRequestChanged_FrozenToUserRotation() {
        final DisplayContent dc = createNewDisplay();
        dc.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_ENABLED);
        dc.getDisplayRotation().setUserRotation(
                WindowManagerPolicy.USER_ROTATION_LOCKED, ROTATION_180);
        final int newOrientation = getRotatedOrientation(dc);

        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(dc).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopMostTask().getTopNonFinishingActivity();
        dc.setFocusedApp(activity);

        activity.setRequestedOrientation(newOrientation);

        verify(dc, never()).updateDisplayOverrideConfigurationLocked(any(), eq(activity),
                anyBoolean(), same(null));
        assertEquals(ROTATION_180, dc.getRotation());
    }

    @Test
    public void testFixedToUserRotationChanged() {
        final DisplayContent dc = createNewDisplay();
        dc.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_ENABLED);
        dc.getDisplayRotation().setUserRotation(
                WindowManagerPolicy.USER_ROTATION_LOCKED, ROTATION_0);
        final int newOrientation = getRotatedOrientation(dc);

        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(dc).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopMostTask().getTopNonFinishingActivity();
        dc.setFocusedApp(activity);

        activity.setRequestedOrientation(newOrientation);

        dc.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_DISABLED);

        assertTrue("The display should be rotated.", dc.getRotation() % 2 == 1);
    }

    @Test
    public void testComputeImeParent_app() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.setImeLayeringTarget(createWindow(null, TYPE_BASE_APPLICATION, "app"));
        dc.setImeInputTarget(dc.getImeTarget(IME_TARGET_LAYERING).getWindow());
        assertEquals(dc.getImeTarget(IME_TARGET_LAYERING).getWindow()
                        .mActivityRecord.getSurfaceControl(), dc.computeImeParent());
    }

    @Test
    public void testComputeImeParent_app_notFullscreen() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.setImeLayeringTarget(createWindow(null, TYPE_STATUS_BAR, "app"));
        dc.getImeTarget(IME_TARGET_LAYERING).getWindow().setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertEquals(dc.getImeContainer().getParentSurfaceControl(), dc.computeImeParent());
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testComputeImeParent_app_notMatchParentBounds() {
        spyOn(mAppWindow.mActivityRecord);
        doReturn(false).when(mAppWindow.mActivityRecord).matchParentBounds();
        mDisplayContent.setImeLayeringTarget(mAppWindow);
        // The surface parent of IME should be the display instead of app window.
        assertEquals(mDisplayContent.getImeContainer().getParentSurfaceControl(),
                mDisplayContent.computeImeParent());
    }

    @Test
    public void testComputeImeParent_noApp() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.setImeLayeringTarget(createWindow(null, TYPE_STATUS_BAR, "statusBar"));
        assertEquals(dc.getImeContainer().getParentSurfaceControl(), dc.computeImeParent());
    }

    @Test
    public void testInputMethodInputTarget_isClearedWhenWindowStateIsRemoved() throws Exception {
        final DisplayContent dc = createNewDisplay();

        WindowState app = createWindow(null, TYPE_BASE_APPLICATION, dc, "app");

        dc.setImeInputTarget(app);
        assertEquals(app, dc.computeImeControlTarget());

        app.removeImmediately();

        assertNull(dc.getImeTarget(IME_TARGET_INPUT));
        assertNull(dc.computeImeControlTarget());
    }

    @Test
    public void testComputeImeControlTarget() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.setRemoteInsetsController(createDisplayWindowInsetsController());
        dc.setImeInputTarget(createWindow(null, TYPE_BASE_APPLICATION, "app"));
        dc.setImeLayeringTarget(dc.getImeTarget(IME_TARGET_INPUT).getWindow());
        assertEquals(dc.getImeTarget(IME_TARGET_INPUT).getWindow(), dc.computeImeControlTarget());
    }

    @Test
    public void testComputeImeControlTarget_splitscreen() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.setImeInputTarget(createWindow(null, TYPE_BASE_APPLICATION, "app"));
        dc.getImeTarget(IME_TARGET_INPUT).getWindow().setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        dc.setImeLayeringTarget(dc.getImeTarget(IME_TARGET_INPUT).getWindow());
        dc.setRemoteInsetsController(createDisplayWindowInsetsController());
        assertNotEquals(dc.getImeTarget(IME_TARGET_INPUT).getWindow(),
                dc.computeImeControlTarget());
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testComputeImeControlTarget_notMatchParentBounds() throws Exception {
        spyOn(mAppWindow.mActivityRecord);
        doReturn(false).when(mAppWindow.mActivityRecord).matchParentBounds();
        mDisplayContent.setImeInputTarget(mAppWindow);
        mDisplayContent.setImeLayeringTarget(
                mDisplayContent.getImeTarget(IME_TARGET_INPUT).getWindow());
        mDisplayContent.setRemoteInsetsController(createDisplayWindowInsetsController());
        assertEquals(mAppWindow, mDisplayContent.computeImeControlTarget());
    }

    @Test
    public void testUpdateSystemGestureExclusion() throws Exception {
        final DisplayContent dc = createNewDisplay();
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, dc, "win");
        win.getAttrs().flags |= FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        win.setSystemGestureExclusion(Collections.singletonList(new Rect(10, 20, 30, 40)));

        performLayout(dc);

        win.setHasSurface(true);
        dc.updateSystemGestureExclusion();

        final boolean[] invoked = { false };
        final ISystemGestureExclusionListener.Stub verifier =
                new ISystemGestureExclusionListener.Stub() {
            @Override
            public void onSystemGestureExclusionChanged(int displayId, Region actual,
                    Region unrestricted) {
                Region expected = Region.obtain();
                expected.set(10, 20, 30, 40);
                assertEquals(expected, actual);
                invoked[0] = true;
            }
        };
        try {
            dc.registerSystemGestureExclusionListener(verifier);
        } finally {
            dc.unregisterSystemGestureExclusionListener(verifier);
        }
        assertTrue("SystemGestureExclusionListener was not invoked", invoked[0]);
    }

    @Test
    public void testCalculateSystemGestureExclusion() throws Exception {
        final DisplayContent dc = createNewDisplay();
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, dc, "win");
        win.getAttrs().flags |= FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        win.setSystemGestureExclusion(Collections.singletonList(new Rect(10, 20, 30, 40)));

        final WindowState win2 = createWindow(null, TYPE_APPLICATION, dc, "win2");
        win2.getAttrs().flags |= FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        win2.setSystemGestureExclusion(Collections.singletonList(new Rect(20, 30, 40, 50)));

        performLayout(dc);

        win.setHasSurface(true);
        win2.setHasSurface(true);

        final Region expected = Region.obtain();
        expected.set(20, 30, 40, 50);
        assertEquals(expected, calculateSystemGestureExclusion(dc));
    }

    private Region calculateSystemGestureExclusion(DisplayContent dc) {
        Region out = Region.obtain();
        Region unrestricted = Region.obtain();
        dc.calculateSystemGestureExclusion(out, unrestricted);
        return out;
    }

    @Test
    public void testCalculateSystemGestureExclusion_modal() throws Exception {
        final DisplayContent dc = createNewDisplay();
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, dc, "base");
        win.getAttrs().flags |= FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        win.setSystemGestureExclusion(Collections.singletonList(new Rect(0, 0, 1000, 1000)));

        final WindowState win2 = createWindow(null, TYPE_APPLICATION, dc, "modal");
        win2.getAttrs().flags |= FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        win2.getAttrs().privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION;
        win2.getAttrs().width = 10;
        win2.getAttrs().height = 10;
        win2.setSystemGestureExclusion(Collections.emptyList());

        performLayout(dc);

        win.setHasSurface(true);
        win2.setHasSurface(true);

        final Region expected = Region.obtain();
        assertEquals(expected, calculateSystemGestureExclusion(dc));
    }

    @Test
    public void testCalculateSystemGestureExclusion_immersiveStickyLegacyWindow() throws Exception {
        mWm.mConstants.mSystemGestureExcludedByPreQStickyImmersive = true;

        final DisplayContent dc = createNewDisplay();
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, dc, "win");
        win.getAttrs().flags |= FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        win.getAttrs().layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        win.getAttrs().privateFlags |= PRIVATE_FLAG_NO_MOVE_ANIMATION;
        win.getAttrs().insetsFlags.behavior = BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
        final InsetsState requestedState = new InsetsState();
        requestedState.getSource(ITYPE_NAVIGATION_BAR).setVisible(false);
        requestedState.getSource(ITYPE_STATUS_BAR).setVisible(false);
        win.updateRequestedVisibility(requestedState);
        win.mActivityRecord.mTargetSdk = P;

        performLayout(dc);

        win.setHasSurface(true);

        final Region expected = Region.obtain();
        expected.set(dc.getBounds());
        assertEquals(expected, calculateSystemGestureExclusion(dc));

        win.setHasSurface(false);
    }

    @UseTestDisplay(addWindows = { W_ABOVE_ACTIVITY, W_ACTIVITY})
    @Test
    public void testRequestResizeForEmptyFrames() {
        final WindowState win = mChildAppWindowAbove;
        makeWindowVisible(win, win.getParentWindow());
        win.setRequestedSize(mDisplayContent.mBaseDisplayWidth, 0 /* height */);
        win.mAttrs.width = win.mAttrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
        win.mAttrs.gravity = Gravity.CENTER;
        performLayout(mDisplayContent);

        // The frame is empty because the requested height is zero.
        assertTrue(win.getFrame().isEmpty());
        // The window should be scheduled to resize then the client may report a new non-empty size.
        win.updateResizingWindowIfNeeded();
        assertThat(mWm.mResizingWindows).contains(win);
    }

    @Test
    public void testOrientationChangeLogging() {
        MetricsLogger mockLogger = mock(MetricsLogger.class);
        Configuration oldConfig = new Configuration();
        oldConfig.orientation = Configuration.ORIENTATION_LANDSCAPE;

        Configuration newConfig = new Configuration();
        newConfig.orientation = Configuration.ORIENTATION_PORTRAIT;
        final DisplayContent displayContent = createNewDisplay();
        Mockito.doReturn(mockLogger).when(displayContent).getMetricsLogger();
        Mockito.doReturn(oldConfig).doReturn(newConfig).when(displayContent).getConfiguration();
        doNothing().when(displayContent).preOnConfigurationChanged();

        displayContent.onConfigurationChanged(newConfig);

        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        verify(mockLogger).write(logMakerCaptor.capture());
        assertThat(logMakerCaptor.getValue().getCategory(),
                is(MetricsProto.MetricsEvent.ACTION_PHONE_ORIENTATION_CHANGED));
        assertThat(logMakerCaptor.getValue().getSubtype(),
                is(Configuration.ORIENTATION_PORTRAIT));
    }

    @UseTestDisplay(addWindows = { W_ACTIVITY, W_WALLPAPER, W_STATUS_BAR, W_NAVIGATION_BAR })
    @Test
    public void testApplyTopFixedRotationTransform() {
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        // Only non-movable (gesture) navigation bar will be animated by fixed rotation animation.
        doReturn(false).when(displayPolicy).navigationBarCanMove();
        displayPolicy.addWindowLw(mStatusBarWindow, mStatusBarWindow.mAttrs);
        displayPolicy.addWindowLw(mNavBarWindow, mNavBarWindow.mAttrs);
        final Configuration config90 = new Configuration();
        mDisplayContent.computeScreenConfiguration(config90, ROTATION_90);

        final Configuration config = new Configuration();
        mDisplayContent.getDisplayRotation().setRotation(ROTATION_0);
        mDisplayContent.computeScreenConfiguration(config);
        mDisplayContent.onRequestedOverrideConfigurationChanged(config);
        assertNotEquals(config90.windowConfiguration.getMaxBounds(),
                config.windowConfiguration.getMaxBounds());

        final ActivityRecord app = mAppWindow.mActivityRecord;
        app.setVisible(false);
        mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_OPEN);
        mDisplayContent.mOpeningApps.add(app);
        final int newOrientation = getRotatedOrientation(mDisplayContent);
        app.setRequestedOrientation(newOrientation);

        assertTrue(app.isFixedRotationTransforming());
        assertTrue(mDisplayContent.getDisplayRotation().shouldRotateSeamlessly(
                ROTATION_0 /* oldRotation */, ROTATION_90 /* newRotation */,
                false /* forceUpdate */));

        assertNotNull(mDisplayContent.getFixedRotationAnimationController());
        assertTrue(mStatusBarWindow.getParent().isAnimating(WindowContainer.AnimationFlags.PARENTS,
                ANIMATION_TYPE_FIXED_TRANSFORM));
        assertTrue(mNavBarWindow.getParent().isAnimating(WindowContainer.AnimationFlags.PARENTS,
                ANIMATION_TYPE_FIXED_TRANSFORM));

        // If the visibility of insets state is changed, the rotated state should be updated too.
        final InsetsState rotatedState = app.getFixedRotationTransformInsetsState();
        final InsetsState state = mDisplayContent.getInsetsStateController().getRawInsetsState();
        assertEquals(state.getSource(ITYPE_STATUS_BAR).isVisible(),
                rotatedState.getSource(ITYPE_STATUS_BAR).isVisible());
        state.getSource(ITYPE_STATUS_BAR).setVisible(
                !rotatedState.getSource(ITYPE_STATUS_BAR).isVisible());
        mDisplayContent.getInsetsStateController().notifyInsetsChanged();
        assertEquals(state.getSource(ITYPE_STATUS_BAR).isVisible(),
                rotatedState.getSource(ITYPE_STATUS_BAR).isVisible());

        final Rect outFrame = new Rect();
        final Rect outInsets = new Rect();
        final Rect outStableInsets = new Rect();
        final Rect outSurfaceInsets = new Rect();
        mAppWindow.getAnimationFrames(outFrame, outInsets, outStableInsets, outSurfaceInsets);
        // The animation frames should not be rotated because display hasn't rotated.
        assertEquals(mDisplayContent.getBounds(), outFrame);

        // The display should keep current orientation and the rotated configuration should apply
        // to the activity.
        assertEquals(config.orientation, mDisplayContent.getConfiguration().orientation);
        assertEquals(config90.orientation, app.getConfiguration().orientation);
        assertEquals(config90.windowConfiguration.getBounds(), app.getBounds());

        // Make wallaper laid out with the fixed rotation transform.
        final WindowToken wallpaperToken = mWallpaperWindow.mToken;
        wallpaperToken.linkFixedRotationTransform(app);
        mWallpaperWindow.mLayoutNeeded = true;
        performLayout(mDisplayContent);

        // Force the negative offset to verify it can be updated.
        mWallpaperWindow.mWinAnimator.mXOffset = mWallpaperWindow.mWinAnimator.mYOffset = -1;
        assertTrue(mDisplayContent.mWallpaperController.updateWallpaperOffset(mWallpaperWindow,
                false /* sync */));
        assertThat(mWallpaperWindow.mWinAnimator.mXOffset).isGreaterThan(-1);
        assertThat(mWallpaperWindow.mWinAnimator.mYOffset).isGreaterThan(-1);

        // The wallpaper need to animate with transformed position, so its surface position should
        // not be reset.
        final Transaction t = wallpaperToken.getPendingTransaction();
        spyOn(t);
        mWallpaperWindow.mToken.onAnimationLeashCreated(t, null /* leash */);
        verify(t, never()).setPosition(any(), eq(0), eq(0));

        // Launch another activity before the transition is finished.
        final Task task2 = new TaskBuilder(mSupervisor).setDisplay(mDisplayContent).build();
        final ActivityRecord app2 = new ActivityBuilder(mAtm).setTask(task2)
                .setUseProcess(app.app).build();
        app2.setVisible(false);
        mDisplayContent.mOpeningApps.add(app2);
        app2.setRequestedOrientation(newOrientation);

        // The activity should share the same transform state as the existing one. The activity
        // should also be the fixed rotation launching app because it is the latest top.
        assertTrue(app.hasFixedRotationTransform(app2));
        assertTrue(mDisplayContent.isFixedRotationLaunchingApp(app2));

        final Configuration expectedProcConfig = new Configuration(app2.app.getConfiguration());
        expectedProcConfig.windowConfiguration.setActivityType(
                WindowConfiguration.ACTIVITY_TYPE_UNDEFINED);
        assertEquals("The process should receive rotated configuration for compatibility",
                expectedProcConfig, app2.app.getConfiguration());

        // The fixed rotation transform can only be finished when all animation finished.
        doReturn(false).when(app2).isAnimating(anyInt(), anyInt());
        mDisplayContent.mAppTransition.notifyAppTransitionFinishedLocked(app2.token);
        assertTrue(app.hasFixedRotationTransform());
        assertTrue(app2.hasFixedRotationTransform());

        // The display should be rotated after the launch is finished.
        doReturn(false).when(app).isAnimating(anyInt(), anyInt());
        mDisplayContent.mAppTransition.notifyAppTransitionFinishedLocked(app.token);

        // The fixed rotation should be cleared and the new rotation is applied to display.
        assertFalse(app.hasFixedRotationTransform());
        assertFalse(app2.hasFixedRotationTransform());
        assertEquals(config90.orientation, mDisplayContent.getConfiguration().orientation);
        assertNull(mDisplayContent.getFixedRotationAnimationController());
    }

    @Test
    public void testFinishFixedRotationNoAppTransitioningTask() {
        unblockDisplayRotation(mDisplayContent);
        final ActivityRecord app = createActivityRecord(mDisplayContent);
        final Task task = app.getTask();
        final ActivityRecord app2 = new ActivityBuilder(mWm.mAtmService).setTask(task).build();
        mDisplayContent.setFixedRotationLaunchingApp(app2, (mDisplayContent.getRotation() + 1) % 4);
        doReturn(true).when(task).isAppTransitioning();
        // If the task is animating transition, this should be no-op.
        mDisplayContent.mFixedRotationTransitionListener.onAppTransitionFinishedLocked(app.token);

        assertTrue(app2.hasFixedRotationTransform());
        assertTrue(mDisplayContent.hasTopFixedRotationLaunchingApp());

        doReturn(false).when(task).isAppTransitioning();
        // Although this notifies app instead of app2 that uses the fixed rotation, app2 should
        // still finish the transform because there is no more transition event.
        mDisplayContent.mFixedRotationTransitionListener.onAppTransitionFinishedLocked(app.token);

        assertFalse(app2.hasFixedRotationTransform());
        assertFalse(mDisplayContent.hasTopFixedRotationLaunchingApp());
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testRotateSeamlesslyWithFixedRotation() {
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();
        final ActivityRecord app = mAppWindow.mActivityRecord;
        mDisplayContent.setFixedRotationLaunchingAppUnchecked(app);
        mAppWindow.mAttrs.rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_ROTATE;

        // Use seamless rotation if the top app is rotated.
        assertTrue(displayRotation.shouldRotateSeamlessly(ROTATION_0 /* oldRotation */,
                ROTATION_90 /* newRotation */, false /* forceUpdate */));

        mDisplayContent.mFixedRotationTransitionListener.onStartRecentsAnimation(app);

        // Use normal rotation because animating recents is an intermediate state.
        assertFalse(displayRotation.shouldRotateSeamlessly(ROTATION_0 /* oldRotation */,
                ROTATION_90 /* newRotation */, false /* forceUpdate */));
    }

    @Test
    public void testFixedRotationWithPip() {
        final DisplayContent displayContent = mDefaultDisplay;
        unblockDisplayRotation(displayContent);
        // Make resume-top really update the activity state.
        setBooted(mWm.mAtmService);
        // Speed up the test by a few seconds.
        mWm.mAtmService.deferWindowLayout();
        doNothing().when(mWm).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());

        final Configuration displayConfig = displayContent.getConfiguration();
        final ActivityRecord pinnedActivity = createActivityRecord(displayContent,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD);
        final Task pinnedTask = pinnedActivity.getRootTask();
        final ActivityRecord homeActivity = createActivityRecord(displayContent);
        if (displayConfig.orientation == Configuration.ORIENTATION_PORTRAIT) {
            homeActivity.setOrientation(SCREEN_ORIENTATION_PORTRAIT);
            pinnedActivity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            homeActivity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
            pinnedActivity.setOrientation(SCREEN_ORIENTATION_PORTRAIT);
        }
        final int homeConfigOrientation = homeActivity.getRequestedConfigurationOrientation();
        final int pinnedConfigOrientation = pinnedActivity.getRequestedConfigurationOrientation();

        assertEquals(homeConfigOrientation, displayConfig.orientation);

        clearInvocations(mWm);
        // Leave PiP to fullscreen. Simulate the step of PipTaskOrganizer that sets the activity
        // to fullscreen, so fixed rotation will apply on it.
        pinnedActivity.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        homeActivity.setState(Task.ActivityState.STOPPED, "test");

        assertTrue(displayContent.hasTopFixedRotationLaunchingApp());
        verify(mWm, never()).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());
        assertNotEquals(pinnedConfigOrientation, displayConfig.orientation);

        // Assume the animation of PipTaskOrganizer is done and then commit fullscreen to task.
        pinnedTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        displayContent.continueUpdateOrientationForDiffOrienLaunchingApp();
        assertFalse(displayContent.getPinnedTaskController().isPipActiveOrWindowingModeChanging());
        assertEquals(pinnedConfigOrientation, displayConfig.orientation);

        clearInvocations(mWm);
        // Enter PiP from fullscreen. The orientation can be updated from
        // ensure-visibility/resume-focused-stack -> ActivityRecord#makeActiveIfNeeded -> resume.
        pinnedTask.setWindowingMode(WINDOWING_MODE_PINNED);

        assertFalse(displayContent.hasTopFixedRotationLaunchingApp());
        verify(mWm, atLeastOnce()).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());
        assertEquals(homeConfigOrientation, displayConfig.orientation);
        assertTrue(displayContent.getPinnedTaskController().isPipActiveOrWindowingModeChanging());
    }

    @Test
    public void testNoFixedRotationOnResumedScheduledApp() {
        unblockDisplayRotation(mDisplayContent);
        final ActivityRecord app = new ActivityBuilder(mAtm).setCreateTask(true).build();
        app.setVisible(false);
        app.setState(Task.ActivityState.RESUMED, "test");
        mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_OPEN);
        mDisplayContent.mOpeningApps.add(app);
        final int newOrientation = getRotatedOrientation(mDisplayContent);
        app.setRequestedOrientation(newOrientation);

        // The condition should reject using fixed rotation because the resumed client in real case
        // might get display info immediately. And the fixed rotation adjustments haven't arrived
        // client side so the info may be inconsistent with the requested orientation.
        verify(mDisplayContent).handleTopActivityLaunchingInDifferentOrientation(eq(app),
                eq(true) /* checkOpening */);
        assertFalse(app.isFixedRotationTransforming());
        assertFalse(mDisplayContent.hasTopFixedRotationLaunchingApp());
    }

    @Test
    public void testRecentsNotRotatingWithFixedRotation() {
        unblockDisplayRotation(mDisplayContent);
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();
        // Skip freezing so the unrelated conditions in updateRotationUnchecked won't disturb.
        doNothing().when(mWm).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());

        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final ActivityRecord recentsActivity = createActivityRecord(mDisplayContent);
        recentsActivity.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);

        // Do not rotate if the recents animation is animating on top.
        mDisplayContent.mFixedRotationTransitionListener.onStartRecentsAnimation(recentsActivity);
        displayRotation.setRotation((displayRotation.getRotation() + 1) % 4);
        assertFalse(displayRotation.updateRotationUnchecked(false));

        // Rotation can be updated if the recents animation is finished.
        mDisplayContent.mFixedRotationTransitionListener.onFinishRecentsAnimation();
        assertTrue(displayRotation.updateRotationUnchecked(false));

        // Rotation can be updated if the recents animation is animating but it is not on top, e.g.
        // switching activities in different orientations by quickstep gesture.
        mDisplayContent.mFixedRotationTransitionListener.onStartRecentsAnimation(recentsActivity);
        mDisplayContent.setFixedRotationLaunchingAppUnchecked(activity);
        displayRotation.setRotation((displayRotation.getRotation() + 1) % 4);
        assertTrue(displayRotation.updateRotationUnchecked(false));

        // The recents activity should not apply fixed rotation if the top activity is not opaque.
        mDisplayContent.mFocusedApp = activity;
        doReturn(false).when(mDisplayContent.mFocusedApp).occludesParent();
        doReturn(ROTATION_90).when(mDisplayContent).rotationForActivityInDifferentOrientation(
                eq(recentsActivity));
        mDisplayContent.mFixedRotationTransitionListener.onStartRecentsAnimation(recentsActivity);
        assertFalse(recentsActivity.hasFixedRotationTransform());
    }

    @Test
    public void testClearIntermediateFixedRotationAdjustments() throws RemoteException {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        mDisplayContent.setFixedRotationLaunchingApp(activity,
                (mDisplayContent.getRotation() + 1) % 4);
        // Create a window so FixedRotationAdjustmentsItem can be sent.
        createWindow(null, TYPE_APPLICATION_STARTING, activity, "AppWin");
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity2.setVisible(false);
        clearInvocations(mAtm.getLifecycleManager());
        // The first activity has applied fixed rotation but the second activity becomes the top
        // before the transition is done and it has the same rotation as display, so the dispatched
        // rotation adjustment of first activity must be cleared.
        mDisplayContent.handleTopActivityLaunchingInDifferentOrientation(activity2,
                false /* checkOpening */);

        final ArgumentCaptor<FixedRotationAdjustmentsItem> adjustmentsCaptor =
                ArgumentCaptor.forClass(FixedRotationAdjustmentsItem.class);
        verify(mAtm.getLifecycleManager(), atLeastOnce()).scheduleTransaction(
                eq(activity.app.getThread()), adjustmentsCaptor.capture());
        // The transformation is kept for animation in real case.
        assertTrue(activity.hasFixedRotationTransform());
        final FixedRotationAdjustmentsItem clearAdjustments = FixedRotationAdjustmentsItem.obtain(
                activity.token, null /* fixedRotationAdjustments */);
        // The captor may match other items. The first one must be the item to clear adjustments.
        assertEquals(clearAdjustments, adjustmentsCaptor.getAllValues().get(0));
    }

    @Test
    public void testRemoteRotation() {
        DisplayContent dc = createNewDisplay();

        final DisplayRotation dr = dc.getDisplayRotation();
        doCallRealMethod().when(dr).updateRotationUnchecked(anyBoolean());
        // Rotate 180 degree so the display doesn't have configuration change. This condition is
        // used for the later verification of stop-freezing (without setting mWaitingForConfig).
        doReturn((dr.getRotation() + 2) % 4).when(dr).rotationForOrientation(anyInt(), anyInt());
        final boolean[] continued = new boolean[1];
        doAnswer(
                invocation -> {
                    continued[0] = true;
                    return true;
                }).when(dc).updateDisplayOverrideConfigurationLocked();
        final boolean[] called = new boolean[1];
        mWm.mDisplayRotationController =
                new IDisplayWindowRotationController.Stub() {
                    @Override
                    public void onRotateDisplay(int displayId, int fromRotation, int toRotation,
                            IDisplayWindowRotationCallback callback) {
                        called[0] = true;

                        try {
                            callback.continueRotateDisplay(toRotation, null);
                        } catch (RemoteException e) {
                            assertTrue(false);
                        }
                    }
                };

        // kill any existing rotation animation (vestigial from test setup).
        dc.setRotationAnimation(null);

        mWm.updateRotation(true /* alwaysSendConfiguration */, false /* forceRelayout */);
        // If remote rotation is not finished, the display should not be able to unfreeze.
        mWm.stopFreezingDisplayLocked();
        assertTrue(mWm.mDisplayFrozen);

        assertTrue(called[0]);
        waitUntilHandlersIdle();
        assertTrue(continued[0]);

        mWm.stopFreezingDisplayLocked();
        assertFalse(mWm.mDisplayFrozen);
    }

    @Test
    public void testGetOrCreateRootHomeTask_defaultDisplay() {
        TaskDisplayArea defaultTaskDisplayArea = mWm.mRoot.getDefaultTaskDisplayArea();

        // Remove the current home stack if it exists so a new one can be created below.
        Task homeTask = defaultTaskDisplayArea.getRootHomeTask();
        if (homeTask != null) {
            defaultTaskDisplayArea.removeChild(homeTask);
        }
        assertNull(defaultTaskDisplayArea.getRootHomeTask());

        assertNotNull(defaultTaskDisplayArea.getOrCreateRootHomeTask());
    }

    @Test
    public void testGetOrCreateRootHomeTask_supportedSecondaryDisplay() {
        DisplayContent display = createNewDisplay();
        doReturn(true).when(display).supportsSystemDecorations();

        // Remove the current home stack if it exists so a new one can be created below.
        TaskDisplayArea taskDisplayArea = display.getDefaultTaskDisplayArea();
        Task homeTask = taskDisplayArea.getRootHomeTask();
        if (homeTask != null) {
            taskDisplayArea.removeChild(homeTask);
        }
        assertNull(taskDisplayArea.getRootHomeTask());

        assertNotNull(taskDisplayArea.getOrCreateRootHomeTask());
    }

    @Test
    public void testGetOrCreateRootHomeTask_unsupportedSystemDecorations() {
        DisplayContent display = createNewDisplay();
        TaskDisplayArea taskDisplayArea = display.getDefaultTaskDisplayArea();
        doReturn(false).when(display).supportsSystemDecorations();

        assertNull(taskDisplayArea.getRootHomeTask());
        assertNull(taskDisplayArea.getOrCreateRootHomeTask());
    }

    @Test
    public void testGetOrCreateRootHomeTask_untrustedDisplay() {
        DisplayContent display = createNewDisplay();
        TaskDisplayArea taskDisplayArea = display.getDefaultTaskDisplayArea();
        doReturn(false).when(display).isTrusted();

        assertNull(taskDisplayArea.getRootHomeTask());
        assertNull(taskDisplayArea.getOrCreateRootHomeTask());
    }

    @Test
    public void testGetOrCreateRootHomeTask_dontMoveToTop() {
        DisplayContent display = createNewDisplay();
        display.mDontMoveToTop = true;
        TaskDisplayArea taskDisplayArea = display.getDefaultTaskDisplayArea();

        assertNull(taskDisplayArea.getRootHomeTask());
        assertNull(taskDisplayArea.getOrCreateRootHomeTask());
    }

    @Test
    public void testValidWindowingLayer() {
        final SurfaceControl windowingLayer = mDisplayContent.getWindowingLayer();
        assertNotNull(windowingLayer);

        final List<DisplayArea<?>> windowedMagnificationAreas =
                mDisplayContent.mDisplayAreaPolicy.getDisplayAreas(FEATURE_WINDOWED_MAGNIFICATION);
        if (windowedMagnificationAreas != null) {
            assertEquals("There should be only one DisplayArea for FEATURE_WINDOWED_MAGNIFICATION",
                    1, windowedMagnificationAreas.size());
            assertEquals(windowedMagnificationAreas.get(0).mSurfaceControl, windowingLayer);
        } else {
            assertNotEquals(mDisplayContent.mSurfaceControl, windowingLayer);
        }
    }

    @Test
    public void testFindScrollCaptureTargetWindow_behindWindow() {
        DisplayContent display = createNewDisplay();
        Task stack = createTaskStackOnDisplay(display);
        Task task = createTaskInStack(stack, 0 /* userId */);
        WindowState activityWindow = createAppWindow(task, TYPE_APPLICATION, "App Window");
        WindowState behindWindow = createWindow(null, TYPE_SCREENSHOT, display, "Screenshot");

        WindowState result = display.findScrollCaptureTargetWindow(behindWindow,
                ActivityTaskManager.INVALID_TASK_ID);
        assertEquals(activityWindow, result);
    }

    @Test
    public void testFindScrollCaptureTargetWindow_cantReceiveKeys() {
        DisplayContent display = createNewDisplay();
        Task stack = createTaskStackOnDisplay(display);
        Task task = createTaskInStack(stack, 0 /* userId */);
        WindowState activityWindow = createAppWindow(task, TYPE_APPLICATION, "App Window");
        WindowState invisible = createWindow(null, TYPE_APPLICATION, "invisible");
        invisible.mViewVisibility = View.INVISIBLE;  // make canReceiveKeys return false

        WindowState result = display.findScrollCaptureTargetWindow(null,
                ActivityTaskManager.INVALID_TASK_ID);
        assertEquals(activityWindow, result);
    }

    @Test
    public void testFindScrollCaptureTargetWindow_taskId() {
        DisplayContent display = createNewDisplay();
        Task stack = createTaskStackOnDisplay(display);
        Task task = createTaskInStack(stack, 0 /* userId */);
        WindowState window = createAppWindow(task, TYPE_APPLICATION, "App Window");
        WindowState behindWindow = createWindow(null, TYPE_SCREENSHOT, display, "Screenshot");

        WindowState result = display.findScrollCaptureTargetWindow(null, task.mTaskId);
        assertEquals(window, result);
    }

    @Test
    public void testFindScrollCaptureTargetWindow_taskIdCantReceiveKeys() {
        DisplayContent display = createNewDisplay();
        Task stack = createTaskStackOnDisplay(display);
        Task task = createTaskInStack(stack, 0 /* userId */);
        WindowState window = createAppWindow(task, TYPE_APPLICATION, "App Window");
        window.mViewVisibility = View.INVISIBLE;  // make canReceiveKeys return false
        WindowState behindWindow = createWindow(null, TYPE_SCREENSHOT, display, "Screenshot");

        WindowState result = display.findScrollCaptureTargetWindow(null, task.mTaskId);
        assertEquals(window, result);
    }

    @Test
    public void testEnsureActivitiesVisibleNotRecursive() {
        final TaskDisplayArea mockTda = mock(TaskDisplayArea.class);
        final boolean[] called = { false };
        doAnswer(invocation -> {
            // The assertion will fail if DisplayArea#ensureActivitiesVisible is called twice.
            assertFalse(called[0]);
            called[0] = true;
            mDisplayContent.ensureActivitiesVisible(null, 0, false, false);
            return null;
        }).when(mockTda).ensureActivitiesVisible(any(), anyInt(), anyBoolean(), anyBoolean());

        mDisplayContent.ensureActivitiesVisible(null, 0, false, false);
    }

    @Test
    public void testSetWindowingModeAtomicallyUpdatesWindoingModeAndDisplayWindowingMode() {
        final DisplayContent dc = createNewDisplay();
        final Task stack = new TaskBuilder(mSupervisor)
                .setDisplay(dc)
                .build();
        doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            final Configuration config = ((Configuration) args[0]);
            assertEquals(config.windowConfiguration.getWindowingMode(),
                    config.windowConfiguration.getDisplayWindowingMode());
            return null;
        }).when(stack).onConfigurationChanged(any());
        dc.setWindowingMode(WINDOWING_MODE_FREEFORM);
        dc.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
    }

    @Test
    public void testForceDesktopMode() {
        mWm.mForceDesktopModeOnExternalDisplays = true;
        // Not applicable for default display
        assertFalse(mDefaultDisplay.forceDesktopMode());

        // Not applicable for private secondary display.
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        displayInfo.flags = FLAG_PRIVATE;
        final DisplayContent privateDc = createNewDisplay(displayInfo);
        assertFalse(privateDc.forceDesktopMode());

        // Applicable for public secondary display.
        final DisplayContent publicDc = createNewDisplay();
        assertTrue(publicDc.forceDesktopMode());

        // Make sure forceDesktopMode() is false when the force config is disabled.
        mWm.mForceDesktopModeOnExternalDisplays = false;
        assertFalse(publicDc.forceDesktopMode());
    }

    @Test
    public void testDisplaySettingsReappliedWhenDisplayChanged() {
        final DisplayInfo displayInfo = new DisplayInfo();
        displayInfo.copyFrom(mDisplayInfo);
        final DisplayContent dc = createNewDisplay(displayInfo);

        // Generate width/height/density values different from the default of the display.
        final int forcedWidth = dc.mBaseDisplayWidth + 1;
        final int forcedHeight = dc.mBaseDisplayHeight + 1;;
        final int forcedDensity = dc.mBaseDisplayDensity + 1;;
        // Update the forced size and density in settings and the unique id to simualate a display
        // remap.
        dc.mWmService.mDisplayWindowSettings.setForcedSize(dc, forcedWidth, forcedHeight);
        dc.mWmService.mDisplayWindowSettings.setForcedDensity(dc, forcedDensity, 0 /* userId */);
        dc.mCurrentUniqueDisplayId = mDisplayInfo.uniqueId + "-test";
        // Trigger display changed.
        dc.onDisplayChanged();
        // Ensure overridden size and denisty match the most up-to-date values in settings for the
        // display.
        verifySizes(dc, forcedWidth, forcedHeight, forcedDensity);
    }

    @UseTestDisplay(addWindows = { W_ACTIVITY, W_INPUT_METHOD })
    @Test
    public void testComputeImeTarget_shouldNotCheckOutdatedImeTargetLayerWhenRemoved() {
        final WindowState child1 = createWindow(mAppWindow, FIRST_SUB_WINDOW, "child1");
        final WindowState nextImeTargetApp = createWindow(null /* parent */,
                TYPE_BASE_APPLICATION, "nextImeTargetApp");
        spyOn(child1);
        doReturn(true).when(child1).inSplitScreenWindowingMode();
        mDisplayContent.setImeLayeringTarget(child1);

        spyOn(nextImeTargetApp);
        spyOn(mAppWindow);
        doReturn(true).when(nextImeTargetApp).canBeImeTarget();
        doReturn(true).when(nextImeTargetApp).isActivityTypeHome();
        doReturn(false).when(mAppWindow).canBeImeTarget();

        child1.removeImmediately();

        verify(mDisplayContent).computeImeTarget(true);
        assertNull(mDisplayContent.getImeTarget(IME_TARGET_INPUT));
        verify(child1, never()).needsRelativeLayeringToIme();
    }

    @UseTestDisplay(addWindows = {W_INPUT_METHOD}, addAllCommonWindows = true)
    @Test
    public void testAttachAndShowImeScreenshotOnTarget() {
        // Preparation: Simulate screen state is on.
        spyOn(mWm.mPolicy);
        doReturn(true).when(mWm.mPolicy).isScreenOn();

        // Preparation: Simulate snapshot IME surface.
        spyOn(mWm.mTaskSnapshotController);
        doReturn(mock(SurfaceControl.ScreenshotHardwareBuffer.class)).when(
                mWm.mTaskSnapshotController).snapshotImeFromAttachedTask(any());
        final SurfaceControl imeSurface = mock(SurfaceControl.class);
        spyOn(imeSurface);
        doReturn(true).when(imeSurface).isValid();
        doReturn(imeSurface).when(mDisplayContent).createImeSurface(any(), any());

        // Preparation: Simulate snapshot Task.
        ActivityRecord act1 = createActivityRecord(mDisplayContent);
        final WindowState appWin1 = createWindow(null, TYPE_BASE_APPLICATION, act1, "appWin1");
        spyOn(appWin1);
        spyOn(appWin1.mWinAnimator);
        appWin1.setHasSurface(true);
        assertTrue(appWin1.canBeImeTarget());
        doReturn(true).when(appWin1.mWinAnimator).getShown();
        doReturn(true).when(appWin1.mActivityRecord).isSurfaceShowing();
        appWin1.mWinAnimator.mLastAlpha = 1f;

        // Test step 1: appWin1 is the current IME target and soft-keyboard is visible.
        mDisplayContent.computeImeTarget(true);
        assertEquals(appWin1, mDisplayContent.getImeTarget(IME_TARGET_LAYERING));
        spyOn(mDisplayContent.mInputMethodWindow);
        doReturn(true).when(mDisplayContent.mInputMethodWindow).isVisible();
        mDisplayContent.getInsetsStateController().getImeSourceProvider().setImeShowing(true);

        // Test step 2: Simulate launching appWin2 and appWin1 is in app transition.
        ActivityRecord act2 = createActivityRecord(mDisplayContent);
        final WindowState appWin2 = createWindow(null, TYPE_BASE_APPLICATION, act2, "appWin2");
        appWin2.setHasSurface(true);
        assertTrue(appWin2.canBeImeTarget());
        doReturn(true).when(appWin1).isAnimating(PARENTS | TRANSITION,
                ANIMATION_TYPE_APP_TRANSITION | ANIMATION_TYPE_RECENTS);

        // Test step 3: Verify appWin2 will be the next IME target and the IME snapshot surface will
        // be shown at this time.
        final Transaction t = mDisplayContent.getPendingTransaction();
        spyOn(t);
        mDisplayContent.setImeInputTarget(appWin2);
        mDisplayContent.computeImeTarget(true);
        assertEquals(appWin2, mDisplayContent.getImeTarget(IME_TARGET_LAYERING));
        assertTrue(mDisplayContent.isImeAttachedToApp());

        verify(mDisplayContent, atLeast(1)).attachAndShowImeScreenshotOnTarget();
        verify(mWm.mTaskSnapshotController).snapshotImeFromAttachedTask(appWin1.getTask());
        assertNotNull(mDisplayContent.mImeScreenshot);
        verify(t).show(mDisplayContent.mImeScreenshot);
    }

    @Test
    public void testRotateBounds_keepSamePhysicalPosition() {
        final DisplayContent dc =
                new TestDisplayContent.Builder(mAtm, 1000, 2000).build();
        final Rect initBounds = new Rect(0, 0, 700, 1500);
        final Rect rotateBounds = new Rect(initBounds);

        // Rotate from 0 to 0
        dc.rotateBounds(ROTATION_0, ROTATION_0, rotateBounds);

        assertEquals(new Rect(0, 0, 700, 1500), rotateBounds);

        // Rotate from 0 to 90
        rotateBounds.set(initBounds);
        dc.rotateBounds(ROTATION_0, ROTATION_90, rotateBounds);

        assertEquals(new Rect(0, 300, 1500, 1000), rotateBounds);

        // Rotate from 0 to 180
        rotateBounds.set(initBounds);
        dc.rotateBounds(ROTATION_0, ROTATION_180, rotateBounds);

        assertEquals(new Rect(300, 500, 1000, 2000), rotateBounds);

        // Rotate from 0 to 270
        rotateBounds.set(initBounds);
        dc.rotateBounds(ROTATION_0, ROTATION_270, rotateBounds);

        assertEquals(new Rect(500, 0, 2000, 700), rotateBounds);
    }

    private boolean isOptionsPanelAtRight(int displayId) {
        return (mWm.getPreferredOptionsPanelGravity(displayId) & Gravity.RIGHT) == Gravity.RIGHT;
    }

    private static void verifySizes(DisplayContent displayContent, int expectedBaseWidth,
                             int expectedBaseHeight, int expectedBaseDensity) {
        assertEquals(expectedBaseWidth, displayContent.mBaseDisplayWidth);
        assertEquals(expectedBaseHeight, displayContent.mBaseDisplayHeight);
        assertEquals(expectedBaseDensity, displayContent.mBaseDisplayDensity);
    }

    private void updateFocusedWindow() {
        mWm.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /* updateInputWindows */);
    }

    static void performLayout(DisplayContent dc) {
        dc.setLayoutNeeded();
        dc.performLayout(true /* initial */, false /* updateImeWindows */);
    }

    /**
     * Create DisplayContent that does not update display base/initial values from device to keep
     * the values set by test.
     */
    private DisplayContent createDisplayNoUpdateDisplayInfo() {
        final DisplayContent displayContent = createNewDisplay();
        doNothing().when(displayContent).updateDisplayInfo();
        return displayContent;
    }

    private void assertForAllWindowsOrder(List<WindowState> expectedWindowsBottomToTop) {
        final LinkedList<WindowState> actualWindows = new LinkedList<>();

        // Test forward traversal.
        mDisplayContent.forAllWindows(actualWindows::addLast, false /* traverseTopToBottom */);
        assertThat("bottomToTop", actualWindows, is(expectedWindowsBottomToTop));

        actualWindows.clear();

        // Test backward traversal.
        mDisplayContent.forAllWindows(actualWindows::addLast, true /* traverseTopToBottom */);
        assertThat("topToBottom", actualWindows, is(reverseList(expectedWindowsBottomToTop)));
    }

    private static int getRotatedOrientation(DisplayContent dc) {
        return dc.mBaseDisplayWidth > dc.mBaseDisplayHeight
                ? SCREEN_ORIENTATION_PORTRAIT
                : SCREEN_ORIENTATION_LANDSCAPE;
    }

    private static List<WindowState> reverseList(List<WindowState> list) {
        final ArrayList<WindowState> result = new ArrayList<>(list);
        Collections.reverse(result);
        return result;
    }

    private void tapOnDisplay(final DisplayContent dc) {
        final DisplayMetrics dm = dc.getDisplayMetrics();
        final float x = dm.widthPixels / 2;
        final float y = dm.heightPixels / 2;
        final long downTime = SystemClock.uptimeMillis();
        final long eventTime = SystemClock.uptimeMillis() + 100;
        // sending ACTION_DOWN
        final MotionEvent downEvent = MotionEvent.obtain(
                downTime,
                downTime,
                MotionEvent.ACTION_DOWN,
                x,
                y,
                0 /*metaState*/);
        downEvent.setDisplayId(dc.getDisplayId());
        dc.mTapDetector.onPointerEvent(downEvent);

        // sending ACTION_UP
        final MotionEvent upEvent = MotionEvent.obtain(
                downTime,
                eventTime,
                MotionEvent.ACTION_UP,
                x,
                y,
                0 /*metaState*/);
        upEvent.setDisplayId(dc.getDisplayId());
        dc.mTapDetector.onPointerEvent(upEvent);
    }
}
