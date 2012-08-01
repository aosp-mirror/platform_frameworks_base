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

#ifndef ANDROID_HWUI_GRADIENT_CACHE_H
#define ANDROID_HWUI_GRADIENT_CACHE_H

#include <GLES2/gl2.h>

#include <SkShader.h>

#include <utils/Mutex.h>
#include <utils/Vector.h>

#include "Texture.h"
#include "utils/Compare.h"
#include "utils/GenerationCache.h"

namespace android {
namespace uirenderer {

struct GradientCacheEntry {
    GradientCacheEntry() {
        count = 0;
        colors = NULL;
        positions = NULL;
    }

    GradientCacheEntry(uint32_t* colors, float* positions, int count) {
        copy(colors, positions, count);
    }

    GradientCacheEntry(const GradientCacheEntry& entry) {
        copy(entry.colors, entry.positions, entry.count);
    }

    ~GradientCacheEntry() {
        delete[] colors;
        delete[] positions;
    }

    GradientCacheEntry& operator=(const GradientCacheEntry& entry) {
        if (this != &entry) {
            delete[] colors;
            delete[] positions;

            copy(entry.colors, entry.positions, entry.count);
        }

        return *this;
    }

    bool operator<(const GradientCacheEntry& r) const {
        const GradientCacheEntry& rhs = (const GradientCacheEntry&) r;
        LTE_INT(count) {
            int result = memcmp(colors, rhs.colors, count * sizeof(uint32_t));
            if (result< 0) return true;
            else if (result == 0) {
                result = memcmp(positions, rhs.positions, count * sizeof(float));
                if (result < 0) return true;
            }
        }
        return false;
    }

    uint32_t* colors;
    float* positions;
    int count;
    SkShader::TileMode tileMode;

private:

    void copy(uint32_t* colors, float* positions, int count) {
        this->count = count;
        this->colors = new uint32_t[count];
        this->positions = new float[count];

        memcpy(this->colors, colors, count * sizeof(uint32_t));
        memcpy(this->positions, positions, count * sizeof(float));
    }

}; // GradientCacheEntry

/**
 * A simple LRU gradient cache. The cache has a maximum size expressed in bytes.
 * Any texture added to the cache causing the cache to grow beyond the maximum
 * allowed size will also cause the oldest texture to be kicked out.
 */
class GradientCache: public OnEntryRemoved<GradientCacheEntry, Texture*> {
public:
    GradientCache();
    GradientCache(uint32_t maxByteSize);
    ~GradientCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(GradientCacheEntry& shader, Texture*& texture);

    /**
     * Returns the texture associated with the specified shader.
     */
    Texture* get(uint32_t* colors, float* positions, int count);

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
    /**
     * Adds a new linear gradient to the cache. The generated texture is
     * returned.
     */
    Texture* addLinearGradient(GradientCacheEntry& gradient,
            uint32_t* colors, float* positions, int count);

    void generateTexture(uint32_t* colors, float* positions, int count, Texture* texture);

    struct GradientInfo {
        uint32_t width;
        bool hasAlpha;
    };

    void getGradientInfo(const uint32_t* colors, const int count, GradientInfo& info);

    GenerationCache<GradientCacheEntry, Texture*> mCache;

    uint32_t mSize;
    uint32_t mMaxSize;

    GLint mMaxTextureSize;

    Vector<SkShader*> mGarbage;
    mutable Mutex mLock;
}; // class GradientCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_GRADIENT_CACHE_H
