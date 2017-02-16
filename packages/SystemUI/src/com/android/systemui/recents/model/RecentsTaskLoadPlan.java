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
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.Settings;
import android.provider.Settings.Secure;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.systemui.Prefs;
import com.android.systemui.R;
import com.android.systemui.recents.Recents;
import com.android.systemui.recents.RecentsConfiguration;
import com.android.systemui.recents.RecentsDebugFlags;
import com.android.systemui.recents.misc.SystemServicesProxy;

import com.android.systemui.recents.views.grid.TaskGridLayoutAlgorithm;
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
     * An optimization to preload the raw list of tasks. The raw tasks are saved in least-recent
     * to most-recent order.
     */
    public synchronized void preloadRawTasks(boolean includeFrontMostExcludedTask) {
        int currentUserId = UserHandle.USER_CURRENT;
        updateCurrentQuietProfilesCache(currentUserId);
        SystemServicesProxy ssp = Recents.getSystemServices();
        mRawTasks = ssp.getRecentTasks(ActivityManager.getMaxRecentTasksStatic(),
                currentUserId, includeFrontMostExcludedTask, mCurrentQuietProfiles);

        // Since the raw tasks are given in most-recent to least-recent order, we need to reverse it
        Collections.reverse(mRawTasks);
    }

    /**
     * Preloads the list of recent tasks from the system. After this call, the TaskStack will
     * have a list of all the recent tasks with their metadata, not including icons or
     * thumbnails which were not cached and have to be loaded.
     *
     * The tasks will be ordered by:
     * - least-recent to most-recent stack tasks
     * - least-recent to most-recent freeform tasks
     */
    public synchronized void preloadPlan(RecentsTaskLoader loader, int runningTaskId,
            boolean includeFrontMostExcludedTask) {
        Resources res = mContext.getResources();
        ArrayList<Task> allTasks = new ArrayList<>();
        if (mRawTasks == null) {
            preloadRawTasks(includeFrontMostExcludedTask);
        }

        SystemServicesProxy ssp = SystemServicesProxy.getInstance(mContext);
        SparseArray<Task.TaskKey> affiliatedTasks = new SparseArray<>();
        SparseIntArray affiliatedTaskCounts = new SparseIntArray();
        String dismissDescFormat = mContext.getString(
                R.string.accessibility_recents_item_will_be_dismissed);
        String appInfoDescFormat = mContext.getString(
                R.string.accessibility_recents_item_open_app_info);
        int currentUserId = ssp.getCurrentUser();
        long legacyLastStackActiveTime = migrateLegacyLastStackActiveTime(currentUserId);
        long lastStackActiveTime = Settings.Secure.getLongForUser(mContext.getContentResolver(),
                Secure.OVERVIEW_LAST_STACK_ACTIVE_TIME, legacyLastStackActiveTime, currentUserId);
        if (RecentsDebugFlags.Static.EnableMockTasks) {
            lastStackActiveTime = 0;
        }
        long newLastStackActiveTime = -1;
        int taskCount = mRawTasks.size();
        for (int i = 0; i < taskCount; i++) {
            ActivityManager.RecentTaskInfo t = mRawTasks.get(i);

            // Compose the task key
            Task.TaskKey taskKey = new Task.TaskKey(t.persistentId, t.stackId, t.baseIntent,
                    t.userId, t.firstActiveTime, t.lastActiveTime);

            // This task is only shown in the stack if it satisfies the historical time or min
            // number of tasks constraints. Freeform tasks are also always shown.
            boolean isFreeformTask = SystemServicesProxy.isFreeformStack(t.stackId);
            boolean isStackTask;
            if (Recents.getConfiguration().isGridEnabled) {
                // When grid layout is enabled, we only show the first
                // TaskGridLayoutAlgorithm.MAX_LAYOUT_TASK_COUNT} tasks.
                isStackTask = t.lastActiveTime >= lastStackActiveTime &&
                    i >= taskCount - TaskGridLayoutAlgorithm.MAX_LAYOUT_TASK_COUNT;
            } else {
                isStackTask = isFreeformTask || !isHistoricalTask(t) ||
                    (t.lastActiveTime >= lastStackActiveTime && i >= (taskCount - MIN_NUM_TASKS));
            }
            boolean isLaunchTarget = taskKey.id == runningTaskId;

            // The last stack active time is the baseline for which we show visible tasks.  Since
            // the system will store all the tasks, we don't want to show the tasks prior to the
            // last visible ones, otherwise, as you dismiss them, the previous tasks may satisfy
            // the other stack-task constraints.
            if (isStackTask && newLastStackActiveTime < 0) {
                newLastStackActiveTime = t.lastActiveTime;
            }

            // Load the title, icon, and color
            ActivityInfo info = loader.getAndUpdateActivityInfo(taskKey);
            String title = loader.getAndUpdateActivityTitle(taskKey, t.taskDescription);
            String titleDescription = loader.getAndUpdateContentDescription(taskKey, res);
            String dismissDescription = String.format(dismissDescFormat, titleDescription);
            String appInfoDescription = String.format(appInfoDescFormat, titleDescription);
            Drawable icon = isStackTask
                    ? loader.getAndUpdateActivityIcon(taskKey, t.taskDescription, res, false)
                    : null;
            Bitmap thumbnail = loader.getAndUpdateThumbnail(taskKey, false /* loadIfNotCached */);
            int activityColor = loader.getActivityPrimaryColor(t.taskDescription);
            int backgroundColor = loader.getActivityBackgroundColor(t.taskDescription);
            boolean isSystemApp = (info != null) &&
                    ((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0);

            // Add the task to the stack
            Task task = new Task(taskKey, t.affiliatedTaskId, t.affiliatedTaskColor, icon,
                    thumbnail, title, titleDescription, dismissDescription, appInfoDescription,
                    activityColor, backgroundColor, isLaunchTarget, isStackTask, isSystemApp,
                    t.isDockable, t.bounds, t.taskDescription, t.resizeMode, t.topActivity);

            allTasks.add(task);
            affiliatedTaskCounts.put(taskKey.id, affiliatedTaskCounts.get(taskKey.id, 0) + 1);
            affiliatedTasks.put(taskKey.id, taskKey);
        }
        if (newLastStackActiveTime != -1) {
            Settings.Secure.putLongForUser(mContext.getContentResolver(),
                    Secure.OVERVIEW_LAST_STACK_ACTIVE_TIME, newLastStackActiveTime, currentUserId);
        }

        // Initialize the stacks
        mStack = new TaskStack();
        mStack.setTasks(mContext, allTasks, false /* notifyStackChanges */);
    }

    /**
     * Called to apply the actual loading based on the specified conditions.
     */
    public synchronized void executePlan(Options opts, RecentsTaskLoader loader,
            TaskResourceLoadQueue loadQueue) {
        RecentsConfiguration config = Recents.getConfiguration();
        Resources res = mContext.getResources();

        // Iterate through each of the tasks and load them according to the load conditions.
        ArrayList<Task> tasks = mStack.getStackTasks();
        int taskCount = tasks.size();
        for (int i = 0; i < taskCount; i++) {
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
                if (task.icon == null) {
                    task.icon = loader.getAndUpdateActivityIcon(taskKey, task.taskDescription, res,
                            true);
                }
            }
            if (opts.loadThumbnails && (isRunningTask || isVisibleThumbnail)) {
                if (task.thumbnail == null || isRunningTask) {
                    if (config.svelteLevel <= RecentsConfiguration.SVELTE_LIMIT_CACHE) {
                        task.thumbnail = loader.getAndUpdateThumbnail(taskKey,
                                true /* loadIfNotCached */);
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

    /**
     * Returns the raw list of recent tasks.
     */
    public List<ActivityManager.RecentTaskInfo> getRawTasks() {
        return mRawTasks;
    }

    /** Returns whether there are any tasks in any stacks. */
    public boolean hasTasks() {
        if (mStack != null) {
            return mStack.getTaskCount() > 0;
        }
        return false;
    }

    /**
     * Returns whether this task is too old to be shown.
     */
    private boolean isHistoricalTask(ActivityManager.RecentTaskInfo t) {
        return t.lastActiveTime < (System.currentTimeMillis() - SESSION_BEGIN_TIME);
    }

    /**
     * Migrate the last active time from the prefs to the secure settings.
     *
     * The first time this runs, it will:
     * 1) fetch the last stack active time from the prefs
     * 2) set the prefs to the last stack active time for all users
     * 3) clear the pref
     * 4) return the last stack active time
     *
     * Subsequent calls to this will return zero.
     */
    private long migrateLegacyLastStackActiveTime(int currentUserId) {
        long legacyLastStackActiveTime = Prefs.getLong(mContext,
                Prefs.Key.OVERVIEW_LAST_STACK_TASK_ACTIVE_TIME, -1);
        if (legacyLastStackActiveTime != -1) {
            Prefs.remove(mContext, Prefs.Key.OVERVIEW_LAST_STACK_TASK_ACTIVE_TIME);
            UserManager userMgr = (UserManager) mContext.getSystemService(Context.USER_SERVICE);
            List<UserInfo> users = userMgr.getUsers();
            for (int i = 0; i < users.size(); i++) {
                int userId = users.get(i).id;
                if (userId != currentUserId) {
                    Settings.Secure.putLongForUser(mContext.getContentResolver(),
                            Secure.OVERVIEW_LAST_STACK_ACTIVE_TIME, legacyLastStackActiveTime,
                            userId);
                }
            }
            return legacyLastStackActiveTime;
        }
        return 0;
    }
}
