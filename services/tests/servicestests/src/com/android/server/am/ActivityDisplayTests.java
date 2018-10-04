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

package com.android.server.am;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.content.pm.ActivityInfo.FLAG_ALWAYS_FOCUSABLE;

import static com.android.server.am.ActivityStackSupervisor.ON_TOP;

import static org.junit.Assert.assertTrue;

import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link ActivityDisplay} class.
 *
 * Build/Install/Run:
 *  atest WmTests:ActivityDisplayTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityDisplayTests extends ActivityTestsBase {

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        setupActivityTaskManagerService();
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

    private ActivityStack createFullscreenStackWithSimpleActivityAt(ActivityDisplay display) {
        final ActivityStack fullscreenStack = display.createStack(
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, ON_TOP);
        final TaskRecord fullscreenTask = new TaskBuilder(mService.mStackSupervisor)
                .setStack(fullscreenStack).build();
        new ActivityBuilder(mService).setTask(fullscreenTask).build();
        return fullscreenStack;
    }
}
