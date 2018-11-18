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

import static android.app.ActivityManager.START_DELIVERED_TO_TOP;
import static android.app.ActivityManager.START_TASK_TO_FRONT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.LAUNCH_MULTIPLE;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wm.ActivityDisplay.POSITION_TOP;
import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static com.android.server.wm.ActivityStackSupervisor.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityOptions;
import android.app.WaitResult;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.graphics.Rect;
import android.os.Build;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

/**
 * Tests for the {@link ActivityStackSupervisor} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityStackSupervisorTests
 */
@MediumTest
@Presubmit
public class ActivityStackSupervisorTests extends ActivityTestsBase {
    private ActivityStack mFullscreenStack;

    @Before
    public void setUp() throws Exception {
        setupActivityTaskManagerService();
        mFullscreenStack = mService.mStackSupervisor.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
    }

    /**
     * This test ensures that we do not try to restore a task based off an invalid task id. We
     * should expect {@code null} to be returned in this case.
     */
    @Test
    public void testRestoringInvalidTask() {
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
    public void testReplacingTaskInPinnedStack() {
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
    public void testStoppingActivityRemovedWhenResumed() {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();
        mSupervisor.mStoppingActivities.add(firstActivity);

        firstActivity.completeResumeLocked();

        assertFalse(mSupervisor.mStoppingActivities.contains(firstActivity));
    }

    /**
     * Ensures that waiting results are notified of launches.
     */
    @SuppressWarnings("SynchronizeOnNonFinalField")
    @Test
    public void testReportWaitingActivityLaunchedIfNeeded() {
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();

        // #notifyAll will be called on the ActivityManagerService. we must hold the object lock
        // when this happens.
        synchronized (mSupervisor.mService.mGlobalLock) {
            final WaitResult taskToFrontWait = new WaitResult();
            mSupervisor.mWaitingActivityLaunched.add(taskToFrontWait);
            mSupervisor.reportWaitingActivityLaunchedIfNeeded(firstActivity, START_TASK_TO_FRONT);

            assertThat(mSupervisor.mWaitingActivityLaunched).isEmpty();
            assertEquals(taskToFrontWait.result, START_TASK_TO_FRONT);
            assertNull(taskToFrontWait.who);

            final WaitResult deliverToTopWait = new WaitResult();
            mSupervisor.mWaitingActivityLaunched.add(deliverToTopWait);
            mSupervisor.reportWaitingActivityLaunchedIfNeeded(firstActivity,
                    START_DELIVERED_TO_TOP);

            assertThat(mSupervisor.mWaitingActivityLaunched).isEmpty();
            assertEquals(deliverToTopWait.result, START_DELIVERED_TO_TOP);
            assertEquals(deliverToTopWait.who, firstActivity.realActivity);
        }
    }

    @Test
    public void testApplySleepTokensLocked() {
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
    public void testRemovingStackOnAppCrash() {
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
    public void testFocusability() {
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
     * Verify that split-screen primary stack will be chosen if activity is launched that targets
     * split-screen secondary, but a matching existing instance is found on top of split-screen
     * primary stack.
     */
    @Test
    public void testSplitScreenPrimaryChosenWhenTopActivityLaunchedToSecondary() {
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
    public void testResizeDockedStackForSplitScreenPrimary() {
        final Rect taskSize = new Rect(0, 0, 600, 600);
        final Rect stackSize = new Rect(0, 0, 300, 300);

        // Create primary split-screen stack with a task.
        final ActivityStack primaryStack = mService.mStackSupervisor.getDefaultDisplay()
                .createStack(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD,
                        true /* onTop */);
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(primaryStack).build();

        // Resize dock stack.
        mService.resizeDockedStack(stackSize, taskSize, null, null, null);

        // Verify dock stack & its task bounds if is equal as resized result.
        assertEquals(primaryStack.getBounds(), stackSize);
        assertEquals(task.getBounds(), taskSize);
    }

    /**
     * Verify that home stack would be moved to front when the top activity is Recents.
     */
    @Test
    public void testFindTaskToMoveToFrontWhenRecentsOnTop() {
        // Create stack/task on default display.
        final ActivityDisplay display = mSupervisor.getDefaultDisplay();
        final TestActivityStack targetStack = new StackBuilder(mSupervisor).setOnTop(false).build();
        final TaskRecord targetTask = targetStack.getChildAt(0);

        // Create Recents on top of the display.
        final ActivityStack stack =
                new StackBuilder(mSupervisor).setActivityType(ACTIVITY_TYPE_RECENTS).build();

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
    public void testFindTaskToMoveToFrontWhenRecentsOnOtherDisplay() {
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
    public void testResumeActivityWhenNonTopmostStackIsTopFocused() {
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

    /**
     * Tests home activities that targeted sdk before Q cannot start on secondary display.
     */
    @Test
    public void testStartHomeTargetSdkBeforeQ() throws Exception {
        final TestActivityDisplay secondDisplay = spy(createNewActivityDisplay());
        mSupervisor.addChild(secondDisplay, POSITION_TOP);
        doReturn(true).when(secondDisplay).supportsSystemDecorations();

        final ActivityInfo info = new ActivityInfo();
        info.launchMode = LAUNCH_MULTIPLE;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.Q;
        assertTrue(mSupervisor.canStartHomeOnDisplay(info, secondDisplay.mDisplayId,
                false /* allowInstrumenting */));

        info.applicationInfo.targetSdkVersion = Build.VERSION_CODES.P;
        assertFalse(mSupervisor.canStartHomeOnDisplay(info, secondDisplay.mDisplayId,
                false /* allowInstrumenting */));
    }

    /**
     * Tests that home activities can be started on the displays that supports system decorations.
     */
    @Test
    public void testStartHomeOnAllDisplays() {
        // Create secondary displays.
        final TestActivityDisplay secondDisplay = spy(createNewActivityDisplay());
        mSupervisor.addChild(secondDisplay, POSITION_TOP);
        doReturn(true).when(secondDisplay).supportsSystemDecorations();

        // Create mock tasks and other necessary mocks.
        TaskBuilder taskBuilder = new TaskBuilder(mService.mStackSupervisor).setCreateStack(false);
        final TaskRecord.TaskRecordFactory factory = mock(TaskRecord.TaskRecordFactory.class);
        TaskRecord.setTaskRecordFactory(factory);
        doAnswer(i -> taskBuilder.build()).when(factory)
                .create(any(), anyInt(), any(), any(), any(), any());
        doReturn(true).when(mService.mStackSupervisor)
                .ensureVisibilityAndConfig(any(), anyInt(), anyBoolean(), anyBoolean());
        doReturn(true).when(mSupervisor).canStartHomeOnDisplay(any(), anyInt(), anyBoolean());

        mSupervisor.startHomeOnAllDisplays(0, "testStartHome");

        assertTrue(mSupervisor.getDefaultDisplay().getTopStack().isActivityTypeHome());
        assertNotNull(secondDisplay.getTopStack());
        assertTrue(secondDisplay.getTopStack().isActivityTypeHome());
    }

    /**
     * Tests that home activities won't be started before booting when display added.
     */
    @Test
    public void testNotStartHomeBeforeBoot() {
        final int displayId = 1;
        final boolean isBooting = mService.mAmInternal.isBooting();
        final boolean isBooted = mService.mAmInternal.isBooted();
        try {
            mService.mAmInternal.setBooting(false);
            mService.mAmInternal.setBooted(false);
            mSupervisor.onDisplayAdded(displayId);
            verify(mSupervisor, never()).startHomeOnDisplay(anyInt(), any(), anyInt());
        } finally {
            mService.mAmInternal.setBooting(isBooting);
            mService.mAmInternal.setBooted(isBooted);
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
        doReturn(app).when(mService).getProcessController(any(), anyInt());

        // Can not start home if we don't want to start home while home is being instrumented.
        doReturn(true).when(app).isInstrumenting();
        assertFalse(mSupervisor.canStartHomeOnDisplay(info, DEFAULT_DISPLAY,
                false /* allowInstrumenting*/));

        // Can start home for other cases.
        assertTrue(mSupervisor.canStartHomeOnDisplay(info, DEFAULT_DISPLAY,
                true /* allowInstrumenting*/));

        doReturn(false).when(app).isInstrumenting();
        assertTrue(mSupervisor.canStartHomeOnDisplay(info, DEFAULT_DISPLAY,
                false /* allowInstrumenting*/));
        assertTrue(mSupervisor.canStartHomeOnDisplay(info, DEFAULT_DISPLAY,
                true /* allowInstrumenting*/));
    }
}
