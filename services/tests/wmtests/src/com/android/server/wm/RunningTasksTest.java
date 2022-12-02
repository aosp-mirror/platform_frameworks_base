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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.server.wm.RunningTasks.FLAG_ALLOWED;
import static com.android.server.wm.RunningTasks.FLAG_CROSS_USERS;
import static com.android.server.wm.RunningTasks.FLAG_KEEP_INTENT_EXTRA;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager.RunningTaskInfo;
import android.content.ComponentName;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;

import androidx.test.filters.MediumTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * Build/Install/Run:
 *  atest WmTests:RunningTasksTest
 */
@MediumTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class RunningTasksTest extends WindowTestsBase {

    private static final ArraySet<Integer> PROFILE_IDS = new ArraySet<>();

    private RunningTasks mRunningTasks;

    @Before
    public void setUp() throws Exception {
        mRunningTasks = new RunningTasks();
    }

    @Test
    public void testTaskInfo_expectNoExtrasByDefault() {
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1000, 2500).build();
        final int numTasks = 10;
        for (int i = 0; i < numTasks; i++) {
            final Task stack = new TaskBuilder(mSupervisor)
                    .setDisplay(display)
                    .setOnTop(true)
                    .build();
            final Bundle data = new Bundle();
            data.putInt("key", 100);
            createTask(stack, ".Task" + i, i, data);
        }

        final int numFetchTasks = 5;
        final ArrayList<RunningTaskInfo> tasks = new ArrayList<>();
        mRunningTasks.getTasks(numFetchTasks, tasks, FLAG_ALLOWED | FLAG_CROSS_USERS,
                mRootWindowContainer, -1 /* callingUid */, PROFILE_IDS);
        assertThat(tasks).hasSize(numFetchTasks);
        for (int i = 0; i < tasks.size(); i++) {
            final Bundle extras = tasks.get(i).baseIntent.getExtras();
            assertTrue(extras == null || extras.isEmpty());
        }
    }

    @Test
    public void testTaskInfo_expectExtrasWithKeepExtraFlag() {
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1000, 2500).build();
        final int numTasks = 10;
        for (int i = 0; i < numTasks; i++) {
            final Task stack = new TaskBuilder(mSupervisor)
                    .setDisplay(display)
                    .setOnTop(true)
                    .build();
            final Bundle data = new Bundle();
            data.putInt("key", 100);
            createTask(stack, ".Task" + i, i, data);
        }

        final int numFetchTasks = 5;
        final ArrayList<RunningTaskInfo> tasks = new ArrayList<>();
        mRunningTasks.getTasks(numFetchTasks, tasks,
                FLAG_ALLOWED | FLAG_CROSS_USERS | FLAG_KEEP_INTENT_EXTRA, mRootWindowContainer,
                -1 /* callingUid */, PROFILE_IDS);
        assertThat(tasks).hasSize(numFetchTasks);
        for (int i = 0; i < tasks.size(); i++) {
            final Bundle extras = tasks.get(i).baseIntent.getExtras();
            assertNotNull(extras);
            assertEquals(100, extras.getInt("key"));
        }
    }

    @Test
    public void testGetTasksSortByFocusAndVisibility() {
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1000, 2500).build();
        final Task stack = new TaskBuilder(mSupervisor)
                .setDisplay(display)
                .setOnTop(true)
                .build();

        final int numTasks = 10;
        final ArrayList<Task> tasks = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            final Task task = createTask(stack, ".Task" + i, i, null);
            doReturn(false).when(task).isVisible();
            tasks.add(task);
        }

        final Task focusedTask = tasks.get(numTasks - 1);
        doReturn(true).when(focusedTask).isVisible();
        display.mFocusedApp = focusedTask.getTopNonFinishingActivity();

        final Task visibleTaskTop = tasks.get(numTasks - 2);
        doReturn(true).when(visibleTaskTop).isVisible();

        final Task visibleTaskBottom = tasks.get(numTasks - 3);
        doReturn(true).when(visibleTaskBottom).isVisible();

        // Ensure that the focused Task is on top, visible tasks below, then invisible tasks.
        final int numFetchTasks = 5;
        final ArrayList<RunningTaskInfo> fetchTasks = new ArrayList<>();
        mRunningTasks.getTasks(numFetchTasks, fetchTasks,
                FLAG_ALLOWED | FLAG_CROSS_USERS | FLAG_KEEP_INTENT_EXTRA, mRootWindowContainer,
                -1 /* callingUid */, PROFILE_IDS);
        assertThat(fetchTasks).hasSize(numFetchTasks);
        for (int i = 0; i < numFetchTasks; i++) {
            assertEquals(numTasks - i - 1, fetchTasks.get(i).id);
        }

        // Ensure that requesting more than the total number of tasks only returns the subset
        // and does not crash
        fetchTasks.clear();
        mRunningTasks.getTasks(100, fetchTasks,
                FLAG_ALLOWED | FLAG_CROSS_USERS | FLAG_KEEP_INTENT_EXTRA, mRootWindowContainer,
                -1 /* callingUid */, PROFILE_IDS);
        assertThat(fetchTasks).hasSize(numTasks);
        for (int i = 0; i < numTasks; i++) {
            assertEquals(numTasks - i - 1, fetchTasks.get(i).id);
        }
    }

    /**
     * Create a task with a single activity in it.
     */
    private Task createTask(Task stack, String className, int taskId, Bundle extras) {
        final Task task = new TaskBuilder(mAtm.mTaskSupervisor)
                .setComponent(new ComponentName(mContext.getPackageName(), className))
                .setTaskId(taskId)
                .setParentTaskFragment(stack)
                .build();
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(task)
                .setComponent(new ComponentName(mContext.getPackageName(), ".TaskActivity"))
                .setIntentExtras(extras)
                .build();
        task.intent = activity.intent;
        return task;
    }
}
