/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.accessorydisplay.common;

import java.nio.ByteBuffer;

/**
 * Maintains a bounded pool of buffers.  Attempts to acquire buffers beyond the maximum
 * count will block until other buffers are released.
 */
final class BufferPool {
    private final int mInitialBufferSize;
    private final int mMaxBufferSize;
    private final ByteBuffer[] mBuffers;
    private int mAllocated;
    private int mAvailable;

    public BufferPool(int initialBufferSize, int maxBufferSize, int maxBuffers) {
        mInitialBufferSize = initialBufferSize;
        mMaxBufferSize = maxBufferSize;
        mBuffers = new ByteBuffer[maxBuffers];
    }

    public ByteBuffer acquire(int needed) {
        synchronized (this) {
            for (;;) {
                if (mAvailable != 0) {
                    mAvailable -= 1;
                    return grow(mBuffers[mAvailable], needed);
                }

                if (mAllocated < mBuffers.length) {
                    mAllocated += 1;
                    return ByteBuffer.allocate(chooseCapacity(mInitialBufferSize, needed));
                }

                try {
                    wait();
                } catch (InterruptedException ex) {
                }
            }
        }
    }

    public void release(ByteBuffer buffer) {
        synchronized (this) {
            buffer.clear();
            mBuffers[mAvailable++] = buffer;
            notifyAll();
        }
    }

    public ByteBuffer grow(ByteBuffer buffer, int needed) {
        int capacity = buffer.capacity();
        if (capacity < needed) {
            final ByteBuffer oldBuffer = buffer;
            capacity = chooseCapacity(capacity, needed);
            buffer = ByteBuffer.allocate(capacity);
            oldBuffer.flip();
            buffer.put(oldBuffer);
        }
        return buffer;
    }

    private int chooseCapacity(int capacity, int needed) {
        while (capacity < needed) {
            capacity *= 2;
        }
        if (capacity > mMaxBufferSize) {
            if (needed > mMaxBufferSize) {
                throw new IllegalArgumentException("Requested size " + needed
                        + " is larger than maximum buffer size " + mMaxBufferSize + ".");
            }
            capacity = mMaxBufferSize;
        }
        return capacity;
    }
}
