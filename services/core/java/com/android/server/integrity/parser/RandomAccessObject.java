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

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;

/** An interface for random access objects like RandomAccessFile or byte arrays. */
public abstract class RandomAccessObject {

    /** See {@link RandomAccessFile#seek(long)}. */
    public abstract void seek(int position) throws IOException;

    /** See {@link RandomAccessFile#read()}. */
    public abstract int read() throws IOException;

    /** See {@link RandomAccessFile#read(byte[], int, int)}. */
    public abstract int read(byte[] bytes, int off, int len) throws IOException;

    /** See {@link RandomAccessFile#close()}. */
    public abstract void close() throws IOException;

    /** See {@link java.io.RandomAccessFile#length()}. */
    public abstract int length();

    /** Static constructor from a file. */
    public static RandomAccessObject ofFile(File file) throws IOException {
        return new RandomAccessFileObject(file);
    }

    /** Static constructor from a byte array. */
    public static RandomAccessObject ofBytes(byte[] bytes) {
        return new RandomAccessByteArrayObject(bytes);
    }

    private static class RandomAccessFileObject extends RandomAccessObject {
        private final RandomAccessFile mRandomAccessFile;
        // We cache the length since File.length() invokes file IO.
        private final int mLength;

        RandomAccessFileObject(File file) throws IOException {
            long length = file.length();
            if (length > Integer.MAX_VALUE) {
                throw new IOException("Unsupported file size (too big) " + length);
            }

            mRandomAccessFile = new RandomAccessFile(file, /* mode= */ "r");
            mLength = (int) length;
        }

        @Override
        public void seek(int position) throws IOException {
            mRandomAccessFile.seek(position);
        }

        @Override
        public int read() throws IOException {
            return mRandomAccessFile.read();
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            return mRandomAccessFile.read(bytes, off, len);
        }

        @Override
        public void close() throws IOException {
            mRandomAccessFile.close();
        }

        @Override
        public int length() {
            return mLength;
        }
    }

    private static class RandomAccessByteArrayObject extends RandomAccessObject {

        private final ByteBuffer mBytes;

        RandomAccessByteArrayObject(byte[] bytes) {
            mBytes = ByteBuffer.wrap(bytes);
        }

        @Override
        public void seek(int position) throws IOException {
            mBytes.position(position);
        }

        @Override
        public int read() throws IOException {
            if (!mBytes.hasRemaining()) {
                return -1;
            }

            return mBytes.get() & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int off, int len) throws IOException {
            int bytesToCopy = Math.min(len, mBytes.remaining());
            if (bytesToCopy <= 0) {
                return 0;
            }
            mBytes.get(bytes, off, len);
            return bytesToCopy;
        }

        @Override
        public void close() throws IOException {}

        @Override
        public int length() {
            return mBytes.capacity();
        }
    }
}
