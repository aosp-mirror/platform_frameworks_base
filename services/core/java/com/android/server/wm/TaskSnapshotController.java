/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.ActivityManager.ENABLE_TASK_SNAPSHOTS;

import android.annotation.Nullable;
import android.app.ActivityManager.StackId;
import android.app.ActivityManager.TaskSnapshot;
import android.graphics.GraphicBuffer;
import android.os.Environment;
import android.util.ArraySet;
import android.view.WindowManagerPolicy.StartingSurface;

import com.google.android.collect.Sets;

import com.android.internal.annotations.VisibleForTesting;

import java.io.PrintWriter;

/**
 * When an app token becomes invisible, we take a snapshot (bitmap) of the corresponding task and
 * put it into our cache. Internally we use gralloc buffers to be able to draw them wherever we
 * like without any copying.
 * <p>
 * System applications may retrieve a snapshot to represent the current state of a task, and draw
 * them in their own process.
 * <p>
 * When we task becomes visible again, we show a starting window with the snapshot as the content to
 * make app transitions more responsive.
 * <p>
 * To access this class, acquire the global window manager lock.
 */
class TaskSnapshotController {

    private final WindowManagerService mService;

    private final TaskSnapshotCache mCache;
    private final TaskSnapshotPersister mPersister = new TaskSnapshotPersister(
            Environment::getDataSystemCeDirectory);
    private final TaskSnapshotLoader mLoader = new TaskSnapshotLoader(mPersister);
    private final ArraySet<Task> mTmpTasks = new ArraySet<>();

    TaskSnapshotController(WindowManagerService service) {
        mService = service;
        mCache = new TaskSnapshotCache(mService, mLoader);
    }

    void systemReady() {
        mPersister.start();
    }

    void onTransitionStarting() {
        if (!ENABLE_TASK_SNAPSHOTS) {
            return;
        }
        handleClosingApps(mService.mClosingApps);
    }


    /**
     * Called when the visibility of an app changes outside of the regular app transition flow.
     */
    void notifyAppVisibilityChanged(AppWindowToken appWindowToken, boolean visible) {
        if (!ENABLE_TASK_SNAPSHOTS) {
            return;
        }
        if (!visible) {
            handleClosingApps(Sets.newArraySet(appWindowToken));
        }
    }

    private void handleClosingApps(ArraySet<AppWindowToken> closingApps) {

        // We need to take a snapshot of the task if and only if all activities of the task are
        // either closing or hidden.
        getClosingTasks(closingApps, mTmpTasks);
        for (int i = mTmpTasks.size() - 1; i >= 0; i--) {
            final Task task = mTmpTasks.valueAt(i);
            if (!canSnapshotTask(task)) {
                continue;
            }
            final TaskSnapshot snapshot = snapshotTask(task);
            if (snapshot != null) {
                mCache.putSnapshot(task, snapshot);
                mPersister.persistSnapshot(task.mTaskId, task.mUserId, snapshot);
                if (task.getController() != null) {
                    task.getController().reportSnapshotChanged(snapshot);
                }
            }
        }
    }

    /**
     * Retrieves a snapshot. If {@param restoreFromDisk} equals {@code true}, DO HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    @Nullable TaskSnapshot getSnapshot(int taskId, int userId, boolean restoreFromDisk) {
        return mCache.getSnapshot(taskId, userId, restoreFromDisk);
    }

    /**
     * Creates a starting surface for {@param token} with {@param snapshot}. DO NOT HOLD THE WINDOW
     * MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    StartingSurface createStartingSurface(AppWindowToken token,
            GraphicBuffer snapshot) {
        return TaskSnapshotSurface.create(mService, token, snapshot);
    }

    private TaskSnapshot snapshotTask(Task task) {
        final AppWindowToken top = task.getTopChild();
        if (top == null) {
            return null;
        }
        final GraphicBuffer buffer = top.mDisplayContent.screenshotApplicationsToBuffer(top.token,
                -1, -1, false, 1.0f, false, true);
        if (buffer == null) {
            return null;
        }
        return new TaskSnapshot(buffer, top.getConfiguration().orientation,
                top.findMainWindow().mStableInsets);
    }

    /**
     * Retrieves all closing tasks based on the list of closing apps during an app transition.
     */
    @VisibleForTesting
    void getClosingTasks(ArraySet<AppWindowToken> closingApps, ArraySet<Task> outClosingTasks) {
        outClosingTasks.clear();
        for (int i = closingApps.size() - 1; i >= 0; i--) {
            final AppWindowToken atoken = closingApps.valueAt(i);

            // If the task of the app is not visible anymore, it means no other app in that task
            // is opening. Thus, the task is closing.
            if (atoken.mTask != null && !atoken.mTask.isVisible()) {
                outClosingTasks.add(closingApps.valueAt(i).mTask);
            }
        }
    }

    private boolean canSnapshotTask(Task task) {
        return !StackId.isHomeOrRecentsStack(task.mStack.mStackId);
    }

    /**
     * Called when an {@link AppWindowToken} has been removed.
     */
    void onAppRemoved(AppWindowToken wtoken) {
        mCache.onAppRemoved(wtoken);
    }

    /**
     * Called when the process of an {@link AppWindowToken} has died.
     */
    void onAppDied(AppWindowToken wtoken) {
        mCache.onAppDied(wtoken);
    }

    void notifyTaskRemovedFromRecents(int taskId, int userId) {
        mCache.onTaskRemoved(taskId);
        mPersister.onTaskRemovedFromRecents(taskId, userId);
    }

    /**
     * See {@link TaskSnapshotPersister#removeObsoleteFiles}
     */
    void removeObsoleteTaskFiles(ArraySet<Integer> persistentTaskIds, int[] runningUserIds) {
        mPersister.removeObsoleteFiles(persistentTaskIds, runningUserIds);
    }

    void dump(PrintWriter pw, String prefix) {
        mCache.dump(pw, prefix);
    }
}
