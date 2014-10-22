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

package com.android.server.hdmi;

import android.util.SparseIntArray;

/**
 * Unmodifiable version of {@link SparseIntArray}.
 */
final class UnmodifiableSparseIntArray {
    private static final String TAG = "ImmutableSparseIntArray";

    private final SparseIntArray mArray;

    public UnmodifiableSparseIntArray(SparseIntArray array) {
        mArray = array;
    }

    public int size() {
        return mArray.size();
    }

    public int get(int key) {
        return mArray.get(key);
    }

    public int get(int key, int valueIfKeyNotFound) {
        return mArray.get(key, valueIfKeyNotFound);
    }

    public int keyAt(int index) {
        return mArray.keyAt(index);
    }

    public int valueAt(int index) {
        return mArray.valueAt(index);
    }

    public int indexOfValue(int value) {
        return mArray.indexOfValue(value);
    }

    @Override
    public String toString() {
        return mArray.toString();
    }
}
