/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.wm.shell;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_SECONDARY;
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import android.annotation.IntDef;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.WindowConfiguration.WindowingMode;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.window.ITaskOrganizerController;
import android.window.TaskOrganizer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.common.SyncTransactionQueue;
import com.android.wm.shell.common.TransactionPool;
import com.android.wm.shell.protolog.ShellProtoLogGroup;

import java.util.Arrays;

/**
 * Unified task organizer for all components in the shell.
 * TODO(b/167582004): may consider consolidating this class and TaskOrganizer
 */
public class ShellTaskOrganizer extends TaskOrganizer {

    // Intentionally using negative numbers here so the positive numbers can be used
    // for task id specific listeners that will be added later.
    public static final int TASK_LISTENER_TYPE_UNDEFINED = -1;
    public static final int TASK_LISTENER_TYPE_FULLSCREEN = -2;
    public static final int TASK_LISTENER_TYPE_MULTI_WINDOW = -3;
    public static final int TASK_LISTENER_TYPE_PIP = -4;
    public static final int TASK_LISTENER_TYPE_SPLIT_SCREEN = -5;

    @IntDef(prefix = {"TASK_LISTENER_TYPE_"}, value = {
            TASK_LISTENER_TYPE_UNDEFINED,
            TASK_LISTENER_TYPE_FULLSCREEN,
            TASK_LISTENER_TYPE_MULTI_WINDOW,
            TASK_LISTENER_TYPE_PIP,
            TASK_LISTENER_TYPE_SPLIT_SCREEN,
    })
    public @interface TaskListenerType {}

    private static final String TAG = "ShellTaskOrganizer";

    /**
     * Callbacks for when the tasks change in the system.
     */
    public interface TaskListener {
        default void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {}
        default void onTaskInfoChanged(RunningTaskInfo taskInfo) {}
        default void onTaskVanished(RunningTaskInfo taskInfo) {}
        default void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {}
    }

    private final SparseArray<TaskListener> mTaskListenersByType = new SparseArray<>();

    // Keeps track of all the tasks reported to this organizer (changes in windowing mode will
    // require us to report to both old and new listeners)
    private final SparseArray<Pair<RunningTaskInfo, SurfaceControl>> mTasks = new SparseArray<>();

    // TODO(shell-transitions): move to a more "global" Shell location as this isn't only for Tasks
    private final Transitions mTransitions;

    public ShellTaskOrganizer(SyncTransactionQueue syncQueue, TransactionPool transactionPool,
            ShellExecutor mainExecutor, ShellExecutor animExecutor) {
        this(null, syncQueue, transactionPool, mainExecutor, animExecutor);
    }

    @VisibleForTesting
    ShellTaskOrganizer(ITaskOrganizerController taskOrganizerController,
            SyncTransactionQueue syncQueue, TransactionPool transactionPool,
            ShellExecutor mainExecutor, ShellExecutor animExecutor) {
        super(taskOrganizerController);
        addListener(new FullscreenTaskListener(syncQueue), TASK_LISTENER_TYPE_FULLSCREEN);
        mTransitions = new Transitions(this, transactionPool, mainExecutor, animExecutor);
        if (Transitions.ENABLE_SHELL_TRANSITIONS) registerTransitionPlayer(mTransitions);
    }

    /**
     * Adds a listener for tasks with given types.
     */
    public void addListener(TaskListener listener, @TaskListenerType int... taskListenerTypes) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Add listener for types=%s listener=%s",
                Arrays.toString(taskListenerTypes), listener);
        for (int listenerType : taskListenerTypes) {
            if (mTaskListenersByType.get(listenerType) != null) {
                throw new IllegalArgumentException("Listener for listenerType=" + listenerType
                        + " already exists");
            }
            mTaskListenersByType.put(listenerType, listener);

            // Notify the listener of all existing tasks with the given type.
            for (int i = mTasks.size() - 1; i >= 0; i--) {
                Pair<RunningTaskInfo, SurfaceControl> data = mTasks.valueAt(i);
                final @TaskListenerType int taskListenerType = getTaskListenerType(data.first);
                if (taskListenerType == listenerType) {
                    listener.onTaskAppeared(data.first, data.second);
                }
            }
        }
    }

    /**
     * Removes a registered listener.
     */
    public void removeListener(TaskListener listener) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Remove listener=%s", listener);
        final int index = mTaskListenersByType.indexOfValue(listener);
        if (index == -1) {
            Log.w(TAG, "No registered listener found");
            return;
        }
        mTaskListenersByType.removeAt(index);
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Task appeared taskId=%d",
                taskInfo.taskId);
        mTasks.put(taskInfo.taskId, new Pair<>(taskInfo, leash));
        final TaskListener listener = mTaskListenersByType.get(getTaskListenerType(taskInfo));
        if (listener != null) {
            listener.onTaskAppeared(taskInfo, leash);
        }
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Task info changed taskId=%d",
                taskInfo.taskId);
        final Pair<RunningTaskInfo, SurfaceControl> data = mTasks.get(taskInfo.taskId);
        final @TaskListenerType int listenerType = getTaskListenerType(taskInfo);
        final @TaskListenerType int prevListenerType = getTaskListenerType(data.first);
        mTasks.put(taskInfo.taskId, new Pair<>(taskInfo, data.second));
        if (prevListenerType != listenerType) {
            // TODO: We currently send vanished/appeared as the task moves between types, but
            //       we should consider adding a different mode-changed callback
            TaskListener listener = mTaskListenersByType.get(prevListenerType);
            if (listener != null) {
                listener.onTaskVanished(taskInfo);
            }
            listener = mTaskListenersByType.get(listenerType);
            if (listener != null) {
                SurfaceControl leash = data.second;
                listener.onTaskAppeared(taskInfo, leash);
            }
        } else {
            final TaskListener listener = mTaskListenersByType.get(listenerType);
            if (listener != null) {
                listener.onTaskInfoChanged(taskInfo);
            }
        }
    }

    @Override
    public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Task root back pressed taskId=%d",
                taskInfo.taskId);
        final TaskListener listener = mTaskListenersByType.get(getTaskListenerType(taskInfo));
        if (listener != null) {
            listener.onBackPressedOnTaskRoot(taskInfo);
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        ProtoLog.v(ShellProtoLogGroup.WM_SHELL_TASK_ORG, "Task vanished taskId=%d",
                taskInfo.taskId);
        final @TaskListenerType int prevListenerType =
                getTaskListenerType(mTasks.get(taskInfo.taskId).first);
        mTasks.remove(taskInfo.taskId);
        final TaskListener listener = mTaskListenersByType.get(prevListenerType);
        if (listener != null) {
            listener.onTaskVanished(taskInfo);
        }
    }

    @TaskListenerType
    private static int getTaskListenerType(RunningTaskInfo runningTaskInfo) {
        // Right now it's N:1 mapping but in the future different task listerners
        // may be triggered by one windowing mode depending on task parameters.
        switch (getWindowingMode(runningTaskInfo)) {
            case WINDOWING_MODE_FULLSCREEN:
                return TASK_LISTENER_TYPE_FULLSCREEN;
            case WINDOWING_MODE_MULTI_WINDOW:
                return TASK_LISTENER_TYPE_MULTI_WINDOW;
            case WINDOWING_MODE_SPLIT_SCREEN_PRIMARY:
            case WINDOWING_MODE_SPLIT_SCREEN_SECONDARY:
                return TASK_LISTENER_TYPE_SPLIT_SCREEN;
            case WINDOWING_MODE_PINNED:
                return TASK_LISTENER_TYPE_PIP;
            case WINDOWING_MODE_FREEFORM:
            case WINDOWING_MODE_UNDEFINED:
            default:
                return TASK_LISTENER_TYPE_UNDEFINED;
        }
    }

    @WindowingMode
    private static int getWindowingMode(RunningTaskInfo taskInfo) {
        return taskInfo.configuration.windowConfiguration.getWindowingMode();
    }
}
