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

import com.android.modules.utils.FastDataInput;

import dalvik.system.VMRuntime;

import java.io.DataInput;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@inheritDoc}
 * <p>
 * This decodes large code-points using 4-byte sequences, and <em>is not</em> compatible with the
 * {@link DataInput} API contract, which specifies that large code-points must be encoded with
 * 3-byte sequences.
 */
public class ArtFastDataInput extends FastDataInput {
    private static AtomicReference<ArtFastDataInput> sInCache = new AtomicReference<>();
    private static VMRuntime sRuntime = VMRuntime.getRuntime();

    private final long mBufferPtr;

    public ArtFastDataInput(@NonNull InputStream in, int bufferSize) {
        super(in, bufferSize);

        mBufferPtr = sRuntime.addressOf(mBuffer);
    }

    /**
     * Obtain a {@link ArtFastDataInput} configured with the given
     * {@link InputStream} and which decodes large code-points using 4-byte
     * sequences.
     * <p>
     * This <em>is not</em> compatible with the {@link DataInput} API contract,
     * which specifies that large code-points must be encoded with 3-byte
     * sequences.
     */
    public static ArtFastDataInput obtain(@NonNull InputStream in) {
        ArtFastDataInput instance = sInCache.getAndSet(null);
        if (instance != null) {
            instance.setInput(in);
            return instance;
        }
        return new ArtFastDataInput(in, DEFAULT_BUFFER_SIZE);
    }

    /**
     * Release a {@link ArtFastDataInput} to potentially be recycled. You must not
     * interact with the object after releasing it.
     */
    @Override
    public void release() {
        super.release();

        if (mBufferCap == DEFAULT_BUFFER_SIZE) {
            // Try to return to the cache.
            sInCache.compareAndSet(null, this);
        }
    }

    @Override
    public byte[] newByteArray(int bufferSize) {
        return (byte[]) sRuntime.newNonMovableArray(byte.class, bufferSize);
    }

    @Override
    public String readUTF() throws IOException {
        // Attempt to read directly from buffer space if there's enough room,
        // otherwise fall back to chunking into place
        final int len = readUnsignedShort();
        if (mBufferCap > len) {
            if (mBufferLim - mBufferPos < len) fill(len);
            final String res = CharsetUtils.fromModifiedUtf8Bytes(mBufferPtr, mBufferPos, len);
            mBufferPos += len;
            return res;
        } else {
            final byte[] tmp = (byte[]) sRuntime.newNonMovableArray(byte.class, len + 1);
            readFully(tmp, 0, len);
            return CharsetUtils.fromModifiedUtf8Bytes(sRuntime.addressOf(tmp), 0, len);
        }
    }
}
