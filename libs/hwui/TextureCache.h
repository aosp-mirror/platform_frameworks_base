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

#include <cutils/compiler.h>

#include <utils/LruCache.h>
#include <utils/Mutex.h>

#include "Debug.h"

#include <unordered_map>
#include <vector>

namespace android {

class Bitmap;

namespace uirenderer {

class Texture;

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
class TextureCache : public OnEntryRemoved<uint32_t, Texture*> {
public:
    TextureCache();
    ~TextureCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(uint32_t&, Texture*& texture) override;

    /**
     * Resets all Textures to not be marked as in use
     */
    void resetMarkInUse(void* ownerToken);

    /**
     * Attempts to precache the SkBitmap. Returns true if a Texture was successfully
     * acquired for the bitmap, false otherwise. If a Texture was acquired it is
     * marked as in use.
     */
    bool prefetchAndMarkInUse(void* ownerToken, Bitmap* bitmap);

    /**
     * Attempts to precache the SkBitmap. Returns true if a Texture was successfully
     * acquired for the bitmap, false otherwise. Does not mark the Texture
     * as in use and won't update currently in-use Textures.
     */
    bool prefetch(Bitmap* bitmap);

    /**
     * Returns the texture associated with the specified bitmap from within the cache.
     * If the texture cannot be found in the cache, a new texture is generated.
     */
    Texture* get(Bitmap* bitmap);

    /**
     * Removes the texture associated with the specified pixelRef. Must be called from RenderThread
     * Returns true if a texture was destroyed, false if no texture with that id was found
     */
    bool destroyTexture(uint32_t pixelRefStableID);

    /**
     * Clears the cache. This causes all textures to be deleted.
     */
    void clear();

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

private:
    bool canMakeTextureFromBitmap(Bitmap* bitmap);

    Texture* getCachedTexture(Bitmap* bitmap);
    Texture* createTexture(Bitmap* bitmap);

    LruCache<uint32_t, Texture*> mCache;

    uint32_t mSize;
    const uint32_t mMaxSize;
    GLint mMaxTextureSize;

    const float mFlushRate;

    bool mDebugEnabled;

    std::unordered_map<uint32_t, std::unique_ptr<Texture>> mHardwareTextures;
};  // class TextureCache

};  // namespace uirenderer
};  // namespace android

#endif  // ANDROID_HWUI_TEXTURE_CACHE_H
