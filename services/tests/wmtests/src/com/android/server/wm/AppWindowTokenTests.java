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
import static android.view.WindowManager.TRANSIT_UNSET;

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
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

/**
 * Tests for the {@link AppWindowToken} class.
 *
 * Build/Install/Run:
 *  atest WmTests:AppWindowTokenTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppWindowTokenTests extends WindowTestsBase {

    TaskStack mStack;
    Task mTask;
    AppWindowToken mToken;

    private final String mPackageName = getInstrumentation().getTargetContext().getPackageName();

    @Before
    public void setUp() throws Exception {
        mStack = createTaskStackOnDisplay(mDisplayContent);
        mTask = createTaskInStack(mStack, 0 /* userId */);
        mToken = WindowTestUtils.createTestAppWindowToken(mDisplayContent);

        mTask.addChild(mToken, 0);
    }

    @Test
    @Presubmit
    public void testAddWindow_Order() {
        assertEquals(0, mToken.getChildCount());

        final WindowState win1 = createWindow(null, TYPE_APPLICATION, mToken, "win1");
        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, mToken,
                "startingWin");
        final WindowState baseWin = createWindow(null, TYPE_BASE_APPLICATION, mToken, "baseWin");
        final WindowState win4 = createWindow(null, TYPE_APPLICATION, mToken, "win4");

        // Should not contain the windows that were added above.
        assertEquals(4, mToken.getChildCount());
        assertTrue(mToken.mChildren.contains(win1));
        assertTrue(mToken.mChildren.contains(startingWin));
        assertTrue(mToken.mChildren.contains(baseWin));
        assertTrue(mToken.mChildren.contains(win4));

        // The starting window should be on-top of all other windows.
        assertEquals(startingWin, mToken.mChildren.peekLast());

        // The base application window should be below all other windows.
        assertEquals(baseWin, mToken.mChildren.peekFirst());
        mToken.removeImmediately();
    }

    @Test
    @Presubmit
    public void testFindMainWindow() {
        assertNull(mToken.findMainWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, mToken, "window1");
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, mToken, "window11");
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, mToken, "window12");
        assertEquals(window1, mToken.findMainWindow());
        window1.mAnimatingExit = true;
        assertEquals(window1, mToken.findMainWindow());
        final WindowState window2 = createWindow(null, TYPE_APPLICATION_STARTING, mToken,
                "window2");
        assertEquals(window2, mToken.findMainWindow());
        mToken.removeImmediately();
    }

    @Test
    @Presubmit
    public void testGetTopFullscreenWindow() {
        assertNull(mToken.getTopFullscreenWindow());

        final WindowState window1 = createWindow(null, TYPE_BASE_APPLICATION, mToken, "window1");
        final WindowState window11 = createWindow(null, TYPE_APPLICATION, mToken, "window11");
        final WindowState window12 = createWindow(null, TYPE_APPLICATION, mToken, "window12");
        assertEquals(window12, mToken.getTopFullscreenWindow());
        window12.mAttrs.width = 500;
        assertEquals(window11, mToken.getTopFullscreenWindow());
        window11.mAttrs.width = 500;
        assertEquals(window1, mToken.getTopFullscreenWindow());
        mToken.removeImmediately();
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testLandscapeSeascapeRotationByApp() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mToken);
        mToken.addWindow(appWindow);

        // Set initial orientation and update.
        mToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        mDisplayContent.updateOrientation(
                mDisplayContent.getRequestedOverrideConfiguration(),
                null /* freezeThisOneIfNeeded */, false /* forceUpdate */);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mDisplayContent.getLastOrientation());
        appWindow.mResizeReported = false;

        // Update the orientation to perform 180 degree rotation and check that resize was reported.
        mToken.setOrientation(SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        mDisplayContent.updateOrientation(
                mDisplayContent.getRequestedOverrideConfiguration(),
                null /* freezeThisOneIfNeeded */, false /* forceUpdate */);
        // In this test, DC will not get config update. Set the waiting flag to false.
        mDisplayContent.mWaitingForConfig = false;
        mWm.mRoot.performSurfacePlacement(false /* recoveringMemory */);
        assertEquals(SCREEN_ORIENTATION_REVERSE_LANDSCAPE, mDisplayContent.getLastOrientation());
        assertTrue(appWindow.mResizeReported);
        appWindow.removeImmediately();
    }

    @Test
    public void testLandscapeSeascapeRotationByPolicy() {
        // This instance has been spied in {@link TestActivityDisplay}.
        final DisplayRotation displayRotation = mDisplayContent.getDisplayRotation();

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("RotationByPolicy");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mToken);
        mToken.addWindow(appWindow);

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
    public void testSizeCompatBounds() {
        final Rect fixedBounds = mToken.getRequestedOverrideConfiguration().windowConfiguration
                .getBounds();
        fixedBounds.set(0, 0, 1200, 1600);
        mToken.getRequestedOverrideConfiguration().windowConfiguration.setAppBounds(fixedBounds);
        final Configuration newParentConfig = mTask.getConfiguration();

        // Change the size of the container to two times smaller with insets.
        newParentConfig.windowConfiguration.setAppBounds(200, 0, 800, 800);
        final Rect containerAppBounds = newParentConfig.windowConfiguration.getAppBounds();
        final Rect containerBounds = newParentConfig.windowConfiguration.getBounds();
        containerBounds.set(0, 0, 600, 800);
        mToken.onConfigurationChanged(newParentConfig);

        assertTrue(mToken.inSizeCompatMode());
        assertEquals(containerAppBounds, mToken.getBounds());
        assertEquals((float) containerAppBounds.width() / fixedBounds.width(),
                mToken.getSizeCompatScale(), 0.0001f /* delta */);

        // Change the width of the container to two times bigger.
        containerAppBounds.set(0, 0, 2400, 1600);
        containerBounds.set(containerAppBounds);
        mToken.onConfigurationChanged(newParentConfig);

        assertTrue(mToken.inSizeCompatMode());
        // Don't scale up, so the bounds keep the same as the fixed width.
        assertEquals(fixedBounds.width(), mToken.getBounds().width());
        // Assert the position is horizontal center.
        assertEquals((containerAppBounds.width() - fixedBounds.width()) / 2,
                mToken.getBounds().left);
        assertEquals(1f, mToken.getSizeCompatScale(), 0.0001f  /* delta */);

        // Change the width of the container to fit the fixed bounds.
        containerBounds.set(0, 0, 1200, 2000);
        mToken.onConfigurationChanged(newParentConfig);
        // Assert don't use fixed bounds because the region is enough.
        assertFalse(mToken.inSizeCompatMode());
    }

    @Test
    @Presubmit
    public void testGetOrientation() {
        mToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        mToken.setOccludesParent(false);
        // Can specify orientation if app doesn't occludes parent.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mToken.getOrientation());

        mToken.setOccludesParent(true);
        mToken.setHidden(true);
        mToken.sendingToBottom = true;
        // Can not specify orientation if app isn't visible even though it occludes parent.
        assertEquals(SCREEN_ORIENTATION_UNSET, mToken.getOrientation());
        // Can specify orientation if the current orientation candidate is orientation behind.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE,
                mToken.getOrientation(SCREEN_ORIENTATION_BEHIND));
    }

    @Test
    @Presubmit
    public void testKeyguardFlagsDuringRelaunch() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.flags |= FLAG_SHOW_WHEN_LOCKED | FLAG_DISMISS_KEYGUARD;
        attrs.setTitle("AppWindow");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mToken);

        // Add window with show when locked flag
        mToken.addWindow(appWindow);
        assertTrue(mToken.containsShowWhenLockedWindow() && mToken.containsDismissKeyguardWindow());

        // Start relaunching
        mToken.startRelaunching();
        assertTrue(mToken.containsShowWhenLockedWindow() && mToken.containsDismissKeyguardWindow());

        // Remove window and make sure that we still report back flag
        mToken.removeChild(appWindow);
        assertTrue(mToken.containsShowWhenLockedWindow() && mToken.containsDismissKeyguardWindow());

        // Finish relaunching and ensure flag is now not reported
        mToken.finishRelaunching();
        assertFalse(
                mToken.containsShowWhenLockedWindow() || mToken.containsDismissKeyguardWindow());
    }

    @Test
    public void testStuckExitingWindow() {
        final WindowState closingWindow = createWindow(null, FIRST_APPLICATION_WINDOW,
                "closingWindow");
        closingWindow.mAnimatingExit = true;
        closingWindow.mRemoveOnExit = true;
        closingWindow.mAppToken.commitVisibility(null, false /* visible */, TRANSIT_UNSET,
                true /* performLayout */, false /* isVoiceInteraction */);

        // We pretended that we were running an exit animation, but that should have been cleared up
        // by changing visibility of AppWindowToken
        closingWindow.removeIfPossible();
        assertTrue(closingWindow.mRemoved);
    }

    @Test
    public void testSetOrientation() {
        // Assert orientation is unspecified to start.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, mToken.getOrientation());

        mToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mToken.getOrientation());

        mDisplayContent.removeAppToken(mToken.token);
        // Assert orientation is unset to after container is removed.
        assertEquals(SCREEN_ORIENTATION_UNSET, mToken.getOrientation());

        // Reset display frozen state
        mWm.mDisplayFrozen = false;
    }

    @Test
    public void testReportOrientationChange() {
        mToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        mDisplayContent.getDisplayRotation().setFixedToUserRotation(
                DisplayRotation.FIXED_TO_USER_ROTATION_ENABLED);

        mTask.mTaskRecord = Mockito.mock(TaskRecord.class, RETURNS_DEEP_STUBS);
        mToken.reportDescendantOrientationChangeIfNeeded();

        verify(mTask.mTaskRecord).onConfigurationChanged(any(Configuration.class));
    }

    @Test
    @FlakyTest(bugId = 131176283)
    public void testCreateRemoveStartingWindow() {
        mToken.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        assertHasStartingWindow(mToken);
        mToken.removeStartingWindow();
        waitUntilHandlersIdle();
        assertNoStartingWindow(mToken);
    }

    @Test
    @FlakyTest(bugId = 130392471)
    public void testAddRemoveRace() {
        // There was once a race condition between adding and removing starting windows
        for (int i = 0; i < 1000; i++) {
            final AppWindowToken appToken = createIsolatedTestAppWindowToken();

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
        final AppWindowToken token1 = createIsolatedTestAppWindowToken();
        final AppWindowToken token2 = createIsolatedTestAppWindowToken();
        token1.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        token2.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, token1.appToken.asBinder(),
                true, true, false, true, false, false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(token1);
        assertHasStartingWindow(token2);
    }

    @Test
    public void testTransferStartingWindowWhileCreating() {
        final AppWindowToken token1 = createIsolatedTestAppWindowToken();
        final AppWindowToken token2 = createIsolatedTestAppWindowToken();
        ((TestWindowManagerPolicy) token1.mWmService.mPolicy).setRunnableWhenAddingSplashScreen(
                () -> {
                    // Surprise, ...! Transfer window in the middle of the creation flow.
                    token2.addStartingWindow(mPackageName,
                            android.R.style.Theme, null, "Test", 0, 0, 0, 0,
                            token1.appToken.asBinder(), true, true, false,
                            true, false, false);
                });
        token1.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();
        assertNoStartingWindow(token1);
        assertHasStartingWindow(token2);
    }

    private AppWindowToken createIsolatedTestAppWindowToken() {
        final TaskStack taskStack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(taskStack, 0 /* userId */);
        return createTestAppWindowTokenForGivenTask(task);
    }

    private AppWindowToken createTestAppWindowTokenForGivenTask(Task task) {
        final AppWindowToken appToken =
                WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        task.addChild(appToken, 0);
        waitUntilHandlersIdle();
        return appToken;
    }

    @Test
    public void testTryTransferStartingWindowFromHiddenAboveToken() {
        // Add two tasks on top of each other.
        final AppWindowToken tokenTop = createIsolatedTestAppWindowToken();
        final AppWindowToken tokenBottom =
                createTestAppWindowTokenForGivenTask(tokenTop.getTask());

        // Add a starting window.
        tokenTop.addStartingWindow(mPackageName,
                android.R.style.Theme, null, "Test", 0, 0, 0, 0, null, true, true, false, true,
                false, false);
        waitUntilHandlersIdle();

        // Make the top one invisible, and try transferring the starting window from the top to the
        // bottom one.
        tokenTop.setVisibility(false, false);
        tokenBottom.transferStartingWindowFromHiddenAboveTokenIfNeeded();
        waitUntilHandlersIdle();

        // Assert that the bottom window now has the starting window.
        assertNoStartingWindow(tokenTop);
        assertHasStartingWindow(tokenBottom);
    }

    @Test
    public void testTransitionAnimationBounds() {
        final Rect stackBounds = new Rect(0, 0, 1000, 600);
        final Rect taskBounds = new Rect(100, 400, 600, 800);
        mStack.setBounds(stackBounds);
        mTask.setBounds(taskBounds);

        // Check that anim bounds for freeform window match task bounds
        mTask.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertEquals(taskBounds, mToken.getAnimationBounds(STACK_CLIP_NONE));

        // STACK_CLIP_AFTER_ANIM should use task bounds since they will be clipped by
        // bounds animation layer.
        mTask.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertEquals(taskBounds, mToken.getAnimationBounds(STACK_CLIP_AFTER_ANIM));

        // STACK_CLIP_BEFORE_ANIM should use stack bounds since it won't be clipped later.
        mTask.setWindowingMode(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY);
        assertEquals(stackBounds, mToken.getAnimationBounds(STACK_CLIP_BEFORE_ANIM));
    }

    @Test
    public void testHasStartingWindow() {
        final WindowManager.LayoutParams attrs =
                new WindowManager.LayoutParams(TYPE_APPLICATION_STARTING);
        final WindowTestUtils.TestWindowState startingWindow = createWindowState(attrs, mToken);
        mToken.startingDisplayed = true;
        mToken.addWindow(startingWindow);
        assertTrue("Starting window should be present", mToken.hasStartingWindow());
        mToken.startingDisplayed = false;
        assertTrue("Starting window should be present", mToken.hasStartingWindow());

        mToken.removeChild(startingWindow);
        assertFalse("Starting window should not be present", mToken.hasStartingWindow());
    }

    private void assertHasStartingWindow(AppWindowToken atoken) {
        assertNotNull(atoken.startingSurface);
        assertNotNull(atoken.mStartingData);
        assertNotNull(atoken.startingWindow);
    }

    private void assertNoStartingWindow(AppWindowToken atoken) {
        assertNull(atoken.startingSurface);
        assertNull(atoken.startingWindow);
        assertNull(atoken.mStartingData);
        atoken.forAllWindows(windowState -> {
            assertFalse(windowState.getBaseType() == TYPE_APPLICATION_STARTING);
        }, true);
    }
}
