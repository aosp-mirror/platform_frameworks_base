/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.systemui.recents;

import android.app.ActivityManager;
import android.support.test.runner.AndroidJUnit4;
import android.content.pm.ActivityInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.recents.misc.SystemServicesProxy;
import com.android.systemui.recents.model.RecentsTaskLoadPlan;
import com.android.systemui.recents.model.RecentsTaskLoader;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;

import java.util.ArrayList;

import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;


/**
 * Mock task loader that does not actually load any tasks.
 */
class MockRecentsTaskNonLoader extends RecentsTaskLoader {
    @Override
    public String getAndUpdateActivityTitle(Task.TaskKey taskKey, ActivityManager.TaskDescription td) {
        return "";
    }

    @Override
    public String getAndUpdateContentDescription(Task.TaskKey taskKey, Resources res) {
        return "";
    }

    @Override
    public Drawable getAndUpdateActivityIcon(Task.TaskKey taskKey, ActivityManager.TaskDescription td, Resources res, boolean loadIfNotCached) {
        return null;
    }

    @Override
    public Bitmap getAndUpdateThumbnail(Task.TaskKey taskKey, boolean loadIfNotCached) {
        return null;
    }

    @Override
    public int getActivityPrimaryColor(ActivityManager.TaskDescription td) {
        return 0;
    }

    @Override
    public int getActivityBackgroundColor(ActivityManager.TaskDescription td) {
        return 0;
    }

    @Override
    public ActivityInfo getAndUpdateActivityInfo(Task.TaskKey taskKey) {
        return null;
    }
}

/**
 * TODO(winsonc):
 * - add test to ensure excluded tasks are loaded at the front of the list
 * - add test to ensure the last visible task active time is migrated from absolute to uptime
 */
@RunWith(AndroidJUnit4.class)
public class RecentsTaskLoadPlanTest extends SysuiTestCase {
    private static final String TAG = "RecentsTaskLoadPlanTest";

    private MockRecentsTaskNonLoader mDummyLoader = new MockRecentsTaskNonLoader();
    private SystemServicesProxy mDummySsp = new SystemServicesProxy();

    @Test
    public void testEmptyRecents() {
        RecentsTaskLoadPlan loadPlan = new RecentsTaskLoadPlan(mContext, mDummySsp);
        ArrayList<ActivityManager.RecentTaskInfo> tasks = new ArrayList<>();
        loadPlan.setInternals(tasks, 0 /* current */, 0 /* lastVisibleTaskActive */);
        loadPlan.preloadPlan(mDummyLoader, 0 /* runningTaskId */,
                false /* includeFrontMostExcludedTask */);
        assertFalse("Expected task to be empty", loadPlan.getTaskStack().getStackTaskCount() > 0);
    }

    @Test
    public void testLessThanEqualMinTasks() {
        RecentsTaskLoadPlan loadPlan = new RecentsTaskLoadPlan(mContext, mDummySsp);
        ArrayList<ActivityManager.RecentTaskInfo> tasks = new ArrayList<>();
        int minTasks = 3;

        resetTaskInfoList(tasks,
                createTaskInfo(0, 1),
                createTaskInfo(1, 2),
                createTaskInfo(2, 3));

        // Ensure that all tasks are loaded if the tasks are within the session and after the last
        // visible active time (all tasks are loaded because there are < minTasks number of tasks)
        loadPlan.setInternals(tasks, minTasks, 0 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2);

        loadPlan.setInternals(tasks, minTasks, 1 /* current */, 0 /* lastVisibleTaskActive */,
                0 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2);

        loadPlan.setInternals(tasks, minTasks, 1 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2);

        loadPlan.setInternals(tasks, minTasks, 3 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2);

        loadPlan.setInternals(tasks, minTasks, 3 /* current */, 1 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2);

        loadPlan.setInternals(tasks, minTasks, 50 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2);

        // Ensure that only tasks are not loaded if are after the last visible active time, even if
        // they are within the session
        loadPlan.setInternals(tasks, minTasks, 50 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2);

        loadPlan.setInternals(tasks, minTasks, 50 /* current */, 1 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2);

        loadPlan.setInternals(tasks, minTasks, 50 /* current */, 2 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0);
        assertTasksInStack(loadPlan.getTaskStack(), 1, 2);

        loadPlan.setInternals(tasks, minTasks, 50 /* current */, 3 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1);
        assertTasksInStack(loadPlan.getTaskStack(), 2);

        loadPlan.setInternals(tasks, minTasks, 50 /* current */, 50 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1, 2);
    }

    @Test
    public void testMoreThanMinTasks() {
        RecentsTaskLoadPlan loadPlan = new RecentsTaskLoadPlan(mContext, mDummySsp);
        ArrayList<ActivityManager.RecentTaskInfo> tasks = new ArrayList<>();
        int minTasks = 3;

        // Create all tasks within the session
        resetTaskInfoList(tasks,
                createTaskInfo(0, 1),
                createTaskInfo(1, 50),
                createTaskInfo(2, 100),
                createTaskInfo(3, 101),
                createTaskInfo(4, 102),
                createTaskInfo(5, 103));

        // Ensure that only the tasks that are within the window but after the last visible active
        // time is loaded, or the minTasks number of tasks are loaded if there are less than that

        // Session window shifts
        loadPlan.setInternals(tasks, minTasks, 0 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 1 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 51 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 52 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0);
        assertTasksInStack(loadPlan.getTaskStack(), 1, 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 100 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0);
        assertTasksInStack(loadPlan.getTaskStack(), 1, 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 101 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1);
        assertTasksInStack(loadPlan.getTaskStack(), 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 103 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1);
        assertTasksInStack(loadPlan.getTaskStack(), 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1);
        assertTasksInStack(loadPlan.getTaskStack(), 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 151 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1, 2);
        assertTasksInStack(loadPlan.getTaskStack(), 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 200 /* current */, 0 /* lastVisibleTaskActive */,
                50 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1, 2);
        assertTasksInStack(loadPlan.getTaskStack(), 3, 4, 5);

        // Last visible active time shifts (everything is in window)
        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 0 /* lastVisibleTaskActive */,
                150 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 1 /* lastVisibleTaskActive */,
                150 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksInStack(loadPlan.getTaskStack(), 0, 1, 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 2 /* lastVisibleTaskActive */,
                150 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0);
        assertTasksInStack(loadPlan.getTaskStack(), 1, 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 50 /* lastVisibleTaskActive */,
                150 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0);
        assertTasksInStack(loadPlan.getTaskStack(), 1, 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 51 /* lastVisibleTaskActive */,
                150 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1);
        assertTasksInStack(loadPlan.getTaskStack(), 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 100 /* lastVisibleTaskActive */,
                150 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1);
        assertTasksInStack(loadPlan.getTaskStack(), 2, 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 101 /* lastVisibleTaskActive */,
                150 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1, 2);
        assertTasksInStack(loadPlan.getTaskStack(), 3, 4, 5);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 102 /* lastVisibleTaskActive */,
                150 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1, 2, 3);
        assertTasksInStack(loadPlan.getTaskStack(), 4, 5);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 103 /* lastVisibleTaskActive */,
                150 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1, 2, 3, 4);
        assertTasksInStack(loadPlan.getTaskStack(), 5);

        loadPlan.setInternals(tasks, minTasks, 150 /* current */, 104 /* lastVisibleTaskActive */,
                150 /* sessionBegin */);
        loadPlan.preloadPlan(mDummyLoader, 0, false);
        assertTasksNotInStack(loadPlan.getTaskStack(), 0, 1, 2, 3, 4, 5);
    }

    private ActivityManager.RecentTaskInfo createTaskInfo(int taskId, long lastActiveTime) {
        ActivityManager.RecentTaskInfo info = new ActivityManager.RecentTaskInfo();
        info.id = info.persistentId = taskId;
        info.lastActiveTime = lastActiveTime;
        return info;
    }

    private void resetTaskInfoList(ArrayList<ActivityManager.RecentTaskInfo> tasks,
            ActivityManager.RecentTaskInfo ... infos) {
        tasks.clear();
        for (ActivityManager.RecentTaskInfo info : infos) {
            tasks.add(info);
        }
    }

    private void assertTasksInStack(TaskStack stack, int... taskIds) {
        for (int taskId : taskIds) {
            assertNotNull("Expected task " + taskId + " in stack", stack.findTaskWithId(taskId));
        }
    }

    private void assertTasksNotInStack(TaskStack stack, int... taskIds) {
        for (int taskId : taskIds) {
            assertNull("Expected task " + taskId + " not in stack", stack.findTaskWithId(taskId));
        }
    }
}
