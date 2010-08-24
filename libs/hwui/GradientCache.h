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

#ifndef ANDROID_UI_GRADIENT_CACHE_H
#define ANDROID_UI_GRADIENT_CACHE_H

#include <SkShader.h>

#include "Texture.h"
#include "GenerationCache.h"

namespace android {
namespace uirenderer {

/**
 * A simple LRU gradient cache. The cache has a maximum size expressed in bytes.
 * Any texture added to the cache causing the cache to grow beyond the maximum
 * allowed size will also cause the oldest texture to be kicked out.
 */
class GradientCache: public OnEntryRemoved<SkShader*, Texture*> {
public:
    GradientCache();
    GradientCache(uint32_t maxByteSize);
    ~GradientCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(SkShader*& shader, Texture*& texture);

    /**
     * Adds a new linear gradient to the cache. The generated texture is
     * returned.
     */
    Texture* addLinearGradient(SkShader* shader, float* bounds, uint32_t* colors,
            float* positions, int count, SkShader::TileMode tileMode);
    /**
     * Returns the texture associated with the specified shader.
     */
    Texture* get(SkShader* shader);
    /**
     * Removes the texture associated with the specified shader. Returns NULL
     * if the texture cannot be found. Upon remove the texture is freed.
     */
    void remove(SkShader* shader);
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

private:
    void generateTexture(SkBitmap* bitmap, Texture* texture);

    GenerationCache<SkShader*, Texture*> mCache;

    uint32_t mSize;
    uint32_t mMaxSize;
}; // class GradientCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_UI_GRADIENT_CACHE_H
