/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.flags;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Threadsafe cache of values that stores the supplied default on cache miss.
 *
 * @param <V> The type of value to store.
 */
public class FlagCache<V> {
    private final Function<String, HashMap<String, V>> mNewHashMap = k -> new HashMap<>();

    // Cache is organized first by namespace, then by name. All values are stored as strings.
    final Map<String, Map<String, V>> mCache = new HashMap<>();

    FlagCache() {
    }

    /**
     * Returns true if the namespace exists in the cache already.
     */
    boolean containsNamespace(String namespace) {
        synchronized (mCache) {
            return mCache.containsKey(namespace);
        }
    }

    /**
     * Returns true if the value is stored in the cache.
     */
    boolean contains(String namespace, String name) {
        synchronized (mCache) {
            Map<String, V> nsCache = mCache.get(namespace);
            return nsCache != null && nsCache.containsKey(name);
        }
    }

    /**
     * Sets the value if it is different from what is currently stored.
     *
     * If the value is not set, or the current value is null, it will store the value and
     * return true.
     *
     * @return True if the value was set. False if the value is the same.
     */
    boolean setIfChanged(String namespace, String name, V value) {
        synchronized (mCache) {
            Map<String, V> nsCache = mCache.computeIfAbsent(namespace, mNewHashMap);
            V curValue = nsCache.get(name);
            if (curValue == null || !curValue.equals(value)) {
                nsCache.put(name, value);
                return true;
            }
            return false;
        }
    }

    /**
     * Gets the current value from the cache, setting it if it is currently absent.
     *
     * @return The value that is now in the cache after the call to the method.
     */
    V getOrSet(String namespace, String name, V defaultValue) {
        synchronized (mCache) {
            Map<String, V> nsCache = mCache.computeIfAbsent(namespace, mNewHashMap);
            V value = nsCache.putIfAbsent(name, defaultValue);
            return value == null ? defaultValue : value;
        }
    }

    /**
     * Gets the current value from the cache, returning null if not present.
     *
     * @return The value that is now in the cache if there is one.
     */
    V getOrNull(String namespace, String name) {
        synchronized (mCache) {
            Map<String, V> nsCache = mCache.get(namespace);
            if (nsCache == null) {
                return null;
            }
            return nsCache.get(name);
        }
    }
}
