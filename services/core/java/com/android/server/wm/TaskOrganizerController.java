/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_PINNED;

import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;
import android.view.ITaskOrganizer;
import android.view.SurfaceControl;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Stores the TaskOrganizers associated with a given windowing mode and
 * their associated state.
 */
class TaskOrganizerController {
    private static final String TAG = "TaskOrganizerController";

    private WindowManagerGlobalLock mGlobalLock;

    private class DeathRecipient implements IBinder.DeathRecipient {
        int mWindowingMode;
        ITaskOrganizer mTaskOrganizer;

        DeathRecipient(ITaskOrganizer organizer, int windowingMode) {
            mTaskOrganizer = organizer;
            mWindowingMode = windowingMode;
        }

        @Override
        public void binderDied() {
            synchronized (mGlobalLock) {
                final TaskOrganizerState state = mTaskOrganizerStates.get(mTaskOrganizer);
                for (int i = 0; i < state.mOrganizedTasks.size(); i++) {
                    state.mOrganizedTasks.get(i).taskOrganizerDied();
                }
                mTaskOrganizerStates.remove(mTaskOrganizer);
                if (mTaskOrganizersForWindowingMode.get(mWindowingMode) == mTaskOrganizer) {
                    mTaskOrganizersForWindowingMode.remove(mWindowingMode);
                }
            }
        }
    };

    class TaskOrganizerState {
        ITaskOrganizer mOrganizer;
        DeathRecipient mDeathRecipient;

        ArrayList<Task> mOrganizedTasks = new ArrayList<>();

        void addTask(Task t) {
            mOrganizedTasks.add(t);
        }

        void removeTask(Task t) {
            mOrganizedTasks.remove(t);
        }

        TaskOrganizerState(ITaskOrganizer organizer, DeathRecipient deathRecipient) {
            mOrganizer = organizer;
            mDeathRecipient = deathRecipient;
        }
    };


    final HashMap<Integer, TaskOrganizerState> mTaskOrganizersForWindowingMode = new HashMap();
    final HashMap<ITaskOrganizer, TaskOrganizerState> mTaskOrganizerStates = new HashMap();

    final HashMap<Integer, ITaskOrganizer> mTaskOrganizersByPendingSyncId = new HashMap();

    final ActivityTaskManagerService mService;

    TaskOrganizerController(ActivityTaskManagerService atm, WindowManagerGlobalLock lock) {
        mService = atm;
        mGlobalLock = lock;
    }

    private void clearIfNeeded(int windowingMode) {
        final TaskOrganizerState oldState = mTaskOrganizersForWindowingMode.get(windowingMode);
        if (oldState != null) {
            oldState.mOrganizer.asBinder().unlinkToDeath(oldState.mDeathRecipient, 0);
        }
    }

    /**
     * Register a TaskOrganizer to manage tasks as they enter the given windowing mode.
     * If there was already a TaskOrganizer for this windowing mode it will be evicted
     * and receive taskVanished callbacks in the process.
     */
    void registerTaskOrganizer(ITaskOrganizer organizer, int windowingMode) {
        if (windowingMode != WINDOWING_MODE_PINNED) {
            throw new UnsupportedOperationException(
                    "As of now only Pinned windowing mode is supported for registerTaskOrganizer");

        }
        clearIfNeeded(windowingMode);
        DeathRecipient dr = new DeathRecipient(organizer, windowingMode);
        try {
            organizer.asBinder().linkToDeath(dr, 0);
        } catch (RemoteException e) {
            Slog.e(TAG, "TaskOrganizer failed to register death recipient");
        }

        final TaskOrganizerState state = new TaskOrganizerState(organizer, dr);
        mTaskOrganizersForWindowingMode.put(windowingMode, state);

        mTaskOrganizerStates.put(organizer, state);
    }

    ITaskOrganizer getTaskOrganizer(int windowingMode) {
        final TaskOrganizerState state = mTaskOrganizersForWindowingMode.get(windowingMode);
        if (state == null) {
            return null;
        }
        return state.mOrganizer;
    }

    private void sendTaskAppeared(ITaskOrganizer organizer, Task task) {
        try {
            organizer.taskAppeared(task.getRemoteToken(), task.getTaskInfo());
        } catch (Exception e) {
            Slog.e(TAG, "Exception sending taskAppeared callback" + e);
        }
    }

    private void sendTaskVanished(ITaskOrganizer organizer, Task task) {
        try {
            organizer.taskVanished(task.getRemoteToken());
        } catch (Exception e) {
            Slog.e(TAG, "Exception sending taskVanished callback" + e);
        }
    }

    void onTaskAppeared(ITaskOrganizer organizer, Task task) {
        TaskOrganizerState state = mTaskOrganizerStates.get(organizer);

        state.addTask(task);
        sendTaskAppeared(organizer, task);
    }

    void onTaskVanished(ITaskOrganizer organizer, Task task) {
        final TaskOrganizerState state = mTaskOrganizerStates.get(organizer);
        sendTaskVanished(organizer, task);

        // This could trigger TaskAppeared for other tasks in the same stack so make sure
        // we do this AFTER sending taskVanished.
        state.removeTask(task);
    }
}
