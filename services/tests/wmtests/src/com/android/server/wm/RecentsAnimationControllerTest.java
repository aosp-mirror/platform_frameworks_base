/*
 * Copyright (C) 2018 The Android Open Source Project
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
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_ACTIVITY_CLOSE;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.atLeast;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;
import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_ORIGINAL_POSITION;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import android.app.ActivityManager.TaskSnapshot;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.platform.test.annotations.Presubmit;
import android.util.SparseBooleanArray;
import android.view.IRecentsAnimationRunner;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Build/Install/Run:
 *  atest FrameworksServicesTests:RecentsAnimationControllerTest
 */
@SmallTest
@Presubmit
public class RecentsAnimationControllerTest extends WindowTestsBase {

    @Mock SurfaceControl mMockLeash;
    @Mock SurfaceControl.Transaction mMockTransaction;
    @Mock OnAnimationFinishedCallback mFinishedCallback;
    @Mock IRecentsAnimationRunner mMockRunner;
    @Mock RecentsAnimationController.RecentsAnimationCallbacks mAnimationCallbacks;
    @Mock TaskSnapshot mMockTaskSnapshot;
    private RecentsAnimationController mController;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        synchronized (mWm.mGlobalLock) {
            // Hold the lock to protect the stubbing from being accessed by other threads.
            spyOn(mWm.mRoot);
            doNothing().when(mWm.mRoot).performSurfacePlacement(anyBoolean());
            doReturn(mDisplayContent).when(mWm.mRoot).getDisplayContent(anyInt());
        }
        when(mMockRunner.asBinder()).thenReturn(new Binder());
        mController = spy(new RecentsAnimationController(mWm, mMockRunner, mAnimationCallbacks,
                DEFAULT_DISPLAY));
    }

    @Test
    public void testRemovedBeforeStarted_expectCanceled() throws Exception {
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        AnimationAdapter adapter = mController.addAnimation(appWindow.getTask(),
                false /* isRecentTaskInvisible */);
        adapter.startAnimation(mMockLeash, mMockTransaction, mFinishedCallback);

        // Remove the app window so that the animation target can not be created
        appWindow.removeImmediately();
        mController.startAnimation();

        // Verify that the finish callback to reparent the leash is called
        verify(mFinishedCallback).onAnimationFinished(eq(adapter));
        // Verify the animation canceled callback to the app was made
        verify(mMockRunner).onAnimationCanceled(null /* taskSnapshot */);
        verifyNoMoreInteractionsExceptAsBinder(mMockRunner);
    }

    @Test
    public void testCancelAfterRemove_expectIgnored() {
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        AnimationAdapter adapter = mController.addAnimation(appWindow.getTask(),
                false /* isRecentTaskInvisible */);
        adapter.startAnimation(mMockLeash, mMockTransaction, mFinishedCallback);

        // Remove the app window so that the animation target can not be created
        appWindow.removeImmediately();
        mController.startAnimation();
        mController.cleanupAnimation(REORDER_KEEP_IN_PLACE);
        try {
            mController.cancelAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION, "test");
        } catch (Exception e) {
            fail("Unexpected failure when canceling animation after finishing it");
        }
    }

    @Test
    public void testIncludedApps_expectTargetAndVisible() {
        mWm.setRecentsAnimationController(mController);
        final ActivityStack homeStack = mDisplayContent.mAcitvityDisplay.getOrCreateStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);
        final AppWindowToken homeAppWindow =
                new ActivityTestsBase.ActivityBuilder(mWm.mAtmService)
                        .setStack(homeStack)
                        .setCreateTask(true)
                        .build()
                        .mAppWindowToken;
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final AppWindowToken hiddenAppWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        hiddenAppWindow.setHidden(true);
        mDisplayContent.getConfiguration().windowConfiguration.setRotation(
                mDisplayContent.getRotation());
        mController.initialize(ACTIVITY_TYPE_HOME, new SparseBooleanArray());

        // Ensure that we are animating the target activity as well
        assertTrue(mController.isAnimatingTask(homeAppWindow.getTask()));
        assertTrue(mController.isAnimatingTask(appWindow.getTask()));
        assertFalse(mController.isAnimatingTask(hiddenAppWindow.getTask()));
    }

    @Test
    public void testWallpaperIncluded_expectTarget() throws Exception {
        mWm.setRecentsAnimationController(mController);
        final ActivityStack homeStack = mDisplayContent.mAcitvityDisplay.getOrCreateStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);
        final AppWindowToken homeAppWindow =
                new ActivityTestsBase.ActivityBuilder(mWm.mAtmService)
                        .setStack(homeStack)
                        .setCreateTask(true)
                        .build()
                        .mAppWindowToken;
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, appWindow, "win1");
        appWindow.addWindow(win1);
        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true, mDisplayContent, true /* ownerCanManageAppTokens */);
        spyOn(mDisplayContent.mWallpaperController);
        doReturn(true).when(mDisplayContent.mWallpaperController).isWallpaperVisible();

        mDisplayContent.getConfiguration().windowConfiguration.setRotation(
                mDisplayContent.getRotation());
        mController.initialize(ACTIVITY_TYPE_HOME, new SparseBooleanArray());
        mController.startAnimation();

        // Ensure that we are animating the app and wallpaper target
        assertTrue(mController.isAnimatingTask(appWindow.getTask()));
        assertTrue(mController.isAnimatingWallpaper(wallpaperWindowToken));
    }

    @Test
    public void testWallpaperAnimatorCanceled_expectAnimationKeepsRunning() throws Exception {
        mWm.setRecentsAnimationController(mController);
        final ActivityStack homeStack = mDisplayContent.mAcitvityDisplay.getOrCreateStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);
        final AppWindowToken homeAppWindow =
                new ActivityTestsBase.ActivityBuilder(mWm.mAtmService)
                        .setStack(homeStack)
                        .setCreateTask(true)
                        .build()
                        .mAppWindowToken;
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, appWindow, "win1");
        appWindow.addWindow(win1);
        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true, mDisplayContent, true /* ownerCanManageAppTokens */);
        spyOn(mDisplayContent.mWallpaperController);
        doReturn(true).when(mDisplayContent.mWallpaperController).isWallpaperVisible();

        mDisplayContent.getConfiguration().windowConfiguration.setRotation(
                mDisplayContent.getRotation());
        mController.initialize(ACTIVITY_TYPE_HOME, new SparseBooleanArray());
        mController.startAnimation();

        // Cancel the animation and ensure the controller is still running
        wallpaperWindowToken.cancelAnimation();
        assertTrue(mController.isAnimatingTask(appWindow.getTask()));
        assertFalse(mController.isAnimatingWallpaper(wallpaperWindowToken));
        verify(mMockRunner, never()).onAnimationCanceled(null /* taskSnapshot */);
    }

    @Test
    public void testFinish_expectTargetAndWallpaperAdaptersRemoved() {
        mWm.setRecentsAnimationController(mController);
        final ActivityStack homeStack = mDisplayContent.mAcitvityDisplay.getOrCreateStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);
        final AppWindowToken homeAppWindow =
                new ActivityTestsBase.ActivityBuilder(mWm.mAtmService)
                        .setStack(homeStack)
                        .setCreateTask(true)
                        .build()
                        .mAppWindowToken;
        final WindowState hwin1 = createWindow(null, TYPE_BASE_APPLICATION, homeAppWindow, "hwin1");
        homeAppWindow.addWindow(hwin1);
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, appWindow, "win1");
        appWindow.addWindow(win1);
        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true, mDisplayContent, true /* ownerCanManageAppTokens */);
        spyOn(mDisplayContent.mWallpaperController);
        doReturn(true).when(mDisplayContent.mWallpaperController).isWallpaperVisible();

        // Start and finish the animation
        mController.initialize(ACTIVITY_TYPE_HOME, new SparseBooleanArray());
        mController.startAnimation();
        // Reset at this point since we may remove adapters that couldn't be created
        reset(mController);
        mController.cleanupAnimation(REORDER_MOVE_TO_TOP);

        // Ensure that we remove the task (home & app) and wallpaper adapters
        verify(mController, times(2)).removeAnimation(any());
        verify(mController, times(1)).removeWallpaperAnimation(any());
    }

    @Test
    public void testDeferCancelAnimation() throws Exception {
        mWm.setRecentsAnimationController(mController);
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, appWindow, "win1");
        appWindow.addWindow(win1);
        assertEquals(appWindow.getTask().getTopVisibleAppToken(), appWindow);
        assertEquals(appWindow.findMainWindow(), win1);

        mController.addAnimation(appWindow.getTask(), false /* isRecentTaskInvisible */);
        assertTrue(mController.isAnimatingTask(appWindow.getTask()));

        mController.setDeferredCancel(true /* deferred */, false /* screenshot */);
        mController.cancelAnimationWithScreenshot(false /* screenshot */);
        verify(mMockRunner).onAnimationCanceled(null /* taskSnapshot */);
        assertNull(mController.mRecentScreenshotAnimator);

        // Simulate the app transition finishing
        mController.mAppTransitionListener.onAppTransitionStartingLocked(0, 0, 0, 0);
        verify(mAnimationCallbacks).onAnimationFinished(REORDER_KEEP_IN_PLACE, false);
    }

    @Test
    public void testDeferCancelAnimationWithScreenShot() throws Exception {
        mWm.setRecentsAnimationController(mController);
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, appWindow, "win1");
        appWindow.addWindow(win1);
        assertEquals(appWindow.getTask().getTopVisibleAppToken(), appWindow);
        assertEquals(appWindow.findMainWindow(), win1);

        mController.addAnimation(appWindow.getTask(), false /* isRecentTaskInvisible */);
        assertTrue(mController.isAnimatingTask(appWindow.getTask()));

        spyOn(mWm.mTaskSnapshotController);
        doNothing().when(mWm.mTaskSnapshotController).notifyAppVisibilityChanged(any(),
                anyBoolean());
        doReturn(mMockTaskSnapshot).when(mWm.mTaskSnapshotController).getSnapshot(anyInt(),
                anyInt(), eq(false) /* restoreFromDisk */, eq(false) /* reducedResolution */);
        mController.setDeferredCancel(true /* deferred */, true /* screenshot */);
        mController.cancelAnimationWithScreenshot(true /* screenshot */);
        verify(mMockRunner).onAnimationCanceled(mMockTaskSnapshot /* taskSnapshot */);
        assertNotNull(mController.mRecentScreenshotAnimator);
        assertTrue(mController.mRecentScreenshotAnimator.isAnimating());

        // Assume IRecentsAnimationController#cleanupScreenshot called to finish screenshot
        // animation.
        spyOn(mController.mRecentScreenshotAnimator.mAnimatable);
        mController.mRecentScreenshotAnimator.cancelAnimation();
        verify(mController.mRecentScreenshotAnimator.mAnimatable).onAnimationLeashLost(any());
        verify(mAnimationCallbacks).onAnimationFinished(REORDER_KEEP_IN_PLACE, false);
    }

    @Test
    public void testShouldAnimateWhenNoCancelWithDeferredScreenshot() {
        mWm.setRecentsAnimationController(mController);
        final AppWindowToken appWindow = createAppWindowToken(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, appWindow, "win1");
        appWindow.addWindow(win1);
        assertEquals(appWindow.getTask().getTopVisibleAppToken(), appWindow);
        assertEquals(appWindow.findMainWindow(), win1);

        mController.addAnimation(appWindow.getTask(), false /* isRecentTaskInvisible */);
        assertTrue(mController.isAnimatingTask(appWindow.getTask()));

        // Assume appWindow transition should animate when no
        // IRecentsAnimationController#setCancelWithDeferredScreenshot called.
        assertFalse(mController.shouldDeferCancelWithScreenshot());
        assertTrue(appWindow.shouldAnimate(TRANSIT_ACTIVITY_CLOSE));
    }

    private static void verifyNoMoreInteractionsExceptAsBinder(IInterface binder) {
        verify(binder, atLeast(0)).asBinder();
        verifyNoMoreInteractions(binder);
    }
}
