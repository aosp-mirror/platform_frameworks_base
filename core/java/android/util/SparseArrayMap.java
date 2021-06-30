/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;

import java.util.function.Consumer;

/**
 * A sparse array of ArrayMaps, which is suitable for holding (userId, packageName)->object
 * associations.
 *
 * @param <K> Any class
 * @param <V> Any class
 * @hide
 */
@TestApi
public class SparseArrayMap<K, V> {
    private final SparseArray<ArrayMap<K, V>> mData = new SparseArray<>();

    /** Add an entry associating obj with the int-K pair. */
    public void add(int key, @NonNull K mapKey, @Nullable V obj) {
        ArrayMap<K, V> data = mData.get(key);
        if (data == null) {
            data = new ArrayMap<>();
            mData.put(key, data);
        }
        data.put(mapKey, obj);
    }

    /** Remove all entries from the map. */
    public void clear() {
        for (int i = 0; i < mData.size(); ++i) {
            mData.valueAt(i).clear();
        }
    }

    /** Return true if the structure contains an explicit entry for the int-K pair. */
    public boolean contains(int key, @NonNull K mapKey) {
        return mData.contains(key) && mData.get(key).containsKey(mapKey);
    }

    /** Removes all the data for the key, if there was any. */
    public void delete(int key) {
        mData.delete(key);
    }

    /**
     * Removes the data for the key and mapKey, if there was any.
     *
     * @return Returns the value that was stored under the keys, or null if there was none.
     */
    @Nullable
    public V delete(int key, @NonNull K mapKey) {
        ArrayMap<K, V> data = mData.get(key);
        if (data != null) {
            return data.remove(mapKey);
        }
        return null;
    }

    /**
     * Get the value associated with the int-K pair.
     */
    @Nullable
    public V get(int key, @NonNull K mapKey) {
        ArrayMap<K, V> data = mData.get(key);
        if (data != null) {
            return data.get(mapKey);
        }
        return null;
    }

    /**
     * Returns the value to which the specified key and mapKey are mapped, or defaultValue if this
     * map contains no mapping for them.
     */
    @Nullable
    public V getOrDefault(int key, @NonNull K mapKey, V defaultValue) {
        if (mData.contains(key)) {
            ArrayMap<K, V> data = mData.get(key);
            if (data != null && data.containsKey(mapKey)) {
                return data.get(mapKey);
            }
        }
        return defaultValue;
    }

    /** @see SparseArray#indexOfKey */
    public int indexOfKey(int key) {
        return mData.indexOfKey(key);
    }

    /**
     * Returns the index of the mapKey.
     *
     * @see SparseArray#indexOfKey
     */
    public int indexOfKey(int key, @NonNull K mapKey) {
        ArrayMap<K, V> data = mData.get(key);
        if (data != null) {
            return data.indexOfKey(mapKey);
        }
        return -1;
    }

    /** Returns the key at the given index. */
    public int keyAt(int index) {
        return mData.keyAt(index);
    }

    /** Returns the map's key at the given mapIndex for the given keyIndex. */
    @NonNull
    public K keyAt(int keyIndex, int mapIndex) {
        return mData.valueAt(keyIndex).keyAt(mapIndex);
    }

    /** Returns the size of the outer array. */
    public int numMaps() {
        return mData.size();
    }

    /** Returns the number of elements in the map of the given key. */
    public int numElementsForKey(int key) {
        ArrayMap<K, V> data = mData.get(key);
        return data == null ? 0 : data.size();
    }

    /** Returns the value V at the given key and map index. */
    @Nullable
    public V valueAt(int keyIndex, int mapIndex) {
        return mData.valueAt(keyIndex).valueAt(mapIndex);
    }

    /** Iterate through all int-K pairs and operate on all of the values. */
    public void forEach(@NonNull Consumer<V> consumer) {
        for (int i = numMaps() - 1; i >= 0; --i) {
            ArrayMap<K, V> data = mData.valueAt(i);
            for (int j = data.size() - 1; j >= 0; --j) {
                consumer.accept(data.valueAt(j));
            }
        }
    }

    /**
     * @param <K> Any class
     * @param <V> Any class
     * @hide
     */
    public interface TriConsumer<K, V> {
        /** Consume the int-K-V tuple. */
        void accept(int key, K mapKey, V value);
    }

    /**
     * Iterate through all int-K pairs and operate on all of the values.
     * @hide
     */
    public void forEach(@NonNull TriConsumer<K, V> consumer) {
        for (int iIdx = numMaps() - 1; iIdx >= 0; --iIdx) {
            final int i = mData.keyAt(iIdx);
            final ArrayMap<K, V> data = mData.valueAt(i);
            for (int kIdx = data.size() - 1; kIdx >= 0; --kIdx) {
                consumer.accept(i, data.keyAt(kIdx), data.valueAt(kIdx));
            }
        }
    }
}
