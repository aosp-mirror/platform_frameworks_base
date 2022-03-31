/*
 * Copyright (C) 2018 The Android Open Source Project
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

/**
 * A sparse array of ArraySets, which is suitable to hold userid->packages association.
 *
 * @hide
 */
public class SparseSetArray<T> {
    private final SparseArray<ArraySet<T>> mData;

    public SparseSetArray() {
        mData = new SparseArray<>();
    }

    /**
     * Copy constructor
     */
    public SparseSetArray(SparseSetArray<T> src) {
        final int arraySize = src.size();
        mData = new SparseArray<>(arraySize);
        for (int i = 0; i < arraySize; i++) {
            final int key = src.keyAt(i);
            final ArraySet<T> set = src.get(key);
            final int setSize = set.size();
            for (int j = 0; j < setSize; j++) {
                add(key, set.valueAt(j));
            }
        }
    }

    /**
     * Add a value for key n.
     * @return FALSE when the value already existed for the given key, TRUE otherwise.
     */
    public boolean add(int n, T value) {
        ArraySet<T> set = mData.get(n);
        if (set == null) {
            set = new ArraySet<>();
            mData.put(n, set);
        }
        if (set.contains(value)) {
            return false;
        }
        set.add(value);
        return true;
    }

    /**
     * Removes all mappings from this SparseSetArray.
     */
    public void clear() {
        mData.clear();
    }

    /**
     * @return whether the value exists for the key n.
     */
    public boolean contains(int n, T value) {
        final ArraySet<T> set = mData.get(n);
        if (set == null) {
            return false;
        }
        return set.contains(value);
    }

    /**
     * @return the set of items of key n
     */
    public ArraySet<T> get(int n) {
        return mData.get(n);
    }

    /**
     * Remove a value for key n.
     * @return TRUE when the value existed for the given key and removed, FALSE otherwise.
     */
    public boolean remove(int n, T value) {
        final ArraySet<T> set = mData.get(n);
        if (set == null) {
            return false;
        }
        final boolean ret = set.remove(value);
        if (set.size() == 0) {
            mData.remove(n);
        }
        return ret;
    }

    /**
     * Remove all values for key n.
     */
    public void remove(int n) {
        mData.remove(n);
    }
    public int size() {
        return mData.size();
    }

    public int keyAt(int index) {
        return mData.keyAt(index);
    }

    public int sizeAt(int index) {
        final ArraySet<T> set = mData.valueAt(index);
        if (set == null) {
            return 0;
        }
        return set.size();
    }

    public T valueAt(int intIndex, int valueIndex) {
        return mData.valueAt(intIndex).valueAt(valueIndex);
    }
}
