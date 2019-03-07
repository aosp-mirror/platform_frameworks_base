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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.FLAG_SHOW_WHEN_LOCKED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import android.app.TaskStackListener;
import android.content.pm.ActivityInfo;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Tests for the {@link ActivityDisplay} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityDisplayTests
 */
@SmallTest
@Presubmit
public class ActivityDisplayTests extends ActivityTestsBase {

    @Test
    public void testLastFocusedStackIsUpdatedWhenMovingStack() {
        // Create a stack at bottom.
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
        final ActivityStack stack =
                new StackBuilder(mRootActivityContainer).setOnTop(!ON_TOP).build();
        final ActivityStack prevFocusedStack = display.getFocusedStack();

        stack.moveToFront("moveStackToFront");
        // After moving the stack to front, the previous focused should be the last focused.
        assertTrue(stack.isFocusedStackOnDisplay());
        assertEquals(prevFocusedStack, display.getLastFocusedStack());

        stack.moveToBack("moveStackToBack", null /* task */);
        // After moving the stack to back, the stack should be the last focused.
        assertEquals(stack, display.getLastFocusedStack());
    }

    /**
     * This test simulates the picture-in-picture menu activity launches an activity to fullscreen
     * stack. The fullscreen stack should be the top focused for resuming correctly.
     */
    @Test
    public void testFullscreenStackCanBeFocusedWhenFocusablePinnedStackExists() {
        // Create a pinned stack and move to front.
        final ActivityStack pinnedStack = mRootActivityContainer.getDefaultDisplay().createStack(
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, ON_TOP);
        final TaskRecord pinnedTask = new TaskBuilder(mService.mStackSupervisor)
                .setStack(pinnedStack).build();
        new ActivityBuilder(mService).setActivityFlags(FLAG_ALWAYS_FOCUSABLE)
                .setTask(pinnedTask).build();
        pinnedStack.moveToFront("movePinnedStackToFront");

        // The focused stack should be the pinned stack.
        assertTrue(pinnedStack.isFocusedStackOnDisplay());

        // Create a fullscreen stack and move to front.
        final ActivityStack fullscreenStack = createFullscreenStackWithSimpleActivityAt(
                mRootActivityContainer.getDefaultDisplay());
        fullscreenStack.moveToFront("moveFullscreenStackToFront");

        // The focused stack should be the fullscreen stack.
        assertTrue(fullscreenStack.isFocusedStackOnDisplay());
    }

    /**
     * Test {@link ActivityDisplay#mPreferredTopFocusableStack} will be cleared when the stack is
     * removed or moved to back, and the focused stack will be according to z-order.
     */
    @Test
    public void testStackShouldNotBeFocusedAfterMovingToBackOrRemoving() {
        // Create a display which only contains 2 stacks.
        final ActivityDisplay display = addNewActivityDisplayAt(ActivityDisplay.POSITION_TOP);
        final ActivityStack stack1 = createFullscreenStackWithSimpleActivityAt(display);
        final ActivityStack stack2 = createFullscreenStackWithSimpleActivityAt(display);

        // Put stack1 and stack2 on top.
        stack1.moveToFront("moveStack1ToFront");
        stack2.moveToFront("moveStack2ToFront");
        assertTrue(stack2.isFocusedStackOnDisplay());

        // Stack1 should be focused after moving stack2 to back.
        stack2.moveToBack("moveStack2ToBack", null /* task */);
        assertTrue(stack1.isFocusedStackOnDisplay());

        // Stack2 should be focused after removing stack1.
        display.removeChild(stack1);
        assertTrue(stack2.isFocusedStackOnDisplay());
    }

    /**
     * Verifies {@link ActivityDisplay#remove} should not resume home stack on the removing display.
     */
    @Test
    public void testNotResumeHomeStackOnRemovingDisplay() {
        // Create a display which supports system decoration and allows reparenting stacks to
        // another display when the display is removed.
        final ActivityDisplay display = spy(createNewActivityDisplay());
        doReturn(false).when(display).shouldDestroyContentOnRemove();
        doReturn(true).when(display).supportsSystemDecorations();
        mRootActivityContainer.addChild(display, ActivityDisplay.POSITION_TOP);

        // Put home stack on the display.
        final ActivityStack homeStack = display.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_HOME, ON_TOP);
        final TaskRecord task = new TaskBuilder(mSupervisor).setStack(homeStack).build();
        new ActivityBuilder(mService).setTask(task).build();
        display.removeChild(homeStack);
        final ActivityStack spiedHomeStack = spy(homeStack);
        display.addChild(spiedHomeStack, ActivityDisplay.POSITION_TOP);
        reset(spiedHomeStack);

        // Put a finishing standard activity which will be reparented.
        final ActivityStack stack = createFullscreenStackWithSimpleActivityAt(display);
        stack.topRunningActivityLocked().makeFinishingLocked();

        display.remove();

        // The removed display should have no focused stack and its home stack should never resume.
        assertNull(display.getFocusedStack());
        verify(spiedHomeStack, never()).resumeTopActivityUncheckedLocked(any(), any());
    }

    private ActivityStack createFullscreenStackWithSimpleActivityAt(ActivityDisplay display) {
        final ActivityStack fullscreenStack = display.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, ON_TOP);
        final TaskRecord fullscreenTask = new TaskBuilder(mService.mStackSupervisor)
                .setStack(fullscreenStack).build();
        new ActivityBuilder(mService).setTask(fullscreenTask).build();
        return fullscreenStack;
    }

    /**
     * Verifies the correct activity is returned when querying the top running activity.
     */
    @Test
    public void testTopRunningActivity() {
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        final ActivityStack stack = new StackBuilder(mRootActivityContainer).build();
        final ActivityRecord activity = stack.getTopActivity();

        // Create empty stack on top.
        final ActivityStack emptyStack =
                new StackBuilder(mRootActivityContainer).setCreateActivity(false).build();

        // Make sure the top running activity is not affected when keyguard is not locked.
        assertTopRunningActivity(activity, display);

        // Check to make sure activity not reported when it cannot show on lock and lock is on.
        doReturn(true).when(keyguard).isKeyguardLocked();
        assertEquals(activity, display.topRunningActivity());
        assertNull(display.topRunningActivity(true /* considerKeyguardState */));

        // Move stack with activity to top.
        stack.moveToFront("testStackToFront");
        assertEquals(stack, display.getFocusedStack());
        assertEquals(activity, display.topRunningActivity());
        assertNull(display.topRunningActivity(true /* considerKeyguardState */));

        // Add activity that should be shown on the keyguard.
        final ActivityRecord showWhenLockedActivity = new ActivityBuilder(mService)
                .setCreateTask(true)
                .setStack(stack)
                .setActivityFlags(FLAG_SHOW_WHEN_LOCKED)
                .build();

        // Ensure the show when locked activity is returned.
        assertTopRunningActivity(showWhenLockedActivity, display);

        // Move empty stack to front. The running activity in focusable stack which below the
        // empty stack should be returned.
        emptyStack.moveToFront("emptyStackToFront");
        assertEquals(stack, display.getFocusedStack());
        assertTopRunningActivity(showWhenLockedActivity, display);
    }

    private static void assertTopRunningActivity(ActivityRecord top, ActivityDisplay display) {
        assertEquals(top, display.topRunningActivity());
        assertEquals(top, display.topRunningActivity(true /* considerKeyguardState */));
    }

    /**
     * This test enforces that alwaysOnTop stack is placed at proper position.
     */
    @Test
    public void testAlwaysOnTopStackLocation() {
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
        final ActivityStack alwaysOnTopStack = display.createStack(WINDOWING_MODE_FREEFORM,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mService).setCreateTask(true)
                .setStack(alwaysOnTopStack).build();
        alwaysOnTopStack.setAlwaysOnTop(true);
        display.positionChildAtTop(alwaysOnTopStack, false /* includingParents */);
        assertTrue(alwaysOnTopStack.isAlwaysOnTop());
        // Ensure always on top state is synced to the children of the stack.
        assertTrue(alwaysOnTopStack.getTopActivity().isAlwaysOnTop());
        assertEquals(alwaysOnTopStack, display.getTopStack());

        final ActivityStack pinnedStack = display.createStack(
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertEquals(pinnedStack, display.getPinnedStack());
        assertEquals(pinnedStack, display.getTopStack());

        final ActivityStack anotherAlwaysOnTopStack = display.createStack(
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        anotherAlwaysOnTopStack.setAlwaysOnTop(true);
        display.positionChildAtTop(anotherAlwaysOnTopStack, false /* includingParents */);
        assertTrue(anotherAlwaysOnTopStack.isAlwaysOnTop());
        int topPosition = display.getChildCount() - 1;
        // Ensure the new alwaysOnTop stack is put below the pinned stack, but on top of the
        // existing alwaysOnTop stack.
        assertEquals(anotherAlwaysOnTopStack, display.getChildAt(topPosition - 1));

        final ActivityStack nonAlwaysOnTopStack = display.createStack(
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertEquals(display, nonAlwaysOnTopStack.getDisplay());
        topPosition = display.getChildCount() - 1;
        // Ensure the non-alwaysOnTop stack is put below the three alwaysOnTop stacks, but above the
        // existing other non-alwaysOnTop stacks.
        assertEquals(nonAlwaysOnTopStack, display.getChildAt(topPosition - 3));

        anotherAlwaysOnTopStack.setAlwaysOnTop(false);
        display.positionChildAtTop(anotherAlwaysOnTopStack, false /* includingParents */);
        assertFalse(anotherAlwaysOnTopStack.isAlwaysOnTop());
        // Ensure, when always on top is turned off for a stack, the stack is put just below all
        // other always on top stacks.
        assertEquals(anotherAlwaysOnTopStack, display.getChildAt(topPosition - 2));
        anotherAlwaysOnTopStack.setAlwaysOnTop(true);

        // Ensure always on top state changes properly when windowing mode changes.
        anotherAlwaysOnTopStack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertFalse(anotherAlwaysOnTopStack.isAlwaysOnTop());
        assertEquals(anotherAlwaysOnTopStack, display.getChildAt(topPosition - 2));
        anotherAlwaysOnTopStack.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertTrue(anotherAlwaysOnTopStack.isAlwaysOnTop());
        assertEquals(anotherAlwaysOnTopStack, display.getChildAt(topPosition - 1));
    }

    @Test
    public void testRemoveStackInWindowingModes() {
        removeStackTests(() -> mRootActivityContainer.removeStacksInWindowingModes(
                WINDOWING_MODE_FULLSCREEN));
    }

    @Test
    public void testRemoveStackWithActivityTypes() {
        removeStackTests(
                () -> mRootActivityContainer.removeStacksWithActivityTypes(ACTIVITY_TYPE_STANDARD));
    }

    private void removeStackTests(Runnable runnable) {
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
        final ActivityStack stack1 = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final ActivityStack stack2 = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final ActivityStack stack3 = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final ActivityStack stack4 = display.createStack(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final TaskRecord task1 = new TaskBuilder(mService.mStackSupervisor).setStack(
                stack1).setTaskId(1).build();
        final TaskRecord task2 = new TaskBuilder(mService.mStackSupervisor).setStack(
                stack2).setTaskId(2).build();
        final TaskRecord task3 = new TaskBuilder(mService.mStackSupervisor).setStack(
                stack3).setTaskId(3).build();
        final TaskRecord task4 = new TaskBuilder(mService.mStackSupervisor).setStack(
                stack4).setTaskId(4).build();

        // Reordering stacks while removing stacks.
        doAnswer(invocation -> {
            display.positionChildAtTop(stack3, false);
            return true;
        }).when(mSupervisor).removeTaskByIdLocked(eq(task4.taskId), anyBoolean(), anyBoolean(),
                any());

        // Removing stacks from the display while removing stacks.
        doAnswer(invocation -> {
            display.removeChild(stack2);
            return true;
        }).when(mSupervisor).removeTaskByIdLocked(eq(task2.taskId), anyBoolean(), anyBoolean(),
                any());

        runnable.run();
        verify(mSupervisor).removeTaskByIdLocked(eq(task4.taskId), anyBoolean(), anyBoolean(),
                any());
        verify(mSupervisor).removeTaskByIdLocked(eq(task3.taskId), anyBoolean(), anyBoolean(),
                any());
        verify(mSupervisor).removeTaskByIdLocked(eq(task2.taskId), anyBoolean(), anyBoolean(),
                any());
        verify(mSupervisor).removeTaskByIdLocked(eq(task1.taskId), anyBoolean(), anyBoolean(),
                any());
    }

    /**
     * Ensures that {@link TaskStackListener} can receive callback about the activity in size
     * compatibility mode.
     */
    @Test
    public void testHandleActivitySizeCompatMode() throws Exception {
        final ActivityDisplay display = mRootActivityContainer.getDefaultDisplay();
        final ActivityRecord activity = createFullscreenStackWithSimpleActivityAt(
                display).topRunningActivityLocked();
        activity.setState(ActivityStack.ActivityState.RESUMED, "testHandleActivitySizeCompatMode");
        activity.info.resizeMode = ActivityInfo.RESIZE_MODE_UNRESIZEABLE;
        activity.info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
        activity.getTaskRecord().getConfiguration().windowConfiguration.getBounds().set(
                0, 0, 1000, 2000);

        final ArrayList<CompletableFuture<IBinder>> resultWrapper = new ArrayList<>();
        mService.getTaskChangeNotificationController().registerTaskStackListener(
                new TaskStackListener() {
                    @Override
                    public void onSizeCompatModeActivityChanged(int displayId,
                            IBinder activityToken) {
                        resultWrapper.get(0).complete(activityToken);
                    }
                });

        // Expect the exact component name when the activity is in size compatible mode.
        activity.getResolvedOverrideConfiguration().windowConfiguration.getBounds().set(
                0, 0, 800, 1600);
        resultWrapper.add(new CompletableFuture<>());
        display.handleActivitySizeCompatModeIfNeeded(activity);

        assertEquals(activity.appToken, resultWrapper.get(0).get(2, TimeUnit.SECONDS));

        // Expect null component name when switching to non-size-compat mode activity.
        activity.info.resizeMode = ActivityInfo.RESIZE_MODE_RESIZEABLE;
        resultWrapper.set(0, new CompletableFuture<>());
        display.handleActivitySizeCompatModeIfNeeded(activity);

        assertNull(resultWrapper.get(0).get(2, TimeUnit.SECONDS));
    }
}
