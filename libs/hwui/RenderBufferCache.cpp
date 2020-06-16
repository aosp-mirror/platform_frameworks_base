/*
 * Copyright (C) 2013 The Android Open Source Project
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

#include "Debug.h"
#include "Properties.h"
#include "RenderBufferCache.h"

#include <utils/Log.h>

#include <cstdlib>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_RENDER_BUFFERS
    #define RENDER_BUFFER_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define RENDER_BUFFER_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

RenderBufferCache::RenderBufferCache()
        : mSize(0)
        , mMaxSize(Properties::renderBufferCacheSize) {}

RenderBufferCache::~RenderBufferCache() {
    clear();
}

///////////////////////////////////////////////////////////////////////////////
// Size management
///////////////////////////////////////////////////////////////////////////////

uint32_t RenderBufferCache::getSize() {
    return mSize;
}

uint32_t RenderBufferCache::getMaxSize() {
    return mMaxSize;
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

int RenderBufferCache::RenderBufferEntry::compare(
        const RenderBufferCache::RenderBufferEntry& lhs,
        const RenderBufferCache::RenderBufferEntry& rhs) {
    int deltaInt = int(lhs.mWidth) - int(rhs.mWidth);
    if (deltaInt != 0) return deltaInt;

    deltaInt = int(lhs.mHeight) - int(rhs.mHeight);
    if (deltaInt != 0) return deltaInt;

    return int(lhs.mFormat) - int(rhs.mFormat);
}

void RenderBufferCache::deleteBuffer(RenderBuffer* buffer) {
    if (buffer) {
        RENDER_BUFFER_LOGD("Deleted %s render buffer (%dx%d)",
                RenderBuffer::formatName(buffer->getFormat()),
                buffer->getWidth(), buffer->getHeight());

        mSize -= buffer->getSize();
        delete buffer;
    }
}

void RenderBufferCache::clear() {
    for (auto entry : mCache) {
        deleteBuffer(entry.mBuffer);
    }
    mCache.clear();
}

RenderBuffer* RenderBufferCache::get(GLenum format, const uint32_t width, const uint32_t height) {
    RenderBuffer* buffer = nullptr;

    RenderBufferEntry entry(format, width, height);
    auto iter = mCache.find(entry);

    if (iter != mCache.end()) {
        entry = *iter;
        mCache.erase(iter);

        buffer = entry.mBuffer;
        mSize -= buffer->getSize();

        RENDER_BUFFER_LOGD("Found %s render buffer (%dx%d)",
                RenderBuffer::formatName(format), width, height);
    } else {
        buffer = new RenderBuffer(format, width, height);

        RENDER_BUFFER_LOGD("Created new %s render buffer (%dx%d)",
                RenderBuffer::formatName(format), width, height);
    }

    buffer->bind();
    buffer->allocate();

    return buffer;
}

bool RenderBufferCache::put(RenderBuffer* buffer) {
    if (!buffer) return false;

    const uint32_t size = buffer->getSize();
    if (size < mMaxSize) {
        while (mSize + size > mMaxSize) {
            RenderBuffer* victim = mCache.begin()->mBuffer;
            deleteBuffer(victim);
            mCache.erase(mCache.begin());
        }

        RenderBufferEntry entry(buffer);

        mCache.insert(entry);
        mSize += size;

        RENDER_BUFFER_LOGD("Added %s render buffer (%dx%d)",
                RenderBuffer::formatName(buffer->getFormat()),
                buffer->getWidth(), buffer->getHeight());

        return true;
    } else {
        RENDER_BUFFER_LOGD("Deleted %s render buffer (%dx%d) Size=%d, MaxSize=%d",
        RenderBuffer::formatName(buffer->getFormat()),
                 buffer->getWidth(), buffer->getHeight(), size, mMaxSize);
        delete buffer;
    }
    return false;
}

}; // namespace uirenderer
}; // namespace android
