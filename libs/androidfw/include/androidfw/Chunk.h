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

#ifndef CHUNK_H_
#define CHUNK_H_

#include "android-base/logging.h"
#include "android-base/macros.h"
#include "utils/ByteOrder.h"

#ifdef _WIN32
#ifdef ERROR
#undef ERROR
#endif
#endif

#include "androidfw/ResourceTypes.h"

namespace android {

// Helpful wrapper around a ResChunk_header that provides getter methods
// that handle endianness conversions and provide access to the data portion
// of the chunk.
class Chunk {
 public:
  explicit Chunk(const ResChunk_header* chunk) : device_chunk_(chunk) {}

  // Returns the type of the chunk. Caller need not worry about endianness.
  inline int type() const { return dtohs(device_chunk_->type); }

  // Returns the size of the entire chunk. This can be useful for skipping
  // over the entire chunk. Caller need not worry about endianness.
  inline size_t size() const { return dtohl(device_chunk_->size); }

  // Returns the size of the header. Caller need not worry about endianness.
  inline size_t header_size() const { return dtohs(device_chunk_->headerSize); }

  template <typename T, size_t MinSize = sizeof(T)>
  inline const T* header() const {
    if (header_size() >= MinSize) {
      return reinterpret_cast<const T*>(device_chunk_);
    }
    return nullptr;
  }

  inline const void* data_ptr() const {
    return reinterpret_cast<const uint8_t*>(device_chunk_) + header_size();
  }

  inline size_t data_size() const { return size() - header_size(); }

 private:
  const ResChunk_header* device_chunk_;
};

// Provides a Java style iterator over an array of ResChunk_header's.
// Validation is performed while iterating.
// The caller should check if there was an error during chunk validation
// by calling HadError() and GetLastError() to get the reason for failure.
// Example:
//
//   ChunkIterator iter(data_ptr, data_len);
//   while (iter.HasNext()) {
//     const Chunk chunk = iter.Next();
//     ...
//   }
//
//   if (iter.HadError()) {
//     LOG(ERROR) << iter.GetLastError();
//   }
//
class ChunkIterator {
 public:
  ChunkIterator(const void* data, size_t len)
      : next_chunk_(reinterpret_cast<const ResChunk_header*>(data)),
        len_(len),
        last_error_(nullptr) {
    CHECK(next_chunk_ != nullptr) << "data can't be nullptr";
    VerifyNextChunk();
  }

  Chunk Next();
  inline bool HasNext() const { return !HadError() && len_ != 0; };
  // Returns whether there was an error and processing should stop
  inline bool HadError() const { return last_error_ != nullptr; }
  inline std::string GetLastError() const { return last_error_; }
  // Returns whether there was an error and processing should stop. For legacy purposes,
  // some errors are considered "non fatal". Fatal errors stop processing new chunks and
  // throw away any chunks already processed. Non fatal errors also stop processing new
  // chunks, but, will retain and use any valid chunks already processed.
  inline bool HadFatalError() const { return HadError() && last_error_was_fatal_; }

 private:
  DISALLOW_COPY_AND_ASSIGN(ChunkIterator);

  // Returns false if there was an error.
  bool VerifyNextChunk();
  // Returns false if there was an error. For legacy purposes.
  bool VerifyNextChunkNonFatal();

  const ResChunk_header* next_chunk_;
  size_t len_;
  const char* last_error_;
  bool last_error_was_fatal_ = true;
};

}  // namespace android

#endif /* CHUNK_H_ */
