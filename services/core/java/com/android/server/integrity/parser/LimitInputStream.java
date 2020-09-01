/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.integrity.parser;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** An {@link InputStream} that basically truncates another {@link InputStream} */
public class LimitInputStream extends FilterInputStream {
    private int mReadBytes;
    private final int mLimit;

    public LimitInputStream(InputStream in, int limit) {
        super(in);
        if (limit < 0) {
            throw new IllegalArgumentException("limit " + limit + " cannot be negative");
        }
        mReadBytes = 0;
        mLimit = limit;
    }

    @Override
    public int available() throws IOException {
        return Math.min(super.available(), mLimit - mReadBytes);
    }

    @Override
    public int read() throws IOException {
        if (mReadBytes == mLimit) {
            return -1;
        }
        mReadBytes++;
        return super.read();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (len <= 0) {
            return 0;
        }
        int available = available();
        if (available <= 0) {
            return -1;
        }
        int result = super.read(b, off, Math.min(len, available));
        mReadBytes += result;
        return result;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        int available = available();
        if (available <= 0) {
            return 0;
        }
        int bytesToSkip = (int) Math.min(available, n);
        long bytesSkipped = super.skip(bytesToSkip);
        mReadBytes += (int) bytesSkipped;
        return bytesSkipped;
    }
}
