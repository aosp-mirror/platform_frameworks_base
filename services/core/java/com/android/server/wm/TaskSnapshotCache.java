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
import android.app.ActivityManager.TaskSnapshot;
import android.util.ArrayMap;

import java.io.PrintWriter;

/**
 * Caches snapshots. See {@link TaskSnapshotController}.
 * <p>
 * Access to this class should be guarded by the global window manager lock.
 */
class TaskSnapshotCache {

    private final ArrayMap<AppWindowToken, Task> mAppTaskMap = new ArrayMap<>();
    private final ArrayMap<Task, CacheEntry> mCache = new ArrayMap<>();

    void putSnapshot(Task task, TaskSnapshot snapshot) {
        final CacheEntry entry = mCache.get(task);
        if (entry != null) {
            mAppTaskMap.remove(entry.topApp);
        }
        final AppWindowToken top = task.getTopChild();
        mAppTaskMap.put(top, task);
        mCache.put(task, new CacheEntry(snapshot, task.getTopChild()));
    }

    @Nullable TaskSnapshot getSnapshot(Task task) {
        final CacheEntry entry = mCache.get(task);
        return entry != null ? entry.snapshot : null;
    }

    /**
     * Cleans the cache after an app window token's process died.
     */
    void cleanCache(AppWindowToken wtoken) {
        final Task task = mAppTaskMap.get(wtoken);
        if (task != null) {
            removeEntry(task);
        }
    }

    private void removeEntry(Task task) {
        final CacheEntry entry = mCache.get(task);
        if (entry != null) {
            mAppTaskMap.remove(entry.topApp);
            mCache.remove(task);
        }
    }

    void dump(PrintWriter pw, String prefix) {
        final String doublePrefix = prefix + "  ";
        final String triplePrefix = doublePrefix + "  ";
        pw.println(prefix + "SnapshotCache");
        for (int i = mCache.size() - 1; i >= 0; i--) {
            final CacheEntry entry = mCache.valueAt(i);
            pw.println(doublePrefix + "Entry taskId=" + mCache.keyAt(i).mTaskId);
            pw.println(triplePrefix + "topApp=" + entry.topApp);
            pw.println(triplePrefix + "snapshot=" + entry.snapshot);
        }
    }

    private static final class CacheEntry {

        /** The snapshot. */
        final TaskSnapshot snapshot;

        /** The app token that was on top of the task when the snapshot was taken */
        final AppWindowToken topApp;

        CacheEntry(TaskSnapshot snapshot, AppWindowToken topApp) {
            this.snapshot = snapshot;
            this.topApp = topApp;
        }
    }
}
