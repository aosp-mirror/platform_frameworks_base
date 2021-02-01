/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "androidfw/Chunk.h"
#include "androidfw/Util.h"

#include "android-base/logging.h"

namespace android {

Chunk ChunkIterator::Next() {
  CHECK(len_ != 0) << "called Next() after last chunk";

  const incfs::map_ptr<ResChunk_header> this_chunk = next_chunk_;
  CHECK((bool) this_chunk) << "Next() called without verifying next chunk";

  // We've already checked the values of this_chunk, so safely increment.
  next_chunk_ = this_chunk.offset(dtohl(this_chunk->size)).convert<ResChunk_header>();
  len_ -= dtohl(this_chunk->size);

  if (len_ != 0) {
    // Prepare the next chunk.
    if (VerifyNextChunkNonFatal()) {
      VerifyNextChunk();
    }
  }
  return Chunk(this_chunk.verified());
}

// TODO(b/111401637) remove this and have full resource file verification
// Returns false if there was an error.
bool ChunkIterator::VerifyNextChunkNonFatal() {
  if (len_ < sizeof(ResChunk_header)) {
    last_error_ = "not enough space for header";
    last_error_was_fatal_ = false;
    return false;
  }

  if (!next_chunk_) {
    last_error_ = "failed to read chunk from data";
    last_error_was_fatal_ = false;
    return false;
  }

  const size_t size = dtohl(next_chunk_->size);
  if (size > len_) {
    last_error_ = "chunk size is bigger than given data";
    last_error_was_fatal_ = false;
    return false;
  }
  return true;
}

// Returns false if there was an error.
bool ChunkIterator::VerifyNextChunk() {
  // This data must be 4-byte aligned, since we directly
  // access 32-bit words, which must be aligned on
  // certain architectures.
  if (!util::IsFourByteAligned(next_chunk_)) {
    last_error_ = "header not aligned on 4-byte boundary";
    return false;
  }

  if (len_ < sizeof(ResChunk_header)) {
    last_error_ = "not enough space for header";
    return false;
  }

  if (!next_chunk_) {
    last_error_ = "failed to read chunk from data";
    return false;
  }

  const size_t header_size = dtohs(next_chunk_->headerSize);
  const size_t size = dtohl(next_chunk_->size);
  if (header_size < sizeof(ResChunk_header)) {
    last_error_ = "header size too small";
    return false;
  }

  if (header_size > size) {
    last_error_ = "header size is larger than entire chunk";
    return false;
  }

  if (size > len_) {
    last_error_ = "chunk size is bigger than given data";
    return false;
  }

  if ((size | header_size) & 0x03U) {
    last_error_ = "header sizes are not aligned on 4-byte boundary";
    return false;
  }
  return true;
}

}  // namespace android
