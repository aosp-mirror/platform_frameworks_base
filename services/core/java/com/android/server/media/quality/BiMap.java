/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.media.quality;

import android.util.ArrayMap;

import java.util.Collection;
import java.util.Map;

/**
 * A very basic bidirectional map.
 *
 * @param <K> data type of Key
 * @param <V> data type of Value
 */
public class BiMap<K, V> {
    private Map<K, V> mPrimaryMap = new ArrayMap<>();
    private Map<V, K> mSecondaryMap = new ArrayMap<>();

    /**
     * Add key and associated value to the map
     *
     * @param key key to add
     * @param value value to add
     * @return true if successfully added, false otherwise
     */
    public boolean put(K key, V value) {
        if (key == null || value == null || mPrimaryMap.containsKey(key)
                || mSecondaryMap.containsKey(value)) {
            return false;
        }

        mPrimaryMap.put(key, value);
        mSecondaryMap.put(value, key);
        return true;
    }

    /**
     * Remove key and associated value from the map
     *
     * @param key key to remove
     * @return true if removed, false otherwise
     */
    public boolean remove(K key) {
        if (key == null) {
            return false;
        }
        if (mPrimaryMap.containsKey(key)) {
            V value = getValue(key);
            mPrimaryMap.remove(key);
            mSecondaryMap.remove(value);
            return true;
        }
        return false;
    }

    /**
     * Remove value and associated key from the map
     *
     * @param value value to remove
     * @return true if removed, false otherwise
     */
    public boolean removeValue(V value) {
        if (value == null) {
            return false;
        }
        return remove(getKey(value));
    }

    /**
     * Get the value
     *
     * @param key key for which to get value
     * @return V
     */
    public V getValue(K key) {
        return mPrimaryMap.get(key);
    }

    /**
     * Get the key
     *
     * @param value value for which to get key
     * @return K
     */
    public K getKey(V value) {
        return mSecondaryMap.get(value);
    }

    /**
     * Get the values of the map.
     * @return Collection
     */
    public Collection<V> getValues() {
        return mPrimaryMap.values();
    }

    /**
     * Clear the map
     */
    public void clear() {
        mPrimaryMap.clear();
        mSecondaryMap.clear();
    }
}
