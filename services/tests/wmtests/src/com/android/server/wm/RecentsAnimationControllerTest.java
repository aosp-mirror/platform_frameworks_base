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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.atLeast;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.RecentsAnimationController.REORDER_KEEP_IN_PLACE;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_ORIGINAL_POSITION;
import static com.android.server.wm.RecentsAnimationController.REORDER_MOVE_TO_TOP;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;

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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.platform.test.annotations.Presubmit;
import android.util.SparseBooleanArray;
import android.view.IRecentsAnimationRunner;
import android.view.SurfaceControl;
import android.window.TaskSnapshot;

import androidx.test.filters.SmallTest;

import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;

/**
 * Build/Install/Run:
 *  atest WmTests:RecentsAnimationControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class RecentsAnimationControllerTest extends WindowTestsBase {

    @Mock SurfaceControl mMockLeash;
    @Mock SurfaceControl.Transaction mMockTransaction;
    @Mock OnAnimationFinishedCallback mFinishedCallback;
    @Mock IRecentsAnimationRunner mMockRunner;
    @Mock RecentsAnimationController.RecentsAnimationCallbacks mAnimationCallbacks;
    @Mock TaskSnapshot mMockTaskSnapshot;
    private RecentsAnimationController mController;
    private Task mRootHomeTask;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        doNothing().when(mWm.mRoot).performSurfacePlacement();
        when(mMockRunner.asBinder()).thenReturn(new Binder());
        mController = spy(new RecentsAnimationController(mWm, mMockRunner, mAnimationCallbacks,
                DEFAULT_DISPLAY));
        mController.mShouldAttachNavBarToAppDuringTransition = false;
        mRootHomeTask = mDefaultDisplay.getDefaultTaskDisplayArea().getRootHomeTask();
        assertNotNull(mRootHomeTask);
    }

    @Test
    public void testRemovedBeforeStarted_expectCanceled() throws Exception {
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        AnimationAdapter adapter = mController.addAnimation(activity.getTask(),
                false /* isRecentTaskInvisible */);
        adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_RECENTS,
                mFinishedCallback);

        // Remove the app window so that the animation target can not be created
        activity.removeImmediately();
        mController.startAnimation();

        // Verify that the finish callback to reparent the leash is called
        verify(mFinishedCallback).onAnimationFinished(eq(ANIMATION_TYPE_RECENTS), eq(adapter));
        // Verify the animation canceled callback to the app was made
        verify(mMockRunner).onAnimationCanceled(null /* taskSnapshot */);
        verifyNoMoreInteractionsExceptAsBinder(mMockRunner);
    }

    @Test
    public void testCancelAfterRemove_expectIgnored() {
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        AnimationAdapter adapter = mController.addAnimation(activity.getTask(),
                false /* isRecentTaskInvisible */);
        adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_RECENTS,
                mFinishedCallback);

        // Remove the app window so that the animation target can not be created
        activity.removeImmediately();
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
        final ActivityRecord homeActivity = createHomeActivity();
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final ActivityRecord hiddenActivity = createActivityRecord(mDefaultDisplay);
        hiddenActivity.setVisible(false);
        mDefaultDisplay.getConfiguration().windowConfiguration.setRotation(
                mDefaultDisplay.getRotation());
        initializeRecentsAnimationController(mController, homeActivity);

        // Ensure that we are animating the target activity as well
        assertTrue(mController.isAnimatingTask(homeActivity.getTask()));
        assertTrue(mController.isAnimatingTask(activity.getTask()));
        assertFalse(mController.isAnimatingTask(hiddenActivity.getTask()));
    }

    @Test
    public void testWallpaperIncluded_expectTarget() throws Exception {
        mWm.setRecentsAnimationController(mController);
        final ActivityRecord homeActivity = createHomeActivity();
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "win1");
        activity.addWindow(win1);
        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true, mDefaultDisplay, true /* ownerCanManageAppTokens */);
        spyOn(mDefaultDisplay.mWallpaperController);
        doReturn(true).when(mDefaultDisplay.mWallpaperController).isWallpaperVisible();

        mDefaultDisplay.getConfiguration().windowConfiguration.setRotation(
                mDefaultDisplay.getRotation());
        initializeRecentsAnimationController(mController, homeActivity);
        mController.startAnimation();

        // Ensure that we are animating the app and wallpaper target
        assertTrue(mController.isAnimatingTask(activity.getTask()));
        assertTrue(mController.isAnimatingWallpaper(wallpaperWindowToken));
    }

    @Test
    public void testWallpaperAnimatorCanceled_expectAnimationKeepsRunning() throws Exception {
        mWm.setRecentsAnimationController(mController);
        final ActivityRecord homeActivity = createHomeActivity();
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "win1");
        activity.addWindow(win1);
        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true, mDefaultDisplay, true /* ownerCanManageAppTokens */);
        spyOn(mDefaultDisplay.mWallpaperController);
        doReturn(true).when(mDefaultDisplay.mWallpaperController).isWallpaperVisible();

        mDefaultDisplay.getConfiguration().windowConfiguration.setRotation(
                mDefaultDisplay.getRotation());
        initializeRecentsAnimationController(mController, homeActivity);
        mController.startAnimation();

        // Cancel the animation and ensure the controller is still running
        wallpaperWindowToken.cancelAnimation();
        assertTrue(mController.isAnimatingTask(activity.getTask()));
        assertFalse(mController.isAnimatingWallpaper(wallpaperWindowToken));
        verify(mMockRunner, never()).onAnimationCanceled(null /* taskSnapshot */);
    }

    @Test
    public void testFinish_expectTargetAndWallpaperAdaptersRemoved() {
        mWm.setRecentsAnimationController(mController);
        final ActivityRecord homeActivity = createHomeActivity();
        final WindowState hwin1 = createWindow(null, TYPE_BASE_APPLICATION, homeActivity, "hwin1");
        homeActivity.addWindow(hwin1);
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "win1");
        activity.addWindow(win1);
        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true, mDefaultDisplay, true /* ownerCanManageAppTokens */);
        spyOn(mDefaultDisplay.mWallpaperController);
        doReturn(true).when(mDefaultDisplay.mWallpaperController).isWallpaperVisible();

        // Start and finish the animation
        initializeRecentsAnimationController(mController, homeActivity);
        mController.startAnimation();

        assertTrue(mController.isAnimatingTask(homeActivity.getTask()));
        assertTrue(mController.isAnimatingTask(activity.getTask()));

        // Reset at this point since we may remove adapters that couldn't be created
        clearInvocations(mController);
        mController.cleanupAnimation(REORDER_MOVE_TO_TOP);

        // Ensure that we remove the task (home & app) and wallpaper adapters
        verify(mController, times(2)).removeAnimation(any());
        verify(mController, times(1)).removeWallpaperAnimation(any());
    }

    @Test
    public void testDeferCancelAnimation() throws Exception {
        mWm.setRecentsAnimationController(mController);
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "win1");
        activity.addWindow(win1);
        assertEquals(activity.getTask().getTopVisibleActivity(), activity);
        assertEquals(activity.findMainWindow(), win1);

        mController.addAnimation(activity.getTask(), false /* isRecentTaskInvisible */);
        assertTrue(mController.isAnimatingTask(activity.getTask()));

        mController.setDeferredCancel(true /* deferred */, false /* screenshot */);
        mController.cancelAnimationWithScreenshot(false /* screenshot */);
        verify(mMockRunner).onAnimationCanceled(null /* taskSnapshot */);
        assertNull(mController.mRecentScreenshotAnimator);

        // Simulate the app transition finishing
        mController.mAppTransitionListener.onAppTransitionStartingLocked(false, 0, 0, 0);
        verify(mAnimationCallbacks).onAnimationFinished(REORDER_KEEP_IN_PLACE, false);
    }

    @Test
    public void testDeferCancelAnimationWithScreenShot() throws Exception {
        mWm.setRecentsAnimationController(mController);
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "win1");
        activity.addWindow(win1);
        assertEquals(activity.getTask().getTopVisibleActivity(), activity);
        assertEquals(activity.findMainWindow(), win1);

        mController.addAnimation(activity.getTask(), false /* isRecentTaskInvisible */);
        assertTrue(mController.isAnimatingTask(activity.getTask()));

        spyOn(mWm.mTaskSnapshotController);
        doNothing().when(mWm.mTaskSnapshotController).notifyAppVisibilityChanged(any(),
                anyBoolean());
        doReturn(mMockTaskSnapshot).when(mWm.mTaskSnapshotController).getSnapshot(anyInt(),
                anyInt(), eq(false) /* restoreFromDisk */, eq(false) /* isLowResolution */);
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
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "win1");
        activity.addWindow(win1);
        assertEquals(activity.getTask().getTopVisibleActivity(), activity);
        assertEquals(activity.findMainWindow(), win1);

        mController.addAnimation(activity.getTask(), false /* isRecentTaskInvisible */);
        assertTrue(mController.isAnimatingTask(activity.getTask()));

        // Assume activity transition should animate when no
        // IRecentsAnimationController#setDeferCancelUntilNextTransition called.
        assertFalse(mController.shouldDeferCancelWithScreenshot());
        assertTrue(activity.shouldAnimate());
    }

    @Test
    public void testRecentViewInFixedPortraitWhenTopAppInLandscape() {
        unblockDisplayRotation(mDefaultDisplay);
        mWm.setRecentsAnimationController(mController);

        final ActivityRecord homeActivity = createHomeActivity();
        homeActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        final ActivityRecord landActivity = createActivityRecord(mDefaultDisplay);
        landActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, landActivity, "win1");
        landActivity.addWindow(win1);

        assertEquals(landActivity.getTask().getTopVisibleActivity(), landActivity);
        assertEquals(landActivity.findMainWindow(), win1);

        // Ensure that the display is in Landscape
        landActivity.onDescendantOrientationChanged(landActivity);
        assertEquals(Configuration.ORIENTATION_LANDSCAPE,
                mDefaultDisplay.getConfiguration().orientation);

        initializeRecentsAnimationController(mController, homeActivity);

        assertTrue(mDefaultDisplay.isFixedRotationLaunchingApp(homeActivity));

        // Check that the home app is in portrait
        assertEquals(Configuration.ORIENTATION_PORTRAIT,
                homeActivity.getConfiguration().orientation);

        // Home activity won't become top (return to landActivity), so the top rotated record should
        // be cleared.
        mController.cleanupAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION);
        assertFalse(mDefaultDisplay.isFixedRotationLaunchingApp(homeActivity));
        assertFalse(mDefaultDisplay.hasTopFixedRotationLaunchingApp());
        // The transform should keep until the transition is done, so the restored configuration
        // won't be sent to activity and cause unnecessary configuration change.
        assertTrue(homeActivity.hasFixedRotationTransform());

        // In real case the transition will be executed from RecentsAnimation#finishAnimation.
        mDefaultDisplay.mFixedRotationTransitionListener.onAppTransitionFinishedLocked(
                homeActivity.token);
        assertFalse(homeActivity.hasFixedRotationTransform());
    }

    private ActivityRecord prepareFixedRotationLaunchingAppWithRecentsAnim() {
        final ActivityRecord homeActivity = createHomeActivity();
        homeActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        // Add a window so it can be animated by the recents.
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, activity, "win");
        activity.addWindow(win);
        // Assume an activity is launching to different rotation.
        mDefaultDisplay.setFixedRotationLaunchingApp(activity,
                (mDefaultDisplay.getRotation() + 1) % 4);

        assertTrue(activity.hasFixedRotationTransform());
        assertTrue(mDefaultDisplay.isFixedRotationLaunchingApp(activity));

        // Before the transition is done, the recents animation is triggered.
        initializeRecentsAnimationController(mController, homeActivity);
        assertFalse(homeActivity.hasFixedRotationTransform());
        assertTrue(mController.isAnimatingTask(activity.getTask()));

        return activity;
    }

    @Test
    public void testClearFixedRotationLaunchingAppAfterCleanupAnimation() {
        final ActivityRecord activity = prepareFixedRotationLaunchingAppWithRecentsAnim();

        // Simulate giving up the swipe up gesture to keep the original activity as top.
        mController.cleanupAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION);
        // The rotation transform should be cleared after updating orientation with display.
        assertTopFixedRotationLaunchingAppCleared(activity);

        // Simulate swiping up recents (home) in different rotation.
        final ActivityRecord home = mDefaultDisplay.getDefaultTaskDisplayArea().getHomeActivity();
        startRecentsInDifferentRotation(home);

        // If the recents activity becomes the top running activity (e.g. the original top activity
        // is either finishing or moved to back during recents animation), the display orientation
        // will be determined by it so the fixed rotation must be cleared.
        activity.finishing = true;
        mController.cleanupAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION);
        assertTopFixedRotationLaunchingAppCleared(home);

        startRecentsInDifferentRotation(home);
        // Assume recents activity becomes invisible for some reason (e.g. screen off).
        home.setVisible(false);
        mController.cleanupAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION);
        // Although there won't be a transition finish callback, the fixed rotation must be cleared.
        assertTopFixedRotationLaunchingAppCleared(home);
    }

    @Test
    public void testKeepFixedRotationWhenMovingRecentsToTop() {
        final ActivityRecord activity = prepareFixedRotationLaunchingAppWithRecentsAnim();
        // Assume a transition animation has started running before recents animation. Then the
        // activity will receive onAnimationFinished that notifies app transition finished when
        // removing the recents animation of task.
        activity.getTask().getAnimationSources().add(activity);

        // Simulate swiping to home/recents before the transition is done.
        mController.cleanupAnimation(REORDER_MOVE_TO_TOP);
        // The rotation transform should be preserved. In real case, it will be cleared by the next
        // move-to-top transition.
        assertTrue(activity.hasFixedRotationTransform());
    }

    @Test
    public void testWallpaperHasFixedRotationApplied() {
        unblockDisplayRotation(mDefaultDisplay);
        mWm.setRecentsAnimationController(mController);

        // Create a portrait home activity, a wallpaper and a landscape activity displayed on top.
        final ActivityRecord homeActivity = createHomeActivity();
        homeActivity.setOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        final WindowState homeWindow = createWindow(null, TYPE_BASE_APPLICATION, homeActivity,
                "homeWindow");
        makeWindowVisible(homeWindow);
        homeActivity.addWindow(homeWindow);
        homeWindow.getAttrs().flags |= FLAG_SHOW_WALLPAPER;

        // Landscape application
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final WindowState applicationWindow = createWindow(null, TYPE_BASE_APPLICATION, activity,
                "applicationWindow");
        activity.addWindow(applicationWindow);
        activity.setOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

        // Wallpaper
        final WallpaperWindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm,
                mock(IBinder.class), true, mDefaultDisplay, true /* ownerCanManageAppTokens */);
        final WindowState wallpaperWindow = createWindow(null, TYPE_WALLPAPER, wallpaperWindowToken,
                "wallpaperWindow");

        // Make sure the landscape activity is on top and the display is in landscape
        activity.moveFocusableActivityToTop("test");
        mDefaultDisplay.getConfiguration().windowConfiguration.setRotation(
                mDefaultDisplay.getRotation());

        spyOn(mDefaultDisplay.mWallpaperController);
        doReturn(true).when(mDefaultDisplay.mWallpaperController).isWallpaperVisible();

        // Start the recents animation
        initializeRecentsAnimationController(mController, homeActivity);

        mDefaultDisplay.mWallpaperController.adjustWallpaperWindows();

        // Check preconditions
        ArrayList<WallpaperWindowToken> wallpapers = new ArrayList<>(1);
        mDefaultDisplay.forAllWallpaperWindows(wallpapers::add);

        Truth.assertThat(wallpapers).hasSize(1);
        Truth.assertThat(wallpapers.get(0).getTopChild()).isEqualTo(wallpaperWindow);

        // Actual check
        assertEquals(Configuration.ORIENTATION_PORTRAIT,
                wallpapers.get(0).getConfiguration().orientation);

        mController.cleanupAnimation(REORDER_MOVE_TO_TOP);
        // The transform state should keep because we expect to listen the signal from the
        // transition executed by moving the task to front.
        assertTrue(homeActivity.hasFixedRotationTransform());
        assertTrue(mDefaultDisplay.isFixedRotationLaunchingApp(homeActivity));

        mDefaultDisplay.mFixedRotationTransitionListener.onAppTransitionFinishedLocked(
                homeActivity.token);
        // Wallpaper's transform state should be cleared with home.
        assertFalse(homeActivity.hasFixedRotationTransform());
        assertFalse(wallpaperWindowToken.hasFixedRotationTransform());
    }

    @Test
    public void testIsAnimatingByRecents() {
        final ActivityRecord homeActivity = createHomeActivity();
        final Task rootTask = createTask(mDefaultDisplay);
        final Task childTask = createTaskInRootTask(rootTask, 0 /* userId */);
        final Task leafTask = createTaskInRootTask(childTask, 0 /* userId */);
        spyOn(leafTask);
        doReturn(true).when(leafTask).isVisible();

        initializeRecentsAnimationController(mController, homeActivity);

        // Verify RecentsAnimationController will animate visible leaf task by default.
        verify(mController).addAnimation(eq(leafTask), anyBoolean(), anyBoolean(), any());
        assertTrue(leafTask.isAnimatingByRecents());

        // Make sure isAnimatingByRecents will also return true when it called by the parent task.
        assertTrue(rootTask.isAnimatingByRecents());
        assertTrue(childTask.isAnimatingByRecents());
    }

    @Test
    public void testRestoreNavBarWhenEnteringRecents_expectAnimation() {
        setupForShouldAttachNavBarDuringTransition();
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final ActivityRecord homeActivity = createHomeActivity();
        initializeRecentsAnimationController(mController, homeActivity);

        final WindowToken navToken = mDefaultDisplay.getDisplayPolicy().getNavigationBar().mToken;
        final SurfaceControl.Transaction transaction = navToken.getPendingTransaction();

        verify(mController.mStatusBar).setNavigationBarLumaSamplingEnabled(
                mDefaultDisplay.mDisplayId, false);
        verify(transaction).reparent(navToken.getSurfaceControl(), activity.getSurfaceControl());

        final WindowContainer parent = navToken.getParent();
        final NavBarFadeAnimationController navBarFadeAnimationController =
                mDefaultDisplay.getDisplayPolicy().getNavBarFadeAnimationController();

        mController.cleanupAnimation(REORDER_MOVE_TO_TOP);
        verify(mController.mStatusBar).setNavigationBarLumaSamplingEnabled(
                mDefaultDisplay.mDisplayId, true);
        verify(transaction).reparent(navToken.getSurfaceControl(), parent.getSurfaceControl());
        verify(navBarFadeAnimationController).fadeWindowToken(true);
    }

    @Test
    public void testRestoreNavBarWhenBackToApp_expectNoAnimation() {
        setupForShouldAttachNavBarDuringTransition();
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final ActivityRecord homeActivity = createHomeActivity();
        initializeRecentsAnimationController(mController, homeActivity);

        final WindowToken navToken = mDefaultDisplay.getDisplayPolicy().getNavigationBar().mToken;
        final SurfaceControl.Transaction transaction = navToken.getPendingTransaction();

        verify(mController.mStatusBar).setNavigationBarLumaSamplingEnabled(
                mDefaultDisplay.mDisplayId, false);
        verify(transaction).reparent(navToken.getSurfaceControl(), activity.getSurfaceControl());

        final WindowContainer parent = navToken.getParent();
        final NavBarFadeAnimationController navBarFadeAnimationController =
                mDefaultDisplay.getDisplayPolicy().getNavBarFadeAnimationController();

        mController.cleanupAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION);
        verify(mController.mStatusBar).setNavigationBarLumaSamplingEnabled(
                mDefaultDisplay.mDisplayId, true);
        verify(transaction).reparent(navToken.getSurfaceControl(), parent.getSurfaceControl());
        verify(navBarFadeAnimationController, never()).fadeWindowToken(anyBoolean());
    }

    @Test
    public void testNotAttachNavigationBar_controlledByFadeRotationAnimation() {
        setupForShouldAttachNavBarDuringTransition();
        FadeRotationAnimationController mockController =
                mock(FadeRotationAnimationController.class);
        doReturn(mockController).when(mDefaultDisplay).getFadeRotationAnimationController();
        final ActivityRecord homeActivity = createHomeActivity();
        initializeRecentsAnimationController(mController, homeActivity);
        assertFalse(mController.isNavigationBarAttachedToApp());
    }

    @Test
    public void testAttachNavBarInSplitScreenMode() {
        setupForShouldAttachNavBarDuringTransition();
        final ActivityRecord primary = createActivityRecordWithParentTask(mDefaultDisplay,
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord secondary = createActivityRecordWithParentTask(mDefaultDisplay,
                WINDOWING_MODE_SPLIT_SCREEN_SECONDARY, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord homeActivity = createHomeActivity();
        homeActivity.setVisibility(true);
        initializeRecentsAnimationController(mController, homeActivity);

        WindowState navWindow = mController.getNavigationBarWindow();
        final WindowToken navToken = navWindow.mToken;
        final SurfaceControl.Transaction transaction = navToken.getPendingTransaction();

        verify(mController.mStatusBar).setNavigationBarLumaSamplingEnabled(
                mDefaultDisplay.mDisplayId, false);
        verify(navWindow).setSurfaceTranslationY(-secondary.getBounds().top);
        verify(transaction).reparent(navToken.getSurfaceControl(), secondary.getSurfaceControl());
        reset(navWindow);

        mController.cleanupAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION);
        final WindowContainer parent = navToken.getParent();
        final NavBarFadeAnimationController navBarFadeAnimationController =
                mDefaultDisplay.getDisplayPolicy().getNavBarFadeAnimationController();
        verify(mController.mStatusBar).setNavigationBarLumaSamplingEnabled(
                mDefaultDisplay.mDisplayId, true);
        verify(navWindow).setSurfaceTranslationY(0);
        verify(transaction).reparent(navToken.getSurfaceControl(), parent.getSurfaceControl());
        verify(navBarFadeAnimationController, never()).fadeWindowToken(anyBoolean());
    }

    @Test
    public void testCleanupAnimation_expectExitAnimationDone() {
        mWm.setRecentsAnimationController(mController);
        final ActivityRecord homeActivity = createHomeActivity();
        final ActivityRecord activity = createActivityRecord(mDefaultDisplay);
        final WindowState win1 = createWindow(null, TYPE_BASE_APPLICATION, activity, "win1");
        activity.addWindow(win1);

        initializeRecentsAnimationController(mController, homeActivity);
        mController.startAnimation();

        spyOn(win1);
        spyOn(win1.mWinAnimator);
        // Simulate when the window is exiting and cleanupAnimation invoked
        // (e.g. screen off during RecentsAnimation animating), will expect the window receives
        // onExitAnimationDone to destroy the surface when the removal is allowed.
        win1.mWinAnimator.mSurfaceController = mock(WindowSurfaceController.class);
        win1.mHasSurface = true;
        win1.mAnimatingExit = true;
        win1.mRemoveOnExit = true;
        win1.mWindowRemovalAllowed = true;
        mController.cleanupAnimation(REORDER_MOVE_TO_ORIGINAL_POSITION);
        verify(win1).onAnimationFinished(eq(ANIMATION_TYPE_RECENTS), any());
        verify(win1).onExitAnimationDone();
        verify(win1).destroySurface(eq(false), eq(false));
        assertFalse(win1.mAnimatingExit);
        assertFalse(win1.mHasSurface);
    }

    private ActivityRecord createHomeActivity() {
        final ActivityRecord homeActivity = new ActivityBuilder(mWm.mAtmService)
                .setParentTask(mRootHomeTask)
                .setCreateTask(true)
                .build();
        // Avoid {@link RecentsAnimationController.TaskAnimationAdapter#createRemoteAnimationTarget}
        // returning null when calling {@link RecentsAnimationController#createAppAnimations}.
        homeActivity.setVisibility(true);
        return homeActivity;
    }

    private void startRecentsInDifferentRotation(ActivityRecord recentsActivity) {
        final DisplayContent displayContent = recentsActivity.mDisplayContent;
        displayContent.setFixedRotationLaunchingApp(recentsActivity,
                (displayContent.getRotation() + 1) % 4);
        mController = new RecentsAnimationController(mWm, mMockRunner, mAnimationCallbacks,
                displayContent.getDisplayId());
        initializeRecentsAnimationController(mController, recentsActivity);
        assertTrue(recentsActivity.hasFixedRotationTransform());
    }

    private static void assertTopFixedRotationLaunchingAppCleared(ActivityRecord activity) {
        assertFalse(activity.hasFixedRotationTransform());
        assertFalse(activity.mDisplayContent.hasTopFixedRotationLaunchingApp());
    }

    private void setupForShouldAttachNavBarDuringTransition() {
        mController.mShouldAttachNavBarToAppDuringTransition = true;
        final WindowState navBar = spy(createWindow(null, TYPE_NAVIGATION_BAR, "NavigationBar"));
        mDefaultDisplay.getDisplayPolicy().addWindowLw(navBar, navBar.mAttrs);
        mWm.setRecentsAnimationController(mController);
        doReturn(navBar).when(mController).getNavigationBarWindow();
        final NavBarFadeAnimationController mockNavBarFadeAnimationController =
                mock(NavBarFadeAnimationController.class);
        final DisplayPolicy displayPolicy = spy(mDefaultDisplay.getDisplayPolicy());
        doReturn(displayPolicy).when(mDefaultDisplay).getDisplayPolicy();
        doReturn(mockNavBarFadeAnimationController).when(displayPolicy)
                .getNavBarFadeAnimationController();
    }

    private static void initializeRecentsAnimationController(RecentsAnimationController controller,
            ActivityRecord activity) {
        controller.initialize(activity.getActivityType(), new SparseBooleanArray(), activity);
    }

    private static void verifyNoMoreInteractionsExceptAsBinder(IInterface binder) {
        verify(binder, atLeast(0)).asBinder();
        verifyNoMoreInteractions(binder);
    }
}
