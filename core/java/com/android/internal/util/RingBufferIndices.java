/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.util;

/**
 * Helper class for implementing a ring buffer.  This supplies the indices, you supply
 * the array(s).
 */
public class RingBufferIndices {
    private final int mCapacity;

    // The first valid element and the next open slot.
    private int mStart;
    private int mSize;

    /**
     * Create ring buffer of the given capacity.
     */
    public RingBufferIndices(int capacity) {
        mCapacity = capacity;
    }

    /**
     * Add a new item to the ring buffer.  If the ring buffer is full, this
     * replaces the oldest item.
     * @return Returns the index at which the new item appears, for placing in your array.
     */
    public int add() {
        if (mSize < mCapacity) {
            final int pos = mSize;
            mSize++;
            return pos;
        }
        int pos = mStart;
        mStart++;
        if (mStart == mCapacity) {
            mStart = 0;
        }
        return pos;
    }

    /**
     * Clear the ring buffer.
     */
    public void clear() {
        mStart = 0;
        mSize = 0;
    }

    /**
     * Return the current size of the ring buffer.
     */
    public int size() {
        return mSize;
    }

    /**
     * Convert a position in the ring buffer that is [0..size()] to an offset
     * in the array(s) containing the ring buffer items.
     */
    public int indexOf(int pos) {
        int index = mStart + pos;
        if (index >= mCapacity) {
            index -= mCapacity;
        }
        return index;
    }
}
