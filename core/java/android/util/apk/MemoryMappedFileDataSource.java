/*
 * Copyright (C) 2017 The Android Open Source Project
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
import android.system.OsConstants;

import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.DirectByteBuffer;
import java.security.DigestException;

/**
 * {@link DataSource} which provides data from a file descriptor by memory-mapping the sections
 * of the file.
 */
class MemoryMappedFileDataSource implements DataSource {
    private static final long MEMORY_PAGE_SIZE_BYTES = Os.sysconf(OsConstants._SC_PAGESIZE);

    private final FileDescriptor mFd;
    private final long mFilePosition;
    private final long mSize;

    /**
     * Constructs a new {@code MemoryMappedFileDataSource} for the specified region of the file.
     *
     * @param position start position of the region in the file.
     * @param size size (in bytes) of the region.
     */
    MemoryMappedFileDataSource(FileDescriptor fd, long position, long size) {
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
        // IMPLEMENTATION NOTE: After a lot of experimentation, the implementation of this
        // method was settled on a straightforward mmap with prefaulting.
        //
        // This method is not using FileChannel.map API because that API does not offset a way
        // to "prefault" the resulting memory pages. Without prefaulting, performance is about
        // 10% slower on small to medium APKs, but is significantly worse for APKs in 500+ MB
        // range. FileChannel.load (which currently uses madvise) doesn't help. Finally,
        // invoking madvise (MADV_SEQUENTIAL) after mmap with prefaulting wastes quite a bit of
        // time, which is not compensated for by faster reads.

        // We mmap the smallest region of the file containing the requested data. mmap requires
        // that the start offset in the file must be a multiple of memory page size. We thus may
        // need to mmap from an offset less than the requested offset.
        long filePosition = mFilePosition + offset;
        long mmapFilePosition =
                (filePosition / MEMORY_PAGE_SIZE_BYTES) * MEMORY_PAGE_SIZE_BYTES;
        int dataStartOffsetInMmapRegion = (int) (filePosition - mmapFilePosition);
        long mmapRegionSize = size + dataStartOffsetInMmapRegion;
        long mmapPtr = 0;
        try {
            mmapPtr = Os.mmap(
                    0, // let the OS choose the start address of the region in memory
                    mmapRegionSize,
                    OsConstants.PROT_READ,
                    OsConstants.MAP_SHARED | OsConstants.MAP_POPULATE, // "prefault" all pages
                    mFd,
                    mmapFilePosition);
            ByteBuffer buf = new DirectByteBuffer(
                    size,
                    mmapPtr + dataStartOffsetInMmapRegion,
                    mFd,  // not really needed, but just in case
                    null, // no need to clean up -- it's taken care of by the finally block
                    true  // read only buffer
                    );
            md.consume(buf);
        } catch (ErrnoException e) {
            throw new IOException("Failed to mmap " + mmapRegionSize + " bytes", e);
        } finally {
            if (mmapPtr != 0) {
                try {
                    Os.munmap(mmapPtr, mmapRegionSize);
                } catch (ErrnoException ignored) { }
            }
        }
    }
}
