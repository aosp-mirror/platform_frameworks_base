/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.utils;

import android.annotation.NonNull;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.util.SparseSetArray;

/**
 * A collection of useful methods for manipulating Snapshot classes.  This is similar to
 * java.util.Objects or java.util.Arrays.
 */
public class Snapshots {

    /**
     * Return the snapshot of an object, if the object extends {@link Snapper}, or the object
     * itself.
     * @param o The object to be copied
     * @return A snapshot of the object, if the object extends {@link Snapper}
     */
    public static <T> T maybeSnapshot(T o) {
        if (o instanceof Snappable) {
            return ((Snappable<T>) o).snapshot();
        } else {
            return o;
        }
    }

    /**
     * Copy a SparseArray in a manner suitable for a snapshot.  The destination must be
     * empty.  This is not a snapshot because the elements are copied by reference even if
     * they are {@link Snappable}.
     * @param dst The destination array.  It must be empty.
     * @param src The source array
     */
    public static <E> void copy(@NonNull SparseArray<E> dst, @NonNull SparseArray<E> src) {
        if (dst.size() != 0) {
            throw new IllegalArgumentException("copy destination is not empty");
        }
        final int end = src.size();
        for (int i = 0; i < end; i++) {
            dst.put(src.keyAt(i), src.valueAt(i));
        }
    }

    /**
     * Copy a SparseSetArray in a manner suitable for a snapshot.  The destination must be
     * empty.  This is not a snapshot because the elements are copied by reference even if
     * they are {@link Snappable}.
     * @param dst The destination array.  It must be empty.
     * @param src The source array
     */
    public static <E> void copy(@NonNull SparseSetArray<E> dst, @NonNull SparseSetArray<E> src) {
        if (dst.size() != 0) {
            throw new IllegalArgumentException("copy destination is not empty");
        }
        final int end = src.size();
        for (int i = 0; i < end; i++) {
            final int size = src.sizeAt(i);
            for (int j = 0; j < size; j++) {
                dst.add(src.keyAt(i), src.valueAt(i, j));
            }
        }
    }

    /**
     * Make <dst> a snapshot of <src> .
     * @param dst The destination array.  It must be empty.
     * @param src The source array
     */
    public static void snapshot(@NonNull SparseIntArray dst, @NonNull SparseIntArray src) {
        if (dst.size() != 0) {
            throw new IllegalArgumentException("snapshot destination is not empty");
        }
        final int end = src.size();
        for (int i = 0; i < end; i++) {
            dst.put(src.keyAt(i), src.valueAt(i));
        }
    }

    /**
     * Make <dst> a "snapshot" of <src>.  <dst> mst be empty.  The destination is just a
     * copy of the source except that if the source elements implement Snappable, then
     * the elements in the destination will be snapshots of elements from the source.
     * @param dst The destination array.  It must be empty.
     * @param src The source array
     */
    public static <E extends Snappable<E>> void snapshot(@NonNull SparseArray<E> dst,
            @NonNull SparseArray<E> src) {
        if (dst.size() != 0) {
            throw new IllegalArgumentException("snapshot destination is not empty");
        }
        final int end = src.size();
        for (int i = 0; i < end; i++) {
            dst.put(src.keyAt(i), src.valueAt(i).snapshot());
        }
    }

    /**
     * Make <dst> a "snapshot" of <src>.  <dst> mst be empty.  The destination is a
     * copy of the source except that snapshots are taken of the elements.
     * @param dst The destination array.  It must be empty.
     * @param src The source array
     */
    public static <E extends Snappable<E>> void snapshot(@NonNull SparseSetArray<E> dst,
            @NonNull SparseSetArray<E> src) {
        if (dst.size() != 0) {
            throw new IllegalArgumentException("snapshot destination is not empty");
        }
        final int end = src.size();
        for (int i = 0; i < end; i++) {
            final int size = src.sizeAt(i);
            for (int j = 0; j < size; j++) {
                dst.add(src.keyAt(i), src.valueAt(i, j).snapshot());
            }
        }
    }
}
