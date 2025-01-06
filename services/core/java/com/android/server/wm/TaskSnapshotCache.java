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
     * Retrieves a snapshot from cache.
     */
    @Nullable TaskSnapshot getSnapshot(int taskId, boolean isLowResolution) {
        return getSnapshot(taskId, isLowResolution, TaskSnapshot.REFERENCE_NONE);
    }

    // TODO (b/238206323) Respect isLowResolution.
    @Nullable TaskSnapshot getSnapshot(int taskId, boolean isLowResolution,
            @TaskSnapshot.ReferenceFlags int usage) {
        synchronized (mLock) {
            final TaskSnapshot snapshot = getSnapshotInner(taskId);
            if (snapshot != null) {
                if (usage != TaskSnapshot.REFERENCE_NONE) {
                    snapshot.addReference(usage);
                }
                return snapshot;
            }
        }
        return null;
    }

    /**
     * Restore snapshot from disk, DO NOT HOLD THE WINDOW MANAGER LOCK!
     */
    @Nullable TaskSnapshot getSnapshotFromDisk(int taskId, int userId, boolean isLowResolution,
            @TaskSnapshot.ReferenceFlags int usage) {
        final TaskSnapshot snapshot = mLoader.loadTask(taskId, userId, isLowResolution);
        // Note: This can be weird if the caller didn't ask for reference.
        if (snapshot != null && usage != TaskSnapshot.REFERENCE_NONE) {
            snapshot.addReference(usage);
        }
        return snapshot;
    }
}
