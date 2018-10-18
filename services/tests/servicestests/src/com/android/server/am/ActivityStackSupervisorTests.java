/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.server.am;

import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.FLAG_SHOW_WHEN_LOCKED;

import static com.android.server.am.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static com.android.server.am.ActivityStackSupervisor.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityOptions;
import android.app.WaitResult;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * Tests for the {@link ActivityStackSupervisor} class.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:com.android.server.am.ActivityStackSupervisorTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityStackSupervisorTests extends ActivityTestsBase {
    private ActivityStack mFullscreenStack;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        setupActivityTaskManagerService();
        mFullscreenStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
    }

    /**
     * This test ensures that we do not try to restore a task based off an invalid task id. We
     * should expect {@code null} to be returned in this case.
     */
    @Test
    public void testRestoringInvalidTask() throws Exception {
        ((TestActivityDisplay) mSupervisor.getDefaultDisplay()).removeAllTasks();
        TaskRecord task = mSupervisor.anyTaskForIdLocked(0 /*taskId*/,
                MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE, null, false /* onTop */);
        assertNull(task);
    }

    /**
     * This test ensures that an existing task in the pinned stack is moved to the fullscreen
     * activity stack when a new task is added.
     */
    @Test
    public void testReplacingTaskInPinnedStack() throws Exception {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();
        final TaskRecord firstTask = firstActivity.getTask();

        final ActivityRecord secondActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();
        final TaskRecord secondTask = secondActivity.getTask();

        mFullscreenStack.moveToFront("testReplacingTaskInPinnedStack");

        // Ensure full screen stack has both tasks.
        ensureStackPlacement(mFullscreenStack, firstTask, secondTask);

        // Move first activity to pinned stack.
        final Rect sourceBounds = new Rect();
        mSupervisor.moveActivityToPinnedStackLocked(firstActivity, sourceBounds,
                0f /*aspectRatio*/, "initialMove");

        final ActivityDisplay display = mFullscreenStack.getDisplay();
        ActivityStack pinnedStack = display.getPinnedStack();
        // Ensure a task has moved over.
        ensureStackPlacement(pinnedStack, firstTask);
        ensureStackPlacement(mFullscreenStack, secondTask);

        // Move second activity to pinned stack.
        mSupervisor.moveActivityToPinnedStackLocked(secondActivity, sourceBounds,
                0f /*aspectRatio*/, "secondMove");

        // Need to get stacks again as a new instance might have been created.
        pinnedStack = display.getPinnedStack();
        mFullscreenStack = display.getStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        // Ensure stacks have swapped tasks.
        ensureStackPlacement(pinnedStack, secondTask);
        ensureStackPlacement(mFullscreenStack, firstTask);
    }

    private static void ensureStackPlacement(ActivityStack stack, TaskRecord... tasks) {
        final ArrayList<TaskRecord> stackTasks = stack.getAllTasks();
        assertEquals(stackTasks.size(), tasks != null ? tasks.length : 0);

        if (tasks == null) {
            return;
        }

        for (TaskRecord task : tasks) {
            assertTrue(stackTasks.contains(task));
        }
    }

    /**
     * Ensures that an activity is removed from the stopping activities list once it is resumed.
     */
    @Test
    public void testStoppingActivityRemovedWhenResumed() throws Exception {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();
        mSupervisor.mStoppingActivities.add(firstActivity);

        firstActivity.completeResumeLocked();

        assertFalse(mSupervisor.mStoppingActivities.contains(firstActivity));
    }

    /**
     * Ensures that waiting results are notified of launches.
     */
    @Test
    public void testReportWaitingActivityLaunchedIfNeeded() throws Exception {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();

        // #notifyAll will be called on the ActivityManagerService. we must hold the object lock
        // when this happens.
        synchronized (mSupervisor.mService.mGlobalLock) {
            final WaitResult taskToFrontWait = new WaitResult();
            mSupervisor.mWaitingActivityLaunched.add(taskToFrontWait);
            mSupervisor.reportWaitingActivityLaunchedIfNeeded(firstActivity, START_TASK_TO_FRONT);

            assertTrue(mSupervisor.mWaitingActivityLaunched.isEmpty());
            assertEquals(taskToFrontWait.result, START_TASK_TO_FRONT);
            assertEquals(taskToFrontWait.who, null);

            final WaitResult deliverToTopWait = new WaitResult();
            mSupervisor.mWaitingActivityLaunched.add(deliverToTopWait);
            mSupervisor.reportWaitingActivityLaunchedIfNeeded(firstActivity,
                    START_DELIVERED_TO_TOP);

            assertTrue(mSupervisor.mWaitingActivityLaunched.isEmpty());
            assertEquals(deliverToTopWait.result, START_DELIVERED_TO_TOP);
            assertEquals(deliverToTopWait.who, firstActivity.realActivity);
        }
    }

    @Test
    public void testApplySleepTokensLocked() throws Exception {
        final ActivityDisplay display = mSupervisor.getDefaultDisplay();
        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        final ActivityStack stack = mock(ActivityStack.class);
        display.addChild(stack, 0 /* position */);

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

    private void verifySleepTokenBehavior(ActivityDisplay display, KeyguardController keyguard,
            ActivityStack stack, boolean displaySleeping, boolean displayShouldSleep,
            boolean isFocusedStack, boolean keyguardShowing, boolean expectWakeFromSleep,
            boolean expectResumeTopActivity) {
        reset(stack);

        doReturn(displayShouldSleep).when(display).shouldSleep();
        doReturn(displaySleeping).when(display).isSleeping();
        doReturn(keyguardShowing).when(keyguard).isKeyguardOrAodShowing(anyInt());

        doReturn(isFocusedStack).when(stack).isFocusedStackOnDisplay();
        doReturn(isFocusedStack ? stack : null).when(display).getFocusedStack();
        mSupervisor.applySleepTokensLocked(true);
        verify(stack, times(expectWakeFromSleep ? 1 : 0)).awakeFromSleepingLocked();
        verify(stack, times(expectResumeTopActivity ? 1 : 0)).resumeTopActivityUncheckedLocked(
                null /* target */, null /* targetOptions */);
    }

    /**
     * Verifies that removal of activity with task and stack is done correctly.
     */
    @Test
    public void testRemovingStackOnAppCrash() throws Exception {
        final ActivityDisplay defaultDisplay = mService.mStackSupervisor.getDefaultDisplay();
        final int originalStackCount = defaultDisplay.getChildCount();
        final ActivityStack stack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(stack).build();

        assertEquals(originalStackCount + 1, defaultDisplay.getChildCount());

        // Let's pretend that the app has crashed.
        firstActivity.app.setThread(null);
        mService.mStackSupervisor.finishTopCrashedActivitiesLocked(firstActivity.app, "test");

        // Verify that the stack was removed.
        assertEquals(originalStackCount, defaultDisplay.getChildCount());
    }

    @Test
    public void testFocusability() throws Exception {
        final ActivityStack stack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(stack).build();

        // Under split screen primary we should be focusable when not minimized
        mService.mStackSupervisor.setDockedStackMinimized(false);
        assertTrue(stack.isFocusable());
        assertTrue(activity.isFocusable());

        // Under split screen primary we should not be focusable when minimized
        mService.mStackSupervisor.setDockedStackMinimized(true);
        assertFalse(stack.isFocusable());
        assertFalse(activity.isFocusable());

        final ActivityStack pinnedStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord pinnedActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(pinnedStack).build();

        // We should not be focusable when in pinned mode
        assertFalse(pinnedStack.isFocusable());
        assertFalse(pinnedActivity.isFocusable());

        // Add flag forcing focusability.
        pinnedActivity.info.flags |= FLAG_ALWAYS_FOCUSABLE;

        // We should not be focusable when in pinned mode
        assertTrue(pinnedStack.isFocusable());
        assertTrue(pinnedActivity.isFocusable());

        // Without the overridding activity, stack should not be focusable.
        pinnedStack.removeTask(pinnedActivity.getTask(), "testFocusability",
                REMOVE_TASK_MODE_DESTROYING);
        assertFalse(pinnedStack.isFocusable());
    }

    /**
     * Verifies the correct activity is returned when querying the top running activity.
     */
    @Test
    public void testTopRunningActivity() throws Exception {
        // Create stack to hold focus
        final ActivityDisplay display = mService.mStackSupervisor.getDefaultDisplay();
        final ActivityStack emptyStack = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);

        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        final ActivityStack stack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(stack).build();

        // Make sure the top running activity is not affected when keyguard is not locked
        assertEquals(activity, mService.mStackSupervisor.topRunningActivityLocked());
        assertEquals(activity, mService.mStackSupervisor.topRunningActivityLocked(
                true /* considerKeyguardState */));

        // Check to make sure activity not reported when it cannot show on lock and lock is on.
        doReturn(true).when(keyguard).isKeyguardLocked();
        assertEquals(activity, mService.mStackSupervisor.topRunningActivityLocked());
        assertEquals(null, mService.mStackSupervisor.topRunningActivityLocked(
                true /* considerKeyguardState */));

        // Change focus to stack with activity.
        stack.moveToFront("focusChangeToTestStack");
        assertEquals(stack, display.getFocusedStack());
        assertEquals(activity, mService.mStackSupervisor.topRunningActivityLocked());
        assertEquals(null, mService.mStackSupervisor.topRunningActivityLocked(
                true /* considerKeyguardState */));

        // Add activity that should be shown on the keyguard.
        final ActivityRecord showWhenLockedActivity = new ActivityBuilder(mService)
                .setCreateTask(true)
                .setStack(stack)
                .setActivityFlags(FLAG_SHOW_WHEN_LOCKED)
                .build();

        // Ensure the show when locked activity is returned.
        assertEquals(showWhenLockedActivity, mService.mStackSupervisor.topRunningActivityLocked());
        assertEquals(showWhenLockedActivity, mService.mStackSupervisor.topRunningActivityLocked(
                true /* considerKeyguardState */));

        // Change focus back to empty stack
        emptyStack.moveToFront("focusChangeToEmptyStack");
        assertEquals(emptyStack, display.getFocusedStack());
        // Looking for running activity only in top and focused stack, so nothing should be returned
        // from empty stack.
        assertEquals(null, mService.mStackSupervisor.topRunningActivityLocked());
        assertEquals(null, mService.mStackSupervisor.topRunningActivityLocked(
                true /* considerKeyguardState */));
    }

    /**
     * Verify that split-screen primary stack will be chosen if activity is launched that targets
     * split-screen secondary, but a matching existing instance is found on top of split-screen
     * primary stack.
     */
    @Test
    public void testSplitScreenPrimaryChosenWhenTopActivityLaunchedToSecondary() throws Exception {
        // Create primary split-screen stack with a task and an activity.
        final ActivityStack primaryStack = mService.mStackSupervisor.getDefaultDisplay()
                .createStack(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD,
                        true /* onTop */);
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(primaryStack).build();
        final ActivityRecord r = new ActivityBuilder(mService).setTask(task).build();

        // Find a launch stack for the top activity in split-screen primary, while requesting
        // split-screen secondary.
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
        final ActivityStack result = mSupervisor.getLaunchStack(r, options, task, true /* onTop */);

        // Assert that the primary stack is returned.
        assertEquals(primaryStack, result);
    }

    /**
     * Verify split-screen primary stack & task can resized by
     * {@link android.app.IActivityTaskManager#resizeDockedStack} as expect.
     */
    @Test
    public void testResizeDockedStackForSplitScreenPrimary() throws Exception {
        final Rect TASK_SIZE = new Rect(0, 0, 600, 600);
        final Rect STACK_SIZE = new Rect(0, 0, 300, 300);

        // Create primary split-screen stack with a task.
        final ActivityStack primaryStack = mService.mStackSupervisor.getDefaultDisplay()
                .createStack(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD,
                        true /* onTop */);
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(primaryStack).build();

        // Resize dock stack.
        mService.resizeDockedStack(STACK_SIZE, TASK_SIZE, null, null, null);

        // Verify dock stack & its task bounds if is equal as resized result.
        assertEquals(primaryStack.getBounds(), STACK_SIZE);
        assertEquals(task.getBounds(), TASK_SIZE);
    }

    /**
     * Verify that home stack would be moved to front when the top activity is Recents.
     */
    @Test
    public void testFindTaskToMoveToFrontWhenRecentsOnTop() throws Exception {
        // Create stack/task on default display.
        final ActivityDisplay display = mSupervisor.getDefaultDisplay();
        final ActivityStack targetStack = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final TaskRecord targetTask = new TaskBuilder(mSupervisor).setStack(targetStack).build();

        // Create Recents on top of the display.
        final ActivityStack stack = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(stack).build();
        new ActivityBuilder(mService).setTask(task).build();

        final String reason = "findTaskToMoveToFront";
        mSupervisor.findTaskToMoveToFront(targetTask, 0, ActivityOptions.makeBasic(), reason,
                false);

        verify(display).moveHomeStackToFront(contains(reason));
    }

    /**
     * Verify that home stack won't be moved to front if the top activity on other display is
     * Recents.
     */
    @Test
    public void testFindTaskToMoveToFrontWhenRecentsOnOtherDisplay() throws Exception {
        // Create stack/task on default display.
        final ActivityDisplay display = mSupervisor.getDefaultDisplay();
        final ActivityStack targetStack = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final TaskRecord targetTask = new TaskBuilder(mSupervisor).setStack(targetStack).build();

        // Create Recents on secondary display.
        final TestActivityDisplay secondDisplay = addNewActivityDisplayAt(
                ActivityDisplay.POSITION_TOP);
        final ActivityStack stack = secondDisplay.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_RECENTS, true /* onTop */);
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(stack).build();
        new ActivityBuilder(mService).setTask(task).build();

        final String reason = "findTaskToMoveToFront";
        mSupervisor.findTaskToMoveToFront(targetTask, 0, ActivityOptions.makeBasic(), reason,
                false);

        verify(display, never()).moveHomeStackToFront(contains(reason));
    }

    /**
     * Verify if a stack is not at the topmost position, it should be able to resume its activity if
     * the stack is the top focused.
     */
    @Test
    public void testResumeActivityWhenNonTopmostStackIsTopFocused() throws Exception {
        // Create a stack at bottom.
        final ActivityDisplay display = mSupervisor.getDefaultDisplay();
        final ActivityStack targetStack = spy(display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */));
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(targetStack).build();
        final ActivityRecord activity = new ActivityBuilder(mService).setTask(task).build();
        display.positionChildAtBottom(targetStack);

        // Assume the stack is not at the topmost position (e.g. behind always-on-top stacks) but it
        // is the current top focused stack.
        assertFalse(targetStack.isTopStackOnDisplay());
        doReturn(targetStack).when(mSupervisor).getTopDisplayFocusedStack();

        // Use the stack as target to resume.
        mSupervisor.resumeFocusedStacksTopActivitiesLocked(
                targetStack, activity, null /* targetOptions */);

        // Verify the target stack should resume its activity.
        verify(targetStack, times(1)).resumeTopActivityUncheckedLocked(
                eq(activity), eq(null /* targetOptions */));
    }
}
