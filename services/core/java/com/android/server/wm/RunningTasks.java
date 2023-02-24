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

package com.android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;

import android.app.ActivityManager.RunningTaskInfo;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArraySet;

import com.android.internal.util.function.pooled.PooledConsumer;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.ArrayList;
import java.util.List;

/**
 * Class for resolving the set of running tasks in the system.
 */
class RunningTasks {

    static final int FLAG_FILTER_ONLY_VISIBLE_RECENTS = 1;
    static final int FLAG_ALLOWED = 1 << 1;
    static final int FLAG_CROSS_USERS = 1 << 2;
    static final int FLAG_KEEP_INTENT_EXTRA = 1 << 3;

    // Tasks are sorted in order {focusedVisibleTasks, visibleTasks, invisibleTasks}.
    private final ArrayList<Task> mTmpSortedTasks = new ArrayList<>();
    // mTmpVisibleTasks, mTmpInvisibleTasks and mTmpFocusedTasks are sorted from top
    // to bottom.
    private final ArrayList<Task> mTmpVisibleTasks = new ArrayList<>();
    private final ArrayList<Task> mTmpInvisibleTasks = new ArrayList<>();
    private final ArrayList<Task> mTmpFocusedTasks = new ArrayList<>();

    private int mCallingUid;
    private int mUserId;
    private boolean mCrossUser;
    private ArraySet<Integer> mProfileIds;
    private boolean mAllowed;
    private boolean mFilterOnlyVisibleRecents;
    private RecentTasks mRecentTasks;
    private boolean mKeepIntentExtra;

    void getTasks(int maxNum, List<RunningTaskInfo> list, int flags, RecentTasks recentTasks,
            WindowContainer root, int callingUid, ArraySet<Integer> profileIds) {
        // Return early if there are no tasks to fetch
        if (maxNum <= 0) {
            return;
        }

        mCallingUid = callingUid;
        mUserId = UserHandle.getUserId(callingUid);
        mCrossUser = (flags & FLAG_CROSS_USERS) == FLAG_CROSS_USERS;
        mProfileIds = profileIds;
        mAllowed = (flags & FLAG_ALLOWED) == FLAG_ALLOWED;
        mFilterOnlyVisibleRecents =
                (flags & FLAG_FILTER_ONLY_VISIBLE_RECENTS) == FLAG_FILTER_ONLY_VISIBLE_RECENTS;
        mRecentTasks = recentTasks;
        mKeepIntentExtra = (flags & FLAG_KEEP_INTENT_EXTRA) == FLAG_KEEP_INTENT_EXTRA;

        if (root instanceof RootWindowContainer) {
            ((RootWindowContainer) root).forAllDisplays(dc -> {
                final Task focusedTask = dc.mFocusedApp != null ? dc.mFocusedApp.getTask() : null;
                if (focusedTask != null) {
                    mTmpFocusedTasks.add(focusedTask);
                }
                processTaskInWindowContainer(dc);
            });
        } else {
            final DisplayContent dc = root.getDisplayContent();
            final Task focusedTask = dc != null
                    ? (dc.mFocusedApp != null ? dc.mFocusedApp.getTask() : null)
                    : null;
            // May not be include focusedTask if root is DisplayArea.
            final boolean rootContainsFocusedTask = focusedTask != null
                    && focusedTask.isDescendantOf(root);
            if (rootContainsFocusedTask) {
                mTmpFocusedTasks.add(focusedTask);
            }
            processTaskInWindowContainer(root);
        }

        final int visibleTaskCount = mTmpVisibleTasks.size();
        for (int i = 0; i < mTmpFocusedTasks.size(); i++) {
            final Task focusedTask = mTmpFocusedTasks.get(i);
            final boolean containsFocusedTask = mTmpVisibleTasks.remove(focusedTask);
            if (containsFocusedTask) {
                // Put the visible focused task at the first position.
                mTmpSortedTasks.add(focusedTask);
            }
        }
        if (!mTmpVisibleTasks.isEmpty()) {
            mTmpSortedTasks.addAll(mTmpVisibleTasks);
        }
        if (!mTmpInvisibleTasks.isEmpty()) {
            mTmpSortedTasks.addAll(mTmpInvisibleTasks);
        }

        // Take the first {@param maxNum} tasks and create running task infos for them
        final int size = Math.min(maxNum, mTmpSortedTasks.size());
        final long now = SystemClock.elapsedRealtime();
        for (int i = 0; i < size; i++) {
            final Task task = mTmpSortedTasks.get(i);
            // Override the last active to current time for the visible tasks because the visible
            // tasks can be considered to be currently active, the values are descending as
            // the item order.
            final long visibleActiveTime = i < visibleTaskCount ? now + size - i : -1;
            list.add(createRunningTaskInfo(task, visibleActiveTime));
        }

        mTmpFocusedTasks.clear();
        mTmpVisibleTasks.clear();
        mTmpInvisibleTasks.clear();
        mTmpSortedTasks.clear();
    }

    private void processTaskInWindowContainer(WindowContainer wc) {
        final PooledConsumer c = PooledLambda.obtainConsumer(RunningTasks::processTask, this,
                PooledLambda.__(Task.class));
        wc.forAllLeafTasks(c, true);
        c.recycle();
    }

    private void processTask(Task task) {
        if (task.getTopNonFinishingActivity() == null) {
            // Skip if there are no activities in the task
            return;
        }
        if (task.effectiveUid != mCallingUid) {
            if (task.mUserId != mUserId && !mCrossUser && !mProfileIds.contains(task.mUserId)) {
                // Skip if the caller does not have cross user permission or cannot access
                // the task's profile
                return;
            }
            if (!mAllowed) {
                // Skip if the caller isn't allowed to fetch this task
                return;
            }
        }
        if (mFilterOnlyVisibleRecents
                && task.getActivityType() != ACTIVITY_TYPE_HOME
                && task.getActivityType() != ACTIVITY_TYPE_RECENTS
                && !mRecentTasks.isVisibleRecentTask(task)) {
            // Skip if this task wouldn't be visibile (ever) from recents, with an exception for the
            // home & recent tasks
            return;
        }
        if (task.isVisible()) {
            mTmpVisibleTasks.add(task);
        } else {
            mTmpInvisibleTasks.add(task);
        }
    }

    /** Constructs a {@link RunningTaskInfo} from a given {@param task}. */
    private RunningTaskInfo createRunningTaskInfo(Task task, long visibleActiveTime) {
        final RunningTaskInfo rti = new RunningTaskInfo();
        task.fillTaskInfo(rti, !mKeepIntentExtra);
        if (visibleActiveTime > 0) {
            rti.lastActiveTime = visibleActiveTime;
        }
        // Fill in some deprecated values
        rti.id = rti.taskId;

        if (!mAllowed) {
            Task.trimIneffectiveInfo(task, rti);
        }
        return rti;
    }
}
