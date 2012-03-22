/*
 * Copyright (C) 2012 The Android Open Source Project
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
#define LOG_TAG "SkipCutBuffer"
#include <utils/Log.h>

#include <media/stagefright/foundation/ADebug.h>
#include <media/stagefright/MediaBuffer.h>
#include <media/stagefright/SkipCutBuffer.h>

namespace android {

SkipCutBuffer::SkipCutBuffer(int32_t skip, int32_t cut, int32_t output_size) {
    mFrontPadding = skip;
    mBackPadding = cut;
    mWriteHead = 0;
    mReadHead = 0;
    mCapacity = cut + output_size;
    mCutBuffer = new char[mCapacity];
    ALOGV("skipcutbuffer %d %d %d", skip, cut, mCapacity);
}

SkipCutBuffer::~SkipCutBuffer() {
    delete[] mCutBuffer;
}

void SkipCutBuffer::submit(MediaBuffer *buffer) {
    int32_t offset = buffer->range_offset();
    int32_t buflen = buffer->range_length();

    // drop the initial data from the buffer if needed
    if (mFrontPadding > 0) {
        // still data left to drop
        int32_t to_drop = (buflen < mFrontPadding) ? buflen : mFrontPadding;
        offset += to_drop;
        buflen -= to_drop;
        buffer->set_range(offset, buflen);
        mFrontPadding -= to_drop;
    }


    // append data to cutbuffer
    char *src = ((char*) buffer->data()) + offset;
    write(src, buflen);


    // the mediabuffer is now empty. Fill it from cutbuffer, always leaving
    // at least mBackPadding bytes in the cutbuffer
    char *dst = (char*) buffer->data();
    size_t copied = read(dst, buffer->size());
    buffer->set_range(0, copied);
}

void SkipCutBuffer::clear() {
    mWriteHead = mReadHead = 0;
}

void SkipCutBuffer::write(const char *src, size_t num) {
    int32_t sizeused = (mWriteHead - mReadHead);
    if (sizeused < 0) sizeused += mCapacity;

    // everything must fit
    CHECK_GE((mCapacity - size_t(sizeused)), num);

    size_t copyfirst = (mCapacity - mWriteHead);
    if (copyfirst > num) copyfirst = num;
    if (copyfirst) {
        memcpy(mCutBuffer + mWriteHead, src, copyfirst);
        num -= copyfirst;
        src += copyfirst;
        mWriteHead += copyfirst;
        CHECK_LE(mWriteHead, mCapacity);
        if (mWriteHead == mCapacity) mWriteHead = 0;
        if (num) {
            memcpy(mCutBuffer, src, num);
            mWriteHead += num;
        }
    }
}

size_t SkipCutBuffer::read(char *dst, size_t num) {
    int32_t available = (mWriteHead - mReadHead);
    if (available < 0) available += mCapacity;

    available -= mBackPadding;
    if (available <=0) {
        return 0;
    }
    if (available < num) {
        num = available;
    }

    size_t copyfirst = (mCapacity - mReadHead);
    if (copyfirst > num) copyfirst = num;
    if (copyfirst) {
        memcpy(dst, mCutBuffer + mReadHead, copyfirst);
        num -= copyfirst;
        dst += copyfirst;
        mReadHead += copyfirst;
        CHECK_LE(mReadHead, mCapacity);
        if (mReadHead == mCapacity) mReadHead = 0;
        if (num) {
            memcpy(dst, mCutBuffer, num);
            mReadHead += num;
        }
    }
    return available;
}

size_t SkipCutBuffer::size() {
    int32_t available = (mWriteHead - mReadHead);
    if (available < 0) available += mCapacity;
    return available;
}

}  // namespace android
