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

#include "unflatten/ResChunkPullParser.h"
#include "util/Util.h"

#include <androidfw/ResourceTypes.h>
#include <cstddef>

namespace aapt {

using android::ResChunk_header;

ResChunkPullParser::Event ResChunkPullParser::next() {
    if (!isGoodEvent(mEvent)) {
        return mEvent;
    }

    if (mEvent == Event::StartDocument) {
        mCurrentChunk = mData;
    } else {
        mCurrentChunk = (const ResChunk_header*)
                (((const char*) mCurrentChunk) + util::deviceToHost32(mCurrentChunk->size));
    }

    const std::ptrdiff_t diff = (const char*) mCurrentChunk - (const char*) mData;
    assert(diff >= 0 && "diff is negative");
    const size_t offset = static_cast<const size_t>(diff);

    if (offset == mLen) {
        mCurrentChunk = nullptr;
        return (mEvent = Event::EndDocument);
    } else if (offset + sizeof(ResChunk_header) > mLen) {
        mLastError = "chunk is past the end of the document";
        mCurrentChunk = nullptr;
        return (mEvent = Event::BadDocument);
    }

    if (util::deviceToHost16(mCurrentChunk->headerSize) < sizeof(ResChunk_header)) {
        mLastError = "chunk has too small header";
        mCurrentChunk = nullptr;
        return (mEvent = Event::BadDocument);
    } else if (util::deviceToHost32(mCurrentChunk->size) <
            util::deviceToHost16(mCurrentChunk->headerSize)) {
        mLastError = "chunk's total size is smaller than header";
        mCurrentChunk = nullptr;
        return (mEvent = Event::BadDocument);
    } else if (offset + util::deviceToHost32(mCurrentChunk->size) > mLen) {
        mLastError = "chunk's data extends past the end of the document";
        mCurrentChunk = nullptr;
        return (mEvent = Event::BadDocument);
    }
    return (mEvent = Event::Chunk);
}

} // namespace aapt
