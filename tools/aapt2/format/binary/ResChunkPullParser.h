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

#ifndef AAPT_FORMAT_BINARY_RESCHUNKPULLPARSER_H
#define AAPT_FORMAT_BINARY_RESCHUNKPULLPARSER_H

#include <string>

#include "android-base/macros.h"
#include "androidfw/ResourceTypes.h"

#include "util/Util.h"

namespace aapt {

// A pull parser, modeled after XmlPullParser, that reads android::ResChunk_header structs from a
// block of data.
// An android::ResChunk_header specifies a type, headerSize, and size. The pull parser will verify
// that the chunk's size doesn't extend beyond the available data, and will iterate over each chunk
// in the given block of data.
// Processing nested chunks is done by creating a new ResChunkPullParser pointing to the data
// portion of a chunk.
class ResChunkPullParser {
 public:
  enum class Event {
    kStartDocument,
    kEndDocument,
    kBadDocument,

    kChunk,
  };

  // Returns false if the event is EndDocument or BadDocument.
  static bool IsGoodEvent(Event event);

  // Create a ResChunkPullParser to read android::ResChunk_headers from the memory pointed to by
  // data, of len bytes.
  ResChunkPullParser(const void* data, size_t len);

  Event event() const;
  const std::string& error() const;
  const android::ResChunk_header* chunk() const;

  // Move to the next android::ResChunk_header.
  Event Next();

 private:
  DISALLOW_COPY_AND_ASSIGN(ResChunkPullParser);

  Event event_;
  const android::ResChunk_header* data_;
  size_t len_;
  const android::ResChunk_header* current_chunk_;
  std::string error_;
};

template <typename T, size_t MinSize = sizeof(T)>
inline static const T* ConvertTo(const android::ResChunk_header* chunk) {
  if (util::DeviceToHost16(chunk->headerSize) < MinSize) {
    return nullptr;
  }
  return reinterpret_cast<const T*>(chunk);
}

inline static const uint8_t* GetChunkData(const android::ResChunk_header* chunk) {
  return reinterpret_cast<const uint8_t*>(chunk) + util::DeviceToHost16(chunk->headerSize);
}

inline static uint32_t GetChunkDataLen(const android::ResChunk_header* chunk) {
  return util::DeviceToHost32(chunk->size) - util::DeviceToHost16(chunk->headerSize);
}

//
// Implementation
//

inline bool ResChunkPullParser::IsGoodEvent(ResChunkPullParser::Event event) {
  return event != Event::kEndDocument && event != Event::kBadDocument;
}

inline ResChunkPullParser::ResChunkPullParser(const void* data, size_t len)
    : event_(Event::kStartDocument),
      data_(reinterpret_cast<const android::ResChunk_header*>(data)),
      len_(len),
      current_chunk_(nullptr) {
}

inline ResChunkPullParser::Event ResChunkPullParser::event() const {
  return event_;
}

inline const std::string& ResChunkPullParser::error() const {
  return error_;
}

inline const android::ResChunk_header* ResChunkPullParser::chunk() const {
  return current_chunk_;
}

}  // namespace aapt

#endif  // AAPT_FORMAT_BINARY_RESCHUNKPULLPARSER_H
