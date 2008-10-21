/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */


package org.apache.harmony.x.imageio.stream;

import java.util.ArrayList;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class RandomAccessMemoryCache {
    private static final int BLOCK_SHIFT = 9;
    private static final int BLOCK_SIZE = 1 << BLOCK_SHIFT;
    private static final int BLOCK_MASK = BLOCK_SIZE - 1;
    
    private long length;

    private int firstUndisposed = 0;

    private ArrayList<byte[]> blocks = new ArrayList<byte[]>();

    public RandomAccessMemoryCache() {
    }

    public long length() {
        return length;
    }

    public void close() {
        blocks.clear();
        length = 0;
    }

    private void grow(long pos) {
        int blocksNeeded = (int)(pos >> BLOCK_SHIFT) - blocks.size() + 1;
        for (int i=0; i < blocksNeeded; i++) {
            blocks.add(new byte[BLOCK_SIZE]);
        }

        length = pos + 1;
    }

    public void putData(int oneByte, long pos) {
        if (pos >= length) {
            grow(pos);
        }

        byte[] block = blocks.get((int)(pos >> BLOCK_SHIFT));
        block[(int)(pos & BLOCK_MASK)] = (byte) oneByte;
    }

    public void putData(byte[] buffer, int offset, int count, long pos) {
        if (count > buffer.length - offset || count < 0 || offset < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (count == 0){
            return;
        }

        long lastPos = pos + count - 1;
        if (lastPos >= length) {
            grow(lastPos);
        }

        while (count > 0) {
            byte[] block = blocks.get((int)(pos >> BLOCK_SHIFT));
            int blockOffset = (int)(pos & BLOCK_MASK);
            int toCopy = Math.min(BLOCK_SIZE - blockOffset, count);
            System.arraycopy(buffer, offset, block, blockOffset, toCopy);
            pos += toCopy;
            count -= toCopy;
            offset += toCopy;
        }
    }

    public int getData(long pos) {
        if (pos >= length) {
            return -1;
        }

        byte[] block = blocks.get((int)(pos >> BLOCK_SHIFT));
        return block[(int)(pos & BLOCK_MASK)] & 0xFF;
    }

    public int getData(byte[] buffer, int offset, int count, long pos) {
        if (count > buffer.length - offset || count < 0 || offset < 0) {
            throw new IndexOutOfBoundsException();
        }
        if (count == 0) {
            return 0;
        }
        if (pos >= length) {
            return -1;
        }

        if (count + pos > length) {
            count = (int) (length - pos);
        }

        byte[] block = blocks.get((int)(pos >> BLOCK_SHIFT));
        int nbytes = Math.min(count, BLOCK_SIZE - (int)(pos & BLOCK_MASK));
        System.arraycopy(block, (int)(pos & BLOCK_MASK), buffer, offset, nbytes);

        return nbytes;
    }
    /*
    public void seek(long pos) throws IOException {
        if (pos < 0) {
            throw new IOException("seek position is negative");
        }
        this.pos = pos; 
    }

    public void readFully(byte[] buffer) throws IOException {
        readFully(buffer, 0, buffer.length);
    }

    public void readFully(byte[] buffer, int offset, int count) throws IOException {
        if (0 <= offset && offset <= buffer.length && 0 <= count && count <= buffer.length - offset) {
            while (count > 0) {
                int result = read(buffer, offset, count);
                if (result >= 0) {
                    offset += result;
                    count -= result;
                } else {
                    throw new EOFException();
                }
            }
        } else {
            throw new IndexOutOfBoundsException();
        }
    }

    public long getFilePointer() {
        return pos;
    }
*/

    public void freeBefore(long pos) {
        int blockIdx = (int)(pos >> BLOCK_SHIFT);
        if (blockIdx <= firstUndisposed) { // Nothing to do
            return;
        }

        for (int i = firstUndisposed; i < blockIdx; i++) {
            blocks.set(i, null);
        }

        firstUndisposed = blockIdx;
    }

    public int appendData(InputStream is, int count) throws IOException {
        if (count <= 0) {
            return 0;
        }

        long startPos = length;
        long lastPos = length + count - 1;
        grow(lastPos); // Changes length

        int blockIdx = (int)(startPos >> BLOCK_SHIFT);
        int offset = (int) (startPos & BLOCK_MASK);

        int bytesAppended = 0;

        while (count > 0) {
            byte[] block = blocks.get(blockIdx);
            int toCopy = Math.min(BLOCK_SIZE - offset, count);
            count -= toCopy;

            while (toCopy > 0) {
                int bytesRead = is.read(block, offset, toCopy);

                if (bytesRead < 0) {
                    length -= (count - bytesAppended);
                    return bytesAppended;
                }

                toCopy -= bytesRead;
                offset += bytesRead;
            }

            blockIdx++;
            offset = 0;
        }

        return count;
    }

    public void getData(OutputStream os, int count, long pos) throws IOException {
        if (pos + count > length) {
            throw new IndexOutOfBoundsException("Argument out of cache");
        }

        int blockIdx = (int)(pos >> BLOCK_SHIFT);
        int offset = (int) (pos & BLOCK_MASK);
        if (blockIdx < firstUndisposed) {
            throw new IndexOutOfBoundsException("The requested data are already disposed");
        }

        while (count > 0) {
            byte[] block = blocks.get(blockIdx);
            int toWrite = Math.min(BLOCK_SIZE - offset, count);
            os.write(block, offset, toWrite);

            blockIdx++;
            offset = 0;
            count -= toWrite;
        }
    }
}
