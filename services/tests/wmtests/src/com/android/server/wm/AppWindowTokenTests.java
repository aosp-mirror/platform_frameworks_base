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
import static android.view.WindowManager.TRANSIT_ACTIVITY_OPEN;

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

import org.junit.Before;
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

    ActivityStack mStack;
    Task mTask;
    ActivityRecord mActivity;

    private final String mPackageName = getInstrumentation().getTargetContext().getPackageName();

    @Before
    public void setUp() throws Exception {
        mStack = createTaskStackOnDisplay(mDisplayContent);
        mTask = createTaskInStack(mStack, 0 /* userId */);
        mActivity = WindowTestUtils.createTestActivityRecord(mDisplayContent);

        mTask.addChild(mActivity, 0);
    }

    @Test
    @Presubmit
    public void testAddWindow_Order() {
        assertEquals(0, mActivity.getChildCount());

        final WindowState win1 = createWindow(null, TYPE_APPLICATION, mActivity, "win1");
        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, mActivity,
                "startingWin");
        final WindowState baseWin = createWindow(null, TYPE_BASE_APPLICATION, mActivity, "baseWin");
        final WindowState win4 = createWindow(null, TYPE_APPLICATION, mActivity, "win4");

        // Should not contain the windows that were added above.
        assertEquals(4, mActivity.getChildCount());
        assertTrue(mActivity.mChildren.contains(win1));
        assertTrue(mActivity.mChildren.contains(startingWin));
        assertTrue(mActivity.mChildren.contains(baseWin));
        assertTrue(mActivity.mChildren.contains(win4));

        // The starting window should be on-top of all other windows.
        assertEquals(startingWin, mActivity.mChildren.peekLast());

        // The base application window should be below all other windows.
        assertEquals(baseWin, mActivity.mChildren.peekFirst());
        mActivity.removeImmediately();
    }

    @Test
    @Presubmit
    public void testFindMainWindow() {
        assertNull(mActivity.findMainWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, mActivity, "window1");
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, mActivity, "window11");
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, mActivity, "window12");
        assertEquals(window1, mActivity.findMainWindow());
        window1.mAnimatingExit = true;
        assertEquals(window1, mActivity.findMainWindow());
        final WindowState window2 = createWindow(null, TYPE_APPLICATION_STARTING, mActivity,
                "window2");
        assertEquals(window2, mActivity.findMainWindow());
        mActivity.removeImmediately();
    }

    @Test
    @Presubmit
    public void testGetTopFullscreenOpaqueWindow() {
        assertNull(mActivity.getTopFullscreenOpaqueWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, mActivity, "window1");
        final WindowState window11 = createWindow(null, TYPE_APPLICATION, mActivity, "window11");
        final WindowState window12 = createWindow(null, TYPE_APPLICATION, mActivity, "window12");
        assertEquals(window12, mActivity.getTopFullscreenOpaqueWindow());
        window12.mAttrs.width = 500;
        assertEquals(window11, mActivity.getTopFullscreenOpaqueWindow());
        window11.mAttrs.width = 500;
        assertEquals(window1, mActivity.getTopFullscreenOpaqueWindow());
        window1.mAttrs.alpha = 0f;
        assertNull(mActivity.getTopFullscreenOpaqueWindow());
        mActivity.removeImmediately();
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testLandscapeSeascapeRotationByApp() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mActivity);
        mActivity.addWindow(appWindow);

        // Set initial orientation and update.
        mActivity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        mDisplayContent.updateOrientation(
                mDisplayContent.getRequestedOverrideConfiguration(),
                null /* freezeThisOneIfNeeded */, false /* forceUpdate */);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mDisplayContent.getLastOrientation());
        appWindow.mResizeReported = false;

        // Update the orientation to perform 180 degree rotation and check that resize was reported.
        mActivity.setOrientation(SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
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

    @Test
    public void testLandscapeSeascapeRotationByPolicy() {
        // This instance has been spied in {@link TestDisplayContent}.
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("RotationByPolicy");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mActivity);
        mActivity.addWindow(appWindow);

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
        mActivity.setVisible(true);

        mActivity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        mActivity.setOccludesParent(false);
        // Can specify orientation if app doesn't occludes parent.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mActivity.getOrientation());

        mActivity.setOccludesParent(true);
        mActivity.setVisible(false);
        // Can not specify orientation if app isn't visible even though it occludes parent.
        assertEquals(SCREEN_ORIENTATION_UNSET, mActivity.getOrientation());
        // Can specify orientation if the current orientation candidate is orientation behind.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE,
                mActivity.getOrientation(SCREEN_ORIENTATION_BEHIND));
    }

    @Test
    @Presubmit
    public void testKeyguardFlagsDuringRelaunch() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.flags |= FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD;
        attrs.setTitle("AppWindow");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mActivity);

        // Add window with show when locked flag
        mActivity.addWindow(appWindow);
        assertTrue(mActivity.containsShowWhenLockedWindow() && mActivity.containsDismissKeyguardWindow());

        // Start relaunching
        mActivity.startRelaunching();
        assertTrue(mActivity.containsShowWhenLockedWindow() && mActivity.containsDismissKeyguardWindow());

        // Remove window and make sure that we still report back flag
        mActivity.removeChild(appWindow);
        assertTrue(mActivity.containsShowWhenLockedWindow() && mActivity.containsDismissKeyguardWindow());

        // Finish relaunching and ensure flag is now not reported
        mActivity.finishRelaunching();
        assertFalse(
                mActivity.containsShowWhenLockedWindow() || mActivity.containsDismissKeyguardWindow());
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
        mActivity.setVisible(true);

        // Assert orientation is unspecified to start.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, mActivity.getOrientation());

        mActivity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mActivity.getOrientation());

        mDisplayContent.removeAppToken(mActivity.token);
        // Assert orientation is unset to after container is removed.
        assertEquals(SCREEN_ORIENTATION_UNSET, mActivity.getOrientation());

        // Reset display frozen state
        mWm.mDisplayFrozen = false;
    }

    @Test
    public void testRespectTopFullscreenOrientation() {
        final Configuration displayConfig = mActivity.mDisplayContent.getConfiguration();
        final Configuration activityConfig = mActivity.getConfiguration();
        mActivity.setOrientation(SCREEN_ORIENTATION_PORTRAIT);

        assertEquals(Configuration.ORIENTATION_PORTRAIT, displayConfig.orientation);
        assertEquals(Configuration.ORIENTATION_PORTRAIT, activityConfig.orientation);

        final ActivityRecord topActivity = WindowTestUtils.createTestActivityRecord(mStack);
        topActivity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        assertEquals(Configuration.ORIENTATION_LANDSCAPE, displayConfig.orientation);
        // Although the activity requested portrait, it is not the top activity that determines
        // the display orientation. So it should be able to inherit the orientation from parent.
        // Otherwise its configuration will be inconsistent that its orientation is portrait but
        // other screen configurations are in landscape, e.g. screenWidthDp, screenHeightDp, and
        // window configuration.
        assertEquals(Configuration.ORIENTATION_LANDSCAPE, activityConfig.orientation);
    }

    @Test
    public void testReportOrientationChange() {
        mActivity.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        mDisplayContent.getDisplayRotation().setFixedToUserRotation(
                IWindowManager.FIXED_TO_USER_ROTATION_ENABLED);
        reset(mTask);
        mActivity.reportDescendantOrientationChangeIfNeeded();
        verify(mTask).onConfigurationChanged(any(Configuration.class));
    }

    @Test
    @FlakyTest(bugId = 131176283)
    public void testCreateRemoveStartingWindow() {
        mActivity.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false);
        waitUntilHandlersIdle();
        assertHasStartingWindow(mActivity);
        mActivity.removeStartingWindow();
        waitUntilHandlersIdle();
        assertNoStartingWindow(mActivity);
    }

    @Test
    public void testAddRemoveRace() {
        // There was once a race condition between adding and removing starting windows
        final ActivityRecord appToken = mAppWindow.mActivityRecord;
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
        final ActivityRecord activity1 = createIsolatedTestActivityRecord();
        final ActivityRecord activity2 = createIsolatedTestActivityRecord();
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
        final ActivityRecord activity1 = createIsolatedTestActivityRecord();
        final ActivityRecord activity2 = createIsolatedTestActivityRecord();
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
        final ActivityRecord activity1 = createIsolatedTestActivityRecord();
        final ActivityRecord activity2 = createIsolatedTestActivityRecord();
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
        assertTrue(activity2.applyAnimation(null, TRANSIT_ACTIVITY_OPEN, true, false, sources));
    }

    @Test
    public void testTransferStartingWindowFromFinishingActivity() {
        mActivity.addStartingWindow(mPackageName, android.R.style.Theme, null /* compatInfo */,
                "Test", 0 /* labelRes */, 0 /* icon */, 0 /* logo */, 0 /* windowFlags */,
                null /* transferFrom */, true /* newTask */, true /* taskSwitch */,
                false /* processRunning */, false /* allowTaskSnapshot */,
                false /* activityCreate */);
        waitUntilHandlersIdle();
        assertHasStartingWindow(mActivity);
        mActivity.mStartingWindowState = ActivityRecord.STARTING_WINDOW_SHOWN;

        doCallRealMethod().when(mStack).startActivityLocked(
                any(), any(), anyBoolean(), anyBoolean(), any());
        // Make mVisibleSetFromTransferredStartingWindow true.
        final ActivityRecord middle = new ActivityTestsBase.ActivityBuilder(mWm.mAtmService)
                .setTask(mTask).build();
        mStack.startActivityLocked(middle, null /* focusedTopActivity */,
                false /* newTask */, false /* keepCurTransition */, null /* options */);
        middle.makeFinishingLocked();

        assertNull(mActivity.startingWindow);
        assertHasStartingWindow(middle);

        final ActivityRecord top = new ActivityTestsBase.ActivityBuilder(mWm.mAtmService)
                .setTask(mTask).build();
        // Expect the visibility should be updated to true when transferring starting window from
        // a visible activity.
        top.setVisible(false);
        // The finishing middle should be able to transfer starting window to top.
        mStack.startActivityLocked(top, null /* focusedTopActivity */,
                false /* newTask */, false /* keepCurTransition */, null /* options */);

        assertNull(middle.startingWindow);
        assertHasStartingWindow(top);
        assertTrue(top.isVisible());
        // The activity was visible by mVisibleSetFromTransferredStartingWindow, so after its
        // starting window is transferred, it should restore to invisible.
        assertFalse(middle.isVisible());
    }

    @Test
    public void testTransferStartingWindowSetFixedRotation() {
        final ActivityRecord topActivity = createTestActivityRecordForGivenTask(mTask);
        topActivity.setVisible(false);
        mTask.positionChildAt(topActivity, POSITION_TOP);
        mActivity.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false);
        waitUntilHandlersIdle();

        // Make activities to have different rotation from it display and set fixed rotation
        // transform to activity1.
        int rotation = (mDisplayContent.getRotation() + 1) % 4;
        mDisplayContent.setFixedRotationLaunchingApp(mActivity, rotation);
        doReturn(rotation).when(mDisplayContent)
                .rotationForActivityInDifferentOrientation(topActivity);

        // Make sure the fixed rotation transform linked to activity2 when adding starting window
        // on activity2.
        topActivity.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, mActivity.appToken.asBinder(),
                false, false, false, true, false);
        waitUntilHandlersIdle();
        assertTrue(topActivity.hasFixedRotationTransform());
    }

    private ActivityRecord createIsolatedTestActivityRecord() {
        final ActivityStack taskStack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(taskStack, 0 /* userId */);
        return createTestActivityRecordForGivenTask(task);
    }

    private ActivityRecord createTestActivityRecordForGivenTask(Task task) {
        final ActivityRecord activity =
                WindowTestUtils.createTestActivityRecord(mDisplayContent);
        task.addChild(activity, 0);
        waitUntilHandlersIdle();
        return activity;
    }

    @Test
    public void testTryTransferStartingWindowFromHiddenAboveToken() {
        // Add two tasks on top of each other.
        final ActivityRecord activityTop = createIsolatedTestActivityRecord();
        final ActivityRecord activityBottom =
                createTestActivityRecordForGivenTask(activityTop.getTask());

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
        final Rect stackBounds = new Rect(0, 0, 1000, 600);
        final Rect taskBounds = new Rect(100, 400, 600, 800);
        // Set the bounds and windowing mode to window configuration directly, otherwise the
        // testing setups may be discarded by configuration resolving.
        mStack.getWindowConfiguration().setBounds(stackBounds);
        mTask.getWindowConfiguration().setBounds(taskBounds);
        mActivity.getWindowConfiguration().setBounds(taskBounds);

        // Check that anim bounds for freeform window match task bounds
        mTask.getWindowConfiguration().setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(mTask.getBounds(), mActivity.getAnimationBounds(STACK_CLIP_NONE));

        // STACK_CLIP_AFTER_ANIM should use task bounds since they will be clipped by
        // bounds animation layer.
        mTask.getWindowConfiguration().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertEquals(mTask.getBounds(), mActivity.getAnimationBounds(STACK_CLIP_AFTER_ANIM));

        // Even the activity is smaller than task and it is not aligned to the top-left corner of
        // task, the animation bounds the same as task and position should be zero because in real
        // case the letterbox will fill the remaining area in task.
        final Rect halfBounds = new Rect(taskBounds);
        halfBounds.scale(0.5f);
        mActivity.getWindowConfiguration().setBounds(halfBounds);
        final Point animationPosition = new Point();
        mActivity.getAnimationPosition(animationPosition);

        assertEquals(taskBounds, mActivity.getAnimationBounds(STACK_CLIP_AFTER_ANIM));
        assertEquals(new Point(0, 0), animationPosition);

        // STACK_CLIP_BEFORE_ANIM should use stack bounds since it won't be clipped later.
        mTask.getWindowConfiguration().setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertEquals(mStack.getBounds(), mActivity.getAnimationBounds(STACK_CLIP_BEFORE_ANIM));
    }

    @Test
    public void testHasStartingWindow() {
        final WindowManager.LayoutParams attrs =
                new WindowManager.LayoutParams(TYPE_APPLICATION_STARTING);
        final WindowTestUtils.TestWindowState startingWindow = createWindowState(attrs, mActivity);
        mActivity.startingDisplayed = true;
        mActivity.addWindow(startingWindow);
        assertTrue("Starting window should be present", mActivity.hasStartingWindow());
        mActivity.startingDisplayed = false;
        assertTrue("Starting window should be present", mActivity.hasStartingWindow());

        mActivity.removeChild(startingWindow);
        assertFalse("Starting window should not be present", mActivity.hasStartingWindow());
    }

    private void assertHasStartingWindow(ActivityRecord atoken) {
        assertNotNull(atoken.startingSurface);
        assertNotNull(atoken.mStartingData);
        assertNotNull(atoken.startingWindow);
    }

    private void assertNoStartingWindow(ActivityRecord atoken) {
        assertNull(atoken.startingSurface);
        assertNull(atoken.startingWindow);
        assertNull(atoken.mStartingData);
        atoken.forAllWindows(windowState -> {
            assertFalse(windowState.getBaseType() == TYPE_APPLICATION_STARTING);
        }, true);
    }
}
