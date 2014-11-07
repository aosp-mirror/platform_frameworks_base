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

//#define LOG_NDEBUG 0
#define LOG_TAG "szipinf"
#include <utils/Log.h>

#include <androidfw/StreamingZipInflater.h>
#include <utils/FileMap.h>
#include <string.h>
#include <stddef.h>
#include <assert.h>
#include <unistd.h>
#include <errno.h>

/*
 * TEMP_FAILURE_RETRY is defined by some, but not all, versions of
 * <unistd.h>. (Alas, it is not as standard as we'd hoped!) So, if it's
 * not already defined, then define it here.
 */
#ifndef TEMP_FAILURE_RETRY
/* Used to retry syscalls that can return EINTR. */
#define TEMP_FAILURE_RETRY(exp) ({         \
    typeof (exp) _rc;                      \
    do {                                   \
        _rc = (exp);                       \
    } while (_rc == -1 && errno == EINTR); \
    _rc; })
#endif

static const bool kIsDebug = false;

static inline size_t min_of(size_t a, size_t b) { return (a < b) ? a : b; }

using namespace android;

/*
 * Streaming access to compressed asset data in an open fd
 */
StreamingZipInflater::StreamingZipInflater(int fd, off64_t compDataStart,
        size_t uncompSize, size_t compSize) {
    mFd = fd;
    mDataMap = NULL;
    mInFileStart = compDataStart;
    mOutTotalSize = uncompSize;
    mInTotalSize = compSize;

    mInBufSize = StreamingZipInflater::INPUT_CHUNK_SIZE;
    mInBuf = new uint8_t[mInBufSize];

    mOutBufSize = StreamingZipInflater::OUTPUT_CHUNK_SIZE;
    mOutBuf = new uint8_t[mOutBufSize];

    initInflateState();
}

/*
 * Streaming access to compressed data held in an mmapped region of memory
 */
StreamingZipInflater::StreamingZipInflater(FileMap* dataMap, size_t uncompSize) {
    mFd = -1;
    mDataMap = dataMap;
    mOutTotalSize = uncompSize;
    mInTotalSize = dataMap->getDataLength();

    mInBuf = (uint8_t*) dataMap->getDataPtr();
    mInBufSize = mInTotalSize;

    mOutBufSize = StreamingZipInflater::OUTPUT_CHUNK_SIZE;
    mOutBuf = new uint8_t[mOutBufSize];

    initInflateState();
}

StreamingZipInflater::~StreamingZipInflater() {
    // tear down the in-flight zip state just in case
    ::inflateEnd(&mInflateState);

    if (mDataMap == NULL) {
        delete [] mInBuf;
    }
    delete [] mOutBuf;
}

void StreamingZipInflater::initInflateState() {
    ALOGV("Initializing inflate state");

    memset(&mInflateState, 0, sizeof(mInflateState));
    mInflateState.zalloc = Z_NULL;
    mInflateState.zfree = Z_NULL;
    mInflateState.opaque = Z_NULL;
    mInflateState.next_in = (Bytef*)mInBuf;
    mInflateState.next_out = (Bytef*) mOutBuf;
    mInflateState.avail_out = mOutBufSize;
    mInflateState.data_type = Z_UNKNOWN;

    mOutLastDecoded = mOutDeliverable = mOutCurPosition = 0;
    mInNextChunkOffset = 0;
    mStreamNeedsInit = true;

    if (mDataMap == NULL) {
        ::lseek(mFd, mInFileStart, SEEK_SET);
        mInflateState.avail_in = 0; // set when a chunk is read in
    } else {
        mInflateState.avail_in = mInBufSize;
    }
}

/*
 * Basic approach:
 *
 * 1. If we have undelivered uncompressed data, send it.  At this point
 *    either we've satisfied the request, or we've exhausted the available
 *    output data in mOutBuf.
 *
 * 2. While we haven't sent enough data to satisfy the request:
 *    0. if the request is for more data than exists, bail.
 *    a. if there is no input data to decode, read some into the input buffer
 *       and readjust the z_stream input pointers
 *    b. point the output to the start of the output buffer and decode what we can
 *    c. deliver whatever output data we can
 */
ssize_t StreamingZipInflater::read(void* outBuf, size_t count) {
    uint8_t* dest = (uint8_t*) outBuf;
    size_t bytesRead = 0;
    size_t toRead = min_of(count, size_t(mOutTotalSize - mOutCurPosition));
    while (toRead > 0) {
        // First, write from whatever we already have decoded and ready to go
        size_t deliverable = min_of(toRead, mOutLastDecoded - mOutDeliverable);
        if (deliverable > 0) {
            if (outBuf != NULL) memcpy(dest, mOutBuf + mOutDeliverable, deliverable);
            mOutDeliverable += deliverable;
            mOutCurPosition += deliverable;
            dest += deliverable;
            bytesRead += deliverable;
            toRead -= deliverable;
        }

        // need more data?  time to decode some.
        if (toRead > 0) {
            // if we don't have any data to decode, read some in.  If we're working
            // from mmapped data this won't happen, because the clipping to total size
            // will prevent reading off the end of the mapped input chunk.
            if ((mInflateState.avail_in == 0) && (mDataMap == NULL)) {
                int err = readNextChunk();
                if (err < 0) {
                    ALOGE("Unable to access asset data: %d", err);
                    if (!mStreamNeedsInit) {
                        ::inflateEnd(&mInflateState);
                        initInflateState();
                    }
                    return -1;
                }
            }
            // we know we've drained whatever is in the out buffer now, so just
            // start from scratch there, reading all the input we have at present.
            mInflateState.next_out = (Bytef*) mOutBuf;
            mInflateState.avail_out = mOutBufSize;

            /*
            ALOGV("Inflating to outbuf: avail_in=%u avail_out=%u next_in=%p next_out=%p",
                    mInflateState.avail_in, mInflateState.avail_out,
                    mInflateState.next_in, mInflateState.next_out);
            */
            int result = Z_OK;
            if (mStreamNeedsInit) {
                ALOGV("Initializing zlib to inflate");
                result = inflateInit2(&mInflateState, -MAX_WBITS);
                mStreamNeedsInit = false;
            }
            if (result == Z_OK) result = ::inflate(&mInflateState, Z_SYNC_FLUSH);
            if (result < 0) {
                // Whoops, inflation failed
                ALOGE("Error inflating asset: %d", result);
                ::inflateEnd(&mInflateState);
                initInflateState();
                return -1;
            } else {
                if (result == Z_STREAM_END) {
                    // we know we have to have reached the target size here and will
                    // not try to read any further, so just wind things up.
                    ::inflateEnd(&mInflateState);
                }

                // Note how much data we got, and off we go
                mOutDeliverable = 0;
                mOutLastDecoded = mOutBufSize - mInflateState.avail_out;
            }
        }
    }
    return bytesRead;
}

int StreamingZipInflater::readNextChunk() {
    assert(mDataMap == NULL);

    if (mInNextChunkOffset < mInTotalSize) {
        size_t toRead = min_of(mInBufSize, mInTotalSize - mInNextChunkOffset);
        if (toRead > 0) {
            ssize_t didRead = TEMP_FAILURE_RETRY(::read(mFd, mInBuf, toRead));
            if (kIsDebug) {
                ALOGV("Reading input chunk, size %08zx didread %08zx", toRead, didRead);
            }
            if (didRead < 0) {
                ALOGE("Error reading asset data: %s", strerror(errno));
                return didRead;
            } else {
                mInNextChunkOffset += didRead;
                mInflateState.next_in = (Bytef*) mInBuf;
                mInflateState.avail_in = didRead;
            }
        }
    }
    return 0;
}

// seeking backwards requires uncompressing fom the beginning, so is very
// expensive.  seeking forwards only requires uncompressing from the current
// position to the destination.
off64_t StreamingZipInflater::seekAbsolute(off64_t absoluteInputPosition) {
    if (absoluteInputPosition < mOutCurPosition) {
        // rewind and reprocess the data from the beginning
        if (!mStreamNeedsInit) {
            ::inflateEnd(&mInflateState);
        }
        initInflateState();
        read(NULL, absoluteInputPosition);
    } else if (absoluteInputPosition > mOutCurPosition) {
        read(NULL, absoluteInputPosition - mOutCurPosition);
    }
    // else if the target position *is* our current position, do nothing
    return absoluteInputPosition;
}
