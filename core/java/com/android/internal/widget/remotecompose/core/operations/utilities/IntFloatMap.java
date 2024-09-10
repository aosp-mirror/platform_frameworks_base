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
package com.android.internal.widget.remotecompose.core.operations.utilities;

import java.util.Arrays;

public class IntFloatMap {

    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    private static final int NOT_PRESENT = Integer.MIN_VALUE;
    private int[] mKeys;
    private float[] mValues;
    int mSize;

    public IntFloatMap() {
        mKeys = new int[DEFAULT_CAPACITY];
        Arrays.fill(mKeys, NOT_PRESENT);
        mValues = new float[DEFAULT_CAPACITY];
    }

    /**
     * clear the map
     */
    public void clear() {
        Arrays.fill(mKeys, NOT_PRESENT);
        Arrays.fill(mValues, Float.NaN); // not strictly necessary but defensive
        mSize = 0;
    }

    /**
     * is the key contained in map
     *
     * @param key the key to check
     * @return true if the map contains the key
     */
    public boolean contains(int key) {
        return findKey(key) != -1;
    }

    /**
     * Put a item in the map
     *
     * @param key item's key
     * @param value item's value
     * @return old value if exist
     */
    public float put(int key, float value) {
        if (key == NOT_PRESENT) {
            throw new IllegalArgumentException("Key cannot be NOT_PRESENT");
        }
        if (mSize > mKeys.length * LOAD_FACTOR) {
            resize();
        }
        return insert(key, value);
    }

    /**
     * get an element given the key
     *
     * @param key the key to fetch
     * @return the value
     */
    public float get(int key) {
        int index = findKey(key);
        if (index == -1) {
            return 0;
        } else
            return mValues[index];
    }

    /**
     * how many elements in the map
     *
     * @return number of elements
     */
    public int size() {
        return mSize;
    }

    private float insert(int key, float value) {
        int index = hash(key) % mKeys.length;
        while (mKeys[index] != NOT_PRESENT && mKeys[index] != key) {
            index = (index + 1) % mKeys.length;
        }
        float oldValue = 0;
        if (mKeys[index] == NOT_PRESENT) {
            mSize++;
        } else {
            oldValue = mValues[index];
        }
        mKeys[index] = key;
        mValues[index] = value;
        return oldValue;
    }

    private int findKey(int key) {
        int index = hash(key) % mKeys.length;
        while (mKeys[index] != NOT_PRESENT) {
            if (mKeys[index] == key) {
                return index;
            }
            index = (index + 1) % mKeys.length;
        }
        return -1;
    }

    private int hash(int key) {
        return key;
    }

    private void resize() {
        int[] oldKeys = mKeys;
        float[] oldValues = mValues;
        mKeys = new int[(oldKeys.length * 2)];
        for (int i = 0; i < mKeys.length; i++) {
            mKeys[i] = NOT_PRESENT;
        }
        mValues = new float[oldKeys.length * 2];
        mSize = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != NOT_PRESENT) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }
}
