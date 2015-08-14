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

#ifndef AAPT_RES_CHUNK_PULL_PARSER_H
#define AAPT_RES_CHUNK_PULL_PARSER_H

#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <string>

namespace aapt {

/**
 * A pull parser, modeled after XmlPullParser, that reads
 * android::ResChunk_header structs from a block of data.
 *
 * An android::ResChunk_header specifies a type, headerSize,
 * and size. The pull parser will verify that the chunk's size
 * doesn't extend beyond the available data, and will iterate
 * over each chunk in the given block of data.
 *
 * Processing nested chunks is done by creating a new ResChunkPullParser
 * pointing to the data portion of a chunk.
 */
class ResChunkPullParser {
public:
    enum class Event {
        StartDocument,
        EndDocument,
        BadDocument,

        Chunk,
    };

    /**
     * Returns false if the event is EndDocument or BadDocument.
     */
    static bool isGoodEvent(Event event);

    /**
     * Create a ResChunkPullParser to read android::ResChunk_headers
     * from the memory pointed to by data, of len bytes.
     */
    ResChunkPullParser(const void* data, size_t len);

    ResChunkPullParser(const ResChunkPullParser&) = delete;

    Event getEvent() const;
    const std::string& getLastError() const;
    const android::ResChunk_header* getChunk() const;

    /**
     * Move to the next android::ResChunk_header.
     */
    Event next();

private:
    Event mEvent;
    const android::ResChunk_header* mData;
    size_t mLen;
    const android::ResChunk_header* mCurrentChunk;
    std::string mLastError;
};

template <typename T>
inline static const T* convertTo(const android::ResChunk_header* chunk) {
    if (util::deviceToHost16(chunk->headerSize) < sizeof(T)) {
        return nullptr;
    }
    return reinterpret_cast<const T*>(chunk);
}

inline static const uint8_t* getChunkData(const android::ResChunk_header* chunk) {
    return reinterpret_cast<const uint8_t*>(chunk) + util::deviceToHost16(chunk->headerSize);
}

inline static uint32_t getChunkDataLen(const android::ResChunk_header* chunk) {
    return util::deviceToHost32(chunk->size) - util::deviceToHost16(chunk->headerSize);
}

//
// Implementation
//

inline bool ResChunkPullParser::isGoodEvent(ResChunkPullParser::Event event) {
    return event != Event::EndDocument && event != Event::BadDocument;
}

inline ResChunkPullParser::ResChunkPullParser(const void* data, size_t len) :
        mEvent(Event::StartDocument),
        mData(reinterpret_cast<const android::ResChunk_header*>(data)),
        mLen(len),
        mCurrentChunk(nullptr) {
}

inline ResChunkPullParser::Event ResChunkPullParser::getEvent() const {
    return mEvent;
}

inline const std::string& ResChunkPullParser::getLastError() const {
    return mLastError;
}

inline const android::ResChunk_header* ResChunkPullParser::getChunk() const {
    return mCurrentChunk;
}

} // namespace aapt

#endif // AAPT_RES_CHUNK_PULL_PARSER_H
