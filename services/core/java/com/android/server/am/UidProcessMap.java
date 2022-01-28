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

package com.android.server.am;

import android.util.ArrayMap;
import android.util.SparseArray;

/**
 * Utility class to track mappings between (UID, name) and E.
 *
 * @param <E> The type of the values in this map.
 */
public class UidProcessMap<E> {
    final SparseArray<ArrayMap<String, E>> mMap = new SparseArray<>();

    /**
     * Retrieve a value from the map.
     */
    public E get(int uid, String name) {
        final ArrayMap<String, E> names = mMap.get(uid);
        if (names == null) {
            return null;
        }
        return names.get(name);
    }

    /**
     * Add a new value to the array map.
     */
    public E put(int uid, String name, E value) {
        ArrayMap<String, E> names = mMap.get(uid);
        if (names == null) {
            names = new ArrayMap<String, E>(2);
            mMap.put(uid, names);
        }
        names.put(name, value);
        return value;
    }

    /**
     * Remove an existing key (uid, name) from the array map.
     */
    public E remove(int uid, String name) {
        final int index = mMap.indexOfKey(uid);
        if (index < 0) {
            return null;
        }
        final ArrayMap<String, E> names = mMap.valueAt(index);
        if (names != null) {
            final E old = names.remove(name);
            if (names.isEmpty()) {
                mMap.removeAt(index);
            }
            return old;
        }
        return null;
    }

    /**
     * Return the underneath map.
     */
    public SparseArray<ArrayMap<String, E>> getMap() {
        return mMap;
    }

    /**
     * Return the number of items in this map.
     */
    public int size() {
        return mMap.size();
    }

    /**
     * Make the map empty. All storage is released.
     */
    public void clear() {
        mMap.clear();
    }

    /**
     * Perform a {@link #put} of all key/value pairs in other.
     */
    public void putAll(UidProcessMap<E> other) {
        for (int i = other.mMap.size() - 1; i >= 0; i--) {
            final int uid = other.mMap.keyAt(i);
            final ArrayMap<String, E> names = mMap.get(uid);
            if (names != null) {
                names.putAll(other.mMap.valueAt(i));
            } else {
                mMap.put(uid, new ArrayMap<String, E>(other.mMap.valueAt(i)));
            }
        }
    }
}
