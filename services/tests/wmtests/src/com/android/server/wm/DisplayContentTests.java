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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.FLAG_SHOW_WHEN_LOCKED;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_NOSENSOR;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.os.Build.VERSION_CODES.P;
import static android.os.Build.VERSION_CODES.Q;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.FLAG_PRIVATE;
import static android.view.DisplayCutout.BOUNDS_POSITION_TOP;
import static android.view.DisplayCutout.fromBoundingRect;
import static android.view.Surface.ROTATION_0;
import static android.view.Surface.ROTATION_180;
import static android.view.Surface.ROTATION_270;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowInsets.Type.ime;
import static android.view.WindowInsets.Type.navigationBars;
import static android.view.WindowInsets.Type.statusBars;
import static android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
import static android.view.WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.FLAG_SECURE;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
import static android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_NO_MOVE_ANIMATION;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN;
import static android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_NOTIFICATION_SHADE;
import static android.view.WindowManager.LayoutParams.TYPE_SCREENSHOT;
import static android.view.WindowManager.LayoutParams.TYPE_STATUS_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_VOICE_INTERACTION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE;
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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;
import static com.android.server.wm.DisplayContent.IME_TARGET_LAYERING;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_TOKEN_TRANSFORM;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.app.ActivityTaskManager;
import android.app.WindowConfiguration;
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.DisplayInfo;
import android.view.Gravity;
import android.view.IDisplayChangeWindowCallback;
import android.view.IDisplayChangeWindowController;
import android.view.ISystemGestureExclusionListener;
import android.view.IWindowManager;
import android.view.InsetsState;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;
import android.view.View;
import android.view.WindowManager;
import android.window.DisplayAreaInfo;
import android.window.IDisplayAreaOrganizer;
import android.window.ScreenCapture;
import android.window.WindowContainerToken;
import android.window.WindowContainerTransaction;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;
import com.android.server.LocalServices;
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

    @SetupWindows(addAllCommonWindows = true)
    @Test
    public void testForAllWindows() {
        final WindowState exitingAppWindow = createWindow(null, TYPE_BASE_APPLICATION,
                mDisplayContent, "exiting app");
        final ActivityRecord exitingApp = exitingAppWindow.mActivityRecord;
        exitingApp.startAnimation(exitingApp.getPendingTransaction(), mock(AnimationAdapter.class),
                false /* hidden */, SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION);
        exitingApp.mIsExiting = true;
        // If the activity is animating, its window should not be removed.
        mDisplayContent.handleCompleteDeferredRemoval();

        final ArrayList<WindowState> windows = new ArrayList<>(Arrays.asList(
                mWallpaperWindow,
                mChildAppWindowBelow,
                mAppWindow,
                mChildAppWindowAbove,
                exitingAppWindow,
                mDockedDividerWindow,
                mImeWindow,
                mImeDialogWindow,
                mStatusBarWindow,
                mNotificationShadeWindow,
                mNavBarWindow));
        assertForAllWindowsOrder(windows);

        exitingApp.cancelAnimation();
        // The exiting window will be removed because its parent is no longer animating.
        mDisplayContent.handleCompleteDeferredRemoval();
        windows.remove(exitingAppWindow);
        assertForAllWindowsOrder(windows);
    }

    @SetupWindows(addAllCommonWindows = true)
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

    @SetupWindows(addAllCommonWindows = true)
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

    @SetupWindows(addAllCommonWindows = true)
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

    @SetupWindows(addAllCommonWindows = true)
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

    @SetupWindows(addAllCommonWindows = true)
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
                mImeWindow,
                mImeDialogWindow,
                mStatusBarWindow,
                mNotificationShadeWindow,
                voiceInteractionWindow, // It can show above lock screen.
                mNavBarWindow));
    }

    @SetupWindows(addAllCommonWindows = true)
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

    @SetupWindows(addAllCommonWindows = true)
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

    @Test
    public void testUpdateImeParent_forceUpdateRelativeLayer() {
        final DisplayArea.Tokens imeContainer = mDisplayContent.getImeContainer();
        final ActivityRecord activity = createActivityRecord(mDisplayContent);

        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, activity,
                "startingWin");
        startingWin.setHasSurface(true);
        assertTrue(startingWin.canBeImeTarget());
        final SurfaceControl imeSurfaceParent = mock(SurfaceControl.class);
        doReturn(imeSurfaceParent).when(mDisplayContent).computeImeParent();
        spyOn(imeContainer);

        mDisplayContent.setImeInputTarget(startingWin);
        mDisplayContent.onConfigurationChanged(new Configuration());
        verify(mDisplayContent).updateImeParent();

        // Force reassign the relative layer when the IME surface parent is changed.
        verify(imeContainer).assignRelativeLayer(any(), eq(imeSurfaceParent), anyInt(), eq(true));
    }

    @Test
    public void testComputeImeTargetReturnsNull_windowDidntRequestIme() {
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION,
                new ActivityBuilder(mAtm).setCreateTask(true).build(), "app");
        final WindowState win2 = createWindow(null, TYPE_BASE_APPLICATION,
                new ActivityBuilder(mAtm).setCreateTask(true).build(), "app2");

        mDisplayContent.setImeInputTarget(win1);
        mDisplayContent.setImeLayeringTarget(win2);

        doReturn(true).when(mDisplayContent).shouldImeAttachedToApp();
        // Compute IME parent returns nothing if current target and window receiving input
        // are different i.e. if current window didn't request IME.
        assertNull("computeImeParent() should be null", mDisplayContent.computeImeParent());
    }

    @Test
    public void testUpdateImeParent_skipForOrganizedImeContainer() {
        final DisplayArea.Tokens imeContainer = mDisplayContent.getImeContainer();
        final ActivityRecord activity = createActivityRecord(mDisplayContent);

        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, activity,
                "startingWin");
        startingWin.setHasSurface(true);
        assertTrue(startingWin.canBeImeTarget());
        final SurfaceControl imeSurfaceParent = mock(SurfaceControl.class);
        doReturn(imeSurfaceParent).when(mDisplayContent).computeImeParent();

        // Main precondition for this test: organize the ImeContainer.
        final IDisplayAreaOrganizer mockImeOrganizer = mock(IDisplayAreaOrganizer.class);
        when(mockImeOrganizer.asBinder()).thenReturn(new Binder());
        imeContainer.setOrganizer(mockImeOrganizer);

        mDisplayContent.updateImeParent();

        assertNull("Don't reparent the surface of an organized ImeContainer.",
                mDisplayContent.mInputMethodSurfaceParent);

        // Clean up organizer.
        imeContainer.setOrganizer(null);
    }

    @Test
    public void testImeContainerIsReparentedUnderParentWhenOrganized() {
        final DisplayArea.Tokens imeContainer = mDisplayContent.getImeContainer();
        final ActivityRecord activity = createActivityRecord(mDisplayContent);

        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, activity,
                "startingWin");
        startingWin.setHasSurface(true);
        assertTrue(startingWin.canBeImeTarget());

        final Transaction transaction = mDisplayContent.getPendingTransaction();
        spyOn(transaction);

        // Organized the ime container.
        final IDisplayAreaOrganizer mockImeOrganizer = mock(IDisplayAreaOrganizer.class);
        when(mockImeOrganizer.asBinder()).thenReturn(new Binder());
        imeContainer.setOrganizer(mockImeOrganizer);

        // Verify that the ime container surface is reparented under
        // its parent surface as a consequence of the setOrganizer call.
        SurfaceControl imeParentSurfaceControl = imeContainer.getParentSurfaceControl();
        verify(transaction).reparent(imeContainer.getSurfaceControl(), imeParentSurfaceControl);

        // Clean up organizer.
        imeContainer.setOrganizer(null);
    }

    /**
     * This tests root task movement between displays and proper root task's, task's and app token's
     * display container references updates.
     */
    @Test
    public void testMoveRootTaskBetweenDisplays() {
        // Create a second display.
        final DisplayContent dc = createNewDisplay();

        // Add root task with activity.
        final Task rootTask = createTask(dc);
        assertEquals(dc.getDisplayId(), rootTask.getDisplayContent().getDisplayId());
        assertEquals(dc, rootTask.getDisplayContent());

        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createNonAttachedActivityRecord(dc);
        task.addChild(activity, 0);
        assertEquals(dc, task.getDisplayContent());
        assertEquals(dc, activity.getDisplayContent());

        // Move root task to first display.
        rootTask.reparent(mDisplayContent.getDefaultTaskDisplayArea(), true /* onTop */);
        assertEquals(mDisplayContent.getDisplayId(), rootTask.getDisplayContent().getDisplayId());
        assertEquals(mDisplayContent, rootTask.getDisplayContent());
        assertEquals(mDisplayContent, task.getDisplayContent());
        assertEquals(mDisplayContent, activity.getDisplayContent());
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

        defaultDisplay.updateDisplayOverrideConfigurationLocked(newOverrideConfig,
                null /* starting */, false /* deferResume */, null /* result */);

        // Check that global configuration is updated, as we've updated default display's config.
        Configuration globalConfig = mWm.mRoot.getConfiguration();
        assertEquals(newOverrideConfig.densityDpi, globalConfig.densityDpi);
        assertEquals(newOverrideConfig.fontScale, globalConfig.fontScale, 0.1 /* delta */);

        // Return back to original values.
        defaultDisplay.updateDisplayOverrideConfigurationLocked(currentConfig,
                null /* starting */, false /* deferResume */, null /* result */);
        globalConfig = mWm.mRoot.getConfiguration();
        assertEquals(currentConfig.densityDpi, globalConfig.densityDpi);
        assertEquals(currentConfig.fontScale, globalConfig.fontScale, 0.1 /* delta */);
    }

    /**
     * Tests tapping on a root task in different display results in window gaining focus.
     */
    @Test
    public void testInputEventBringsCorrectDisplayInFocus() {
        DisplayContent dc0 = mWm.getDefaultDisplayContentLocked();
        // Create a second display
        final DisplayContent dc1 = createNewDisplay();

        // Add root task with activity.
        final Task rootTask0 = createTask(dc0);
        final Task task0 = createTaskInRootTask(rootTask0, 0 /* userId */);
        final ActivityRecord activity = createNonAttachedActivityRecord(dc0);
        task0.addChild(activity, 0);
        dc0.configureDisplayPolicy();
        assertNotNull(dc0.mTapDetector);

        final Task rootTask1 = createTask(dc1);
        final Task task1 = createTaskInRootTask(rootTask1, 0 /* userId */);
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
        final ActivityRecord app2 = new ActivityBuilder(mAtm)
                .setTask(new TaskBuilder(mSupervisor).setDisplay(dc).build())
                .setUseProcess(window1.getProcess()).setOnTop(true).build();
        final WindowState window2 = createWindow(null, TYPE_BASE_APPLICATION, app2, "window2");
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
        window1.mActivityRecord.setVisibleRequested(false);
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
        wallpaper.mToken.asWallpaperToken().setVisibility(false);
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
    public void testDisplayHasContent() {
        final WindowState window = createWindow(null, TYPE_APPLICATION_OVERLAY, "window");
        setDrawnState(WindowStateAnimator.COMMIT_DRAW_PENDING, window);
        assertFalse(mDisplayContent.getLastHasContent());
        // The pending draw state should be committed and the has-content state is also updated.
        mDisplayContent.applySurfaceChangesTransaction();
        assertTrue(window.isDrawn());
        assertTrue(mDisplayContent.getLastHasContent());
        // If the only window is no longer visible, has-content will be false.
        setDrawnState(WindowStateAnimator.NO_SURFACE, window);
        mDisplayContent.applySurfaceChangesTransaction();
        assertFalse(mDisplayContent.getLastHasContent());
    }

    @Test
    public void testImeIsAttachedToDisplayForLetterboxedApp() {
        final DisplayContent dc = mDisplayContent;
        final WindowState ws = createWindow(null, TYPE_APPLICATION, dc, "app window");
        dc.setImeLayeringTarget(ws);
        dc.setImeInputTarget(ws);

        // Adjust bounds so that matchesRootDisplayAreaBounds() returns false.
        final Rect bounds = new Rect(dc.getBounds());
        bounds.scale(0.5f);
        ws.mActivityRecord.setBounds(bounds);
        assertFalse("matchesRootDisplayAreaBounds() should return false",
                ws.matchesDisplayAreaBounds());

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
        final float baseXDpi = 60;
        final float baseYDpi = 60;

        displayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity, baseYDpi,
                baseYDpi);

        final int maxWidth = 300;
        final float ratioChange = maxWidth / (float) baseWidth;
        final int resultingHeight = (int) (baseHeight * ratioChange);
        final int resultingDensity = (int) (baseDensity * ratioChange);
        final float resultingXDpi = baseXDpi * ratioChange;
        final float resultingYDpi = baseYDpi * ratioChange;

        displayContent.setMaxUiWidth(maxWidth);
        verifySizes(displayContent, maxWidth, resultingHeight, resultingDensity, resultingXDpi,
                resultingYDpi);

        // Assert setting values again does not change;
        displayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity, baseXDpi,
                baseYDpi);
        verifySizes(displayContent, maxWidth, resultingHeight, resultingDensity, resultingXDpi,
                resultingYDpi);

        final int smallerWidth = 200;
        final int smallerHeight = 400;
        final int smallerDensity = 100;

        // Specify smaller dimension, verify that it is honored
        displayContent.updateBaseDisplayMetrics(smallerWidth, smallerHeight, smallerDensity,
                baseXDpi, baseYDpi);
        verifySizes(displayContent, smallerWidth, smallerHeight, smallerDensity, baseXDpi,
                baseYDpi);

        // Verify that setting the max width to a greater value than the base width has no effect
        displayContent.setMaxUiWidth(maxWidth);
        verifySizes(displayContent, smallerWidth, smallerHeight, smallerDensity, baseXDpi,
                baseYDpi);
    }

    @Test
    public void testSetForcedSize() {
        // Prevent base display metrics for test from being updated to the value of real display.
        final DisplayContent displayContent = createDisplayNoUpdateDisplayInfo();
        final int baseWidth = 1280;
        final int baseHeight = 720;
        final int baseDensity = 320;
        final float baseXDpi = 60;
        final float baseYDpi = 60;

        displayContent.mInitialDisplayWidth = baseWidth;
        displayContent.mInitialDisplayHeight = baseHeight;
        displayContent.mInitialDisplayDensity = baseDensity;
        displayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity, baseXDpi,
                baseYDpi);

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
        final float baseXDpi = 60;
        final float baseYDpi = 60;

        displayContent.mInitialDisplayWidth = baseWidth;
        displayContent.mInitialDisplayHeight = baseHeight;
        displayContent.mInitialDisplayDensity = baseDensity;
        displayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity, baseXDpi,
                baseYDpi);

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
    public void testSetForcedDensity() {
        final DisplayContent displayContent = createDisplayNoUpdateDisplayInfo();
        final int baseWidth = 1280;
        final int baseHeight = 720;
        final int baseDensity = 320;
        final float baseXDpi = 60;
        final float baseYDpi = 60;
        final int originalMinTaskSizeDp = displayContent.mMinSizeOfResizeableTaskDp;

        displayContent.mInitialDisplayWidth = baseWidth;
        displayContent.mInitialDisplayHeight = baseHeight;
        displayContent.mInitialDisplayDensity = baseDensity;
        displayContent.updateBaseDisplayMetrics(baseWidth, baseHeight, baseDensity, baseXDpi,
                baseYDpi);

        final int forcedDensity = 600;

        // Verify that forcing the density is honored and the size doesn't change.
        displayContent.setForcedDensity(forcedDensity, 0 /* userId */);
        verifySizes(displayContent, baseWidth, baseHeight, forcedDensity);

        // Verify that forcing the density is idempotent.
        displayContent.setForcedDensity(forcedDensity, 0 /* userId */);
        verifySizes(displayContent, baseWidth, baseHeight, forcedDensity);

        // Verify that minimal task size (dp) doesn't change with density of display.
        assertEquals(originalMinTaskSizeDp, displayContent.mMinSizeOfResizeableTaskDp);

        // Verify that forcing resolution won't affect the already forced density.
        displayContent.setForcedSize(1800, 1200);
        verifySizes(displayContent, 1800, 1200, forcedDensity);
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
                displayWidth, displayHeight, displayWidth, displayHeight, density, "",
                Surface.ROTATION_0, 1f, 1f);
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
                displayWidth, displayHeight, displayWidth, displayHeight, density, "",
                Surface.ROTATION_90, 1f, 1f);
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
    public void testOrientationDefinedByKeyguard() {
        final DisplayContent dc = mDisplayContent;
        dc.getDisplayPolicy().setAwake(true);

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
        mAtm.mKeyguardController.setKeyguardShown(window.getDisplayId(), true /* keyguardShowing */,
                false /* aodShowing */);
        assertEquals("Visible keyguard must influence device orientation",
                SCREEN_ORIENTATION_PORTRAIT, dc.getOrientation());

        mAtm.mKeyguardController.keyguardGoingAway(window.getDisplayId(), 0 /* flags */);
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

        // Assume that a decor window occupies the display height, so the configuration orientation
        // should be landscape.
        dc.getDisplayPolicy().getDecorInsetsInfo(ROTATION_0, dc.mBaseDisplayHeight,
                dc.mBaseDisplayWidth).mConfigFrame.set(0, 0, 1000, 990);
        dc.computeScreenConfiguration(config, ROTATION_0);
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, config.orientation);
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

    @SetupWindows(addWindows = W_INPUT_METHOD)
    @Test
    public void testInputMethodTargetUpdateWhenSwitchingOnDisplays() {
        final DisplayContent newDisplay = createNewDisplay();

        final WindowState appWin = createWindow(null, TYPE_APPLICATION, mDisplayContent, "appWin");
        final Task rootTask = mDisplayContent.getTopRootTask();
        final ActivityRecord activity = rootTask.topRunningActivity();
        doReturn(true).when(activity).shouldBeVisibleUnchecked();

        final WindowState appWin1 = createWindow(null, TYPE_APPLICATION, newDisplay, "appWin1");
        final Task rootTask1 = newDisplay.getTopRootTask();
        final ActivityRecord activity1 = rootTask1.topRunningActivity();
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

    @SetupWindows(addWindows = W_INPUT_METHOD)
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

        final Task rootTask = new TaskBuilder(mSupervisor)
                .setDisplay(dc)
                .setCreateActivity(true)
                .build();
        doReturn(true).when(rootTask).isVisible();

        final Task freeformRootTask = new TaskBuilder(mSupervisor)
                .setDisplay(dc)
                .setCreateActivity(true)
                .setWindowingMode(WINDOWING_MODE_FREEFORM)
                .build();
        doReturn(true).when(freeformRootTask).isVisible();
        freeformRootTask.getTopChild().setBounds(100, 100, 300, 400);

        assertTrue(dc.getDefaultTaskDisplayArea().isRootTaskVisible(WINDOWING_MODE_FREEFORM));

        freeformRootTask.getTopNonFinishingActivity().setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        rootTask.getTopNonFinishingActivity().setOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, dc.getOrientation());

        rootTask.getTopNonFinishingActivity().setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        freeformRootTask.getTopNonFinishingActivity().setOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, dc.getOrientation());
    }

    private void updateAllDisplayContentAndRotation(DisplayContent dc) {
        // NB updateOrientation will not revert the user orientation until a settings change
        // takes effect.
        dc.updateOrientation();
        dc.onDisplayChanged(dc);
        dc.mWmService.updateRotation(true /* alwaysSendConfiguration */,
                false /* forceRelayout */);
        waitUntilHandlersIdle();
    }

    @Test
    public void testNoSensorRevert() {
        final DisplayContent dc = mDisplayContent;
        spyOn(dc);
        doReturn(true).when(dc).getIgnoreOrientationRequest();
        final DisplayRotation dr = dc.getDisplayRotation();
        spyOn(dr);
        doReturn(false).when(dr).useDefaultSettingsProvider();
        final ActivityRecord app = new ActivityBuilder(mAtm).setCreateTask(true).build();
        app.setOrientation(SCREEN_ORIENTATION_LANDSCAPE, app);

        assertFalse(dc.getRotationReversionController().isAnyOverrideActive());
        dc.getDisplayRotation().setUserRotation(WindowManagerPolicy.USER_ROTATION_LOCKED,
                ROTATION_90, /* caller= */ "DisplayContentTests");
        updateAllDisplayContentAndRotation(dc);
        assertEquals(ROTATION_90, dc.getDisplayRotation()
                .rotationForOrientation(SCREEN_ORIENTATION_UNSPECIFIED, ROTATION_90));

        app.setOrientation(SCREEN_ORIENTATION_NOSENSOR);
        updateAllDisplayContentAndRotation(dc);
        assertTrue(dc.getRotationReversionController().isAnyOverrideActive());
        assertEquals(ROTATION_0, dc.getRotation());

        app.setOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        updateAllDisplayContentAndRotation(dc);
        assertFalse(dc.getRotationReversionController().isAnyOverrideActive());
        assertEquals(WindowManagerPolicy.USER_ROTATION_LOCKED,
                dc.getDisplayRotation().getUserRotationMode());
        assertEquals(ROTATION_90, dc.getDisplayRotation().getUserRotation());
        assertEquals(ROTATION_90, dc.getDisplayRotation()
                .rotationForOrientation(SCREEN_ORIENTATION_UNSPECIFIED, ROTATION_0));
        dc.getDisplayRotation().setUserRotation(WindowManagerPolicy.USER_ROTATION_FREE,
                ROTATION_0, /* caller= */ "DisplayContentTests");
    }

    @Test
    public void testOnDescendantOrientationRequestChanged() {
        final DisplayContent dc = createNewDisplay();
        dc.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_DISABLED);
        dc.getDefaultTaskDisplayArea().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
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
                WindowManagerPolicy.USER_ROTATION_LOCKED, ROTATION_180,
                /* caller= */ "DisplayContentTests");
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
    public void testOrientationBehind() {
        final ActivityRecord prev = new ActivityBuilder(mAtm).setCreateTask(true)
                .setScreenOrientation(getRotatedOrientation(mDisplayContent)).build();
        prev.setVisibleRequested(false);
        final ActivityRecord top = new ActivityBuilder(mAtm).setCreateTask(true)
                .setScreenOrientation(SCREEN_ORIENTATION_BEHIND).build();
        assertNotEquals(WindowConfiguration.ROTATION_UNDEFINED,
                mDisplayContent.rotationForActivityInDifferentOrientation(top));

        mDisplayContent.requestTransitionAndLegacyPrepare(WindowManager.TRANSIT_OPEN, 0);
        top.setVisibility(true);
        mDisplayContent.updateOrientation();
        // The top uses "behind", so the orientation is decided by the previous.
        assertEquals(prev, mDisplayContent.getLastOrientationSource());
        // The top will use the rotation from "prev" with fixed rotation.
        assertTrue(top.hasFixedRotationTransform());
    }

    @Test
    public void testFixedToUserRotationChanged() {
        final DisplayContent dc = createNewDisplay();
        dc.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_ENABLED);
        dc.getDisplayRotation().setUserRotation(
                WindowManagerPolicy.USER_ROTATION_LOCKED, ROTATION_0,
                /* caller= */ "DisplayContentTests");
        dc.getDefaultTaskDisplayArea().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
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
                WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);
        dc.setImeInputTarget(dc.getImeTarget(IME_TARGET_LAYERING).getWindow());
        assertEquals(dc.getImeContainer().getParentSurfaceControl(), dc.computeImeParent());
    }

    @SetupWindows(addWindows = W_ACTIVITY)
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
        dc.setImeInputTarget(dc.getImeTarget(IME_TARGET_LAYERING).getWindow());
        assertEquals(dc.getImeContainer().getParentSurfaceControl(), dc.computeImeParent());
    }

    @SetupWindows(addWindows = W_ACTIVITY)
    @Test
    public void testComputeImeParent_inputTargetNotUpdate() throws Exception {
        WindowState app1 = createWindow(null, TYPE_BASE_APPLICATION, "app1");
        WindowState app2 = createWindow(null, TYPE_BASE_APPLICATION, "app2");
        doReturn(true).when(mDisplayContent).shouldImeAttachedToApp();
        mDisplayContent.setImeLayeringTarget(app1);
        mDisplayContent.setImeInputTarget(app1);
        assertEquals(app1.mActivityRecord.getSurfaceControl(), mDisplayContent.computeImeParent());
        mDisplayContent.setImeLayeringTarget(app2);
        // Expect null means no change IME parent when the IME layering target not yet
        // request IME to be the input target.
        assertNull(mDisplayContent.computeImeParent());
    }

    @SetupWindows(addWindows = W_ACTIVITY)
    @Test
    public void testComputeImeParent_updateParentWhenTargetNotUseIme() throws Exception {
        WindowState overlay = createWindow(null, TYPE_APPLICATION_OVERLAY, "overlay");
        overlay.setBounds(100, 100, 200, 200);
        overlay.mAttrs.flags = FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM;
        WindowState app = createWindow(null, TYPE_BASE_APPLICATION, "app");
        mDisplayContent.setImeLayeringTarget(overlay);
        mDisplayContent.setImeInputTarget(app);
        assertFalse(mDisplayContent.shouldImeAttachedToApp());
        assertEquals(mDisplayContent.getImeContainer().getParentSurfaceControl(),
                mDisplayContent.computeImeParent());
    }

    @Test
    public void testComputeImeParent_remoteControlTarget() throws Exception {
        final DisplayContent dc = mDisplayContent;
        WindowState app1 = createWindow(null, TYPE_BASE_APPLICATION, "app1");
        WindowState app2 = createWindow(null, TYPE_BASE_APPLICATION, "app2");

        dc.setImeLayeringTarget(app1);
        dc.setImeInputTarget(app2);
        dc.setRemoteInsetsController(createDisplayWindowInsetsController());
        dc.getImeTarget(IME_TARGET_LAYERING).getWindow().setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);
        dc.getImeInputTarget().getWindowState().setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);

        // Expect ImeParent is null since ImeLayeringTarget and ImeInputTarget are different.
        assertNull(dc.computeImeParent());

        // ImeLayeringTarget and ImeInputTarget are updated to the same.
        dc.setImeInputTarget(app1);
        assertEquals(dc.getImeTarget(IME_TARGET_LAYERING), dc.getImeInputTarget());

        // The ImeParent should be the display.
        assertEquals(dc.getImeContainer().getParent().getSurfaceControl(), dc.computeImeParent());
    }

    @Test
    public void testInputMethodInputTarget_isClearedWhenWindowStateIsRemoved() throws Exception {
        final DisplayContent dc = createNewDisplay();

        WindowState app = createWindow(null, TYPE_BASE_APPLICATION, dc, "app");

        dc.setImeInputTarget(app);
        assertEquals(app, dc.computeImeControlTarget());

        app.removeImmediately();

        assertNull(dc.getImeInputTarget());
        assertNull(dc.computeImeControlTarget());
    }

    @Test
    public void testComputeImeControlTarget() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.setRemoteInsetsController(createDisplayWindowInsetsController());
        dc.mCurrentFocus = createWindow(null, TYPE_BASE_APPLICATION, "app");

        // Expect returning null IME control target when the focus window has not yet been the
        // IME input target (e.g. IME is restarting) in fullscreen windowing mode.
        dc.setImeInputTarget(null);
        assertFalse(dc.mCurrentFocus.inMultiWindowMode());
        assertNull(dc.computeImeControlTarget());

        dc.setImeInputTarget(dc.mCurrentFocus);
        dc.setImeLayeringTarget(dc.getImeInputTarget().getWindowState());
        assertEquals(dc.getImeInputTarget().getWindowState(), dc.computeImeControlTarget());
    }

    @Test
    public void testComputeImeControlTarget_splitscreen() throws Exception {
        final DisplayContent dc = createNewDisplay();
        dc.setImeInputTarget(createWindow(null, TYPE_BASE_APPLICATION, "app"));
        dc.getImeInputTarget().getWindowState().setWindowingMode(
                WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW);
        dc.setImeLayeringTarget(dc.getImeInputTarget().getWindowState());
        dc.setRemoteInsetsController(createDisplayWindowInsetsController());
        assertNotEquals(dc.getImeInputTarget().getWindowState(),
                dc.computeImeControlTarget());
    }

    @SetupWindows(addWindows = W_INPUT_METHOD)
    @Test
    public void testImeSecureFlagGetUpdatedAfterImeInputTarget() {
        // Verify IME window can get up-to-date secure flag update when the IME input target
        // set before setCanScreenshot called.
        final WindowState app = createWindow(null, TYPE_APPLICATION, "app");
        SurfaceControl.Transaction t = mDisplayContent.mInputMethodWindow.getPendingTransaction();
        spyOn(t);
        mDisplayContent.setImeInputTarget(app);
        mDisplayContent.mInputMethodWindow.setCanScreenshot(t, false /* canScreenshot */);

        verify(t).setSecure(eq(mDisplayContent.mInputMethodWindow.mSurfaceControl), eq(true));
    }

    @SetupWindows(addWindows = W_ACTIVITY)
    @Test
    public void testComputeImeControlTarget_notMatchParentBounds() throws Exception {
        spyOn(mAppWindow.mActivityRecord);
        doReturn(false).when(mAppWindow.mActivityRecord).matchParentBounds();
        mDisplayContent.setImeInputTarget(mAppWindow);
        mDisplayContent.setImeLayeringTarget(
            mDisplayContent.getImeInputTarget().getWindowState());
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
        win.setRequestedVisibleTypes(0, navigationBars() | statusBars());
        win.mActivityRecord.mTargetSdk = P;

        performLayout(dc);

        win.setHasSurface(true);

        final Region expected = Region.obtain();
        expected.set(dc.getBounds());
        assertEquals(expected, calculateSystemGestureExclusion(dc));

        win.setHasSurface(false);
    }

    @Test
    public void testCalculateSystemGestureExclusion_unrestricted() throws Exception {
        mWm.mConstants.mSystemGestureExcludedByPreQStickyImmersive = true;

        final DisplayContent dc = createNewDisplay();
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, dc, "win");
        win.getAttrs().flags |= FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_INSET_DECOR;
        win.getAttrs().layoutInDisplayCutoutMode = LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        win.getAttrs().privateFlags |= PRIVATE_FLAG_UNRESTRICTED_GESTURE_EXCLUSION;
        win.setSystemGestureExclusion(Collections.singletonList(dc.getBounds()));

        performLayout(dc);

        win.setHasSurface(true);

        final Region expected = Region.obtain();
        expected.set(dc.getBounds());
        assertEquals(expected, calculateSystemGestureExclusion(dc));

        win.setHasSurface(false);
    }

    @SetupWindows(addWindows = { W_ABOVE_ACTIVITY, W_ACTIVITY })
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

        displayContent.onConfigurationChanged(newConfig);

        ArgumentCaptor<LogMaker> logMakerCaptor = ArgumentCaptor.forClass(LogMaker.class);
        verify(mockLogger).write(logMakerCaptor.capture());
        assertThat(logMakerCaptor.getValue().getCategory(),
                is(MetricsProto.MetricsEvent.ACTION_PHONE_ORIENTATION_CHANGED));
        assertThat(logMakerCaptor.getValue().getSubtype(),
                is(Configuration.ORIENTATION_PORTRAIT));
    }

    @Test
    public void testHybridRotationAnimation() {
        final DisplayContent displayContent = mDefaultDisplay;
        final WindowState statusBar = createWindow(null, TYPE_STATUS_BAR, "statusBar");
        final WindowState navBar = createWindow(null, TYPE_NAVIGATION_BAR, "navBar");
        final WindowState app = createWindow(null, TYPE_BASE_APPLICATION, "app");
        final WindowState[] windows = { statusBar, navBar, app };
        makeWindowVisible(windows);
        final DisplayPolicy displayPolicy = displayContent.getDisplayPolicy();
        displayPolicy.addWindowLw(statusBar, statusBar.mAttrs);
        displayPolicy.addWindowLw(navBar, navBar.mAttrs);
        final ScreenRotationAnimation rotationAnim = new ScreenRotationAnimation(displayContent,
                displayContent.getRotation());
        spyOn(rotationAnim);
        // Assume that the display rotation is changed so it is frozen in preparation for animation.
        doReturn(true).when(rotationAnim).hasScreenshot();
        displayContent.getDisplayRotation().setRotation((displayContent.getRotation() + 1) % 4);
        displayContent.setRotationAnimation(rotationAnim);
        // The fade rotation animation also starts to hide some non-app windows.
        assertNotNull(displayContent.getAsyncRotationController());
        assertTrue(statusBar.isAnimating(PARENTS, ANIMATION_TYPE_TOKEN_TRANSFORM));

        for (WindowState w : windows) {
            w.setOrientationChanging(true);
        }
        // The display only waits for the app window to unfreeze.
        assertFalse(displayContent.shouldSyncRotationChange(statusBar));
        assertFalse(displayContent.shouldSyncRotationChange(navBar));
        assertTrue(displayContent.shouldSyncRotationChange(app));
        // If all windows animated by fade rotation animation have done the orientation change,
        // the animation controller should be cleared.
        statusBar.setOrientationChanging(false);
        navBar.setOrientationChanging(false);
        assertNull(displayContent.getAsyncRotationController());
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_WALLPAPER, W_STATUS_BAR, W_NAVIGATION_BAR,
            W_INPUT_METHOD, W_NOTIFICATION_SHADE })
    @Test
    public void testApplyTopFixedRotationTransform() {
        final DisplayPolicy displayPolicy = mDisplayContent.getDisplayPolicy();
        spyOn(displayPolicy);
        // Only non-movable (gesture) navigation bar will be animated by fixed rotation animation.
        doReturn(false).when(displayPolicy).navigationBarCanMove();
        displayPolicy.addWindowLw(mStatusBarWindow, mStatusBarWindow.mAttrs);
        displayPolicy.addWindowLw(mNavBarWindow, mNavBarWindow.mAttrs);
        displayPolicy.addWindowLw(mNotificationShadeWindow, mNotificationShadeWindow.mAttrs);
        makeWindowVisible(mStatusBarWindow, mNavBarWindow);
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
        assertTrue(mAppWindow.matchesDisplayAreaBounds());
        assertFalse(mAppWindow.areAppWindowBoundsLetterboxed());
        assertTrue(mDisplayContent.getDisplayRotation().shouldRotateSeamlessly(
                ROTATION_0 /* oldRotation */, ROTATION_90 /* newRotation */,
                false /* forceUpdate */));

        final AsyncRotationController asyncRotationController =
                mDisplayContent.getAsyncRotationController();
        assertNotNull(asyncRotationController);
        assertTrue(mStatusBarWindow.isAnimating(PARENTS, ANIMATION_TYPE_TOKEN_TRANSFORM));
        assertTrue(mNavBarWindow.isAnimating(PARENTS, ANIMATION_TYPE_TOKEN_TRANSFORM));
        // Notification shade may have its own view animation in real case so do not fade out it.
        assertFalse(mNotificationShadeWindow.isAnimating(PARENTS, ANIMATION_TYPE_TOKEN_TRANSFORM));

        // If the visibility of insets state is changed, the rotated state should be updated too.
        final int statusBarId = mStatusBarWindow.getControllableInsetProvider().getSource().getId();
        final InsetsState rotatedState = app.getFixedRotationTransformInsetsState();
        final InsetsState state = mDisplayContent.getInsetsStateController().getRawInsetsState();
        assertEquals(state.isSourceOrDefaultVisible(statusBarId, statusBars()),
                rotatedState.isSourceOrDefaultVisible(statusBarId, statusBars()));
        state.setSourceVisible(statusBarId,
                !rotatedState.isSourceOrDefaultVisible(statusBarId, statusBars()));
        mDisplayContent.getInsetsStateController().notifyInsetsChanged();
        assertEquals(state.isSourceOrDefaultVisible(statusBarId, statusBars()),
                rotatedState.isSourceOrDefaultVisible(statusBarId, statusBars()));

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

        // Associate wallpaper with the fixed rotation transform.
        final WindowToken wallpaperToken = mWallpaperWindow.mToken;
        wallpaperToken.linkFixedRotationTransform(app);

        // Force the negative offset to verify it can be updated.
        mWallpaperWindow.mXOffset = mWallpaperWindow.mYOffset = -1;
        assertTrue(mDisplayContent.mWallpaperController.updateWallpaperOffset(mWallpaperWindow,
                false /* sync */));
        assertThat(mWallpaperWindow.mXOffset).isNotEqualTo(-1);
        assertThat(mWallpaperWindow.mYOffset).isNotEqualTo(-1);

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

        // If the rotated activity requests to show IME, the IME window should use the
        // transformation from activity to lay out in the same orientation.
        LocalServices.getService(WindowManagerInternal.class).onToggleImeRequested(true /* show */,
                app.token, app.token, mDisplayContent.mDisplayId);
        assertTrue(asyncRotationController.isTargetToken(mImeWindow.mToken));
        assertTrue(mImeWindow.mToken.hasFixedRotationTransform());
        assertTrue(mImeWindow.isAnimating(PARENTS, ANIMATION_TYPE_TOKEN_TRANSFORM));

        // The fixed rotation transform can only be finished when all animation finished.
        doReturn(false).when(app2).isAnimating(anyInt(), anyInt());
        mDisplayContent.mAppTransition.notifyAppTransitionFinishedLocked(app2.token);
        assertTrue(app.hasFixedRotationTransform());
        assertTrue(app2.hasFixedRotationTransform());

        // The display should be rotated after the launch is finished.
        doReturn(false).when(app).isAnimating(anyInt(), anyInt());
        mDisplayContent.mAppTransition.notifyAppTransitionFinishedLocked(app.token);
        mStatusBarWindow.finishSeamlessRotation(t);
        mNavBarWindow.finishSeamlessRotation(t);

        // The fixed rotation should be cleared and the new rotation is applied to display.
        assertFalse(app.hasFixedRotationTransform());
        assertFalse(app2.hasFixedRotationTransform());
        assertEquals(config90.orientation, mDisplayContent.getConfiguration().orientation);
        assertNull(mDisplayContent.getAsyncRotationController());
    }

    @Test
    public void testFinishFixedRotationNoAppTransitioningTask() {
        unblockDisplayRotation(mDisplayContent);
        final ActivityRecord app = createActivityRecord(mDisplayContent);
        final Task task = app.getTask();
        final ActivityRecord app2 = new ActivityBuilder(mWm.mAtmService).setTask(task).build();
        mDisplayContent.setFixedRotationLaunchingApp(app2, (mDisplayContent.getRotation() + 1) % 4);
        doReturn(true).when(app).inTransitionSelfOrParent();
        // If the task contains a transition, this should be no-op.
        mDisplayContent.mFixedRotationTransitionListener.onAppTransitionFinishedLocked(app.token);

        assertTrue(app2.hasFixedRotationTransform());
        assertTrue(mDisplayContent.hasTopFixedRotationLaunchingApp());

        // The display should be unlikely to be in transition, but if it happens, the fixed
        // rotation should proceed to finish because the activity/task level transition is finished.
        doReturn(true).when(mDisplayContent).inTransition();
        doReturn(false).when(app).inTransitionSelfOrParent();
        // Although this notifies app instead of app2 that uses the fixed rotation, app2 should
        // still finish the transform because there is no more transition event.
        mDisplayContent.mFixedRotationTransitionListener.onAppTransitionFinishedLocked(app.token);

        assertFalse(app2.hasFixedRotationTransform());
        assertFalse(mDisplayContent.hasTopFixedRotationLaunchingApp());
    }

    @SetupWindows(addWindows = W_ACTIVITY)
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
        // Unblock the condition in PinnedTaskController#continueOrientationChangeIfNeeded.
        doNothing().when(displayContent).prepareAppTransition(anyInt());
        // Make resume-top really update the activity state.
        setBooted(mAtm);
        clearInvocations(mWm);
        // Speed up the test by a few seconds.
        mAtm.deferWindowLayout();

        final ActivityRecord homeActivity = createActivityRecord(
                displayContent.getDefaultTaskDisplayArea().getRootHomeTask());
        final ActivityRecord pinnedActivity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task pinnedTask = pinnedActivity.getRootTask();
        doReturn((displayContent.getRotation() + 1) % 4).when(displayContent)
                .rotationForActivityInDifferentOrientation(eq(homeActivity));
        // Enter PiP from fullscreen.
        pinnedTask.setWindowingMode(WINDOWING_MODE_PINNED);

        assertTrue(displayContent.hasTopFixedRotationLaunchingApp());
        assertTrue(displayContent.mPinnedTaskController.shouldDeferOrientationChange());
        verify(mWm, never()).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());
        clearInvocations(pinnedTask);

        // Assume that the PiP enter animation is done then the new bounds are set. Expect the
        // orientation update is no longer deferred.
        displayContent.mPinnedTaskController.setEnterPipBounds(pinnedTask.getBounds());
        // The Task Configuration was frozen to skip the change of orientation.
        verify(pinnedTask, never()).onConfigurationChanged(any());
        assertFalse(displayContent.mPinnedTaskController.shouldDeferOrientationChange());
        assertFalse(displayContent.hasTopFixedRotationLaunchingApp());
        assertEquals(homeActivity.getConfiguration().orientation,
                displayContent.getConfiguration().orientation);

        doReturn((displayContent.getRotation() + 1) % 4).when(displayContent)
                .rotationForActivityInDifferentOrientation(eq(pinnedActivity));
        // Leave PiP to fullscreen. Simulate the step of PipTaskOrganizer that sets the activity
        // to fullscreen, so fixed rotation will apply on it.
        pinnedActivity.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertTrue(displayContent.hasTopFixedRotationLaunchingApp());

        // Assume the animation of PipTaskOrganizer is done and then commit fullscreen to task.
        pinnedTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        displayContent.continueUpdateOrientationForDiffOrienLaunchingApp();
        assertFalse(displayContent.mPinnedTaskController.isFreezingTaskConfig(pinnedTask));
        assertEquals(pinnedActivity.getConfiguration().orientation,
                displayContent.getConfiguration().orientation);

        // No need to apply rotation if the display ignores orientation request.
        doCallRealMethod().when(displayContent).rotationForActivityInDifferentOrientation(any());
        pinnedActivity.setOverrideOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        displayContent.setIgnoreOrientationRequest(true);
        assertEquals(WindowConfiguration.ROTATION_UNDEFINED,
                displayContent.rotationForActivityInDifferentOrientation(pinnedActivity));
    }

    @Test
    public void testNoFixedRotationOnResumedScheduledApp() {
        unblockDisplayRotation(mDisplayContent);
        final ActivityRecord app = new ActivityBuilder(mAtm).setCreateTask(true).build();
        app.setVisible(false);
        app.setState(ActivityRecord.State.RESUMED, "test");
        mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_OPEN);
        mDisplayContent.mOpeningApps.add(app);
        final int newOrientation = getRotatedOrientation(mDisplayContent);
        app.setRequestedOrientation(newOrientation);

        // The condition should reject using fixed rotation because the resumed client in real case
        // might get display info immediately. And the fixed rotation adjustments haven't arrived
        // client side so the info may be inconsistent with the requested orientation.
        verify(mDisplayContent).updateOrientation(eq(app), anyBoolean());
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
        recentsActivity.setRequestedOrientation(SCREEN_ORIENTATION_NOSENSOR);
        doReturn(mock(RecentsAnimationController.class)).when(mWm).getRecentsAnimationController();

        // Do not rotate if the recents animation is animating on top.
        mDisplayContent.mFixedRotationTransitionListener.onStartRecentsAnimation(recentsActivity);
        displayRotation.setRotation((displayRotation.getRotation() + 1) % 4);
        assertFalse(displayRotation.updateRotationUnchecked(false));

        // Rotation can be updated if the recents animation is finished.
        mDisplayContent.mFixedRotationTransitionListener.onFinishRecentsAnimation();
        assertTrue(displayRotation.updateRotationUnchecked(false));

        // Rotation can be updated if the policy is not ok to animate (e.g. going to sleep).
        mDisplayContent.mFixedRotationTransitionListener.onStartRecentsAnimation(recentsActivity);
        displayRotation.setRotation((displayRotation.getRotation() + 1) % 4);
        ((TestWindowManagerPolicy) mWm.mPolicy).mOkToAnimate = false;
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
    public void testSecondaryInternalDisplayRotationFollowsDefaultDisplay() {
        // Skip freezing so the unrelated conditions in updateRotationUnchecked won't disturb.
        doNothing().when(mWm).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());

        final DisplayRotationCoordinator coordinator =
                mRootWindowContainer.getDisplayRotationCoordinator();
        final DisplayContent defaultDisplayContent = mDisplayContent;
        final DisplayRotation defaultDisplayRotation = defaultDisplayContent.getDisplayRotation();
        coordinator.removeDefaultDisplayRotationChangedCallback();

        DeviceStateController deviceStateController = mock(DeviceStateController.class);
        when(deviceStateController.shouldMatchBuiltInDisplayOrientationToReverseDefaultDisplay())
                .thenReturn(true);

        // Create secondary display
        final DisplayContent secondaryDisplayContent =
                createSecondaryDisplayContent(Display.TYPE_INTERNAL, deviceStateController);
        final DisplayRotation secondaryDisplayRotation =
                secondaryDisplayContent.getDisplayRotation();
        try {
            // TestDisplayContent bypasses this method but we need it for this test
            doCallRealMethod().when(secondaryDisplayRotation).updateRotationUnchecked(anyBoolean());

            // TestDisplayContent creates this as a mock. Lets set it up to test our use case.
            when(secondaryDisplayContent.mDeviceStateController
                    .shouldMatchBuiltInDisplayOrientationToReverseDefaultDisplay()).thenReturn(
                    true);

            // Check that secondary display registered callback
            assertEquals(secondaryDisplayRotation.mDefaultDisplayRotationChangedCallback,
                    coordinator.mDefaultDisplayRotationChangedCallback);

            // Set the default display to a known orientation. This may be a zero or non-zero
            // rotation since mDisplayInfo.logicalWidth/Height depends on the DUT's default display
            defaultDisplayRotation.updateOrientation(SCREEN_ORIENTATION_PORTRAIT, false);
            assertEquals(defaultDisplayRotation.mPortraitRotation,
                    defaultDisplayRotation.getRotation());
            assertEquals(defaultDisplayRotation.mPortraitRotation,
                    coordinator.getDefaultDisplayCurrentRotation());

            // Check that in the initial state, the secondary display is in the right rotation
            assertRotationsAreCorrectlyReversed(defaultDisplayRotation.getRotation(),
                    secondaryDisplayRotation.getRotation());

            // Update primary display rotation, check display coordinator rotation is the default
            // display's landscape rotation, and that the secondary display rotation is correct.
            defaultDisplayRotation.updateOrientation(SCREEN_ORIENTATION_LANDSCAPE, false);
            assertEquals(defaultDisplayRotation.mLandscapeRotation,
                    defaultDisplayRotation.getRotation());
            assertEquals(defaultDisplayRotation.mLandscapeRotation,
                    coordinator.getDefaultDisplayCurrentRotation());
            assertRotationsAreCorrectlyReversed(defaultDisplayRotation.getRotation(),
                    secondaryDisplayRotation.getRotation());
        } finally {
            secondaryDisplayRotation.removeDefaultDisplayRotationChangedCallback();
        }
    }

    @Test
    public void testSecondaryNonInternalDisplayDoesNotFollowDefaultDisplay() {
        // Skip freezing so the unrelated conditions in updateRotationUnchecked won't disturb.
        doNothing().when(mWm).startFreezingDisplay(anyInt(), anyInt(), any(), anyInt());

        final DisplayRotationCoordinator coordinator =
                mRootWindowContainer.getDisplayRotationCoordinator();
        coordinator.removeDefaultDisplayRotationChangedCallback();

        DeviceStateController deviceStateController = mock(DeviceStateController.class);
        when(deviceStateController.shouldMatchBuiltInDisplayOrientationToReverseDefaultDisplay())
                .thenReturn(true);

        // Create secondary non-internal displays
        createSecondaryDisplayContent(Display.TYPE_EXTERNAL, deviceStateController);
        assertNull(coordinator.mDefaultDisplayRotationChangedCallback);
        createSecondaryDisplayContent(Display.TYPE_VIRTUAL, deviceStateController);
        assertNull(coordinator.mDefaultDisplayRotationChangedCallback);
    }

    private DisplayContent createSecondaryDisplayContent(int displayType,
            @NonNull DeviceStateController deviceStateController) {
        final DisplayInfo secondaryDisplayInfo = new DisplayInfo();
        secondaryDisplayInfo.copyFrom(mDisplayInfo);
        secondaryDisplayInfo.type = displayType;

        return new TestDisplayContent.Builder(mAtm, secondaryDisplayInfo)
                .setDeviceStateController(deviceStateController)
                .build();
    }

    private static void assertRotationsAreCorrectlyReversed(@Surface.Rotation int rotation1,
            @Surface.Rotation int rotation2) {
        if (rotation1 == ROTATION_0) {
            assertEquals(rotation1, rotation2);
        } else if (rotation1 == ROTATION_180) {
            assertEquals(rotation1, rotation2);
        } else if (rotation1 == ROTATION_90) {
            assertEquals(ROTATION_270, rotation2);
        } else if (rotation1 == ROTATION_270) {
            assertEquals(ROTATION_90, rotation2);
        } else {
            throw new IllegalArgumentException("Unknown rotation: " + rotation1 + ", " + rotation2);
        }
    }

    @Test
    public void testRemoteRotation() {
        final DisplayContent dc = mDisplayContent;
        final DisplayRotation dr = dc.getDisplayRotation();
        spyOn(dr);
        doReturn((dr.getRotation() + 2) % 4).when(dr).rotationForOrientation(anyInt(), anyInt());
        final boolean[] continued = new boolean[1];
        doAnswer(invocation -> {
            continued[0] = true;
            mDisplayContent.mWaitingForConfig = false;
            mAtm.addWindowLayoutReasons(ActivityTaskManagerService.LAYOUT_REASON_CONFIG_CHANGED);
            return true;
        }).when(dc).updateDisplayOverrideConfigurationLocked();
        final boolean[] called = new boolean[1];
        mWm.mDisplayChangeController =
                new IDisplayChangeWindowController.Stub() {
                    @Override
                    public void onDisplayChange(int displayId, int fromRotation, int toRotation,
                            DisplayAreaInfo newDisplayAreaInfo,
                            IDisplayChangeWindowCallback callback) throws RemoteException {
                        called[0] = true;

                        try {
                            callback.continueDisplayChange(null);
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
    public void testRemoteDisplayChange() {
        mWm.mDisplayChangeController = mock(IDisplayChangeWindowController.class);
        final Boolean[] isWaitingForRemote = new Boolean[2];
        final var callbacks = new RemoteDisplayChangeController.ContinueRemoteDisplayChangeCallback[
                isWaitingForRemote.length];
        for (int i = 0; i < isWaitingForRemote.length; i++) {
            final int index = i;
            var callback = new RemoteDisplayChangeController.ContinueRemoteDisplayChangeCallback() {
                @Override
                public void onContinueRemoteDisplayChange(WindowContainerTransaction transaction) {
                    isWaitingForRemote[index] =
                            mDisplayContent.mRemoteDisplayChangeController
                                    .isWaitingForRemoteDisplayChange();
                }
            };
            mDisplayContent.mRemoteDisplayChangeController.performRemoteDisplayChange(
                    ROTATION_0, ROTATION_0, null /* newDisplayAreaInfo */, callback);
            callbacks[i] = callback;
        }

        // The last callback is completed, all callbacks should be notified.
        mDisplayContent.mRemoteDisplayChangeController.continueDisplayChange(callbacks[1],
                null /* transaction */);
        // When notifying 0, the callback 1 still exists.
        assertTrue(isWaitingForRemote[0]);
        assertFalse(isWaitingForRemote[1]);

        // The first callback is completed, other callbacks after it should remain.
        for (int i = 0; i < isWaitingForRemote.length; i++) {
            isWaitingForRemote[i] = null;
            mDisplayContent.mRemoteDisplayChangeController.performRemoteDisplayChange(
                    ROTATION_0, ROTATION_0, null /* newDisplayAreaInfo */, callbacks[i]);
        }
        mDisplayContent.mRemoteDisplayChangeController.continueDisplayChange(callbacks[0],
                null /* transaction */);
        assertTrue(isWaitingForRemote[0]);
        assertNull(isWaitingForRemote[1]);

        // Complete the last callback. It should be able to consume pending config change.
        mDisplayContent.mWaitingForConfig = true;
        mDisplayContent.mRemoteDisplayChangeController.continueDisplayChange(callbacks[1],
                null /* transaction */);
        assertFalse(isWaitingForRemote[1]);
        assertFalse(mDisplayContent.mWaitingForConfig);
    }

    @Test
    public void testShellTransitRotation() {
        DisplayContent dc = createNewDisplay();
        dc.setLastHasContent();

        final TestTransitionPlayer testPlayer = registerTestTransitionPlayer();
        final DisplayRotation dr = dc.getDisplayRotation();
        doCallRealMethod().when(dr).updateRotationUnchecked(anyBoolean());
        // Rotate 180 degree so the display doesn't have configuration change. This condition is
        // used for the later verification of stop-freezing (without setting mWaitingForConfig).
        doReturn((dr.getRotation() + 2) % 4).when(dr).rotationForOrientation(anyInt(), anyInt());
        mWm.mDisplayChangeController =
                new IDisplayChangeWindowController.Stub() {
                    @Override
                    public void onDisplayChange(int displayId, int fromRotation, int toRotation,
                            DisplayAreaInfo newDisplayAreaInfo,
                            IDisplayChangeWindowCallback callback) throws RemoteException {
                        try {
                            callback.continueDisplayChange(null);
                        } catch (RemoteException e) {
                            assertTrue(false);
                        }
                    }
                };

        // kill any existing rotation animation (vestigial from test setup).
        dc.setRotationAnimation(null);

        final int origRot = dc.getConfiguration().windowConfiguration.getRotation();

        mWm.updateRotation(true /* alwaysSendConfiguration */, false /* forceRelayout */);
        // Should create a transition request without performing rotation
        assertNotNull(testPlayer.mLastRequest);
        assertEquals(origRot, dc.getConfiguration().windowConfiguration.getRotation());

        // Once transition starts, rotation is applied and transition shows DC rotating.
        testPlayer.startTransition();
        waitUntilHandlersIdle();
        assertNotEquals(origRot, dc.getConfiguration().windowConfiguration.getRotation());
        assertNotNull(testPlayer.mLastReady);
        assertTrue(testPlayer.mController.isPlaying());
        WindowContainerToken dcToken = dc.mRemoteToken.toWindowContainerToken();
        assertNotEquals(testPlayer.mLastReady.getChange(dcToken).getEndRotation(),
                testPlayer.mLastReady.getChange(dcToken).getStartRotation());
        testPlayer.finish();

        // The AsyncRotationController should only exist if there is an ongoing rotation change.
        dc.finishAsyncRotationIfPossible();
        dc.setLastHasContent();
        doReturn(dr.getRotation() + 1).when(dr).rotationForOrientation(anyInt(), anyInt());
        dr.updateRotationUnchecked(true /* forceUpdate */);
        assertNotNull(dc.getAsyncRotationController());
        doReturn(dr.getRotation() - 1).when(dr).rotationForOrientation(anyInt(), anyInt());
        dr.updateRotationUnchecked(true /* forceUpdate */);
        assertNull("Cancel AsyncRotationController for the intermediate rotation changes 0->1->0",
                dc.getAsyncRotationController());
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
        Task rootTask = createTask(display);
        Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        WindowState activityWindow = createAppWindow(task, TYPE_APPLICATION, "App Window");
        WindowState behindWindow = createWindow(null, TYPE_SCREENSHOT, display, "Screenshot");

        WindowState result = display.findScrollCaptureTargetWindow(behindWindow,
                ActivityTaskManager.INVALID_TASK_ID);
        assertEquals(activityWindow, result);
    }

    @Test
    public void testFindScrollCaptureTargetWindow_cantReceiveKeys() {
        DisplayContent display = createNewDisplay();
        Task rootTask = createTask(display);
        Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        WindowState activityWindow = createAppWindow(task, TYPE_APPLICATION, "App Window");
        WindowState invisible = createWindow(null, TYPE_APPLICATION, "invisible");
        invisible.mViewVisibility = View.INVISIBLE;  // make canReceiveKeys return false

        WindowState result = display.findScrollCaptureTargetWindow(null,
                ActivityTaskManager.INVALID_TASK_ID);
        assertEquals(activityWindow, result);
    }

    @Test
    public void testFindScrollCaptureTargetWindow_secure() {
        DisplayContent display = createNewDisplay();
        Task rootTask = createTask(display);
        Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        WindowState secureWindow = createWindow(null, TYPE_APPLICATION, "Secure Window");
        secureWindow.mAttrs.flags |= FLAG_SECURE;

        WindowState result = display.findScrollCaptureTargetWindow(null,
                ActivityTaskManager.INVALID_TASK_ID);
        assertNull(result);
    }

    @Test
    public void testFindScrollCaptureTargetWindow_secureTaskId() {
        DisplayContent display = createNewDisplay();
        Task rootTask = createTask(display);
        Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        WindowState secureWindow = createWindow(null, TYPE_APPLICATION, "Secure Window");
        secureWindow.mAttrs.flags |= FLAG_SECURE;

        WindowState result = display.findScrollCaptureTargetWindow(null,  task.mTaskId);
        assertNull(result);
    }

    @Test
    public void testFindScrollCaptureTargetWindow_taskId() {
        DisplayContent display = createNewDisplay();
        Task rootTask = createTask(display);
        Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        WindowState window = createAppWindow(task, TYPE_APPLICATION, "App Window");
        WindowState behindWindow = createWindow(null, TYPE_SCREENSHOT, display, "Screenshot");

        WindowState result = display.findScrollCaptureTargetWindow(null, task.mTaskId);
        assertEquals(window, result);
    }

    @Test
    public void testFindScrollCaptureTargetWindow_taskIdCantReceiveKeys() {
        DisplayContent display = createNewDisplay();
        Task rootTask = createTask(display);
        Task task = createTaskInRootTask(rootTask, 0 /* userId */);
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
        dc.mWmService.mDisplayWindowSettings.setForcedDensity(displayInfo, forcedDensity,
                0 /* userId */);
        dc.mCurrentUniqueDisplayId = mDisplayInfo.uniqueId + "-test";
        // Trigger display changed.
        dc.onDisplayChanged();
        // Ensure overridden size and denisty match the most up-to-date values in settings for the
        // display.
        verifySizes(dc, forcedWidth, forcedHeight, forcedDensity);
    }

    @SetupWindows(addWindows = { W_ACTIVITY, W_INPUT_METHOD })
    @Test
    public void testComputeImeTarget_shouldNotCheckOutdatedImeTargetLayerWhenRemoved() {
        final WindowState child1 = createWindow(mAppWindow, FIRST_SUB_WINDOW, "child1");
        final WindowState nextImeTargetApp = createWindow(null /* parent */,
                TYPE_BASE_APPLICATION, "nextImeTargetApp");
        spyOn(child1);
        doReturn(false).when(mDisplayContent).shouldImeAttachedToApp();
        mDisplayContent.setImeLayeringTarget(child1);

        spyOn(nextImeTargetApp);
        spyOn(mAppWindow);
        doReturn(true).when(nextImeTargetApp).canBeImeTarget();
        doReturn(true).when(nextImeTargetApp).isActivityTypeHome();
        doReturn(false).when(mAppWindow).canBeImeTarget();

        child1.removeImmediately();

        verify(mDisplayContent).computeImeTarget(true);
        assertNull(mDisplayContent.getImeInputTarget());
        verify(child1, never()).needsRelativeLayeringToIme();
    }

    @SetupWindows(addWindows = W_INPUT_METHOD)
    @Test
    public void testAttachAndShowImeScreenshotOnTarget() {
        // Preparation: Simulate screen state is on.
        spyOn(mWm.mPolicy);
        doReturn(true).when(mWm.mPolicy).isScreenOn();

        // Preparation: Simulate snapshot IME surface.
        spyOn(mWm.mTaskSnapshotController);
        ScreenCapture.ScreenshotHardwareBuffer mockHwBuffer = mock(
                ScreenCapture.ScreenshotHardwareBuffer.class);
        doReturn(mock(HardwareBuffer.class)).when(mockHwBuffer).getHardwareBuffer();
        doReturn(mockHwBuffer).when(mWm.mTaskSnapshotController).snapshotImeFromAttachedTask(any());

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
        mDisplayContent.setImeInputTarget(appWin1);
        spyOn(mDisplayContent.mInputMethodWindow);
        doReturn(true).when(mDisplayContent.mInputMethodWindow).isVisible();
        mDisplayContent.getInsetsStateController().getImeSourceProvider().setImeShowing(true);

        // Test step 2: Simulate launching appWin2 and appWin1 is in app transition.
        ActivityRecord act2 = createActivityRecord(mDisplayContent);
        final WindowState appWin2 = createWindow(null, TYPE_BASE_APPLICATION, act2, "appWin2");
        appWin2.setHasSurface(true);
        assertTrue(appWin2.canBeImeTarget());
        doReturn(true).when(appWin1).inTransitionSelfOrParent();

        // Test step 3: Verify appWin2 will be the next IME target and the IME snapshot surface will
        // be attached and shown on the display at this time.
        mDisplayContent.computeImeTarget(true);
        assertEquals(appWin2, mDisplayContent.getImeTarget(IME_TARGET_LAYERING));
        assertTrue(mDisplayContent.shouldImeAttachedToApp());

        verify(mDisplayContent, atLeast(1)).showImeScreenshot();
        verify(mWm.mTaskSnapshotController).snapshotImeFromAttachedTask(appWin1.getTask());
        assertNotNull(mDisplayContent.mImeScreenshot);
    }

    @SetupWindows(addWindows = W_INPUT_METHOD)
    @Test
    public void testShowImeScreenshot() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, activity, "win");
        task.getDisplayContent().prepareAppTransition(TRANSIT_CLOSE);
        doReturn(true).when(task).okToAnimate();
        ArrayList<WindowContainer> sources = new ArrayList<>();
        sources.add(activity);

        mDisplayContent.setImeLayeringTarget(win);
        spyOn(mDisplayContent);

        // Expecting the IME screenshot only be attached when performing task closing transition.
        task.applyAnimation(null, TRANSIT_OLD_TASK_CLOSE, false /* enter */,
                false /* isVoiceInteraction */, sources);
        verify(mDisplayContent).showImeScreenshot();

        clearInvocations(mDisplayContent);
        activity.applyAnimation(null, TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE, false /* enter */,
                false /* isVoiceInteraction */, sources);
        verify(mDisplayContent, never()).showImeScreenshot();
    }

    @SetupWindows(addWindows = W_INPUT_METHOD)
    @Test
    public void testShowImeScreenshot_removeCurSnapshotBeforeCreateNext() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, activity, "win");

        mDisplayContent.setImeLayeringTarget(win);
        mDisplayContent.setImeInputTarget(win);
        spyOn(mDisplayContent);
        spyOn(mDisplayContent.mInputMethodWindow);
        doReturn(true).when(mDisplayContent.mInputMethodWindow).isVisible();
        mDisplayContent.getInsetsStateController().getImeSourceProvider().setImeShowing(true);

        // Verify when the timing of 2 showImeScreenshot invocations are very close, will first
        // detach the current snapshot then create the next one.
        mDisplayContent.showImeScreenshot();
        DisplayContent.ImeScreenshot curSnapshot = mDisplayContent.mImeScreenshot;
        spyOn(curSnapshot);
        mDisplayContent.showImeScreenshot();
        verify(curSnapshot).detach(any());
        assertNotNull(mDisplayContent.mImeScreenshot);
        assertNotEquals(curSnapshot, mDisplayContent.mImeScreenshot);
    }

    @UseTestDisplay(addWindows = {W_INPUT_METHOD})
    @Test
    public void testRemoveImeScreenshot_whenTargetSurfaceWasInvisible() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, activity, "win");
        win.onSurfaceShownChanged(true);
        makeWindowVisible(win, mDisplayContent.mInputMethodWindow);
        task.getDisplayContent().prepareAppTransition(TRANSIT_CLOSE);
        doReturn(true).when(task).okToAnimate();
        ArrayList<WindowContainer> sources = new ArrayList<>();
        sources.add(activity);

        mDisplayContent.setImeLayeringTarget(win);
        mDisplayContent.setImeInputTarget(win);
        mDisplayContent.getInsetsStateController().getImeSourceProvider().setImeShowing(true);
        task.applyAnimation(null, TRANSIT_OLD_TASK_CLOSE, false /* enter */,
                false /* isVoiceInteraction */, sources);
        assertNotNull(mDisplayContent.mImeScreenshot);

        win.onSurfaceShownChanged(false);
        assertNull(mDisplayContent.mImeScreenshot);
    }

    @UseTestDisplay(addWindows = {W_INPUT_METHOD})
    @Test
    public void testRemoveImeScreenshot_whenWindowRemoveImmediately() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, activity, "win");
        makeWindowVisible(mDisplayContent.mInputMethodWindow);

        mDisplayContent.setImeLayeringTarget(win);
        mDisplayContent.setImeInputTarget(win);
        mDisplayContent.getInsetsStateController().getImeSourceProvider().setImeShowing(true);
        mDisplayContent.showImeScreenshot();
        assertNotNull(mDisplayContent.mImeScreenshot);

        // Expect IME snapshot will be removed when the win is IME layering target and invoked
        // removeImeSurfaceByTarget.
        win.removeImmediately();
        assertNull(mDisplayContent.mImeScreenshot);
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

    /**
     * Creates a TestDisplayContent using the constructor that takes in display width and height as
     * parameters and validates that the newly-created TestDisplayContent's DisplayInfo and
     * WindowConfiguration match the parameters passed into the constructor. Additionally, this test
     * checks that device-specific overrides are not applied.
     */
    @Test
    public void testCreateTestDisplayContentFromDimensions() {
        final int displayWidth = 540;
        final int displayHeight = 960;
        final int density = 192;
        final int expectedWidthDp = 450; // = 540/(192/160)
        final int expectedHeightDp = 800; // = 960/(192/160)
        final int windowingMode = WINDOWING_MODE_FULLSCREEN;
        final boolean ignoreOrientationRequests = false;
        final float fixedOrientationLetterboxRatio = 0;
        final DisplayContent testDisplayContent = new TestDisplayContent.Builder(mAtm, displayWidth,
                displayHeight).setDensityDpi(density).build();

        // test display info
        final DisplayInfo di = testDisplayContent.getDisplayInfo();
        assertEquals(displayWidth, di.logicalWidth);
        assertEquals(displayHeight, di.logicalHeight);
        assertEquals(density, di.logicalDensityDpi);

        // test configuration
        final Configuration config = testDisplayContent.getConfiguration();
        assertEquals(expectedWidthDp, config.screenWidthDp);
        assertEquals(expectedHeightDp, config.screenHeightDp);
        final WindowConfiguration windowConfig = config.windowConfiguration;
        assertEquals(displayWidth, windowConfig.getBounds().width());
        assertEquals(displayHeight, windowConfig.getBounds().height());
        assertEquals(windowingMode, windowConfig.getWindowingMode());
        assertEquals(Configuration.SCREENLAYOUT_SIZE_NORMAL,
                config.screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK);

        // test misc display overrides
        assertEquals(ignoreOrientationRequests, testDisplayContent.mSetIgnoreOrientationRequest);
        assertEquals(fixedOrientationLetterboxRatio,
                mWm.mLetterboxConfiguration.getFixedOrientationLetterboxAspectRatio(),
                0 /* delta */);
    }

    /**
     * Creates a TestDisplayContent using the constructor that takes in a DisplayInfo as a parameter
     * and validates that the newly-created TestDisplayContent's DisplayInfo and WindowConfiguration
     * match the width, height, and density values set in the DisplayInfo passed as a parameter.
     * Additionally, this test checks that device-specific overrides are not applied.
     */
    @Test
    public void testCreateTestDisplayContentFromDisplayInfo() {
        final int displayWidth = 1000;
        final int displayHeight = 2000;
        final int windowingMode = WINDOWING_MODE_FULLSCREEN;
        final boolean ignoreOrientationRequests = false;
        final float fixedOrientationLetterboxRatio = 0;
        final DisplayInfo testDisplayInfo = new DisplayInfo();
        mContext.getDisplay().getDisplayInfo(testDisplayInfo);
        testDisplayInfo.logicalWidth = displayWidth;
        testDisplayInfo.logicalHeight = displayHeight;
        testDisplayInfo.logicalDensityDpi = TestDisplayContent.DEFAULT_LOGICAL_DISPLAY_DENSITY;
        final DisplayContent testDisplayContent = new TestDisplayContent.Builder(mAtm,
                testDisplayInfo).build();

        // test display info
        final DisplayInfo di = testDisplayContent.getDisplayInfo();
        assertEquals(displayWidth, di.logicalWidth);
        assertEquals(displayHeight, di.logicalHeight);
        assertEquals(TestDisplayContent.DEFAULT_LOGICAL_DISPLAY_DENSITY, di.logicalDensityDpi);

        // test configuration
        final WindowConfiguration windowConfig = testDisplayContent.getConfiguration()
                .windowConfiguration;
        assertEquals(displayWidth, windowConfig.getBounds().width());
        assertEquals(displayHeight, windowConfig.getBounds().height());
        assertEquals(windowingMode, windowConfig.getWindowingMode());
        assertEquals(Configuration.SCREENLAYOUT_SIZE_LARGE, testDisplayContent
                .getConfiguration().screenLayout & Configuration.SCREENLAYOUT_SIZE_MASK);

        // test misc display overrides
        assertEquals(ignoreOrientationRequests, testDisplayContent.mSetIgnoreOrientationRequest);
        assertEquals(fixedOrientationLetterboxRatio,
                mWm.mLetterboxConfiguration.getFixedOrientationLetterboxAspectRatio(),
                0 /* delta */);
    }

    /**
     * Verifies {@link DisplayContent#remove} should not resume home root task on the removing
     * display.
     */
    @Test
    public void testNotResumeHomeRootTaskOnRemovingDisplay() {
        // Create a display which supports system decoration and allows reparenting root tasks to
        // another display when the display is removed.
        final DisplayContent display = new TestDisplayContent.Builder(
                mAtm, 1000, 1500).setSystemDecorations(true).build();
        doReturn(false).when(display).shouldDestroyContentOnRemove();

        // Put home root task on the display.
        final Task homeRootTask = new TaskBuilder(mSupervisor)
                .setDisplay(display).setActivityType(ACTIVITY_TYPE_HOME).build();

        // Put a finishing standard activity which will be reparented.
        final Task rootTask = createTaskWithActivity(display.getDefaultTaskDisplayArea(),
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, ON_TOP, true /* twoLevelTask */);
        rootTask.topRunningActivity().makeFinishingLocked();

        clearInvocations(homeRootTask);
        display.remove();

        // The removed display should have no focused root task and its home root task should never
        // resume.
        assertNull(display.getFocusedRootTask());
        verify(homeRootTask, never()).resumeTopActivityUncheckedLocked(any(), any());
    }

    /**
     * Verifies the correct activity is returned when querying the top running activity.
     */
    @Test
    public void testTopRunningActivity() {
        final DisplayContent display = mRootWindowContainer.getDefaultDisplay();
        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        final Task rootTask = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord activity = rootTask.getTopNonFinishingActivity();

        // Create empty root task on top.
        final Task emptyRootTask = new TaskBuilder(mSupervisor).build();

        // Make sure the top running activity is not affected when keyguard is not locked.
        assertTopRunningActivity(activity, display);

        // Check to make sure activity not reported when it cannot show on lock and lock is on.
        doReturn(true).when(keyguard).isKeyguardLocked(anyInt());
        assertEquals(activity, display.topRunningActivity());
        assertNull(display.topRunningActivity(true /* considerKeyguardState */));

        // Move root task with activity to top.
        rootTask.moveToFront("testRootTaskToFront");
        assertEquals(rootTask, display.getFocusedRootTask());
        assertEquals(activity, display.topRunningActivity());
        assertNull(display.topRunningActivity(true /* considerKeyguardState */));

        // Add activity that should be shown on the keyguard.
        final ActivityRecord showWhenLockedActivity = new ActivityBuilder(mAtm)
                .setTask(rootTask)
                .setActivityFlags(FLAG_SHOW_WHEN_LOCKED)
                .build();

        // Ensure the show when locked activity is returned.
        assertTopRunningActivity(showWhenLockedActivity, display);

        // Move empty root task to front. The running activity in focusable root task which below
        // the empty root task should be returned.
        emptyRootTask.moveToFront("emptyRootTaskToFront");
        assertEquals(rootTask, display.getFocusedRootTask());
        assertTopRunningActivity(showWhenLockedActivity, display);
    }

    private static void assertTopRunningActivity(ActivityRecord top, DisplayContent display) {
        assertEquals(top, display.topRunningActivity());
        assertEquals(top, display.topRunningActivity(true /* considerKeyguardState */));
    }

    @Test
    public void testKeyguardGoingAwayWhileAodShown() {
        mDisplayContent.getDisplayPolicy().setAwake(true);

        final WindowState appWin = createWindow(null, TYPE_APPLICATION, mDisplayContent, "appWin");
        final ActivityRecord activity = appWin.mActivityRecord;

        mAtm.mKeyguardController.setKeyguardShown(appWin.getDisplayId(), true /* keyguardShowing */,
                true /* aodShowing */);
        assertFalse(activity.isVisibleRequested());

        mAtm.mKeyguardController.keyguardGoingAway(appWin.getDisplayId(), 0 /* flags */);
        assertTrue(activity.isVisibleRequested());
    }

    @Test
    public void testRemoveRootTaskInWindowingModes() {
        removeRootTaskTests(() -> mRootWindowContainer.removeRootTasksInWindowingModes(
                WINDOWING_MODE_FULLSCREEN));
    }

    @Test
    public void testRemoveRootTaskWithActivityTypes() {
        removeRootTaskTests(() -> mRootWindowContainer.removeRootTasksWithActivityTypes(
                ACTIVITY_TYPE_STANDARD));
    }

    @SetupWindows(addWindows = W_INPUT_METHOD)
    @Test
    public void testImeChildWindowFocusWhenImeLayeringTargetChanges() {
        final WindowState imeChildWindow =
                createWindow(mImeWindow, TYPE_APPLICATION_ATTACHED_DIALOG, "imeChildWindow");
        makeWindowVisibleAndDrawn(imeChildWindow, mImeWindow);
        assertTrue(imeChildWindow.canReceiveKeys());
        mDisplayContent.setInputMethodWindowLocked(mImeWindow);

        // Verify imeChildWindow can be focused window if the next IME target requests IME visible.
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "imeAppTarget");
        mDisplayContent.setImeLayeringTarget(imeAppTarget);
        spyOn(imeAppTarget);
        doReturn(true).when(imeAppTarget).isRequestedVisible(ime());
        assertEquals(imeChildWindow, mDisplayContent.findFocusedWindow());

        // Verify imeChildWindow doesn't be focused window if the next IME target does not
        // request IME visible.
        final WindowState nextImeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "nextImeAppTarget");
        mDisplayContent.setImeLayeringTarget(nextImeAppTarget);
        assertNotEquals(imeChildWindow, mDisplayContent.findFocusedWindow());
    }

    @SetupWindows(addWindows = W_INPUT_METHOD)
    @Test
    public void testImeMenuDialogFocusWhenImeLayeringTargetChanges() {
        final WindowState imeMenuDialog =
                createWindow(null, TYPE_INPUT_METHOD_DIALOG, "imeMenuDialog");
        makeWindowVisibleAndDrawn(imeMenuDialog, mImeWindow);
        assertTrue(imeMenuDialog.canReceiveKeys());
        mDisplayContent.setInputMethodWindowLocked(mImeWindow);

        // Verify imeMenuDialog can be focused window if the next IME target requests IME visible.
        final WindowState imeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "imeAppTarget");
        mDisplayContent.setImeLayeringTarget(imeAppTarget);
        spyOn(imeAppTarget);
        doReturn(true).when(imeAppTarget).isRequestedVisible(ime());
        assertEquals(imeMenuDialog, mDisplayContent.findFocusedWindow());

        // Verify imeMenuDialog doesn't be focused window if the next IME target is closing.
        final WindowState nextImeAppTarget =
                createWindow(null, TYPE_BASE_APPLICATION, mDisplayContent, "nextImeAppTarget");
        makeWindowVisibleAndDrawn(nextImeAppTarget);
        nextImeAppTarget.mActivityRecord.commitVisibility(false, false);
        mDisplayContent.setImeLayeringTarget(nextImeAppTarget);
        assertNotEquals(imeMenuDialog, mDisplayContent.findFocusedWindow());
    }

    @Test
    public void testKeepClearAreasMultipleWindows() {
        final WindowState w1 = createWindow(null, TYPE_NAVIGATION_BAR, mDisplayContent, "w1");
        final Rect rect1 = new Rect(0, 0, 10, 10);
        w1.setKeepClearAreas(Arrays.asList(rect1), Collections.emptyList());
        final WindowState w2 = createWindow(null, TYPE_NOTIFICATION_SHADE, mDisplayContent, "w2");
        final Rect rect2 = new Rect(10, 10, 20, 20);
        w2.setKeepClearAreas(Arrays.asList(rect2), Collections.emptyList());

        // No keep clear areas on display, because the windows are not visible
        assertEquals(Collections.emptySet(), mDisplayContent.getKeepClearAreas());

        makeWindowVisible(w1);

        // The returned keep-clear areas contain the areas just from the visible window
        assertEquals(new ArraySet(Arrays.asList(rect1)), mDisplayContent.getKeepClearAreas());

        makeWindowVisible(w1, w2);

        // The returned keep-clear areas contain the areas from all visible windows
        assertEquals(new ArraySet(Arrays.asList(rect1, rect2)),
                mDisplayContent.getKeepClearAreas());
    }

    @Test
    public void testMayImeShowOnLaunchingActivity_negativeWhenSoftInputModeHidden() {
        final ActivityRecord app = createActivityRecord(mDisplayContent);
        final WindowState appWin = createWindow(null, TYPE_BASE_APPLICATION, app, "appWin");
        createWindow(null, TYPE_APPLICATION_STARTING, app, "startingWin");
        app.mStartingData = mock(SnapshotStartingData.class);
        // Assume the app has shown IME before and warm launching with a snapshot window.
        doReturn(true).when(app.mStartingData).hasImeSurface();

        // Expect true when this IME focusable activity will show IME during launching.
        assertTrue(WindowManager.LayoutParams.mayUseInputMethod(appWin.mAttrs.flags));
        assertTrue(mDisplayContent.mayImeShowOnLaunchingActivity(app));

        // Not expect IME will be shown during launching if the app's softInputMode is hidden.
        appWin.mAttrs.softInputMode = SOFT_INPUT_STATE_ALWAYS_HIDDEN;
        assertFalse(mDisplayContent.mayImeShowOnLaunchingActivity(app));
        appWin.mAttrs.softInputMode = SOFT_INPUT_STATE_HIDDEN;
        assertFalse(mDisplayContent.mayImeShowOnLaunchingActivity(app));
    }

    private void removeRootTaskTests(Runnable runnable) {
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task rootTask1 = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task rootTask2 = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task rootTask3 = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task rootTask4 = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task task1 = new TaskBuilder(mSupervisor).setParentTask(rootTask1).build();
        final Task task2 = new TaskBuilder(mSupervisor).setParentTask(rootTask2).build();
        final Task task3 = new TaskBuilder(mSupervisor).setParentTask(rootTask3).build();
        final Task task4 = new TaskBuilder(mSupervisor).setParentTask(rootTask4).build();

        // Reordering root tasks while removing root tasks.
        doAnswer(invocation -> {
            taskDisplayArea.positionChildAt(POSITION_TOP, rootTask3, false /*includingParents*/);
            return true;
        }).when(mSupervisor).removeTask(eq(task4), anyBoolean(), anyBoolean(), any());

        // Removing root tasks from the display while removing root tasks.
        doAnswer(invocation -> {
            taskDisplayArea.removeRootTask(rootTask2);
            return true;
        }).when(mSupervisor).removeTask(eq(task2), anyBoolean(), anyBoolean(), any());

        runnable.run();
        verify(mSupervisor).removeTask(eq(task4), anyBoolean(), anyBoolean(), any());
        verify(mSupervisor).removeTask(eq(task3), anyBoolean(), anyBoolean(), any());
        verify(mSupervisor).removeTask(eq(task2), anyBoolean(), anyBoolean(), any());
        verify(mSupervisor).removeTask(eq(task1), anyBoolean(), anyBoolean(), any());
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

    private static void verifySizes(DisplayContent displayContent, int expectedBaseWidth,
            int expectedBaseHeight, int expectedBaseDensity, float expectedBaseXDpi,
            float expectedBaseYDpi) {
        assertEquals(expectedBaseWidth, displayContent.mBaseDisplayWidth);
        assertEquals(expectedBaseHeight, displayContent.mBaseDisplayHeight);
        assertEquals(expectedBaseDensity, displayContent.mBaseDisplayDensity);
        assertEquals(expectedBaseXDpi, displayContent.mBaseDisplayPhysicalXDpi, 1.0f /* delta */);
        assertEquals(expectedBaseYDpi, displayContent.mBaseDisplayPhysicalYDpi, 1.0f /* delta */);
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

    static int getRotatedOrientation(DisplayContent dc) {
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
