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

import static android.app.ActivityManager.StackId.FULLSCREEN_WORKSPACE_STACK_ID;
import static android.app.ActivityManager.StackId.PINNED_STACK_ID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;

import org.junit.runner.RunWith;
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

    /**
     * This test ensures that we do not try to restore a task based off an invalid task id. The
     * stack supervisor is a test version so there will be no tasks present. We should expect
     * {@code null} to be returned in this case.
     */
    @Test
    public void testRestoringInvalidTask() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        TaskRecord task = service.mStackSupervisor.anyTaskForIdLocked(0 /*taskId*/,
                MATCH_TASK_IN_STACKS_OR_RECENT_TASKS_AND_RESTORE, 0 /*stackId*/);
        assertNull(task);
    }

    /**
     * This test ensures that an existing task in the pinned stack is moved to the fullscreen
     * activity stack when a new task is added.
     */
    @Test
    public void testReplacingTaskInPinnedStack() throws Exception {
        final ActivityManagerService service = createActivityManagerService();
        final TaskRecord firstTask = createTask(service, testActivityComponent,
                FULLSCREEN_WORKSPACE_STACK_ID);
        final ActivityRecord firstActivity = createActivity(service, testActivityComponent,
                firstTask);
        // Create a new task on the full screen stack
        final TaskRecord secondTask = createTask(service, testActivityComponent,
                FULLSCREEN_WORKSPACE_STACK_ID);
        final ActivityRecord secondActivity = createActivity(service, testActivityComponent,
                secondTask);
        service.mStackSupervisor.setFocusStackUnchecked("testReplacingTaskInPinnedStack",
                service.mStackSupervisor.getStack(FULLSCREEN_WORKSPACE_STACK_ID));

        // Ensure full screen stack has both tasks.
        ensureStackPlacement(service.mStackSupervisor, FULLSCREEN_WORKSPACE_STACK_ID, firstTask,
                secondTask);

        // Move first activity to pinned stack.
        service.mStackSupervisor.moveActivityToPinnedStackLocked(firstActivity,
                new Rect() /*sourceBounds*/, 0f /*aspectRatio*/, false, "initialMove");

        // Ensure a task has moved over.
        ensureStackPlacement(service.mStackSupervisor, PINNED_STACK_ID, firstTask);
        ensureStackPlacement(service.mStackSupervisor, FULLSCREEN_WORKSPACE_STACK_ID, secondTask);

        // Move second activity to pinned stack.
        service.mStackSupervisor.moveActivityToPinnedStackLocked(secondActivity,
                new Rect() /*sourceBounds*/, 0f /*aspectRatio*/ /*destBounds*/, false, "secondMove");

        // Ensure stacks have swapped tasks.
        ensureStackPlacement(service.mStackSupervisor, PINNED_STACK_ID, secondTask);
        ensureStackPlacement(service.mStackSupervisor, FULLSCREEN_WORKSPACE_STACK_ID, firstTask);
    }

    private static void ensureStackPlacement(ActivityStackSupervisor supervisor, int stackId,
            TaskRecord... tasks) {
        final ActivityStack stack = supervisor.getStack(stackId);
        final ArrayList<TaskRecord> stackTasks = stack.getAllTasks();
        assertEquals(stackTasks.size(), tasks != null ? tasks.length : 0);

        if (tasks == null) {
            return;
        }

        for (TaskRecord task : tasks) {
            assertTrue(stackTasks.contains(task));
        }
    }
}
