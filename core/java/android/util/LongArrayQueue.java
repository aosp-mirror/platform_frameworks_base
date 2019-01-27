/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.util;

import com.android.internal.util.ArrayUtils;
import com.android.internal.util.GrowingArrayUtils;

import libcore.util.EmptyArray;

import java.util.NoSuchElementException;

/**
 * A lightweight implementation for a queue with long values.
 * Additionally supports getting an element with a specified position from the head of the queue.
 * The queue grows in size if needed to accommodate new elements.
 *
 * @hide
 */
public class LongArrayQueue {

    private long[] mValues;
    private int mSize;
    private int mHead;
    private int mTail;

    /**
     * Initializes a queue with the given starting capacity.
     *
     * @param initialCapacity the capacity.
     */
    public LongArrayQueue(int initialCapacity) {
        if (initialCapacity == 0) {
            mValues = EmptyArray.LONG;
        } else {
            mValues = ArrayUtils.newUnpaddedLongArray(initialCapacity);
        }
        mSize = 0;
        mHead = mTail = 0;
    }

    /**
     * Initializes a queue with default starting capacity.
     */
    public LongArrayQueue() {
        this(16);
    }

    private void grow() {
        if (mSize < mValues.length) {
            throw new IllegalStateException("Queue not full yet!");
        }
        final int newSize = GrowingArrayUtils.growSize(mSize);
        final long[] newArray = ArrayUtils.newUnpaddedLongArray(newSize);
        final int r = mValues.length - mHead; // Number of elements on and to the right of head.
        System.arraycopy(mValues, mHead, newArray, 0, r);
        System.arraycopy(mValues, 0, newArray, r, mHead);
        mValues = newArray;
        mHead = 0;
        mTail = mSize;
    }

    /**
     * Returns the number of elements in the queue.
     */
    public int size() {
        return mSize;
    }

    /**
     * Removes all elements from this queue.
     */
    public void clear() {
        mSize = 0;
        mHead = mTail = 0;
    }

    /**
     * Adds a value to the tail of the queue.
     *
     * @param value the value to be added.
     */
    public void addLast(long value) {
        if (mSize == mValues.length) {
            grow();
        }
        mValues[mTail] = value;
        mTail = (mTail + 1) % mValues.length;
        mSize++;
    }

    /**
     * Removes an element from the head of the queue.
     *
     * @return the element at the head of the queue.
     * @throws NoSuchElementException if the queue is empty.
     */
    public long removeFirst() {
        if (mSize == 0) {
            throw new NoSuchElementException("Queue is empty!");
        }
        final long ret = mValues[mHead];
        mHead = (mHead + 1) % mValues.length;
        mSize--;
        return ret;
    }

    /**
     * Returns the element at the given position from the head of the queue, where 0 represents the
     * head of the queue.
     *
     * @param position the position from the head of the queue.
     * @return the element found at the given position.
     * @throws IndexOutOfBoundsException if {@code position} < {@code 0} or
     *                                   {@code position} >= {@link #size()}
     */
    public long get(int position) {
        if (position < 0 || position >= mSize) {
            throw new IndexOutOfBoundsException("Index " + position
                    + " not valid for a queue of size " + mSize);
        }
        final int index = (mHead + position) % mValues.length;
        return mValues[index];
    }

    /**
     * Returns the element at the head of the queue, without removing it.
     *
     * @return the element at the head of the queue.
     * @throws NoSuchElementException if the queue is empty
     */
    public long peekFirst() {
        if (mSize == 0) {
            throw new NoSuchElementException("Queue is empty!");
        }
        return mValues[mHead];
    }

    /**
     * Returns the element at the tail of the queue.
     *
     * @return the element at the tail of the queue.
     * @throws NoSuchElementException if the queue is empty.
     */
    public long peekLast() {
        if (mSize == 0) {
            throw new NoSuchElementException("Queue is empty!");
        }
        final int index = (mTail == 0) ? mValues.length - 1 : mTail - 1;
        return mValues[index];
    }
}
