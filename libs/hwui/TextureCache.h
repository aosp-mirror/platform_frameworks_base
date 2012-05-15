/*
 * Copyright (C) 2010 The Android Open Source Project
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

#ifndef ANDROID_HWUI_TEXTURE_CACHE_H
#define ANDROID_HWUI_TEXTURE_CACHE_H

#include <SkBitmap.h>

#include <utils/Mutex.h>
#include <utils/Vector.h>

#include "Debug.h"
#include "Texture.h"
#include "utils/GenerationCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_TEXTURES
    #define TEXTURE_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define TEXTURE_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Classes
///////////////////////////////////////////////////////////////////////////////

/**
 * A simple LRU texture cache. The cache has a maximum size expressed in bytes.
 * Any texture added to the cache causing the cache to grow beyond the maximum
 * allowed size will also cause the oldest texture to be kicked out.
 */
class TextureCache: public OnEntryRemoved<SkBitmap*, Texture*> {
public:
    TextureCache();
    TextureCache(uint32_t maxByteSize);
    ~TextureCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(SkBitmap*& bitmap, Texture*& texture);

    /**
     * Returns the texture associated with the specified bitmap. If the texture
     * cannot be found in the cache, a new texture is generated.
     */
    Texture* get(SkBitmap* bitmap);
    /**
     * Returns the texture associated with the specified bitmap. The generated
     * texture is not kept in the cache. The caller must destroy the texture.
     */
    Texture* getTransient(SkBitmap* bitmap);
    /**
     * Removes the texture associated with the specified bitmap.
     * Upon remove the texture is freed.
     */
    void remove(SkBitmap* bitmap);
    /**
     * Removes the texture associated with the specified bitmap. This is meant
     * to be called from threads that are not the EGL context thread.
     */
    void removeDeferred(SkBitmap* bitmap);
    /**
     * Process deferred removals.
     */
    void clearGarbage();

    /**
     * Clears the cache. This causes all textures to be deleted.
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

    /**
     * Partially flushes the cache. The amount of memory freed by a flush
     * is defined by the flush rate.
     */
    void flush();
    /**
     * Indicates the percentage of the cache to retain when a
     * memory trim is requested (see Caches::flush).
     */
    void setFlushRate(float flushRate);

private:
    /**
     * Generates the texture from a bitmap into the specified texture structure.
     *
     * @param regenerate If true, the bitmap data is reuploaded into the texture, but
     *        no new texture is generated.
     */
    void generateTexture(SkBitmap* bitmap, Texture* texture, bool regenerate = false);

    void uploadLoFiTexture(bool resize, SkBitmap* bitmap, uint32_t width, uint32_t height);
    void uploadToTexture(bool resize, GLenum format, GLsizei width, GLsizei height,
            GLenum type, const GLvoid * data);

    void init();

    GenerationCache<SkBitmap*, Texture*> mCache;

    uint32_t mSize;
    uint32_t mMaxSize;
    GLint mMaxTextureSize;

    float mFlushRate;

    bool mDebugEnabled;

    Vector<SkBitmap*> mGarbage;
    mutable Mutex mLock;
}; // class TextureCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_TEXTURE_CACHE_H
