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

#include <android/util/EncodedBuffer.h>

#include <stdlib.h>

namespace android {
namespace util {

const size_t BUFFER_SIZE = 8 * 1024; // 8 KB

EncodedBuffer::Pointer::Pointer() : Pointer(BUFFER_SIZE)
{
}

EncodedBuffer::Pointer::Pointer(size_t chunkSize)
        :mIndex(0),
         mOffset(0)
{
    mChunkSize = chunkSize == 0 ? BUFFER_SIZE : chunkSize;
}

size_t
EncodedBuffer::Pointer::pos() const
{
    return mIndex * mChunkSize + mOffset;
}

size_t
EncodedBuffer::Pointer::index() const
{
    return mIndex;
}

size_t
EncodedBuffer::Pointer::offset() const
{
    return mOffset;
}

void
EncodedBuffer::Pointer::move(size_t amt)
{
    size_t newOffset = mOffset + amt;
    mIndex += newOffset / mChunkSize;
    mOffset = newOffset % mChunkSize;
}

void
EncodedBuffer::Pointer::rewind()
{
    mIndex = 0;
    mOffset = 0;
}

EncodedBuffer::Pointer
EncodedBuffer::Pointer::copy() const
{
    Pointer p = Pointer(mChunkSize);
    p.mIndex = mIndex;
    p.mOffset = mOffset;
    return p;
}

// ===========================================================
EncodedBuffer::EncodedBuffer() : EncodedBuffer(0)
{
}

EncodedBuffer::EncodedBuffer(size_t chunkSize)
        :mBuffers()
{
    mChunkSize = chunkSize == 0 ? BUFFER_SIZE : chunkSize;
    mWp = Pointer(mChunkSize);
}

EncodedBuffer::~EncodedBuffer()
{
    for (size_t i=0; i<mBuffers.size(); i++) {
        uint8_t* buf = mBuffers[i];
        free(buf);
    }
}

inline uint8_t*
EncodedBuffer::at(const Pointer& p) const
{
    return mBuffers[p.index()] + p.offset();
}

/******************************** Write APIs ************************************************/
size_t
EncodedBuffer::size() const
{
    return mWp.pos();
}

EncodedBuffer::Pointer*
EncodedBuffer::wp()
{
    return &mWp;
}

uint8_t*
EncodedBuffer::writeBuffer()
{
    // This prevents write pointer move too fast than allocating the buffer.
    if (mWp.index() > mBuffers.size()) return NULL;
    uint8_t* buf = NULL;
    if (mWp.index() == mBuffers.size()) {
        buf = (uint8_t*)malloc(mChunkSize);

        if (buf == NULL) return NULL; // This indicates NO_MEMORY

        mBuffers.push_back(buf);
    }
    return at(mWp);
}

size_t
EncodedBuffer::currentToWrite()
{
    return mChunkSize - mWp.offset();
}

size_t
EncodedBuffer::writeRawVarint(uint32_t val)
{
    size_t size = 0;
    while (true) {
        size++;
        if ((val & ~0x7F) == 0) {
            *writeBuffer() = (uint8_t) val;
            mWp.move();
            return size;
        } else {
            *writeBuffer() = (uint8_t)((val & 0x7F) | 0x80);
            mWp.move();
            val >>= 7;
        }
    }
}

size_t
EncodedBuffer::writeHeader(uint32_t fieldId, uint8_t wireType)
{
    return writeRawVarint((fieldId << 3) | wireType);
}

/********************************* Read APIs ************************************************/
EncodedBuffer::iterator
EncodedBuffer::begin() const
{
    return EncodedBuffer::iterator(*this);
}

EncodedBuffer::iterator::iterator(const EncodedBuffer& buffer)
        :mData(buffer),
         mRp(buffer.mChunkSize)
{
}

size_t
EncodedBuffer::iterator::size() const
{
    return mData.size();
}

size_t
EncodedBuffer::iterator::bytesRead() const
{
    return mRp.pos();
}

EncodedBuffer::Pointer*
EncodedBuffer::iterator::rp()
{
    return &mRp;
}

uint8_t const*
EncodedBuffer::iterator::readBuffer()
{
    return hasNext() ? const_cast<uint8_t const*>(mData.at(mRp)) : NULL;
}

size_t
EncodedBuffer::iterator::currentToRead()
{
    return (mData.mWp.index() > mRp.index()) ?
            mData.mChunkSize - mRp.offset() :
            mData.mWp.offset() - mRp.offset();
}

bool
EncodedBuffer::iterator::hasNext()
{
    return mRp.pos() < mData.mWp.pos();
}

uint8_t
EncodedBuffer::iterator::next()
{
    uint8_t res = *(mData.at(mRp));
    mRp.move();
    return res;
}

uint32_t
EncodedBuffer::iterator::readRawVarint()
{
    uint32_t val = 0, shift = 0;
    while (true) {
        uint8_t byte = next();
        val += (byte & 0x7F) << shift;
        if ((byte & 0x80) == 0) break;
        shift += 7;
    }
    return val;
}

} // util
} // android
