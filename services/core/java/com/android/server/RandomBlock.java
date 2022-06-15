/*
 * Copyright (C) 2009 The Android Open Source Project
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

package com.android.server;

import android.util.Slog;

import java.io.Closeable;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

/**
 * A block of 512 random {@code byte}s.
 */
class RandomBlock {

    private static final String TAG = "RandomBlock";
    private static final boolean DEBUG = false;
    private static final int BLOCK_SIZE = 512;
    private byte[] block = new byte[BLOCK_SIZE];

    private RandomBlock() { }

    static RandomBlock fromFile(String filename) throws IOException {
        if (DEBUG) Slog.v(TAG, "reading from file " + filename);
        InputStream stream = null;
        try {
            stream = new FileInputStream(filename);
            return fromStream(stream);
        } finally {
            close(stream);
        }
    }

    private static RandomBlock fromStream(InputStream in) throws IOException {
        RandomBlock retval = new RandomBlock();
        int total = 0;
        while(total < BLOCK_SIZE) {
            int result = in.read(retval.block, total, BLOCK_SIZE - total);
            if (result == -1) {
                throw new EOFException();
            }
            total += result;
        }
        return retval;
    }

    void toFile(String filename, boolean sync) throws IOException {
        if (DEBUG) Slog.v(TAG, "writing to file " + filename);
        RandomAccessFile out = null;
        try {
            out = new RandomAccessFile(filename, sync ? "rws" : "rw");
            toDataOut(out);
            truncateIfPossible(out);
        } finally {
            close(out);
        }
    }

    private static void truncateIfPossible(RandomAccessFile f) {
        try {
            f.setLength(BLOCK_SIZE);
        } catch (IOException e) {
            // ignore this exception.  Sometimes, the file we're trying to
            // write is a character device, such as /dev/urandom, and
            // these character devices do not support setting the length.
        }
    }

    private void toDataOut(DataOutput out) throws IOException {
        out.write(block);
    }

    private static void close(Closeable c) {
        try {
            if (c == null) {
                return;
            }
            c.close();
        } catch (IOException e) {
            Slog.w(TAG, "IOException thrown while closing Closeable", e);
        }
    }
}
