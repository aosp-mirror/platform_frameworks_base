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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_STARTING;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_FLAG_APP_CRASHED;
import static android.view.WindowManager.TRANSIT_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_KEYGUARD_OCCLUDE;
import static android.view.WindowManager.TRANSIT_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_NONE;
import static android.view.WindowManager.TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CHANGE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_UNSET;
import static android.view.WindowManager.TRANSIT_OPEN;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_RECENTS;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;

import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.view.Display;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.window.ITaskFragmentOrganizer;
import android.window.TaskFragmentOrganizer;

import androidx.test.filters.SmallTest;

import com.android.internal.policy.TransitionAnimation;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class for {@link AppTransition}.
 *
 * Build/Install/Run:
 *  atest WmTests:AppTransitionTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppTransitionTests extends WindowTestsBase {
    private DisplayContent mDc;

    @Before
    public void setUp() throws Exception {
        doNothing().when(mWm.mRoot).performSurfacePlacement();
        mDc = mWm.getDefaultDisplayContentLocked();
    }

    @Test
    public void testKeyguardOverride() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_OPEN);
        mDc.prepareAppTransition(TRANSIT_KEYGUARD_OCCLUDE);
        mDc.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY);
        mDc.mOpeningApps.add(activity);
        assertEquals(TRANSIT_OLD_KEYGUARD_GOING_AWAY,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testKeyguardUnoccludeOcclude() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_KEYGUARD_UNOCCLUDE);
        mDc.prepareAppTransition(TRANSIT_KEYGUARD_OCCLUDE);
        mDc.mOpeningApps.add(activity);
        assertEquals(TRANSIT_NONE,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /*skipAppTransitionAnimation*/));

    }

    @Test
    public void testKeyguardKeep() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY);
        mDc.prepareAppTransition(TRANSIT_OPEN);
        mDc.mOpeningApps.add(activity);
        assertEquals(TRANSIT_OLD_KEYGUARD_GOING_AWAY,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testCrashing() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_OPEN);
        mDc.prepareAppTransition(TRANSIT_CLOSE, TRANSIT_FLAG_APP_CRASHED);
        mDc.mClosingApps.add(activity);
        assertEquals(TRANSIT_OLD_CRASHING_ACTIVITY_CLOSE,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testKeepKeyguard_withCrashing() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_KEYGUARD_GOING_AWAY);
        mDc.prepareAppTransition(TRANSIT_CLOSE, TRANSIT_FLAG_APP_CRASHED);
        mDc.mClosingApps.add(activity);
        assertEquals(TRANSIT_OLD_KEYGUARD_GOING_AWAY,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testSkipTransitionAnimation() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        final ActivityRecord activity = createActivityRecord(dc);

        mDc.prepareAppTransition(TRANSIT_OPEN);
        mDc.prepareAppTransition(TRANSIT_CLOSE);
        mDc.mClosingApps.add(activity);
        assertEquals(TRANSIT_OLD_UNSET,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, true /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testTaskChangeWindowingMode() {
        final ActivityRecord activity = createActivityRecord(mDc);

        mDc.prepareAppTransition(TRANSIT_OPEN);
        mDc.prepareAppTransition(TRANSIT_CHANGE);
        mDc.mOpeningApps.add(activity); // Make sure TRANSIT_CHANGE has the priority
        mDc.mChangingContainers.add(activity.getTask());

        assertEquals(TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testTaskFragmentChange() {
        final ActivityRecord activity = createActivityRecord(mDc);
        final TaskFragment taskFragment = new TaskFragment(mAtm, new Binder(),
                true /* createdByOrganizer */, true /* isEmbedded */);
        activity.getTask().addChild(taskFragment, POSITION_TOP);
        activity.reparent(taskFragment, POSITION_TOP);

        mDc.prepareAppTransition(TRANSIT_OPEN);
        mDc.prepareAppTransition(TRANSIT_CHANGE);
        mDc.mOpeningApps.add(activity); // Make sure TRANSIT_CHANGE has the priority
        mDc.mChangingContainers.add(taskFragment);

        assertEquals(TRANSIT_OLD_TASK_FRAGMENT_CHANGE,
                AppTransitionController.getTransitCompatType(mDc.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /*skipAppTransitionAnimation*/));
    }

    @Test
    public void testTaskFragmentOpeningTransition() {
        final ActivityRecord activity = createHierarchyForTaskFragmentTest(
                false /* createEmbeddedTask */);
        activity.setVisible(false);

        mDisplayContent.prepareAppTransition(TRANSIT_OPEN);
        mDisplayContent.mOpeningApps.add(activity);
        assertEquals(TRANSIT_OLD_TASK_FRAGMENT_OPEN,
                AppTransitionController.getTransitCompatType(mDisplayContent.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /* skipAppTransitionAnimation */));
    }

    @Test
    public void testEmbeddedTaskOpeningTransition() {
        final ActivityRecord activity = createHierarchyForTaskFragmentTest(
                true /* createEmbeddedTask */);
        activity.setVisible(false);

        mDisplayContent.prepareAppTransition(TRANSIT_OPEN);
        mDisplayContent.mOpeningApps.add(activity);
        assertEquals(TRANSIT_OLD_TASK_FRAGMENT_OPEN,
                AppTransitionController.getTransitCompatType(mDisplayContent.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /* skipAppTransitionAnimation */));
    }

    @Test
    public void testTaskFragmentClosingTransition() {
        final ActivityRecord activity = createHierarchyForTaskFragmentTest(
                false /* createEmbeddedTask */);
        activity.setVisible(true);

        mDisplayContent.prepareAppTransition(TRANSIT_CLOSE);
        mDisplayContent.mClosingApps.add(activity);
        assertEquals(TRANSIT_OLD_TASK_FRAGMENT_CLOSE,
                AppTransitionController.getTransitCompatType(mDisplayContent.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /* skipAppTransitionAnimation */));
    }

    @Test
    public void testEmbeddedTaskClosingTransition() {
        final ActivityRecord activity = createHierarchyForTaskFragmentTest(
                true /* createEmbeddedTask */);
        activity.setVisible(true);

        mDisplayContent.prepareAppTransition(TRANSIT_CLOSE);
        mDisplayContent.mClosingApps.add(activity);
        assertEquals(TRANSIT_OLD_TASK_FRAGMENT_CLOSE,
                AppTransitionController.getTransitCompatType(mDisplayContent.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        mDisplayContent.mChangingContainers, null /* wallpaperTarget */,
                        null /* oldWallpaper */, false /* skipAppTransitionAnimation */));
    }

    /**
     * Creates a {@link Task} with two {@link TaskFragment TaskFragments}.
     * The bottom TaskFragment is to prevent
     * {@link AppTransitionController#getAnimationTargets(ArraySet, ArraySet, boolean) the animation
     * target} to promote to Task or above.
     *
     * @param createEmbeddedTask {@code true} to create embedded Task for verified TaskFragment
     * @return The Activity to be put in either opening or closing Activity
     */
    private ActivityRecord createHierarchyForTaskFragmentTest(boolean createEmbeddedTask) {
        final Task parentTask = createTask(mDisplayContent);
        final TaskFragment bottomTaskFragment = createTaskFragmentWithParentTask(parentTask,
                false /* createEmbeddedTask */);
        final ActivityRecord bottomActivity = bottomTaskFragment.getTopMostActivity();
        bottomActivity.setOccludesParent(true);
        bottomActivity.setVisible(true);

        final TaskFragment verifiedTaskFragment = createTaskFragmentWithParentTask(parentTask,
                createEmbeddedTask);
        final ActivityRecord activity = verifiedTaskFragment.getTopMostActivity();
        activity.setOccludesParent(true);

        return activity;
    }

    @Test
    public void testAppTransitionStateForMultiDisplay() {
        // Create 2 displays & presume both display the state is ON for ready to display & animate.
        final DisplayContent dc1 = createNewDisplay(Display.STATE_ON);
        final DisplayContent dc2 = createNewDisplay(Display.STATE_ON);

        // Create 2 app window tokens to represent 2 activity window.
        final ActivityRecord activity1 = createActivityRecord(dc1);
        final ActivityRecord activity2 = createActivityRecord(dc2);

        activity1.allDrawn = true;
        activity1.startingMoved = true;

        // Simulate activity resume / finish flows to prepare app transition & set visibility,
        // make sure transition is set as expected for each display.
        dc1.prepareAppTransition(TRANSIT_OPEN);
        dc2.prepareAppTransition(TRANSIT_CLOSE);
        // One activity window is visible for resuming & the other activity window is invisible
        // for finishing in different display.
        activity1.setVisibility(true, false);
        activity2.setVisibility(false, false);

        // Make sure each display is in animating stage.
        assertTrue(dc1.mOpeningApps.size() > 0);
        assertTrue(dc2.mClosingApps.size() > 0);
        assertTrue(dc1.isAppTransitioning());
        assertTrue(dc2.isAppTransitioning());
    }

    @Test
    public void testCleanAppTransitionWhenRootTaskReparent() {
        // Create 2 displays & presume both display the state is ON for ready to display & animate.
        final DisplayContent dc1 = createNewDisplay(Display.STATE_ON);
        final DisplayContent dc2 = createNewDisplay(Display.STATE_ON);

        final Task rootTask1 = createTask(dc1);
        final Task task1 = createTaskInRootTask(rootTask1, 0 /* userId */);
        final ActivityRecord activity1 = createNonAttachedActivityRecord(dc1);
        task1.addChild(activity1, 0);

        // Simulate same app is during opening / closing transition set stage.
        dc1.mClosingApps.add(activity1);
        assertTrue(dc1.mClosingApps.size() > 0);

        dc1.prepareAppTransition(TRANSIT_OPEN);
        assertTrue(dc1.mAppTransition.containsTransitRequest(TRANSIT_OPEN));
        assertTrue(dc1.mAppTransition.isTransitionSet());

        dc1.mOpeningApps.add(activity1);
        assertTrue(dc1.mOpeningApps.size() > 0);

        // Move root task to another display.
        rootTask1.reparent(dc2.getDefaultTaskDisplayArea(), true);

        // Verify if token are cleared from both pending transition list in former display.
        assertFalse(dc1.mOpeningApps.contains(activity1));
        assertFalse(dc1.mOpeningApps.contains(activity1));
    }

    @Test
    public void testLoadAnimationSafely() {
        DisplayContent dc = createNewDisplay(Display.STATE_ON);
        assertNull(dc.mAppTransition.loadAnimationSafely(
                getInstrumentation().getTargetContext(), -1));
    }

    @Test
    public void testCancelRemoteAnimationWhenFreeze() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        doReturn(false).when(dc).onDescendantOrientationChanged(any());
        final WindowState exitingAppWindow = createWindow(null /* parent */, TYPE_BASE_APPLICATION,
                dc, "exiting app");
        final ActivityRecord exitingActivity = exitingAppWindow.mActivityRecord;
        // Wait until everything in animation handler get executed to prevent the exiting window
        // from being removed during WindowSurfacePlacer Traversal.
        waitUntilHandlersIdle();

        // Set a remote animator.
        final TestRemoteAnimationRunner runner = new TestRemoteAnimationRunner();
        final RemoteAnimationAdapter adapter = new RemoteAnimationAdapter(
                runner, 100, 50, true /* changeNeedsSnapshot */);
        // RemoteAnimationController will tracking RemoteAnimationAdapter's caller with calling pid.
        adapter.setCallingPidUid(123, 456);

        // Simulate activity finish flows to prepare app transition & set visibility,
        // make sure transition is set as expected.
        dc.prepareAppTransition(TRANSIT_CLOSE);
        assertTrue(dc.mAppTransition.containsTransitRequest(TRANSIT_CLOSE));
        dc.mAppTransition.overridePendingAppTransitionRemote(adapter);
        exitingActivity.setVisibility(false, false);
        assertTrue(dc.mClosingApps.size() > 0);

        // Make sure window is in animating stage before freeze, and cancel after freeze.
        assertTrue(dc.isAppTransitioning());
        assertFalse(runner.mCancelled);
        dc.mAppTransition.freeze();
        assertFalse(dc.isAppTransitioning());
        assertTrue(runner.mCancelled);
    }

    @Test
    public void testDelayWhileRecents() {
        final DisplayContent dc = createNewDisplay(Display.STATE_ON);
        doReturn(false).when(dc).onDescendantOrientationChanged(any());
        final Task task = createTask(dc);

        // Simulate activity1 launches activity2.
        final ActivityRecord activity1 = createActivityRecord(task);
        activity1.setVisible(true);
        activity1.setVisibleRequested(false);
        activity1.allDrawn = true;
        final ActivityRecord activity2 = createActivityRecord(task);
        activity2.setVisible(false);
        activity2.setVisibleRequested(true);
        activity2.allDrawn = true;

        dc.mClosingApps.add(activity1);
        dc.mOpeningApps.add(activity2);
        dc.prepareAppTransition(TRANSIT_OPEN);
        assertTrue(dc.mAppTransition.containsTransitRequest(TRANSIT_OPEN));

        // Wait until everything in animation handler get executed to prevent the exiting window
        // from being removed during WindowSurfacePlacer Traversal.
        waitUntilHandlersIdle();

        // Start recents
        doReturn(true).when(task)
                .isSelfAnimating(anyInt(), eq(ANIMATION_TYPE_RECENTS));

        dc.mAppTransitionController.handleAppTransitionReady();

        verify(activity1, never()).commitVisibility(anyBoolean(), anyBoolean(), anyBoolean());
        verify(activity2, never()).commitVisibility(anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    public void testGetAnimationStyleResId() {
        // Verify getAnimationStyleResId will return as LayoutParams.windowAnimations when without
        // specifying window type.
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();
        attrs.windowAnimations = 0x12345678;
        assertEquals(attrs.windowAnimations, mDc.mAppTransition.getAnimationStyleResId(attrs));

        // Verify getAnimationStyleResId will return system resource Id when the window type is
        // starting window.
        attrs.type = TYPE_APPLICATION_STARTING;
        assertEquals(mDc.mAppTransition.getDefaultWindowAnimationStyleResId(),
                mDc.mAppTransition.getAnimationStyleResId(attrs));
    }

    @Test
    public void testActivityRecordReparentedToTaskFragment() {
        final ActivityRecord activity = createActivityRecord(mDc);
        final SurfaceControl activityLeash = mock(SurfaceControl.class);
        doNothing().when(activity).setDropInputMode(anyInt());
        activity.setVisibility(true);
        activity.setSurfaceControl(activityLeash);
        final Task task = activity.getTask();

        // Add a TaskFragment of half of the Task size.
        final TaskFragmentOrganizer organizer = new TaskFragmentOrganizer(Runnable::run);
        final ITaskFragmentOrganizer iOrganizer =
                ITaskFragmentOrganizer.Stub.asInterface(organizer.getOrganizerToken().asBinder());
        mAtm.mTaskFragmentOrganizerController.registerOrganizer(iOrganizer);
        final TaskFragment taskFragment = new TaskFragmentBuilder(mAtm)
                .setParentTask(task)
                .setOrganizer(organizer)
                .build();
        final Rect taskBounds = new Rect();
        task.getBounds(taskBounds);
        taskFragment.setBounds(0, 0, taskBounds.right / 2, taskBounds.bottom);
        spyOn(taskFragment);
        mockSurfaceFreezerSnapshot(taskFragment.mSurfaceFreezer);

        assertTrue(mDc.mChangingContainers.isEmpty());
        assertFalse(mDc.mAppTransition.isTransitionSet());

        // Schedule app transition when reparent activity to a TaskFragment of different size.
        final Rect startBounds = new Rect(activity.getBounds());
        activity.reparent(taskFragment, POSITION_TOP);

        // It should transit at TaskFragment level with snapshot on the activity surface.
        verify(taskFragment).initializeChangeTransition(activity.getBounds(), activityLeash);
        assertTrue(mDc.mChangingContainers.contains(taskFragment));
        assertTrue(mDc.mAppTransition.containsTransitRequest(TRANSIT_CHANGE));
        assertEquals(startBounds, taskFragment.mSurfaceFreezer.mFreezeBounds);
    }

    @Test
    public void testGetNextAppTransitionBackgroundColor() {
        assumeFalse(WindowManagerService.sEnableShellTransitions);

        // No override by default.
        assertEquals(0, mDc.mAppTransition.getNextAppTransitionBackgroundColor());

        // Override with a custom color.
        mDc.mAppTransition.prepareAppTransition(TRANSIT_OPEN, 0);
        final int testColor = 123;
        mDc.mAppTransition.overridePendingAppTransition("testPackage", 0 /* enterAnim */,
                0 /* exitAnim */, testColor, null /* startedCallback */, null /* endedCallback */,
                false /* overrideTaskTransaction */);

        assertEquals(testColor, mDc.mAppTransition.getNextAppTransitionBackgroundColor());
        assertTrue(mDc.mAppTransition.isNextAppTransitionOverrideRequested());

        // Override with ActivityEmbedding remote animation. Background color should be kept.
        mDc.mAppTransition.overridePendingAppTransitionRemote(mock(RemoteAnimationAdapter.class),
                false /* sync */, true /* isActivityEmbedding */);

        assertEquals(testColor, mDc.mAppTransition.getNextAppTransitionBackgroundColor());
        assertFalse(mDc.mAppTransition.isNextAppTransitionOverrideRequested());

        // Background color should not be cleared anymore after #clear().
        mDc.mAppTransition.clear();
        assertEquals(0, mDc.mAppTransition.getNextAppTransitionBackgroundColor());
        assertFalse(mDc.mAppTransition.isNextAppTransitionOverrideRequested());
    }

    @Test
    public void testGetNextAppRequestedAnimation() {
        assumeFalse(WindowManagerService.sEnableShellTransitions);
        final String packageName = "testPackage";
        final int enterAnimResId = 1;
        final int exitAnimResId = 2;
        final int testColor = 123;
        final Animation enterAnim = mock(Animation.class);
        final Animation exitAnim = mock(Animation.class);
        final TransitionAnimation transitionAnimation = mDc.mAppTransition.mTransitionAnimation;
        spyOn(transitionAnimation);
        doReturn(enterAnim).when(transitionAnimation)
                .loadAppTransitionAnimation(packageName, enterAnimResId);
        doReturn(exitAnim).when(transitionAnimation)
                .loadAppTransitionAnimation(packageName, exitAnimResId);

        // No override by default.
        assertNull(mDc.mAppTransition.getNextAppRequestedAnimation(true /* enter */));
        assertNull(mDc.mAppTransition.getNextAppRequestedAnimation(false /* enter */));

        // Override with a custom animation.
        mDc.mAppTransition.prepareAppTransition(TRANSIT_OPEN, 0);
        mDc.mAppTransition.overridePendingAppTransition(packageName, enterAnimResId, exitAnimResId,
                testColor, null /* startedCallback */, null /* endedCallback */,
                false /* overrideTaskTransaction */);

        assertEquals(enterAnim, mDc.mAppTransition.getNextAppRequestedAnimation(true /* enter */));
        assertEquals(exitAnim, mDc.mAppTransition.getNextAppRequestedAnimation(false /* enter */));
        assertTrue(mDc.mAppTransition.isNextAppTransitionOverrideRequested());

        // Override with ActivityEmbedding remote animation. Custom animation should be kept.
        mDc.mAppTransition.overridePendingAppTransitionRemote(mock(RemoteAnimationAdapter.class),
                false /* sync */, true /* isActivityEmbedding */);

        assertEquals(enterAnim, mDc.mAppTransition.getNextAppRequestedAnimation(true /* enter */));
        assertEquals(exitAnim, mDc.mAppTransition.getNextAppRequestedAnimation(false /* enter */));
        assertFalse(mDc.mAppTransition.isNextAppTransitionOverrideRequested());

        // Custom animation should not be cleared anymore after #clear().
        mDc.mAppTransition.clear();
        assertNull(mDc.mAppTransition.getNextAppRequestedAnimation(true /* enter */));
        assertNull(mDc.mAppTransition.getNextAppRequestedAnimation(false /* enter */));
    }

    private class TestRemoteAnimationRunner implements IRemoteAnimationRunner {
        boolean mCancelled = false;
        @Override
        public void onAnimationStart(@WindowManager.TransitionOldType int transit,
                RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers,
                RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback) throws RemoteException {
        }

        @Override
        public void onAnimationCancelled(boolean isKeyguardOccluded) {
            mCancelled = true;
        }

        @Override
        public IBinder asBinder() {
            return null;
        }
    }
}
