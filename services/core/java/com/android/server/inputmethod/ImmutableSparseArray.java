/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.server.inputmethod;


import android.annotation.AnyThread;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.SparseArray;

import java.util.function.Consumer;

/**
 * A holder object to expose {@link SparseArray} to multiple threads in a thread-safe manner through
 * "final Field Semantics" defined in JLS 17.5, with only exposing thread-safe methods such as
 * {@link SparseArray#get(int)} and {@link SparseArray#size()} from {@link SparseArray}, and with
 * adding clone-with-update style methods {@link #cloneWithPutOrSelf(int, Object)} and
 * {@link #cloneWithRemoveOrSelf(int)} instead of exposing mutation methods.
 *
 * @param <E> Type of the element
 */
final class ImmutableSparseArray<E> {
    @NonNull
    private final SparseArray<E> mArray;

    private static final ImmutableSparseArray<Object> EMPTY =
            new ImmutableSparseArray<>(new SparseArray<>());

    /**
     * Returns an empty {@link ImmutableSparseArray} instance.
     *
     * @return An empty {@link ImmutableSparseArray} instance.
     * @param <T> Type of the element
     */
    @SuppressWarnings("unchecked")
    @AnyThread
    @NonNull
    static <T> ImmutableSparseArray<T> empty() {
        return (ImmutableSparseArray<T>) EMPTY;
    }

    private ImmutableSparseArray(@NonNull SparseArray<E> array) {
        mArray = array;
    }

    /**
     * @return the size of this array
     */
    @AnyThread
    int size() {
        return mArray.size();
    }

    /**
     * Returns the key of the specified index.
     *
     * @return the key of the specified index
     * @throws ArrayIndexOutOfBoundsException when the index is out of range
     */
    @AnyThread
    int keyAt(int index) {
        return mArray.keyAt(index);
    }

    /**
     * Returns the value of the specified index.
     *
     * @return the value of the specified index
     * @throws ArrayIndexOutOfBoundsException when the index is out of range
     */
    @AnyThread
    @Nullable
    public E valueAt(int index) {
        return mArray.valueAt(index);
    }

    /**
     * Returns the index of the specified key.
     *
     * @return the index of the specified key if exists. Otherwise {@code -1}
     */
    @AnyThread
    int indexOfKey(int key) {
        return mArray.indexOfKey(key);
    }

    /**
     * Returns {@code true} if the given {@code key} exists.
     *
     * @param key the key to be queried
     * @return    {@code true} if the given {@code key} exists
     */
    @AnyThread
    boolean contains(int key) {
        return mArray.contains(key);
    }

    /**
     * Returns the value associated with the {@code key}.
     *
     * @param key the key to be queried
     * @return    the value associated with the {@code key} if exists. Otherwise {@code null}
     */
    @AnyThread
    @Nullable
    E get(int key) {
        return mArray.get(key);
    }

    /**
     * Run {@link Consumer} for each value.
     *
     * @param consumer {@link Consumer} to be called back
     */
    @AnyThread
    void forEach(@NonNull Consumer<E> consumer) {
        final int size = mArray.size();
        for (int i = 0; i < size; ++i) {
            consumer.accept(mArray.valueAt(i));
        }
    }

    /**
     * Returns an instance of {@link ImmutableSparseArray} that has the given key and value on top
     * of items cloned from this instance.
     *
     * @param key   the key to be added
     * @param value the value to be added
     * @return      the same {@link ImmutableSparseArray} instance if there is actually no update.
     *              Otherwise, a new instance of {@link ImmutableSparseArray}
     */
    @AnyThread
    @NonNull
    ImmutableSparseArray<E> cloneWithPutOrSelf(int key, @Nullable E value) {
        final var prevKeyIndex = mArray.indexOfKey(key);
        if (prevKeyIndex >= 0) {
            final var prevValue = mArray.valueAt(prevKeyIndex);
            if (prevValue == value) {
                return this;
            }
        }
        final var clone = mArray.clone();
        clone.put(key, value);
        return new ImmutableSparseArray<>(clone);
    }

    /**
     * Returns an instance of {@link ImmutableSparseArray} that does not have the given key on top
     * of items cloned from this instance.
     *
     * @param key the key to be removed
     * @return    the same {@link ImmutableSparseArray} instance if there is actually no update.
     *            Otherwise, a new instance of {@link ImmutableSparseArray}
     */
    @AnyThread
    @NonNull
    ImmutableSparseArray<E> cloneWithRemoveOrSelf(int key) {
        final int index = indexOfKey(key);
        if (index < 0) {
            return this;
        }
        if (mArray.size() == 1) {
            return empty();
        }
        final var clone = mArray.clone();
        clone.remove(key);
        return new ImmutableSparseArray<>(clone);
    }
}
