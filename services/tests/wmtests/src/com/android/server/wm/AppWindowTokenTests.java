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

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_AFTER_ANIM;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_BEFORE_ANIM;
import static com.android.server.wm.WindowStateAnimator.STACK_CLIP_NONE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
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
                false, false);
        waitUntilHandlersIdle();
        assertHasStartingWindow(mActivity);
        mActivity.removeStartingWindow();
        waitUntilHandlersIdle();
        assertNoStartingWindow(mActivity);
    }

    @Test
    @FlakyTest(bugId = 130392471)
    public void testAddRemoveRace() {
        // There was once a race condition between adding and removing starting windows
        for (int i = 0; i < 1000; i++) {
            final ActivityRecord appToken = createIsolatedTestActivityRecord();

            appToken.addStartingWindow(mPackageName,
                    android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                    false, false);
            appToken.removeStartingWindow();
            waitUntilHandlersIdle();
            assertNoStartingWindow(appToken);

            appToken.getParent().getParent().removeImmediately();
        }
    }

    @Test
    public void testTransferStartingWindow() {
        final ActivityRecord activity1 = createIsolatedTestActivityRecord();
        final ActivityRecord activity2 = createIsolatedTestActivityRecord();
        activity1.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        activity2.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, activity1.appToken.asBinder(),
                true, true, false, true, false, false);
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
                            true, false, false);
                });
        activity1.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(activity1);
        assertHasStartingWindow(activity2);
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
                false, false);
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
        mStack.setBounds(stackBounds);
        mTask.setBounds(taskBounds);

        // Check that anim bounds for freeform window match task bounds
        mTask.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(mTask.getBounds(), mActivity.getAnimationBounds(STACK_CLIP_NONE));

        // STACK_CLIP_AFTER_ANIM should use task bounds since they will be clipped by
        // bounds animation layer.
        mTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertEquals(mTask.getBounds(), mActivity.getAnimationBounds(STACK_CLIP_AFTER_ANIM));

        // STACK_CLIP_BEFORE_ANIM should use stack bounds since it won't be clipped later.
        mTask.setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
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
