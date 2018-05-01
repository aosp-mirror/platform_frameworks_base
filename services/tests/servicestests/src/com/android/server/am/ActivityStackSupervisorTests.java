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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.FLAG_SHOW_WHEN_LOCKED;

import static com.android.server.am.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static com.android.server.am.ActivityStackSupervisor
        .MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityOptions;
import android.app.WaitResult;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.SparseIntArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.invocation.InvocationOnMock;

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
    private ActivityManagerService mService;
    private ActivityStackSupervisor mSupervisor;
    private ActivityStack mFullscreenStack;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mService = createActivityManagerService();
        mSupervisor = mService.mStackSupervisor;
        mFullscreenStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
    }

    /**
     * This test ensures that we do not try to restore a task based off an invalid task id. The
     * stack supervisor is a test version so there will be no tasks present. We should expect
     * {@code null} to be returned in this case.
     */
    @Test
    public void testRestoringInvalidTask() throws Exception {
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

        mSupervisor.setFocusStackUnchecked("testReplacingTaskInPinnedStack", mFullscreenStack);

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
        synchronized (mSupervisor.mService) {
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

        mSupervisor.mFocusedStack = isFocusedStack ? stack : null;
        mSupervisor.applySleepTokensLocked(true);
        verify(stack, times(expectWakeFromSleep ? 1 : 0)).awakeFromSleepingLocked();
        verify(stack, times(expectResumeTopActivity ? 1 : 0)).resumeTopActivityUncheckedLocked(
                null /* target */, null /* targetOptions */);
    }

    @Test
    public void testTopRunningActivityLockedWithNonExistentDisplay() throws Exception {
        // Create display that ActivityManagerService does not know about
        final int unknownDisplayId = 100;

        doAnswer((InvocationOnMock invocationOnMock) -> {
            final SparseIntArray displayIds = invocationOnMock.<SparseIntArray>getArgument(0);
            displayIds.put(0, unknownDisplayId);
            return null;
        }).when(mSupervisor.mWindowManager).getDisplaysInFocusOrder(any());

        mSupervisor.mFocusedStack = mock(ActivityStack.class);

        // Supervisor should skip over the non-existent display.
        assertEquals(null, mSupervisor.topRunningActivityLocked());
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
        firstActivity.app.thread = null;
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
     * Verifies the correct activity is returned when querying the top running activity with an
     * empty focused stack.
     */
    @Test
    public void testNonFocusedTopRunningActivity() throws Exception {
        // Create stack to hold focus
        final ActivityStack focusedStack = mService.mStackSupervisor.getDefaultDisplay()
                .createStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);

        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        final ActivityStack stack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(stack).build();

        mSupervisor.mFocusedStack = focusedStack;

        doAnswer((InvocationOnMock invocationOnMock) -> {
            final SparseIntArray displayIds = invocationOnMock.<SparseIntArray>getArgument(0);
            displayIds.put(0, mSupervisor.getDefaultDisplay().mDisplayId);
            return null;
        }).when(mSupervisor.mWindowManager).getDisplaysInFocusOrder(any());

        // Make sure the top running activity is not affected when keyguard is not locked
        assertEquals(activity, mService.mStackSupervisor.topRunningActivityLocked());
        assertEquals(activity, mService.mStackSupervisor.topRunningActivityLocked(
                true /* considerKeyguardState */));

        // Check to make sure activity not reported when it cannot show on lock and lock is on.
        doReturn(true).when(keyguard).isKeyguardLocked();
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
}
