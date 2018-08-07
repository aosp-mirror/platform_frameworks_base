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

#include "format/binary/ResChunkPullParser.h"

#include <inttypes.h>
#include <cstddef>

#include "android-base/logging.h"
#include "android-base/stringprintf.h"
#include "androidfw/ResourceTypes.h"

#include "util/Util.h"

namespace aapt {

using android::ResChunk_header;
using android::base::StringPrintf;

static std::string ChunkHeaderDump(const ResChunk_header* header) {
  return StringPrintf("(type=%02" PRIx16 " header_size=%" PRIu16 " size=%" PRIu32 ")",
                      util::DeviceToHost16(header->type), util::DeviceToHost16(header->headerSize),
                      util::DeviceToHost32(header->size));
}

ResChunkPullParser::Event ResChunkPullParser::Next() {
  if (!IsGoodEvent(event_)) {
    return event_;
  }

  if (event_ == Event::kStartDocument) {
    current_chunk_ = data_;
  } else {
    current_chunk_ = (const ResChunk_header*)(((const char*)current_chunk_) +
                                              util::DeviceToHost32(current_chunk_->size));
  }

  const std::ptrdiff_t diff = (const char*)current_chunk_ - (const char*)data_;
  CHECK(diff >= 0) << "diff is negative";
  const size_t offset = static_cast<const size_t>(diff);

  if (offset == len_) {
    current_chunk_ = nullptr;
    return (event_ = Event::kEndDocument);
  } else if (offset + sizeof(ResChunk_header) > len_) {
    error_ = "chunk is past the end of the document";
    current_chunk_ = nullptr;
    return (event_ = Event::kBadDocument);
  }

  if (util::DeviceToHost16(current_chunk_->headerSize) < sizeof(ResChunk_header)) {
    error_ = "chunk has too small header";
    current_chunk_ = nullptr;
    return (event_ = Event::kBadDocument);
  } else if (util::DeviceToHost32(current_chunk_->size) <
             util::DeviceToHost16(current_chunk_->headerSize)) {
    error_ = "chunk's total size is smaller than header " + ChunkHeaderDump(current_chunk_);
    current_chunk_ = nullptr;
    return (event_ = Event::kBadDocument);
  } else if (offset + util::DeviceToHost32(current_chunk_->size) > len_) {
    error_ = "chunk's data extends past the end of the document " + ChunkHeaderDump(current_chunk_);
    current_chunk_ = nullptr;
    return (event_ = Event::kBadDocument);
  }
  return (event_ = Event::kChunk);
}

}  // namespace aapt
