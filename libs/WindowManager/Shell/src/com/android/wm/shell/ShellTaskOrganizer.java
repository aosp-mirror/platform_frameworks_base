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
import static android.app.WindowConfiguration.WINDOWING_MODE_UNDEFINED;

import static com.android.wm.shell.protolog.ShellProtoLogGroup.WM_SHELL_TASK_ORG;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.TaskInfo;
import android.content.Context;
import android.content.LocusId;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.SurfaceControl;
import android.window.ITaskOrganizerController;
import android.window.StartingWindowInfo;
import android.window.TaskAppearedInfo;
import android.window.TaskOrganizer;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.protolog.common.ProtoLog;
import com.android.wm.shell.common.ScreenshotUtils;
import com.android.wm.shell.common.ShellExecutor;
import com.android.wm.shell.sizecompatui.SizeCompatUIController;
import com.android.wm.shell.startingsurface.StartingWindowController;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Unified task organizer for all components in the shell.
 * TODO(b/167582004): may consider consolidating this class and TaskOrganizer
 */
public class ShellTaskOrganizer extends TaskOrganizer implements
        SizeCompatUIController.SizeCompatUICallback {

    // Intentionally using negative numbers here so the positive numbers can be used
    // for task id specific listeners that will be added later.
    public static final int TASK_LISTENER_TYPE_UNDEFINED = -1;
    public static final int TASK_LISTENER_TYPE_FULLSCREEN = -2;
    public static final int TASK_LISTENER_TYPE_MULTI_WINDOW = -3;
    public static final int TASK_LISTENER_TYPE_PIP = -4;

    @IntDef(prefix = {"TASK_LISTENER_TYPE_"}, value = {
            TASK_LISTENER_TYPE_UNDEFINED,
            TASK_LISTENER_TYPE_FULLSCREEN,
            TASK_LISTENER_TYPE_MULTI_WINDOW,
            TASK_LISTENER_TYPE_PIP,
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
        /** Whether this task listener supports size compat UI. */
        default boolean supportSizeCompatUI() {
            // All TaskListeners should support size compat except PIP.
            return true;
        }
        /** Attaches the a child window surface to the task surface. */
        default void attachChildSurfaceToTask(int taskId, SurfaceControl.Builder b) {
            throw new IllegalStateException(
                    "This task listener doesn't support child surface attachment.");
        }
        default void dump(@NonNull PrintWriter pw, String prefix) {};
    }

    /**
     * Callbacks for events on a task with a locus id.
     */
    public interface LocusIdListener {
        /**
         * Notifies when a task with a locusId becomes visible, when a visible task's locusId
         * changes, or if a previously visible task with a locusId becomes invisible.
         */
        void onVisibilityChanged(int taskId, LocusId locus, boolean visible);
    }

    /**
     * Keys map from either a task id or {@link TaskListenerType}.
     * @see #addListenerForTaskId
     * @see #addListenerForType
     */
    private final SparseArray<TaskListener> mTaskListeners = new SparseArray<>();

    // Keeps track of all the tasks reported to this organizer (changes in windowing mode will
    // require us to report to both old and new listeners)
    private final SparseArray<TaskAppearedInfo> mTasks = new SparseArray<>();

    /** @see #setPendingLaunchCookieListener */
    private final ArrayMap<IBinder, TaskListener> mLaunchCookieToListener = new ArrayMap<>();

    // Keeps track of taskId's with visible locusIds. Used to notify any {@link LocusIdListener}s
    // that might be set.
    private final SparseArray<LocusId> mVisibleTasksWithLocusId = new SparseArray<>();

    /** @see #addLocusIdListener */
    private final ArraySet<LocusIdListener> mLocusIdListeners = new ArraySet<>();

    private final Object mLock = new Object();
    private StartingWindowController mStartingWindow;

    /**
     * In charge of showing size compat UI. Can be {@code null} if device doesn't support size
     * compat.
     */
    @Nullable
    private final SizeCompatUIController mSizeCompatUI;

    public ShellTaskOrganizer(ShellExecutor mainExecutor, Context context) {
        this(null /* taskOrganizerController */, mainExecutor, context, null /* sizeCompatUI */);
    }

    public ShellTaskOrganizer(ShellExecutor mainExecutor, Context context, @Nullable
            SizeCompatUIController sizeCompatUI) {
        this(null /* taskOrganizerController */, mainExecutor, context, sizeCompatUI);
    }

    @VisibleForTesting
    ShellTaskOrganizer(ITaskOrganizerController taskOrganizerController, ShellExecutor mainExecutor,
            Context context, @Nullable SizeCompatUIController sizeCompatUI) {
        super(taskOrganizerController, mainExecutor);
        mSizeCompatUI = sizeCompatUI;
        if (sizeCompatUI != null) {
            sizeCompatUI.setSizeCompatUICallback(this);
        }
    }

    @Override
    public List<TaskAppearedInfo> registerOrganizer() {
        synchronized (mLock) {
            ProtoLog.v(WM_SHELL_TASK_ORG, "Registering organizer");
            final List<TaskAppearedInfo> taskInfos = super.registerOrganizer();
            for (int i = 0; i < taskInfos.size(); i++) {
                final TaskAppearedInfo info = taskInfos.get(i);
                ProtoLog.v(WM_SHELL_TASK_ORG, "Existing task: id=%d component=%s",
                        info.getTaskInfo().taskId, info.getTaskInfo().baseIntent);
                onTaskAppeared(info);
            }
            return taskInfos;
        }
    }

    public void createRootTask(int displayId, int windowingMode, TaskListener listener) {
        ProtoLog.v(WM_SHELL_TASK_ORG, "createRootTask() displayId=%d winMode=%d listener=%s",
                displayId, windowingMode, listener.toString());
        final IBinder cookie = new Binder();
        setPendingLaunchCookieListener(cookie, listener);
        super.createRootTask(displayId, windowingMode, cookie);
    }

    /**
     * @hide
     */
    public void initStartingWindow(StartingWindowController startingWindow) {
        mStartingWindow = startingWindow;
    }

    /**
     * Adds a listener for a specific task id.
     */
    public void addListenerForTaskId(TaskListener listener, int taskId) {
        synchronized (mLock) {
            ProtoLog.v(WM_SHELL_TASK_ORG, "addListenerForTaskId taskId=%s", taskId);
            if (mTaskListeners.get(taskId) != null) {
                throw new IllegalArgumentException(
                        "Listener for taskId=" + taskId + " already exists");
            }

            final TaskAppearedInfo info = mTasks.get(taskId);
            if (info == null) {
                throw new IllegalArgumentException("addListenerForTaskId unknown taskId=" + taskId);
            }

            final TaskListener oldListener = getTaskListener(info.getTaskInfo());
            mTaskListeners.put(taskId, listener);
            updateTaskListenerIfNeeded(info.getTaskInfo(), info.getLeash(), oldListener, listener);
        }
    }

    /**
     * Adds a listener for tasks with given types.
     */
    public void addListenerForType(TaskListener listener, @TaskListenerType int... listenerTypes) {
        synchronized (mLock) {
            ProtoLog.v(WM_SHELL_TASK_ORG, "addListenerForType types=%s listener=%s",
                    Arrays.toString(listenerTypes), listener);
            for (int listenerType : listenerTypes) {
                if (mTaskListeners.get(listenerType) != null) {
                    throw new IllegalArgumentException("Listener for listenerType=" + listenerType
                            + " already exists");
                }
                mTaskListeners.put(listenerType, listener);

                // Notify the listener of all existing tasks with the given type.
                for (int i = mTasks.size() - 1; i >= 0; --i) {
                    final TaskAppearedInfo data = mTasks.valueAt(i);
                    final TaskListener taskListener = getTaskListener(data.getTaskInfo());
                    if (taskListener != listener) continue;
                    listener.onTaskAppeared(data.getTaskInfo(), data.getLeash());
                }
            }
        }
    }

    /**
     * Removes a registered listener.
     */
    public void removeListener(TaskListener listener) {
        synchronized (mLock) {
            ProtoLog.v(WM_SHELL_TASK_ORG, "Remove listener=%s", listener);
            final int index = mTaskListeners.indexOfValue(listener);
            if (index == -1) {
                Log.w(TAG, "No registered listener found");
                return;
            }

            // Collect tasks associated with the listener we are about to remove.
            final ArrayList<TaskAppearedInfo> tasks = new ArrayList<>();
            for (int i = mTasks.size() - 1; i >= 0; --i) {
                final TaskAppearedInfo data = mTasks.valueAt(i);
                final TaskListener taskListener = getTaskListener(data.getTaskInfo());
                if (taskListener != listener) continue;
                tasks.add(data);
            }

            // Remove listener
            mTaskListeners.removeAt(index);

            // Associate tasks with new listeners if needed.
            for (int i = tasks.size() - 1; i >= 0; --i) {
                final TaskAppearedInfo data = tasks.get(i);
                updateTaskListenerIfNeeded(data.getTaskInfo(), data.getLeash(),
                        null /* oldListener already removed*/, getTaskListener(data.getTaskInfo()));
            }
        }
    }

    /**
     * Associated a listener to a pending launch cookie so we can route the task later once it
     * appears.
     */
    public void setPendingLaunchCookieListener(IBinder cookie, TaskListener listener) {
        synchronized (mLock) {
            mLaunchCookieToListener.put(cookie, listener);
        }
    }

    /**
     * Adds a listener to be notified for {@link LocusId} visibility changes.
     */
    public void addLocusIdListener(LocusIdListener listener) {
        synchronized (mLock) {
            mLocusIdListeners.add(listener);
            for (int i = 0; i < mVisibleTasksWithLocusId.size(); i++) {
                listener.onVisibilityChanged(mVisibleTasksWithLocusId.keyAt(i),
                        mVisibleTasksWithLocusId.valueAt(i), true /* visible */);
            }
        }
    }

    /**
     * Removes listener.
     */
    public void removeLocusIdListener(LocusIdListener listener) {
        synchronized (mLock) {
            mLocusIdListeners.remove(listener);
        }
    }

    @Override
    public void addStartingWindow(StartingWindowInfo info, IBinder appToken) {
        if (mStartingWindow != null) {
            mStartingWindow.addStartingWindow(info, appToken);
        }
    }

    @Override
    public void removeStartingWindow(int taskId, SurfaceControl leash, Rect frame,
            boolean playRevealAnimation) {
        if (mStartingWindow != null) {
            mStartingWindow.removeStartingWindow(taskId, leash, frame, playRevealAnimation);
        }
    }

    @Override
    public void copySplashScreenView(int taskId) {
        if (mStartingWindow != null) {
            mStartingWindow.copySplashScreenView(taskId);
        }
    }

    @Override
    public void onAppSplashScreenViewRemoved(int taskId) {
        if (mStartingWindow != null) {
            mStartingWindow.onAppSplashScreenViewRemoved(taskId);
        }
    }

    @Override
    public void onImeDrawnOnTask(int taskId) {
        if (mStartingWindow != null) {
            mStartingWindow.onImeDrawnOnTask(taskId);
        }
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        synchronized (mLock) {
            onTaskAppeared(new TaskAppearedInfo(taskInfo, leash));
        }
    }

    private void onTaskAppeared(TaskAppearedInfo info) {
        final int taskId = info.getTaskInfo().taskId;
        mTasks.put(taskId, info);
        final TaskListener listener =
                getTaskListener(info.getTaskInfo(), true /*removeLaunchCookieIfNeeded*/);
        ProtoLog.v(WM_SHELL_TASK_ORG, "Task appeared taskId=%d listener=%s", taskId, listener);
        if (listener != null) {
            listener.onTaskAppeared(info.getTaskInfo(), info.getLeash());
        }
        notifyLocusVisibilityIfNeeded(info.getTaskInfo());
        notifySizeCompatUI(info.getTaskInfo(), listener);
    }

    /**
     * Take a screenshot of a task.
     */
    public void screenshotTask(RunningTaskInfo taskInfo, Rect crop,
            Consumer<SurfaceControl.ScreenshotHardwareBuffer> consumer) {
        final TaskAppearedInfo info = mTasks.get(taskInfo.taskId);
        if (info == null) {
            return;
        }
        ScreenshotUtils.captureLayer(info.getLeash(), crop, consumer);
    }


    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        synchronized (mLock) {
            ProtoLog.v(WM_SHELL_TASK_ORG, "Task info changed taskId=%d", taskInfo.taskId);
            final TaskAppearedInfo data = mTasks.get(taskInfo.taskId);
            final TaskListener oldListener = getTaskListener(data.getTaskInfo());
            final TaskListener newListener = getTaskListener(taskInfo);
            mTasks.put(taskInfo.taskId, new TaskAppearedInfo(taskInfo, data.getLeash()));
            final boolean updated = updateTaskListenerIfNeeded(
                    taskInfo, data.getLeash(), oldListener, newListener);
            if (!updated && newListener != null) {
                newListener.onTaskInfoChanged(taskInfo);
            }
            notifyLocusVisibilityIfNeeded(taskInfo);
            if (updated || !taskInfo.equalsForSizeCompat(data.getTaskInfo())) {
                // Notify the size compat UI if the listener or task info changed.
                notifySizeCompatUI(taskInfo, newListener);
            }
        }
    }

    @Override
    public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
        synchronized (mLock) {
            ProtoLog.v(WM_SHELL_TASK_ORG, "Task root back pressed taskId=%d", taskInfo.taskId);
            final TaskListener listener = getTaskListener(taskInfo);
            if (listener != null) {
                listener.onBackPressedOnTaskRoot(taskInfo);
            }
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        synchronized (mLock) {
            ProtoLog.v(WM_SHELL_TASK_ORG, "Task vanished taskId=%d", taskInfo.taskId);
            final int taskId = taskInfo.taskId;
            final TaskListener listener = getTaskListener(mTasks.get(taskId).getTaskInfo());
            mTasks.remove(taskId);
            if (listener != null) {
                listener.onTaskVanished(taskInfo);
            }
            notifyLocusVisibilityIfNeeded(taskInfo);
            // Pass null for listener to remove the size compat UI on this task if there is any.
            notifySizeCompatUI(taskInfo, null /* taskListener */);
        }
    }

    /** Gets running task by taskId. Returns {@code null} if no such task observed. */
    @Nullable
    public RunningTaskInfo getRunningTaskInfo(int taskId) {
        synchronized (mLock) {
            final TaskAppearedInfo info = mTasks.get(taskId);
            return info != null ? info.getTaskInfo() : null;
        }
    }

    /** Helper to set int metadata on the Surface corresponding to the task id. */
    public void setSurfaceMetadata(int taskId, int key, int value) {
        synchronized (mLock) {
            final TaskAppearedInfo info = mTasks.get(taskId);
            if (info == null || info.getLeash() == null) {
                return;
            }
            SurfaceControl.Transaction t = new SurfaceControl.Transaction();
            t.setMetadata(info.getLeash(), key, value);
            t.apply();
        }
    }

    private boolean updateTaskListenerIfNeeded(RunningTaskInfo taskInfo, SurfaceControl leash,
            TaskListener oldListener, TaskListener newListener) {
        if (oldListener == newListener) return false;
        // TODO: We currently send vanished/appeared as the task moves between types, but
        //       we should consider adding a different mode-changed callback
        if (oldListener != null) {
            oldListener.onTaskVanished(taskInfo);
        }
        if (newListener != null) {
            newListener.onTaskAppeared(taskInfo, leash);
        }
        return true;
    }

    private void notifyLocusVisibilityIfNeeded(TaskInfo taskInfo) {
        final int taskId = taskInfo.taskId;
        final LocusId prevLocus = mVisibleTasksWithLocusId.get(taskId);
        final boolean sameLocus = Objects.equals(prevLocus, taskInfo.mTopActivityLocusId);
        if (prevLocus == null) {
            // New visible locus
            if (taskInfo.mTopActivityLocusId != null && taskInfo.isVisible) {
                mVisibleTasksWithLocusId.put(taskId, taskInfo.mTopActivityLocusId);
                notifyLocusIdChange(taskId, taskInfo.mTopActivityLocusId, true /* visible */);
            }
        } else if (sameLocus && !taskInfo.isVisible) {
            // Hidden locus
            mVisibleTasksWithLocusId.remove(taskId);
            notifyLocusIdChange(taskId, taskInfo.mTopActivityLocusId, false /* visible */);
        } else if (!sameLocus) {
            // Changed locus
            if (taskInfo.isVisible) {
                mVisibleTasksWithLocusId.put(taskId, taskInfo.mTopActivityLocusId);
                notifyLocusIdChange(taskId, prevLocus, false /* visible */);
                notifyLocusIdChange(taskId, taskInfo.mTopActivityLocusId, true /* visible */);
            } else {
                mVisibleTasksWithLocusId.remove(taskInfo.taskId);
                notifyLocusIdChange(taskId, prevLocus, false /* visible */);
            }
        }
    }

    private void notifyLocusIdChange(int taskId, LocusId locus, boolean visible) {
        for (int i = 0; i < mLocusIdListeners.size(); i++) {
            mLocusIdListeners.valueAt(i).onVisibilityChanged(taskId, locus, visible);
        }
    }

    @Override
    public void onSizeCompatRestartButtonClicked(int taskId) {
        final TaskAppearedInfo info;
        synchronized (mLock) {
            info = mTasks.get(taskId);
        }
        if (info != null) {
            restartTaskTopActivityProcessIfVisible(info.getTaskInfo().token);
        }
    }

    /**
     * Notifies {@link SizeCompatUIController} about the size compat info changed on the give Task
     * to update the UI accordingly.
     *
     * @param taskInfo the new Task info
     * @param taskListener listener to handle the Task Surface placement. {@code null} if task is
     *                     vanished.
     */
    private void notifySizeCompatUI(RunningTaskInfo taskInfo, @Nullable TaskListener taskListener) {
        if (mSizeCompatUI == null) {
            return;
        }

        // The task is vanished or doesn't support size compat UI, notify to remove size compat UI
        // on this Task if there is any.
        if (taskListener == null || !taskListener.supportSizeCompatUI()
                || !taskInfo.topActivityInSizeCompat || !taskInfo.isVisible) {
            mSizeCompatUI.onSizeCompatInfoChanged(taskInfo.displayId, taskInfo.taskId,
                    null /* taskConfig */, null /* taskListener */);
            return;
        }

        mSizeCompatUI.onSizeCompatInfoChanged(taskInfo.displayId, taskInfo.taskId,
                taskInfo.configuration, taskListener);
    }

    private TaskListener getTaskListener(RunningTaskInfo runningTaskInfo) {
        return getTaskListener(runningTaskInfo, false /*removeLaunchCookieIfNeeded*/);
    }

    private TaskListener getTaskListener(RunningTaskInfo runningTaskInfo,
            boolean removeLaunchCookieIfNeeded) {

        final int taskId = runningTaskInfo.taskId;
        TaskListener listener;

        // First priority goes to listener that might be pending for this task.
        final ArrayList<IBinder> launchCookies = runningTaskInfo.launchCookies;
        for (int i = launchCookies.size() - 1; i >= 0; --i) {
            final IBinder cookie = launchCookies.get(i);
            listener = mLaunchCookieToListener.get(cookie);
            if (listener == null) continue;

            if (removeLaunchCookieIfNeeded) {
                // Remove the cookie and add the listener.
                mLaunchCookieToListener.remove(cookie);
                mTaskListeners.put(taskId, listener);
            }
            return listener;
        }

        // Next priority goes to taskId specific listeners.
        listener = mTaskListeners.get(taskId);
        if (listener != null) return listener;

        // Next priority goes to the listener listening to its parent.
        if (runningTaskInfo.hasParentTask()) {
            listener = mTaskListeners.get(runningTaskInfo.parentTaskId);
            if (listener != null) return listener;
        }

        // Next we try type specific listeners.
        final int taskListenerType = taskInfoToTaskListenerType(runningTaskInfo);
        return mTaskListeners.get(taskListenerType);
    }

    @VisibleForTesting
    static @TaskListenerType int taskInfoToTaskListenerType(RunningTaskInfo runningTaskInfo) {
        switch (runningTaskInfo.getWindowingMode()) {
            case WINDOWING_MODE_FULLSCREEN:
                return TASK_LISTENER_TYPE_FULLSCREEN;
            case WINDOWING_MODE_MULTI_WINDOW:
                return TASK_LISTENER_TYPE_MULTI_WINDOW;
            case WINDOWING_MODE_PINNED:
                return TASK_LISTENER_TYPE_PIP;
            case WINDOWING_MODE_FREEFORM:
            case WINDOWING_MODE_UNDEFINED:
            default:
                return TASK_LISTENER_TYPE_UNDEFINED;
        }
    }

    public static String taskListenerTypeToString(@TaskListenerType int type) {
        switch (type) {
            case TASK_LISTENER_TYPE_FULLSCREEN:
                return "TASK_LISTENER_TYPE_FULLSCREEN";
            case TASK_LISTENER_TYPE_MULTI_WINDOW:
                return "TASK_LISTENER_TYPE_MULTI_WINDOW";
            case TASK_LISTENER_TYPE_PIP:
                return "TASK_LISTENER_TYPE_PIP";
            case TASK_LISTENER_TYPE_UNDEFINED:
                return "TASK_LISTENER_TYPE_UNDEFINED";
            default:
                return "taskId#" + type;
        }
    }

    public void dump(@NonNull PrintWriter pw, String prefix) {
        synchronized (mLock) {
            final String innerPrefix = prefix + "  ";
            final String childPrefix = innerPrefix + "  ";
            pw.println(prefix + TAG);
            pw.println(innerPrefix + mTaskListeners.size() + " Listeners");
            for (int i = mTaskListeners.size() - 1; i >= 0; --i) {
                final int key = mTaskListeners.keyAt(i);
                final TaskListener listener = mTaskListeners.valueAt(i);
                pw.println(innerPrefix + "#" + i + " " + taskListenerTypeToString(key));
                listener.dump(pw, childPrefix);
            }

            pw.println();
            pw.println(innerPrefix + mTasks.size() + " Tasks");
            for (int i = mTasks.size() - 1; i >= 0; --i) {
                final int key = mTasks.keyAt(i);
                final TaskAppearedInfo info = mTasks.valueAt(i);
                final TaskListener listener = getTaskListener(info.getTaskInfo());
                pw.println(innerPrefix + "#" + i + " task=" + key + " listener=" + listener);
            }

            pw.println();
            pw.println(innerPrefix + mLaunchCookieToListener.size() + " Launch Cookies");
            for (int i = mLaunchCookieToListener.size() - 1; i >= 0; --i) {
                final IBinder key = mLaunchCookieToListener.keyAt(i);
                final TaskListener listener = mLaunchCookieToListener.valueAt(i);
                pw.println(innerPrefix + "#" + i + " cookie=" + key + " listener=" + listener);
            }
        }
    }
}
