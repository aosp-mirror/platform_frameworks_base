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

import java.io.IOException;
import java.io.InputStream;

/** A wrapper around {@link RandomAccessObject} to turn it into a {@link InputStream}. */
public class RandomAccessInputStream extends InputStream {

    private final RandomAccessObject mRandomAccessObject;

    private int mPosition;

    public RandomAccessInputStream(RandomAccessObject object) throws IOException {
        mRandomAccessObject = object;
        mPosition = 0;
    }

    /** Returns the position of the file pointer. */
    public int getPosition() {
        return mPosition;
    }

    /** See {@link RandomAccessObject#seek(int)} */
    public void seek(int position) throws IOException {
        mRandomAccessObject.seek(position);
        mPosition = position;
    }

    @Override
    public int available() throws IOException {
        return mRandomAccessObject.length() - mPosition;
    }

    @Override
    public void close() throws IOException {
        mRandomAccessObject.close();
    }

    @Override
    public int read() throws IOException {
        if (available() <= 0) {
            return -1;
        }
        mPosition++;
        return mRandomAccessObject.read();
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
        int result = mRandomAccessObject.read(b, off, Math.min(len, available));
        mPosition += result;
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
        int skipAmount = (int) Math.min(available, n);
        mPosition += skipAmount;
        mRandomAccessObject.seek(mPosition);
        return skipAmount;
    }
}
