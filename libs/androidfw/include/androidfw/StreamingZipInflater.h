/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef __LIBS_STREAMINGZIPINFLATER_H
#define __LIBS_STREAMINGZIPINFLATER_H

#include <unistd.h>
#include <inttypes.h>

#include <util/map_ptr.h>
#include <zlib.h>

#include <utils/Compat.h>

namespace android {

class StreamingZipInflater {
public:
    static const size_t INPUT_CHUNK_SIZE = 64 * 1024;
    static const size_t OUTPUT_CHUNK_SIZE = 64 * 1024;

    // Flavor that pages in the compressed data from a fd
    StreamingZipInflater(int fd, off64_t compDataStart, size_t uncompSize, size_t compSize);

    // Flavor that gets the compressed data from an in-memory buffer
    StreamingZipInflater(const incfs::IncFsFileMap* dataMap, size_t uncompSize);

    ~StreamingZipInflater();

    // read 'count' bytes of uncompressed data from the current position.  outBuf may
    // be NULL, in which case the data is consumed and discarded.
    ssize_t read(void* outBuf, size_t count);

    // seeking backwards requires uncompressing fom the beginning, so is very
    // expensive.  seeking forwards only requires uncompressing from the current
    // position to the destination.
    off64_t seekAbsolute(off64_t absoluteInputPosition);

private:
    void initInflateState();
    int readNextChunk();

    // where to find the uncompressed data
    int mFd;
    off64_t mInFileStart;         // where the compressed data lives in the file
    const incfs::IncFsFileMap* mDataMap;

    z_stream mInflateState;
    bool mStreamNeedsInit;

    // output invariants for this asset
    uint8_t* mOutBuf;           // output buf for decompressed bytes
    size_t mOutBufSize;         // allocated size of mOutBuf
    size_t mOutTotalSize;       // total uncompressed size of the blob

    // current output state bookkeeping
    off64_t mOutCurPosition;      // current position in total offset
    size_t mOutLastDecoded;     // last decoded byte + 1 in mOutbuf
    size_t mOutDeliverable;     // next undelivered byte of decoded output in mOutBuf

    // input invariants
    uint8_t* mInBuf;
    size_t mInBufSize;          // allocated size of mInBuf;
    size_t mInTotalSize;        // total size of compressed data for this blob

    // input state bookkeeping
    size_t mInNextChunkOffset;  // offset from start of blob at which the next input chunk lies
    // the z_stream contains state about input block consumption
};

}

#endif
