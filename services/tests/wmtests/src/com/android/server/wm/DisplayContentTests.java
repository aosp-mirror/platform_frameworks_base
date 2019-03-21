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

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Build.VERSION_CODES.Q;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.DisplayCutout.BOUNDS_POSITION_LEFT;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.DisplayCutout.fromBoundingRect;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.same;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowManagerService.UPDATE_FOCUS_NORMAL;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;

import android.annotation.SuppressLint;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.graphics.Region;
import android.metrics.LogMaker;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.DisplayMetrics;
import android.util.MutableBoolean;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.ISystemGestureExclusionListener;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.ViewRootImpl;
import android.view.test.InsetsModeSession;

import androidx.test.filters.SmallTest;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.server.wm.utils.WmDisplayCutout;

import org.junit.Test;
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
public class DisplayContentTests extends WindowTestsBase {

    @Test
    public void testForAllWindows() {
        final WindowState exitingAppWindow = createWindow(null, TYPE_BASE_APPLICATION,
                mDisplayContent, "exiting app");
        final AppWindowToken exitingAppToken = exitingAppWindow.mAppToken;
        // Wait until everything in animation handler get executed to prevent the exiting window
        // from being removed during WindowSurfacePlacer Traversal.
        waitUntilHandlersIdle();

        exitingAppToken.mIsExiting = true;
        exitingAppToken.getTask().mStack.mExitingAppTokens.add(exitingAppToken);

        assertForAllWindowsOrder(Arrays.asList(
                mWallpaperWindow,
                exitingAppWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                mDockedDividerWindow,
                mStatusBarWindow,
                mNavBarWindow,
                mImeWindow,
                mImeDialogWindow));
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
                mStatusBarWindow,
                mNavBarWindow,
                mImeWindow,
                mImeDialogWindow));
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

    /**
     * This tests stack movement between displays and proper stack's, task's and app token's display
     * container references updates.
     */
    @Test
    public void testMoveStackBetweenDisplays() {
        // Create a second display.
        final DisplayContent dc = createNewDisplay();

        // Add stack with activity.
        final TaskStack stack = createTaskStackOnDisplay(dc);
        assertEquals(dc.getDisplayId(), stack.getDisplayContent().getDisplayId());
        assertEquals(dc, stack.getParent().getParent());
        assertEquals(dc, stack.getDisplayContent());

        final Task task = createTaskInStack(stack, 0 /* userId */);
        final WindowTestUtils.TestAppWindowToken token = WindowTestUtils.createTestAppWindowToken(
                dc);
        task.addChild(token, 0);
        assertEquals(dc, task.getDisplayContent());
        assertEquals(dc, token.getDisplayContent());

        // Move stack to first display.
        mDisplayContent.moveStackToDisplay(stack, true /* onTop */);
        assertEquals(mDisplayContent.getDisplayId(), stack.getDisplayContent().getDisplayId());
        assertEquals(mDisplayContent, stack.getParent().getParent());
        assertEquals(mDisplayContent, stack.getDisplayContent());
        assertEquals(mDisplayContent, task.getDisplayContent());
        assertEquals(mDisplayContent, token.getDisplayContent());
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
        final TaskStack stack0 = createTaskStackOnDisplay(dc0);
        final Task task0 = createTaskInStack(stack0, 0 /* userId */);
        final WindowTestUtils.TestAppWindowToken token =
                WindowTestUtils.createTestAppWindowToken(dc0);
        task0.addChild(token, 0);
        dc0.configureDisplayPolicy();
        assertNotNull(dc0.mTapDetector);

        final TaskStack stack1 = createTaskStackOnDisplay(dc1);
        final Task task1 = createTaskInStack(stack1, 0 /* userId */);
        final WindowTestUtils.TestAppWindowToken token1 =
                WindowTestUtils.createTestAppWindowToken(dc0);
        task1.addChild(token1, 0);
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
        window1.mAppToken.mTargetSdk = targetSdk;
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
        window2.mAppToken.mTargetSdk = targetSdk;
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
        synchronized (mWm.getWindowManagerLock()) {
            final DisplayContent dc = createNewDisplay();
            dc.mInitialDisplayWidth = 200;
            dc.mInitialDisplayHeight = 400;
            Rect r = new Rect(80, 0, 120, 10);
            final DisplayCutout cutout = new WmDisplayCutout(
                    fromBoundingRect(r.left, r.top, r.right, r.bottom, BOUNDS_POSITION_TOP), null)
                    .computeSafeInsets(200, 400).getDisplayCutout();

            dc.mInitialDisplayCutout = cutout;
            dc.setRotation(Surface.ROTATION_0);
            dc.computeScreenConfiguration(new Configuration()); // recomputes dc.mDisplayInfo.

            assertEquals(cutout, dc.getDisplayInfo().displayCutout);
        }
    }

    @Test
    public void testDisplayCutout_rot90() {
        synchronized (mWm.getWindowManagerLock()) {
            // Prevent mInitialDisplayCutout from being updated from real display (e.g. null
            // if the device has no cutout).
            final DisplayContent dc = createDisplayNoUpdateDisplayInfo();
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
                    fromBoundingRect(r1.left, r1.top, r1.right, r1.bottom, BOUNDS_POSITION_TOP),
                    null)
                    .computeSafeInsets(displayWidth, displayHeight).getDisplayCutout();

            dc.mInitialDisplayCutout = cutout;
            dc.setRotation(Surface.ROTATION_90);
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
                    .computeSafeInsets(displayHeight, displayWidth)
                    .getDisplayCutout(), dc.getDisplayInfo().displayCutout);
        }
    }

    @Test
    public void testLayoutSeq_assignedDuringLayout() {
        synchronized (mWm.getWindowManagerLock()) {

            final DisplayContent dc = createNewDisplay();
            final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, dc, "w");

            dc.setLayoutNeeded();
            dc.performLayout(true /* initial */, false /* updateImeWindows */);

            assertThat(win.mLayoutSeq, is(dc.mLayoutSeq));
        }
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
        window.mAppToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        final WindowState keyguard = createWindow(null, TYPE_STATUS_BAR, dc, "keyguard");
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

        portraitDisplay.setRotation(Surface.ROTATION_0);
        assertFalse(isOptionsPanelAtRight(portraitDisplay.getDisplayId()));
        portraitDisplay.setRotation(Surface.ROTATION_90);
        assertTrue(isOptionsPanelAtRight(portraitDisplay.getDisplayId()));

        final DisplayContent landscapeDisplay = createNewDisplay();
        landscapeDisplay.mInitialDisplayHeight = 1000;
        landscapeDisplay.mInitialDisplayWidth = 2000;

        landscapeDisplay.setRotation(Surface.ROTATION_0);
        assertTrue(isOptionsPanelAtRight(landscapeDisplay.getDisplayId()));
        landscapeDisplay.setRotation(Surface.ROTATION_90);
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
        assertTrue("appWin should be IME target window",
                appWin.equals(mDisplayContent.mInputMethodTarget));
        assertNull("newDisplay Ime target: ", newDisplay.mInputMethodTarget);

        // Switch input method window on new display & make sure the input method target also
        // switched as expected.
        newDisplay.setInputMethodWindowLocked(mImeWindow);
        mDisplayContent.setInputMethodWindowLocked(null);
        assertTrue("appWin1 should be IME target window",
                appWin1.equals(newDisplay.mInputMethodTarget));
        assertNull("default display Ime target: ", mDisplayContent.mInputMethodTarget);
    }

    @Test
    public void testOnDescendantOrientationRequestChanged() {
        final DisplayContent dc = createNewDisplay();
        mWm.mAtmService.mRootActivityContainer = mock(RootActivityContainer.class);
        final int newOrientation = dc.getLastOrientation() == SCREEN_ORIENTATION_LANDSCAPE
                ? SCREEN_ORIENTATION_PORTRAIT
                : SCREEN_ORIENTATION_LANDSCAPE;

        final WindowState window = createWindow(null /* parent */, TYPE_BASE_APPLICATION, dc, "w");
        window.getTask().mTaskRecord = mock(TaskRecord.class, ExtendedMockito.RETURNS_DEEP_STUBS);
        window.mAppToken.setOrientation(newOrientation);

        ActivityRecord activityRecord = mock(ActivityRecord.class);

        assertTrue("Display should rotate to handle orientation request by default.",
                dc.onDescendantOrientationChanged(window.mToken.token, activityRecord));

        final ArgumentCaptor<Configuration> captor = ArgumentCaptor.forClass(Configuration.class);
        verify(mWm.mAtmService).updateDisplayOverrideConfigurationLocked(captor.capture(),
                same(activityRecord), anyBoolean(), eq(dc.getDisplayId()));
        final Configuration newDisplayConfig = captor.getValue();
        assertEquals(Configuration.ORIENTATION_PORTRAIT, newDisplayConfig.orientation);
    }

    @Test
    public void testOnDescendantOrientationRequestChanged_FrozenToUserRotation() {
        final DisplayContent dc = createNewDisplay();
        dc.getDisplayRotation().setFixedToUserRotation(
                DisplayRotation.FIXED_TO_USER_ROTATION_ENABLED);
        mWm.mAtmService.mRootActivityContainer = mock(RootActivityContainer.class);
        final int newOrientation = dc.getLastOrientation() == SCREEN_ORIENTATION_LANDSCAPE
                ? SCREEN_ORIENTATION_PORTRAIT
                : SCREEN_ORIENTATION_LANDSCAPE;

        final WindowState window = createWindow(null /* parent */, TYPE_BASE_APPLICATION, dc, "w");
        window.getTask().mTaskRecord = mock(TaskRecord.class, ExtendedMockito.RETURNS_DEEP_STUBS);
        window.mAppToken.setOrientation(newOrientation);

        ActivityRecord activityRecord = mock(ActivityRecord.class);

        assertFalse("Display shouldn't rotate to handle orientation request if fixed to"
                        + " user rotation.",
                dc.onDescendantOrientationChanged(window.mToken.token, activityRecord));
        verify(mWm.mAtmService, never()).updateDisplayOverrideConfigurationLocked(any(),
                eq(activityRecord), anyBoolean(), eq(dc.getDisplayId()));
    }

    @Test
    public void testComputeImeParent_app() throws Exception {
        try (final InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_IME)) {
            final DisplayContent dc = createNewDisplay();
            dc.mInputMethodTarget = createWindow(null, TYPE_BASE_APPLICATION, "app");
            assertEquals(dc.mInputMethodTarget.mAppToken.getSurfaceControl(),
                    dc.computeImeParent());
        }
    }

    @Test
    public void testComputeImeParent_app_notFullscreen() throws Exception {
        try (final InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_IME)) {
            final DisplayContent dc = createNewDisplay();
            dc.mInputMethodTarget = createWindow(null, TYPE_STATUS_BAR, "app");
            dc.mInputMethodTarget.setWindowingMode(
                    WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
            assertEquals(dc.getWindowingLayer(), dc.computeImeParent());
        }
    }

    @Test
    public void testComputeImeParent_app_notMatchParentBounds() {
        spyOn(mAppWindow.mAppToken);
        doReturn(false).when(mAppWindow.mAppToken).matchParentBounds();
        mDisplayContent.mInputMethodTarget = mAppWindow;
        // The surface parent of IME should be the display instead of app window.
        assertEquals(mDisplayContent.getWindowingLayer(), mDisplayContent.computeImeParent());
    }

    @Test
    public void testComputeImeParent_noApp() throws Exception {
        try (final InsetsModeSession session =
                     new InsetsModeSession(ViewRootImpl.NEW_INSETS_MODE_IME)) {
            final DisplayContent dc = createNewDisplay();
            dc.mInputMethodTarget = createWindow(null, TYPE_STATUS_BAR, "statusBar");
            assertEquals(dc.getWindowingLayer(), dc.computeImeParent());
        }
    }

    @Test
    public void testUpdateSystemGestureExclusion() throws Exception {
        final DisplayContent dc = createNewDisplay();
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, dc, "win");
        win.getAttrs().flags |= FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        win.setSystemGestureExclusion(Collections.singletonList(new Rect(10, 20, 30, 40)));

        dc.setLayoutNeeded();
        dc.performLayout(true /* initial */, false /* updateImeWindows */);

        win.setHasSurface(true);
        dc.updateSystemGestureExclusion();

        final MutableBoolean invoked = new MutableBoolean(false);
        final ISystemGestureExclusionListener.Stub verifier =
                new ISystemGestureExclusionListener.Stub() {
            @Override
            public void onSystemGestureExclusionChanged(int displayId, Region actual) {
                Region expected = Region.obtain();
                expected.set(10, 20, 30, 40);
                assertEquals(expected, actual);
                invoked.value = true;
            }
        };
        try {
            dc.registerSystemGestureExclusionListener(verifier);
        } finally {
            dc.unregisterSystemGestureExclusionListener(verifier);
        }
        assertTrue("SystemGestureExclusionListener was not invoked", invoked.value);
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

        dc.setLayoutNeeded();
        dc.performLayout(true /* initial */, false /* updateImeWindows */);

        win.setHasSurface(true);
        win2.setHasSurface(true);

        final Region expected = Region.obtain();
        expected.set(20, 30, 40, 50);
        assertEquals(expected, dc.calculateSystemGestureExclusion());
    }

    @Test
    public void testOrientationChangeLogging() {
        MetricsLogger mockLogger = mock(MetricsLogger.class);
        Configuration oldConfig = new Configuration();
        oldConfig.orientation = Configuration.ORIENTATION_LANDSCAPE;

        Configuration newConfig = new Configuration();
        newConfig.orientation = Configuration.ORIENTATION_PORTRAIT;
        final DisplayContent displayContent = spy(createNewDisplay());
        Mockito.doReturn(mockLogger).when(displayContent).getMetricsLogger();
        Mockito.doReturn(oldConfig).doReturn(newConfig).when(displayContent).getConfiguration();

        displayContent.onConfigurationChanged(newConfig);

        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        verify(mockLogger).write(logMakerCaptor.capture());
        assertThat(logMakerCaptor.getValue().getCategory(),
                is(MetricsProto.MetricsEvent.ACTION_PHONE_ORIENTATION_CHANGED));
        assertThat(logMakerCaptor.getValue().getSubtype(),
                is(Configuration.ORIENTATION_PORTRAIT));
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
        synchronized (mWm.mGlobalLock) {
            mWm.updateFocusedWindowLocked(UPDATE_FOCUS_NORMAL, false);
        }
    }

    /**
     * Create DisplayContent that does not update display base/initial values from device to keep
     * the values set by test.
     */
    private DisplayContent createDisplayNoUpdateDisplayInfo() {
        final DisplayContent displayContent = spy(createNewDisplay());
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
