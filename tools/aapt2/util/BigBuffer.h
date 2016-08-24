/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef AAPT_BIG_BUFFER_H
#define AAPT_BIG_BUFFER_H

#include <cassert>
#include <cstring>
#include <memory>
#include <type_traits>
#include <vector>

namespace aapt {

/**
 * Inspired by protobuf's ZeroCopyOutputStream, offers blocks of memory
 * in which to write without knowing the full size of the entire payload.
 * This is essentially a list of memory blocks. As one fills up, another
 * block is allocated and appended to the end of the list.
 */
class BigBuffer {
public:
    /**
     * A contiguous block of allocated memory.
     */
    struct Block {
        /**
         * Pointer to the memory.
         */
        std::unique_ptr<uint8_t[]> buffer;

        /**
         * Size of memory that is currently occupied. The actual
         * allocation may be larger.
         */
        size_t size;

    private:
        friend class BigBuffer;

        /**
         * The size of the memory block allocation.
         */
        size_t mBlockSize;
    };

    typedef std::vector<Block>::const_iterator const_iterator;

    /**
     * Create a BigBuffer with block allocation sizes
     * of blockSize.
     */
    BigBuffer(size_t blockSize);

    BigBuffer(const BigBuffer&) = delete; // No copying.

    BigBuffer(BigBuffer&& rhs);

    /**
     * Number of occupied bytes in all the allocated blocks.
     */
    size_t size() const;

    /**
     * Returns a pointer to an array of T, where T is
     * a POD type. The elements are zero-initialized.
     */
    template <typename T>
    T* nextBlock(size_t count = 1);

    /**
     * Moves the specified BigBuffer into this one. When this method
     * returns, buffer is empty.
     */
    void appendBuffer(BigBuffer&& buffer);

    /**
     * Pads the block with 'bytes' bytes of zero values.
     */
    void pad(size_t bytes);

    /**
     * Pads the block so that it aligns on a 4 byte boundary.
     */
    void align4();

    const_iterator begin() const;
    const_iterator end() const;

private:
    /**
     * Returns a pointer to a buffer of the requested size.
     * The buffer is zero-initialized.
     */
    void* nextBlockImpl(size_t size);

    size_t mBlockSize;
    size_t mSize;
    std::vector<Block> mBlocks;
};

inline BigBuffer::BigBuffer(size_t blockSize) : mBlockSize(blockSize), mSize(0) {
}

inline BigBuffer::BigBuffer(BigBuffer&& rhs) :
        mBlockSize(rhs.mBlockSize), mSize(rhs.mSize), mBlocks(std::move(rhs.mBlocks)) {
}

inline size_t BigBuffer::size() const {
    return mSize;
}

template <typename T>
inline T* BigBuffer::nextBlock(size_t count) {
    static_assert(std::is_standard_layout<T>::value, "T must be standard_layout type");
    assert(count != 0);
    return reinterpret_cast<T*>(nextBlockImpl(sizeof(T) * count));
}

inline void BigBuffer::appendBuffer(BigBuffer&& buffer) {
    std::move(buffer.mBlocks.begin(), buffer.mBlocks.end(), std::back_inserter(mBlocks));
    mSize += buffer.mSize;
    buffer.mBlocks.clear();
    buffer.mSize = 0;
}

inline void BigBuffer::pad(size_t bytes) {
    nextBlock<char>(bytes);
}

inline void BigBuffer::align4() {
    const size_t unaligned = mSize % 4;
    if (unaligned != 0) {
        pad(4 - unaligned);
    }
}

inline BigBuffer::const_iterator BigBuffer::begin() const {
    return mBlocks.begin();
}

inline BigBuffer::const_iterator BigBuffer::end() const {
    return mBlocks.end();
}

} // namespace aapt

#endif // AAPT_BIG_BUFFER_H
