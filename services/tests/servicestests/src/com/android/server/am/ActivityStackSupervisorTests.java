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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.content.ComponentName;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.android.server.am.ActivityStackSupervisor.MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE;

/**
 * Tests for the {@link ActivityStackSupervisor} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.am.ActivityStackSupervisorTests
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ActivityStackSupervisorTests extends ActivityTestsBase {
    private final ComponentName testActivityComponent =
            ComponentName.unflattenFromString("com.foo/.BarActivity");

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
                MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE, null);
        assertNull(task);
    }

    /**
     * This test ensures that an existing task in the pinned stack is moved to the fullscreen
     * activity stack when a new task is added.
     */
    @Test
    public void testReplacingTaskInPinnedStack() throws Exception {
        final TaskRecord firstTask = createTask(
                mSupervisor, testActivityComponent, mFullscreenStack);
        final ActivityRecord firstActivity = createActivity(mService, testActivityComponent,
                firstTask);
        // Create a new task on the full screen stack
        final TaskRecord secondTask = createTask(
                mSupervisor, testActivityComponent, mFullscreenStack);
        final ActivityRecord secondActivity = createActivity(mService, testActivityComponent,
                secondTask);
        mSupervisor.setFocusStackUnchecked("testReplacingTaskInPinnedStack", mFullscreenStack);

        // Ensure full screen stack has both tasks.
        ensureStackPlacement(mFullscreenStack, firstTask, secondTask);

        // Move first activity to pinned stack.
        final Rect sourceBounds = new Rect();
        mSupervisor.moveActivityToPinnedStackLocked(firstActivity, sourceBounds,
                0f /*aspectRatio*/, false, "initialMove");

        final ActivityDisplay display = mFullscreenStack.getDisplay();
        ActivityStack pinnedStack = display.getPinnedStack();
        // Ensure a task has moved over.
        ensureStackPlacement(pinnedStack, firstTask);
        ensureStackPlacement(mFullscreenStack, secondTask);

        // Move second activity to pinned stack.
        mSupervisor.moveActivityToPinnedStackLocked(secondActivity, sourceBounds,
                0f /*aspectRatio*/, false, "secondMove");

        // Need to get pinned stack again as a new instance might have been created.
        pinnedStack = display.getPinnedStack();

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
        final TaskRecord firstTask = createTask(
                mSupervisor, testActivityComponent, mFullscreenStack);
        final ActivityRecord firstActivity = createActivity(mService, testActivityComponent,
            firstTask);
        mSupervisor.mStoppingActivities.add(firstActivity);

        firstActivity.completeResumeLocked();

        assertFalse(mSupervisor.mStoppingActivities.contains(firstActivity));
    }
}
