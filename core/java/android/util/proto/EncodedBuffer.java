/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.util.proto;

import android.annotation.TestApi;
import android.util.Log;

import java.util.ArrayList;

/**
 * A stream of bytes containing a read pointer and a write pointer,
 * backed by a set of fixed-size buffers.  There are write functions for the
 * primitive types stored by protocol buffers, but none of the logic
 * for tags, inner objects, or any of that.
 *
 * Terminology:
 *      *Pos:       Position in the whole data set (as if it were a single buffer).
 *      *Index:     Position within a buffer.
 *      *BufIndex:  Index of a buffer within the mBuffers list
 * @hide
 */
@TestApi
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public final class EncodedBuffer {
    private static final String TAG = "EncodedBuffer";

    private final ArrayList<byte[]> mBuffers = new ArrayList<byte[]>();

    private final int mChunkSize;

    /**
     * The number of buffers in mBuffers. Stored separately to avoid the extra
     * function call to size() everywhere for bounds checking.
     */
    private int mBufferCount;

    /**
     * The buffer we are currently writing to.
     */
    private byte[] mWriteBuffer;

    /**
     * The index into mWriteBuffer that we will write to next.
     * It may point to the end of the buffer, in which case,
     * the NEXT write will allocate a new buffer.
     */
    private int mWriteIndex;

    /**
     * The index of mWriteBuffer in mBuffers.
     */
    private int mWriteBufIndex;

    /**
     * The buffer we are currently reading from.
     */
    private byte[] mReadBuffer;

    /**
     * The index of mReadBuffer in mBuffers.
     */
    private int mReadBufIndex;

    /**
     * The index into mReadBuffer that we will read from next.
     * It may point to the end of the buffer, in which case,
     * the NEXT read will advance to the next buffer.
     */
    private int mReadIndex;

    /**
     * The amount of data in the last buffer.
     */
    private int mReadLimit = -1;

    /**
     * How much data there is total.
     */
    private int mReadableSize = -1;

    public EncodedBuffer() {
        this(0);
    }

    /**
     * Construct an EncodedBuffer object.
     *
     * @param chunkSize The size of the buffers to use.  If chunkSize &lt;= 0, a default
     *                  size will be used instead.
     */
    public EncodedBuffer(int chunkSize) {
        if (chunkSize <= 0) {
            chunkSize = 8 * 1024;
        }
        mChunkSize = chunkSize;
        mWriteBuffer = new byte[mChunkSize];
        mBuffers.add(mWriteBuffer);
        mBufferCount = 1;
    }

    //
    // Buffer management.
    //

    /**
     * Rewind the read and write pointers, and record how much data was last written.
     */
    public void startEditing() {
        mReadableSize = ((mWriteBufIndex) * mChunkSize) + mWriteIndex;
        mReadLimit = mWriteIndex;

        mWriteBuffer = mBuffers.get(0);
        mWriteIndex = 0;
        mWriteBufIndex = 0;

        mReadBuffer = mWriteBuffer;
        mReadBufIndex = 0;
        mReadIndex = 0;
    }

    /**
     * Rewind the read pointer. Don't touch the write pointer.
     */
    public void rewindRead() {
        mReadBuffer = mBuffers.get(0);
        mReadBufIndex = 0;
        mReadIndex = 0;
    }

    /**
     * Only valid after startEditing. Returns -1 before that.
     */
    public int getReadableSize() {
        return mReadableSize;
    }

    /**
     * Returns the buffer size
     * @return the buffer size
     */
    public int getSize() {
        return ((mBufferCount - 1) * mChunkSize) + mWriteIndex;
    }

    //
    // Reading from the read position.
    //

    /**
     * Only valid after startEditing.
     */
    public int getReadPos() {
        return ((mReadBufIndex) * mChunkSize) + mReadIndex;
    }

    /**
     * Skip over _amount_ bytes.
     */
    public void skipRead(int amount) {
        if (amount < 0) {
            throw new RuntimeException("skipRead with negative amount=" + amount);
        }
        if (amount == 0) {
            return;
        }
        if (amount <= mChunkSize - mReadIndex) {
            mReadIndex += amount;
        } else {
            amount -= mChunkSize - mReadIndex;
            mReadIndex = amount % mChunkSize;
            if (mReadIndex == 0) {
                mReadIndex = mChunkSize;
                mReadBufIndex += (amount / mChunkSize);
            } else {
                mReadBufIndex += 1 + (amount / mChunkSize);
            }
            mReadBuffer = mBuffers.get(mReadBufIndex);
        }
    }

    /**
     * Read one byte from the stream and advance the read pointer.
     *
     * @throws IndexOutOfBoundsException if the read point is past the end of
     * the buffer or past the read limit previously set by startEditing().
     */
    public byte readRawByte() {
        if (mReadBufIndex > mBufferCount
                || (mReadBufIndex == mBufferCount - 1 && mReadIndex >= mReadLimit)) {
            throw new IndexOutOfBoundsException("Trying to read too much data"
                    + " mReadBufIndex=" + mReadBufIndex + " mBufferCount=" + mBufferCount
                    + " mReadIndex=" + mReadIndex + " mReadLimit=" + mReadLimit);
        }
        if (mReadIndex >= mChunkSize) {
            mReadBufIndex++;
            mReadBuffer = mBuffers.get(mReadBufIndex);
            mReadIndex = 0;
        }
        return mReadBuffer[mReadIndex++];
    }

    /**
     * Read an unsigned varint. The value will be returend in a java signed long.
     */
    public long readRawUnsigned() {
        int bits = 0;
        long result = 0;
        while (true) {
            final byte b = readRawByte();
            result |= ((long)(b & 0x7F)) << bits;
            if ((b & 0x80) == 0) {
                return result;
            }
            bits += 7;
            if (bits > 64) {
                throw new ProtoParseException("Varint too long -- " + getDebugString());
            }
        }
    }

    /**
     * Read 32 little endian bits from the stream.
     */
    public int readRawFixed32() {
        return (readRawByte() & 0x0ff)
                | ((readRawByte() & 0x0ff) << 8)
                | ((readRawByte() & 0x0ff) << 16)
                | ((readRawByte() & 0x0ff) << 24);
    }

    //
    // Writing at a the end of the stream.
    //

    /**
     * Advance to the next write buffer, allocating it if necessary.
     *
     * Must be called immediately <b>before</b> the next write, not after a write,
     * so that a dangling empty buffer is not created.  Doing so will interfere
     * with the expectation that mWriteIndex will point past the end of the buffer
     * until the next read happens.
     */
    private void nextWriteBuffer() {
        mWriteBufIndex++;
        if (mWriteBufIndex >= mBufferCount) {
            mWriteBuffer = new byte[mChunkSize];
            mBuffers.add(mWriteBuffer);
            mBufferCount++;
        } else {
            mWriteBuffer = mBuffers.get(mWriteBufIndex);
        }
        mWriteIndex = 0;
    }

    /**
     * Write a single byte to the stream.
     */
    public void writeRawByte(byte val) {
        if (mWriteIndex >= mChunkSize) {
            nextWriteBuffer();
        }
        mWriteBuffer[mWriteIndex++] = val;
    }

    /**
     * Return how many bytes a 32 bit unsigned varint will take when written to the stream.
     */
    public static int getRawVarint32Size(int val) {
        if ((val & (0xffffffff << 7)) == 0) return 1;
        if ((val & (0xffffffff << 14)) == 0) return 2;
        if ((val & (0xffffffff << 21)) == 0) return 3;
        if ((val & (0xffffffff << 28)) == 0) return 4;
        return 5;
    }

    /**
     * Write an unsigned varint to the stream. A signed value would need to take 10 bytes.
     *
     * @param val treated as unsigned.
     */
    public void writeRawVarint32(int val) {
        while (true) {
            if ((val & ~0x7F) == 0) {
                writeRawByte((byte)val);
                return;
            } else {
                writeRawByte((byte)((val & 0x7F) | 0x80));
                val >>>= 7;
            }
        }
    }

    /**
     * Return how many bytes a 32 bit signed zig zag value will take when written to the stream.
     */
    public static int getRawZigZag32Size(int val) {
        return getRawVarint32Size(zigZag32(val));
    }

    /**
     *  Write a zig-zag encoded value.
     *
     *  @param val treated as signed
     */
    public void writeRawZigZag32(int val) {
        writeRawVarint32(zigZag32(val));
    }

    /**
     * Return how many bytes a 64 bit varint will take when written to the stream.
     */
    public static int getRawVarint64Size(long val) {
        if ((val & (0xffffffffffffffffL << 7)) == 0) return 1;
        if ((val & (0xffffffffffffffffL << 14)) == 0) return 2;
        if ((val & (0xffffffffffffffffL << 21)) == 0) return 3;
        if ((val & (0xffffffffffffffffL << 28)) == 0) return 4;
        if ((val & (0xffffffffffffffffL << 35)) == 0) return 5;
        if ((val & (0xffffffffffffffffL << 42)) == 0) return 6;
        if ((val & (0xffffffffffffffffL << 49)) == 0) return 7;
        if ((val & (0xffffffffffffffffL << 56)) == 0) return 8;
        if ((val & (0xffffffffffffffffL << 63)) == 0) return 9;
        return 10;
    }

    /**
     * Write a 64 bit varint to the stream.
     */
    public void writeRawVarint64(long val) {
        while (true) {
            if ((val & ~0x7FL) == 0) {
                writeRawByte((byte)val);
                return;
            } else {
                writeRawByte((byte)((val & 0x7F) | 0x80));
                val >>>= 7;
            }
        }
    }

    /**
     * Return how many bytes a signed 64 bit zig zag value will take when written to the stream.
     */
    public static int getRawZigZag64Size(long val) {
        return getRawVarint64Size(zigZag64(val));
    }

    /**
     * Write a 64 bit signed zig zag value to the stream.
     */
    public void writeRawZigZag64(long val) {
        writeRawVarint64(zigZag64(val));
    }

    /**
     * Write 4 little endian bytes to the stream.
     */
    public void writeRawFixed32(int val) {
        writeRawByte((byte)(val));
        writeRawByte((byte)(val >> 8));
        writeRawByte((byte)(val >> 16));
        writeRawByte((byte)(val >> 24));
    }

    /**
     * Write 8 little endian bytes to the stream.
     */
    public void writeRawFixed64(long val) {
        writeRawByte((byte)(val));
        writeRawByte((byte)(val >> 8));
        writeRawByte((byte)(val >> 16));
        writeRawByte((byte)(val >> 24));
        writeRawByte((byte)(val >> 32));
        writeRawByte((byte)(val >> 40));
        writeRawByte((byte)(val >> 48));
        writeRawByte((byte)(val >> 56));
    }

    /**
     * Write a buffer to the stream. Writes nothing if val is null or zero-length.
     */
    public void writeRawBuffer(byte[] val) {
        if (val != null && val.length > 0) {
            writeRawBuffer(val, 0, val.length);
        }
    }

    /**
     * Write part of an array of bytes.
     */
    public void writeRawBuffer(byte[] val, int offset, int length) {
        if (val == null) {
            return;
        }
        // Write up to the amount left in the first chunk to write.
        int amt = length < (mChunkSize - mWriteIndex) ? length : (mChunkSize - mWriteIndex);
        if (amt > 0) {
            System.arraycopy(val, offset, mWriteBuffer, mWriteIndex, amt);
            mWriteIndex += amt;
            length -= amt;
            offset += amt;
        }
        while (length > 0) {
            // We know we're now at the beginning of a chunk
            nextWriteBuffer();
            amt = length < mChunkSize ? length : mChunkSize;
            System.arraycopy(val, offset, mWriteBuffer, mWriteIndex, amt);
            mWriteIndex += amt;
            length -= amt;
            offset += amt;
        }
    }

    /**
     * Copies data _size_ bytes of data within this buffer from _srcOffset_
     * to the current write position. Like memmov but handles the chunked buffer.
     */
    public void writeFromThisBuffer(int srcOffset, int size) {
        if (mReadLimit < 0) {
            throw new IllegalStateException("writeFromThisBuffer before startEditing");
        }
        if (srcOffset < getWritePos()) {
            throw new IllegalArgumentException("Can only move forward in the buffer --"
                    + " srcOffset=" + srcOffset + " size=" + size + " " + getDebugString());
        }
        if (srcOffset + size > mReadableSize) {
            throw new IllegalArgumentException("Trying to move more data than there is --"
                    + " srcOffset=" + srcOffset + " size=" + size + " " + getDebugString());
        }
        if (size == 0) {
            return;
        }
        if (srcOffset == ((mWriteBufIndex) * mChunkSize) + mWriteIndex /* write pos */) {
            // Writing to the same location. Just advance the write pointer.  We already
            // checked that size is in bounds, so we don't need to do any more range
            // checking.
            if (size <= mChunkSize - mWriteIndex) {
                mWriteIndex += size;
            } else {
                size -= mChunkSize - mWriteIndex;
                mWriteIndex = size % mChunkSize;
                if (mWriteIndex == 0) {
                    // Roll it back so nextWriteBuffer can do its job
                    // on the next call (also makes mBuffers.get() not
                    // fail if we're at the end).
                    mWriteIndex = mChunkSize;
                    mWriteBufIndex += (size / mChunkSize);
                } else {
                    mWriteBufIndex += 1 + (size / mChunkSize);
                }
                mWriteBuffer = mBuffers.get(mWriteBufIndex);
            }
        } else {
            // Loop through the buffer, copying as much as we can each time.
            // We already bounds checked so we don't need to do it again here,
            // and nextWriteBuffer will never allocate.
            int readBufIndex = srcOffset / mChunkSize;
            byte[] readBuffer = mBuffers.get(readBufIndex);
            int readIndex = srcOffset % mChunkSize;
            while (size > 0) {
                if (mWriteIndex >= mChunkSize) {
                    nextWriteBuffer();
                }
                if (readIndex >= mChunkSize) {
                    readBufIndex++;
                    readBuffer = mBuffers.get(readBufIndex);
                    readIndex = 0;
                }
                final int spaceInWriteBuffer = mChunkSize - mWriteIndex;
                final int availableInReadBuffer = mChunkSize - readIndex;
                final int amt = Math.min(size, Math.min(spaceInWriteBuffer, availableInReadBuffer));
                System.arraycopy(readBuffer, readIndex, mWriteBuffer, mWriteIndex, amt);
                mWriteIndex += amt;
                readIndex += amt;
                size -= amt;
            }
        }
    }

    //
    // Writing at a particular location.
    //

    /**
     * Returns the index into the virtual array of the write pointer.
     */
    public int getWritePos() {
        return ((mWriteBufIndex) * mChunkSize) + mWriteIndex;
    }

    /**
     * Resets the write pointer to a virtual location as returned by getWritePos.
     */
    public void rewindWriteTo(int writePos) {
        if (writePos > getWritePos()) {
            throw new RuntimeException("rewindWriteTo only can go backwards" + writePos);
        }
        mWriteBufIndex = writePos / mChunkSize;
        mWriteIndex = writePos % mChunkSize;
        if (mWriteIndex == 0 && mWriteBufIndex != 0) {
            // Roll back so nextWriteBuffer can do its job on the next call
            // but at the first write we're at 0.
            mWriteIndex = mChunkSize;
            mWriteBufIndex--;
        }
        mWriteBuffer = mBuffers.get(mWriteBufIndex);
    }

    /**
     * Read a 32 bit value from the stream.
     *
     * Doesn't touch or affect mWritePos.
     */
    public int getRawFixed32At(int pos) {
        return (0x00ff & (int)mBuffers.get(pos / mChunkSize)[pos % mChunkSize])
                | ((0x0ff & (int)mBuffers.get((pos+1) / mChunkSize)[(pos+1) % mChunkSize]) << 8)
                | ((0x0ff & (int)mBuffers.get((pos+2) / mChunkSize)[(pos+2) % mChunkSize]) << 16)
                | ((0x0ff & (int)mBuffers.get((pos+3) / mChunkSize)[(pos+3) % mChunkSize]) << 24);
    }

    /**
     * Overwrite a 32 bit value in the stream.
     *
     * Doesn't touch or affect mWritePos.
     */
    public void editRawFixed32(int pos, int val) {
        mBuffers.get(pos / mChunkSize)[pos % mChunkSize] = (byte)(val);
        mBuffers.get((pos+1) / mChunkSize)[(pos+1) % mChunkSize] = (byte)(val >> 8);
        mBuffers.get((pos+2) / mChunkSize)[(pos+2) % mChunkSize] = (byte)(val >> 16);
        mBuffers.get((pos+3) / mChunkSize)[(pos+3) % mChunkSize] = (byte)(val >> 24);
    }

    //
    // Zigging and zagging
    //

    /**
     * Zig-zag encode a 32 bit value.
     */
    private static int zigZag32(int val) {
        return (val << 1) ^ (val >> 31);
    }

    /**
     * Zig-zag encode a 64 bit value.
     */
    private static long zigZag64(long val) {
        return (val << 1) ^ (val >> 63);
    }

    //
    // Debugging / testing
    //
    // VisibleForTesting

    /**
     * Get a copy of the first _size_ bytes of data. This is not range
     * checked, and if the bounds are outside what has been written you will
     * get garbage and if it is outside the buffers that have been allocated,
     * you will get an exception.
     */
    public byte[] getBytes(int size) {
        final byte[] result = new byte[size];

        final int bufCount = size / mChunkSize;
        int bufIndex;
        int writeIndex = 0;

        for (bufIndex=0; bufIndex<bufCount; bufIndex++) {
            System.arraycopy(mBuffers.get(bufIndex), 0, result, writeIndex, mChunkSize);
            writeIndex += mChunkSize;
        }

        final int lastSize = size - (bufCount * mChunkSize);
        if (lastSize > 0) {
            System.arraycopy(mBuffers.get(bufIndex), 0, result, writeIndex, lastSize);
        }

        return result;
    }

    /**
     * Get the number of chunks allocated.
     */
    // VisibleForTesting
    public int getChunkCount() {
        return mBuffers.size();
    }

    /**
     * Get the write position inside the current write chunk.
     */
     // VisibleForTesting
    public int getWriteIndex() {
        return mWriteIndex;
    }

    /**
     * Get the index of the current write chunk in the list of chunks.
     */
    // VisibleForTesting
    public int getWriteBufIndex() {
        return mWriteBufIndex;
    }

    /**
     * Return debugging information about this EncodedBuffer object.
     */
    public String getDebugString() {
        return "EncodedBuffer( mChunkSize=" + mChunkSize + " mBuffers.size=" + mBuffers.size()
                + " mBufferCount=" + mBufferCount + " mWriteIndex=" + mWriteIndex
                + " mWriteBufIndex=" + mWriteBufIndex + " mReadBufIndex=" + mReadBufIndex
                + " mReadIndex=" + mReadIndex + " mReadableSize=" + mReadableSize
                + " mReadLimit=" + mReadLimit + " )";
    }

    /**
     * Print the internal buffer chunks.
     */
    public void dumpBuffers(String tag) {
        final int N = mBuffers.size();
        int start = 0;
        for (int i=0; i<N; i++) {
            start += dumpByteString(tag, "{" + i + "} ", start, mBuffers.get(i));
        }
    }

    /**
     * Print the internal buffer chunks.
     */
    public static void dumpByteString(String tag, String prefix, byte[] buf) {
        dumpByteString(tag, prefix, 0, buf);
    }

    /**
     * Print the internal buffer chunks.
     */
    private static int dumpByteString(String tag, String prefix, int start, byte[] buf) {
        StringBuilder sb = new StringBuilder();
        final int length = buf.length;
        final int lineLen = 16;
        int i;
        for (i=0; i<length; i++) {
            if (i % lineLen == 0) {
                if (i != 0) {
                    Log.d(tag, sb.toString());
                    sb = new StringBuilder();
                }
                sb.append(prefix);
                sb.append('[');
                sb.append(start + i);
                sb.append(']');
                sb.append(' ');
            } else {
                sb.append(' ');
            }
            byte b = buf[i];
            byte c = (byte)((b >> 4) & 0x0f);
            if (c < 10) {
                sb.append((char)('0' + c));
            } else {
                sb.append((char)('a' - 10 + c));
            }
            byte d = (byte)(b & 0x0f);
            if (d < 10) {
                sb.append((char)('0' + d));
            } else {
                sb.append((char)('a' - 10 + d));
            }
        }
        Log.d(tag, sb.toString());
        return length;
    }
}
