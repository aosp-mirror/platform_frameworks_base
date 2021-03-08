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
package com.android.wm.shell.startingsurface;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_NONE;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SNAPSHOT;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SPLASH_SCREEN;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ACTIVITY_CREATED;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_NEW_TASK;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_PROCESS_RUNNING;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_TASK_SWITCH;

import android.app.ActivityManager.RunningTaskInfo;
import android.app.ActivityTaskManager;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.window.StartingWindowInfo;
import android.window.TaskOrganizer;
import android.window.TaskSnapshot;

import com.android.wm.shell.common.ShellExecutor;

import java.util.function.BiConsumer;

/**
 * Implementation to draw the starting window to an application, and remove the starting window
 * until the application displays its own window.
 *
 * When receive {@link TaskOrganizer#addStartingWindow} callback, use this class to create a
 * starting window and attached to the Task, then when the Task want to remove the starting window,
 * the TaskOrganizer will receive {@link TaskOrganizer#removeStartingWindow} callback then use this
 * class to remove the starting window of the Task.
 * @hide
 */
public class StartingWindowController {
    private static final String TAG = StartingWindowController.class.getSimpleName();
    static final boolean DEBUG_SPLASH_SCREEN = false;
    static final boolean DEBUG_TASK_SNAPSHOT = false;

    private final StartingSurfaceDrawer mStartingSurfaceDrawer;
    private final StartingTypeChecker mStartingTypeChecker = new StartingTypeChecker();

    private BiConsumer<Integer, Integer> mTaskLaunchingCallback;
    private final StartingSurfaceImpl mImpl = new StartingSurfaceImpl();

    public StartingWindowController(Context context, ShellExecutor mainExecutor) {
        mStartingSurfaceDrawer = new StartingSurfaceDrawer(context, mainExecutor);
    }

    /**
     * Provide the implementation for Shell Module.
     */
    public StartingSurface asStartingSurface() {
        return mImpl;
    }

    private static class StartingTypeChecker {
        TaskSnapshot mSnapshot;

        StartingTypeChecker() { }

        private void reset() {
            mSnapshot = null;
        }

        private @StartingWindowInfo.StartingWindowType int
                estimateStartingWindowType(StartingWindowInfo windowInfo) {
            reset();
            final int parameter = windowInfo.startingWindowTypeParameter;
            final boolean newTask = (parameter & TYPE_PARAMETER_NEW_TASK) != 0;
            final boolean taskSwitch = (parameter & TYPE_PARAMETER_TASK_SWITCH) != 0;
            final boolean processRunning = (parameter & TYPE_PARAMETER_PROCESS_RUNNING) != 0;
            final boolean allowTaskSnapshot = (parameter & TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT) != 0;
            final boolean activityCreated = (parameter & TYPE_PARAMETER_ACTIVITY_CREATED) != 0;
            return estimateStartingWindowType(windowInfo, newTask, taskSwitch,
                    processRunning, allowTaskSnapshot, activityCreated);
        }

        // reference from ActivityRecord#getStartingWindowType
        private int estimateStartingWindowType(StartingWindowInfo windowInfo,
                boolean newTask, boolean taskSwitch, boolean processRunning,
                boolean allowTaskSnapshot, boolean activityCreated) {
            if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                Slog.d(TAG, "preferredStartingWindowType newTask " + newTask
                        + " taskSwitch " + taskSwitch
                        + " processRunning " + processRunning
                        + " allowTaskSnapshot " + allowTaskSnapshot
                        + " activityCreated " + activityCreated);
            }
            if (newTask || !processRunning || (taskSwitch && !activityCreated)) {
                return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
            }
            if (taskSwitch && allowTaskSnapshot) {
                final TaskSnapshot snapshot = getTaskSnapshot(windowInfo.taskInfo.taskId);
                if (isSnapshotCompatible(windowInfo, snapshot)) {
                    return STARTING_WINDOW_TYPE_SNAPSHOT;
                }
                if (windowInfo.taskInfo.topActivityType != ACTIVITY_TYPE_HOME) {
                    return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
                }
            }
            return STARTING_WINDOW_TYPE_NONE;
        }

        /**
         * Returns {@code true} if the task snapshot is compatible with this activity (at least the
         * rotation must be the same).
         */
        private boolean isSnapshotCompatible(StartingWindowInfo windowInfo, TaskSnapshot snapshot) {
            if (snapshot == null) {
                if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                    Slog.d(TAG, "isSnapshotCompatible no snapshot " + windowInfo.taskInfo.taskId);
                }
                return false;
            }

            final int taskRotation = windowInfo.taskInfo.configuration
                    .windowConfiguration.getRotation();
            final int snapshotRotation = snapshot.getRotation();
            if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                Slog.d(TAG, "isSnapshotCompatible rotation " + taskRotation
                        + " snapshot " + snapshotRotation);
            }
            return taskRotation == snapshotRotation;
        }

        private TaskSnapshot getTaskSnapshot(int taskId) {
            if (mSnapshot != null) {
                return mSnapshot;
            }
            try {
                mSnapshot = ActivityTaskManager.getService().getTaskSnapshot(taskId,
                        false/* isLowResolution */);
            } catch (RemoteException e) {
                Slog.e(TAG, "Unable to get snapshot for task: " + taskId + ", from: " + e);
                return null;
            }
            return mSnapshot;
        }
    }

    /*
     * Registers the starting window listener.
     *
     * @param listener The callback when need a starting window.
     */
    void setStartingWindowListener(BiConsumer<Integer, Integer> listener) {
        mTaskLaunchingCallback = listener;
    }

    /**
     * Called when a task need a starting window.
     */
    void addStartingWindow(StartingWindowInfo windowInfo, IBinder appToken) {
        final int suggestionType = mStartingTypeChecker.estimateStartingWindowType(windowInfo);
        final RunningTaskInfo runningTaskInfo = windowInfo.taskInfo;
        if (mTaskLaunchingCallback != null) {
            mTaskLaunchingCallback.accept(runningTaskInfo.taskId, suggestionType);
        }
        if (suggestionType == STARTING_WINDOW_TYPE_SPLASH_SCREEN) {
            mStartingSurfaceDrawer.addSplashScreenStartingWindow(windowInfo, appToken);
        } else if (suggestionType == STARTING_WINDOW_TYPE_SNAPSHOT) {
            final TaskSnapshot snapshot = mStartingTypeChecker.mSnapshot;
            mStartingSurfaceDrawer.makeTaskSnapshotWindow(windowInfo, appToken, snapshot);
        }
        // If prefer don't show, then don't show!
    }

    void copySplashScreenView(int taskId) {
        mStartingSurfaceDrawer.copySplashScreenView(taskId);
    }

    /**
     * Called when the content of a task is ready to show, starting window can be removed.
     */
    void removeStartingWindow(int taskId) {
        mStartingSurfaceDrawer.removeStartingWindow(taskId);
    }

    private class StartingSurfaceImpl implements StartingSurface {

        @Override
        public void addStartingWindow(StartingWindowInfo windowInfo, IBinder appToken) {
            StartingWindowController.this.addStartingWindow(windowInfo, appToken);
        }

        @Override
        public void removeStartingWindow(int taskId) {
            StartingWindowController.this.removeStartingWindow(taskId);
        }

        @Override
        public void copySplashScreenView(int taskId) {
            StartingWindowController.this.copySplashScreenView(taskId);
        }

        @Override
        public void setStartingWindowListener(BiConsumer<Integer, Integer> listener) {
            StartingWindowController.this.setStartingWindowListener(listener);
        }
    }
}
