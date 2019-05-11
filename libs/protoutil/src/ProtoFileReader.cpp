/*
 * Copyright (C) 2018 The Android Open Source Project
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
#define LOG_TAG "libprotoutil"

#include <android/util/ProtoFileReader.h>
#include <cutils/log.h>

#include <cinttypes>
#include <type_traits>

#include <unistd.h>

namespace android {
namespace util {

/**
 * Get the amount of data remaining in the file in fd, or -1 if the file size can't be measured.
 * It's not the whole file, but this allows us to skip any preamble that might have already
 * been passed over.
 */
ssize_t get_file_size(int fd) {
    off_t current = lseek(fd, 0, SEEK_CUR);
    if (current < 0) {
        return -1;
    }
    off_t end = lseek(fd, 0, SEEK_END);
    if (end < 0) {
        return -1;
    }
    off_t err = lseek(fd, current, SEEK_SET);
    if (err < 0) {
        ALOGW("get_file_size could do SEEK_END but not SEEK_SET. We might have skipped data.");
        return -1;
    }
    return (ssize_t)(end-current);
}

// =========================================================================
ProtoFileReader::ProtoFileReader(int fd)
        :mFd(fd),
         mStatus(NO_ERROR),
         mSize(get_file_size(fd)),
         mPos(0),
         mOffset(0),
         mMaxOffset(0),
         mChunkSize(sizeof(mBuffer)) {
}

ProtoFileReader::~ProtoFileReader() {
}

ssize_t
ProtoFileReader::size() const
{
    return (ssize_t)mSize;
}

size_t
ProtoFileReader::bytesRead() const
{
    return mPos;
}

uint8_t const*
ProtoFileReader::readBuffer()
{
    return hasNext() ? mBuffer + mOffset : NULL;
}

size_t
ProtoFileReader::currentToRead()
{
    return mMaxOffset - mOffset;
}

bool
ProtoFileReader::hasNext()
{
    return ensure_data();
}

uint8_t
ProtoFileReader::next()
{
    if (!ensure_data()) {
        // Shouldn't get to here.  Always call hasNext() before calling next().
        return 0;
    }
    return mBuffer[mOffset++];
}

uint64_t
ProtoFileReader::readRawVarint()
{
    uint64_t val = 0, shift = 0;
    while (true) {
        if (!hasNext()) {
            ALOGW("readRawVarint() called without hasNext() called first.");
            mStatus = NOT_ENOUGH_DATA;
            return 0;
        }
        uint8_t byte = next();
        val |= (INT64_C(0x7F) & byte) << shift;
        if ((byte & 0x80) == 0) break;
        shift += 7;
    }
    return val;
}

void
ProtoFileReader::move(size_t amt)
{
    while (mStatus == NO_ERROR && amt > 0) {
        if (!ensure_data()) {
            return;
        }
        const size_t chunk =
                mMaxOffset - mOffset > amt ? amt : mMaxOffset - mOffset;
        mOffset += chunk;
        amt -= chunk;
    }
}

status_t
ProtoFileReader::getError() const {
    return mStatus;
}

bool
ProtoFileReader::ensure_data() {
    if (mStatus != NO_ERROR) {
        return false;
    }
    if (mOffset < mMaxOffset) {
        return true;
    }
    ssize_t amt = TEMP_FAILURE_RETRY(read(mFd, mBuffer, mChunkSize));
    if (amt == 0) {
        return false;
    } else if (amt < 0) {
        mStatus = -errno;
        return false;
    } else {
        mOffset = 0;
        mMaxOffset = amt;
        return true;
    }
}


} // util
} // android

