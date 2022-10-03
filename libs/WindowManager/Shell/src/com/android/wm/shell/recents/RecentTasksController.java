/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.wm.shell.recents;

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.content.pm.PackageManager.FEATURE_PC;

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.annotation.BinderThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.RemoteCallable;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SingleInstanceRemoteListener;
import com.android.wm.shell.common.TaskStackListenerCallback;
import com.android.wm.shell.common.TaskStackListenerImpl;
import com.android.wm.shell.common.annotations.ExternalThread;
import com.android.wm.shell.common.annotations.ShellMainThread;
import com.android.wm.shell.desktopmode.DesktopModeStatus;
import com.android.wm.shell.desktopmode.DesktopModeTaskRepository;
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.sysui.ShellCommandHandler;
import com.android.wm.shell.sysui.ShellInit;
import com.android.wm.shell.util.GroupedRecentTaskInfo;
import com.android.wm.shell.util.SplitBounds;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Manages the recent task list from the system, caching it as necessary.
 */
public class RecentTasksController implements TaskStackListenerCallback,
        RemoteCallable<RecentTasksController>, DesktopModeTaskRepository.Listener {
    private static final String TAG = RecentTasksController.class.getSimpleName();

    private final Context mContext;
    private final ShellCommandHandler mShellCommandHandler;
    private final Optional<DesktopModeTaskRepository> mDesktopModeTaskRepository;
    private final ShellExecutor mMainExecutor;
    private final TaskStackListenerImpl mTaskStackListener;
    private final RecentTasks mImpl = new RecentTasksImpl();
    private final ActivityTaskManager mActivityTaskManager;
    private IRecentTasksListener mListener;
    private final boolean mIsDesktopMode;

    // Mapping of split task ids, mappings are symmetrical (ie. if t1 is the taskid of a task in a
    // pair, then mSplitTasks[t1] = t2, and mSplitTasks[t2] = t1)
    private final SparseIntArray mSplitTasks = new SparseIntArray();
    /**
     * Maps taskId to {@link SplitBounds} for both taskIDs.
     * Meaning there will be two taskId integers mapping to the same object.
     * If there's any ordering to the pairing than we can probably just get away with only one
     * taskID mapping to it, leaving both for consistency with {@link #mSplitTasks} for now.
     */
    private final Map<Integer, SplitBounds> mTaskSplitBoundsMap = new HashMap<>();

    /**
     * Creates {@link RecentTasksController}, returns {@code null} if the feature is not
     * supported.
     */
    @Nullable
    public static RecentTasksController create(
            Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            TaskStackListenerImpl taskStackListener,
            ActivityTaskManager activityTaskManager,
            Optional<DesktopModeTaskRepository> desktopModeTaskRepository,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        if (!context.getResources().getBoolean(com.android.internal.R.bool.config_hasRecents)) {
            return null;
        }
        return new RecentTasksController(context, shellInit, shellCommandHandler, taskStackListener,
                activityTaskManager, desktopModeTaskRepository, mainExecutor);
    }

    RecentTasksController(Context context,
            ShellInit shellInit,
            ShellCommandHandler shellCommandHandler,
            TaskStackListenerImpl taskStackListener,
            ActivityTaskManager activityTaskManager,
            Optional<DesktopModeTaskRepository> desktopModeTaskRepository,
            ShellExecutor mainExecutor) {
        mContext = context;
        mShellCommandHandler = shellCommandHandler;
        mActivityTaskManager = activityTaskManager;
        mIsDesktopMode = mContext.getPackageManager().hasSystemFeature(FEATURE_PC);
        mTaskStackListener = taskStackListener;
        mDesktopModeTaskRepository = desktopModeTaskRepository;
        mMainExecutor = mainExecutor;
        shellInit.addInitCallback(this::onInit, this);
    }

    public RecentTasks asRecentTasks() {
        return mImpl;
    }

    private void onInit() {
        mShellCommandHandler.addDumpCallback(this::dump, this);
        mTaskStackListener.addListener(this);
        mDesktopModeTaskRepository.ifPresent(it -> it.addListener(this));
    }

    /**
     * Adds a split pair. This call does not validate the taskIds, only that they are not the same.
     */
    public void addSplitPair(int taskId1, int taskId2, SplitBounds splitBounds) {
        if (taskId1 == taskId2) {
            return;
        }
        if (mSplitTasks.get(taskId1, INVALID_TASK_ID) == taskId2
                && mTaskSplitBoundsMap.get(taskId1).equals(splitBounds)) {
            // If the two tasks are already paired and the bounds are the same, then skip updating
            return;
        }
        // Remove any previous pairs
        removeSplitPair(taskId1);
        removeSplitPair(taskId2);
        mTaskSplitBoundsMap.remove(taskId1);
        mTaskSplitBoundsMap.remove(taskId2);

        mSplitTasks.put(taskId1, taskId2);
        mSplitTasks.put(taskId2, taskId1);
        mTaskSplitBoundsMap.put(taskId1, splitBounds);
        mTaskSplitBoundsMap.put(taskId2, splitBounds);
        notifyRecentTasksChanged();
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENT_TASKS, "Add split pair: %d, %d, %s",
                taskId1, taskId2, splitBounds);
    }

    /**
     * Removes a split pair.
     */
    public void removeSplitPair(int taskId) {
        int pairedTaskId = mSplitTasks.get(taskId, INVALID_TASK_ID);
        if (pairedTaskId != INVALID_TASK_ID) {
            mSplitTasks.delete(taskId);
            mSplitTasks.delete(pairedTaskId);
            mTaskSplitBoundsMap.remove(taskId);
            mTaskSplitBoundsMap.remove(pairedTaskId);
            notifyRecentTasksChanged();
            ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENT_TASKS, "Remove split pair: %d, %d",
                    taskId, pairedTaskId);
        }
    }

    @Override
    public Context getContext() {
        return mContext;
    }

    @Override
    public ShellExecutor getRemoteCallExecutor() {
        return mMainExecutor;
    }

    @Override
    public void onTaskStackChanged() {
        notifyRecentTasksChanged();
    }

    @Override
    public void onRecentTaskListUpdated() {
        // In some cases immediately after booting, the tasks in the system recent task list may be
        // loaded, but not in the active task hierarchy in the system.  These tasks are displayed in
        // overview, but removing them don't result in a onTaskStackChanged() nor a onTaskRemoved()
        // callback (those are for changes to the active tasks), but the task list is still updated,
        // so we should also invalidate the change id to ensure we load a new list instead of
        // reusing a stale list.
        notifyRecentTasksChanged();
    }

    public void onTaskAdded(ActivityManager.RunningTaskInfo taskInfo) {
        notifyRunningTaskAppeared(taskInfo);
    }

    public void onTaskRemoved(ActivityManager.RunningTaskInfo taskInfo) {
        // Remove any split pairs associated with this task
        removeSplitPair(taskInfo.taskId);
        notifyRecentTasksChanged();
        notifyRunningTaskVanished(taskInfo);
    }

    public void onTaskWindowingModeChanged(TaskInfo taskInfo) {
        notifyRecentTasksChanged();
    }

    @Override
    public void onActiveTasksChanged() {
        notifyRecentTasksChanged();
    }

    @VisibleForTesting
    void notifyRecentTasksChanged() {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENT_TASKS, "Notify recent tasks changed");
        if (mListener == null) {
            return;
        }
        try {
            mListener.onRecentTasksChanged();
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed call notifyRecentTasksChanged", e);
        }
    }

    /**
     * Notify the running task listener that a task appeared on desktop environment.
     */
    private void notifyRunningTaskAppeared(ActivityManager.RunningTaskInfo taskInfo) {
        if (mListener == null || !mIsDesktopMode || taskInfo.realActivity == null) {
            return;
        }
        try {
            mListener.onRunningTaskAppeared(taskInfo);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed call onRunningTaskAppeared", e);
        }
    }

    /**
     * Notify the running task listener that a task was removed on desktop environment.
     */
    private void notifyRunningTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
        if (mListener == null || !mIsDesktopMode || taskInfo.realActivity == null) {
            return;
        }
        try {
            mListener.onRunningTaskVanished(taskInfo);
        } catch (RemoteException e) {
            Slog.w(TAG, "Failed call onRunningTaskVanished", e);
        }
    }

    private void registerRecentTasksListener(IRecentTasksListener listener) {
        mListener = listener;
    }

    private void unregisterRecentTasksListener() {
        mListener = null;
    }

    @VisibleForTesting
    ArrayList<GroupedRecentTaskInfo> getRecentTasks(int maxNum, int flags, int userId) {
        // Note: the returned task list is from the most-recent to least-recent order
        final List<ActivityManager.RecentTaskInfo> rawList = mActivityTaskManager.getRecentTasks(
                maxNum, flags, userId);

        // Make a mapping of task id -> task info
        final SparseArray<ActivityManager.RecentTaskInfo> rawMapping = new SparseArray<>();
        for (int i = 0; i < rawList.size(); i++) {
            final ActivityManager.RecentTaskInfo taskInfo = rawList.get(i);
            rawMapping.put(taskInfo.taskId, taskInfo);
        }

        boolean desktopModeActive = DesktopModeStatus.isActive(mContext);
        ArrayList<ActivityManager.RecentTaskInfo> freeformTasks = new ArrayList<>();

        // Pull out the pairs as we iterate back in the list
        ArrayList<GroupedRecentTaskInfo> recentTasks = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            final ActivityManager.RecentTaskInfo taskInfo = rawList.get(i);
            if (!rawMapping.contains(taskInfo.taskId)) {
                // If it's not in the mapping, then it was already paired with another task
                continue;
            }

            if (desktopModeActive && mDesktopModeTaskRepository.isPresent()
                    && mDesktopModeTaskRepository.get().isActiveTask(taskInfo.taskId)) {
                // Freeform tasks will be added as a separate entry
                freeformTasks.add(taskInfo);
                continue;
            }

            final int pairedTaskId = mSplitTasks.get(taskInfo.taskId);
            if (!desktopModeActive && pairedTaskId != INVALID_TASK_ID && rawMapping.contains(
                    pairedTaskId)) {
                final ActivityManager.RecentTaskInfo pairedTaskInfo = rawMapping.get(pairedTaskId);
                rawMapping.remove(pairedTaskId);
                recentTasks.add(GroupedRecentTaskInfo.forSplitTasks(taskInfo, pairedTaskInfo,
                        mTaskSplitBoundsMap.get(pairedTaskId)));
            } else {
                recentTasks.add(GroupedRecentTaskInfo.forSingleTask(taskInfo));
            }
        }

        // Add a special entry for freeform tasks
        if (!freeformTasks.isEmpty()) {
            recentTasks.add(0, GroupedRecentTaskInfo.forFreeformTasks(
                    freeformTasks.toArray(new ActivityManager.RecentTaskInfo[0])));
        }

        return recentTasks;
    }

    /**
     * Find the background task that match the given component.
     */
    @Nullable
    public ActivityManager.RecentTaskInfo findTaskInBackground(ComponentName componentName) {
        if (componentName == null) {
            return null;
        }
        List<ActivityManager.RecentTaskInfo> tasks = mActivityTaskManager.getRecentTasks(
                Integer.MAX_VALUE, ActivityManager.RECENT_IGNORE_UNAVAILABLE,
                ActivityManager.getCurrentUser());
        for (int i = 0; i < tasks.size(); i++) {
            final ActivityManager.RecentTaskInfo task = tasks.get(i);
            if (task.isVisible) {
                continue;
            }
            if (componentName.equals(task.baseIntent.getComponent())) {
                return task;
            }
        }
        return null;
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        final String innerPrefix = prefix + "  ";
        pw.println(prefix + TAG);
        ArrayList<GroupedRecentTaskInfo> recentTasks = getRecentTasks(Integer.MAX_VALUE,
                ActivityManager.RECENT_IGNORE_UNAVAILABLE, ActivityManager.getCurrentUser());
        for (int i = 0; i < recentTasks.size(); i++) {
            pw.println(innerPrefix + recentTasks.get(i));
        }
    }

    /**
     * The interface for calls from outside the Shell, within the host process.
     */
    @ExternalThread
    private class RecentTasksImpl implements RecentTasks {
        private IRecentTasksImpl mIRecentTasks;

        @Override
        public IRecentTasks createExternalInterface() {
            if (mIRecentTasks != null) {
                mIRecentTasks.invalidate();
            }
            mIRecentTasks = new IRecentTasksImpl(RecentTasksController.this);
            return mIRecentTasks;
        }

        @Override
        public void getRecentTasks(int maxNum, int flags, int userId, Executor executor,
                Consumer<List<GroupedRecentTaskInfo>> callback) {
            mMainExecutor.execute(() -> {
                List<GroupedRecentTaskInfo> tasks =
                        RecentTasksController.this.getRecentTasks(maxNum, flags, userId);
                executor.execute(() -> callback.accept(tasks));
            });
        }
    }


    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IRecentTasksImpl extends IRecentTasks.Stub {
        private RecentTasksController mController;
        private final SingleInstanceRemoteListener<RecentTasksController,
                IRecentTasksListener> mListener;
        private final IRecentTasksListener mRecentTasksListener = new IRecentTasksListener.Stub() {
            @Override
            public void onRecentTasksChanged() throws RemoteException {
                mListener.call(l -> l.onRecentTasksChanged());
            }

            @Override
            public void onRunningTaskAppeared(ActivityManager.RunningTaskInfo taskInfo) {
                mListener.call(l -> l.onRunningTaskAppeared(taskInfo));
            }

            @Override
            public void onRunningTaskVanished(ActivityManager.RunningTaskInfo taskInfo) {
                mListener.call(l -> l.onRunningTaskVanished(taskInfo));
            }
        };

        public IRecentTasksImpl(RecentTasksController controller) {
            mController = controller;
            mListener = new SingleInstanceRemoteListener<>(controller,
                    c -> c.registerRecentTasksListener(mRecentTasksListener),
                    c -> c.unregisterRecentTasksListener());
        }

        /**
         * Invalidates this instance, preventing future calls from updating the controller.
         */
        void invalidate() {
            mController = null;
        }

        @Override
        public void registerRecentTasksListener(IRecentTasksListener listener)
                throws RemoteException {
            executeRemoteCallWithTaskPermission(mController, "registerRecentTasksListener",
                    (controller) -> mListener.register(listener));
        }

        @Override
        public void unregisterRecentTasksListener(IRecentTasksListener listener)
                throws RemoteException {
            executeRemoteCallWithTaskPermission(mController, "unregisterRecentTasksListener",
                    (controller) -> mListener.unregister());
        }

        @Override
        public GroupedRecentTaskInfo[] getRecentTasks(int maxNum, int flags, int userId)
                throws RemoteException {
            if (mController == null) {
                // The controller is already invalidated -- just return an empty task list for now
                return new GroupedRecentTaskInfo[0];
            }

            final GroupedRecentTaskInfo[][] out = new GroupedRecentTaskInfo[][]{null};
            executeRemoteCallWithTaskPermission(mController, "getRecentTasks",
                    (controller) -> out[0] = controller.getRecentTasks(maxNum, flags, userId)
                            .toArray(new GroupedRecentTaskInfo[0]),
                    true /* blocking */);
            return out[0];
        }

        @Override
        public ActivityManager.RunningTaskInfo[] getRunningTasks(int maxNum) {
            final ActivityManager.RunningTaskInfo[][] tasks =
                    new ActivityManager.RunningTaskInfo[][] {null};
            executeRemoteCallWithTaskPermission(mController, "getRunningTasks",
                    (controller) -> tasks[0] = ActivityTaskManager.getInstance().getTasks(maxNum)
                            .toArray(new ActivityManager.RunningTaskInfo[0]),
                    true /* blocking */);
            return tasks[0];
        }
    }
}