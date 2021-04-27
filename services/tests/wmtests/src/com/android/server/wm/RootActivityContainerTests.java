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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityDisplay.POSITION_TOP;
import static com.android.server.wm.ActivityStack.REMOVE_TASK_MODE_DESTROYING;
import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;
import static com.android.server.wm.RootActivityContainer.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE;

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
import static org.mockito.ArgumentMatchers.refEq;

import android.app.ActivityOptions;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Rect;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.util.Pair;
import android.view.DisplayInfo;

import androidx.test.filters.MediumTest;

import com.android.internal.app.ResolverActivity;
import com.android.server.wm.ActivityStack.ActivityState;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for the {@link RootActivityContainer} class.
 *
 * Build/Install/Run:
 *  atest WmTests:RootActivityContainerTests
 */
@MediumTest
@Presubmit
public class RootActivityContainerTests extends ActivityTestsBase {
    private ActivityStack mFullscreenStack;

    @Before
    public void setUp() throws Exception {
        mFullscreenStack = mRootActivityContainer.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true /* onTop */);
    }

    /**
     * This test ensures that we do not try to restore a task based off an invalid task id. We
     * should expect {@code null} to be returned in this case.
     */
    @Test
    public void testRestoringInvalidTask() {
        ((TestActivityDisplay) mRootActivityContainer.getDefaultDisplay()).removeAllTasks();
        TaskRecord task = mRootActivityContainer.anyTaskForId(0 /*taskId*/,
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
        final TaskRecord firstTask = firstActivity.getTaskRecord();

        final ActivityRecord secondActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(mFullscreenStack).build();
        final TaskRecord secondTask = secondActivity.getTaskRecord();

        mFullscreenStack.moveToFront("testReplacingTaskInPinnedStack");

        // Ensure full screen stack has both tasks.
        ensureStackPlacement(mFullscreenStack, firstTask, secondTask);

        // Move first activity to pinned stack.
        final Rect sourceBounds = new Rect();
        mRootActivityContainer.moveActivityToPinnedStack(firstActivity, sourceBounds,
                0f /*aspectRatio*/, "initialMove");

        final ActivityDisplay display = mFullscreenStack.getDisplay();
        ActivityStack pinnedStack = display.getPinnedStack();
        // Ensure a task has moved over.
        ensureStackPlacement(pinnedStack, firstTask);
        ensureStackPlacement(mFullscreenStack, secondTask);

        // Move second activity to pinned stack.
        mRootActivityContainer.moveActivityToPinnedStack(secondActivity, sourceBounds,
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
        assertEquals("Expecting " + Arrays.deepToString(tasks) + " got " + stackTasks,
                stackTasks.size(), tasks != null ? tasks.length : 0);

        if (tasks == null) {
            return;
        }

        for (TaskRecord task : tasks) {
            assertTrue(stackTasks.contains(task));
        }
    }

    @Test
    public void testApplySleepTokens() {
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
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
        mRootActivityContainer.applySleepTokens(true);
        verify(stack, times(expectWakeFromSleep ? 1 : 0)).awakeFromSleepingLocked();
        verify(stack, times(expectResumeTopActivity ? 1 : 0)).resumeTopActivityUncheckedLocked(
                null /* target */, null /* targetOptions */);
    }

    /**
     * Verifies that removal of activity with task and stack is done correctly.
     */
    @Test
    public void testRemovingStackOnAppCrash() {
        final ActivityDisplay defaultDisplay = mRootActivityContainer.getDefaultDisplay();
        final int originalStackCount = defaultDisplay.getChildCount();
        final ActivityStack stack = mRootActivityContainer.getDefaultDisplay().createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, false /* onTop */);
        final ActivityRecord firstActivity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(stack).build();

        assertEquals(originalStackCount + 1, defaultDisplay.getChildCount());

        // Let's pretend that the app has crashed.
        firstActivity.app.setThread(null);
        mRootActivityContainer.finishTopCrashedActivities(firstActivity.app, "test");

        // Verify that the stack was removed.
        assertEquals(originalStackCount, defaultDisplay.getChildCount());
    }

    @Test
    public void testFocusability() {
        final ActivityStack stack = mRootActivityContainer.getDefaultDisplay().createStack(
                WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(stack).build();

        // Under split screen primary we should be focusable when not minimized
        mRootActivityContainer.setDockedStackMinimized(false);
        assertTrue(stack.isFocusable());
        assertTrue(activity.isFocusable());

        // Under split screen primary we should not be focusable when minimized
        mRootActivityContainer.setDockedStackMinimized(true);
        assertFalse(stack.isFocusable());
        assertFalse(activity.isFocusable());

        final ActivityStack pinnedStack = mRootActivityContainer.getDefaultDisplay().createStack(
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
        pinnedStack.removeTask(pinnedActivity.getTaskRecord(), "testFocusability",
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
        final ActivityStack primaryStack = mRootActivityContainer.getDefaultDisplay()
                .createStack(WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD,
                        true /* onTop */);
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(primaryStack).build();
        final ActivityRecord r = new ActivityBuilder(mService).setTask(task).build();

        // Find a launch stack for the top activity in split-screen primary, while requesting
        // split-screen secondary.
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN_OR_SPLIT_SCREEN_SECONDARY);
        final ActivityStack result =
                mRootActivityContainer.getLaunchStack(r, options, task, true /* onTop */);

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
        final ActivityStack primaryStack = mRootActivityContainer.getDefaultDisplay()
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
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
        final TestActivityStack targetStack = (TestActivityStack) new StackBuilder(
                mRootActivityContainer).setOnTop(false).build();
        final TaskRecord targetTask = targetStack.getChildAt(0);

        // Create Recents on top of the display.
        final ActivityStack stack = new StackBuilder(mRootActivityContainer).setActivityType(
                ACTIVITY_TYPE_RECENTS).build();

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
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
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
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
        final ActivityStack targetStack = spy(display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */));
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(targetStack).build();
        final ActivityRecord activity = new ActivityBuilder(mService).setTask(task).build();
        display.positionChildAtBottom(targetStack);

        // Assume the stack is not at the topmost position (e.g. behind always-on-top stacks) but it
        // is the current top focused stack.
        assertFalse(targetStack.isTopStackOnDisplay());
        doReturn(targetStack).when(mRootActivityContainer).getTopDisplayFocusedStack();

        // Use the stack as target to resume.
        mRootActivityContainer.resumeFocusedStacksTopActivities(
                targetStack, activity, null /* targetOptions */);

        // Verify the target stack should resume its activity.
        verify(targetStack, times(1)).resumeTopActivityUncheckedLocked(
                eq(activity), eq(null /* targetOptions */));
    }

    /**
     * Verify that home activity will be started on a display even if another display has a
     * focusable activity.
     */
    @Test
    public void testResumeFocusedStacksStartsHomeActivity_NoActivities() {
        mFullscreenStack.remove();
        mService.mRootActivityContainer.getActivityDisplay(DEFAULT_DISPLAY).getHomeStack().remove();
        mService.mRootActivityContainer.getActivityDisplay(DEFAULT_DISPLAY)
                .createStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);

        doReturn(true).when(mRootActivityContainer).resumeHomeActivity(any(), any(), anyInt());

        mService.setBooted(true);

        // Trigger resume on all displays
        mRootActivityContainer.resumeFocusedStacksTopActivities();

        // Verify that home activity was started on the default display
        verify(mRootActivityContainer).resumeHomeActivity(any(), any(), eq(DEFAULT_DISPLAY));
    }

    /**
     * Verify that home activity will be started on a display even if another display has a
     * focusable activity.
     */
    @Test
    public void testResumeFocusedStacksStartsHomeActivity_ActivityOnSecondaryScreen() {
        mFullscreenStack.remove();
        mService.mRootActivityContainer.getActivityDisplay(DEFAULT_DISPLAY).getHomeStack().remove();
        mService.mRootActivityContainer.getActivityDisplay(DEFAULT_DISPLAY)
                .createStack(WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);

        // Create an activity on secondary display.
        final TestActivityDisplay secondDisplay = addNewActivityDisplayAt(
                ActivityDisplay.POSITION_TOP);
        final ActivityStack stack = secondDisplay.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(stack).build();
        new ActivityBuilder(mService).setTask(task).build();

        doReturn(true).when(mRootActivityContainer).resumeHomeActivity(any(), any(), anyInt());

        mService.setBooted(true);

        // Trigger resume on all displays
        mRootActivityContainer.resumeFocusedStacksTopActivities();

        // Verify that home activity was started on the default display
        verify(mRootActivityContainer).resumeHomeActivity(any(), any(), eq(DEFAULT_DISPLAY));
    }

    /**
     * Verify that a lingering transition is being executed in case the activity to be resumed is
     * already resumed
     */
    @Test
    public void testResumeActivityLingeringTransition() {
        // Create a stack at top.
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
        final ActivityStack targetStack = spy(display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */));
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(targetStack).build();
        final ActivityRecord activity = new ActivityBuilder(mService).setTask(task).build();
        activity.setState(ActivityState.RESUMED, "test");

        // Assume the stack is at the topmost position
        assertTrue(targetStack.isTopStackOnDisplay());

        // Use the stack as target to resume.
        mRootActivityContainer.resumeFocusedStacksTopActivities();

        // Verify the lingering app transition is being executed because it's already resumed
        verify(targetStack, times(1)).executeAppTransition(any());
    }

    @Test
    public void testResumeActivityLingeringTransition_notExecuted() {
        // Create a stack at bottom.
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
        final ActivityStack targetStack = spy(display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, false /* onTop */));
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(targetStack).build();
        final ActivityRecord activity = new ActivityBuilder(mService).setTask(task).build();
        activity.setState(ActivityState.RESUMED, "test");
        display.positionChildAtBottom(targetStack);

        // Assume the stack is at the topmost position
        assertFalse(targetStack.isTopStackOnDisplay());
        doReturn(targetStack).when(mRootActivityContainer).getTopDisplayFocusedStack();

        // Use the stack as target to resume.
        mRootActivityContainer.resumeFocusedStacksTopActivities();

        // Verify the lingering app transition is being executed because it's already resumed
        verify(targetStack, never()).executeAppTransition(any());
    }

    /**
     * Tests that home activities can be started on the displays that supports system decorations.
     */
    @Test
    public void testStartHomeOnAllDisplays() {
        mockResolveHomeActivity();

        // Create secondary displays.
        final TestActivityDisplay secondDisplay = spy(createNewActivityDisplay());
        mRootActivityContainer.addChild(secondDisplay, POSITION_TOP);
        doReturn(true).when(secondDisplay).supportsSystemDecorations();

        // Create mock tasks and other necessary mocks.
        mockTaskRecordFactory();
        doReturn(true).when(mRootActivityContainer)
                .ensureVisibilityAndConfig(any(), anyInt(), anyBoolean(), anyBoolean());
        doReturn(true).when(mRootActivityContainer).canStartHomeOnDisplay(
                any(), anyInt(), anyBoolean());

        mRootActivityContainer.startHomeOnAllDisplays(0, "testStartHome");

        assertTrue(mRootActivityContainer.getDefaultDisplay().getTopStack().isActivityTypeHome());
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
            mRootActivityContainer.onDisplayAdded(displayId);
            verify(mRootActivityContainer, never()).startHomeOnDisplay(anyInt(), any(), anyInt());
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
        assertFalse(mRootActivityContainer.canStartHomeOnDisplay(info, DEFAULT_DISPLAY,
                false /* allowInstrumenting*/));

        // Can start home for other cases.
        assertTrue(mRootActivityContainer.canStartHomeOnDisplay(info, DEFAULT_DISPLAY,
                true /* allowInstrumenting*/));

        doReturn(false).when(app).isInstrumenting();
        assertTrue(mRootActivityContainer.canStartHomeOnDisplay(info, DEFAULT_DISPLAY,
                false /* allowInstrumenting*/));
        assertTrue(mRootActivityContainer.canStartHomeOnDisplay(info, DEFAULT_DISPLAY,
                true /* allowInstrumenting*/));
    }

    /**
     * Tests that secondary home activity should not be resolved if device is still locked.
     */
    @Test
    public void testStartSecondaryHomeOnDisplayWithUserKeyLocked() {
        // Create secondary displays.
        final TestActivityDisplay secondDisplay = spy(createNewActivityDisplay());
        mRootActivityContainer.addChild(secondDisplay, POSITION_TOP);

        doReturn(true).when(secondDisplay).supportsSystemDecorations();
        // Use invalid user id to let StorageManager.isUserKeyUnlocked() return false.
        final int currentUser = mRootActivityContainer.mCurrentUser;
        mRootActivityContainer.mCurrentUser = -1;

        mRootActivityContainer.startHomeOnDisplay(0 /* userId */, "testStartSecondaryHome",
                secondDisplay.mDisplayId, true /* allowInstrumenting */, true /* fromHomeKey */);

        try {
            verify(mRootActivityContainer, never()).resolveSecondaryHomeActivity(anyInt(),
                    anyInt());
        } finally {
            mRootActivityContainer.mCurrentUser = currentUser;
        }
    }

    /**
     * Tests that secondary home activity should not be resolved if display does not support system
     * decorations.
     */
    @Test
    public void testStartSecondaryHomeOnDisplayWithoutSysDecorations() {
        // Create secondary displays.
        final TestActivityDisplay secondDisplay = spy(createNewActivityDisplay());
        mRootActivityContainer.addChild(secondDisplay, POSITION_TOP);
        doReturn(false).when(secondDisplay).supportsSystemDecorations();

        mRootActivityContainer.startHomeOnDisplay(0 /* userId */, "testStartSecondaryHome",
                secondDisplay.mDisplayId, true /* allowInstrumenting */, true /* fromHomeKey */);

        verify(mRootActivityContainer, never()).resolveSecondaryHomeActivity(anyInt(), anyInt());
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
        doReturn(info).when(mRootActivityContainer).resolveHomeActivity(anyInt(), any());
        mockTaskRecordFactory();

        mRootActivityContainer.startHomeOnDisplay(0 /* userId */, "test", DEFAULT_DISPLAY);
        final ActivityRecord resolverActivity = mRootActivityContainer.topRunningActivity();

        assertEquals(info, resolverActivity.info);
        assertEquals(ACTIVITY_TYPE_STANDARD, resolverActivity.getActivityStack().getActivityType());
    }

    /**
     * Tests that secondary home should be selected if default home not set.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenDefaultHomeNotSet() {
        final Intent defaultHomeIntent = mService.getHomeIntent();
        final ActivityInfo aInfoDefault = new ActivityInfo();
        aInfoDefault.name = ResolverActivity.class.getName();
        doReturn(aInfoDefault).when(mRootActivityContainer).resolveHomeActivity(anyInt(),
                refEq(defaultHomeIntent));

        final String secondaryHomeComponent = mService.mContext.getResources().getString(
                com.android.internal.R.string.config_secondaryHomeComponent);
        final ComponentName comp = ComponentName.unflattenFromString(secondaryHomeComponent);
        final Intent secondaryHomeIntent = mService.getSecondaryHomeIntent(null);
        final ActivityInfo aInfoSecondary = new ActivityInfo();
        aInfoSecondary.name = comp.getClassName();
        doReturn(aInfoSecondary).when(mRootActivityContainer).resolveHomeActivity(anyInt(),
                refEq(secondaryHomeIntent));

        // Should fallback to secondary home if default home not set.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootActivityContainer
                .resolveSecondaryHomeActivity(0 /* userId */, 1 /* displayId */);

        assertEquals(comp.getClassName(), resolvedInfo.first.name);
    }

    /**
     * Tests that the default secondary home activity is always picked when it is in forced by
     * config_useSystemProvidedLauncherForSecondary.
     */
    @Test
    public void testResolveSecondaryHomeActivityForced() throws Exception {
        Resources resources = mContext.getResources();
        spyOn(resources);
        try {
            // setUp: set secondary launcher and force it.
            final String defaultSecondaryHome =
                    "com.android.test/com.android.test.TestDefaultSecondaryHome";
            final ComponentName secondaryComp = ComponentName.unflattenFromString(
                    defaultSecondaryHome);
            doReturn(defaultSecondaryHome).when(resources).getString(
                    com.android.internal.R.string.config_secondaryHomeComponent);
            doReturn(true).when(resources).getBoolean(
                    com.android.internal.R.bool.config_useSystemProvidedLauncherForSecondary);
            final Intent secondaryHomeIntent = mService.getSecondaryHomeIntent(null);
            assertEquals(secondaryComp, secondaryHomeIntent.getComponent());
            final ActivityInfo aInfoSecondary = new ActivityInfo();
            aInfoSecondary.name = secondaryComp.getClassName();
            aInfoSecondary.applicationInfo = new ApplicationInfo();
            aInfoSecondary.applicationInfo.packageName = secondaryComp.getPackageName();
            doReturn(aInfoSecondary).when(mRootActivityContainer).resolveHomeActivity(anyInt(),
                    refEq(secondaryHomeIntent));
            final Intent homeIntent = mService.getHomeIntent();
            final ActivityInfo aInfoDefault = new ActivityInfo();
            aInfoDefault.name = "fakeHomeActivity";
            aInfoDefault.applicationInfo = new ApplicationInfo();
            aInfoDefault.applicationInfo.packageName = "fakeHomePackage";
            doReturn(aInfoDefault).when(mRootActivityContainer).resolveHomeActivity(anyInt(),
                    refEq(homeIntent));
            // Let resolveActivities call to validate both main launcher and second launcher so that
            // resolveActivities call does not work as enabler for secondary.
            final List<ResolveInfo> resolutions1 = new ArrayList<>();
            final ResolveInfo resolveInfo1 = new ResolveInfo();
            resolveInfo1.activityInfo = new ActivityInfo();
            resolveInfo1.activityInfo.name = aInfoDefault.name;
            resolveInfo1.activityInfo.applicationInfo = aInfoDefault.applicationInfo;
            resolutions1.add(resolveInfo1);
            doReturn(resolutions1).when(mRootActivityContainer).resolveActivities(anyInt(),
                    refEq(homeIntent));
            final List<ResolveInfo> resolutions2 = new ArrayList<>();
            final ResolveInfo resolveInfo2 = new ResolveInfo();
            resolveInfo2.activityInfo = new ActivityInfo();
            resolveInfo2.activityInfo.name = aInfoSecondary.name;
            resolveInfo2.activityInfo.applicationInfo = aInfoSecondary.applicationInfo;
            resolutions2.add(resolveInfo2);
            doReturn(resolutions2).when(mRootActivityContainer).resolveActivities(anyInt(),
                    refEq(secondaryHomeIntent));
            doReturn(true).when(mRootActivityContainer).canStartHomeOnDisplay(
                    any(), anyInt(), anyBoolean());

            // Run the test
            final Pair<ActivityInfo, Intent> resolvedInfo = mRootActivityContainer
                    .resolveSecondaryHomeActivity(0 /* userId */, 1 /* displayId */);
            assertEquals(secondaryComp.getClassName(), resolvedInfo.first.name);
            assertEquals(secondaryComp.getPackageName(),
                    resolvedInfo.first.applicationInfo.packageName);
            assertEquals(aInfoSecondary.name, resolvedInfo.first.name);
        } finally {
            // tearDown
            reset(resources);
        }
    }

    /**
     * Tests that secondary home should be selected if default home not support secondary displays
     * or there is no matched activity in the same package as selected default home.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenDefaultHomeNotSupportMultiDisplay() {
        mockResolveHomeActivity();

        final List<ResolveInfo> resolutions = new ArrayList<>();
        doReturn(resolutions).when(mRootActivityContainer).resolveActivities(anyInt(), any());

        final String secondaryHomeComponent = mService.mContext.getResources().getString(
                com.android.internal.R.string.config_secondaryHomeComponent);
        final ComponentName comp = ComponentName.unflattenFromString(secondaryHomeComponent);
        final Intent secondaryHomeIntent = mService.getSecondaryHomeIntent(null);
        final ActivityInfo aInfoSecondary = new ActivityInfo();
        aInfoSecondary.name = comp.getClassName();
        doReturn(aInfoSecondary).when(mRootActivityContainer).resolveHomeActivity(anyInt(),
                refEq(secondaryHomeIntent));

        // Should fallback to secondary home if selected default home not support secondary displays
        // or there is no matched activity in the same package as selected default home.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootActivityContainer
                .resolveSecondaryHomeActivity(0 /* userId */, 1 /* displayId */);

        assertEquals(comp.getClassName(), resolvedInfo.first.name);
    }

    /**
     * Tests that default home activity should be selected if it already support secondary displays.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenDefaultHomeSupportMultiDisplay() {
        final ActivityInfo aInfoDefault = mockResolveHomeActivity();

        final List<ResolveInfo> resolutions = new ArrayList<>();
        final ResolveInfo infoFake1 = new ResolveInfo();
        infoFake1.activityInfo = new ActivityInfo();
        infoFake1.activityInfo.name = "fakeActivity1";
        infoFake1.activityInfo.applicationInfo = new ApplicationInfo();
        infoFake1.activityInfo.applicationInfo.packageName = "fakePackage1";
        final ResolveInfo infoFake2 = new ResolveInfo();
        infoFake2.activityInfo = aInfoDefault;
        resolutions.add(infoFake1);
        resolutions.add(infoFake2);
        doReturn(resolutions).when(mRootActivityContainer).resolveActivities(anyInt(), any());

        doReturn(true).when(mRootActivityContainer).canStartHomeOnDisplay(
                any(), anyInt(), anyBoolean());

        // Use default home activity if it support secondary displays.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootActivityContainer
                .resolveSecondaryHomeActivity(0 /* userId */, 1 /* displayId */);

        assertEquals(aInfoDefault.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
        assertEquals(aInfoDefault.name, resolvedInfo.first.name);
    }

    /**
     * Tests that the first one that matches should be selected if there are multiple activities.
     */
    @Test
    public void testResolveSecondaryHomeActivityWhenOtherActivitySupportMultiDisplay() {
        mockResolveHomeActivity();

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
        doReturn(resolutions).when(mRootActivityContainer).resolveActivities(anyInt(), any());

        doReturn(true).when(mRootActivityContainer).canStartHomeOnDisplay(
                any(), anyInt(), anyBoolean());

        // Use the first one of matched activities in the same package as selected default home.
        final Pair<ActivityInfo, Intent> resolvedInfo = mRootActivityContainer
                .resolveSecondaryHomeActivity(0 /* userId */, 1 /* displayId */);

        assertEquals(infoFake1.activityInfo.applicationInfo.packageName,
                resolvedInfo.first.applicationInfo.packageName);
        assertEquals(infoFake1.activityInfo.name, resolvedInfo.first.name);
    }

    @Test
    public void testLockAllProfileTasks() {
        // Make an activity visible with the user id set to 1
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(mFullscreenStack).build();
        final ActivityRecord activity = new ActivityBuilder(mService).setTask(task)
                .setUid(UserHandle.PER_USER_RANGE + 1).build();

        // Create another activity on top and the user id is 2
        final ActivityRecord topActivity = new ActivityBuilder(mService)
                .setTask(task).setUid(UserHandle.PER_USER_RANGE + 2).build();

        // Make sure the listeners will be notified for putting the task to locked state
        TaskChangeNotificationController controller =
                mService.getTaskChangeNotificationController();
        spyOn(controller);
        mService.mRootActivityContainer.lockAllProfileTasks(1);
        verify(controller).notifyTaskProfileLocked(eq(task.taskId), eq(1));
    }

    /**
     * Test that {@link RootActivityContainer#getLaunchStack} with the real caller id will get the
     * expected stack when requesting the activity launch on the secondary display.
     */
    @Test
    public void testGetLaunchStackWithRealCallerId() {
        // Create a non-system owned virtual display.
        final DisplayInfo info = new DisplayInfo();
        mSupervisor.mService.mContext.getDisplay().getDisplayInfo(info);
        info.type = TYPE_VIRTUAL;
        info.ownerUid = 100;
        final TestActivityDisplay secondaryDisplay = createNewActivityDisplay(info);
        mRootActivityContainer.addChild(secondaryDisplay, POSITION_TOP);

        // Create an activity with specify the original launch pid / uid.
        final ActivityRecord r = new ActivityBuilder(mService).setLaunchedFromPid(200)
                .setLaunchedFromUid(200).build();

        // Simulate ActivityStarter to find a launch stack for requesting the activity to launch
        // on the secondary display with realCallerId.
        final ActivityOptions options = ActivityOptions.makeBasic();
        options.setLaunchDisplayId(secondaryDisplay.mDisplayId);
        options.setLaunchWindowingMode(WINDOWING_MODE_FULLSCREEN);
        doReturn(true).when(mSupervisor).canPlaceEntityOnDisplay(secondaryDisplay.mDisplayId,
                300 /* test realCallerPid */, 300 /* test realCallerUid */, r.info);
        final ActivityStack result = mRootActivityContainer.getLaunchStack(r, options,
                null /* task */, true /* onTop */, null, 300 /* test realCallerPid */,
                300 /* test realCallerUid */);

        // Assert that the stack is returned as expected.
        assertNotNull(result);
        assertEquals("The display ID of the stack should same as secondary display ",
                secondaryDisplay.mDisplayId, result.mDisplayId);
    }

    /**
     * Mock {@link RootActivityContainerTests#resolveHomeActivity} for returning consistent activity
     * info for test cases (the original implementation will resolve from the real package manager).
     */
    private ActivityInfo mockResolveHomeActivity() {
        final Intent homeIntent = mService.getHomeIntent();
        final ActivityInfo aInfoDefault = new ActivityInfo();
        aInfoDefault.name = "fakeHomeActivity";
        aInfoDefault.applicationInfo = new ApplicationInfo();
        aInfoDefault.applicationInfo.packageName = "fakeHomePackage";
        doReturn(aInfoDefault).when(mRootActivityContainer).resolveHomeActivity(anyInt(),
                refEq(homeIntent));
        return aInfoDefault;
    }
}
