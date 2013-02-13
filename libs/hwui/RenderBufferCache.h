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

#ifndef ANDROID_HWUI_RENDER_BUFFER_CACHE_H
#define ANDROID_HWUI_RENDER_BUFFER_CACHE_H

#include <GLES2/gl2.h>

#include "RenderBuffer.h"
#include "utils/SortedList.h"

namespace android {
namespace uirenderer {

class RenderBufferCache {
public:
    RenderBufferCache();
    ~RenderBufferCache();

    /**
     * Returns a buffer with the exact specified dimensions. If no suitable
     * buffer can be found, a new one is created and returned. If creating a
     * new buffer fails, NULL is returned.
     *
     * When a buffer is obtained from the cache, it is removed and the total
     * size of the cache goes down.
     *
     * The returned buffer is always allocated and bound
     * (see RenderBuffer::isAllocated()).
     *
     * @param format The desired render buffer format
     * @param width The desired width of the buffer
     * @param height The desired height of the buffer
     */
    RenderBuffer* get(GLenum format, const uint32_t width, const uint32_t height);

    /**
     * Adds the buffer to the cache. The buffer will not be added if there is
     * not enough space available. Adding a buffer can cause other buffer to
     * be removed from the cache.
     *
     * @param buffer The render buffer to add to the cache
     *
     * @return True if the buffer was added, false otherwise.
     */
    bool put(RenderBuffer* buffer);
    /**
     * Clears the cache. This causes all layers to be deleted.
     */
    void clear();

    /**
     * Sets the maximum size of the cache in bytes.
     */
    void setMaxSize(uint32_t maxSize);
    /**
     * Returns the maximum size of the cache in bytes.
     */
    uint32_t getMaxSize();
    /**
     * Returns the current size of the cache in bytes.
     */
    uint32_t getSize();

private:
    struct RenderBufferEntry {
        RenderBufferEntry():
            mBuffer(NULL), mWidth(0), mHeight(0) {
        }

        RenderBufferEntry(GLenum format, const uint32_t width, const uint32_t height):
            mBuffer(NULL), mFormat(format), mWidth(width), mHeight(height) {
        }

        RenderBufferEntry(RenderBuffer* buffer):
            mBuffer(buffer), mFormat(buffer->getFormat()),
            mWidth(buffer->getWidth()), mHeight(buffer->getHeight()) {
        }

        static int compare(const RenderBufferEntry& lhs, const RenderBufferEntry& rhs);

        bool operator==(const RenderBufferEntry& other) const {
            return compare(*this, other) == 0;
        }

        bool operator!=(const RenderBufferEntry& other) const {
            return compare(*this, other) != 0;
        }

        friend inline int strictly_order_type(const RenderBufferEntry& lhs,
                const RenderBufferEntry& rhs) {
            return RenderBufferEntry::compare(lhs, rhs) < 0;
        }

        friend inline int compare_type(const RenderBufferEntry& lhs,
                const RenderBufferEntry& rhs) {
            return RenderBufferEntry::compare(lhs, rhs);
        }

        RenderBuffer* mBuffer;
        GLenum mFormat;
        uint32_t mWidth;
        uint32_t mHeight;
    }; // struct RenderBufferEntry

    void deleteBuffer(RenderBuffer* buffer);

    SortedList<RenderBufferEntry> mCache;

    uint32_t mSize;
    uint32_t mMaxSize;
}; // class RenderBufferCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_RENDER_BUFFER_CACHE_H
