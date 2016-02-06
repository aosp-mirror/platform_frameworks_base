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

#include <memory>

#include <GLES3/gl3.h>

#include <SkShader.h>

#include <utils/LruCache.h>
#include <utils/Mutex.h>

namespace android {
namespace uirenderer {

class Texture;

struct GradientCacheEntry {
    GradientCacheEntry() {
        count = 0;
        colors = nullptr;
        positions = nullptr;
    }

    GradientCacheEntry(uint32_t* colors, float* positions, uint32_t count) {
        copy(colors, positions, count);
    }

    GradientCacheEntry(const GradientCacheEntry& entry) {
        copy(entry.colors.get(), entry.positions.get(), entry.count);
    }

    GradientCacheEntry& operator=(const GradientCacheEntry& entry) {
        if (this != &entry) {
            copy(entry.colors.get(), entry.positions.get(), entry.count);
        }

        return *this;
    }

    hash_t hash() const;

    static int compare(const GradientCacheEntry& lhs, const GradientCacheEntry& rhs);

    bool operator==(const GradientCacheEntry& other) const {
        return compare(*this, other) == 0;
    }

    bool operator!=(const GradientCacheEntry& other) const {
        return compare(*this, other) != 0;
    }

    std::unique_ptr<uint32_t[]> colors;
    std::unique_ptr<float[]> positions;
    uint32_t count;

private:
    void copy(uint32_t* colors, float* positions, uint32_t count) {
        this->count = count;
        this->colors.reset(new uint32_t[count]);
        this->positions.reset(new float[count]);

        memcpy(this->colors.get(), colors, count * sizeof(uint32_t));
        memcpy(this->positions.get(), positions, count * sizeof(float));
    }

}; // GradientCacheEntry

// Caching support

inline int strictly_order_type(const GradientCacheEntry& lhs, const GradientCacheEntry& rhs) {
    return GradientCacheEntry::compare(lhs, rhs) < 0;
}

inline int compare_type(const GradientCacheEntry& lhs, const GradientCacheEntry& rhs) {
    return GradientCacheEntry::compare(lhs, rhs);
}

inline hash_t hash_type(const GradientCacheEntry& entry) {
    return entry.hash();
}

/**
 * A simple LRU gradient cache. The cache has a maximum size expressed in bytes.
 * Any texture added to the cache causing the cache to grow beyond the maximum
 * allowed size will also cause the oldest texture to be kicked out.
 */
class GradientCache: public OnEntryRemoved<GradientCacheEntry, Texture*> {
public:
    GradientCache(Extensions& extensions);
    ~GradientCache();

    /**
     * Used as a callback when an entry is removed from the cache.
     * Do not invoke directly.
     */
    void operator()(GradientCacheEntry& shader, Texture*& texture) override;

    /**
     * Returns the texture associated with the specified shader.
     */
    Texture* get(uint32_t* colors, float* positions, int count);

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

private:
    /**
     * Adds a new linear gradient to the cache. The generated texture is
     * returned.
     */
    Texture* addLinearGradient(GradientCacheEntry& gradient,
            uint32_t* colors, float* positions, int count);

    void generateTexture(uint32_t* colors, float* positions,
            const uint32_t width, const uint32_t height, Texture* texture);

    struct GradientInfo {
        uint32_t width;
        bool hasAlpha;
    };

    void getGradientInfo(const uint32_t* colors, const int count, GradientInfo& info);

    size_t bytesPerPixel() const;

    struct GradientColor {
        float r;
        float g;
        float b;
        float a;
    };

    typedef void (GradientCache::*ChannelSplitter)(uint32_t inColor,
            GradientColor& outColor) const;

    void splitToBytes(uint32_t inColor, GradientColor& outColor) const;
    void splitToFloats(uint32_t inColor, GradientColor& outColor) const;

    typedef void (GradientCache::*ChannelMixer)(GradientColor& start, GradientColor& end,
            float amount, uint8_t*& dst) const;

    void mixBytes(GradientColor& start, GradientColor& end, float amount, uint8_t*& dst) const;
    void mixFloats(GradientColor& start, GradientColor& end, float amount, uint8_t*& dst) const;

    LruCache<GradientCacheEntry, Texture*> mCache;

    uint32_t mSize;
    const uint32_t mMaxSize;

    GLint mMaxTextureSize;
    bool mUseFloatTexture;
    bool mHasNpot;

    mutable Mutex mLock;
}; // class GradientCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_GRADIENT_CACHE_H
