/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.backup.utils;

import android.util.SparseArray;

import java.util.HashSet;

/**
 * Helper functions for manipulating instances of {@link SparseArray}.
 */
public final class SparseArrayUtils {
    // Statics only
    private SparseArrayUtils() {}

    /**
     * Given a {@link SparseArray<HashSet>}, returns a new {@link HashSet} containing every element
     * from every set in the array.
     *
     * @param sets The array of sets from which to take the union.
     * @param <V> The type of element contained in the set.
     * @return The complete set.
     */
    public static<V> HashSet<V> union(SparseArray<HashSet<V>> sets) {
        HashSet<V> unionSet = new HashSet<>();
        int n = sets.size();
        for (int i = 0; i < n; i++) {
            HashSet<V> ithSet = sets.valueAt(i);
            if (ithSet != null) {
                unionSet.addAll(ithSet);
            }
        }
        return unionSet;
    }
}
