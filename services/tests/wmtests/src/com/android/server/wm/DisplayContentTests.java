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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Build.VERSION_CODES.Q;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.DisplayCutout.fromBoundingRect;
import static android.view.InsetsState.ITYPE_STATUS_BAR;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_90;
import static android.view.View.SYSTEM_UI_FLAG_FULLSCREEN;
import static android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
import static android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
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
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_FIXED_TRANSFORM;
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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;

import android.annotation.SuppressLint;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
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
import android.view.SurfaceControl.Transaction;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
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

    @Test
    public void testForAllWindows() {
        final WindowState exitingAppWindow = createWindow(null, TYPE_BASE_APPLICATION,
                mDisplayContent, "exiting app");
        final ActivityRecord exitingApp = exitingAppWindow.mActivityRecord;
        // Wait until everything in animation handler get executed to prevent the exiting window
        // from being removed during WindowSurfacePlacer Traversal.
        waitUntilHandlersIdle();

        exitingApp.mIsExiting = true;
        exitingApp.getTask().getStack().mExitingActivities.add(exitingApp);

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

    @Test
    public void testForAllWindows_WithAppImeTarget() {
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "imeAppTarget");

        mDisplayContent.mInputMethodTarget = imeAppTarget;

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

    @Test
    public void testForAllWindows_WithChildWindowImeTarget() throws Exception {
        mDisplayContent.mInputMethodTarget = mChildAppWindowAbove;

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

    @Test
    public void testForAllWindows_WithStatusBarImeTarget() throws Exception {
        mDisplayContent.mInputMethodTarget = mStatusBarWindow;

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

    @Test
    public void testForAllWindows_WithNotificationShadeImeTarget() throws Exception {
        mDisplayContent.mInputMethodTarget = mNotificationShadeWindow;

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

    @Test
    public void testComputeImeTarget_startingWindow() {
        ActivityRecord activity = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);

        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, activity,
                "startingWin");
        startingWin.setHasSurface(true);
        assertTrue(startingWin.canBeImeTarget());

        WindowState imeTarget = mDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(startingWin, imeTarget);
        startingWin.mHidden = false;

        // Verify that an app window launching behind the starting window becomes the target
        final WindowState appWin = createWindow(null, TYPE_BASE_APPLICATION, activity, "appWin");
        appWin.setHasSurface(true);
        assertTrue(appWin.canBeImeTarget());

        imeTarget = mDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(appWin, imeTarget);
        appWin.mHidden = false;

        // Verify that an child window can be an ime target even behind a launching app window
        final WindowState childWin = createWindow(appWin,
                TYPE_APPLICATION_ATTACHED_DIALOG, "childWin");
        childWin.setHasSurface(true);
        assertTrue(childWin.canBeImeTarget());
        imeTarget = mDisplayContent.computeImeTarget(false /* updateImeTarget */);
        assertEquals(childWin, imeTarget);
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
        final ActivityStack stack = createTaskStackOnDisplay(dc);
        assertEquals(dc.getDisplayId(), stack.getDisplayContent().getDisplayId());
        assertEquals(dc, stack.getDisplayContent());

        final Task task = createTaskInStack(stack, 0 /* userId */);
        final ActivityRecord activity = WindowTestUtils.createTestActivityRecord(dc);
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
        final ActivityStack stack0 = createTaskStackOnDisplay(dc0);
        final Task task0 = createTaskInStack(stack0, 0 /* userId */);
        final ActivityRecord activity =
                WindowTestUtils.createTestActivityRecord(dc0);
        task0.addChild(activity, 0);
        dc0.configureDisplayPolicy();
        assertNotNull(dc0.mTapDetector);

        final ActivityStack stack1 = createTaskStackOnDisplay(dc1);
        final Task task1 = createTaskInStack(stack1, 0 /* userId */);
        final ActivityRecord activity1 =
                WindowTestUtils.createTestActivityRecord(dc0);
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

    private WindowState[] createNotDrawnWindowsOn(DisplayContent displayContent, int... types) {
        final WindowState[] windows = new WindowState[types.length];
        for (int i = 0; i < types.length; i++) {
            final int type = types[i];
            windows[i] = createWindow(null /* parent */, type, displayContent, "window-" + type);
            windows[i].mHasSurface = false;
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
        final int resultingDensity = (maxWidth * baseDensity) / baseWidth;

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
        final int cutoutWidth = 40;
        final int cutoutHeight = 10;
        final int left = (displayWidth - cutoutWidth) / 2;
        final int top = 0;
        final int right = (displayWidth + cutoutWidth) / 2;
        final int bottom = cutoutHeight;

        final Rect r1 = new Rect(left, top, right, bottom);
        final DisplayCutout cutout = new WmDisplayCutout(
                fromBoundingRect(r1.left, r1.top, r1.right, r1.bottom, BOUNDS_POSITION_TOP), null)
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
        final Rect r = new Rect(top, left, bottom, right);
        assertEquals(new WmDisplayCutout(
                fromBoundingRect(r.left, r.top, r.right, r.bottom, BOUNDS_POSITION_LEFT), null)
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
        // Test close-to-square display
        // ----------------------------
        dc.mBaseDisplayHeight = dc.mBaseDisplayWidth;
        dc.configureDisplayPolicy();

        assertEquals("Screen orientation must be SCREEN_ORIENTATION_USER.",
                SCREEN_ORIENTATION_USER, dc.getOrientation());
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

    @Test
    public void testInputMethodTargetUpdateWhenSwitchingOnDisplays() {
        final DisplayContent newDisplay = createNewDisplay();

        final WindowState appWin = createWindow(null, TYPE_APPLICATION, mDisplayContent, "appWin");
        final WindowState appWin1 = createWindow(null, TYPE_APPLICATION, newDisplay, "appWin1");
        appWin.setHasSurface(true);
        appWin1.setHasSurface(true);

        // Set current input method window on default display, make sure the input method target
        // is appWin & null on the other display.
        mDisplayContent.setInputMethodWindowLocked(mImeWindow);
        newDisplay.setInputMethodWindowLocked(null);
        assertEquals("appWin should be IME target window",
                appWin, mDisplayContent.mInputMethodTarget);
        assertNull("newDisplay Ime target: ", newDisplay.mInputMethodTarget);

        // Switch input method window on new display & make sure the input method target also
        // switched as expected.
        newDisplay.setInputMethodWindowLocked(mImeWindow);
        mDisplayContent.setInputMethodWindowLocked(null);
        assertEquals("appWin1 should be IME target window", appWin1, newDisplay.mInputMethodTarget);
        assertNull("default display Ime target: ", mDisplayContent.mInputMethodTarget);
    }

    @Test
    public void testAllowsTopmostFullscreenOrientation() {
        final DisplayContent dc = createNewDisplay();
        dc.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_DISABLED);

        final ActivityStack stack =
                new ActivityTestsBase.StackBuilder(mWm.mAtmService.mRootWindowContainer)
                        .setDisplay(dc)
                        .build();
        doReturn(true).when(stack).isVisible();

        final ActivityStack freeformStack =
                new ActivityTestsBase.StackBuilder(mWm.mAtmService.mRootWindowContainer)
                        .setDisplay(dc)
                        .setWindowingMode(WINDOWING_MODE_FREEFORM)
                        .build();
        doReturn(true).when(freeformStack).isVisible();
        freeformStack.getTopChild().setBounds(100, 100, 300, 400);

        assertTrue(dc.getDefaultTaskDisplayArea().isStackVisible(WINDOWING_MODE_FREEFORM));

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

        final ActivityStack stack =
                new ActivityTestsBase.StackBuilder(mWm.mAtmService.mRootWindowContainer)
                        .setDisplay(dc).build();
        final ActivityRecord activity = stack.getTopMostTask().getTopNonFinishingActivity();

        activity.setRequestedOrientation(newOrientation);

        final int expectedOrientation = newOrientation == SCREEN_ORIENTATION_PORTRAIT
                ? Configuration.ORIENTATION_PORTRAIT
                : Configuration.ORIENTATION_LANDSCAPE;
        assertEquals(expectedOrientation, dc.getConfiguration().orientation);
    }

    @Test
    public void testOnDescendantOrientationRequestChanged_FrozenToUserRotation() {
        final DisplayContent dc = createNewDisplay();
        dc.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_ENABLED);
        final int newOrientation = getRotatedOrientation(dc);

        final ActivityStack stack =
                new ActivityTestsBase.StackBuilder(mWm.mAtmService.mRootWindowContainer)
                        .setDisplay(dc).build();
        final ActivityRecord activity = stack.getTopMostTask().getTopNonFinishingActivity();

        activity.setRequestedOrientation(newOrientation);

        verify(dc, never()).updateDisplayOverrideConfigurationLocked(any(), eq(activity),
                anyBoolean(), same(null));
        assertEquals(dc.getDisplayRotation().getUserRotation(), dc.getRotation());
    }

    @Test
    public void testComputeImeParent_app() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.mInputMethodTarget = createWindow(null, TYPE_BASE_APPLICATION, "app");
        dc.mInputMethodInputTarget = dc.mInputMethodTarget;
        assertEquals(dc.mInputMethodTarget.mActivityRecord.getSurfaceControl(),
                dc.computeImeParent());
    }

    @Test
    public void testComputeImeParent_app_notFullscreen() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.mInputMethodTarget = createWindow(null, TYPE_STATUS_BAR, "app");
        dc.mInputMethodTarget.setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertEquals(dc.getImeContainer().getParentSurfaceControl(), dc.computeImeParent());
    }

    @Test
    public void testComputeImeParent_app_notMatchParentBounds() {
        spyOn(mAppWindow.mActivityRecord);
        doReturn(false).when(mAppWindow.mActivityRecord).matchParentBounds();
        mDisplayContent.mInputMethodTarget = mAppWindow;
        // The surface parent of IME should be the display instead of app window.
        assertEquals(mDisplayContent.getImeContainer().getParentSurfaceControl(),
                mDisplayContent.computeImeParent());
    }

    @Test
    public void testComputeImeParent_noApp() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.mInputMethodTarget = createWindow(null, TYPE_STATUS_BAR, "statusBar");
        assertEquals(dc.getImeContainer().getParentSurfaceControl(), dc.computeImeParent());
    }

    @Test
    public void testInputMethodInputTarget_isClearedWhenWindowStateIsRemoved() throws Exception {
        final DisplayContent dc = createNewDisplay();

        WindowState app = createWindow(null, TYPE_BASE_APPLICATION, dc, "app");

        dc.mInputMethodInputTarget = app;
        assertEquals(app, dc.computeImeControlTarget());

        app.removeImmediately();

        assertNull(dc.mInputMethodInputTarget);
        assertNull(dc.computeImeControlTarget());
    }

    @Test
    public void testComputeImeControlTarget() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.setRemoteInsetsController(createDisplayWindowInsetsController());
        dc.mInputMethodInputTarget = createWindow(null, TYPE_BASE_APPLICATION, "app");
        dc.mInputMethodTarget = dc.mInputMethodInputTarget;
        assertEquals(dc.mInputMethodInputTarget, dc.computeImeControlTarget());
    }

    @Test
    public void testComputeImeControlTarget_splitscreen() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.mInputMethodInputTarget = createWindow(null, TYPE_BASE_APPLICATION, "app");
        dc.mInputMethodInputTarget.setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        dc.mInputMethodTarget = dc.mInputMethodInputTarget;
        dc.setRemoteInsetsController(createDisplayWindowInsetsController());
        assertNotEquals(dc.mInputMethodInputTarget, dc.computeImeControlTarget());
    }

    @Test
    public void testComputeImeControlTarget_notMatchParentBounds() throws Exception {
        spyOn(mAppWindow.mActivityRecord);
        doReturn(false).when(mAppWindow.mActivityRecord).matchParentBounds();
        mDisplayContent.mInputMethodInputTarget = mAppWindow;
        mDisplayContent.mInputMethodTarget = mDisplayContent.mInputMethodInputTarget;
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
        win.getAttrs().subtreeSystemUiVisibility = win.mSystemUiVisibility =
                SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        win.mActivityRecord.mTargetSdk = P;

        performLayout(dc);

        win.setHasSurface(true);

        final Region expected = Region.obtain();
        expected.set(dc.getBounds());
        assertEquals(expected, calculateSystemGestureExclusion(dc));

        win.setHasSurface(false);
    }

    @Test
    public void testRequestResizeForEmptyFrames() {
        final WindowState win = mChildAppWindowAbove;
        makeWindowVisible(win, win.getParentWindow());
        win.setRequestedSize(mDisplayContent.mBaseDisplayWidth, 0 /* height */);
        win.mAttrs.width = win.mAttrs.height = WindowManager.LayoutParams.WRAP_CONTENT;
        win.mAttrs.gravity = Gravity.CENTER;
        performLayout(mDisplayContent);

        // The frame is empty because the requested height is zero.
        assertTrue(win.getFrameLw().isEmpty());
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

        final ActivityRecord app = mAppWindow.mActivityRecord;
        app.setVisible(false);
        mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_ACTIVITY_OPEN,
                false /* alwaysKeepCurrent */);
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
        final ActivityStack stack2 = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setDisplay(mDisplayContent).build();
        final ActivityRecord app2 = new ActivityTestsBase.ActivityBuilder(mWm.mAtmService)
                .setStack(stack2).setUseProcess(app.app).build();
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
        final ActivityRecord app = mAppWindow.mActivityRecord;
        final Task task = app.getTask();
        final ActivityRecord app2 = new ActivityTestsBase.ActivityBuilder(mWm.mAtmService)
                .setTask(task).build();
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
    public void testNoFixedRotationWithPip() {
        // Make resume-top really update the activity state.
        setBooted(mWm.mAtmService);
        // Speed up the test by a few seconds.
        mWm.mAtmService.deferWindowLayout();
        doNothing().when(mWm).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());

        final DisplayContent displayContent = mWm.mRoot.getDefaultDisplay();
        final Configuration displayConfig = displayContent.getConfiguration();
        final ActivityRecord pinnedActivity = createActivityRecord(displayContent,
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD);
        final Task pinnedTask = pinnedActivity.getRootTask();
        final ActivityRecord homeActivity = WindowTestUtils.createTestActivityRecord(
                displayContent.getDefaultTaskDisplayArea().getOrCreateRootHomeTask());
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
        // Leave PiP to fullscreen. The orientation can be updated from
        // ActivityRecord#reportDescendantOrientationChangeIfNeeded.
        pinnedTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        homeActivity.setState(ActivityStack.ActivityState.STOPPED, "test");

        assertFalse(displayContent.hasTopFixedRotationLaunchingApp());
        verify(mWm, atLeastOnce()).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());
        assertEquals(pinnedConfigOrientation, displayConfig.orientation);
        assertFalse(displayContent.getPinnedStackController().isPipActiveOrWindowingModeChanging());

        clearInvocations(mWm);
        // Enter PiP from fullscreen. The orientation can be updated from
        // ensure-visibility/resume-focused-stack -> ActivityRecord#makeActiveIfNeeded -> resume.
        pinnedTask.setWindowingMode(WINDOWING_MODE_PINNED);

        assertFalse(displayContent.hasTopFixedRotationLaunchingApp());
        verify(mWm, atLeastOnce()).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());
        assertEquals(homeConfigOrientation, displayConfig.orientation);
        assertTrue(displayContent.getPinnedStackController().isPipActiveOrWindowingModeChanging());
    }

    @Test
    public void testNoFixedRotationOnResumedScheduledApp() {
        final ActivityRecord app = new ActivityTestsBase.StackBuilder(mWm.mRoot)
                .setDisplay(mDisplayContent).build().getTopMostActivity();
        app.setVisible(false);
        app.setState(ActivityStack.ActivityState.RESUMED, "test");
        mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_ACTIVITY_OPEN,
                false /* alwaysKeepCurrent */);
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
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();
        doCallRealMethod().when(displayRotation).updateRotationUnchecked(anyBoolean());
        // Skip freezing so the unrelated conditions in updateRotationUnchecked won't disturb.
        doNothing().when(mWm).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());

        final ActivityRecord recentsActivity = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_RECENTS);
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
        mDisplayContent.setFixedRotationLaunchingAppUnchecked(mAppWindow.mActivityRecord);
        displayRotation.setRotation((displayRotation.getRotation() + 1) % 4);
        assertTrue(displayRotation.updateRotationUnchecked(false));

        // The recents activity should not apply fixed rotation if the top activity is not opaque.
        mDisplayContent.mFocusedApp = mAppWindow.mActivityRecord;
        doReturn(false).when(mDisplayContent.mFocusedApp).occludesParent();
        doReturn(ROTATION_90).when(mDisplayContent).rotationForActivityInDifferentOrientation(
                eq(recentsActivity));
        mDisplayContent.mFixedRotationTransitionListener.onStartRecentsAnimation(recentsActivity);
        assertFalse(recentsActivity.hasFixedRotationTransform());
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
        ActivityStack homeTask = defaultTaskDisplayArea.getRootHomeTask();
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
        ActivityStack homeTask = taskDisplayArea.getRootHomeTask();
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
    public void testFindScrollCaptureTargetWindow_behindWindow() {
        DisplayContent display = createNewDisplay();
        ActivityStack stack = createTaskStackOnDisplay(display);
        Task task = createTaskInStack(stack, 0 /* userId */);
        WindowState activityWindow = createAppWindow(task, TYPE_APPLICATION, "App Window");
        WindowState behindWindow = createWindow(null, TYPE_SCREENSHOT, display, "Screenshot");

        WindowState result = display.findScrollCaptureTargetWindow(behindWindow,
                ActivityTaskManager.INVALID_TASK_ID);
        assertEquals(activityWindow, result);
    }

    @Test
    public void testFindScrollCaptureTargetWindow_taskId() {
        DisplayContent display = createNewDisplay();
        ActivityStack stack = createTaskStackOnDisplay(display);
        Task task = createTaskInStack(stack, 0 /* userId */);
        WindowState window = createAppWindow(task, TYPE_APPLICATION, "App Window");
        WindowState behindWindow = createWindow(null, TYPE_SCREENSHOT, display, "Screenshot");

        WindowState result = display.findScrollCaptureTargetWindow(null, task.mTaskId);
        assertEquals(window, result);
    }

    @Test
    public void testEnsureActivitiesVisibleNotRecursive() {
        final TaskDisplayArea mockTda = mock(TaskDisplayArea.class);
        doReturn(mockTda).when(mDisplayContent).getTaskDisplayAreaAt(anyInt());
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
    public void testForceDesktopMode() {
        mWm.mForceDesktopModeOnExternalDisplays = true;
        // Not applicable for default display
        final DisplayContent defaultDisplay = mWm.mRoot.getDefaultDisplay();
        assertFalse(defaultDisplay.forceDesktopMode());

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

    private boolean isOptionsPanelAtRight(int displayId) {
        return (mWm.getPreferredOptionsPanelGravity(displayId) & Gravity.RIGHT) == Gravity.RIGHT;
    }

    private static void verifySizes(DisplayContent displayContent, int expectedBaseWidth,
                             int expectedBaseHeight, int expectedBaseDensity) {
        assertEquals(displayContent.mBaseDisplayWidth, expectedBaseWidth);
        assertEquals(displayContent.mBaseDisplayHeight, expectedBaseHeight);
        assertEquals(displayContent.mBaseDisplayDensity, expectedBaseDensity);
    }

    private void updateFocusedWindow() {
        mWm.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false /* updateInputWindows */);
    }

    private void performLayout(DisplayContent dc) {
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
