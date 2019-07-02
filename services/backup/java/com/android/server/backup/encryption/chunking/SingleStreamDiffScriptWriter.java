/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.server.backup.encryption.chunking;

import android.annotation.Nullable;

import com.android.internal.util.Preconditions;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.Locale;

/**
 * A {@link DiffScriptWriter} that writes an entire diff script to a single {@link OutputStream}.
 */
public class SingleStreamDiffScriptWriter implements DiffScriptWriter {
    static final byte LINE_SEPARATOR = 0xA;
    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final int mMaxNewByteChunkSize;
    private final OutputStream mOutputStream;
    private final byte[] mByteBuffer;
    private int mBufferSize = 0;
    // Each chunk could be written immediately to the output stream. However,
    // it is possible that chunks may overlap. We therefore cache the most recent
    // reusable chunk and try to merge it with future chunks.
    private ByteRange mReusableChunk;

    public SingleStreamDiffScriptWriter(OutputStream outputStream, int maxNewByteChunkSize) {
        mOutputStream = outputStream;
        mMaxNewByteChunkSize = maxNewByteChunkSize;
        mByteBuffer = new byte[maxNewByteChunkSize];
    }

    @Override
    public void writeByte(byte b) throws IOException {
        if (mReusableChunk != null) {
            writeReusableChunk();
        }
        mByteBuffer[mBufferSize++] = b;
        if (mBufferSize == mMaxNewByteChunkSize) {
            writeByteBuffer();
        }
    }

    @Override
    public void writeChunk(long chunkStart, int chunkLength) throws IOException {
        Preconditions.checkArgument(chunkStart >= 0);
        Preconditions.checkArgument(chunkLength > 0);
        if (mBufferSize != 0) {
            writeByteBuffer();
        }

        if (mReusableChunk != null && mReusableChunk.getEnd() + 1 == chunkStart) {
            // The new chunk overlaps the old, so combine them into a single byte range.
            mReusableChunk = mReusableChunk.extend(chunkLength);
        } else {
            writeReusableChunk();
            mReusableChunk = new ByteRange(chunkStart, chunkStart + chunkLength - 1);
        }
    }

    @Override
    public void flush() throws IOException {
        Preconditions.checkState(!(mBufferSize != 0 && mReusableChunk != null));
        if (mBufferSize != 0) {
            writeByteBuffer();
        }
        if (mReusableChunk != null) {
            writeReusableChunk();
        }
        mOutputStream.flush();
    }

    private void writeByteBuffer() throws IOException {
        mOutputStream.write(Integer.toString(mBufferSize).getBytes(UTF_8));
        mOutputStream.write(LINE_SEPARATOR);
        mOutputStream.write(mByteBuffer, 0, mBufferSize);
        mOutputStream.write(LINE_SEPARATOR);
        mBufferSize = 0;
    }

    private void writeReusableChunk() throws IOException {
        if (mReusableChunk != null) {
            mOutputStream.write(
                    String.format(
                                    Locale.US,
                                    "%d-%d",
                                    mReusableChunk.getStart(),
                                    mReusableChunk.getEnd())
                            .getBytes(UTF_8));
            mOutputStream.write(LINE_SEPARATOR);
            mReusableChunk = null;
        }
    }

    /** A factory that creates {@link SingleStreamDiffScriptWriter}s. */
    public static class Factory implements DiffScriptWriter.Factory {
        private final int mMaxNewByteChunkSize;
        private final OutputStreamWrapper mOutputStreamWrapper;

        public Factory(int maxNewByteChunkSize, @Nullable OutputStreamWrapper outputStreamWrapper) {
            mMaxNewByteChunkSize = maxNewByteChunkSize;
            mOutputStreamWrapper = outputStreamWrapper;
        }

        @Override
        public SingleStreamDiffScriptWriter create(OutputStream outputStream) {
            if (mOutputStreamWrapper != null) {
                outputStream = mOutputStreamWrapper.wrap(outputStream);
            }
            return new SingleStreamDiffScriptWriter(outputStream, mMaxNewByteChunkSize);
        }
    }
}
