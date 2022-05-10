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

import static com.android.wm.shell.common.ExecutorUtils.executeRemoteCallWithTaskPermission;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskInfo;
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
import com.android.wm.shell.protolog.ShellProtoLogGroup;
import com.android.wm.shell.util.GroupedRecentTaskInfo;
import com.android.wm.shell.util.StagedSplitBounds;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Manages the recent task list from the system, caching it as necessary.
 */
public class RecentTasksController implements TaskStackListenerCallback,
        RemoteCallable<RecentTasksController> {
    private static final String TAG = RecentTasksController.class.getSimpleName();

    private final Context mContext;
    private final ShellExecutor mMainExecutor;
    private final TaskStackListenerImpl mTaskStackListener;
    private final RecentTasks mImpl = new RecentTasksImpl();

    private final ArrayList<Runnable> mCallbacks = new ArrayList<>();
    // Mapping of split task ids, mappings are symmetrical (ie. if t1 is the taskid of a task in a
    // pair, then mSplitTasks[t1] = t2, and mSplitTasks[t2] = t1)
    private final SparseIntArray mSplitTasks = new SparseIntArray();
    /**
     * Maps taskId to {@link StagedSplitBounds} for both taskIDs.
     * Meaning there will be two taskId integers mapping to the same object.
     * If there's any ordering to the pairing than we can probably just get away with only one
     * taskID mapping to it, leaving both for consistency with {@link #mSplitTasks} for now.
     */
    private final Map<Integer, StagedSplitBounds> mTaskSplitBoundsMap = new HashMap<>();

    /**
     * Creates {@link RecentTasksController}, returns {@code null} if the feature is not
     * supported.
     */
    @Nullable
    public static RecentTasksController create(
            Context context,
            TaskStackListenerImpl taskStackListener,
            @ShellMainThread ShellExecutor mainExecutor
    ) {
        if (!context.getResources().getBoolean(com.android.internal.R.bool.config_hasRecents)) {
            return null;
        }
        return new RecentTasksController(context, taskStackListener, mainExecutor);
    }

    RecentTasksController(Context context, TaskStackListenerImpl taskStackListener,
            ShellExecutor mainExecutor) {
        mContext = context;
        mTaskStackListener = taskStackListener;
        mMainExecutor = mainExecutor;
    }

    public RecentTasks asRecentTasks() {
        return mImpl;
    }

    public void init() {
        mTaskStackListener.addListener(this);
    }

    /**
     * Adds a split pair. This call does not validate the taskIds, only that they are not the same.
     */
    public void addSplitPair(int taskId1, int taskId2, StagedSplitBounds splitBounds) {
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

    public void onTaskRemoved(TaskInfo taskInfo) {
        // Remove any split pairs associated with this task
        removeSplitPair(taskInfo.taskId);
        notifyRecentTasksChanged();
    }

    public void onTaskWindowingModeChanged(TaskInfo taskInfo) {
        notifyRecentTasksChanged();
    }

    @VisibleForTesting
    void notifyRecentTasksChanged() {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_RECENT_TASKS, "Notify recent tasks changed");
        for (int i = 0; i < mCallbacks.size(); i++) {
            mCallbacks.get(i).run();
        }
    }

    private void registerRecentTasksListener(Runnable listener) {
        if (!mCallbacks.contains(listener)) {
            mCallbacks.add(listener);
        }
    }

    private void unregisterRecentTasksListener(Runnable listener) {
        mCallbacks.remove(listener);
    }

    @VisibleForTesting
    List<ActivityManager.RecentTaskInfo> getRawRecentTasks(int maxNum, int flags, int userId) {
        return ActivityTaskManager.getInstance().getRecentTasks(maxNum, flags, userId);
    }

    @VisibleForTesting
    ArrayList<GroupedRecentTaskInfo> getRecentTasks(int maxNum, int flags, int userId) {
        // Note: the returned task list is from the most-recent to least-recent order
        final List<ActivityManager.RecentTaskInfo> rawList = getRawRecentTasks(maxNum, flags,
                userId);

        // Make a mapping of task id -> task info
        final SparseArray<ActivityManager.RecentTaskInfo> rawMapping = new SparseArray<>();
        for (int i = 0; i < rawList.size(); i++) {
            final ActivityManager.RecentTaskInfo taskInfo = rawList.get(i);
            rawMapping.put(taskInfo.taskId, taskInfo);
        }

        // Pull out the pairs as we iterate back in the list
        ArrayList<GroupedRecentTaskInfo> recentTasks = new ArrayList<>();
        for (int i = 0; i < rawList.size(); i++) {
            final ActivityManager.RecentTaskInfo taskInfo = rawList.get(i);
            if (!rawMapping.contains(taskInfo.taskId)) {
                // If it's not in the mapping, then it was already paired with another task
                continue;
            }

            final int pairedTaskId = mSplitTasks.get(taskInfo.taskId);
            if (pairedTaskId != INVALID_TASK_ID && rawMapping.contains(pairedTaskId)) {
                final ActivityManager.RecentTaskInfo pairedTaskInfo = rawMapping.get(pairedTaskId);
                rawMapping.remove(pairedTaskId);
                recentTasks.add(new GroupedRecentTaskInfo(taskInfo, pairedTaskInfo,
                        mTaskSplitBoundsMap.get(pairedTaskId)));
            } else {
                recentTasks.add(new GroupedRecentTaskInfo(taskInfo));
            }
        }
        return recentTasks;
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
    }


    /**
     * The interface for calls from outside the host process.
     */
    @BinderThread
    private static class IRecentTasksImpl extends IRecentTasks.Stub {
        private RecentTasksController mController;
        private final SingleInstanceRemoteListener<RecentTasksController,
                IRecentTasksListener> mListener;
        private final Runnable mRecentTasksListener =
                new Runnable() {
                    @Override
                    public void run() {
                        mListener.call(l -> l.onRecentTasksChanged());
                    }
                };

        public IRecentTasksImpl(RecentTasksController controller) {
            mController = controller;
            mListener = new SingleInstanceRemoteListener<>(controller,
                    c -> c.registerRecentTasksListener(mRecentTasksListener),
                    c -> c.unregisterRecentTasksListener(mRecentTasksListener));
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
    }
}