/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.annotation.NonNull;
import android.util.CharsetUtils;

import com.android.modules.utils.FastDataOutput;

import dalvik.system.VMRuntime;

import java.io.DataOutput;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@inheritDoc}
 * <p>
 * This encodes large code-points using 4-byte sequences and <em>is not</em> compatible with the
 * {@link DataOutput} API contract, which specifies that large code-points must be encoded with
 * 3-byte sequences.
 */
public class ArtFastDataOutput extends FastDataOutput {
    private static AtomicReference<ArtFastDataOutput> sOutCache = new AtomicReference<>();
    private static VMRuntime sRuntime = VMRuntime.getRuntime();

    private final long mBufferPtr;

    public ArtFastDataOutput(@NonNull OutputStream out, int bufferSize) {
        super(out, bufferSize);

        mBufferPtr = sRuntime.addressOf(mBuffer);
    }

    /**
     * Obtain an {@link ArtFastDataOutput} configured with the given
     * {@link OutputStream} and which encodes large code-points using 4-byte
     * sequences.
     * <p>
     * This <em>is not</em> compatible with the {@link DataOutput} API contract,
     * which specifies that large code-points must be encoded with 3-byte
     * sequences.
     */
    public static ArtFastDataOutput obtain(@NonNull OutputStream out) {
        ArtFastDataOutput instance = sOutCache.getAndSet(null);
        if (instance != null) {
            instance.setOutput(out);
            return instance;
        }
        return new ArtFastDataOutput(out, DEFAULT_BUFFER_SIZE);
    }

    @Override
    public void release() {
        super.release();

        if (mBufferCap == DEFAULT_BUFFER_SIZE) {
            // Try to return to the cache.
            sOutCache.compareAndSet(null, this);
        }
    }

    @Override
    public byte[] newByteArray(int bufferSize) {
        return (byte[]) sRuntime.newNonMovableArray(byte.class, bufferSize);
    }

    @Override
    public void writeUTF(String s) throws IOException {
        // Attempt to write directly to buffer space if there's enough room,
        // otherwise fall back to chunking into place
        if (mBufferCap - mBufferPos < 2 + s.length()) drain();

        // Magnitude of this returned value indicates the number of bytes
        // required to encode the string; sign indicates success/failure
        int len = CharsetUtils.toModifiedUtf8Bytes(s, mBufferPtr, mBufferPos + 2, mBufferCap);
        if (Math.abs(len) > MAX_UNSIGNED_SHORT) {
            throw new IOException("Modified UTF-8 length too large: " + len);
        }

        if (len >= 0) {
            // Positive value indicates the string was encoded into the buffer
            // successfully, so we only need to prefix with length
            writeShort(len);
            mBufferPos += len;
        } else {
            // Negative value indicates buffer was too small and we need to
            // allocate a temporary buffer for encoding
            len = -len;
            final byte[] tmp = (byte[]) sRuntime.newNonMovableArray(byte.class, len + 1);
            CharsetUtils.toModifiedUtf8Bytes(s, sRuntime.addressOf(tmp), 0, tmp.length);
            writeShort(len);
            write(tmp, 0, len);
        }
    }
}
