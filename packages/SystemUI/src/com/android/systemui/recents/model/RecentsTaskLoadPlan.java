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
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.util.Log;
import android.util.SparseArray;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.misc.SystemServicesProxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    static String TAG = "RecentsTaskLoadPlan";
    static boolean DEBUG = false;

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
    RecentsConfiguration mConfig;
    SystemServicesProxy mSystemServicesProxy;

    List<ActivityManager.RecentTaskInfo> mRawTasks;
    TaskStack mStack;
    HashMap<Task.ComponentNameKey, ActivityInfoHandle> mActivityInfoCache =
            new HashMap<Task.ComponentNameKey, ActivityInfoHandle>();

    /** Package level ctor */
    RecentsTaskLoadPlan(Context context, RecentsConfiguration config, SystemServicesProxy ssp) {
        mContext = context;
        mConfig = config;
        mSystemServicesProxy = ssp;
    }

    /**
     * An optimization to preload the raw list of tasks.
     */
    public synchronized void preloadRawTasks(boolean isTopTaskHome) {
        mRawTasks = mSystemServicesProxy.getRecentTasks(mConfig.maxNumTasksToLoad,
                UserHandle.CURRENT.getIdentifier(), isTopTaskHome);
        Collections.reverse(mRawTasks);

        if (DEBUG) Log.d(TAG, "preloadRawTasks, tasks: " + mRawTasks.size());
    }

    /**
     * Preloads the list of recent tasks from the system.  After this call, the TaskStack will
     * have a list of all the recent tasks with their metadata, not including icons or
     * thumbnails which were not cached and have to be loaded.
     */
    synchronized void preloadPlan(RecentsTaskLoader loader, boolean isTopTaskHome) {
        if (DEBUG) Log.d(TAG, "preloadPlan");

        // This activity info cache will be used for both preloadPlan() and executePlan()
        mActivityInfoCache.clear();

        Resources res = mContext.getResources();
        ArrayList<Task> stackTasks = new ArrayList<>();
        if (mRawTasks == null) {
            preloadRawTasks(isTopTaskHome);
        }
        int taskCount = mRawTasks.size();
        for (int i = 0; i < taskCount; i++) {
            ActivityManager.RecentTaskInfo t = mRawTasks.get(i);

            // Compose the task key
            Task.TaskKey taskKey = new Task.TaskKey(t.persistentId, t.stackId, t.baseIntent,
                    t.userId, t.firstActiveTime, t.lastActiveTime);

            // Get an existing activity info handle if possible
            Task.ComponentNameKey cnKey = taskKey.getComponentNameKey();
            ActivityInfoHandle infoHandle;
            boolean hadCachedActivityInfo = false;
            if (mActivityInfoCache.containsKey(cnKey)) {
                infoHandle = mActivityInfoCache.get(cnKey);
                hadCachedActivityInfo = true;
            } else {
                infoHandle = new ActivityInfoHandle();
            }

            // Load the label, icon, and color
            String activityLabel = loader.getAndUpdateActivityLabel(taskKey, t.taskDescription,
                    mSystemServicesProxy, infoHandle);
            String contentDescription = loader.getAndUpdateContentDescription(taskKey,
                    activityLabel, mSystemServicesProxy, res);
            Drawable activityIcon = loader.getAndUpdateActivityIcon(taskKey, t.taskDescription,
                    mSystemServicesProxy, res, infoHandle, false);
            int activityColor = loader.getActivityPrimaryColor(t.taskDescription, mConfig);

            // Update the activity info cache
            if (!hadCachedActivityInfo && infoHandle.info != null) {
                mActivityInfoCache.put(cnKey, infoHandle);
            }

            Bitmap icon = t.taskDescription != null
                    ? t.taskDescription.getInMemoryIcon()
                    : null;
            String iconFilename = t.taskDescription != null
                    ? t.taskDescription.getIconFilename()
                    : null;

            // Add the task to the stack
            Task task = new Task(taskKey, (t.id != RecentsTaskLoader.INVALID_TASK_ID),
                    t.affiliatedTaskId, t.affiliatedTaskColor, activityLabel, contentDescription,
                    activityIcon, activityColor, (i == (taskCount - 1)), mConfig.lockToAppEnabled,
                    icon, iconFilename);
            task.thumbnail = loader.getAndUpdateThumbnail(taskKey, mSystemServicesProxy, false);
            if (DEBUG) Log.d(TAG, "\tthumbnail: " + taskKey + ", " + task.thumbnail);

            stackTasks.add(task);
        }

        // Initialize the stacks
        mStack = new TaskStack();
        mStack.setTasks(stackTasks);
        mStack.createAffiliatedGroupings(mConfig);
    }

    /**
     * Called to apply the actual loading based on the specified conditions.
     */
    synchronized void executePlan(Options opts, RecentsTaskLoader loader,
            TaskResourceLoadQueue loadQueue) {
        if (DEBUG) Log.d(TAG, "executePlan, # tasks: " + opts.numVisibleTasks +
                ", # thumbnails: " + opts.numVisibleTaskThumbnails +
                ", running task id: " + opts.runningTaskId);

        Resources res = mContext.getResources();

        // Iterate through each of the tasks and load them according to the load conditions.
        ArrayList<Task> tasks = mStack.getTasks();
        int taskCount = tasks.size();
        for (int i = 0; i < taskCount; i++) {
            ActivityManager.RecentTaskInfo t = mRawTasks.get(i);
            Task task = tasks.get(i);
            Task.TaskKey taskKey = task.key;

            // Get an existing activity info handle if possible
            Task.ComponentNameKey cnKey = taskKey.getComponentNameKey();
            ActivityInfoHandle infoHandle;
            boolean hadCachedActivityInfo = false;
            if (mActivityInfoCache.containsKey(cnKey)) {
                infoHandle = mActivityInfoCache.get(cnKey);
                hadCachedActivityInfo = true;
            } else {
                infoHandle = new ActivityInfoHandle();
            }

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
                    task.activityIcon = loader.getAndUpdateActivityIcon(taskKey,
                            t.taskDescription, mSystemServicesProxy, res, infoHandle, true);
                }
            }
            if (opts.loadThumbnails && (isRunningTask || isVisibleThumbnail)) {
                if (task.thumbnail == null || isRunningTask) {
                    if (DEBUG) Log.d(TAG, "\tLoading thumbnail: " + taskKey);
                    if (mConfig.svelteLevel <= RecentsConfiguration.SVELTE_LIMIT_CACHE) {
                        task.thumbnail = loader.getAndUpdateThumbnail(taskKey,
                                mSystemServicesProxy, true);
                    } else if (mConfig.svelteLevel == RecentsConfiguration.SVELTE_DISABLE_CACHE) {
                        loadQueue.addTask(task);
                    }
                }
            }

            // Update the activity info cache
            if (!hadCachedActivityInfo && infoHandle.info != null) {
                mActivityInfoCache.put(cnKey, infoHandle);
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
            return mStack.getTaskCount() > 0;
        }
        return false;
    }
}
