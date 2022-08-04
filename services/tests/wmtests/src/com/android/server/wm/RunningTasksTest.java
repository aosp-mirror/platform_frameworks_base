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

import com.google.common.truth.Correspondence;

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
    private static final Correspondence<RunningTaskInfo, Integer> TASKINFO_HAS_ID =
            Correspondence.transforming((RunningTaskInfo t) -> t.taskId, "has id");


    private RunningTasks mRunningTasks;

    @Before
    public void setUp() throws Exception {
        mRunningTasks = new RunningTasks();
    }

    @Test
    public void testCollectTasksByLastActiveTime() {
        // Create a number of stacks with tasks (of incrementing active time)
        final ArrayList<DisplayContent> displays = new ArrayList<>();
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1000, 2500).build();
        displays.add(display);

        final int numStacks = 2;
        for (int stackIndex = 0; stackIndex < numStacks; stackIndex++) {
            final Task stack = new TaskBuilder(mSupervisor)
                    .setDisplay(display)
                    .setOnTop(false)
                    .build();
        }

        final int numTasks = 10;
        int activeTime = 0;
        final List<Task> rootTasks = new ArrayList<>();
        display.getDefaultTaskDisplayArea().forAllRootTasks(task -> {
            rootTasks.add(task);
        }, false /* traverseTopToBottom */);
        for (int i = 0; i < numTasks; i++) {
            final Task task =
                    createTask(rootTasks.get(i % numStacks), ".Task" + i, i, activeTime++, null);
            doReturn(false).when(task).isVisible();
        }

        // Ensure that the latest tasks were returned in order of decreasing last active time,
        // collected from all tasks across all the stacks
        final int numFetchTasks = 5;
        ArrayList<RunningTaskInfo> tasks = new ArrayList<>();
        mRunningTasks.getTasks(5, tasks, FLAG_ALLOWED | FLAG_CROSS_USERS,
                mAtm.getRecentTasks(), mRootWindowContainer, -1 /* callingUid */, PROFILE_IDS);
        assertThat(tasks).hasSize(numFetchTasks);
        for (int i = 0; i < numFetchTasks; i++) {
            assertEquals(numTasks - i - 1, tasks.get(i).id);
        }

        // Ensure that requesting more than the total number of tasks only returns the subset
        // and does not crash
        tasks.clear();
        mRunningTasks.getTasks(100, tasks, FLAG_ALLOWED | FLAG_CROSS_USERS,
                mAtm.getRecentTasks(), mRootWindowContainer, -1 /* callingUid */, PROFILE_IDS);
        assertThat(tasks).hasSize(numTasks);
        for (int i = 0; i < numTasks; i++) {
            assertEquals(numTasks - i - 1, tasks.get(i).id);
        }
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
            createTask(stack, ".Task" + i, i, i, data);
        }

        final int numFetchTasks = 5;
        final ArrayList<RunningTaskInfo> tasks = new ArrayList<>();
        mRunningTasks.getTasks(numFetchTasks, tasks, FLAG_ALLOWED | FLAG_CROSS_USERS,
                mAtm.getRecentTasks(), mRootWindowContainer, -1 /* callingUid */, PROFILE_IDS);
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
            createTask(stack, ".Task" + i, i, i, data);
        }

        final int numFetchTasks = 5;
        final ArrayList<RunningTaskInfo> tasks = new ArrayList<>();
        mRunningTasks.getTasks(numFetchTasks, tasks,
                FLAG_ALLOWED | FLAG_CROSS_USERS | FLAG_KEEP_INTENT_EXTRA,
                mAtm.getRecentTasks(), mRootWindowContainer, -1 /* callingUid */, PROFILE_IDS);
        assertThat(tasks).hasSize(numFetchTasks);
        for (int i = 0; i < tasks.size(); i++) {
            final Bundle extras = tasks.get(i).baseIntent.getExtras();
            assertNotNull(extras);
            assertEquals(100, extras.getInt("key"));
        }
    }

    @Test
    public void testUpdateLastActiveTimeOfVisibleTasks() {
        final DisplayContent display = new TestDisplayContent.Builder(mAtm, 1000, 2500).build();
        final int numTasks = 10;
        final ArrayList<Task> tasks = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            final Task task = createTask(null, ".Task" + i, i, i, null);
            doReturn(false).when(task).isVisible();
            tasks.add(task);
        }

        final Task visibleTask = tasks.get(0);
        doReturn(true).when(visibleTask).isVisible();

        final Task focusedTask = tasks.get(1);
        doReturn(true).when(focusedTask).isVisible();
        doReturn(true).when(focusedTask).isFocused();

        // Ensure that the last active time of visible tasks were updated while the focused one had
        // the largest last active time.
        final int numFetchTasks = 5;
        final ArrayList<RunningTaskInfo> fetchTasks = new ArrayList<>();
        mRunningTasks.getTasks(numFetchTasks, fetchTasks,
                FLAG_ALLOWED | FLAG_CROSS_USERS | FLAG_KEEP_INTENT_EXTRA,
                mAtm.getRecentTasks(), mRootWindowContainer, -1 /* callingUid */, PROFILE_IDS);
        assertThat(fetchTasks).hasSize(numFetchTasks);
        assertEquals(fetchTasks.get(0).id, focusedTask.mTaskId);
        assertEquals(fetchTasks.get(1).id, visibleTask.mTaskId);
    }

    /**
     * Create a task with a single activity in it, with the given last active time.
     */
    private Task createTask(Task stack, String className, int taskId,
            int lastActiveTime, Bundle extras) {
        final Task task = new TaskBuilder(mAtm.mTaskSupervisor)
                .setComponent(new ComponentName(mContext.getPackageName(), className))
                .setTaskId(taskId)
                .setParentTaskFragment(stack)
                .build();
        task.lastActiveTime = lastActiveTime;
        final ActivityRecord activity = new ActivityBuilder(mAtm)
                .setTask(task)
                .setComponent(new ComponentName(mContext.getPackageName(), ".TaskActivity"))
                .setIntentExtras(extras)
                .build();
        task.intent = activity.intent;
        return task;
    }

    @Test
    public void testMultipleDisplays() {
        final DisplayContent display0 = new TestDisplayContent.Builder(mAtm, 1000, 2500).build();
        final DisplayContent display1 = new TestDisplayContent.Builder(mAtm, 1000, 2500).build();
        final int numTasks = 10;
        final ArrayList<Task> tasks = new ArrayList<>();
        for (int i = 0; i < numTasks; i++) {
            final Task stack = new TaskBuilder(mSupervisor)
                    .setDisplay(i % 2 == 0 ? display0 : display1)
                    .setOnTop(true)
                    .build();
            final Task task = createTask(stack, ".Task" + i, i, i, null);
            tasks.add(task);
        }

        final int numFetchTasks = numTasks;
        final ArrayList<RunningTaskInfo> fetchTasks = new ArrayList<>();

        mRunningTasks.getTasks(numFetchTasks, fetchTasks,
                FLAG_ALLOWED | FLAG_CROSS_USERS,
                mAtm.getRecentTasks(), display0, -1 /* callingUid */, PROFILE_IDS);
        assertThat(fetchTasks).hasSize(numTasks / 2);
        assertThat(fetchTasks).comparingElementsUsing(TASKINFO_HAS_ID)
                .containsExactly(0, 2, 4, 6, 8);

        fetchTasks.clear();
        mRunningTasks.getTasks(numFetchTasks, fetchTasks,
                FLAG_ALLOWED | FLAG_CROSS_USERS,
                mAtm.getRecentTasks(), display1, -1 /* callingUid */, PROFILE_IDS);
        assertThat(fetchTasks).hasSize(numTasks / 2);
        assertThat(fetchTasks).comparingElementsUsing(TASKINFO_HAS_ID)
                .containsExactly(1, 3, 5, 7, 9);

        fetchTasks.clear();
        mRunningTasks.getTasks(numFetchTasks, fetchTasks,
                FLAG_ALLOWED | FLAG_CROSS_USERS,
                mAtm.getRecentTasks(), mRootWindowContainer, -1 /* callingUid */, PROFILE_IDS);
        assertThat(fetchTasks).hasSize(numTasks);
        assertThat(fetchTasks).comparingElementsUsing(TASKINFO_HAS_ID)
                .containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }
}
