/*
 * Copyright (C) 2014 The Android Open Source Project
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

import java.io.IOException;
import java.io.InputStream;

/**
 * Reads exact number of bytes from wrapped stream, returning EOF once those
 * bytes have been read.
 */
@android.ravenwood.annotation.RavenwoodKeepWholeClass
public class SizedInputStream extends InputStream {
    private final InputStream mWrapped;
    private long mLength;

    public SizedInputStream(InputStream wrapped, long length) {
        mWrapped = wrapped;
        mLength = length;
    }

    @Override
    public void close() throws IOException {
        super.close();
        mWrapped.close();
    }

    @Override
    public int read() throws IOException {
        byte[] buffer = new byte[1];
        int result = read(buffer, 0, 1);
        return (result != -1) ? buffer[0] & 0xff : -1;
    }

    @Override
    public int read(byte[] buffer, int byteOffset, int byteCount) throws IOException {
        if (mLength <= 0) {
            return -1;
        } else if (byteCount > mLength) {
            byteCount = (int) mLength;
        }

        final int n = mWrapped.read(buffer, byteOffset, byteCount);
        if (n == -1) {
            if (mLength > 0) {
                throw new IOException("Unexpected EOF; expected " + mLength + " more bytes");
            }
        } else {
            mLength -= n;
        }
        return n;
    }
}
