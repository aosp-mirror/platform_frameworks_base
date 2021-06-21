/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.util.apk;

import android.system.ErrnoException;
import android.system.Os;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.DigestException;

/**
 * {@link DataSource} which provides data from a file descriptor by reading the sections
 * of the file via raw read() syscall. This is slower than memory-mapping but safer.
 */
class ReadFileDataSource implements DataSource {
    private final FileDescriptor mFd;
    private final long mFilePosition;
    private final long mSize;

    private static final int CHUNK_SIZE = 1024 * 1024;

    /**
     * Constructs a new {@code ReadFileDataSource} for the specified region of the file.
     *
     * @param fd file descriptor to read from.
     * @param position start position of the region in the file.
     * @param size size (in bytes) of the region.
     */
    ReadFileDataSource(FileDescriptor fd, long position, long size) {
        mFd = fd;
        mFilePosition = position;
        mSize = size;
    }

    @Override
    public long size() {
        return mSize;
    }

    @Override
    public void feedIntoDataDigester(DataDigester md, long offset, int size)
            throws IOException, DigestException {
        try {
            final byte[] buffer = new byte[Math.min(size, CHUNK_SIZE)];
            final long start = mFilePosition + offset;
            final long end = start + size;
            for (long pos = start, curSize = Math.min(size, CHUNK_SIZE);
                    pos < end; curSize = Math.min(end - pos, CHUNK_SIZE)) {
                final int readSize = Os.pread(mFd, buffer, 0, (int) curSize, pos);
                md.consume(ByteBuffer.wrap(buffer, 0, readSize));
                pos += readSize;
            }
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }
}
