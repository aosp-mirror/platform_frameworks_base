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

import android.app.ActivityManager.RunningTaskInfo;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.SurfaceControl;
import android.window.TaskOrganizer;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Unified task organizer for all components in the shell.
 */
public class ShellTaskOrganizer extends TaskOrganizer {

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

    private final SparseArray<ArrayList<TaskListener>> mListenersByWindowingMode =
            new SparseArray<>();

    // Keeps track of all the tasks reported to this organizer (changes in windowing mode will
    // require us to report to both old and new listeners)
    private final SparseArray<Pair<RunningTaskInfo, SurfaceControl>> mTasks = new SparseArray<>();

    /**
     * Adds a listener for tasks in a specific windowing mode.
     */
    public void addListener(TaskListener listener, int... windowingModes) {
        for (int winMode : windowingModes) {
            ArrayList<TaskListener> listeners = mListenersByWindowingMode.get(winMode);
            if (listeners == null) {
                listeners = new ArrayList<>();
                mListenersByWindowingMode.put(winMode, listeners);
            }
            if (listeners.contains(listener)) {
                Log.w(TAG, "Listener already exists");
                return;
            }
            listeners.add(listener);

            // Notify the listener of all existing tasks in that windowing mode
            for (int i = mTasks.size() - 1; i >= 0; i--) {
                Pair<RunningTaskInfo, SurfaceControl> data = mTasks.valueAt(i);
                int taskWinMode = data.first.configuration.windowConfiguration.getWindowingMode();
                if (taskWinMode == winMode) {
                    listener.onTaskAppeared(data.first, data.second);
                }
            }
        }
    }

    /**
     * Removes a registered listener.
     */
    public void removeListener(TaskListener listener) {
        for (int i = 0; i < mListenersByWindowingMode.size(); i++) {
            mListenersByWindowingMode.valueAt(i).remove(listener);
        }
    }

    @Override
    public void onTaskAppeared(RunningTaskInfo taskInfo, SurfaceControl leash) {
        mTasks.put(taskInfo.taskId, new Pair<>(taskInfo, leash));
        ArrayList<TaskListener> listeners = mListenersByWindowingMode.get(
                getWindowingMode(taskInfo));
        if (listeners != null) {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                listeners.get(i).onTaskAppeared(taskInfo, leash);
            }
        }
    }

    @Override
    public void onTaskInfoChanged(RunningTaskInfo taskInfo) {
        Pair<RunningTaskInfo, SurfaceControl> data = mTasks.get(taskInfo.taskId);
        int winMode = getWindowingMode(taskInfo);
        int prevWinMode = getWindowingMode(data.first);
        if (prevWinMode != -1 && prevWinMode != winMode) {
            // TODO: We currently send vanished/appeared as the task moves between win modes, but
            //       we should consider adding a different mode-changed callback
            ArrayList<TaskListener> listeners = mListenersByWindowingMode.get(prevWinMode);
            if (listeners != null) {
                for (int i = listeners.size() - 1; i >= 0; i--) {
                    listeners.get(i).onTaskVanished(taskInfo);
                }
            }
            listeners = mListenersByWindowingMode.get(winMode);
            if (listeners != null) {
                SurfaceControl leash = data.second;
                for (int i = listeners.size() - 1; i >= 0; i--) {
                    listeners.get(i).onTaskAppeared(taskInfo, leash);
                }
            }
        } else {
            ArrayList<TaskListener> listeners = mListenersByWindowingMode.get(winMode);
            if (listeners != null) {
                for (int i = listeners.size() - 1; i >= 0; i--) {
                    listeners.get(i).onTaskInfoChanged(taskInfo);
                }
            }
        }
    }

    @Override
    public void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) {
        ArrayList<TaskListener> listeners = mListenersByWindowingMode.get(
                getWindowingMode(taskInfo));
        if (listeners != null) {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                listeners.get(i).onBackPressedOnTaskRoot(taskInfo);
            }
        }
    }

    @Override
    public void onTaskVanished(RunningTaskInfo taskInfo) {
        int prevWinMode = getWindowingMode(mTasks.get(taskInfo.taskId).first);
        mTasks.remove(taskInfo.taskId);
        ArrayList<TaskListener> listeners = mListenersByWindowingMode.get(prevWinMode);
        if (listeners != null) {
            for (int i = listeners.size() - 1; i >= 0; i--) {
                listeners.get(i).onTaskVanished(taskInfo);
            }
        }
    }

    private int getWindowingMode(RunningTaskInfo taskInfo) {
        return taskInfo.configuration.windowConfiguration.getWindowingMode();
    }
}
