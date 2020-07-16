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

package com.android.server.textclassifier;

import android.annotation.NonNull;
import android.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;

/**
 * A fixed-size queue which automatically evicts the oldest element from the queue when it is full.
 *
 * <p>This class does not accept null element.
 *
 * @param <E> the type of elements held in this queue
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class FixedSizeQueue<E> {

    private final Queue<E> mDelegate;

    @Nullable
    private final OnEntryEvictedListener<E> mOnEntryEvictedListener;

    private final int mMaxSize;

    public FixedSizeQueue(int maxSize, @Nullable OnEntryEvictedListener<E> onEntryEvictedListener) {
        Preconditions.checkArgument(maxSize > 0, "maxSize (%s) must > 0", maxSize);
        mDelegate = new ArrayDeque<>(maxSize);
        mMaxSize = maxSize;
        mOnEntryEvictedListener = onEntryEvictedListener;
    }

    /** Returns the number of items in the queue. */
    public int size() {
        return mDelegate.size();
    }

    /** Adds an element to the queue, evicts the oldest element if it reaches its max capacity. */
    public boolean add(@NonNull E element) {
        Objects.requireNonNull(element);
        if (size() == mMaxSize) {
            E removed = mDelegate.remove();
            if (mOnEntryEvictedListener != null) {
                mOnEntryEvictedListener.onEntryEvicted(removed);
            }
        }
        mDelegate.add(element);
        return true;
    }

    /**
     * Returns and removes the head of the queue, or returns null if this queue is empty.
     */
    @Nullable
    public E poll() {
        return mDelegate.poll();
    }

    /**
     * Removes an element from the queue, returns a boolean to indicate if an element is removed.
     */
    public boolean remove(@NonNull E element) {
        Objects.requireNonNull(element);
        return mDelegate.remove(element);
    }

    /** Returns whether the queue is empty. */
    public boolean isEmpty() {
        return mDelegate.isEmpty();
    }

    /**
     * A listener to get notified when an element is evicted.
     *
     * @param <E> the type of element
     */
    public interface OnEntryEvictedListener<E> {
        /**
         * Notifies that an element is evicted because the queue is reaching its max capacity.
         */
        void onEntryEvicted(@NonNull E element);
    }
}
