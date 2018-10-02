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

#include <cstring>
#include <memory>
#include <string>
#include <type_traits>
#include <vector>

#include "android-base/logging.h"
#include "android-base/macros.h"

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
    size_t block_size_;
  };

  typedef std::vector<Block>::const_iterator const_iterator;

  /**
   * Create a BigBuffer with block allocation sizes
   * of block_size.
   */
  explicit BigBuffer(size_t block_size);

  BigBuffer(BigBuffer&& rhs) noexcept;

  /**
   * Number of occupied bytes in all the allocated blocks.
   */
  size_t size() const;

  /**
   * Returns a pointer to an array of T, where T is
   * a POD type. The elements are zero-initialized.
   */
  template <typename T>
  T* NextBlock(size_t count = 1);

  /**
   * Returns the next block available and puts the size in out_count.
   * This is useful for grabbing blocks where the size doesn't matter.
   * Use BackUp() to give back any bytes that were not used.
   */
  void* NextBlock(size_t* out_count);

  /**
   * Backs up count bytes. This must only be called after NextBlock()
   * and can not be larger than sizeof(T) * count of the last NextBlock()
   * call.
   */
  void BackUp(size_t count);

  /**
   * Moves the specified BigBuffer into this one. When this method
   * returns, buffer is empty.
   */
  void AppendBuffer(BigBuffer&& buffer);

  /**
   * Pads the block with 'bytes' bytes of zero values.
   */
  void Pad(size_t bytes);

  /**
   * Pads the block so that it aligns on a 4 byte boundary.
   */
  void Align4();

  size_t block_size() const;

  const_iterator begin() const;
  const_iterator end() const;

  std::string to_string() const;

 private:
  DISALLOW_COPY_AND_ASSIGN(BigBuffer);

  /**
   * Returns a pointer to a buffer of the requested size.
   * The buffer is zero-initialized.
   */
  void* NextBlockImpl(size_t size);

  size_t block_size_;
  size_t size_;
  std::vector<Block> blocks_;
};

inline BigBuffer::BigBuffer(size_t block_size)
    : block_size_(block_size), size_(0) {}

inline BigBuffer::BigBuffer(BigBuffer&& rhs) noexcept
    : block_size_(rhs.block_size_),
      size_(rhs.size_),
      blocks_(std::move(rhs.blocks_)) {}

inline size_t BigBuffer::size() const { return size_; }

inline size_t BigBuffer::block_size() const { return block_size_; }

template <typename T>
inline T* BigBuffer::NextBlock(size_t count) {
  static_assert(std::is_standard_layout<T>::value,
                "T must be standard_layout type");
  CHECK(count != 0);
  return reinterpret_cast<T*>(NextBlockImpl(sizeof(T) * count));
}

inline void BigBuffer::BackUp(size_t count) {
  Block& block = blocks_.back();
  block.size -= count;
  size_ -= count;
}

inline void BigBuffer::AppendBuffer(BigBuffer&& buffer) {
  std::move(buffer.blocks_.begin(), buffer.blocks_.end(),
            std::back_inserter(blocks_));
  size_ += buffer.size_;
  buffer.blocks_.clear();
  buffer.size_ = 0;
}

inline void BigBuffer::Pad(size_t bytes) { NextBlock<char>(bytes); }

inline void BigBuffer::Align4() {
  const size_t unaligned = size_ % 4;
  if (unaligned != 0) {
    Pad(4 - unaligned);
  }
}

inline BigBuffer::const_iterator BigBuffer::begin() const {
  return blocks_.begin();
}

inline BigBuffer::const_iterator BigBuffer::end() const {
  return blocks_.end();
}

}  // namespace aapt

#endif  // AAPT_BIG_BUFFER_H
