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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.TYPE_VIRTUAL;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;
import static com.android.server.wm.RootWindowContainer.MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE;
import static com.android.server.wm.Task.ActivityState.STOPPED;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.refEq;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.platform.test.annotations.Presubmit;
import android.util.MergedConfiguration;
import android.util.Pair;

import androidx.test.filters.MediumTest;

import com.android.internal.app.ResolverActivity;
import com.android.server.wm.Task.ActivityState;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/**
 * Tests for the {@link RootWindowContainer} class.
 *
 * Build/Install/Run:
 *  atest WmTests:RootActivityContainerTests
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class RootActivityContainerTests extends WindowTestsBase {

    @Before
    public void setUp() throws Exception {
        doNothing().when(mAtm).updateSleepIfNeededLocked();
    }

    /**
     * This test ensures that we do not try to restore a task based off an invalid task id. We
     * should expect {@code null} to be returned in this case.
     */
    @Test
    public void testRestoringInvalidTask() {
        mRootWindowContainer.getDefaultDisplay().removeAllTasks();
        Task task = mRootWindowContainer.anyTaskForId(0 /*taskId*/,
                MATCH_ATTACHED_TASK_OR_RECENT_TASKS_AND_RESTORE, null, false /* onTop */);
        assertNull(task);
    }

    /**
     * This test ensures that an existing task in the pinned stack is moved to the fullscreen
     * activity stack when a new task is added.
     */
    @Test
    public void testReplacingTaskInPinnedStack() {
        Task fullscreenTask = mRootWindowContainer.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(fullscreenTask).build();
        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(fullscreenTask).build();

        fullscreenTask.moveToFront("testReplacingTaskInPinnedStack");

        // Ensure full screen stack has both tasks.
        ensureStackPlacement(fullscreenTask, firstActivity, secondActivity);

        // Move first activity to pinned stack.
        mRootWindowContainer.moveActivityToPinnedRootTask(firstActivity, "initialMove");

        final TaskDisplayArea taskDisplayArea = fullscreenTask.getDisplayArea();
        Task pinnedStack = taskDisplayArea.getRootPinnedTask();
        // Ensure a task has moved over.
        ensureStackPlacement(pinnedStack, firstActivity);
        ensureStackPlacement(fullscreenTask, secondActivity);

        // Move second activity to pinned stack.
        mRootWindowContainer.moveActivityToPinnedRootTask(secondActivity, "secondMove");

        // Need to get stacks again as a new instance might have been created.
        pinnedStack = taskDisplayArea.getRootPinnedTask();
        fullscreenTask = taskDisplayArea.getRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD);
        // Ensure stacks have swapped tasks.
        ensureStackPlacement(pinnedStack, secondActivity);
        ensureStackPlacement(fullscreenTask, firstActivity);
    }

    @Test
    public void testMovingBottomMostStackActivityToPinnedStack() {
        final Task fullscreenTask = mRootWindowContainer.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm)
                .setTask(fullscreenTask).build();
        final Task task = firstActivity.getTask();

        final ActivityRecord secondActivity = new ActivityBuilder(mAtm)
                .setTask(fullscreenTask).build();

        fullscreenTask.moveTaskToBack(task);

        // Ensure full screen stack has both tasks.
        ensureStackPlacement(fullscreenTask, firstActivity, secondActivity);
        assertEquals(task.getTopMostActivity(), secondActivity);
        firstActivity.setState(STOPPED, "testMovingBottomMostStackActivityToPinnedStack");


        // Move first activity to pinned stack.
        mRootWindowContainer.moveActivityToPinnedRootTask(secondActivity, "initialMove");

        assertTrue(firstActivity.mRequestForceTransition);
    }

    private static void ensureStackPlacement(Task task, ActivityRecord... activities) {
        final ArrayList<ActivityRecord> taskActivities = new ArrayList<>();

        task.forAllActivities((Consumer<ActivityRecord>) taskActivities::add, false);

        assertEquals("Expecting " + Arrays.deepToString(activities) + " got " + taskActivities,
                taskActivities.size(), activities != null ? activities.length : 0);

        if (activities == null) {
            return;
        }

        for (ActivityRecord activity : activities) {
            assertTrue(taskActivities.contains(activity));
        }
    }

    @Test
    public void testApplySleepTokens() {
        final DisplayContent display = mRootWindowContainer.getDefaultDisplay();
        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        final Task stack = new TaskBuilder(mSupervisor)
                .setDisplay(display)
                .setOnTop(false)
                .build();

        // Make sure we wake and resume in the case the display is turning on and the keyguard is
        // not showing.
        verifySleepTokenBehavior(display, keyguard, stack, true /*displaySleeping*/,
                false /* displayShouldSleep */, true /* isFocusedStack */,
                false /* keyguardShowing */, true /* expectWakeFromSleep */,
                true /* expectResumeTopActivity */);

        // Make sure we wake and don't resume when the display is turning on and the keyguard is
        // showing.
        verifySleepTokenBehavior(display, keyguard, stack, true /*displaySleeping*/,
                false /* displayShouldSleep */, true /* isFocusedStack */,
                true /* keyguardShowing */, true /* expectWakeFromSleep */,
                false /* expectResumeTopActivity */);

        // Make sure we wake and don't resume when the display is turning on and the keyguard is
        // not showing as unfocused.
        verifySleepTokenBehavior(display, keyguard, stack, true /*displaySleeping*/,
                false /* displayShouldSleep */, false /* isFocusedStack */,
                false /* keyguardShowing */, true /* expectWakeFromSleep */,
                false /* expectResumeTopActivity */);

        // Should not do anything if the display state hasn't changed.
        verifySleepTokenBehavior(display, keyguard, stack, false /*displaySleeping*/,
                false /* displayShouldSleep */, true /* isFocusedStack */,
                false /* keyguardShowing */, false /* expectWakeFromSleep */,
                false /* expectResumeTopActivity */);
    }

    private void verifySleepTokenBehavior(DisplayContent display, KeyguardController keyguard,
            Task stack, boolean displaySleeping, boolean displayShouldSleep,
            boolean isFocusedStack, boolean keyguardShowing, boolean expectWakeFromSleep,
            boolean expectResumeTopActivity) {
        reset(stack);

        doReturn(displayShouldSleep).when(display).shouldSleep();
        doReturn(displaySleeping).when(display).isSleeping();
        doReturn(keyguardShowing).when(keyguard).isKeyguardOrAodShowing(anyInt());

        doReturn(isFocusedStack).when(stack).isFocusedStackOnDisplay();
        doReturn(isFocusedStack ? stack : null).when(display).getFocusedRootTask();
        TaskDisplayArea defaultTaskDisplayArea = display.getDefaultTaskDisplayArea();
        doReturn(isFocusedStack ? stack : null).when(defaultTaskDisplayArea).getFocusedRootTask();
        mRootWindowContainer.applySleepTokens(true);
        verify(stack, times(expectWakeFromSleep ? 1 : 0)).awakeFromSleepingLocked();
        verify(stack, times(expectResumeTopActivity ? 1 : 0)).resumeTopActivityUncheckedLocked(
                null /* target */, null /* targetOptions */);
    }

    @Test
    public void testAwakeFromSleepingWithAppConfiguration() {
        final DisplayContent display = mRootWindowContainer.getDefaultDisplay();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setCreateTask(true).build();
        activity.moveFocusableActivityToTop("test");
        assertTrue(activity.getStack().isFocusedStackOnDisplay());
        ActivityRecordTests.setRotatedScreenOrientationSilently(activity);

        final Configuration rotatedConfig = new Configuration();
        display.computeScreenConfiguration(rotatedConfig, display.getDisplayRotation()
                .rotationForOrientation(activity.getOrientation(), display.getRotation()));
        assertNotEquals(activity.getConfiguration().orientation, rotatedConfig.orientation);
        // Assume the activity was shown in different orientation. For example, the top activity is
        // landscape and the portrait lockscreen is shown.
        activity.setLastReportedConfiguration(
                new MergedConfiguration(mAtm.getGlobalConfiguration(), rotatedConfig));
        activity.setState(ActivityState.STOPPED, "sleep");

        display.setIsSleeping(true);
        doReturn(false).when(display).shouldSleep();
        // Allow to resume when awaking.
        setBooted(mAtm);
        mRootWindowContainer.applySleepTokens(true);

        // The display orientation should be changed by the activity so there is no relaunch.
        verify(activity, never()).relaunchActivityLocked(anyBoolean());
        assertEquals(rotatedConfig.orientation, display.getConfiguration().orientation);
    }

    /**
     * Verifies that removal of activity with task and stack is done correctly.
     */
    @Test
    public void testRemovingStackOnAppCrash() {
        final TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer
                .getDefaultTaskDisplayArea();
        final int originalStackCount = defaultTaskDisplayArea.getRootTaskCount();
        final Task stack = defaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm).setTask(stack).build();

        assertEquals(originalStackCount + 1, defaultTaskDisplayArea.getRootTaskCount());

        // Let's pretend that the app has crashed.
        firstActivity.app.setThread(null);
        mRootWindowContainer.finishTopCrashedActivities(firstActivity.app, "test");

        // Verify that the stack was removed.
        assertEquals(originalStackCount, defaultTaskDisplayArea.getRootTaskCount());
    }

    /**
     * Verifies that removal of activities with task and stack is done correctly when there are
     * several task display areas.
     */
    @Test
    public void testRemovingStackOnAppCrash_multipleDisplayAreas() {
        final TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer
                .getDefaultTaskDisplayArea();
        final int originalStackCount = defaultTaskDisplayArea.getRootTaskCount();
        final Task stack = defaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mAtm).setTask(stack).build();
        assertEquals(originalStackCount + 1, defaultTaskDisplayArea.getRootTaskCount());

        final DisplayContent dc = defaultTaskDisplayArea.getDisplayContent();
        final TaskDisplayArea secondTaskDisplayArea = createTaskDisplayArea(
                dc, mRootWindowContainer.mWmService, "TestTaskDisplayArea", FEATURE_VENDOR_FIRST);
        final Task secondStack = secondTaskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        new ActivityBuilder(mAtm).setTask(secondStack).setUseProcess(firstActivity.app).build();
        assertEquals(1, secondTaskDisplayArea.getRootTaskCount());

        // Let's pretend that the app has crashed.
        firstActivity.app.setThread(null);
        mRootWindowContainer.finishTopCrashedActivities(firstActivity.app, "test");

        // Verify that the stacks were removed.
        assertEquals(originalStackCount, defaultTaskDisplayArea.getRootTaskCount());
        assertEquals(0, secondTaskDisplayArea.getRootTaskCount());
    }

    @Test
    public void testFocusability() {
        final TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer
                .getDefaultTaskDisplayArea();
        final Task stack = defaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(stack).build();

        // Created stacks are focusable by default.
        assertTrue(stack.isTopActivityFocusable());
        assertTrue(activity.isFocusable());

        // If the stack is made unfocusable, its activities should inherit that.
        stack.setFocusable(false);
        assertFalse(stack.isTopActivityFocusable());
        assertFalse(activity.isFocusable());

        final Task pinnedStack = defaultTaskDisplayArea.createRootTask(
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord pinnedActivity = new ActivityBuilder(mAtm)
                .setTask(pinnedStack).build();

        // We should not be focusable when in pinned mode
        assertFalse(pinnedStack.isTopActivityFocusable());
        assertFalse(pinnedActivity.isFocusable());

        // Add flag forcing focusability.
        pinnedActivity.info.flags |= FLAG_ALWAYS_FOCUSABLE;

        // Task with FLAG_ALWAYS_FOCUSABLE should be focusable.
        assertTrue(pinnedStack.isTopActivityFocusable());
        assertTrue(pinnedActivity.isFocusable());
    }

    /**
     * Verify that split-screen primary stack will be chosen if activity is launched that targets
     * split-screen secondary, but a matching existing instance is found on top of split-screen
     * primary stack.
     */
    @Test
    public void testSplitScreenPrimaryChosenWhenTopActivityLaunchedToSecondary() {
        // Create primary split-screen stack with a task and an activity.
        final Task primaryStack = mRootWindowContainer.getDefaultTaskDisplayArea()
                .createRootTask(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD,
                        true /* onTop */);
        final Task task = new TaskBuilder(mSupervisor).setParentTask(primaryStack).build();
        final ActivityRecord r = new ActivityBuilder(mAtm).setTask(task).build();

        // Find a launch stack for the top activity in split-screen primary, while requesting
        // split-screen secondary.
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
        final Task result =
                mRootWindowContainer.getLaunchRootTask(r, options, task, true /* onTop */);

        // Assert that the primary stack is returned.
        assertEquals(primaryStack, result);
    }

    /**
     * Verify that home stack would be moved to front when the top activity is Recents.
     */
    @Test
    public void testFindTaskToMoveToFrontWhenRecentsOnTop() {
        // Create stack/task on default display.
        final Task targetStack = new TaskBuilder(mSupervisor)
                .setCreateActivity(true)
                .setOnTop(false)
                .build();
        final Task targetTask = targetStack.getBottomMostTask();

        // Create Recents on top of the display.
        final Task stack = new TaskBuilder(mSupervisor)
                .setCreateActivity(true)
                .setActivityType(ACTIVITY_TYPE_RECENTS)
                .build();

        final String reason = "findTaskToMoveToFront";
        mSupervisor.findTaskToMoveToFront(targetTask, 0, ActivityOptions.makeBasic(), reason,
                false);

        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        verify(taskDisplayArea).moveHomeRootTaskToFront(contains(reason));
    }

    /**
     * Verify that home stack won't be moved to front if the top activity on other display is
     * Recents.
     */
    @Test
    public void testFindTaskToMoveToFrontWhenRecentsOnOtherDisplay() {
        // Create tasks on default display.
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task targetRootTask = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final Task targetTask = new TaskBuilder(mSupervisor).setParentTask(targetRootTask).build();

        // Create Recents on secondary display.
        final TestDisplayContent secondDisplay = addNewDisplayContentAt(
                DisplayContent.POSITION_TOP);
        final Task rootTask = secondDisplay.getDefaultTaskDisplayArea()
                .createRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_RECENTS, true /* onTop */);
        new ActivityBuilder(mAtm).setTask(rootTask).build();

        final String reason = "findTaskToMoveToFront";
        mSupervisor.findTaskToMoveToFront(targetTask, 0, ActivityOptions.makeBasic(), reason,
                false);

        verify(taskDisplayArea, never()).moveHomeRootTaskToFront(contains(reason));
    }

    /**
     * Verify if a stack is not at the topmost position, it should be able to resume its activity if
     * the stack is the top focused.
     */
    @Test
    public void testResumeActivityWhenNonTopmostStackIsTopFocused() {
        // Create a root task at bottom.
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task rootTask = spy(taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */));
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(rootTask).build();
        taskDisplayArea.positionChildAt(POSITION_BOTTOM, rootTask, false /*includingParents*/);

        // Assume the task is not at the topmost position (e.g. behind always-on-top stacks) but it
        // is the current top focused task.
        assertFalse(rootTask.isTopStackInDisplayArea());
        doReturn(rootTask).when(mRootWindowContainer).getTopDisplayFocusedRootTask();

        // Use the task as target to resume.
        mRootWindowContainer.resumeFocusedTasksTopActivities(
                rootTask, activity, null /* targetOptions */);

        // Verify the target task should resume its activity.
        verify(rootTask, times(1)).resumeTopActivityUncheckedLocked(
                eq(activity), eq(null /* targetOptions */));
    }

    /**
     * Verify that home activity will be started on a display even if another display has a
     * focusable activity.
     */
    @Test
    public void testResumeFocusedStacksStartsHomeActivity_NoActivities() {
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        taskDisplayArea.getRootHomeTask().removeIfPossible();
        taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);

        doReturn(true).when(mRootWindowContainer).resumeHomeActivity(any(), any(), any());

        mAtm.setBooted(true);

        // Trigger resume on all displays
        mRootWindowContainer.resumeFocusedTasksTopActivities();

        // Verify that home activity was started on the default display
        verify(mRootWindowContainer).resumeHomeActivity(any(), any(), eq(taskDisplayArea));
    }

    /**
     * Verify that home activity will be started on a display even if another display has a
     * focusable activity.
     */
    @Test
    public void testResumeFocusedStacksStartsHomeActivity_ActivityOnSecondaryScreen() {
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        taskDisplayArea.getRootHomeTask().removeIfPossible();
        taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);

        // Create an activity on secondary display.
        final TestDisplayContent secondDisplay = addNewDisplayContentAt(
                DisplayContent.POSITION_TOP);
        final Task rootTask = secondDisplay.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        new ActivityBuilder(mAtm).setTask(rootTask).build();

        doReturn(true).when(mRootWindowContainer).resumeHomeActivity(any(), any(), any());

        mAtm.setBooted(true);

        // Trigger resume on all displays
        mRootWindowContainer.resumeFocusedTasksTopActivities();

        // Verify that home activity was started on the default display
        verify(mRootWindowContainer).resumeHomeActivity(any(), any(), eq(taskDisplayArea));
    }

    /**
     * Verify that a lingering transition is being executed in case the activity to be resumed is
     * already resumed
     */
    @Test
    public void testResumeActivityLingeringTransition() {
        // Create a root task at top.
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task rootTask = spy(taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */));
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(rootTask).setOnTop(true).build();
        activity.setState(ActivityState.RESUMED, "test");

        // Assume the task is at the topmost position
        assertTrue(rootTask.isTopStackInDisplayArea());

        // Use the task as target to resume.
        mRootWindowContainer.resumeFocusedTasksTopActivities();

        // Verify the lingering app transition is being executed because it's already resumed
        verify(rootTask, times(1)).executeAppTransition(any());
    }

    @Test
    public void testResumeActivityLingeringTransition_notExecuted() {
        // Create a root task at bottom.
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task rootTask = spy(taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */));
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(rootTask).setOnTop(true).build();
        activity.setState(ActivityState.RESUMED, "test");
        taskDisplayArea.positionChildAt(POSITION_BOTTOM, rootTask, false /*includingParents*/);

        // Assume the task is at the topmost position
        assertFalse(rootTask.isTopStackInDisplayArea());
        doReturn(rootTask).when(mRootWindowContainer).getTopDisplayFocusedRootTask();

        // Use the task as target to resume.
        mRootWindowContainer.resumeFocusedTasksTopActivities();

        // Verify the lingering app transition is being executed because it's already resumed
        verify(rootTask, never()).executeAppTransition(any());
    }

    /**
     * Tests that home activities can be started on the displays that supports system decorations.
     */
    @Test
    public void testStartHomeOnAllDisplays() {
        mockResolveHomeActivity(true /* primaryHome */, false /* forceSystemProvided */);
        mockResolveSecondaryHomeActivity();

        // Create secondary displays.
        final TestDisplayContent secondDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setSystemDecorations(true).build();

        doReturn(true).when(mRootWindowContainer)
                .ensureVisibilityAndConfig(any(), anyInt(), anyBoolean(), anyBoolean());
        doReturn(true).when(mRootWindowContainer).canStartHomeOnDisplayArea(any(), any(),
                anyBoolean());

        mRootWindowContainer.startHomeOnAllDisplays(0, "testStartHome");

        assertTrue(mRootWindowContainer.getDefaultDisplay().getTopRootTask().isActivityTypeHome());
        assertNotNull(secondDisplay.getTopRootTask());
        assertTrue(secondDisplay.getTopRootTask().isActivityTypeHome());
    }

    /**
     * Tests that home activities won't be started before booting when display added.
     */
    @Test
    public void testNotStartHomeBeforeBoot() {
        final int displayId = 1;
        final boolean isBooting = mAtm.mAmInternal.isBooting();
        final boolean isBooted = mAtm.mAmInternal.isBooted();
        try {
            mAtm.mAmInternal.setBooting(false);
            mAtm.mAmInternal.setBooted(false);
            mRootWindowContainer.onDisplayAdded(displayId);
            verify(mRootWindowContainer, never()).startHomeOnDisplay(anyInt(), any(), anyInt());
        } finally {
            mAtm.mAmInternal.setBooting(isBooting);
            mAtm.mAmInternal.setBooted(isBooted);
        }
    }

    /**
     * Tests whether home can be started if being instrumented.
     */
    @Test
    public void testCanStartHomeWhenInstrumented() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        final WindowProcessController app = mock(WindowProcessController.class);
        doReturn(app).when(mAtm).getProcessController(any(), anyInt());

        // Can not start home if we don't want to start home while home is being instrumented.
        doReturn(true).when(app).isInstrumenting();
        final TaskDisplayArea defaultTaskDisplayArea = mRootWindowContainer
                .getDefaultTaskDisplayArea();
        assertFalse(mRootWindowContainer.canStartHomeOnDisplayArea(info, defaultTaskDisplayArea,
                false /* allowInstrumenting*/));

        // Can start home for other cases.
        assertTrue(mRootWindowContainer.canStartHomeOnDisplayArea(info, defaultTaskDisplayArea,
                true /* allowInstrumenting*/));

        doReturn(false).when(app).isInstrumenting();
        assertTrue(mRootWindowContainer.canStartHomeOnDisplayArea(info, defaultTaskDisplayArea,
                false /* allowInstrumenting*/));
        assertTrue(mRootWindowContainer.canStartHomeOnDisplayArea(info, defaultTaskDisplayArea,
                true /* allowInstrumenting*/));
    }

    /**
     * Tests that secondary home activity should not be resolved if device is still locked.
     */
    @Test
    public void testStartSecondaryHomeOnDisplayWithUserKeyLocked() {
        // Create secondary displays.
        final TestDisplayContent secondDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setSystemDecorations(true).build();

        // Use invalid user id to let StorageManager.isUserKeyUnlocked() return false.
        final int currentUser = mRootWindowContainer.mCurrentUser;
        mRootWindowContainer.mCurrentUser = -1;

        mRootWindowContainer.startHomeOnDisplay(0 /* userId */, "testStartSecondaryHome",
                secondDisplay.mDisplayId, true /* allowInstrumenting */, true /* fromHomeKey */);

        try {
            verify(mRootWindowContainer, never()).resolveSecondaryHomeActivity(anyInt(), any());
        } finally {
            mRootWindowContainer.mCurrentUser = currentUser;
        }
    }

    /**
     * Tests that secondary home activity should not be resolved if display does not support system
     * decorations.
     */
    @Test
    public void testStartSecondaryHomeOnDisplayWithoutSysDecorations() {
        // Create secondary displays.
        final TestDisplayContent secondDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setSystemDecorations(false).build();

        mRootWindowContainer.startHomeOnDisplay(0 /* userId */, "testStartSecondaryHome",
                secondDisplay.mDisplayId, true /* allowInstrumenting */, true /* fromHomeKey */);

        verify(mRootWindowContainer, never()).resolveSecondaryHomeActivity(anyInt(), any());
    }

    /**
     * Tests that when starting {@link #ResolverActivity} for home, it should use the standard
     * activity type (in a new stack) so the order of back stack won't be broken.
     */
    @Test
    public void testStartResolverActivityForHome() {
        final ActivityInfo info = new ActivityInfo();
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = "android";
        info.name = ResolverActivity.class.getName();
        doReturn(info).when(mRootWindowContainer).resolveHomeActivity(anyInt(), any());

        mRootWindowContainer.startHomeOnDisplay(0 /* userId */, "test", DEFAULT_DISPLAY);
        final ActivityRecord resolverActivity = mRootWindowContainer.topRunningActivity();

        assertEquals(info, resolverActivity.info);
        assertEquals(ACTIVITY_TYPE_STANDARD, resolverActivity.getRootTask().getActivityType());
    }

    /**
     * Tests that secondary home should be selected if primary home not set.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenPrimaryHomeNotSet() {
        // Setup: primary home not set.
        final Intent primaryHomeIntent = mAtm.getHomeIntent();
        final ActivityInfo aInfoPrimary = new ActivityInfo();
        aInfoPrimary.name = ResolverActivity.class.getName();
        doReturn(aInfoPrimary).when(mRootWindowContainer).resolveHomeActivity(anyInt(),
                refEq(primaryHomeIntent));
        // Setup: set secondary home.
        mockResolveHomeActivity(false /* primaryHome */, false /* forceSystemProvided */);

        // Run the test.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootWindowContainer
                .resolveSecondaryHomeActivity(0 /* userId */, mock(TaskDisplayArea.class));
        final ActivityInfo aInfoSecondary = getFakeHomeActivityInfo(false /* primaryHome*/);
        assertEquals(aInfoSecondary.name, resolvedInfo.first.name);
        assertEquals(aInfoSecondary.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
    }

    /**
     * Tests that the default secondary home activity is always picked when it is in forced by
     * config_useSystemProvidedLauncherForSecondary.
     */
    @Test
    public void testResolveSecondaryHomeActivityForced() {
        // SetUp: set primary home.
        mockResolveHomeActivity(true /* primaryHome */, false /* forceSystemProvided */);
        // SetUp: set secondary home and force it.
        mockResolveHomeActivity(false /* primaryHome */, true /* forceSystemProvided */);
        final Intent secondaryHomeIntent =
                mAtm.getSecondaryHomeIntent(null /* preferredPackage */);
        final List<ResolveInfo> resolutions = new ArrayList<>();
        final ResolveInfo resolveInfo = new ResolveInfo();
        final ActivityInfo aInfoSecondary = getFakeHomeActivityInfo(false /* primaryHome*/);
        resolveInfo.activityInfo = aInfoSecondary;
        resolutions.add(resolveInfo);
        doReturn(resolutions).when(mRootWindowContainer).resolveActivities(anyInt(),
                refEq(secondaryHomeIntent));
        doReturn(true).when(mRootWindowContainer).canStartHomeOnDisplayArea(any(), any(),
                anyBoolean());

        // Run the test.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootWindowContainer
                .resolveSecondaryHomeActivity(0 /* userId */, mock(TaskDisplayArea.class));
        assertEquals(aInfoSecondary.name, resolvedInfo.first.name);
        assertEquals(aInfoSecondary.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
    }

    /**
     * Tests that secondary home should be selected if primary home not support secondary displays
     * or there is no matched activity in the same package as selected primary home.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenPrimaryHomeNotSupportMultiDisplay() {
        // Setup: there is no matched activity in the same package as selected primary home.
        mockResolveHomeActivity(true /* primaryHome */, false /* forceSystemProvided */);
        final List<ResolveInfo> resolutions = new ArrayList<>();
        doReturn(resolutions).when(mRootWindowContainer).resolveActivities(anyInt(), any());
        // Setup: set secondary home.
        mockResolveHomeActivity(false /* primaryHome */, false /* forceSystemProvided */);

        // Run the test.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootWindowContainer
                .resolveSecondaryHomeActivity(0 /* userId */, mock(TaskDisplayArea.class));
        final ActivityInfo aInfoSecondary = getFakeHomeActivityInfo(false /* primaryHome*/);
        assertEquals(aInfoSecondary.name, resolvedInfo.first.name);
        assertEquals(aInfoSecondary.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
    }
    /**
     * Tests that primary home activity should be selected if it already support secondary displays.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenPrimaryHomeSupportMultiDisplay() {
        // SetUp: set primary home.
        mockResolveHomeActivity(true /* primaryHome */, false /* forceSystemProvided */);
        // SetUp: put primary home info on 2nd item
        final List<ResolveInfo> resolutions = new ArrayList<>();
        final ResolveInfo infoFake1 = new ResolveInfo();
        infoFake1.activityInfo = new ActivityInfo();
        infoFake1.activityInfo.name = "fakeActivity1";
        infoFake1.activityInfo.applicationInfo = new ApplicationInfo();
        infoFake1.activityInfo.applicationInfo.packageName = "fakePackage1";
        final ResolveInfo infoFake2 = new ResolveInfo();
        final ActivityInfo aInfoPrimary = getFakeHomeActivityInfo(true /* primaryHome */);
        infoFake2.activityInfo = aInfoPrimary;
        resolutions.add(infoFake1);
        resolutions.add(infoFake2);
        doReturn(resolutions).when(mRootWindowContainer).resolveActivities(anyInt(), any());

        doReturn(true).when(mRootWindowContainer).canStartHomeOnDisplayArea(any(), any(),
                anyBoolean());

        // Run the test.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootWindowContainer
                .resolveSecondaryHomeActivity(0 /* userId */, mock(TaskDisplayArea.class));
        assertEquals(aInfoPrimary.name, resolvedInfo.first.name);
        assertEquals(aInfoPrimary.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
    }

    /**
     * Tests that the first one that matches should be selected if there are multiple activities.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenOtherActivitySupportMultiDisplay() {
        // SetUp: set primary home.
        mockResolveHomeActivity(true /* primaryHome */, false /* forceSystemProvided */);
        // Setup: prepare two eligible activity info.
        final List<ResolveInfo> resolutions = new ArrayList<>();
        final ResolveInfo infoFake1 = new ResolveInfo();
        infoFake1.activityInfo = new ActivityInfo();
        infoFake1.activityInfo.name = "fakeActivity1";
        infoFake1.activityInfo.applicationInfo = new ApplicationInfo();
        infoFake1.activityInfo.applicationInfo.packageName = "fakePackage1";
        final ResolveInfo infoFake2 = new ResolveInfo();
        infoFake2.activityInfo = new ActivityInfo();
        infoFake2.activityInfo.name = "fakeActivity2";
        infoFake2.activityInfo.applicationInfo = new ApplicationInfo();
        infoFake2.activityInfo.applicationInfo.packageName = "fakePackage2";
        resolutions.add(infoFake1);
        resolutions.add(infoFake2);
        doReturn(resolutions).when(mRootWindowContainer).resolveActivities(anyInt(), any());

        doReturn(true).when(mRootWindowContainer).canStartHomeOnDisplayArea(any(), any(),
                anyBoolean());

        // Use the first one of matched activities in the same package as selected primary home.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootWindowContainer
                .resolveSecondaryHomeActivity(0 /* userId */, mock(TaskDisplayArea.class));

        assertEquals(infoFake1.activityInfo.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
        assertEquals(infoFake1.activityInfo.name, resolvedInfo.first.name);
    }

    /**
     * Test that {@link RootWindowContainer#getLaunchRootTask} with the real caller id will get the
     * expected stack when requesting the activity launch on the secondary display.
     */
    @Test
    public void testGetLaunchStackWithRealCallerId() {
        // Create a non-system owned virtual display.
        final TestDisplayContent secondaryDisplay =
                new TestDisplayContent.Builder(mAtm, 1000, 1500)
                        .setType(TYPE_VIRTUAL).setOwnerUid(100).build();

        // Create an activity with specify the original launch pid / uid.
        final ActivityRecord r = new ActivityBuilder(mAtm).setLaunchedFromPid(200)
                .setLaunchedFromUid(200).build();

        // Simulate ActivityStarter to find a launch stack for requesting the activity to launch
        // on the secondary display with realCallerId.
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(secondaryDisplay.mDisplayId);
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
        doReturn(true).when(mSupervisor).canPlaceEntityOnDisplay(secondaryDisplay.mDisplayId,
                300 /* test realCallerPid */, 300 /* test realCallerUid */, r.info);
        final Task result = mRootWindowContainer.getLaunchRootTask(r, options,
                null /* task */, true /* onTop */, null, 300 /* test realCallerPid */,
                300 /* test realCallerUid */);

        // Assert that the stack is returned as expected.
        assertNotNull(result);
        assertEquals("The display ID of the stack should same as secondary display ",
                secondaryDisplay.mDisplayId, result.getDisplayId());
    }

    @Test
    public void testGetValidLaunchStackOnDisplayWithCandidateRootTask() {
        // Create a root task with an activity on secondary display.
        final TestDisplayContent secondaryDisplay = new TestDisplayContent.Builder(mAtm, 300,
                600).build();
        final Task task = new TaskBuilder(mSupervisor)
                .setDisplay(secondaryDisplay).build();
        final ActivityRecord activity = new ActivityBuilder(mAtm).setTask(task).build();

        // Make sure the root task is valid and can be reused on default display.
        final Task stack = mRootWindowContainer.getValidLaunchRootTaskInTaskDisplayArea(
                mRootWindowContainer.getDefaultTaskDisplayArea(), activity, task,
                null /* options */, null /* launchParams */);
        assertEquals(task, stack);
    }

    @Test
    public void testSwitchUser_missingHomeRootTask() {
        final Task fullscreenTask = mRootWindowContainer.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        doReturn(fullscreenTask).when(mRootWindowContainer).getTopDisplayFocusedRootTask();

        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        Task homeStack = taskDisplayArea.getRootHomeTask();
        if (homeStack != null) {
            homeStack.removeImmediately();
        }
        assertNull(taskDisplayArea.getRootHomeTask());

        int currentUser = mRootWindowContainer.mCurrentUser;
        int otherUser = currentUser + 1;

        mRootWindowContainer.switchUser(otherUser, null);

        assertNotNull(taskDisplayArea.getRootHomeTask());
        assertEquals(taskDisplayArea.getTopRootTask(), taskDisplayArea.getRootHomeTask());
    }

    /**
     * Mock {@link RootWindowContainer#resolveHomeActivity} for returning consistent activity
     * info for test cases.
     *
     * @param primaryHome Indicate to use primary home intent as parameter, otherwise, use
     *                    secondary home intent.
     * @param forceSystemProvided Indicate to force using system provided home activity.
     */
    private void mockResolveHomeActivity(boolean primaryHome, boolean forceSystemProvided) {
        ActivityInfo targetActivityInfo = getFakeHomeActivityInfo(primaryHome);
        Intent targetIntent;
        if (primaryHome) {
            targetIntent = mAtm.getHomeIntent();
        } else {
            Resources resources = mContext.getResources();
            spyOn(resources);
            doReturn(targetActivityInfo.applicationInfo.packageName).when(resources).getString(
                    com.android.internal.R.string.config_secondaryHomePackage);
            doReturn(forceSystemProvided).when(resources).getBoolean(
                    com.android.internal.R.bool.config_useSystemProvidedLauncherForSecondary);
            targetIntent = mAtm.getSecondaryHomeIntent(null /* preferredPackage */);
        }
        doReturn(targetActivityInfo).when(mRootWindowContainer).resolveHomeActivity(anyInt(),
                refEq(targetIntent));
    }

    /**
     * Mock {@link RootWindowContainer#resolveSecondaryHomeActivity} for returning consistent
     * activity info for test cases.
     */
    private void mockResolveSecondaryHomeActivity() {
        final Intent secondaryHomeIntent = mAtm
                .getSecondaryHomeIntent(null /* preferredPackage */);
        final ActivityInfo aInfoSecondary = getFakeHomeActivityInfo(false);
        doReturn(Pair.create(aInfoSecondary, secondaryHomeIntent)).when(mRootWindowContainer)
                .resolveSecondaryHomeActivity(anyInt(), any());
    }

    private ActivityInfo getFakeHomeActivityInfo(boolean primaryHome) {
        final ActivityInfo aInfo = new ActivityInfo();
        aInfo.name = primaryHome ? "fakeHomeActivity" : "fakeSecondaryHomeActivity";
        aInfo.applicationInfo = new ApplicationInfo();
        aInfo.applicationInfo.packageName =
                primaryHome ? "fakeHomePackage" : "fakeSecondaryHomePackage";
        return  aInfo;
    }
}
