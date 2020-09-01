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
 * @param <T> Any class
 * @hide
 */
@TestApi
public class SparseArrayMap<T> {
    private final SparseArray<ArrayMap<String, T>> mData = new SparseArray<>();

    /** Add an entry associating obj with the int-String pair. */
    public void add(int key, @NonNull String mapKey, @Nullable T obj) {
        ArrayMap<String, T> data = mData.get(key);
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

    /** Return true if the structure contains an explicit entry for the int-String pair. */
    public boolean contains(int key, @NonNull String mapKey) {
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
    public T delete(int key, @NonNull String mapKey) {
        ArrayMap<String, T> data = mData.get(key);
        if (data != null) {
            return data.remove(mapKey);
        }
        return null;
    }

    /**
     * Get the value associated with the int-String pair.
     */
    @Nullable
    public T get(int key, @NonNull String mapKey) {
        ArrayMap<String, T> data = mData.get(key);
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
    public T getOrDefault(int key, @NonNull String mapKey, T defaultValue) {
        if (mData.contains(key)) {
            ArrayMap<String, T> data = mData.get(key);
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
    public int indexOfKey(int key, @NonNull String mapKey) {
        ArrayMap<String, T> data = mData.get(key);
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
    public String keyAt(int keyIndex, int mapIndex) {
        return mData.valueAt(keyIndex).keyAt(mapIndex);
    }

    /** Returns the size of the outer array. */
    public int numMaps() {
        return mData.size();
    }

    /** Returns the number of elements in the map of the given key. */
    public int numElementsForKey(int key) {
        ArrayMap<String, T> data = mData.get(key);
        return data == null ? 0 : data.size();
    }

    /** Returns the value T at the given key and map index. */
    @Nullable
    public T valueAt(int keyIndex, int mapIndex) {
        return mData.valueAt(keyIndex).valueAt(mapIndex);
    }

    /** Iterate through all int-String pairs and operate on all of the values. */
    public void forEach(@NonNull Consumer<T> consumer) {
        for (int i = numMaps() - 1; i >= 0; --i) {
            ArrayMap<String, T> data = mData.valueAt(i);
            for (int j = data.size() - 1; j >= 0; --j) {
                consumer.accept(data.valueAt(j));
            }
        }
    }
}
