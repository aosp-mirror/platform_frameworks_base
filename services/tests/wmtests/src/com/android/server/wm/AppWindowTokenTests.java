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

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_OPEN;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doCallRealMethod;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_AFTER_ANIM;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_BEFORE_ANIM;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_NONE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.IWindowManager;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * Tests for the {@link ActivityRecord} class.
 *
 * Build/Install/Run:
 *  atest WmTests:AppWindowTokenTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppWindowTokenTests extends WindowTestsBase {

    private final String mPackageName = getInstrumentation().getTargetContext().getPackageName();

    @Test
    @Presubmit
    public void testAddWindow_Order() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        assertEquals(0, activity.getChildCount());

        final WindowState win1 = createWindow(null, TYPE_APPLICATION, activity, "win1");
        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, activity,
                "startingWin");
        final WindowState baseWin = createWindow(null, TYPE_BASE_APPLICATION, activity, "baseWin");
        final WindowState win4 = createWindow(null, TYPE_APPLICATION, activity, "win4");

        // Should not contain the windows that were added above.
        assertEquals(4, activity.getChildCount());
        assertTrue(activity.mChildren.contains(win1));
        assertTrue(activity.mChildren.contains(startingWin));
        assertTrue(activity.mChildren.contains(baseWin));
        assertTrue(activity.mChildren.contains(win4));

        // The starting window should be on-top of all other windows.
        assertEquals(startingWin, activity.mChildren.peekLast());

        // The base application window should be below all other windows.
        assertEquals(baseWin, activity.mChildren.peekFirst());
        activity.removeImmediately();
    }

    @Test
    @Presubmit
    public void testFindMainWindow() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        assertNull(activity.findMainWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "window1");
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, activity, "window11");
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, activity, "window12");
        assertEquals(window1, activity.findMainWindow());
        window1.mAnimatingExit = true;
        assertEquals(window1, activity.findMainWindow());
        final WindowState window2 = createWindow(null, TYPE_APPLICATION_STARTING, activity,
                "window2");
        assertEquals(window2, activity.findMainWindow());
        activity.removeImmediately();
    }

    @Test
    @Presubmit
    public void testGetTopFullscreenOpaqueWindow() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        assertNull(activity.getTopFullscreenOpaqueWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "window1");
        final WindowState window11 = createWindow(null, TYPE_APPLICATION, activity, "window11");
        final WindowState window12 = createWindow(null, TYPE_APPLICATION, activity, "window12");
        assertEquals(window12, activity.getTopFullscreenOpaqueWindow());
        window12.mAttrs.width = 500;
        assertEquals(window11, activity.getTopFullscreenOpaqueWindow());
        window11.mAttrs.width = 500;
        assertEquals(window1, activity.getTopFullscreenOpaqueWindow());
        window1.mAttrs.alpha = 0f;
        assertNull(activity.getTopFullscreenOpaqueWindow());
        activity.removeImmediately();
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    @FlakyTest(bugId = 131005232)
    public void testLandscapeSeascapeRotationByApp() {
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopNonFinishingActivity();
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow");
        final TestWindowState appWindow = createWindowState(attrs, activity);
        activity.addWindow(appWindow);

        // Set initial orientation and update.
        activity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        mDisplayContent.updateOrientation(
                mDisplayContent.getRequestedOverrideConfiguration(),
                null /* freezeThisOneIfNeeded */, false /* forceUpdate */);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mDisplayContent.getLastOrientation());
        appWindow.mResizeReported = false;

        // Update the orientation to perform 180 degree rotation and check that resize was reported.
        activity.setOrientation(SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        mDisplayContent.updateOrientation(
                mDisplayContent.getRequestedOverrideConfiguration(),
                null /* freezeThisOneIfNeeded */, false /* forceUpdate */);
        // In this test, DC will not get config update. Set the waiting flag to false.
        mDisplayContent.mWaitingForConfig = false;
        mWm.mRoot.performSurfacePlacement();
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, mDisplayContent.getLastOrientation());
        assertTrue(appWindow.mResizeReported);
        appWindow.removeImmediately();
    }

    @UseTestDisplay(addWindows = W_ACTIVITY)
    @Test
    public void testLandscapeSeascapeRotationByPolicy() {
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopNonFinishingActivity();
        // This instance has been spied in {@link TestDisplayContent}.
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("RotationByPolicy");
        final TestWindowState appWindow = createWindowState(attrs, activity);
        activity.addWindow(appWindow);

        // Set initial orientation and update.
        performRotation(displayRotation, Surface.ROTATION_90);
        appWindow.mResizeReported = false;

        // Update the rotation to perform 180 degree rotation and check that resize was reported.
        performRotation(displayRotation, Surface.ROTATION_270);
        assertTrue(appWindow.mResizeReported);

        appWindow.removeImmediately();
    }

    private void performRotation(DisplayRotation spiedRotation, int rotationToReport) {
        doReturn(rotationToReport).when(spiedRotation).rotationForOrientation(anyInt(), anyInt());
        mWm.updateRotation(false, false);
    }

    @Test
    @Presubmit
    public void testGetOrientation() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity.setVisible(true);

        activity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        activity.setOccludesParent(false);
        // Can specify orientation if app doesn't occludes parent.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, activity.getOrientation());

        activity.setOccludesParent(true);
        activity.setVisible(false);
        // Can not specify orientation if app isn't visible even though it occludes parent.
        assertEquals(SCREEN_ORIENTATION_UNSET, activity.getOrientation());
        // Can specify orientation if the current orientation candidate is orientation behind.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE,
                activity.getOrientation(SCREEN_ORIENTATION_BEHIND));
    }

    @Test
    @Presubmit
    public void testKeyguardFlagsDuringRelaunch() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.flags |= FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD;
        attrs.setTitle("AppWindow");
        final TestWindowState appWindow = createWindowState(attrs, activity);

        // Add window with show when locked flag
        activity.addWindow(appWindow);
        assertTrue(activity.containsShowWhenLockedWindow()
                && activity.containsDismissKeyguardWindow());

        // Start relaunching
        activity.startRelaunching();
        assertTrue(activity.containsShowWhenLockedWindow()
                && activity.containsDismissKeyguardWindow());

        // Remove window and make sure that we still report back flag
        activity.removeChild(appWindow);
        assertTrue(activity.containsShowWhenLockedWindow()
                && activity.containsDismissKeyguardWindow());

        // Finish relaunching and ensure flag is now not reported
        activity.finishRelaunching();
        assertFalse(activity.containsShowWhenLockedWindow()
                || activity.containsDismissKeyguardWindow());
    }

    @Test
    public void testStuckExitingWindow() {
        final WindowState closingWindow = createWindow(null, FIRST_APPLICATION_WINDOW,
                "closingWindow");
        closingWindow.mAnimatingExit = true;
        closingWindow.mRemoveOnExit = true;
        closingWindow.mActivityRecord.commitVisibility(
                false /* visible */, true /* performLayout */);

        // We pretended that we were running an exit animation, but that should have been cleared up
        // by changing visibility of ActivityRecord
        closingWindow.removeIfPossible();
        assertTrue(closingWindow.mRemoved);
    }

    @Test
    public void testSetOrientation() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity.setVisible(true);

        // Assert orientation is unspecified to start.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, activity.getOrientation());

        activity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, activity.getOrientation());

        mDisplayContent.removeAppToken(activity.token);
        // Assert orientation is unset to after container is removed.
        assertEquals(SCREEN_ORIENTATION_UNSET, activity.getOrientation());

        // Reset display frozen state
        mWm.mDisplayFrozen = false;
    }

    @UseTestDisplay
    @Test
    public void testRespectTopFullscreenOrientation() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Configuration displayConfig = activity.mDisplayContent.getConfiguration();
        final Configuration activityConfig = activity.getConfiguration();
        activity.setOrientation(SCREEN_ORIENTATION_PORTRAIT);

        assertEquals(Configuration.ORIENTATION_PORTRAIT, displayConfig.orientation);
        assertEquals(Configuration.ORIENTATION_PORTRAIT, activityConfig.orientation);

        final ActivityRecord topActivity = createActivityRecord(activity.getTask());
        topActivity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        assertEquals(Configuration.ORIENTATION_LANDSCAPE, displayConfig.orientation);
        // Although the activity requested portrait, it is not the top activity that determines
        // the display orientation. So it should be able to inherit the orientation from parent.
        // Otherwise its configuration will be inconsistent that its orientation is portrait but
        // other screen configurations are in landscape, e.g. screenWidthDp, screenHeightDp, and
        // window configuration.
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, activityConfig.orientation);
    }

    @UseTestDisplay
    @Test
    public void testReportOrientationChange() {
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(mDisplayContent).setCreateActivity(true).build();
        final ActivityRecord activity = task.getTopNonFinishingActivity();
        activity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        mDisplayContent.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_ENABLED);
        reset(task);
        activity.reportDescendantOrientationChangeIfNeeded();
        verify(task).onConfigurationChanged(any(Configuration.class));
    }

    @Test
    @FlakyTest(bugId = 131176283)
    public void testCreateRemoveStartingWindow() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false);
        waitUntilHandlersIdle();
        assertHasStartingWindow(activity);
        activity.removeStartingWindow();
        waitUntilHandlersIdle();
        assertNoStartingWindow(activity);
    }

    @Test
    public void testAddRemoveRace() {
        // There was once a race condition between adding and removing starting windows
        final ActivityRecord appToken = new ActivityBuilder(mAtm).setCreateTask(true).build();
        for (int i = 0; i < 1000; i++) {
            appToken.addStartingWindow(mPackageName,
                    android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                    false);
            appToken.removeStartingWindow();
            waitUntilHandlersIdle();
            assertNoStartingWindow(appToken);
        }
    }

    @Test
    public void testTransferStartingWindow() {
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity1.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false);
        waitUntilHandlersIdle();
        activity2.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, activity1.appToken.asBinder(),
                true, true, false, true, false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(activity1);
        assertHasStartingWindow(activity2);
    }

    @Test
    public void testTransferStartingWindowWhileCreating() {
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        ((TestWindowManagerPolicy) activity1.mWmService.mPolicy).setRunnableWhenAddingSplashScreen(
                () -> {
                    // Surprise, ...! Transfer window in the middle of the creation flow.
                    activity2.addStartingWindow(mPackageName,
                            android.R.style.Theme, null, "Test", 0, 0, 0, 0,
                            activity1.appToken.asBinder(), true, true, false,
                            true, false);
                });
        activity1.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(activity1);
        assertHasStartingWindow(activity2);
    }

    @Test
    public void testTransferStartingWindowCanAnimate() {
        final ActivityRecord activity1 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord activity2 = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity1.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false);
        waitUntilHandlersIdle();
        activity2.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, activity1.appToken.asBinder(),
                true, true, false, true, false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(activity1);
        assertHasStartingWindow(activity2);

        // Assert that bottom activity is allowed to do animation.
        ArrayList<WindowContainer> sources = new ArrayList<>();
        sources.add(activity2);
        doReturn(true).when(activity2).okToAnimate();
        doReturn(true).when(activity2).isAnimating();
        assertTrue(activity2.applyAnimation(null, TRANSIT_OLD_ACTIVITY_OPEN, true, false, sources));
    }

    @Test
    public void testTransferStartingWindowFromFinishingActivity() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = activity.getTask();
        activity.addStartingWindow(mPackageName, android.R.style.Theme, null /* compatInfo */,
                "Test", 0 /* labelRes */, 0 /* icon */, 0 /* logo */, 0 /* windowFlags */,
                null /* transferFrom */, true /* newTask */, true /* taskSwitch */,
                false /* processRunning */, false /* allowTaskSnapshot */,
                false /* activityCreate */);
        waitUntilHandlersIdle();
        assertHasStartingWindow(activity);
        activity.mStartingWindowState = ActivityRecord.STARTING_WINDOW_SHOWN;

        doCallRealMethod().when(task).startActivityLocked(
                any(), any(), anyBoolean(), anyBoolean(), any());
        // Make mVisibleSetFromTransferredStartingWindow true.
        final ActivityRecord middle = new ActivityBuilder(mAtm).setTask(task).build();
        task.startActivityLocked(middle, null /* focusedTopActivity */,
                false /* newTask */, false /* keepCurTransition */, null /* options */);
        middle.makeFinishingLocked();

        assertNull(activity.mStartingWindow);
        assertHasStartingWindow(middle);

        final ActivityRecord top = new ActivityBuilder(mAtm).setTask(task).build();
        // Expect the visibility should be updated to true when transferring starting window from
        // a visible activity.
        top.setVisible(false);
        // The finishing middle should be able to transfer starting window to top.
        task.startActivityLocked(top, null /* focusedTopActivity */,
                false /* newTask */, false /* keepCurTransition */, null /* options */);

        assertNull(middle.mStartingWindow);
        assertHasStartingWindow(top);
        assertTrue(top.isVisible());
        // The activity was visible by mVisibleSetFromTransferredStartingWindow, so after its
        // starting window is transferred, it should restore to invisible.
        assertFalse(middle.isVisible());
    }

    @Test
    public void testTransferStartingWindowSetFixedRotation() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final Task task = activity.getTask();
        final ActivityRecord topActivity = new ActivityBuilder(mAtm).setTask(task).build();
        topActivity.setVisible(false);
        task.positionChildAt(topActivity, POSITION_TOP);
        activity.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false);
        waitUntilHandlersIdle();

        // Make activities to have different rotation from it display and set fixed rotation
        // transform to activity1.
        int rotation = (mDisplayContent.getRotation() + 1) % 4;
        mDisplayContent.setFixedRotationLaunchingApp(activity, rotation);
        doReturn(rotation).when(mDisplayContent)
                .rotationForActivityInDifferentOrientation(topActivity);

        // Make sure the fixed rotation transform linked to activity2 when adding starting window
        // on activity2.
        topActivity.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, activity.appToken.asBinder(),
                false, false, false, true, false);
        waitUntilHandlersIdle();
        assertTrue(topActivity.hasFixedRotationTransform());
    }

    @Test
    public void testTryTransferStartingWindowFromHiddenAboveToken() {
        // Add two tasks on top of each other.
        final ActivityRecord activityTop = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final ActivityRecord activityBottom = new ActivityBuilder(mAtm).build();
        activityTop.getTask().addChild(activityBottom, 0);

        // Add a starting window.
        activityTop.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false);
        waitUntilHandlersIdle();

        // Make the top one invisible, and try transferring the starting window from the top to the
        // bottom one.
        activityTop.setVisibility(false, false);
        activityBottom.transferStartingWindowFromHiddenAboveTokenIfNeeded();
        waitUntilHandlersIdle();

        // Assert that the bottom window now has the starting window.
        assertNoStartingWindow(activityTop);
        assertHasStartingWindow(activityBottom);
    }

    @Test
    public void testTransitionAnimationBounds() {
        removeGlobalMinSizeRestriction();
        final Task task = new TaskBuilder(mSupervisor)
                .setCreateParentTask(true).setCreateActivity(true).build();
        final Task rootTask = task.getRootTask();
        final ActivityRecord activity = task.getTopNonFinishingActivity();
        final Rect stackBounds = new Rect(0, 0, 1000, 600);
        final Rect taskBounds = new Rect(100, 400, 600, 800);
        // Set the bounds and windowing mode to window configuration directly, otherwise the
        // testing setups may be discarded by configuration resolving.
        rootTask.getWindowConfiguration().setBounds(stackBounds);
        task.getWindowConfiguration().setBounds(taskBounds);
        activity.getWindowConfiguration().setBounds(taskBounds);

        // Check that anim bounds for freeform window match task bounds
        task.getWindowConfiguration().setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(task.getBounds(), activity.getAnimationBounds(STACK_CLIP_NONE));

        // STACK_CLIP_AFTER_ANIM should use task bounds since they will be clipped by
        // bounds animation layer.
        task.getWindowConfiguration().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertEquals(task.getBounds(), activity.getAnimationBounds(STACK_CLIP_AFTER_ANIM));

        // Even the activity is smaller than task and it is not aligned to the top-left corner of
        // task, the animation bounds the same as task and position should be zero because in real
        // case the letterbox will fill the remaining area in task.
        final Rect halfBounds = new Rect(taskBounds);
        halfBounds.scale(0.5f);
        activity.getWindowConfiguration().setBounds(halfBounds);
        final Point animationPosition = new Point();
        activity.getAnimationPosition(animationPosition);

        assertEquals(taskBounds, activity.getAnimationBounds(STACK_CLIP_AFTER_ANIM));
        assertEquals(new Point(0, 0), animationPosition);

        // STACK_CLIP_BEFORE_ANIM should use stack bounds since it won't be clipped later.
        task.getWindowConfiguration().setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertEquals(rootTask.getBounds(), activity.getAnimationBounds(STACK_CLIP_BEFORE_ANIM));
    }

    @Test
    public void testHasStartingWindow() {
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        final WindowManager.LayoutParams attrs =
                new WindowManager.LayoutParams(TYPE_APPLICATION_STARTING);
        final TestWindowState startingWindow = createWindowState(attrs, activity);
        activity.startingDisplayed = true;
        activity.addWindow(startingWindow);
        assertTrue("Starting window should be present", activity.hasStartingWindow());
        activity.startingDisplayed = false;
        assertTrue("Starting window should be present", activity.hasStartingWindow());

        activity.removeChild(startingWindow);
        assertFalse("Starting window should not be present", activity.hasStartingWindow());
    }

    private void assertHasStartingWindow(ActivityRecord atoken) {
        assertNotNull(atoken.mStartingSurface);
        assertNotNull(atoken.mStartingData);
        assertNotNull(atoken.mStartingWindow);
    }

    private void assertNoStartingWindow(ActivityRecord atoken) {
        assertNull(atoken.mStartingSurface);
        assertNull(atoken.mStartingWindow);
        assertNull(atoken.mStartingData);
        atoken.forAllWindows(windowState -> {
            assertFalse(windowState.getBaseType() == TYPE_APPLICATION_STARTING);
        }, true);
    }
}
