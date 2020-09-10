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

package com.android.server.integrity.model;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An output stream that tracks the total number written bytes since construction and allows
 * querying this value any time during the execution.
 *
 * <p>This class is used for constructing the rule indexing.
 */
public class ByteTrackedOutputStream extends OutputStream {

    private int mWrittenBytesCount;
    private final OutputStream mOutputStream;

    public ByteTrackedOutputStream(OutputStream outputStream) {
        mWrittenBytesCount = 0;
        mOutputStream = outputStream;
    }

    @Override
    public void write(int b) throws IOException {
        mWrittenBytesCount++;
        mOutputStream.write(b);
    }

    /**
     * Writes the given bytes into the output stream provided in constructor and updates the total
     * number of written bytes.
     */
    @Override
    public void write(byte[] bytes) throws IOException {
        write(bytes, 0, bytes.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        mWrittenBytesCount += len;
        mOutputStream.write(b, off, len);
    }

    /** Returns the total number of bytes written into the output stream at the requested time. */
    public int getWrittenBytesCount() {
        return mWrittenBytesCount;
    }
}
