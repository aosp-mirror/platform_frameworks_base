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
 * limitations under the License
 */

package com.android.server.wm;

import static android.app.ActivityManager.FLAG_AND_UNLOCKED;
import static android.app.ActivityManager.RECENT_IGNORE_UNAVAILABLE;
import static android.app.ActivityManager.RECENT_WITH_EXCLUDED;
import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_ASSISTANT;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_DREAM;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_RECENTS;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_UNDEFINED;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;
import static android.content.Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
import static android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_DOCUMENT;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.os.Process.SYSTEM_UID;

import static com.android.server.wm.ActivityStackSupervisor.REMOVE_FROM_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_RECENTS_TRIM_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.DEBUG_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_RECENTS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.POSTFIX_TASKS;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_ATM;
import static com.android.server.wm.ActivityTaskManagerDebugConfig.TAG_WITH_CLASS_NAME;

import android.annotation.Nullable;
import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.AppGlobals;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.view.MotionEvent;
import android.view.WindowManagerPolicyConstants.PointerEventListener;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.function.pooled.PooledLambda;
import com.android.server.am.ActivityManagerService;

import com.google.android.collect.Sets;

import java.io.File;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Class for managing the recent tasks list. The list is ordered by most recent (index 0) to the
 * least recent.
 *
 * The trimming logic can be boiled down to the following.  For recent task list with a number of
 * tasks, the visible tasks are an interleaving subset of tasks that would normally be presented to
 * the user. Non-visible tasks are not considered for trimming. Of the visible tasks, only a
 * sub-range are presented to the user, based on the device type, last task active time, or other
 * task state. Tasks that are not in the visible range and are not returnable from the SystemUI
 * (considering the back stack) are considered trimmable. If the device does not support recent
 * tasks, then trimming is completely disabled.
 *
 * eg.
 * L = [TTTTTTTTTTTTTTTTTTTTTTTTTT] // list of tasks
 *     [VVV  VV   VVVV  V V V     ] // Visible tasks
 *     [RRR  RR   XXXX  X X X     ] // Visible range tasks, eg. if the device only shows 5 tasks,
 *                                  // 'X' tasks are trimmed.
 */
class RecentTasks {
    private static final String TAG = TAG_WITH_CLASS_NAME ? "RecentTasks" : TAG_ATM;
    private static final String TAG_RECENTS = TAG + POSTFIX_RECENTS;
    private static final String TAG_TASKS = TAG + POSTFIX_TASKS;

    private static final int DEFAULT_INITIAL_CAPACITY = 5;

    // The duration of time after freezing the recent tasks list where getRecentTasks() will return
    // a stable ordering of the tasks. Upon the next call to getRecentTasks() beyond this duration,
    // the task list will be unfrozen and committed (the current top task will be moved to the
    // front of the list)
    private static final long FREEZE_TASK_LIST_TIMEOUT_MS = TimeUnit.SECONDS.toMillis(5);

    // Comparator to sort by taskId
    private static final Comparator<Task> TASK_ID_COMPARATOR =
            (lhs, rhs) -> rhs.mTaskId - lhs.mTaskId;

    // Placeholder variables to keep track of activities/apps that are no longer avialble while
    // iterating through the recents list
    private static final ActivityInfo NO_ACTIVITY_INFO_TOKEN = new ActivityInfo();
    private static final ApplicationInfo NO_APPLICATION_INFO_TOKEN = new ApplicationInfo();
    private TaskChangeNotificationController mTaskNotificationController;

    /**
     * Callbacks made when manipulating the list.
     */
    interface Callbacks {
        /**
         * Called when a task is added to the recent tasks list.
         */
        void onRecentTaskAdded(Task task);

        /**
         * Called when a task is removed from the recent tasks list.
         */
        void onRecentTaskRemoved(Task task, boolean wasTrimmed, boolean killProcess);
    }

    /**
     * Save recent tasks information across reboots.
     */
    private final TaskPersister mTaskPersister;
    private final ActivityTaskManagerService mService;
    private final ActivityStackSupervisor mSupervisor;

    /**
     * Keeps track of the static recents package/component which is granted additional permissions
     * to call recents-related APIs.
     */
    private int mRecentsUid = -1;
    private ComponentName mRecentsComponent = null;
    private @Nullable String mFeatureId;

    /**
     * Mapping of user id -> whether recent tasks have been loaded for that user.
     */
    private final SparseBooleanArray mUsersWithRecentsLoaded = new SparseBooleanArray(
            DEFAULT_INITIAL_CAPACITY);

    /**
     * Stores for each user task ids that are taken by tasks residing in persistent storage. These
     * tasks may or may not currently be in memory.
     */
    private final SparseArray<SparseBooleanArray> mPersistedTaskIds = new SparseArray<>(
            DEFAULT_INITIAL_CAPACITY);

    // List of all active recent tasks
    private final ArrayList<Task> mTasks = new ArrayList<>();
    private final ArrayList<Callbacks> mCallbacks = new ArrayList<>();

    /** The non-empty tasks that are removed from recent tasks (see {@link #removeForAddTask}). */
    private final ArrayList<Task> mHiddenTasks = new ArrayList<>();

    // These values are generally loaded from resources, but can be set dynamically in the tests
    private boolean mHasVisibleRecentTasks;
    private int mGlobalMaxNumTasks;
    private int mMinNumVisibleTasks;
    private int mMaxNumVisibleTasks;
    private long mActiveTasksSessionDurationMs;

    // When set, the task list will not be reordered as tasks within the list are moved to the
    // front. Newly created tasks, or tasks that are removed from the list will continue to change
    // the list.  This does not affect affiliated tasks.
    private boolean mFreezeTaskListReordering;
    private long mFreezeTaskListTimeoutMs = FREEZE_TASK_LIST_TIMEOUT_MS;

    // Mainly to avoid object recreation on multiple calls.
    private final ArrayList<Task> mTmpRecents = new ArrayList<>();
    private final HashMap<ComponentName, ActivityInfo> mTmpAvailActCache = new HashMap<>();
    private final HashMap<String, ApplicationInfo> mTmpAvailAppCache = new HashMap<>();
    private final SparseBooleanArray mTmpQuietProfileUserIds = new SparseBooleanArray();

    // TODO(b/127498985): This is currently a rough heuristic for interaction inside an app
    private final PointerEventListener mListener = new PointerEventListener() {
        @Override
        public void onPointerEvent(MotionEvent ev) {
            if (!mFreezeTaskListReordering || ev.getAction() != MotionEvent.ACTION_DOWN) {
                // Skip if we aren't freezing or starting a gesture
                return;
            }
            int displayId = ev.getDisplayId();
            int x = (int) ev.getX();
            int y = (int) ev.getY();
            mService.mH.post(PooledLambda.obtainRunnable((nonArg) -> {
                synchronized (mService.mGlobalLock) {
                    // Unfreeze the task list once we touch down in a task
                    final RootWindowContainer rac = mService.mRootWindowContainer;
                    final DisplayContent dc = rac.getDisplayContent(displayId).mDisplayContent;
                    if (dc.pointWithinAppWindow(x, y)) {
                        final ActivityStack stack = mService.getTopDisplayFocusedStack();
                        final Task topTask = stack != null ? stack.getTopMostTask() : null;
                        resetFreezeTaskListReordering(topTask);
                    }
                }
            }, null).recycleOnUse());
        }
    };

    private final Runnable mResetFreezeTaskListOnTimeoutRunnable =
            this::resetFreezeTaskListReorderingOnTimeout;

    @VisibleForTesting
    RecentTasks(ActivityTaskManagerService service, TaskPersister taskPersister) {
        mService = service;
        mSupervisor = mService.mStackSupervisor;
        mTaskPersister = taskPersister;
        mGlobalMaxNumTasks = ActivityTaskManager.getMaxRecentTasksStatic();
        mHasVisibleRecentTasks = true;
        mTaskNotificationController = service.getTaskChangeNotificationController();
    }

    RecentTasks(ActivityTaskManagerService service, ActivityStackSupervisor stackSupervisor) {
        final File systemDir = Environment.getDataSystemDirectory();
        final Resources res = service.mContext.getResources();
        mService = service;
        mSupervisor = mService.mStackSupervisor;
        mTaskPersister = new TaskPersister(systemDir, stackSupervisor, service, this,
                stackSupervisor.mPersisterQueue);
        mGlobalMaxNumTasks = ActivityTaskManager.getMaxRecentTasksStatic();
        mTaskNotificationController = service.getTaskChangeNotificationController();
        mHasVisibleRecentTasks = res.getBoolean(com.android.internal.R.bool.config_hasRecents);
        loadParametersFromResources(res);
    }

    @VisibleForTesting
    void setParameters(int minNumVisibleTasks, int maxNumVisibleTasks,
            long activeSessionDurationMs) {
        mMinNumVisibleTasks = minNumVisibleTasks;
        mMaxNumVisibleTasks = maxNumVisibleTasks;
        mActiveTasksSessionDurationMs = activeSessionDurationMs;
    }

    @VisibleForTesting
    void setGlobalMaxNumTasks(int globalMaxNumTasks) {
        mGlobalMaxNumTasks = globalMaxNumTasks;
    }

    @VisibleForTesting
    void setFreezeTaskListTimeout(long timeoutMs) {
        mFreezeTaskListTimeoutMs = timeoutMs;
    }

    PointerEventListener getInputListener() {
        return mListener;
    }

    /**
     * Freezes the current recent task list order until either a user interaction with the current
     * app, or a timeout occurs.
     */
    void setFreezeTaskListReordering() {
        // Only fire the callback once per quickswitch session, not on every individual switch
        if (!mFreezeTaskListReordering) {
            mTaskNotificationController.notifyTaskListFrozen(true);
            mFreezeTaskListReordering = true;
        }

        // Always update the reordering time when this is called to ensure that the timeout
        // is reset
        mService.mH.removeCallbacks(mResetFreezeTaskListOnTimeoutRunnable);
        mService.mH.postDelayed(mResetFreezeTaskListOnTimeoutRunnable, mFreezeTaskListTimeoutMs);
    }

    /**
     * Commits the frozen recent task list order, moving the provided {@param topTask} to the
     * front of the list.
     */
    void resetFreezeTaskListReordering(Task topTask) {
        if (!mFreezeTaskListReordering) {
            return;
        }

        // Once we end freezing the task list, reset the existing task order to the stable state
        mFreezeTaskListReordering = false;
        mService.mH.removeCallbacks(mResetFreezeTaskListOnTimeoutRunnable);

        // If the top task is provided, then restore the top task to the front of the list
        if (topTask != null) {
            mTasks.remove(topTask);
            mTasks.add(0, topTask);
        }

        // Resume trimming tasks
        trimInactiveRecentTasks();

        mTaskNotificationController.notifyTaskStackChanged();
        mTaskNotificationController.notifyTaskListFrozen(false);
    }

    /**
     * Resets the frozen recent task list order if the timeout has passed. This should be called
     * before we need to iterate the task list in order (either for purposes of returning the list
     * to SystemUI or if we need to trim tasks in order)
     */
    @VisibleForTesting
    void resetFreezeTaskListReorderingOnTimeout() {
        synchronized (mService.mGlobalLock) {
            final ActivityStack focusedStack = mService.getTopDisplayFocusedStack();
            final Task topTask = focusedStack != null ? focusedStack.getTopMostTask() : null;
            resetFreezeTaskListReordering(topTask);
        }
    }

    @VisibleForTesting
    boolean isFreezeTaskListReorderingSet() {
        return mFreezeTaskListReordering;
    }

    /**
     * Loads the parameters from the system resources.
     */
    @VisibleForTesting
    void loadParametersFromResources(Resources res) {
        if (ActivityManager.isLowRamDeviceStatic()) {
            mMinNumVisibleTasks = res.getInteger(
                    com.android.internal.R.integer.config_minNumVisibleRecentTasks_lowRam);
            mMaxNumVisibleTasks = res.getInteger(
                    com.android.internal.R.integer.config_maxNumVisibleRecentTasks_lowRam);
        } else if (SystemProperties.getBoolean("ro.recents.grid", false)) {
            mMinNumVisibleTasks = res.getInteger(
                    com.android.internal.R.integer.config_minNumVisibleRecentTasks_grid);
            mMaxNumVisibleTasks = res.getInteger(
                    com.android.internal.R.integer.config_maxNumVisibleRecentTasks_grid);
        } else {
            mMinNumVisibleTasks = res.getInteger(
                    com.android.internal.R.integer.config_minNumVisibleRecentTasks);
            mMaxNumVisibleTasks = res.getInteger(
                    com.android.internal.R.integer.config_maxNumVisibleRecentTasks);
        }
        final int sessionDurationHrs = res.getInteger(
                com.android.internal.R.integer.config_activeTaskDurationHours);
        mActiveTasksSessionDurationMs = (sessionDurationHrs > 0)
                ? TimeUnit.HOURS.toMillis(sessionDurationHrs)
                : -1;
    }

    /**
     * Loads the static recents component.  This is called after the system is ready, but before
     * any dependent services (like SystemUI) is started.
     */
    void loadRecentsComponent(Resources res) {
        final String rawRecentsComponent = res.getString(
                com.android.internal.R.string.config_recentsComponentName);
        if (TextUtils.isEmpty(rawRecentsComponent)) {
            return;
        }

        final ComponentName cn = ComponentName.unflattenFromString(rawRecentsComponent);
        if (cn != null) {
            try {
                final ApplicationInfo appInfo = AppGlobals.getPackageManager()
                        .getApplicationInfo(cn.getPackageName(), 0, mService.mContext.getUserId());
                if (appInfo != null) {
                    mRecentsUid = appInfo.uid;
                    mRecentsComponent = cn;
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Could not load application info for recents component: " + cn);
            }
        }
    }

    /**
     * @return whether the current caller has the same uid as the recents component.
     */
    boolean isCallerRecents(int callingUid) {
        return UserHandle.isSameApp(callingUid, mRecentsUid);
    }

    /**
     * @return whether the given component is the recents component and shares the same uid as the
     *         recents component.
     */
    boolean isRecentsComponent(ComponentName cn, int uid) {
        return cn.equals(mRecentsComponent) && UserHandle.isSameApp(uid, mRecentsUid);
    }

    /**
     * @return whether the home app is also the active handler of recent tasks.
     */
    boolean isRecentsComponentHomeActivity(int userId) {
        final ComponentName defaultHomeActivity = mService.getPackageManagerInternalLocked()
                .getDefaultHomeActivity(userId);
        return defaultHomeActivity != null && mRecentsComponent != null &&
                defaultHomeActivity.getPackageName().equals(mRecentsComponent.getPackageName());
    }

    /**
     * @return the recents component.
     */
    ComponentName getRecentsComponent() {
        return mRecentsComponent;
    }

    /**
     * @return the featureId for the recents component.
     */
    @Nullable String getRecentsComponentFeatureId() {
        return mFeatureId;
    }

    /**
     * @return the uid for the recents component.
     */
    int getRecentsComponentUid() {
        return mRecentsUid;
    }

    void registerCallback(Callbacks callback) {
        mCallbacks.add(callback);
    }

    void unregisterCallback(Callbacks callback) {
        mCallbacks.remove(callback);
    }

    private void notifyTaskAdded(Task task) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onRecentTaskAdded(task);
        }
        mTaskNotificationController.notifyTaskListUpdated();
    }

    private void notifyTaskRemoved(Task task, boolean wasTrimmed, boolean killProcess) {
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).onRecentTaskRemoved(task, wasTrimmed, killProcess);
        }
        mTaskNotificationController.notifyTaskListUpdated();
    }

    /**
     * Loads the persistent recentTasks for {@code userId} into this list from persistent storage.
     * Does nothing if they are already loaded.
     *
     * @param userId the user Id
     */
    void loadUserRecentsLocked(int userId) {
        if (mUsersWithRecentsLoaded.get(userId)) {
            // User already loaded, return early
            return;
        }

        // Load the task ids if not loaded.
        loadPersistedTaskIdsForUserLocked(userId);

        // Check if any tasks are added before recents is loaded
        final SparseBooleanArray preaddedTasks = new SparseBooleanArray();
        for (final Task task : mTasks) {
            if (task.mUserId == userId && shouldPersistTaskLocked(task)) {
                preaddedTasks.put(task.mTaskId, true);
            }
        }

        Slog.i(TAG, "Loading recents for user " + userId + " into memory.");
        List<Task> tasks = mTaskPersister.restoreTasksForUserLocked(userId, preaddedTasks);
        mTasks.addAll(tasks);
        cleanupLocked(userId);
        mUsersWithRecentsLoaded.put(userId, true);

        // If we have tasks added before loading recents, we need to update persistent task IDs.
        if (preaddedTasks.size() > 0) {
            syncPersistentTaskIdsLocked();
        }
    }

    private void loadPersistedTaskIdsForUserLocked(int userId) {
        // An empty instead of a null set here means that no persistent taskIds were present
        // on file when we loaded them.
        if (mPersistedTaskIds.get(userId) == null) {
            mPersistedTaskIds.put(userId, mTaskPersister.loadPersistedTaskIdsForUser(userId));
            Slog.i(TAG, "Loaded persisted task ids for user " + userId);
        }
    }

    /**
     * @return whether the {@param taskId} is currently in use for the given user.
     */
    boolean containsTaskId(int taskId, int userId) {
        loadPersistedTaskIdsForUserLocked(userId);
        return mPersistedTaskIds.get(userId).get(taskId);
    }

    /**
     * @return all the task ids for the user with the given {@param userId}.
     */
    SparseBooleanArray getTaskIdsForUser(int userId) {
        loadPersistedTaskIdsForUserLocked(userId);
        return mPersistedTaskIds.get(userId);
    }

    /**
     * Kicks off the task persister to write any pending tasks to disk.
     */
    void notifyTaskPersisterLocked(Task task, boolean flush) {
        final ActivityStack stack = task != null ? task.getStack() : null;
        if (stack != null && stack.isHomeOrRecentsStack()) {
            // Never persist the home or recents stack.
            return;
        }
        syncPersistentTaskIdsLocked();
        mTaskPersister.wakeup(task, flush);
    }

    private void syncPersistentTaskIdsLocked() {
        for (int i = mPersistedTaskIds.size() - 1; i >= 0; i--) {
            int userId = mPersistedTaskIds.keyAt(i);
            if (mUsersWithRecentsLoaded.get(userId)) {
                // Recents are loaded only after task ids are loaded. Therefore, the set of taskids
                // referenced here should not be null.
                mPersistedTaskIds.valueAt(i).clear();
            }
        }
        for (int i = mTasks.size() - 1; i >= 0; i--) {
            final Task task = mTasks.get(i);
            if (shouldPersistTaskLocked(task)) {
                // Set of persisted taskIds for task.userId should not be null here
                // TODO Investigate why it can happen. For now initialize with an empty set
                if (mPersistedTaskIds.get(task.mUserId) == null) {
                    Slog.wtf(TAG, "No task ids found for userId " + task.mUserId + ". task=" + task
                            + " mPersistedTaskIds=" + mPersistedTaskIds);
                    mPersistedTaskIds.put(task.mUserId, new SparseBooleanArray());
                }
                mPersistedTaskIds.get(task.mUserId).put(task.mTaskId, true);
            }
        }
    }

    private static boolean shouldPersistTaskLocked(Task task) {
        final ActivityStack stack = task.getStack();
        return task.isPersistable && (stack == null || !stack.isHomeOrRecentsStack());
    }

    void onSystemReadyLocked() {
        loadRecentsComponent(mService.mContext.getResources());
        mTasks.clear();
    }

    Bitmap getTaskDescriptionIcon(String path) {
        return mTaskPersister.getTaskDescriptionIcon(path);
    }

    void saveImage(Bitmap image, String path) {
        mTaskPersister.saveImage(image, path);
    }

    void flush() {
        synchronized (mService.mGlobalLock) {
            syncPersistentTaskIdsLocked();
        }
        mTaskPersister.flush();
    }

    /**
     * Returns all userIds for which recents from persistent storage are loaded into this list.
     *
     * @return an array of userIds.
     */
    int[] usersWithRecentsLoadedLocked() {
        int[] usersWithRecentsLoaded = new int[mUsersWithRecentsLoaded.size()];
        int len = 0;
        for (int i = 0; i < usersWithRecentsLoaded.length; i++) {
            int userId = mUsersWithRecentsLoaded.keyAt(i);
            if (mUsersWithRecentsLoaded.valueAt(i)) {
                usersWithRecentsLoaded[len++] = userId;
            }
        }
        if (len < usersWithRecentsLoaded.length) {
            // should never happen.
            return Arrays.copyOf(usersWithRecentsLoaded, len);
        }
        return usersWithRecentsLoaded;
    }

    /**
     * Removes recent tasks and any other state kept in memory for the passed in user. Does not
     * touch the information present on persistent storage.
     *
     * @param userId the id of the user
     */
    void unloadUserDataFromMemoryLocked(int userId) {
        if (mUsersWithRecentsLoaded.get(userId)) {
            Slog.i(TAG, "Unloading recents for user " + userId + " from memory.");
            mUsersWithRecentsLoaded.delete(userId);
            removeTasksForUserLocked(userId);
        }
        mPersistedTaskIds.delete(userId);
        mTaskPersister.unloadUserDataFromMemory(userId);
    }

    /** Remove recent tasks for a user. */
    private void removeTasksForUserLocked(int userId) {
        if(userId <= 0) {
            Slog.i(TAG, "Can't remove recent task on user " + userId);
            return;
        }

        for (int i = mTasks.size() - 1; i >= 0; --i) {
            Task task = mTasks.get(i);
            if (task.mUserId == userId) {
                if(DEBUG_TASKS) Slog.i(TAG_TASKS,
                        "remove RecentTask " + task + " when finishing user" + userId);
                remove(task);
            }
        }
    }

    void onPackagesSuspendedChanged(String[] packages, boolean suspended, int userId) {
        final Set<String> packageNames = Sets.newHashSet(packages);
        for (int i = mTasks.size() - 1; i >= 0; --i) {
            final Task task = mTasks.get(i);
            if (task.realActivity != null
                    && packageNames.contains(task.realActivity.getPackageName())
                    && task.mUserId == userId
                    && task.realActivitySuspended != suspended) {
               task.realActivitySuspended = suspended;
               if (suspended) {
                   mSupervisor.removeTask(task, false, REMOVE_FROM_RECENTS, "suspended-package");
               }
               notifyTaskPersisterLocked(task, false);
            }
        }
    }

    void onLockTaskModeStateChanged(int lockTaskModeState, int userId) {
        if (lockTaskModeState != ActivityManager.LOCK_TASK_MODE_LOCKED) {
            return;
        }
        for (int i = mTasks.size() - 1; i >= 0; --i) {
            final Task task = mTasks.get(i);
            if (task.mUserId == userId && !mService.getLockTaskController().isTaskWhitelisted(task)) {
                remove(task);
            }
        }
    }

    void removeTasksByPackageName(String packageName, int userId) {
        for (int i = mTasks.size() - 1; i >= 0; --i) {
            final Task task = mTasks.get(i);
            final String taskPackageName =
                    task.getBaseIntent().getComponent().getPackageName();
            if (task.mUserId != userId) continue;
            if (!taskPackageName.equals(packageName)) continue;

            mSupervisor.removeTask(task, true, REMOVE_FROM_RECENTS, "remove-package-task");
        }
    }

    void removeAllVisibleTasks(int userId) {
        Set<Integer> profileIds = getProfileIds(userId);
        for (int i = mTasks.size() - 1; i >= 0; --i) {
            final Task task = mTasks.get(i);
            if (!profileIds.contains(task.mUserId)) continue;
            if (isVisibleRecentTask(task)) {
                mTasks.remove(i);
                notifyTaskRemoved(task, true /* wasTrimmed */, true /* killProcess */);
            }
        }
    }

    void cleanupDisabledPackageTasksLocked(String packageName, Set<String> filterByClasses,
            int userId) {
        for (int i = mTasks.size() - 1; i >= 0; --i) {
            final Task task = mTasks.get(i);
            if (userId != UserHandle.USER_ALL && task.mUserId != userId) {
                continue;
            }

            ComponentName cn = task.intent != null ? task.intent.getComponent() : null;
            final boolean sameComponent = cn != null && cn.getPackageName().equals(packageName)
                    && (filterByClasses == null || filterByClasses.contains(cn.getClassName()));
            if (sameComponent) {
                mSupervisor.removeTask(task, false, REMOVE_FROM_RECENTS, "disabled-package");
            }
        }
    }

    /**
     * Update the recent tasks lists: make sure tasks should still be here (their
     * applications / activities still exist), update their availability, fix-up ordering
     * of affiliations.
     */
    void cleanupLocked(int userId) {
        int recentsCount = mTasks.size();
        if (recentsCount == 0) {
            // Happens when called from the packagemanager broadcast before boot,
            // or just any empty list.
            return;
        }

        // Clear the temp lists
        mTmpAvailActCache.clear();
        mTmpAvailAppCache.clear();

        final IPackageManager pm = AppGlobals.getPackageManager();
        for (int i = recentsCount - 1; i >= 0; i--) {
            final Task task = mTasks.get(i);
            if (userId != UserHandle.USER_ALL && task.mUserId != userId) {
                // Only look at tasks for the user ID of interest.
                continue;
            }
            if (task.autoRemoveRecents && task.getTopNonFinishingActivity() == null) {
                // This situation is broken, and we should just get rid of it now.
                remove(task);
                Slog.w(TAG, "Removing auto-remove without activity: " + task);
                continue;
            }
            // Check whether this activity is currently available.
            if (task.realActivity != null) {
                ActivityInfo ai = mTmpAvailActCache.get(task.realActivity);
                if (ai == null) {
                    try {
                        // At this first cut, we're only interested in
                        // activities that are fully runnable based on
                        // current system state.
                        ai = pm.getActivityInfo(task.realActivity,
                                PackageManager.MATCH_DEBUG_TRIAGED_MISSING
                                        | ActivityManagerService.STOCK_PM_FLAGS, userId);
                    } catch (RemoteException e) {
                        // Will never happen.
                        continue;
                    }
                    if (ai == null) {
                        ai = NO_ACTIVITY_INFO_TOKEN;
                    }
                    mTmpAvailActCache.put(task.realActivity, ai);
                }
                if (ai == NO_ACTIVITY_INFO_TOKEN) {
                    // This could be either because the activity no longer exists, or the
                    // app is temporarily gone. For the former we want to remove the recents
                    // entry; for the latter we want to mark it as unavailable.
                    ApplicationInfo app = mTmpAvailAppCache
                            .get(task.realActivity.getPackageName());
                    if (app == null) {
                        try {
                            app = pm.getApplicationInfo(task.realActivity.getPackageName(),
                                    PackageManager.MATCH_UNINSTALLED_PACKAGES, userId);
                        } catch (RemoteException e) {
                            // Will never happen.
                            continue;
                        }
                        if (app == null) {
                            app = NO_APPLICATION_INFO_TOKEN;
                        }
                        mTmpAvailAppCache.put(task.realActivity.getPackageName(), app);
                    }
                    if (app == NO_APPLICATION_INFO_TOKEN
                            || (app.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
                        // Doesn't exist any more! Good-bye.
                        remove(task);
                        Slog.w(TAG, "Removing no longer valid recent: " + task);
                        continue;
                    } else {
                        // Otherwise just not available for now.
                        if (DEBUG_RECENTS && task.isAvailable) Slog.d(TAG_RECENTS,
                                "Making recent unavailable: " + task);
                        task.isAvailable = false;
                    }
                } else {
                    if (!ai.enabled || !ai.applicationInfo.enabled
                            || (ai.applicationInfo.flags
                                    & ApplicationInfo.FLAG_INSTALLED) == 0) {
                        if (DEBUG_RECENTS && task.isAvailable) Slog.d(TAG_RECENTS,
                                "Making recent unavailable: " + task
                                        + " (enabled=" + ai.enabled + "/"
                                        + ai.applicationInfo.enabled
                                        + " flags="
                                        + Integer.toHexString(ai.applicationInfo.flags)
                                        + ")");
                        task.isAvailable = false;
                    } else {
                        if (DEBUG_RECENTS && !task.isAvailable) Slog.d(TAG_RECENTS,
                                "Making recent available: " + task);
                        task.isAvailable = true;
                    }
                }
            }
        }

        // Verify the affiliate chain for each task.
        int i = 0;
        recentsCount = mTasks.size();
        while (i < recentsCount) {
            i = processNextAffiliateChainLocked(i);
        }
        // recent tasks are now in sorted, affiliated order.
    }

    /**
     * @return whether the given {@param task} can be added to the list without causing another
     * task to be trimmed as a result of that add.
     */
    private boolean canAddTaskWithoutTrim(Task task) {
        return findRemoveIndexForAddTask(task) == -1;
    }

    /**
     * Returns the list of {@link ActivityManager.AppTask}s.
     */
    ArrayList<IBinder> getAppTasksList(int callingUid, String callingPackage) {
        final ArrayList<IBinder> list = new ArrayList<>();
        final int size = mTasks.size();
        for (int i = 0; i < size; i++) {
            final Task task = mTasks.get(i);
            // Skip tasks that do not match the caller.  We don't need to verify
            // callingPackage, because we are also limiting to callingUid and know
            // that will limit to the correct security sandbox.
            if (task.effectiveUid != callingUid) {
                continue;
            }
            Intent intent = task.getBaseIntent();
            if (intent == null || !callingPackage.equals(intent.getComponent().getPackageName())) {
                continue;
            }
            AppTaskImpl taskImpl = new AppTaskImpl(mService, task.mTaskId, callingUid);
            list.add(taskImpl.asBinder());
        }
        return list;
    }

    @VisibleForTesting
    Set<Integer> getProfileIds(int userId) {
        Set<Integer> userIds = new ArraySet<>();
        int[] profileIds = mService.getUserManager().getProfileIds(userId, false /* enabledOnly */);
        for (int i = 0; i < profileIds.length; i++) {
            userIds.add(Integer.valueOf(profileIds[i]));
        }
        return userIds;
    }

    @VisibleForTesting
    UserInfo getUserInfo(int userId) {
        return mService.getUserManager().getUserInfo(userId);
    }

    @VisibleForTesting
    int[] getCurrentProfileIds() {
        return mService.mAmInternal.getCurrentProfileIds();
    }

    @VisibleForTesting
    boolean isUserRunning(int userId, int flags) {
        return mService.mAmInternal.isUserRunning(userId, flags);
    }

    /**
     * @return the list of recent tasks for presentation.
     */
    ParceledListSlice<ActivityManager.RecentTaskInfo> getRecentTasks(int maxNum, int flags,
            boolean getTasksAllowed, int userId, int callingUid) {
        return new ParceledListSlice<>(getRecentTasksImpl(maxNum, flags, getTasksAllowed,
                userId, callingUid));
    }

    /**
     * @return the list of recent tasks for presentation.
     */
    private ArrayList<ActivityManager.RecentTaskInfo> getRecentTasksImpl(int maxNum, int flags,
            boolean getTasksAllowed, int userId, int callingUid) {
        final boolean withExcluded = (flags & RECENT_WITH_EXCLUDED) != 0;

        if (!isUserRunning(userId, FLAG_AND_UNLOCKED)) {
            Slog.i(TAG, "user " + userId + " is still locked. Cannot load recents");
            return new ArrayList<>();
        }
        loadUserRecentsLocked(userId);

        final Set<Integer> includedUsers = getProfileIds(userId);
        includedUsers.add(Integer.valueOf(userId));

        final ArrayList<ActivityManager.RecentTaskInfo> res = new ArrayList<>();
        final int size = mTasks.size();
        int numVisibleTasks = 0;
        for (int i = 0; i < size; i++) {
            final Task task = mTasks.get(i);

            if (isVisibleRecentTask(task)) {
                numVisibleTasks++;
                if (isInVisibleRange(task, i, numVisibleTasks, withExcluded)) {
                    // Fall through
                } else {
                    // Not in visible range
                    continue;
                }
            } else {
                // Not visible
                continue;
            }

            // Skip remaining tasks once we reach the requested size
            if (res.size() >= maxNum) {
                continue;
            }

            // Only add calling user or related users recent tasks
            if (!includedUsers.contains(Integer.valueOf(task.mUserId))) {
                if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "Skipping, not user: " + task);
                continue;
            }

            if (task.realActivitySuspended) {
                if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "Skipping, activity suspended: " + task);
                continue;
            }

            if (!getTasksAllowed) {
                // If the caller doesn't have the GET_TASKS permission, then only
                // allow them to see a small subset of tasks -- their own and home.
                if (!task.isActivityTypeHome() && task.effectiveUid != callingUid) {
                    if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "Skipping, not allowed: " + task);
                    continue;
                }
            }

            if (task.autoRemoveRecents && task.getTopNonFinishingActivity() == null) {
                // Don't include auto remove tasks that are finished or finishing.
                if (DEBUG_RECENTS) {
                    Slog.d(TAG_RECENTS, "Skipping, auto-remove without activity: " + task);
                }
                continue;
            }
            if ((flags & RECENT_IGNORE_UNAVAILABLE) != 0 && !task.isAvailable) {
                if (DEBUG_RECENTS) {
                    Slog.d(TAG_RECENTS, "Skipping, unavail real act: " + task);
                }
                continue;
            }

            if (!task.mUserSetupComplete) {
                // Don't include task launched while user is not done setting-up.
                if (DEBUG_RECENTS) {
                    Slog.d(TAG_RECENTS, "Skipping, user setup not complete: " + task);
                }
                continue;
            }

            res.add(createRecentTaskInfo(task, true /* stripExtras */, getTasksAllowed));
        }
        return res;
    }

    /**
     * @return the list of persistable task ids.
     */
    void getPersistableTaskIds(ArraySet<Integer> persistentTaskIds) {
        final int size = mTasks.size();
        for (int i = 0; i < size; i++) {
            final Task task = mTasks.get(i);
            if (TaskPersister.DEBUG) Slog.d(TAG, "LazyTaskWriter: task=" + task
                    + " persistable=" + task.isPersistable);
            final ActivityStack stack = task.getStack();
            if ((task.isPersistable || task.inRecents)
                    && (stack == null || !stack.isHomeOrRecentsStack())) {
                if (TaskPersister.DEBUG) Slog.d(TAG, "adding to persistentTaskIds task=" + task);
                persistentTaskIds.add(task.mTaskId);
            } else {
                if (TaskPersister.DEBUG) Slog.d(TAG, "omitting from persistentTaskIds task="
                        + task);
            }
        }
    }

    @VisibleForTesting
    ArrayList<Task> getRawTasks() {
        return mTasks;
    }

    /**
     * @return ids of tasks that are presented in Recents UI.
     */
    SparseBooleanArray getRecentTaskIds() {
        final SparseBooleanArray res = new SparseBooleanArray();
        final int size = mTasks.size();
        int numVisibleTasks = 0;
        for (int i = 0; i < size; i++) {
            final Task task = mTasks.get(i);
            if (isVisibleRecentTask(task)) {
                numVisibleTasks++;
                if (isInVisibleRange(task, i, numVisibleTasks, false /* skipExcludedCheck */)) {
                    res.put(task.mTaskId, true);
                }
            }
        }
        return res;
    }

    /**
     * @return the task in the task list with the given {@param id} if one exists.
     */
    Task getTask(int id) {
        final int recentsCount = mTasks.size();
        for (int i = 0; i < recentsCount; i++) {
            Task task = mTasks.get(i);
            if (task.mTaskId == id) {
                return task;
            }
        }
        return null;
    }

    /**
     * Add a new task to the recent tasks list.
     */
    void add(Task task) {
        if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG, "add: task=" + task);

        // Only allow trimming task if it is not updating visibility for activities, so the caller
        // doesn't need to handle unexpected size and index when looping task containers.
        final boolean canTrimTask = !mSupervisor.inActivityVisibilityUpdate();

        // Clean up the hidden tasks when going to home because the user may not be unable to return
        // to the task from recents.
        if (canTrimTask && !mHiddenTasks.isEmpty() && task.isActivityTypeHome()) {
            removeUnreachableHiddenTasks(task.getWindowingMode());
        }

        final boolean isAffiliated = task.mAffiliatedTaskId != task.mTaskId
                || task.mNextAffiliateTaskId != INVALID_TASK_ID
                || task.mPrevAffiliateTaskId != INVALID_TASK_ID;

        int recentsCount = mTasks.size();
        // Quick case: never add voice sessions.
        // TODO: VI what about if it's just an activity?
        // Probably nothing to do here
        if (task.voiceSession != null) {
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS,
                    "addRecent: not adding voice interaction " + task);
            return;
        }
        // Another quick case: check if the top-most recent task is the same.
        if (!isAffiliated && recentsCount > 0 && mTasks.get(0) == task) {
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: already at top: " + task);
            return;
        }
        // Another quick case: check if this is part of a set of affiliated
        // tasks that are at the top.
        if (isAffiliated && recentsCount > 0 && task.inRecents
                && task.mAffiliatedTaskId == mTasks.get(0).mAffiliatedTaskId) {
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: affiliated " + mTasks.get(0)
                    + " at top when adding " + task);
            return;
        }

        boolean needAffiliationFix = false;

        // Slightly less quick case: the task is already in recents, so all we need
        // to do is move it.
        if (task.inRecents) {
            int taskIndex = mTasks.indexOf(task);
            if (taskIndex >= 0) {
                if (!isAffiliated) {
                    if (!mFreezeTaskListReordering) {
                        // Simple case: this is not an affiliated task, so we just move it to the
                        // front unless overridden by the provided activity options
                        mTasks.remove(taskIndex);
                        mTasks.add(0, task);

                        if (DEBUG_RECENTS) {
                            Slog.d(TAG_RECENTS, "addRecent: moving to top " + task
                                    + " from " + taskIndex);
                        }
                    }
                    notifyTaskPersisterLocked(task, false);
                    return;
                }
            } else {
                Slog.wtf(TAG, "Task with inRecent not in recents: " + task);
                needAffiliationFix = true;
            }
        }

        if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: trimming tasks for " + task);
        removeForAddTask(task);

        task.inRecents = true;
        if (!isAffiliated || needAffiliationFix) {
            // If this is a simple non-affiliated task, or we had some failure trying to
            // handle it as part of an affilated task, then just place it at the top.
            mTasks.add(0, task);
            notifyTaskAdded(task);
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: adding " + task);
        } else if (isAffiliated) {
            // If this is a new affiliated task, then move all of the affiliated tasks
            // to the front and insert this new one.
            Task other = task.mNextAffiliate;
            if (other == null) {
                other = task.mPrevAffiliate;
            }
            if (other != null) {
                int otherIndex = mTasks.indexOf(other);
                if (otherIndex >= 0) {
                    // Insert new task at appropriate location.
                    int taskIndex;
                    if (other == task.mNextAffiliate) {
                        // We found the index of our next affiliation, which is who is
                        // before us in the list, so add after that point.
                        taskIndex = otherIndex+1;
                    } else {
                        // We found the index of our previous affiliation, which is who is
                        // after us in the list, so add at their position.
                        taskIndex = otherIndex;
                    }
                    if (DEBUG_RECENTS) Slog.d(TAG_RECENTS,
                            "addRecent: new affiliated task added at " + taskIndex + ": " + task);
                    mTasks.add(taskIndex, task);
                    notifyTaskAdded(task);

                    // Now move everything to the front.
                    if (moveAffiliatedTasksToFront(task, taskIndex)) {
                        // All went well.
                        return;
                    }

                    // Uh oh...  something bad in the affiliation chain, try to rebuild
                    // everything and then go through our general path of adding a new task.
                    needAffiliationFix = true;
                } else {
                    if (DEBUG_RECENTS) Slog.d(TAG_RECENTS,
                            "addRecent: couldn't find other affiliation " + other);
                    needAffiliationFix = true;
                }
            } else {
                if (DEBUG_RECENTS) Slog.d(TAG_RECENTS,
                        "addRecent: adding affiliated task without next/prev:" + task);
                needAffiliationFix = true;
            }
        }

        if (needAffiliationFix) {
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: regrouping affiliations");
            cleanupLocked(task.mUserId);
        }

        // Trim the set of tasks to the active set
        if (canTrimTask) {
            trimInactiveRecentTasks();
        }
        notifyTaskPersisterLocked(task, false /* flush */);
    }

    /**
     * Add the task to the bottom if possible.
     */
    boolean addToBottom(Task task) {
        if (!canAddTaskWithoutTrim(task)) {
            // Adding this task would cause the task to be removed (since it's appended at
            // the bottom and would be trimmed) so just return now
            return false;
        }

        add(task);
        return true;
    }

    /**
     * Remove a task from the recent tasks list.
     */
    void remove(Task task) {
        mTasks.remove(task);
        notifyTaskRemoved(task, false /* wasTrimmed */, false /* killProcess */);
    }

    /**
     * Trims the recents task list to the global max number of recents.
     */
    private void trimInactiveRecentTasks() {
        if (mFreezeTaskListReordering) {
            // Defer trimming inactive recent tasks until we are unfrozen
            return;
        }

        int recentsCount = mTasks.size();

        // Remove from the end of the list until we reach the max number of recents
        while (recentsCount > mGlobalMaxNumTasks) {
            final Task task = mTasks.remove(recentsCount - 1);
            notifyTaskRemoved(task, true /* wasTrimmed */, false /* killProcess */);
            recentsCount--;
            if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG, "Trimming over max-recents task=" + task
                    + " max=" + mGlobalMaxNumTasks);
        }

        // Remove any tasks that belong to currently quiet profiles
        final int[] profileUserIds = getCurrentProfileIds();
        mTmpQuietProfileUserIds.clear();
        for (int userId : profileUserIds) {
            final UserInfo userInfo = getUserInfo(userId);
            if (userInfo != null && userInfo.isManagedProfile() && userInfo.isQuietModeEnabled()) {
                mTmpQuietProfileUserIds.put(userId, true);
            }
            if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG, "User: " + userInfo
                    + " quiet=" + mTmpQuietProfileUserIds.get(userId));
        }

        // Remove any inactive tasks, calculate the latest set of visible tasks.
        int numVisibleTasks = 0;
        for (int i = 0; i < mTasks.size();) {
            final Task task = mTasks.get(i);

            if (isActiveRecentTask(task, mTmpQuietProfileUserIds)) {
                if (!mHasVisibleRecentTasks) {
                    // Keep all active tasks if visible recent tasks is not supported
                    i++;
                    continue;
                }

                if (!isVisibleRecentTask(task)) {
                    // Keep all active-but-invisible tasks
                    i++;
                    continue;
                } else {
                    numVisibleTasks++;
                    if (isInVisibleRange(task, i, numVisibleTasks, false /* skipExcludedCheck */)
                            || !isTrimmable(task)) {
                        // Keep visible tasks in range
                        i++;
                        continue;
                    } else {
                        // Fall through to trim visible tasks that are no longer in range and
                        // trimmable
                        if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG,
                                "Trimming out-of-range visible task=" + task);
                    }
                }
            } else {
                // Fall through to trim inactive tasks
                if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG, "Trimming inactive task=" + task);
            }

            // Task is no longer active, trim it from the list
            mTasks.remove(task);
            notifyTaskRemoved(task, true /* wasTrimmed */, false /* killProcess */);
            notifyTaskPersisterLocked(task, false /* flush */);
        }
    }

    /**
     * @return whether the given task should be considered active.
     */
    private boolean isActiveRecentTask(Task task, SparseBooleanArray quietProfileUserIds) {
        if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG, "isActiveRecentTask: task=" + task
                + " globalMax=" + mGlobalMaxNumTasks);

        if (quietProfileUserIds.get(task.mUserId)) {
            // Quiet profile user's tasks are never active
            if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG, "\tisQuietProfileTask=true");
            return false;
        }

        if (task.mAffiliatedTaskId != INVALID_TASK_ID && task.mAffiliatedTaskId != task.mTaskId) {
            // Keep the task active if its affiliated task is also active
            final Task affiliatedTask = getTask(task.mAffiliatedTaskId);
            if (affiliatedTask != null) {
                if (!isActiveRecentTask(affiliatedTask, quietProfileUserIds)) {
                    if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG,
                            "\taffiliatedWithTask=" + affiliatedTask + " is not active");
                    return false;
                }
            }
        }

        // All other tasks are considered active
        return true;
    }

    /**
     * @return whether the given active task should be presented to the user through SystemUI.
     */
    @VisibleForTesting
    boolean isVisibleRecentTask(Task task) {
        if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG, "isVisibleRecentTask: task=" + task
                + " minVis=" + mMinNumVisibleTasks + " maxVis=" + mMaxNumVisibleTasks
                + " sessionDuration=" + mActiveTasksSessionDurationMs
                + " inactiveDuration=" + task.getInactiveDuration()
                + " activityType=" + task.getActivityType()
                + " windowingMode=" + task.getWindowingMode()
                + " intentFlags=" + task.getBaseIntent().getFlags());

        switch (task.getActivityType()) {
            case ACTIVITY_TYPE_HOME:
            case ACTIVITY_TYPE_RECENTS:
            case ACTIVITY_TYPE_DREAM:
                // Ignore certain activity types completely
                return false;
            case ACTIVITY_TYPE_ASSISTANT:
                // Ignore assistant that chose to be excluded from Recents, even if it's a top
                // task.
                if ((task.getBaseIntent().getFlags() & FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                        == FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS) {
                    return false;
                }
                break;
        }

        // Ignore certain windowing modes
        switch (task.getWindowingMode()) {
            case WINDOWING_MODE_PINNED:
                return false;
            case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
                if (DEBUG_RECENTS_TRIM_TASKS) {
                    Slog.d(TAG, "\ttop=" + task.getStack().getTopMostTask());
                }
                final ActivityStack stack = task.getStack();
                if (stack != null && stack.getTopMostTask() == task) {
                    // Only the non-top task of the primary split screen mode is visible
                    return false;
                }
                break;
            case WINDOWING_MODE_MULTI_WINDOW:
                // Ignore tasks that are always on top
                if (task.isAlwaysOnTop()) {
                    return false;
                }
                break;
        }

        // Tasks managed by/associated with an ActivityView should be excluded from recents.
        // singleTaskInstance is set on the VirtualDisplay managed by ActivityView
        // TODO(b/126185105): Find a different signal to use besides isSingleTaskInstance
        final ActivityStack stack = task.getStack();
        if (stack != null) {
            DisplayContent display = stack.getDisplay();
            if (display != null && display.isSingleTaskInstance()) {
                return false;
            }
        }

        // If we're in lock task mode, ignore the root task
        if (task == mService.getLockTaskController().getRootTask()) {
            return false;
        }

        return true;
    }

    /**
     * @return whether the given visible task is within the policy range.
     */
    private boolean isInVisibleRange(Task task, int taskIndex, int numVisibleTasks,
            boolean skipExcludedCheck) {
        if (!skipExcludedCheck) {
            // Keep the most recent task even if it is excluded from recents
            final boolean isExcludeFromRecents =
                    (task.getBaseIntent().getFlags() & FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
                            == FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
            if (isExcludeFromRecents) {
                if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG, "\texcludeFromRecents=true");
                return taskIndex == 0;
            }
        }

        if (mMinNumVisibleTasks >= 0 && numVisibleTasks <= mMinNumVisibleTasks) {
            // Always keep up to the min number of recent tasks, after that fall through to the
            // checks below
            return true;
        }

        if (mMaxNumVisibleTasks >= 0) {
            // Always keep up to the max number of recent tasks, but return false afterwards
            return numVisibleTasks <= mMaxNumVisibleTasks;
        }

        if (mActiveTasksSessionDurationMs > 0) {
            // Keep the task if the inactive time is within the session window, this check must come
            // after the checks for the min/max visible task range
            if (task.getInactiveDuration() <= mActiveTasksSessionDurationMs) {
                return true;
            }
        }

        return false;
    }

    /** @return whether the given task can be trimmed even if it is outside the visible range. */
    protected boolean isTrimmable(Task task) {
        final ActivityStack stack = task.getStack();

        // No stack for task, just trim it
        if (stack == null) {
            return true;
        }

        // Ignore tasks from different displays
        // TODO (b/115289124): No Recents on non-default displays.
        if (!stack.isOnHomeDisplay()) {
            return false;
        }

        final ActivityStack rootHomeTask = stack.getDisplayArea().getRootHomeTask();
        // Home stack does not exist. Don't trim the task.
        if (rootHomeTask == null) {
            return false;
        }
        // Trim tasks that are behind the home task.
        return task.compareTo(rootHomeTask) < 0;
    }

    /** Remove the tasks that user may not be able to return. */
    private void removeUnreachableHiddenTasks(int windowingMode) {
        for (int i = mHiddenTasks.size() - 1; i >= 0; i--) {
            final Task hiddenTask = mHiddenTasks.get(i);
            if (!hiddenTask.hasChild() || hiddenTask.inRecents) {
                // The task was removed by other path or it became reachable (added to recents).
                mHiddenTasks.remove(i);
                continue;
            }
            if (hiddenTask.getWindowingMode() != windowingMode
                    || hiddenTask.getTopVisibleActivity() != null) {
                // The task may be reachable from the back stack of other windowing mode or it is
                // currently in use. Keep the task in the hidden list to avoid losing track, e.g.
                // after dismissing primary split screen.
                continue;
            }
            mHiddenTasks.remove(i);
            mSupervisor.removeTask(hiddenTask, false /* killProcess */,
                    !REMOVE_FROM_RECENTS, "remove-hidden-task");
        }
    }

    /**
     * If needed, remove oldest existing entries in recents that are for the same kind
     * of task as the given one.
     */
    private void removeForAddTask(Task task) {
        // The adding task will be in recents so it is not hidden.
        mHiddenTasks.remove(task);

        final int removeIndex = findRemoveIndexForAddTask(task);
        if (removeIndex == -1) {
            // Nothing to trim
            return;
        }

        // There is a similar task that will be removed for the addition of {@param task}, but it
        // can be the same task, and if so, the task will be re-added in add(), so skip the
        // callbacks here.
        final Task removedTask = mTasks.remove(removeIndex);
        if (removedTask != task) {
            if (removedTask.hasChild()) {
                // A non-empty task is replaced by a new task. Because the removed task is no longer
                // managed by the recent tasks list, add it to the hidden list to prevent the task
                // from becoming dangling.
                mHiddenTasks.add(removedTask);
            }
            notifyTaskRemoved(removedTask, false /* wasTrimmed */, false /* killProcess */);
            if (DEBUG_RECENTS_TRIM_TASKS) Slog.d(TAG, "Trimming task=" + removedTask
                    + " for addition of task=" + task);
        }
        notifyTaskPersisterLocked(removedTask, false /* flush */);
    }

    /**
     * Find the task that would be removed if the given {@param task} is added to the recent tasks
     * list (if any).
     */
    private int findRemoveIndexForAddTask(Task task) {
        if (mFreezeTaskListReordering) {
            // Defer removing tasks due to the addition of new tasks until the task list is unfrozen
            return -1;
        }

        final int recentsCount = mTasks.size();
        final Intent intent = task.intent;
        final boolean document = intent != null && intent.isDocument();
        int maxRecents = task.maxRecents - 1;
        for (int i = 0; i < recentsCount; i++) {
            final Task t = mTasks.get(i);
            if (task != t) {
                if (!hasCompatibleActivityTypeAndWindowingMode(task, t)
                        || task.mUserId != t.mUserId) {
                    continue;
                }
                final Intent trIntent = t.intent;
                final boolean sameAffinity =
                        task.affinity != null && task.affinity.equals(t.affinity);
                final boolean sameIntent = intent != null && intent.filterEquals(trIntent);
                boolean multiTasksAllowed = false;
                final int flags = intent.getFlags();
                if ((flags & (FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_NEW_DOCUMENT)) != 0
                        && (flags & FLAG_ACTIVITY_MULTIPLE_TASK) != 0) {
                    multiTasksAllowed = true;
                }
                final boolean trIsDocument = trIntent != null && trIntent.isDocument();
                final boolean bothDocuments = document && trIsDocument;
                if (!sameAffinity && !sameIntent && !bothDocuments) {
                    continue;
                }

                if (bothDocuments) {
                    // Do these documents belong to the same activity?
                    final boolean sameActivity = task.realActivity != null
                            && t.realActivity != null
                            && task.realActivity.equals(t.realActivity);
                    if (!sameActivity) {
                        // If the document is open in another app or is not the same document, we
                        // don't need to trim it.
                        continue;
                    } else if (maxRecents > 0) {
                        --maxRecents;
                        if (!sameIntent || multiTasksAllowed) {
                            // We don't want to trim if we are not over the max allowed entries and
                            // the tasks are not of the same intent filter, or multiple entries for
                            // the task is allowed.
                            continue;
                        }
                    }
                    // Hit the maximum number of documents for this task. Fall through
                    // and remove this document from recents.
                } else if (document || trIsDocument) {
                    // Only one of these is a document. Not the droid we're looking for.
                    continue;
                }
            }
            return i;
        }
        return -1;
    }

    // Extract the affiliates of the chain containing recent at index start.
    private int processNextAffiliateChainLocked(int start) {
        final Task startTask = mTasks.get(start);
        final int affiliateId = startTask.mAffiliatedTaskId;

        // Quick identification of isolated tasks. I.e. those not launched behind.
        if (startTask.mTaskId == affiliateId && startTask.mPrevAffiliate == null &&
                startTask.mNextAffiliate == null) {
            // There is still a slim chance that there are other tasks that point to this task
            // and that the chain is so messed up that this task no longer points to them but
            // the gain of this optimization outweighs the risk.
            startTask.inRecents = true;
            return start + 1;
        }

        // Remove all tasks that are affiliated to affiliateId and put them in mTmpRecents.
        mTmpRecents.clear();
        for (int i = mTasks.size() - 1; i >= start; --i) {
            final Task task = mTasks.get(i);
            if (task.mAffiliatedTaskId == affiliateId) {
                mTasks.remove(i);
                mTmpRecents.add(task);
            }
        }

        // Sort them all by taskId. That is the order they were create in and that order will
        // always be correct.
        Collections.sort(mTmpRecents, TASK_ID_COMPARATOR);

        // Go through and fix up the linked list.
        // The first one is the end of the chain and has no next.
        final Task first = mTmpRecents.get(0);
        first.inRecents = true;
        if (first.mNextAffiliate != null) {
            Slog.w(TAG, "Link error 1 first.next=" + first.mNextAffiliate);
            first.setNextAffiliate(null);
            notifyTaskPersisterLocked(first, false);
        }
        // Everything in the middle is doubly linked from next to prev.
        final int tmpSize = mTmpRecents.size();
        for (int i = 0; i < tmpSize - 1; ++i) {
            final Task next = mTmpRecents.get(i);
            final Task prev = mTmpRecents.get(i + 1);
            if (next.mPrevAffiliate != prev) {
                Slog.w(TAG, "Link error 2 next=" + next + " prev=" + next.mPrevAffiliate +
                        " setting prev=" + prev);
                next.setPrevAffiliate(prev);
                notifyTaskPersisterLocked(next, false);
            }
            if (prev.mNextAffiliate != next) {
                Slog.w(TAG, "Link error 3 prev=" + prev + " next=" + prev.mNextAffiliate +
                        " setting next=" + next);
                prev.setNextAffiliate(next);
                notifyTaskPersisterLocked(prev, false);
            }
            prev.inRecents = true;
        }
        // The last one is the beginning of the list and has no prev.
        final Task last = mTmpRecents.get(tmpSize - 1);
        if (last.mPrevAffiliate != null) {
            Slog.w(TAG, "Link error 4 last.prev=" + last.mPrevAffiliate);
            last.setPrevAffiliate(null);
            notifyTaskPersisterLocked(last, false);
        }

        // Insert the group back into mTmpTasks at start.
        mTasks.addAll(start, mTmpRecents);
        mTmpRecents.clear();

        // Let the caller know where we left off.
        return start + tmpSize;
    }

    private boolean moveAffiliatedTasksToFront(Task task, int taskIndex) {
        int recentsCount = mTasks.size();
        Task top = task;
        int topIndex = taskIndex;
        while (top.mNextAffiliate != null && topIndex > 0) {
            top = top.mNextAffiliate;
            topIndex--;
        }
        if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: adding affilliates starting at "
                + topIndex + " from intial " + taskIndex);
        // Find the end of the chain, doing a sanity check along the way.
        boolean sane = top.mAffiliatedTaskId == task.mAffiliatedTaskId;
        int endIndex = topIndex;
        Task prev = top;
        while (endIndex < recentsCount) {
            Task cur = mTasks.get(endIndex);
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: looking at next chain @"
                    + endIndex + " " + cur);
            if (cur == top) {
                // Verify start of the chain.
                if (cur.mNextAffiliate != null || cur.mNextAffiliateTaskId != INVALID_TASK_ID) {
                    Slog.wtf(TAG, "Bad chain @" + endIndex
                            + ": first task has next affiliate: " + prev);
                    sane = false;
                    break;
                }
            } else {
                // Verify middle of the chain's next points back to the one before.
                if (cur.mNextAffiliate != prev
                        || cur.mNextAffiliateTaskId != prev.mTaskId) {
                    Slog.wtf(TAG, "Bad chain @" + endIndex
                            + ": middle task " + cur + " @" + endIndex
                            + " has bad next affiliate "
                            + cur.mNextAffiliate + " id " + cur.mNextAffiliateTaskId
                            + ", expected " + prev);
                    sane = false;
                    break;
                }
            }
            if (cur.mPrevAffiliateTaskId == INVALID_TASK_ID) {
                // Chain ends here.
                if (cur.mPrevAffiliate != null) {
                    Slog.wtf(TAG, "Bad chain @" + endIndex
                            + ": last task " + cur + " has previous affiliate "
                            + cur.mPrevAffiliate);
                    sane = false;
                }
                if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: end of chain @" + endIndex);
                break;
            } else {
                // Verify middle of the chain's prev points to a valid item.
                if (cur.mPrevAffiliate == null) {
                    Slog.wtf(TAG, "Bad chain @" + endIndex
                            + ": task " + cur + " has previous affiliate "
                            + cur.mPrevAffiliate + " but should be id "
                            + cur.mPrevAffiliate);
                    sane = false;
                    break;
                }
            }
            if (cur.mAffiliatedTaskId != task.mAffiliatedTaskId) {
                Slog.wtf(TAG, "Bad chain @" + endIndex
                        + ": task " + cur + " has affiliated id "
                        + cur.mAffiliatedTaskId + " but should be "
                        + task.mAffiliatedTaskId);
                sane = false;
                break;
            }
            prev = cur;
            endIndex++;
            if (endIndex >= recentsCount) {
                Slog.wtf(TAG, "Bad chain ran off index " + endIndex
                        + ": last task " + prev);
                sane = false;
                break;
            }
        }
        if (sane) {
            if (endIndex < taskIndex) {
                Slog.wtf(TAG, "Bad chain @" + endIndex
                        + ": did not extend to task " + task + " @" + taskIndex);
                sane = false;
            }
        }
        if (sane) {
            // All looks good, we can just move all of the affiliated tasks
            // to the top.
            for (int i=topIndex; i<=endIndex; i++) {
                if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: moving affiliated " + task
                        + " from " + i + " to " + (i-topIndex));
                Task cur = mTasks.remove(i);
                mTasks.add(i - topIndex, cur);
            }
            if (DEBUG_RECENTS) Slog.d(TAG_RECENTS, "addRecent: done moving tasks  " +  topIndex
                    + " to " + endIndex);
            return true;
        }

        // Whoops, couldn't do it.
        return false;
    }

    void dump(PrintWriter pw, boolean dumpAll, String dumpPackage) {
        pw.println("ACTIVITY MANAGER RECENT TASKS (dumpsys activity recents)");
        pw.println("mRecentsUid=" + mRecentsUid);
        pw.println("mRecentsComponent=" + mRecentsComponent);
        pw.println("mFreezeTaskListReordering=" + mFreezeTaskListReordering);
        pw.println("mFreezeTaskListReorderingPendingTimeout="
                + mService.mH.hasCallbacks(mResetFreezeTaskListOnTimeoutRunnable));
        if (!mHiddenTasks.isEmpty()) {
            pw.println("mHiddenTasks=" + mHiddenTasks);
        }
        if (mTasks.isEmpty()) {
            return;
        }

        // Dump raw recent task list
        boolean printedAnything = false;
        boolean printedHeader = false;
        final int size = mTasks.size();
        for (int i = 0; i < size; i++) {
            final Task task = mTasks.get(i);
            if (dumpPackage != null) {
                boolean match = task.intent != null
                        && task.intent.getComponent() != null
                        && dumpPackage.equals(
                        task.intent.getComponent().getPackageName());
                if (!match) {
                    match |= task.affinityIntent != null
                            && task.affinityIntent.getComponent() != null
                            && dumpPackage.equals(
                            task.affinityIntent.getComponent().getPackageName());
                }
                if (!match) {
                    match |= task.origActivity != null
                            && dumpPackage.equals(task.origActivity.getPackageName());
                }
                if (!match) {
                    match |= task.realActivity != null
                            && dumpPackage.equals(task.realActivity.getPackageName());
                }
                if (!match) {
                    match |= dumpPackage.equals(task.mCallingPackage);
                }
                if (!match) {
                    continue;
                }
            }

            if (!printedHeader) {
                pw.println("  Recent tasks:");
                printedHeader = true;
                printedAnything = true;
            }
            pw.print("  * Recent #"); pw.print(i); pw.print(": ");
            pw.println(task);
            if (dumpAll) {
                task.dump(pw, "    ");
            }
        }

        // Dump visible recent task list
        if (mHasVisibleRecentTasks) {
            // Reset the header flag for the next block
            printedHeader = false;
            ArrayList<ActivityManager.RecentTaskInfo> tasks = getRecentTasksImpl(Integer.MAX_VALUE,
                    0, true /* getTasksAllowed */, mService.getCurrentUserId(), SYSTEM_UID);
            for (int i = 0; i < tasks.size(); i++) {
                final ActivityManager.RecentTaskInfo taskInfo = tasks.get(i);
                if (dumpPackage != null) {
                    boolean match = taskInfo.baseIntent != null
                            && taskInfo.baseIntent.getComponent() != null
                            && dumpPackage.equals(
                                    taskInfo.baseIntent.getComponent().getPackageName());
                    if (!match) {
                        match |= taskInfo.baseActivity != null
                                && dumpPackage.equals(taskInfo.baseActivity.getPackageName());
                    }
                    if (!match) {
                        match |= taskInfo.topActivity != null
                                && dumpPackage.equals(taskInfo.topActivity.getPackageName());
                    }
                    if (!match) {
                        match |= taskInfo.origActivity != null
                                && dumpPackage.equals(taskInfo.origActivity.getPackageName());
                    }
                    if (!match) {
                        match |= taskInfo.realActivity != null
                                && dumpPackage.equals(taskInfo.realActivity.getPackageName());
                    }
                    if (!match) {
                        continue;
                    }
                }
                if (!printedHeader) {
                    if (printedAnything) {
                        // Separate from the last block if it printed
                        pw.println();
                    }
                    pw.println("  Visible recent tasks (most recent first):");
                    printedHeader = true;
                    printedAnything = true;
                }

                pw.print("  * RecentTaskInfo #"); pw.print(i); pw.print(": ");
                taskInfo.dump(pw, "    ");
            }
        }

        if (!printedAnything) {
            pw.println("  (nothing)");
        }
    }

    /**
     * Creates a new RecentTaskInfo from a Task.
     */
    ActivityManager.RecentTaskInfo createRecentTaskInfo(Task tr, boolean stripExtras,
            boolean getTasksAllowed) {
        ActivityManager.RecentTaskInfo rti = new ActivityManager.RecentTaskInfo();
        tr.fillTaskInfo(rti, stripExtras);
        // Fill in some deprecated values
        rti.id = rti.isRunning ? rti.taskId : INVALID_TASK_ID;
        rti.persistentId = rti.taskId;
        if (!getTasksAllowed) {
            Task.trimIneffectiveInfo(tr, rti);
        }
        return rti;
    }

    /**
     * @return Whether the activity types and windowing modes of the two tasks are considered
     *         compatible. This is necessary because we currently don't persist the activity type
     *         or the windowing mode with the task, so they can be undefined when restored.
     */
    private boolean hasCompatibleActivityTypeAndWindowingMode(Task t1, Task t2) {
        final int activityType = t1.getActivityType();
        final int windowingMode = t1.getWindowingMode();
        final boolean isUndefinedType = activityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean isUndefinedMode = windowingMode == WINDOWING_MODE_UNDEFINED;
        final int otherActivityType = t2.getActivityType();
        final int otherWindowingMode = t2.getWindowingMode();
        final boolean isOtherUndefinedType = otherActivityType == ACTIVITY_TYPE_UNDEFINED;
        final boolean isOtherUndefinedMode = otherWindowingMode == WINDOWING_MODE_UNDEFINED;

        // An activity type and windowing mode is compatible if they are the exact same type/mode,
        // or if one of the type/modes is undefined
        final boolean isCompatibleType = activityType == otherActivityType
                || isUndefinedType || isOtherUndefinedType;
        final boolean isCompatibleMode = windowingMode == otherWindowingMode
                || isUndefinedMode || isOtherUndefinedMode;

        return isCompatibleType && isCompatibleMode;
    }
}
