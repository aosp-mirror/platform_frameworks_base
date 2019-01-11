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

#ifndef ANDROID_UTIL_ENCODED_BUFFER_H
#define ANDROID_UTIL_ENCODED_BUFFER_H

#include <stdint.h>
#include <vector>

namespace android {
namespace util {

/**
 * A stream of bytes containing a read pointer and a write pointer,
 * backed by a set of fixed-size buffers.  There are write functions for the
 * primitive types stored by protocol buffers, but none of the logic
 * for tags, inner objects, or any of that.
 *
 * Terminology:
 *      *Pos:       Position in the whole data set (as if it were a single buffer).
 *      *Index:     Index of a buffer within the mBuffers list.
 *      *Offset:    Position within a buffer.
 */
class EncodedBuffer
{
public:
    EncodedBuffer();
    explicit EncodedBuffer(size_t chunkSize);
    ~EncodedBuffer();

    class Pointer {
    public:
        Pointer();
        explicit Pointer(size_t chunkSize);

        size_t pos() const;
        size_t index() const;
        size_t offset() const;

        Pointer* move(size_t amt);
        inline Pointer* move() { return move(1); };
        Pointer* rewind();

        Pointer copy() const;

    private:
        size_t mChunkSize;
        size_t mIndex;
        size_t mOffset;
    };

    /**
     * Clears the buffer by rewinding its write pointer to avoid de/allocate buffers in heap.
     */
    void clear();

    /******************************** Write APIs ************************************************/

    /**
     * Returns the number of bytes written in the buffer
     */
    size_t size() const;

    /**
     * Returns the write pointer.
     */
    Pointer* wp();

    /**
     * Returns the current position of write pointer, if the write buffer is full, it will automatically
     * rotate to a new buffer with given chunkSize. If NULL is returned, it means NO_MEMORY
     */
    uint8_t* writeBuffer();

    /**
     * Returns the writeable size in the current write buffer .
     */
    size_t currentToWrite();

    /**
     * Write a single byte to the buffer.
     */
    void writeRawByte(uint8_t val);

    /**
     * Write a varint32 into the buffer. Return the size of the varint.
     */
    size_t writeRawVarint32(uint32_t val);

    /**
     * Write a varint64 into the buffer. Return the size of the varint.
     */
    size_t writeRawVarint64(uint64_t val);

    /**
     * Write Fixed32 into the buffer.
     */
    void writeRawFixed32(uint32_t val);

    /**
     * Write Fixed64 into the buffer.
     */
    void writeRawFixed64(uint64_t val);

    /**
     * Write a protobuf header. Return the size of the header.
     */
    size_t writeHeader(uint32_t fieldId, uint8_t wireType);

    /********************************* Edit APIs ************************************************/
    /**
     * Returns the edit pointer.
     */
    Pointer* ep();

    /**
     * Read a single byte at ep, and move ep to next byte;
     */
    uint8_t readRawByte();

    /**
     * Read varint starting at ep, ep will move to pos of next byte.
     */
    uint64_t readRawVarint();

    /**
     * Read 4 bytes starting at ep, ep will move to pos of next byte.
     */
    uint32_t readRawFixed32();

    /**
     * Read 8 bytes starting at ep, ep will move to pos of next byte.
     */
    uint64_t readRawFixed64();

    /**
     * Edit 4 bytes starting at pos.
     */
    void editRawFixed32(size_t pos, uint32_t val);

    /**
     * Copy _size_ bytes of data starting at __srcPos__ to wp, srcPos must be larger than wp.pos().
     */
    void copy(size_t srcPos, size_t size);

    /********************************* Read APIs ************************************************/
    class iterator;
    friend class iterator;
    class iterator {
    public:
        explicit iterator(const EncodedBuffer& buffer);

        /**
         * Returns the number of bytes written in the buffer
         */
        size_t size() const;

        /**
         * Returns the size of total bytes read.
         */
        size_t bytesRead() const;

        /**
         * Returns the read pointer.
         */
        Pointer* rp();

        /**
         * Returns the current position of read pointer, if NULL is returned, it reaches end of buffer.
         */
        uint8_t const* readBuffer();

        /**
         * Returns the readable size in the current read buffer.
         */
        size_t currentToRead();

        /**
         * Returns true if next bytes is available for read.
         */
        bool hasNext();

        /**
         * Reads the current byte and moves pointer 1 bit.
         */
        uint8_t next();

        /**
         * Read varint from iterator, the iterator will point to next available byte.
         */
        uint64_t readRawVarint();

    private:
        const EncodedBuffer& mData;
        Pointer mRp;
    };

    /**
     * Returns the iterator of EncodedBuffer so it guarantees consumers won't be able to modified the buffer.
     */
    iterator begin() const;

private:
    size_t mChunkSize;
    std::vector<uint8_t*> mBuffers;

    Pointer mWp;
    Pointer mEp;

    inline uint8_t* at(const Pointer& p) const; // helper function to get value
};

} // util
} // android

#endif // ANDROID_UTIL_ENCODED_BUFFER_H

