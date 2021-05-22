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

package com.android.wm.shell.startingsurface.phone;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_HOME;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_EMPTY_SPLASH_SCREEN;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_NONE;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SNAPSHOT;
import static android.window.StartingWindowInfo.STARTING_WINDOW_TYPE_SPLASH_SCREEN;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ACTIVITY_CREATED;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_NEW_TASK;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_PROCESS_RUNNING;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_TASK_SWITCH;
import static android.window.StartingWindowInfo.TYPE_PARAMETER_USE_EMPTY_SPLASH_SCREEN;

import static com.android.wm.shell.startingsurface.StartingWindowController.DEBUG_SPLASH_SCREEN;
import static com.android.wm.shell.startingsurface.StartingWindowController.DEBUG_TASK_SNAPSHOT;

import android.util.Slog;
import android.window.StartingWindowInfo;
import android.window.TaskSnapshot;

import com.android.wm.shell.startingsurface.StartingWindowTypeAlgorithm;

/**
 * Algorithm for determining the type of a new starting window on handheld devices.
 * At the moment also used on Android Auto.
 */
public class PhoneStartingWindowTypeAlgorithm implements StartingWindowTypeAlgorithm {
    private static final String TAG = PhoneStartingWindowTypeAlgorithm.class.getSimpleName();

    @Override
    public int getSuggestedWindowType(StartingWindowInfo windowInfo) {
        final int parameter = windowInfo.startingWindowTypeParameter;
        final boolean newTask = (parameter & TYPE_PARAMETER_NEW_TASK) != 0;
        final boolean taskSwitch = (parameter & TYPE_PARAMETER_TASK_SWITCH) != 0;
        final boolean processRunning = (parameter & TYPE_PARAMETER_PROCESS_RUNNING) != 0;
        final boolean allowTaskSnapshot = (parameter & TYPE_PARAMETER_ALLOW_TASK_SNAPSHOT) != 0;
        final boolean activityCreated = (parameter & TYPE_PARAMETER_ACTIVITY_CREATED) != 0;
        final boolean useEmptySplashScreen =
                (parameter & TYPE_PARAMETER_USE_EMPTY_SPLASH_SCREEN) != 0;
        final boolean topIsHome = windowInfo.taskInfo.topActivityType == ACTIVITY_TYPE_HOME;

        if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
            Slog.d(TAG, "preferredStartingWindowType newTask " + newTask
                    + " taskSwitch " + taskSwitch
                    + " processRunning " + processRunning
                    + " allowTaskSnapshot " + allowTaskSnapshot
                    + " activityCreated " + activityCreated
                    + " useEmptySplashScreen " + useEmptySplashScreen
                    + " topIsHome " + topIsHome);
        }
        if (!topIsHome) {
            if (!processRunning) {
                return useEmptySplashScreen
                        ? STARTING_WINDOW_TYPE_EMPTY_SPLASH_SCREEN
                        : STARTING_WINDOW_TYPE_SPLASH_SCREEN;
            }
            if (newTask) {
                return useEmptySplashScreen
                        ? STARTING_WINDOW_TYPE_EMPTY_SPLASH_SCREEN
                        : STARTING_WINDOW_TYPE_SPLASH_SCREEN;
            }
            if (taskSwitch && !activityCreated) {
                return STARTING_WINDOW_TYPE_SPLASH_SCREEN;
            }
        }
        if (taskSwitch && allowTaskSnapshot) {
            if (isSnapshotCompatible(windowInfo)) {
                return STARTING_WINDOW_TYPE_SNAPSHOT;
            }
            if (!topIsHome) {
                return STARTING_WINDOW_TYPE_EMPTY_SPLASH_SCREEN;
            }
        }
        return STARTING_WINDOW_TYPE_NONE;
    }


    /**
     * Returns {@code true} if the task snapshot is compatible with this activity (at least the
     * rotation must be the same).
     */
    private boolean isSnapshotCompatible(StartingWindowInfo windowInfo) {
        final TaskSnapshot snapshot = windowInfo.mTaskSnapshot;
        if (snapshot == null) {
            if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                Slog.d(TAG, "isSnapshotCompatible no snapshot " + windowInfo.taskInfo.taskId);
            }
            return false;
        }
        if (!snapshot.getTopActivityComponent().equals(windowInfo.taskInfo.topActivity)) {
            if (DEBUG_SPLASH_SCREEN || DEBUG_TASK_SNAPSHOT) {
                Slog.d(TAG, "isSnapshotCompatible obsoleted snapshot "
                        + windowInfo.taskInfo.topActivity);
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
}
