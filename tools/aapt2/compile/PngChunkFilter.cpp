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

#include "compile/Png.h"

#include "android-base/stringprintf.h"
#include "androidfw/StringPiece.h"

#include "io/Io.h"

using ::android::StringPiece;
using ::android::base::StringPrintf;

namespace aapt {

static constexpr const char* kPngSignature = "\x89\x50\x4e\x47\x0d\x0a\x1a\x0a";

// Useful helper function that encodes individual bytes into a uint32
// at compile time.
constexpr uint32_t u32(uint8_t a, uint8_t b, uint8_t c, uint8_t d) {
  return (((uint32_t)a) << 24) | (((uint32_t)b) << 16) | (((uint32_t)c) << 8) |
         ((uint32_t)d);
}

// Whitelist of PNG chunk types that we want to keep in the resulting PNG.
enum PngChunkTypes {
  kPngChunkIHDR = u32(73, 72, 68, 82),
  kPngChunkIDAT = u32(73, 68, 65, 84),
  kPngChunkIEND = u32(73, 69, 78, 68),
  kPngChunkPLTE = u32(80, 76, 84, 69),
  kPngChunktRNS = u32(116, 82, 78, 83),
  kPngChunksRGB = u32(115, 82, 71, 66),
};

static uint32_t Peek32LE(const char* data) {
  uint32_t word = ((uint32_t)data[0]) & 0x000000ff;
  word <<= 8;
  word |= ((uint32_t)data[1]) & 0x000000ff;
  word <<= 8;
  word |= ((uint32_t)data[2]) & 0x000000ff;
  word <<= 8;
  word |= ((uint32_t)data[3]) & 0x000000ff;
  return word;
}

static bool IsPngChunkWhitelisted(uint32_t type) {
  switch (type) {
    case kPngChunkIHDR:
    case kPngChunkIDAT:
    case kPngChunkIEND:
    case kPngChunkPLTE:
    case kPngChunktRNS:
    case kPngChunksRGB:
      return true;
    default:
      return false;
  }
}

PngChunkFilter::PngChunkFilter(const StringPiece& data) : data_(data) {
  if (util::StartsWith(data_, kPngSignature)) {
    window_start_ = 0;
    window_end_ = kPngSignatureSize;
  } else {
    error_msg_ = "file does not start with PNG signature";
  }
}

bool PngChunkFilter::ConsumeWindow(const void** buffer, size_t* len) {
  if (window_start_ != window_end_) {
    // We have bytes to give from our window.
    const size_t bytes_read = window_end_ - window_start_;
    *buffer = data_.data() + window_start_;
    *len = bytes_read;
    window_start_ = window_end_;
    return true;
  }
  return false;
}

bool PngChunkFilter::Next(const void** buffer, size_t* len) {
  if (HadError()) {
    return false;
  }

  // In case BackUp was called, we must consume the window.
  if (ConsumeWindow(buffer, len)) {
    return true;
  }

  // Advance the window as far as possible (until we meet a chunk that
  // we want to strip).
  while (window_end_ < data_.size()) {
    // Chunk length (4 bytes) + type (4 bytes) + crc32 (4 bytes) = 12 bytes.
    const size_t kMinChunkHeaderSize = 3 * sizeof(uint32_t);

    // Is there enough room for a chunk header?
    if (data_.size() - window_end_ < kMinChunkHeaderSize) {
      error_msg_ = StringPrintf("Not enough space for a PNG chunk @ byte %zu/%zu", window_end_,
                                data_.size());
      return false;
    }

    // Verify the chunk length.
    const uint32_t chunk_len = Peek32LE(data_.data() + window_end_);
    if ((size_t)chunk_len > data_.size() - window_end_ - kMinChunkHeaderSize) {
      // Overflow.
      const uint32_t chunk_type = Peek32LE(data_.data() + window_end_ + sizeof(uint32_t));
      error_msg_ = StringPrintf(
          "PNG chunk type %08x is too large: chunk length is %zu but chunk "
          "starts at byte %zu/%zu",
          chunk_type, (size_t)chunk_len, window_end_ + kMinChunkHeaderSize, data_.size());
      return false;
    }

    // Do we strip this chunk?
    const uint32_t chunk_type = Peek32LE(data_.data() + window_end_ + sizeof(uint32_t));
    if (IsPngChunkWhitelisted(chunk_type)) {
      // Advance the window to include this chunk.
      window_end_ += kMinChunkHeaderSize + chunk_len;

      // Special case the IEND chunk, which MUST appear last and libpng stops parsing once it hits
      // such a chunk (let's do the same).
      if (chunk_type == kPngChunkIEND) {
        // Truncate the data to the end of this chunk. There may be garbage trailing after
        // (b/38169876)
        data_ = data_.substr(0, window_end_);
        break;
      }

    } else {
      // We want to strip this chunk. If we accumulated a window,
      // we must return the window now.
      if (window_start_ != window_end_) {
        break;
      }

      // The window is empty, so we can advance past this chunk
      // and keep looking for the next good chunk,
      window_end_ += kMinChunkHeaderSize + chunk_len;
      window_start_ = window_end_;
    }
  }

  if (ConsumeWindow(buffer, len)) {
    return true;
  }
  return false;
}

void PngChunkFilter::BackUp(size_t count) {
  if (HadError()) {
    return;
  }
  window_start_ -= count;
}

bool PngChunkFilter::Rewind() {
  if (HadError()) {
    return false;
  }
  window_start_ = 0;
  window_end_ = kPngSignatureSize;
  return true;
}

}  // namespace aapt
