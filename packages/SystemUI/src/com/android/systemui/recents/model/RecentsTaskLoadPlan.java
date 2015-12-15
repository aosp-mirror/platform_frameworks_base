/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.model;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;
import android.util.Log;
import com.android.systemui.Prefs;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.misc.SystemServicesProxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * This class stores the loading state as it goes through multiple stages of loading:
 *   1) preloadRawTasks() will load the raw set of recents tasks from the system
 *   2) preloadPlan() will construct a new task stack with all metadata and only icons and
 *      thumbnails that are currently in the cache
 *   3) executePlan() will actually load and fill in the icons and thumbnails according to the load
 *      options specified, such that we can transition into the Recents activity seamlessly
 */
public class RecentsTaskLoadPlan {

    private static String TAG = "RecentsTaskLoadPlan";
    private static boolean DEBUG = false;

    private static int MIN_NUM_TASKS = 5;
    private static int SESSION_BEGIN_TIME = 1000 /* ms/s */ * 60 /* s/min */ * 60 /* min/hr */ *
            6 /* hrs */;

    /** The set of conditions to load tasks. */
    public static class Options {
        public int runningTaskId = -1;
        public boolean loadIcons = true;
        public boolean loadThumbnails = true;
        public boolean onlyLoadForCache = false;
        public boolean onlyLoadPausedActivities = false;
        public int numVisibleTasks = 0;
        public int numVisibleTaskThumbnails = 0;
    }

    Context mContext;

    List<ActivityManager.RecentTaskInfo> mRawTasks;
    TaskStack mStack;
    ArraySet<Integer> mCurrentQuietProfiles = new ArraySet<Integer>();

    /** Package level ctor */
    RecentsTaskLoadPlan(Context context) {
        mContext = context;
    }

    private void updateCurrentQuietProfilesCache(int currentUserId) {
        mCurrentQuietProfiles.clear();

        if (currentUserId == UserHandle.USER_CURRENT) {
            currentUserId = ActivityManager.getCurrentUser();
        }
        UserManager userManager = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
        List<UserInfo> profiles = userManager.getProfiles(currentUserId);
        if (profiles != null) {
            for (int i = 0; i < profiles.size(); i++) {
                UserInfo user  = profiles.get(i);
                if (user.isManagedProfile() && user.isQuietModeEnabled()) {
                    mCurrentQuietProfiles.add(user.id);
                }
            }
        }
    }

    /**
     * An optimization to preload the raw list of tasks.  The raw tasks are saved in least-recent
     * to most-recent order.
     */
    public synchronized void preloadRawTasks(boolean isTopTaskHome) {
        int currentUserId = UserHandle.USER_CURRENT;
        updateCurrentQuietProfilesCache(currentUserId);
        SystemServicesProxy ssp = Recents.getSystemServices();
        mRawTasks = ssp.getRecentTasks(ActivityManager.getMaxRecentTasksStatic(),
                currentUserId, isTopTaskHome, mCurrentQuietProfiles);

        // Since the raw tasks are given in most-recent to least-recent order, we need to reverse it
        Collections.reverse(mRawTasks);

        if (DEBUG) {
            Log.d(TAG, "preloadRawTasks, tasks: " + mRawTasks.size());
            for (ActivityManager.RecentTaskInfo info : mRawTasks) {
                Log.d(TAG, "  " + info.baseIntent + ", " + info.lastActiveTime);
            }
        }
    }

    /**
     * Preloads the list of recent tasks from the system.  After this call, the TaskStack will
     * have a list of all the recent tasks with their metadata, not including icons or
     * thumbnails which were not cached and have to be loaded.
     *
     * The tasks will be ordered by:
     * - least-recent to most-recent stack tasks
     * - least-recent to most-recent freeform tasks
     */
    public synchronized void preloadPlan(RecentsTaskLoader loader, boolean isTopTaskHome) {
        if (DEBUG) Log.d(TAG, "preloadPlan");

        RecentsDebugFlags debugFlags = Recents.getDebugFlags();
        RecentsConfiguration config = Recents.getConfiguration();
        SystemServicesProxy ssp = Recents.getSystemServices();
        Resources res = mContext.getResources();
        ArrayList<Task> freeformTasks = new ArrayList<>();
        ArrayList<Task> stackTasks = new ArrayList<>();
        if (mRawTasks == null) {
            preloadRawTasks(isTopTaskHome);
        }

        long lastStackActiveTime = Prefs.getLong(mContext,
                Prefs.Key.OVERVIEW_LAST_STACK_TASK_ACTIVE_TIME, 0);
        long newLastStackActiveTime = -1;
        int taskCount = mRawTasks.size();
        for (int i = 0; i < taskCount; i++) {
            ActivityManager.RecentTaskInfo t = mRawTasks.get(i);

            // Compose the task key
            Task.TaskKey taskKey = new Task.TaskKey(t.persistentId, t.stackId, t.baseIntent,
                    t.userId, t.firstActiveTime, t.lastActiveTime);

            // This task is only shown in the stack if it statisfies the historical time or min
            // number of tasks constraints. Freeform tasks are also always shown.
            boolean isStackTask = true;
            boolean isFreeformTask = SystemServicesProxy.isFreeformStack(t.stackId);
            isStackTask = isFreeformTask || (!isHistoricalTask(t) ||
                    (t.lastActiveTime >= lastStackActiveTime &&
                            i >= (taskCount - MIN_NUM_TASKS)));
            if (isStackTask && newLastStackActiveTime < 0) {
                newLastStackActiveTime = t.lastActiveTime;
            }

            // Load the label, icon, and color
            String activityLabel = loader.getAndUpdateActivityLabel(taskKey, t.taskDescription,
                    ssp);
            String contentDescription = loader.getAndUpdateContentDescription(taskKey,
                    activityLabel, ssp, res);
            Drawable activityIcon = isStackTask
                    ? loader.getAndUpdateActivityIcon(taskKey, t.taskDescription, ssp, res, false)
                    : null;
            int activityColor = loader.getActivityPrimaryColor(t.taskDescription);

            Bitmap icon = t.taskDescription != null
                    ? t.taskDescription.getInMemoryIcon() : null;
            String iconFilename = t.taskDescription != null
                    ? t.taskDescription.getIconFilename() : null;

            // Add the task to the stack
            Task task = new Task(taskKey, t.affiliatedTaskId, t.affiliatedTaskColor, activityLabel,
                    contentDescription, activityIcon, activityColor, (i == (taskCount - 1)),
                    config.lockToAppEnabled, !isStackTask, icon, iconFilename, t.bounds);
            task.thumbnail = loader.getAndUpdateThumbnail(taskKey, ssp, false);
            if (DEBUG) {
                Log.d(TAG, activityLabel + " bounds: " + t.bounds);
            }

            if (task.isFreeformTask()) {
                freeformTasks.add(task);
            } else {
                stackTasks.add(task);
            }
        }
        if (newLastStackActiveTime != -1) {
            Prefs.putLong(mContext, Prefs.Key.OVERVIEW_LAST_STACK_TASK_ACTIVE_TIME,
                    newLastStackActiveTime);
        }

        // Initialize the stacks
        ArrayList<Task> allTasks = new ArrayList<>();
        allTasks.addAll(stackTasks);
        allTasks.addAll(freeformTasks);
        mStack = new TaskStack();
        mStack.setTasks(allTasks);
        mStack.createAffiliatedGroupings(mContext);
    }

    /**
     * Called to apply the actual loading based on the specified conditions.
     */
    public synchronized void executePlan(Options opts, RecentsTaskLoader loader,
            TaskResourceLoadQueue loadQueue) {
        if (DEBUG) Log.d(TAG, "executePlan, # tasks: " + opts.numVisibleTasks +
                ", # thumbnails: " + opts.numVisibleTaskThumbnails +
                ", running task id: " + opts.runningTaskId);

        RecentsConfiguration config = Recents.getConfiguration();
        SystemServicesProxy ssp = Recents.getSystemServices();
        Resources res = mContext.getResources();

        // Iterate through each of the tasks and load them according to the load conditions.
        ArrayList<Task> tasks = mStack.getStackTasks();
        int taskCount = tasks.size();
        for (int i = 0; i < taskCount; i++) {
            ActivityManager.RecentTaskInfo t = mRawTasks.get(i);
            Task task = tasks.get(i);
            Task.TaskKey taskKey = task.key;

            boolean isRunningTask = (task.key.id == opts.runningTaskId);
            boolean isVisibleTask = i >= (taskCount - opts.numVisibleTasks);
            boolean isVisibleThumbnail = i >= (taskCount - opts.numVisibleTaskThumbnails);

            // If requested, skip the running task
            if (opts.onlyLoadPausedActivities && isRunningTask) {
                continue;
            }

            if (opts.loadIcons && (isRunningTask || isVisibleTask)) {
                if (task.activityIcon == null) {
                    if (DEBUG) Log.d(TAG, "\tLoading icon: " + taskKey);
                    task.activityIcon = loader.getAndUpdateActivityIcon(taskKey, t.taskDescription,
                            ssp, res, true);
                }
            }
            if (opts.loadThumbnails && (isRunningTask || isVisibleThumbnail)) {
                if (task.thumbnail == null || isRunningTask) {
                    if (DEBUG) Log.d(TAG, "\tLoading thumbnail: " + taskKey);
                    if (config.svelteLevel <= RecentsConfiguration.SVELTE_LIMIT_CACHE) {
                        task.thumbnail = loader.getAndUpdateThumbnail(taskKey, ssp, true);
                    } else if (config.svelteLevel == RecentsConfiguration.SVELTE_DISABLE_CACHE) {
                        loadQueue.addTask(task);
                    }
                }
            }
        }
    }

    /**
     * Returns the TaskStack from the preloaded list of recent tasks.
     */
    public TaskStack getTaskStack() {
        return mStack;
    }

    /** Returns whether there are any tasks in any stacks. */
    public boolean hasTasks() {
        if (mStack != null) {
            return mStack.getStackTaskCount() > 0;
        }
        return false;
    }

    /**
     * Returns whether this task is considered a task to be shown in the history.
     */
    private boolean isHistoricalTask(ActivityManager.RecentTaskInfo t) {
        return t.lastActiveTime < (System.currentTimeMillis() - SESSION_BEGIN_TIME);
    }
}
