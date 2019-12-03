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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.wm.ActivityDisplay.POSITION_BOTTOM;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.ComponentName;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;

/**
 * Build/Install/Run:
 *  atest WmTests:RunningTasksTest
 */
@MediumTest
@Presubmit
public class RunningTasksTest extends ActivityTestsBase {

    private static final ArraySet<Integer> PROFILE_IDS = new ArraySet<>();

    private RunningTasks mRunningTasks;

    @Before
    public void setUp() throws Exception {
        mRunningTasks = new RunningTasks();
    }

    @Test
    public void testCollectTasksByLastActiveTime() {
        // Create a number of stacks with tasks (of incrementing active time)
        final ArrayList<ActivityDisplay> displays = new ArrayList<>();
        final ActivityDisplay display = TestActivityDisplay.create(mSupervisor, DEFAULT_DISPLAY);
        displays.add(display);

        final int numStacks = 2;
        for (int stackIndex = 0; stackIndex < numStacks; stackIndex++) {
            final ActivityStack stack =
                    new StackBuilder(mRootActivityContainer).setCreateActivity(false).build();
            display.addChild(stack, POSITION_BOTTOM);
        }

        final int numTasks = 10;
        int activeTime = 0;
        for (int i = 0; i < numTasks; i++) {
            createTask(display.getChildAt(i % numStacks), ".Task" + i, i, activeTime++);
        }

        // Ensure that the latest tasks were returned in order of decreasing last active time,
        // collected from all tasks across all the stacks
        final int numFetchTasks = 5;
        ArrayList<RunningTaskInfo> tasks = new ArrayList<>();
        mRunningTasks.getTasks(5, tasks, ACTIVITY_TYPE_UNDEFINED, WINDOWING_MODE_UNDEFINED,
                displays, -1 /* callingUid */, true /* allowed */, true /*crossUser */,
                PROFILE_IDS);
        assertThat(tasks).hasSize(numFetchTasks);
        for (int i = 0; i < numFetchTasks; i++) {
            assertEquals(numTasks - i - 1, tasks.get(i).id);
        }

        // Ensure that requesting more than the total number of tasks only returns the subset
        // and does not crash
        tasks.clear();
        mRunningTasks.getTasks(100, tasks, ACTIVITY_TYPE_UNDEFINED, WINDOWING_MODE_UNDEFINED,
                displays, -1 /* callingUid */, true /* allowed */, true /* crossUser */,
                PROFILE_IDS);
        assertThat(tasks).hasSize(numTasks);
        for (int i = 0; i < numTasks; i++) {
            assertEquals(numTasks - i - 1, tasks.get(i).id);
        }
    }

    /**
     * Create a task with a single activity in it, with the given last active time.
     */
    private TaskRecord createTask(ActivityStack stack, String className, int taskId,
            int lastActiveTime) {
        final TaskRecord task = new TaskBuilder(mService.mStackSupervisor)
                .setComponent(new ComponentName(mContext.getPackageName(), className))
                .setTaskId(taskId)
                .setStack(stack)
                .build();
        task.lastActiveTime = lastActiveTime;
        final ActivityRecord activity = new ActivityBuilder(mService)
                .setTask(task)
                .setComponent(new ComponentName(mContext.getPackageName(), ".TaskActivity"))
                .build();
        return task;
    }
}
