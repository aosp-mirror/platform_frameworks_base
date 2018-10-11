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
 * limitations under the License.
 */

package com.android.server.am;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.view.Display.DEFAULT_DISPLAY;

import static com.android.server.am.ActivityDisplay.POSITION_BOTTOM;

import static org.junit.Assert.assertTrue;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.MediumTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.SparseArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;

/**
 * runtest --path frameworks/base/services/tests/servicestests/src/com/android/server/am/RunningTasksTest.java
 */
@MediumTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class RunningTasksTest extends ActivityTestsBase {

    private Context mContext = InstrumentationRegistry.getContext();
    private ActivityManagerService mService;

    private RunningTasks mRunningTasks;

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        mService = createActivityManagerService();
        mRunningTasks = new RunningTasks();
    }

    @Test
    public void testCollectTasksByLastActiveTime() throws Exception {
        // Create a number of stacks with tasks (of incrementing active time)
        final ActivityStackSupervisor supervisor = mService.mStackSupervisor;
        final SparseArray<ActivityDisplay> displays = new SparseArray<>();
        final ActivityDisplay display = new TestActivityDisplay(supervisor, DEFAULT_DISPLAY);
        displays.put(DEFAULT_DISPLAY, display);

        final int numStacks = 2;
        for (int stackIndex = 0; stackIndex < numStacks; stackIndex++) {
            final ActivityStack stack = new TestActivityStack(display, stackIndex, supervisor,
                    WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD, true);
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
                displays, -1 /* callingUid */, true /* allowed */);
        assertTrue(tasks.size() == numFetchTasks);
        for (int i = 0; i < numFetchTasks; i++) {
            assertTrue(tasks.get(i).id == (numTasks - i - 1));
        }

        // Ensure that requesting more than the total number of tasks only returns the subset
        // and does not crash
        tasks.clear();
        mRunningTasks.getTasks(100, tasks, ACTIVITY_TYPE_UNDEFINED, WINDOWING_MODE_UNDEFINED,
                displays, -1 /* callingUid */, true /* allowed */);
        assertTrue(tasks.size() == numTasks);
        for (int i = 0; i < numTasks; i++) {
            assertTrue(tasks.get(i).id == (numTasks - i - 1));
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