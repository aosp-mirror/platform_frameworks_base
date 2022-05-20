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

package com.android.server;

import android.annotation.Nullable;
import android.util.ArrayMap;

import java.util.Collection;
import java.util.LinkedList;

/**
 * CircularQueue of length limit which puts keys in a circular LinkedList and values in an ArrayMap.
 * @param <K> key
 * @param <V> value
 */
public class CircularQueue<K, V> extends LinkedList<K> {
    private final int mLimit;
    private final ArrayMap<K, V> mArrayMap = new ArrayMap<>();

    public CircularQueue(int limit) {
        this.mLimit = limit;
    }

    @Override
    public boolean add(K k) throws IllegalArgumentException {
        throw new IllegalArgumentException("Call of add(key) prohibited. Please call put(key, "
                + "value) instead. ");
    }

    /**
     * Put a (key|value) pair in the CircularQueue. Only the key will be added to the queue. Value
     * will be added to the ArrayMap.
     * @return the most recently removed value if keys were removed, or {@code null} if no keys were
     * removed.
     */
    @Nullable
    public V put(K key, V value) {
        super.add(key);
        mArrayMap.put(key, value);
        V removedValue = null;
        while (size() > mLimit) {
            K removedKey = super.remove();
            removedValue = mArrayMap.remove(removedKey);
        }
        return removedValue;
    }

    /**
     * Removes the element for the provided key from the data structure.
     * @param key which should be removed
     * @return the value which was removed
     */
    public V removeElement(K key) {
        super.remove(key);
        return mArrayMap.remove(key);
    }

    /**
     * Retrieve a value from the array.
     * @param key The key of the value to retrieve.
     * @return Returns the value associated with the given key,
     * or null if there is no such key.
     */
    public V getElement(K key) {
        return mArrayMap.get(key);
    }

    /**
     * Check whether a key exists in the array.
     *
     * @param key The key to search for.
     * @return Returns true if the key exists, else false.
     */
    public boolean containsKey(K key) {
        return mArrayMap.containsKey(key);
    }

    /**
     * Return a {@link java.util.Collection} for iterating over and interacting with all values
     * in the array map.
     *
     * <p><b>Note:</b> this is a fairly inefficient way to access the array contents, it
     * requires generating a number of temporary objects and allocates additional state
     * information associated with the container that will remain for the life of the container.</p>
     */
    public Collection<V> values() {
        return mArrayMap.values();
    }
}
