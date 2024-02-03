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

import java.util.ArrayList;
import java.util.Arrays;

public class IntMap<T> {

    private static final int DEFAULT_CAPACITY = 16;
    private static final float LOAD_FACTOR = 0.75f;
    private static final int NOT_PRESENT = Integer.MIN_VALUE;
    private int[] mKeys;
    private ArrayList<T> mValues;
    int mSize;

    public IntMap() {
        mKeys = new int[DEFAULT_CAPACITY];
        Arrays.fill(mKeys, NOT_PRESENT);
        mValues = new ArrayList<T>(DEFAULT_CAPACITY);
        for (int i = 0; i < DEFAULT_CAPACITY; i++) {
            mValues.add(null);
        }
    }

    public void clear() {
        Arrays.fill(mKeys, NOT_PRESENT);
        mValues.clear();
        mSize = 0;
    }

    public T put(int key,  T value)  {
        if (key == NOT_PRESENT) throw new IllegalArgumentException("Key cannot be NOT_PRESENT");
        if (mSize > mKeys.length * LOAD_FACTOR) {
            resize();
        }
        return insert(key, value);
    }


    public  T get(int key) {
        int index = findKey(key);
        if (index == -1) {
            return  null;
        } else
            return mValues.get(index);
    }

    public int size() {
        return mSize;
    }

    private  T insert(int key, T value) {
        int index = hash(key) % mKeys.length;
        while (mKeys[index] != NOT_PRESENT && mKeys[index] != key) {
            index = (index + 1) % mKeys.length;
        }
        T oldValue =  null;
        if (mKeys[index] == NOT_PRESENT) {
            mSize++;
        } else {
            oldValue = mValues.get(index);
        }
        mKeys[index] = key;
        mValues.set(index, value);
        return oldValue;
    }

    private  int findKey(int key) {
        int index = hash(key) % mKeys.length;
        while (mKeys[index] != NOT_PRESENT) {
            if (mKeys[index] == key) {
                return index;
            }
            index = (index + 1) % mKeys.length;
        }
        return -1;
    }

    private  int hash(int key) {
        return key;
    }

    private   void resize() {
        int[] oldKeys = mKeys;
        ArrayList<T> oldValues = mValues;
        mKeys = new int[(oldKeys.length * 2)];
        for (int i = 0; i < mKeys.length; i++) {
            mKeys[i] = NOT_PRESENT;
        }
        mValues = new ArrayList<T>(oldKeys.length * 2);
        for (int i = 0; i < oldKeys.length * 2; i++) {
            mValues.add(null);
        }
        mSize = 0;
        for (int i = 0; i < oldKeys.length; i++) {
            if (oldKeys[i] != NOT_PRESENT) {
                put(oldKeys[i], oldValues.get(i));
            }
        }
    }
}
