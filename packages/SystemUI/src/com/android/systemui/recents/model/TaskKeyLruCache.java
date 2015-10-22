/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.systemui.recents.model;

import android.util.LruCache;
import android.util.SparseArray;

/**
 * A mapping of {@link Task.TaskKey} to value, with additional LRU functionality where the least
 * recently referenced key/values will be evicted as more values than the given cache size are
 * inserted.
 *
 * In addition, this also allows the caller to invalidate cached values for keys that have since
 * changed.
 */
public class TaskKeyLruCache<V> {

    private final SparseArray<Task.TaskKey> mKeys = new SparseArray<>();
    private final LruCache<Integer, V> mCache;

    public TaskKeyLruCache(int cacheSize) {
        mCache = new LruCache<Integer, V>(cacheSize) {

            @Override
            protected void entryRemoved(boolean evicted, Integer taskId, V oldV, V newV) {
                mKeys.remove(taskId);
            }
        };
    }

    /**
     * Gets a specific entry in the cache with the specified key, regardless of whether the cached
     * value is valid or not.
     */
    final V get(Task.TaskKey key) {
        return mCache.get(key.id);
    }

    /**
     * Returns the value only if the key is valid (has not been updated since the last time it was
     * in the cache)
     */
    final V getAndInvalidateIfModified(Task.TaskKey key) {
        Task.TaskKey lastKey = mKeys.get(key.id);
        if (lastKey != null) {
            if ((lastKey.stackId != key.stackId) || (lastKey.lastActiveTime < key.lastActiveTime)) {
                // The task has updated (been made active since the last time it was put into the
                // LRU cache) or the stack id for the task has changed, invalidate that cache item
                remove(key);
                return null;
            }
        }
        // Either the task does not exist in the cache, or the last active time is the same as
        // the key specified, so return what is in the cache
        return mCache.get(key.id);
    }

    /** Puts an entry in the cache for a specific key. */
    final void put(Task.TaskKey key, V value) {
        mKeys.put(key.id, key);
        mCache.put(key.id, value);
    }

    /** Removes a cache entry for a specific key. */
    final void remove(Task.TaskKey key) {
        mKeys.remove(key.id);
        mCache.remove(key.id);
    }

    /** Removes all the entries in the cache. */
    final void evictAll() {
        mCache.evictAll();
        mKeys.clear();
    }

    /** Trims the cache to a specific size */
    final void trimToSize(int cacheSize) {
        mCache.resize(cacheSize);
    }
}
