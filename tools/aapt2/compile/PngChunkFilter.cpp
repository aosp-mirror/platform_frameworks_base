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
#include "io/Io.h"
#include "util/StringPiece.h"

namespace aapt {

static constexpr const char* kPngSignature = "\x89\x50\x4e\x47\x0d\x0a\x1a\x0a";

// Useful helper function that encodes individual bytes into a uint32
// at compile time.
constexpr uint32_t u32(uint8_t a, uint8_t b, uint8_t c, uint8_t d) {
    return (((uint32_t) a) << 24)
            | (((uint32_t) b) << 16)
            | (((uint32_t) c) << 8)
            | ((uint32_t) d);
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

static uint32_t peek32LE(const char* data) {
    uint32_t word = ((uint32_t) data[0]) & 0x000000ff;
    word <<= 8;
    word |= ((uint32_t) data[1]) & 0x000000ff;
    word <<= 8;
    word |= ((uint32_t) data[2]) & 0x000000ff;
    word <<= 8;
    word |= ((uint32_t) data[3]) & 0x000000ff;
    return word;
}

static bool isPngChunkWhitelisted(uint32_t type) {
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

PngChunkFilter::PngChunkFilter(const StringPiece& data) : mData(data) {
    if (util::stringStartsWith(mData, kPngSignature)) {
        mWindowStart = 0;
        mWindowEnd = strlen(kPngSignature);
    } else {
        mError = true;
    }
}

bool PngChunkFilter::consumeWindow(const void** buffer, int* len) {
    if (mWindowStart != mWindowEnd) {
        // We have bytes to give from our window.
        const int bytesRead = (int) (mWindowEnd - mWindowStart);
        *buffer = mData.data() + mWindowStart;
        *len = bytesRead;
        mWindowStart = mWindowEnd;
        return true;
    }
    return false;
}

bool PngChunkFilter::Next(const void** buffer, int* len) {
    if (mError) {
        return false;
    }

    // In case BackUp was called, we must consume the window.
    if (consumeWindow(buffer, len)) {
        return true;
    }

    // Advance the window as far as possible (until we meet a chunk that
    // we want to strip).
    while (mWindowEnd < mData.size()) {
        // Chunk length (4 bytes) + type (4 bytes) + crc32 (4 bytes) = 12 bytes.
        const size_t kMinChunkHeaderSize = 3 * sizeof(uint32_t);

        // Is there enough room for a chunk header?
        if (mData.size() - mWindowStart < kMinChunkHeaderSize) {
            mError = true;
            return false;
        }

        // Verify the chunk length.
        const uint32_t chunkLen = peek32LE(mData.data() + mWindowEnd);
        if (((uint64_t) chunkLen) + ((uint64_t) mWindowEnd) + sizeof(uint32_t) > mData.size()) {
            // Overflow.
            mError = true;
            return false;
        }

        // Do we strip this chunk?
        const uint32_t chunkType = peek32LE(mData.data() + mWindowEnd + sizeof(uint32_t));
        if (isPngChunkWhitelisted(chunkType)) {
            // Advance the window to include this chunk.
            mWindowEnd += kMinChunkHeaderSize + chunkLen;
        } else {
            // We want to strip this chunk. If we accumulated a window,
            // we must return the window now.
            if (mWindowStart != mWindowEnd) {
                break;
            }

            // The window is empty, so we can advance past this chunk
            // and keep looking for the next good chunk,
            mWindowEnd += kMinChunkHeaderSize + chunkLen;
            mWindowStart = mWindowEnd;
        }
    }

    if (consumeWindow(buffer, len)) {
        return true;
    }
    return false;
}

void PngChunkFilter::BackUp(int count) {
    if (mError) {
        return;
    }
    mWindowStart -= count;
}

bool PngChunkFilter::Skip(int count) {
    if (mError) {
        return false;
    }

    const void* buffer;
    int len;
    while (count > 0) {
        if (!Next(&buffer, &len)) {
            return false;
        }
        if (len > count) {
            BackUp(len - count);
            count = 0;
        } else {
            count -= len;
        }
    }
    return true;
}

} // namespace aapt
