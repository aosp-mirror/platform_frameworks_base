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
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import android.app.ActivityManager;
import android.app.WaitResult;
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
}
