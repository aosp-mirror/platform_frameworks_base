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
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;
import static android.content.pm.ActivityInfo.FLAG_SHOW_WHEN_LOCKED;

import static com.android.server.wm.ActivityStackSupervisor.ON_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Tests for the {@link ActivityDisplay} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityDisplayTests
 */
@SmallTest
@Presubmit
public class ActivityDisplayTests extends ActivityTestsBase {

    @Before
    public void setUp() throws Exception {
        setupActivityTaskManagerService();
    }

    @Test
    public void testLastFocusedStackIsUpdatedWhenMovingStack() {
        // Create a stack at bottom.
        final ActivityDisplay display = mSupervisor.getDefaultDisplay();
        final ActivityStack stack = new StackBuilder(mSupervisor).setOnTop(!ON_TOP).build();
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
        final ActivityStack pinnedStack = mSupervisor.getDefaultDisplay().createStack(
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
                mSupervisor.getDefaultDisplay());
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
        mSupervisor.addChild(display, ActivityDisplay.POSITION_TOP);

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
        final ActivityDisplay display = mSupervisor.getDefaultDisplay();
        final KeyguardController keyguard = mSupervisor.getKeyguardController();
        final ActivityStack stack = new StackBuilder(mSupervisor).build();
        final ActivityRecord activity = stack.getTopActivity();

        // Create empty stack on top.
        final ActivityStack emptyStack =
                new StackBuilder(mSupervisor).setCreateActivity(false).build();

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
}
