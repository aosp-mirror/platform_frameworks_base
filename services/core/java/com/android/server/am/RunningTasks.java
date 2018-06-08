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

import static com.android.server.am.ActivityManagerDebugConfig.TAG_AM;
import static com.android.server.am.ActivityManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration.ActivityType;
import android.app.WindowConfiguration.WindowingMode;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.TreeSet;

/**
 * Class for resolving the set of running tasks in the system.
 */
class RunningTasks {

    // Comparator to sort by last active time (descending)
    private static final Comparator<TaskRecord> LAST_ACTIVE_TIME_COMPARATOR =
            (o1, o2) -> Long.signum(o2.lastActiveTime - o1.lastActiveTime);

    private final TaskRecord.TaskActivitiesReport mTmpReport =
            new TaskRecord.TaskActivitiesReport();
    private final TreeSet<TaskRecord> mTmpSortedSet = new TreeSet<>(LAST_ACTIVE_TIME_COMPARATOR);
    private final ArrayList<TaskRecord> mTmpStackTasks = new ArrayList<>();

    void getTasks(int maxNum, List<RunningTaskInfo> list, @ActivityType int ignoreActivityType,
            @WindowingMode int ignoreWindowingMode, SparseArray<ActivityDisplay> activityDisplays,
            int callingUid, boolean allowed) {
        // Return early if there are no tasks to fetch
        if (maxNum <= 0) {
            return;
        }

        // Gather all of the tasks across all of the tasks, and add them to the sorted set
        mTmpSortedSet.clear();
        mTmpStackTasks.clear();
        final int numDisplays = activityDisplays.size();
        for (int displayNdx = 0; displayNdx < numDisplays; ++displayNdx) {
            final ActivityDisplay display = activityDisplays.valueAt(displayNdx);
            for (int stackNdx = display.getChildCount() - 1; stackNdx >= 0; --stackNdx) {
                final ActivityStack stack = display.getChildAt(stackNdx);
                stack.getRunningTasks(mTmpStackTasks, ignoreActivityType, ignoreWindowingMode,
                        callingUid, allowed);
                for (int i = mTmpStackTasks.size() - 1; i >= 0; i--) {
                    mTmpSortedSet.addAll(mTmpStackTasks);
                }
            }
        }

        // Take the first {@param maxNum} tasks and create running task infos for them
        final Iterator<TaskRecord> iter = mTmpSortedSet.iterator();
        while (iter.hasNext()) {
            if (maxNum == 0) {
                break;
            }

            final TaskRecord task = iter.next();
            list.add(createRunningTaskInfo(task));
            maxNum--;
        }
    }

    /**
     * Constructs a {@link RunningTaskInfo} from a given {@param task}.
     */
    private RunningTaskInfo createRunningTaskInfo(TaskRecord task) {
        task.getNumRunningActivities(mTmpReport);

        final RunningTaskInfo ci = new RunningTaskInfo();
        ci.id = task.taskId;
        ci.stackId = task.getStackId();
        ci.baseActivity = mTmpReport.base.intent.getComponent();
        ci.topActivity = mTmpReport.top.intent.getComponent();
        ci.lastActiveTime = task.lastActiveTime;
        ci.description = task.lastDescription;
        ci.numActivities = mTmpReport.numActivities;
        ci.numRunning = mTmpReport.numRunning;
        ci.supportsSplitScreenMultiWindow = task.supportsSplitScreenWindowingMode();
        ci.resizeMode = task.mResizeMode;
        ci.configuration.setTo(task.getConfiguration());
        return ci;
    }
}
