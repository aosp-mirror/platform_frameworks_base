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
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;

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
import android.view.Display;
import android.view.Surface;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

/**
 * Tests for the {@link AppWindowToken} class.
 *
 * Build/Install/Run:
 *  atest WmTests:AppWindowTokenTests
 */
@SmallTest
@Presubmit
public class AppWindowTokenTests extends WindowTestsBase {

    TaskStack mStack;
    Task mTask;
    WindowTestUtils.TestAppWindowToken mToken;

    private final String mPackageName = getInstrumentation().getTargetContext().getPackageName();

    @Before
    public void setUp() throws Exception {
        mStack = createTaskStackOnDisplay(mDisplayContent);
        mTask = createTaskInStack(mStack, 0 /* userId */);
        mToken = WindowTestUtils.createTestAppWindowToken(mDisplayContent,
                false /* skipOnParentChanged */);

        mTask.addChild(mToken, 0);
    }

    @Test
    @Presubmit
    public void testAddWindow_Order() {
        assertEquals(0, mToken.getWindowsCount());

        final WindowState win1 = createWindow(null, TYPE_APPLICATION, mToken, "win1");
        final WindowState startingWin = createWindow(null, TYPE_APPLICATION_STARTING, mToken,
                "startingWin");
        final WindowState baseWin = createWindow(null, TYPE_BASE_APPLICATION, mToken, "baseWin");
        final WindowState win4 = createWindow(null, TYPE_APPLICATION, mToken, "win4");

        // Should not contain the windows that were added above.
        assertEquals(4, mToken.getWindowsCount());
        assertTrue(mToken.hasWindow(win1));
        assertTrue(mToken.hasWindow(startingWin));
        assertTrue(mToken.hasWindow(baseWin));
        assertTrue(mToken.hasWindow(win4));

        // The starting window should be on-top of all other windows.
        assertEquals(startingWin, mToken.getLastChild());

        // The base application window should be below all other windows.
        assertEquals(baseWin, mToken.getFirstChild());
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
    public void testLandscapeSeascapeRotationByApp() {
        // Some plumbing to get the service ready for rotation updates.
        mWm.mDisplayReady = true;
        mWm.mDisplayEnabled = true;

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mToken);
        mToken.addWindow(appWindow);

        // Set initial orientation and update.
        mToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        mDisplayContent.updateOrientationFromAppTokens(
                mDisplayContent.getRequestedOverrideConfiguration(),
                null /* freezeThisOneIfNeeded */, false /* forceUpdate */);
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mDisplayContent.getLastOrientation());
        appWindow.mResizeReported = false;

        // Update the orientation to perform 180 degree rotation and check that resize was reported.
        mToken.setOrientation(SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
        mDisplayContent.updateOrientationFromAppTokens(
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
        // Some plumbing to get the service ready for rotation updates.
        mWm.mDisplayReady = true;
        mWm.mDisplayEnabled = true;

        final DisplayRotation spiedRotation = spy(mDisplayContent.getDisplayRotation());
        mDisplayContent.setDisplayRotation(spiedRotation);

        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow");
        final WindowTestUtils.TestWindowState appWindow = createWindowState(attrs, mToken);
        mToken.addWindow(appWindow);

        // Set initial orientation and update.
        performRotation(spiedRotation, Surface.ROTATION_90);
        appWindow.mResizeReported = false;

        // Update the rotation to perform 180 degree rotation and check that resize was reported.
        performRotation(spiedRotation, Surface.ROTATION_270);
        assertTrue(appWindow.mResizeReported);

        appWindow.removeImmediately();
    }

    private void performRotation(DisplayRotation spiedRotation, int rotationToReport) {
        doReturn(rotationToReport).when(spiedRotation).rotationForOrientation(anyInt(), anyInt());
        int oldRotation = mDisplayContent.getRotation();
        mWm.updateRotation(false, false);
        // Must manually apply here since ATM doesn't know about the display during this test
        // (meaning it can't perform the normal sendNewConfiguration flow).
        mDisplayContent.applyRotationLocked(oldRotation, mDisplayContent.getRotation());
        // Prevent the next rotation from being deferred by animation.
        mWm.mAnimator.setScreenRotationAnimationLocked(mDisplayContent.getDisplayId(), null);
        mWm.mRoot.performSurfacePlacement(false /* recoveringMemory */);
    }

    @Test
    public void testSizeCompatBounds() {
        // The real surface transaction is unnecessary.
        mToken.setSkipPrepareSurfaces(true);

        final Rect fixedBounds = mToken.getRequestedOverrideConfiguration().windowConfiguration
                .getBounds();
        fixedBounds.set(0, 0, 1200, 1600);
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

        mToken.setFillsParent(false);
        // Can specify orientation if app doesn't fill parent.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, mToken.getOrientation());

        mToken.setFillsParent(true);
        mToken.setHidden(true);
        mToken.sendingToBottom = true;
        // Can not specify orientation if app isn't visible even though it fills parent.
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
    public void testReportOrientationChangeOnVisibilityChange() {
        synchronized (mWm.mGlobalLock) {
            mToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

            mDisplayContent.getDisplayRotation().setFixedToUserRotation(
                    DisplayRotation.FIXED_TO_USER_ROTATION_ENABLED);

            doReturn(Configuration.ORIENTATION_LANDSCAPE).when(mToken.mActivityRecord)
                    .getRequestedConfigurationOrientation();

            mTask.mTaskRecord = Mockito.mock(TaskRecord.class, RETURNS_DEEP_STUBS);
            mToken.commitVisibility(null, false /* visible */, TRANSIT_UNSET,
                    true /* performLayout */, false /* isVoiceInteraction */);
        }

        verify(mTask.mTaskRecord).onConfigurationChanged(any(Configuration.class));
    }

    @Test
    public void testReportOrientationChangeOnOpeningClosingAppChange() {
        synchronized (mWm.mGlobalLock) {
            mToken.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);

            mDisplayContent.getDisplayRotation().setFixedToUserRotation(
                    DisplayRotation.FIXED_TO_USER_ROTATION_ENABLED);
            mDisplayContent.getDisplayInfo().state = Display.STATE_ON;
            mDisplayContent.prepareAppTransition(WindowManager.TRANSIT_ACTIVITY_CLOSE,
                    false /* alwaysKeepCurrent */, 0 /* flags */, true /* forceOverride */);

            doReturn(Configuration.ORIENTATION_LANDSCAPE).when(mToken.mActivityRecord)
                    .getRequestedConfigurationOrientation();

            mTask.mTaskRecord = Mockito.mock(TaskRecord.class, RETURNS_DEEP_STUBS);
            mToken.setVisibility(false, false);
        }

        verify(mTask.mTaskRecord).onConfigurationChanged(any(Configuration.class));
    }

    @Test
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
    public void testAddRemoveRace() {
        // There was once a race condition between adding and removing starting windows
        for (int i = 0; i < 1000; i++) {
            final WindowTestUtils.TestAppWindowToken appToken = createIsolatedTestAppWindowToken();

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
        final WindowTestUtils.TestAppWindowToken token1 = createIsolatedTestAppWindowToken();
        final WindowTestUtils.TestAppWindowToken token2 = createIsolatedTestAppWindowToken();
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
        final WindowTestUtils.TestAppWindowToken token1 = createIsolatedTestAppWindowToken();
        final WindowTestUtils.TestAppWindowToken token2 = createIsolatedTestAppWindowToken();
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

    private WindowTestUtils.TestAppWindowToken createIsolatedTestAppWindowToken() {
        final TaskStack taskStack = createTaskStackOnDisplay(mDisplayContent);
        final Task task = createTaskInStack(taskStack, 0 /* userId */);
        return createTestAppWindowTokenForGivenTask(task);
    }

    private WindowTestUtils.TestAppWindowToken createTestAppWindowTokenForGivenTask(Task task) {
        final WindowTestUtils.TestAppWindowToken appToken =
                WindowTestUtils.createTestAppWindowToken(mDisplayContent);
        task.addChild(appToken, 0);
        waitUntilHandlersIdle();
        return appToken;
    }

    @Test
    public void testTryTransferStartingWindowFromHiddenAboveToken() {
        // Add two tasks on top of each other.
        final WindowTestUtils.TestAppWindowToken tokenTop = createIsolatedTestAppWindowToken();
        final WindowTestUtils.TestAppWindowToken tokenBottom =
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

    private void assertHasStartingWindow(AppWindowToken atoken) {
        assertNotNull(atoken.startingSurface);
        assertNotNull(atoken.startingData);
        assertNotNull(atoken.startingWindow);
    }

    private void assertNoStartingWindow(AppWindowToken atoken) {
        assertNull(atoken.startingSurface);
        assertNull(atoken.startingWindow);
        assertNull(atoken.startingData);
        atoken.forAllWindows(windowState -> {
            assertFalse(windowState.getBaseType() == TYPE_APPLICATION_STARTING);
        }, true);
    }
}
