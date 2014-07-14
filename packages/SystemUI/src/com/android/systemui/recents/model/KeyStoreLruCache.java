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

import java.util.HashMap;

/**
 * An LRU cache that support querying the keys as well as values. By using the Task's key, we can
 * prevent holding onto a reference to the Task resource data, while keeping the cache data in
 * memory where necessary.
 */
public class KeyStoreLruCache<V> {
    // We keep a set of keys that are associated with the LRU cache, so that we can find out
    // information about the Task that was previously in the cache.
    HashMap<Task.TaskKey, Task.TaskKey> mKeys = new HashMap<Task.TaskKey, Task.TaskKey>();
    // The cache implementation
    LruCache<Task.TaskKey, V> mCache;

    public KeyStoreLruCache(int cacheSize) {
        mCache = new LruCache<Task.TaskKey, V>(cacheSize) {
            @Override
            protected int sizeOf(Task.TaskKey t, V v) {
                return computeSize(v);
            }

            @Override
            protected void entryRemoved(boolean evicted, Task.TaskKey key, V oldV, V newV) {
                mKeys.remove(key);
            }
        };
    }

    /** Computes the size of a value. */
    protected int computeSize(V value) {
        return 0;
    }

    /** Gets a specific entry in the cache. */
    final V get(Task.TaskKey key) {
        return mCache.get(key);
    }

    /**
     * Returns the value only if the last active time of the key currently in the lru cache is
     * greater than or equal to the last active time of the key specified.
     */
    final V getCheckLastActiveTime(Task.TaskKey key) {
        Task.TaskKey lruKey = mKeys.get(key);
        if (lruKey != null && (lruKey.lastActiveTime < key.lastActiveTime)) {
            // The task has changed (been made active since the last time it was put into the
            // LRU cache) so invalidate that item in the cache
            remove(lruKey);
            return null;
        }
        // Either the task does not exist in the cache, or the last active time is the same as
        // the key specified
        return mCache.get(key);
    }

    /** Puts an entry in the cache for a specific key. */
    final void put(Task.TaskKey key, V value) {
        mCache.put(key, value);
        if (mKeys.containsKey(key)) {
            mKeys.get(key).updateLastActiveTime(key.lastActiveTime);
        } else {
            mKeys.put(key, key);
        }
    }

    /** Removes a cache entry for a specific key. */
    final void remove(Task.TaskKey key) {
        mCache.remove(key);
        mKeys.remove(key);
    }

    /** Removes all the entries in the cache. */
    final void evictAll() {
        mCache.evictAll();
        mKeys.clear();
    }

    /** Returns the size of the cache. */
    final int size() {
        return mCache.size();
    }

    /** Trims the cache to a specific size */
    final void trimToSize(int cacheSize) {
        mCache.resize(cacheSize);
    }
}
