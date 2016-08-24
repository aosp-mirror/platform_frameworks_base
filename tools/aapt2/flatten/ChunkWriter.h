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

#ifndef AAPT_FLATTEN_CHUNKWRITER_H
#define AAPT_FLATTEN_CHUNKWRITER_H

#include "util/BigBuffer.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>

namespace aapt {

class ChunkWriter {
private:
    BigBuffer* mBuffer;
    size_t mStartSize = 0;
    android::ResChunk_header* mHeader = nullptr;

public:
    explicit inline ChunkWriter(BigBuffer* buffer) : mBuffer(buffer) {
    }

    ChunkWriter(const ChunkWriter&) = delete;
    ChunkWriter& operator=(const ChunkWriter&) = delete;
    ChunkWriter(ChunkWriter&&) = default;
    ChunkWriter& operator=(ChunkWriter&&) = default;

    template <typename T>
    inline T* startChunk(uint16_t type) {
        mStartSize = mBuffer->size();
        T* chunk = mBuffer->nextBlock<T>();
        mHeader = &chunk->header;
        mHeader->type = util::hostToDevice16(type);
        mHeader->headerSize = util::hostToDevice16(sizeof(T));
        return chunk;
    }

    template <typename T>
    inline T* nextBlock(size_t count = 1) {
        return mBuffer->nextBlock<T>(count);
    }

    inline BigBuffer* getBuffer() {
        return mBuffer;
    }

    inline android::ResChunk_header* getChunkHeader() {
        return mHeader;
    }

    inline size_t size() {
        return mBuffer->size() - mStartSize;
    }

    inline android::ResChunk_header* finish() {
        mBuffer->align4();
        mHeader->size = util::hostToDevice32(mBuffer->size() - mStartSize);
        return mHeader;
    }
};

template <>
inline android::ResChunk_header* ChunkWriter::startChunk(uint16_t type) {
    mStartSize = mBuffer->size();
    mHeader = mBuffer->nextBlock<android::ResChunk_header>();
    mHeader->type = util::hostToDevice16(type);
    mHeader->headerSize = util::hostToDevice16(sizeof(android::ResChunk_header));
    return mHeader;
}

} // namespace aapt

#endif /* AAPT_FLATTEN_CHUNKWRITER_H */
