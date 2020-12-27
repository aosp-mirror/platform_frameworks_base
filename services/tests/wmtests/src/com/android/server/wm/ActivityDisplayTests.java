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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.FLAG_SHOW_WHEN_LOCKED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.clearInvocations;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link DisplayContent} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityDisplayTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
// TODO(b/144248496): Merge to DisplayContentTests
public class ActivityDisplayTests extends WindowTestsBase {

    @Test
    public void testLastFocusedStackIsUpdatedWhenMovingStack() {
        // Create a stack at bottom.
        final TaskDisplayArea taskDisplayAreas =
                mRootWindowContainer.getDefaultDisplay().getDefaultTaskDisplayArea();
        final Task stack =
                new TaskBuilder(mSupervisor).setOnTop(!ON_TOP).setCreateActivity(true).build();
        final Task prevFocusedStack = taskDisplayAreas.getFocusedRootTask();

        stack.moveToFront("moveStackToFront");
        // After moving the stack to front, the previous focused should be the last focused.
        assertTrue(stack.isFocusedRootTaskOnDisplay());
        assertEquals(prevFocusedStack, taskDisplayAreas.getLastFocusedRootTask());

        stack.moveToBack("moveStackToBack", null /* task */);
        // After moving the stack to back, the stack should be the last focused.
        assertEquals(stack, taskDisplayAreas.getLastFocusedRootTask());
    }

    /**
     * This test simulates the picture-in-picture menu activity launches an activity to fullscreen
     * stack. The fullscreen stack should be the top focused for resuming correctly.
     */
    @Test
    public void testFullscreenStackCanBeFocusedWhenFocusablePinnedStackExists() {
        // Create a pinned stack and move to front.
        final Task pinnedStack = mRootWindowContainer.getDefaultTaskDisplayArea()
                .createRootTask(WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task pinnedTask = new TaskBuilder(mAtm.mTaskSupervisor)
                .setParentTask(pinnedStack).build();
        new ActivityBuilder(mAtm).setActivityFlags(FLAG_ALWAYS_FOCUSABLE)
                .setTask(pinnedTask).build();
        pinnedStack.moveToFront("movePinnedStackToFront");

        // The focused stack should be the pinned stack.
        assertTrue(pinnedStack.isFocusedRootTaskOnDisplay());

        // Create a fullscreen stack and move to front.
        final Task fullscreenStack = createFullscreenStackWithSimpleActivityAt(
                mRootWindowContainer.getDefaultDisplay());
        fullscreenStack.moveToFront("moveFullscreenStackToFront");

        // The focused stack should be the fullscreen stack.
        assertTrue(fullscreenStack.isFocusedRootTaskOnDisplay());
    }

    /**
     * Test {@link TaskDisplayArea#mPreferredTopFocusableRootTask} will be cleared when
     * the stack is removed or moved to back, and the focused stack will be according to z-order.
     */
    @Test
    public void testStackShouldNotBeFocusedAfterMovingToBackOrRemoving() {
        // Create a display which only contains 2 stacks.
        final DisplayContent display = addNewDisplayContentAt(POSITION_TOP);
        final Task stack1 = createFullscreenStackWithSimpleActivityAt(display);
        final Task stack2 = createFullscreenStackWithSimpleActivityAt(display);

        // Put stack1 and stack2 on top.
        stack1.moveToFront("moveStack1ToFront");
        stack2.moveToFront("moveStack2ToFront");
        assertTrue(stack2.isFocusedRootTaskOnDisplay());

        // Stack1 should be focused after moving stack2 to back.
        stack2.moveToBack("moveStack2ToBack", null /* task */);
        assertTrue(stack1.isFocusedRootTaskOnDisplay());

        // Stack2 should be focused after removing stack1.
        stack1.getDisplayArea().removeRootTask(stack1);
        assertTrue(stack2.isFocusedRootTaskOnDisplay());
    }

    /**
     * Verifies {@link DisplayContent#remove} should not resume home stack on the removing display.
     */
    @Test
    public void testNotResumeHomeStackOnRemovingDisplay() {
        // Create a display which supports system decoration and allows reparenting stacks to
        // another display when the display is removed.
        final DisplayContent display = new TestDisplayContent.Builder(
                mAtm, 1000, 1500).setSystemDecorations(true).build();
        doReturn(false).when(display).shouldDestroyContentOnRemove();

        // Put home stack on the display.
        final Task homeStack = new TaskBuilder(mSupervisor)
                .setDisplay(display).setActivityType(ACTIVITY_TYPE_HOME).build();

        // Put a finishing standard activity which will be reparented.
        final Task stack = createFullscreenStackWithSimpleActivityAt(display);
        stack.topRunningActivity().makeFinishingLocked();

        clearInvocations(homeStack);
        display.remove();

        // The removed display should have no focused stack and its home stack should never resume.
        assertNull(display.getFocusedRootTask());
        verify(homeStack, never()).resumeTopActivityUncheckedLocked(any(), any());
    }

    private Task createFullscreenStackWithSimpleActivityAt(DisplayContent display) {
        final Task fullscreenStack = display.getDefaultTaskDisplayArea().createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task fullscreenTask = new TaskBuilder(mAtm.mTaskSupervisor)
                .setParentTask(fullscreenStack).build();
        new ActivityBuilder(mAtm).setTask(fullscreenTask).build();
        return fullscreenStack;
    }

    /**
     * Verifies the correct activity is returned when querying the top running activity.
     */
    @Test
    public void testTopRunningActivity() {
        final DisplayContent display = mRootWindowContainer.getDefaultDisplay();
        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        final Task stack = new TaskBuilder(mSupervisor).setCreateActivity(true).build();
        final ActivityRecord activity = stack.getTopNonFinishingActivity();

        // Create empty stack on top.
        final Task emptyStack = new TaskBuilder(mSupervisor).build();

        // Make sure the top running activity is not affected when keyguard is not locked.
        assertTopRunningActivity(activity, display);

        // Check to make sure activity not reported when it cannot show on lock and lock is on.
        doReturn(true).when(keyguard).isKeyguardLocked();
        assertEquals(activity, display.topRunningActivity());
        assertNull(display.topRunningActivity(true /* considerKeyguardState */));

        // Move stack with activity to top.
        stack.moveToFront("testStackToFront");
        assertEquals(stack, display.getFocusedRootTask());
        assertEquals(activity, display.topRunningActivity());
        assertNull(display.topRunningActivity(true /* considerKeyguardState */));

        // Add activity that should be shown on the keyguard.
        final ActivityRecord showWhenLockedActivity = new ActivityBuilder(mAtm)
                .setTask(stack)
                .setActivityFlags(FLAG_SHOW_WHEN_LOCKED)
                .build();

        // Ensure the show when locked activity is returned.
        assertTopRunningActivity(showWhenLockedActivity, display);

        // Move empty stack to front. The running activity in focusable stack which below the
        // empty stack should be returned.
        emptyStack.moveToFront("emptyStackToFront");
        assertEquals(stack, display.getFocusedRootTask());
        assertTopRunningActivity(showWhenLockedActivity, display);
    }

    private static void assertTopRunningActivity(ActivityRecord top, DisplayContent display) {
        assertEquals(top, display.topRunningActivity());
        assertEquals(top, display.topRunningActivity(true /* considerKeyguardState */));
    }

    /**
     * This test enforces that alwaysOnTop stack is placed at proper position.
     */
    @Test
    public void testAlwaysOnTopStackLocation() {
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task alwaysOnTopStack = taskDisplayArea.createRootTask(WINDOWING_MODE_FREEFORM,
                ACTIVITY_TYPE_STANDARD, true /* onTop */);
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(alwaysOnTopStack).build();
        alwaysOnTopStack.setAlwaysOnTop(true);
        taskDisplayArea.positionChildAt(POSITION_TOP, alwaysOnTopStack,
                false /* includingParents */);
        assertTrue(alwaysOnTopStack.isAlwaysOnTop());
        // Ensure always on top state is synced to the children of the stack.
        assertTrue(alwaysOnTopStack.getTopNonFinishingActivity().isAlwaysOnTop());
        assertEquals(alwaysOnTopStack, taskDisplayArea.getTopRootTask());

        final Task pinnedStack = taskDisplayArea.createRootTask(
                WINDOWING_MODE_PINNED, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertEquals(pinnedStack, taskDisplayArea.getRootPinnedTask());
        assertEquals(pinnedStack, taskDisplayArea.getTopRootTask());

        final Task anotherAlwaysOnTopStack = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        anotherAlwaysOnTopStack.setAlwaysOnTop(true);
        taskDisplayArea.positionChildAt(POSITION_TOP, anotherAlwaysOnTopStack,
                false /* includingParents */);
        assertTrue(anotherAlwaysOnTopStack.isAlwaysOnTop());
        int topPosition = taskDisplayArea.getRootTaskCount() - 1;
        // Ensure the new alwaysOnTop stack is put below the pinned stack, but on top of the
        // existing alwaysOnTop stack.
        assertEquals(topPosition - 1, taskDisplayArea.getTaskIndexOf(anotherAlwaysOnTopStack));

        final Task nonAlwaysOnTopStack = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD, true /* onTop */);
        assertEquals(taskDisplayArea, nonAlwaysOnTopStack.getDisplayArea());
        topPosition = taskDisplayArea.getRootTaskCount() - 1;
        // Ensure the non-alwaysOnTop stack is put below the three alwaysOnTop stacks, but above the
        // existing other non-alwaysOnTop stacks.
        assertEquals(topPosition - 3, taskDisplayArea.getTaskIndexOf(nonAlwaysOnTopStack));

        anotherAlwaysOnTopStack.setAlwaysOnTop(false);
        taskDisplayArea.positionChildAt(POSITION_TOP, anotherAlwaysOnTopStack,
                false /* includingParents */);
        assertFalse(anotherAlwaysOnTopStack.isAlwaysOnTop());
        // Ensure, when always on top is turned off for a stack, the stack is put just below all
        // other always on top stacks.
        assertEquals(topPosition - 2, taskDisplayArea.getTaskIndexOf(anotherAlwaysOnTopStack));
        anotherAlwaysOnTopStack.setAlwaysOnTop(true);

        // Ensure always on top state changes properly when windowing mode changes.
        anotherAlwaysOnTopStack.setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        assertFalse(anotherAlwaysOnTopStack.isAlwaysOnTop());
        assertEquals(topPosition - 2, taskDisplayArea.getTaskIndexOf(anotherAlwaysOnTopStack));
        anotherAlwaysOnTopStack.setWindowingMode(WINDOWING_MODE_FREEFORM);
        assertTrue(anotherAlwaysOnTopStack.isAlwaysOnTop());
        assertEquals(topPosition - 1, taskDisplayArea.getTaskIndexOf(anotherAlwaysOnTopStack));

        final Task dreamStack = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_DREAM, true /* onTop */);
        assertEquals(taskDisplayArea, dreamStack.getDisplayArea());
        assertTrue(dreamStack.isAlwaysOnTop());
        topPosition = taskDisplayArea.getRootTaskCount() - 1;
        // Ensure dream shows above all activities, including PiP
        assertEquals(dreamStack, taskDisplayArea.getTopRootTask());
        assertEquals(topPosition - 1, taskDisplayArea.getTaskIndexOf(pinnedStack));

        final Task assistStack = taskDisplayArea.createRootTask(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_ASSISTANT, true /* onTop */);
        assertEquals(taskDisplayArea, assistStack.getDisplayArea());
        assertFalse(assistStack.isAlwaysOnTop());
        topPosition = taskDisplayArea.getRootTaskCount() - 1;

        // Ensure Assistant shows as a non-always-on-top activity when config_assistantOnTopOfDream
        // is false and on top of everything when true.
        final boolean isAssistantOnTop = mContext.getResources()
                .getBoolean(com.android.internal.R.bool.config_assistantOnTopOfDream);
        assertEquals(isAssistantOnTop ? topPosition : topPosition - 4,
                taskDisplayArea.getTaskIndexOf(assistStack));
    }

    @Test
    public void testRemoveRootTaskInWindowingModes() {
        removeStackTests(() -> mRootWindowContainer.removeRootTasksInWindowingModes(
                WINDOWING_MODE_FULLSCREEN));
    }

    @Test
    public void testRemoveStackWithActivityTypes() {
        removeStackTests(() -> mRootWindowContainer.removeRootTasksWithActivityTypes(
                ACTIVITY_TYPE_STANDARD));
    }

    private void removeStackTests(Runnable runnable) {
        final TaskDisplayArea taskDisplayArea = mRootWindowContainer.getDefaultTaskDisplayArea();
        final Task stack1 = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task stack2 = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task stack3 = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task stack4 = taskDisplayArea.createRootTask(WINDOWING_MODE_FULLSCREEN,
                ACTIVITY_TYPE_STANDARD, ON_TOP);
        final Task task1 = new TaskBuilder(mAtm.mTaskSupervisor).setParentTask(stack1).build();
        final Task task2 = new TaskBuilder(mAtm.mTaskSupervisor).setParentTask(stack2).build();
        final Task task3 = new TaskBuilder(mAtm.mTaskSupervisor).setParentTask(stack3).build();
        final Task task4 = new TaskBuilder(mAtm.mTaskSupervisor).setParentTask(stack4).build();

        // Reordering stacks while removing stacks.
        doAnswer(invocation -> {
            taskDisplayArea.positionChildAt(POSITION_TOP, stack3, false /*includingParents*/);
            return true;
        }).when(mSupervisor).removeTask(eq(task4), anyBoolean(), anyBoolean(), any());

        // Removing stacks from the display while removing stacks.
        doAnswer(invocation -> {
            taskDisplayArea.removeRootTask(stack2);
            return true;
        }).when(mSupervisor).removeTask(eq(task2), anyBoolean(), anyBoolean(), any());

        runnable.run();
        verify(mSupervisor).removeTask(eq(task4), anyBoolean(), anyBoolean(), any());
        verify(mSupervisor).removeTask(eq(task3), anyBoolean(), anyBoolean(), any());
        verify(mSupervisor).removeTask(eq(task2), anyBoolean(), anyBoolean(), any());
        verify(mSupervisor).removeTask(eq(task1), anyBoolean(), anyBoolean(), any());
    }
}
