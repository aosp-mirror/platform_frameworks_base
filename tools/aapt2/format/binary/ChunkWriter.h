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

#ifndef AAPT_FORMAT_BINARY_CHUNKWRITER_H
#define AAPT_FORMAT_BINARY_CHUNKWRITER_H

#include "android-base/macros.h"
#include "androidfw/ResourceTypes.h"

#include "util/BigBuffer.h"
#include "util/Util.h"

namespace aapt {

class ChunkWriter {
 public:
  explicit inline ChunkWriter(BigBuffer* buffer) : buffer_(buffer) {
  }
  ChunkWriter(ChunkWriter&&) = default;
  ChunkWriter& operator=(ChunkWriter&&) = default;

  template <typename T>
  inline T* StartChunk(uint16_t type) {
    start_size_ = buffer_->size();
    T* chunk = buffer_->NextBlock<T>();
    header_ = &chunk->header;
    header_->type = util::HostToDevice16(type);
    header_->headerSize = util::HostToDevice16(sizeof(T));
    return chunk;
  }

  template <typename T>
  inline T* NextBlock(size_t count = 1) {
    return buffer_->NextBlock<T>(count);
  }

  inline BigBuffer* buffer() {
    return buffer_;
  }

  inline android::ResChunk_header* chunk_header() {
    return header_;
  }

  inline size_t size() {
    return buffer_->size() - start_size_;
  }

  inline android::ResChunk_header* Finish() {
    buffer_->Align4();
    header_->size = util::HostToDevice32(buffer_->size() - start_size_);
    return header_;
  }

 private:
  DISALLOW_COPY_AND_ASSIGN(ChunkWriter);

  BigBuffer* buffer_;
  size_t start_size_ = 0;
  android::ResChunk_header* header_ = nullptr;
};

template <>
inline android::ResChunk_header* ChunkWriter::StartChunk(uint16_t type) {
  start_size_ = buffer_->size();
  header_ = buffer_->NextBlock<android::ResChunk_header>();
  header_->type = util::HostToDevice16(type);
  header_->headerSize = util::HostToDevice16(sizeof(android::ResChunk_header));
  return header_;
}

}  // namespace aapt

#endif /* AAPT_FORMAT_BINARY_CHUNKWRITER_H */
