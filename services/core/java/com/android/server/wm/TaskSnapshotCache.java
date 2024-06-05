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

import android.annotation.Nullable;
import android.window.TaskSnapshot;

/**
 * Caches snapshots. See {@link TaskSnapshotController}.
 * <p>
 * Access to this class should be guarded by the global window manager lock.
 */
class TaskSnapshotCache extends SnapshotCache<Task> {

    private final AppSnapshotLoader mLoader;

    TaskSnapshotCache(AppSnapshotLoader loader) {
        super("Task");
        mLoader = loader;
    }

    void putSnapshot(Task task, TaskSnapshot snapshot) {
        synchronized (mLock) {
            snapshot.addReference(TaskSnapshot.REFERENCE_CACHE);
            final CacheEntry entry = mRunningCache.get(task.mTaskId);
            if (entry != null) {
                mAppIdMap.remove(entry.topApp);
                entry.snapshot.removeReference(TaskSnapshot.REFERENCE_CACHE);
            }
            final ActivityRecord top = task.getTopMostActivity();
            mAppIdMap.put(top, task.mTaskId);
            mRunningCache.put(task.mTaskId, new CacheEntry(snapshot, top));
        }
    }

    /**
     * If {@param restoreFromDisk} equals {@code true}, DO NOT HOLD THE WINDOW MANAGER LOCK!
     */
    @Nullable TaskSnapshot getSnapshot(int taskId, int userId, boolean restoreFromDisk,
            boolean isLowResolution) {
        final TaskSnapshot snapshot = getSnapshot(taskId);
        if (snapshot != null) {
            return snapshot;
        }

        // Try to restore from disk if asked.
        if (!restoreFromDisk) {
            return null;
        }
        return tryRestoreFromDisk(taskId, userId, isLowResolution);
    }

    /**
     * DO NOT HOLD THE WINDOW MANAGER LOCK WHEN CALLING THIS METHOD!
     */
    private TaskSnapshot tryRestoreFromDisk(int taskId, int userId, boolean isLowResolution) {
        return mLoader.loadTask(taskId, userId, isLowResolution);
    }
}
