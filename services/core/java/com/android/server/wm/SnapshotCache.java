/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.Nullable;
import android.util.ArrayMap;
import android.window.TaskSnapshot;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;

/**
 * Base class for an app snapshot cache
 * @param <TYPE> The basic type, either Task or ActivityRecord
 */
abstract class SnapshotCache<TYPE extends WindowContainer> {
    protected final Object mLock = new Object();

    protected final String mName;

    @GuardedBy("mLock")
    protected final ArrayMap<ActivityRecord, Integer> mAppIdMap = new ArrayMap<>();

    @GuardedBy("mLock")
    protected final ArrayMap<Integer, CacheEntry> mRunningCache = new ArrayMap<>();

    SnapshotCache(String name) {
        mName = name;
    }

    abstract void putSnapshot(TYPE window, TaskSnapshot snapshot);

    void clearRunningCache() {
        synchronized (mLock) {
            mRunningCache.clear();
        }
    }

    @Nullable
    final TaskSnapshot getSnapshot(Integer id) {
        synchronized (mLock) {
            // Try the running cache.
            final CacheEntry entry = mRunningCache.get(id);
            if (entry != null) {
                return entry.snapshot;
            }
        }
        return null;
    }

    /** Called when an app token has been removed. */
    void onAppRemoved(ActivityRecord activity) {
        synchronized (mLock) {
            final Integer id = mAppIdMap.get(activity);
            if (id != null) {
                removeRunningEntry(id);
            }
        }
    }

    /** Called when an app window token's process died. */
    void onAppDied(ActivityRecord activity) {
        synchronized (mLock) {
            final Integer id = mAppIdMap.get(activity);
            if (id != null) {
                removeRunningEntry(id);
            }
        }
    }

    void onIdRemoved(Integer index) {
        removeRunningEntry(index);
    }

    void removeRunningEntry(Integer id) {
        synchronized (mLock) {
            final CacheEntry entry = mRunningCache.get(id);
            if (entry != null) {
                mAppIdMap.remove(entry.topApp);
                mRunningCache.remove(id);
            }
        }
    }

    void dump(PrintWriter pw, String prefix) {
        final String doublePrefix = prefix + "  ";
        final String triplePrefix = doublePrefix + "  ";
        pw.println(prefix + "SnapshotCache " + mName);

        synchronized (mLock) {
            for (int i = mRunningCache.size() - 1; i >= 0; i--) {
                final CacheEntry entry = mRunningCache.valueAt(i);
                pw.println(doublePrefix + "Entry token=" + mRunningCache.keyAt(i));
                pw.println(triplePrefix + "topApp=" + entry.topApp);
                pw.println(triplePrefix + "snapshot=" + entry.snapshot);
            }
        }
    }

    static final class CacheEntry {
        /** The snapshot. */
        final TaskSnapshot snapshot;
        /** The app token that was on top of the task when the snapshot was taken */
        final ActivityRecord topApp;
        CacheEntry(TaskSnapshot snapshot, ActivityRecord topApp) {
            this.snapshot = snapshot;
            this.topApp = topApp;
        }
    }
}
