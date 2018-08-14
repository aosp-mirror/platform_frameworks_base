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
 * limitations under the License.
 */

package com.android.internal.util;

import static com.android.internal.util.Preconditions.checkArgumentPositive;

import java.lang.reflect.Array;
import java.util.Arrays;

/**
 * A simple ring buffer structure with bounded capacity backed by an array.
 * Events can always be added at the logical end of the buffer. If the buffer is
 * full, oldest events are dropped when new events are added.
 * {@hide}
 */
public class RingBuffer<T> {

    // Array for storing events.
    private final T[] mBuffer;
    // Cursor keeping track of the logical end of the array. This cursor never
    // wraps and instead keeps track of the total number of append() operations.
    private long mCursor = 0;

    public RingBuffer(Class<T> c, int capacity) {
        checkArgumentPositive(capacity, "A RingBuffer cannot have 0 capacity");
        // Java cannot create generic arrays without a runtime hint.
        mBuffer = (T[]) Array.newInstance(c, capacity);
    }

    public int size() {
        return (int) Math.min(mBuffer.length, (long) mCursor);
    }

    public boolean isEmpty() {
        return size() == 0;
    }

    public void clear() {
        for (int i = 0; i < size(); ++i) {
            mBuffer[i] = null;
        }
        mCursor = 0;
    }

    public void append(T t) {
        mBuffer[indexOf(mCursor++)] = t;
    }

    /**
     * Returns object of type <T> at the next writable slot, creating one if it is not already
     * available. In case of any errors while creating the object, <code>null</code> will
     * be returned.
     */
    public T getNextSlot() {
        final int nextSlotIdx = indexOf(mCursor++);
        if (mBuffer[nextSlotIdx] == null) {
            mBuffer[nextSlotIdx] = createNewItem();
        }
        return mBuffer[nextSlotIdx];
    }

    /**
     * @return a new object of type <T> or null if a new object could not be created.
     */
    protected T createNewItem() {
        try {
            return (T) mBuffer.getClass().getComponentType().newInstance();
        } catch (IllegalAccessException | InstantiationException e) {
            return null;
        }
    }

    public T[] toArray() {
        // Only generic way to create a T[] from another T[]
        T[] out = Arrays.copyOf(mBuffer, size(), (Class<T[]>) mBuffer.getClass());
        // Reverse iteration from youngest event to oldest event.
        long inCursor = mCursor - 1;
        int outIdx = out.length - 1;
        while (outIdx >= 0) {
            out[outIdx--] = (T) mBuffer[indexOf(inCursor--)];
        }
        return out;
    }

    private int indexOf(long cursor) {
        return (int) Math.abs(cursor % mBuffer.length);
    }
}
