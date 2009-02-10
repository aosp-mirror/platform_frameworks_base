/*
 * Copyright (C) 2006 The Android Open Source Project
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

package android.webkit;

import java.util.LinkedList;

/** Utility class optimized for accumulating bytes, and then spitting
    them back out.  It does not optimize for returning the result in a
    single array, though this is supported in the API. It is fastest
    if the retrieval can be done via iterating through chunks.

    Things to add:
      - consider dynamically increasing our min_capacity,
        as we see mTotalSize increase
*/
class ByteArrayBuilder {

    private static final int DEFAULT_CAPACITY = 8192;

    private LinkedList<Chunk> mChunks;

    /** free pool */
    private LinkedList<Chunk> mPool;

    private int mMinCapacity;

    public ByteArrayBuilder() {
        init(0);
    }

    public ByteArrayBuilder(int minCapacity) {
        init(minCapacity);
    }

    private void init(int minCapacity) {
        mChunks = new LinkedList<Chunk>();
        mPool = new LinkedList<Chunk>();

        if (minCapacity <= 0) {
            minCapacity = DEFAULT_CAPACITY;
        }
        mMinCapacity = minCapacity;
    }

    public void append(byte[] array) {
        append(array, 0, array.length);
    }

    public synchronized void append(byte[] array, int offset, int length) {
        while (length > 0) {
            Chunk c = appendChunk(length);
            int amount = Math.min(length, c.mArray.length - c.mLength);
            System.arraycopy(array, offset, c.mArray, c.mLength, amount);
            c.mLength += amount;
            length -= amount;
            offset += amount;
        }
    }

    /**
     * The fastest way to retrieve the data is to iterate through the
     * chunks.  This returns the first chunk.  Note: this pulls the
     * chunk out of the queue.  The caller must call releaseChunk() to
     * dispose of it.
     */
    public synchronized Chunk getFirstChunk() {
        if (mChunks.isEmpty()) return null;
        return mChunks.removeFirst();
    }

    /**
     * recycles chunk
     */
    public synchronized void releaseChunk(Chunk c) {
        c.mLength = 0;
        mPool.addLast(c);
    }

    public boolean isEmpty() {
        return mChunks.isEmpty();
    }

    public synchronized void clear() {
        Chunk c = getFirstChunk();
        while (c != null) {
            releaseChunk(c);
            c = getFirstChunk();
        }
    }

    private Chunk appendChunk(int length) {
        if (length < mMinCapacity) {
            length = mMinCapacity;
        }

        Chunk c;
        if (mChunks.isEmpty()) {
            c = obtainChunk(length);
        } else {
            c = mChunks.getLast();
            if (c.mLength == c.mArray.length) {
                c = obtainChunk(length);
            }
        }
        return c;
    }

    private Chunk obtainChunk(int length) {
        Chunk c;
        if (mPool.isEmpty()) {
            c = new Chunk(length);
        } else {
            c = mPool.removeFirst();
        }
        mChunks.addLast(c);
        return c;
    }

    public static class Chunk {
        public byte[]  mArray;
        public int     mLength;

        public Chunk(int length) {
            mArray = new byte[length];
            mLength = 0;
        }
    }
}
