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

package com.android.wm.shell.common;

import android.app.ActivityManager;
import android.app.ActivityManager.RunningTaskInfo;
import android.app.ITaskStackListener;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.os.IBinder;

import androidx.annotation.BinderThread;
import androidx.annotation.MainThread;

/**
 * An interface to track task stack changes. Classes should implement this instead of
 * {@link ITaskStackListener} to reduce IPC calls from system services.
 */
public interface TaskStackListenerCallback {

    @MainThread
    default void onRecentTaskListUpdated() { }

    @MainThread
    default void onRecentTaskListFrozenChanged(boolean frozen) { }

    @BinderThread
    default void onTaskStackChangedBackground() { }

    @MainThread
    default void onTaskStackChanged() { }

    @MainThread
    default void onTaskProfileLocked(int taskId, int userId) { }

    @MainThread
    default void onTaskDisplayChanged(int taskId, int newDisplayId) { }

    @MainThread
    default void onTaskCreated(int taskId, ComponentName componentName) { }

    @MainThread
    default void onTaskRemoved(int taskId) { }

    @MainThread
    default void onTaskMovedToFront(int taskId) { }

    @MainThread
    default void onTaskMovedToFront(RunningTaskInfo taskInfo) {
        onTaskMovedToFront(taskInfo.taskId);
    }

    @MainThread
    default void onTaskDescriptionChanged(RunningTaskInfo taskInfo) { }

    @MainThread
    default void onTaskSnapshotChanged(int taskId, ActivityManager.TaskSnapshot snapshot) { }

    @MainThread
    default void onBackPressedOnTaskRoot(RunningTaskInfo taskInfo) { }

    @MainThread
    default void onActivityRestartAttempt(RunningTaskInfo task, boolean homeTaskVisible,
            boolean clearedTask, boolean wasVisible) { }

    @MainThread
    default void onActivityPinned(String packageName, int userId, int taskId, int stackId) { }

    @MainThread
    default void onActivityUnpinned() { }

    @MainThread
    default void onActivityForcedResizable(String packageName, int taskId, int reason) { }

    @MainThread
    default void onActivityDismissingDockedStack() { }

    @MainThread
    default void onActivityLaunchOnSecondaryDisplayFailed() { }

    @MainThread
    default void onActivityLaunchOnSecondaryDisplayFailed(RunningTaskInfo taskInfo) {
        onActivityLaunchOnSecondaryDisplayFailed();
    }

    @MainThread
    default void onActivityLaunchOnSecondaryDisplayRerouted() { }

    @MainThread
    default void onActivityLaunchOnSecondaryDisplayRerouted(RunningTaskInfo taskInfo) {
        onActivityLaunchOnSecondaryDisplayRerouted();
    }

    @MainThread
    default void onActivityRequestedOrientationChanged(int taskId, int requestedOrientation) { }

    @MainThread
    default void onActivityRotation(int displayId) { }

    @MainThread
    default void onSizeCompatModeActivityChanged(int displayId, IBinder activityToken) { }
}
