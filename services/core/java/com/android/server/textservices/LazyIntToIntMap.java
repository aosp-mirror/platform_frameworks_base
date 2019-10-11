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

package com.android.server.textservices;

import android.annotation.NonNull;
import android.util.SparseIntArray;

import java.util.function.IntUnaryOperator;

/**
 * Simple int-to-int key-value-store that is to be lazily initialized with the given
 * {@link IntUnaryOperator}.
 */
final class LazyIntToIntMap {

    private final SparseIntArray mMap = new SparseIntArray();

    @NonNull
    private final IntUnaryOperator mMappingFunction;

    /**
     * @param mappingFunction int to int mapping rules to be (lazily) evaluated
     */
    public LazyIntToIntMap(@NonNull IntUnaryOperator mappingFunction) {
        mMappingFunction = mappingFunction;
    }

    /**
     * Deletes {@code key} and associated value.
     * @param key key to be deleted
     */
    public void delete(int key) {
        mMap.delete(key);
    }

    /**
     * @param key key associated with the value
     * @return value associated with the {@code key}. If this is the first time to access
     * {@code key}, then {@code mappingFunction} passed to the constructor will be evaluated
     */
    public int get(int key) {
        final int index = mMap.indexOfKey(key);
        if (index >= 0) {
            return mMap.valueAt(index);
        }
        final int value = mMappingFunction.applyAsInt(key);
        mMap.append(key, value);
        return value;
    }
}
