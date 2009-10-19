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

import java.lang.ref.ReferenceQueue;
import java.lang.ref.SoftReference;
import java.util.LinkedList;
import java.util.ListIterator;

/** Utility class optimized for accumulating bytes, and then spitting
    them back out.  It does not optimize for returning the result in a
    single array, though this is supported in the API. It is fastest
    if the retrieval can be done via iterating through chunks.
*/
class ByteArrayBuilder {

    private static final int DEFAULT_CAPACITY = 8192;

    // Global pool of chunks to be used by other ByteArrayBuilders.
    private static final LinkedList<SoftReference<Chunk>> sPool =
            new LinkedList<SoftReference<Chunk>>();
    // Reference queue for processing gc'd entries.
    private static final ReferenceQueue<Chunk> sQueue =
            new ReferenceQueue<Chunk>();

    private LinkedList<Chunk> mChunks;

    public ByteArrayBuilder() {
        mChunks = new LinkedList<Chunk>();
    }

    public synchronized void append(byte[] array, int offset, int length) {
        while (length > 0) {
            Chunk c = null;
            if (mChunks.isEmpty()) {
                c = obtainChunk(length);
                mChunks.addLast(c);
            } else {
                c = mChunks.getLast();
                if (c.mLength == c.mArray.length) {
                    c = obtainChunk(length);
                    mChunks.addLast(c);
                }
            }
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
     * chunk out of the queue.  The caller must call Chunk.release() to
     * dispose of it.
     */
    public synchronized Chunk getFirstChunk() {
        if (mChunks.isEmpty()) return null;
        return mChunks.removeFirst();
    }

    public synchronized boolean isEmpty() {
        return mChunks.isEmpty();
    }

    public synchronized int getByteSize() {
        int total = 0;
        ListIterator<Chunk> it = mChunks.listIterator(0);
        while (it.hasNext()) {
            Chunk c = it.next();
            total += c.mLength;
        }
        return total;
    }

    public synchronized void clear() {
        Chunk c = getFirstChunk();
        while (c != null) {
            c.release();
            c = getFirstChunk();
        }
    }

    // Must be called with lock held on sPool.
    private void processPoolLocked() {
        while (true) {
            SoftReference<Chunk> entry = (SoftReference<Chunk>) sQueue.poll();
            if (entry == null) {
                break;
            }
            sPool.remove(entry);
        }
    }

    private Chunk obtainChunk(int length) {
        // Correct a small length.
        if (length < DEFAULT_CAPACITY) {
            length = DEFAULT_CAPACITY;
        }
        synchronized (sPool) {
            // Process any queued references and remove them from the pool.
            processPoolLocked();
            if (!sPool.isEmpty()) {
                Chunk c = sPool.removeFirst().get();
                // The first item may have been queued after processPoolLocked
                // so check for null.
                if (c != null) {
                    return c;
                }
            }
            return new Chunk(length);
        }
    }

    public static class Chunk {
        public byte[]  mArray;
        public int     mLength;

        public Chunk(int length) {
            mArray = new byte[length];
            mLength = 0;
        }

        /**
         * Release the chunk and make it available for reuse.
         */
        public void release() {
            mLength = 0;
            synchronized (sPool) {
                // Add the chunk back to the pool as a SoftReference so it can
                // be gc'd if needed.
                sPool.offer(new SoftReference<Chunk>(this, sQueue));
                sPool.notifyAll();
            }
        }

    }
}
