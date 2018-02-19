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

package com.android.server.wm.utils;

import android.util.SparseArray;

import java.util.Arrays;

/**
 * Caches the result of a rotation-dependent computation.
 *
 * The cache is discarded once the identity of the other parameter changes.
 *
 * @param <T> type of the parameter to the computation
 * @param <R> type of the result of the computation
 */
public class RotationCache<T,R> {

    private final RotationDependentComputation<T,R> mComputation;
    private final SparseArray<R> mCache = new SparseArray<>(4);
    private T mCachedFor;

    public RotationCache(RotationDependentComputation<T, R> computation) {
        mComputation = computation;
    }

    /**
     * Looks up the result of the computation, or calculates it if needed.
     *
     * @param t a parameter to the rotation-dependent computation.
     * @param rotation the rotation for which to perform the rotation-dependent computation.
     * @return the result of the rotation-dependent computation.
     */
    public R getOrCompute(T t, int rotation) {
        if (t != mCachedFor) {
            mCache.clear();
            mCachedFor = t;
        }
        final int idx = mCache.indexOfKey(rotation);
        if (idx >= 0) {
            return mCache.valueAt(idx);
        }
        final R result = mComputation.compute(t, rotation);
        mCache.put(rotation, result);
        return result;
    }

    /**
     * A computation that takes a generic input and is dependent on the rotation. The result can
     * be cached by {@link RotationCache}.
     */
    @FunctionalInterface
    public interface RotationDependentComputation<T, R> {
        R compute(T t, int rotation);
    }
}
