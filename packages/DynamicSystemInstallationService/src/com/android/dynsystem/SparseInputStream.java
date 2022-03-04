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

package com.android.dynsystem;

import static java.lang.Math.min;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * SparseInputStream read from upstream and detects the data format. If the upstream is a valid
 * sparse data, it will unsparse it on the fly. Otherwise, it just passthrough as is.
 */
public class SparseInputStream extends InputStream {
    static final int FILE_HDR_SIZE = 28;
    static final int CHUNK_HDR_SIZE = 12;

    /**
     * This class represents a chunk in the Android sparse image.
     *
     * @see system/core/libsparse/sparse_format.h
     */
    private class SparseChunk {
        static final short RAW = (short) 0xCAC1;
        static final short FILL = (short) 0xCAC2;
        static final short DONTCARE = (short) 0xCAC3;
        public short mChunkType;
        public int mChunkSize;
        public int mTotalSize;
        public byte[] fill;
        public String toString() {
            return String.format(
                    "type: %x, chunk_size: %d, total_size: %d", mChunkType, mChunkSize, mTotalSize);
        }
    }

    private byte[] readFull(InputStream in, int size) throws IOException {
        byte[] buf = new byte[size];
        for (int done = 0, n = 0; done < size; done += n) {
            if ((n = in.read(buf, done, size - done)) < 0) {
                throw new IOException("Failed to readFull");
            }
        }
        return buf;
    }

    private ByteBuffer readBuffer(InputStream in, int size) throws IOException {
        return ByteBuffer.wrap(readFull(in, size)).order(ByteOrder.LITTLE_ENDIAN);
    }

    private SparseChunk readChunk(InputStream in) throws IOException {
        SparseChunk chunk = new SparseChunk();
        ByteBuffer buf = readBuffer(in, CHUNK_HDR_SIZE);
        chunk.mChunkType = buf.getShort();
        buf.getShort();
        chunk.mChunkSize = buf.getInt();
        chunk.mTotalSize = buf.getInt();
        return chunk;
    }

    private BufferedInputStream mIn;
    private boolean mIsSparse;
    private long mBlockSize;
    private long mTotalBlocks;
    private long mTotalChunks;
    private SparseChunk mCur;
    private long mLeft;
    private int mCurChunks;

    public SparseInputStream(BufferedInputStream in) throws IOException {
        mIn = in;
        in.mark(FILE_HDR_SIZE * 2);
        ByteBuffer buf = readBuffer(mIn, FILE_HDR_SIZE);
        mIsSparse = (buf.getInt() == 0xed26ff3a);
        if (!mIsSparse) {
            mIn.reset();
            return;
        }
        int major = buf.getShort();
        int minor = buf.getShort();

        if (major > 0x1 || minor > 0x0) {
            throw new IOException("Unsupported sparse version: " + major + "." + minor);
        }

        if (buf.getShort() != FILE_HDR_SIZE) {
            throw new IOException("Illegal file header size");
        }
        if (buf.getShort() != CHUNK_HDR_SIZE) {
            throw new IOException("Illegal chunk header size");
        }
        mBlockSize = buf.getInt();
        if ((mBlockSize & 0x3) != 0) {
            throw new IOException("Illegal block size, must be a multiple of 4");
        }
        mTotalBlocks = buf.getInt();
        mTotalChunks = buf.getInt();
        mLeft = mCurChunks = 0;
    }

    /**
     * Check if it needs to open a new chunk.
     *
     * @return true if it's EOF
     */
    private boolean prepareChunk() throws IOException {
        if (mCur == null || mLeft <= 0) {
            if (++mCurChunks > mTotalChunks) return true;
            mCur = readChunk(mIn);
            if (mCur.mChunkType == SparseChunk.FILL) {
                mCur.fill = readFull(mIn, 4);
            }
            mLeft = mCur.mChunkSize * mBlockSize;
        }
        return mLeft == 0;
    }

    /**
     * It overrides the InputStream.read(byte[] buf)
     */
    public int read(byte[] buf) throws IOException {
        if (!mIsSparse) {
            return mIn.read(buf);
        }
        if (prepareChunk()) return -1;
        int n = -1;
        switch (mCur.mChunkType) {
            case SparseChunk.RAW:
                n = mIn.read(buf, 0, (int) min(mLeft, buf.length));
                mLeft -= n;
                return n;
            case SparseChunk.DONTCARE:
                n = (int) min(mLeft, buf.length);
                Arrays.fill(buf, 0, n - 1, (byte) 0);
                mLeft -= n;
                return n;
            case SparseChunk.FILL:
                // The FILL type is rarely used, so use a simple implmentation.
                return super.read(buf);
            default:
                throw new IOException("Unsupported Chunk:" + mCur.toString());
        }
    }

    /**
     * It overrides the InputStream.read()
     */
    public int read() throws IOException {
        if (!mIsSparse) {
            return mIn.read();
        }
        if (prepareChunk()) return -1;
        int ret = -1;
        switch (mCur.mChunkType) {
            case SparseChunk.RAW:
                ret = mIn.read();
                break;
            case SparseChunk.DONTCARE:
                ret = 0;
                break;
            case SparseChunk.FILL:
                ret = Byte.toUnsignedInt(mCur.fill[(4 - ((int) mLeft & 0x3)) & 0x3]);
                break;
            default:
                throw new IOException("Unsupported Chunk:" + mCur.toString());
        }
        mLeft--;
        return ret;
    }

    /**
     * Get the unsparse size
     * @return -1 if unknown
     */
    public long getUnsparseSize() {
        if (!mIsSparse) {
            return -1;
        }
        return mBlockSize * mTotalBlocks;
    }
}
