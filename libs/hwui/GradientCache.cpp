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

#include <utils/JenkinsHash.h>

#include "Caches.h"
#include "Debug.h"
#include "DeviceInfo.h"
#include "GradientCache.h"
#include "Properties.h"

#include <cutils/properties.h>

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Functions
///////////////////////////////////////////////////////////////////////////////

template <typename T>
static inline T min(T a, T b) {
    return a < b ? a : b;
}

///////////////////////////////////////////////////////////////////////////////
// Cache entry
///////////////////////////////////////////////////////////////////////////////

hash_t GradientCacheEntry::hash() const {
    uint32_t hash = JenkinsHashMix(0, count);
    for (uint32_t i = 0; i < count; i++) {
        hash = JenkinsHashMix(hash, android::hash_type(colors[i]));
        hash = JenkinsHashMix(hash, android::hash_type(positions[i]));
    }
    return JenkinsHashWhiten(hash);
}

int GradientCacheEntry::compare(const GradientCacheEntry& lhs, const GradientCacheEntry& rhs) {
    int deltaInt = int(lhs.count) - int(rhs.count);
    if (deltaInt != 0) return deltaInt;

    deltaInt = memcmp(lhs.colors.get(), rhs.colors.get(), lhs.count * sizeof(uint32_t));
    if (deltaInt != 0) return deltaInt;

    return memcmp(lhs.positions.get(), rhs.positions.get(), lhs.count * sizeof(float));
}

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

GradientCache::GradientCache(const Extensions& extensions)
        : mCache(LruCache<GradientCacheEntry, Texture*>::kUnlimitedCapacity)
        , mSize(0)
        , mMaxSize(MB(1))
        , mUseFloatTexture(extensions.hasFloatTextures())
        , mHasNpot(extensions.hasNPot())
        , mHasLinearBlending(extensions.hasLinearBlending()) {
    mMaxTextureSize = DeviceInfo::get()->maxTextureSize();

    mCache.setOnEntryRemovedListener(this);
}

GradientCache::~GradientCache() {
    mCache.clear();
}

///////////////////////////////////////////////////////////////////////////////
// Size management
///////////////////////////////////////////////////////////////////////////////

uint32_t GradientCache::getSize() {
    return mSize;
}

uint32_t GradientCache::getMaxSize() {
    return mMaxSize;
}

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void GradientCache::operator()(GradientCacheEntry&, Texture*& texture) {
    if (texture) {
        mSize -= texture->objectSize();
        texture->deleteTexture();
        delete texture;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

Texture* GradientCache::get(uint32_t* colors, float* positions, int count) {
    GradientCacheEntry gradient(colors, positions, count);
    Texture* texture = mCache.get(gradient);

    if (!texture) {
        texture = addLinearGradient(gradient, colors, positions, count);
    }

    return texture;
}

void GradientCache::clear() {
    mCache.clear();
}

void GradientCache::getGradientInfo(const uint32_t* colors, const int count, GradientInfo& info) {
    uint32_t width = 256 * (count - 1);

    // If the npot extension is not supported we cannot use non-clamp
    // wrap modes. We therefore find the nearest largest power of 2
    // unless width is already a power of 2
    if (!mHasNpot && (width & (width - 1)) != 0) {
        width = 1 << (32 - __builtin_clz(width));
    }

    bool hasAlpha = false;
    for (int i = 0; i < count; i++) {
        if (((colors[i] >> 24) & 0xff) < 255) {
            hasAlpha = true;
            break;
        }
    }

    info.width = min(width, uint32_t(mMaxTextureSize));
    info.hasAlpha = hasAlpha;
}

Texture* GradientCache::addLinearGradient(GradientCacheEntry& gradient, uint32_t* colors,
                                          float* positions, int count) {
    GradientInfo info;
    getGradientInfo(colors, count, info);

    Texture* texture = new Texture(Caches::getInstance());
    texture->blend = info.hasAlpha;
    texture->generation = 1;

    // Assume the cache is always big enough
    const uint32_t size = info.width * 2 * bytesPerPixel();
    while (getSize() + size > mMaxSize) {
        LOG_ALWAYS_FATAL_IF(!mCache.removeOldest(),
                            "Ran out of things to remove from the cache? getSize() = %" PRIu32
                            ", size = %" PRIu32 ", mMaxSize = %" PRIu32 ", width = %" PRIu32,
                            getSize(), size, mMaxSize, info.width);
    }

    generateTexture(colors, positions, info.width, 2, texture);

    mSize += size;
    LOG_ALWAYS_FATAL_IF((int)size != texture->objectSize(),
                        "size != texture->objectSize(), size %" PRIu32
                        ", objectSize %d"
                        " width = %" PRIu32 " bytesPerPixel() = %zu",
                        size, texture->objectSize(), info.width, bytesPerPixel());
    mCache.put(gradient, texture);

    return texture;
}

size_t GradientCache::bytesPerPixel() const {
    // We use 4 channels (RGBA)
    return 4 * (mUseFloatTexture ? /* fp16 */ 2 : sizeof(uint8_t));
}

size_t GradientCache::sourceBytesPerPixel() const {
    // We use 4 channels (RGBA) and upload from floats (not half floats)
    return 4 * (mUseFloatTexture ? sizeof(float) : sizeof(uint8_t));
}

void GradientCache::mixBytes(const FloatColor& start, const FloatColor& end, float amount,
                             uint8_t*& dst) const {
    float oppAmount = 1.0f - amount;
    float a = start.a * oppAmount + end.a * amount;
    *dst++ = uint8_t(OECF(start.r * oppAmount + end.r * amount) * 255.0f);
    *dst++ = uint8_t(OECF(start.g * oppAmount + end.g * amount) * 255.0f);
    *dst++ = uint8_t(OECF(start.b * oppAmount + end.b * amount) * 255.0f);
    *dst++ = uint8_t(a * 255.0f);
}

void GradientCache::mixFloats(const FloatColor& start, const FloatColor& end, float amount,
                              uint8_t*& dst) const {
    float oppAmount = 1.0f - amount;
    float a = start.a * oppAmount + end.a * amount;
    float* d = (float*)dst;
#ifdef ANDROID_ENABLE_LINEAR_BLENDING
    // We want to stay linear
    *d++ = (start.r * oppAmount + end.r * amount);
    *d++ = (start.g * oppAmount + end.g * amount);
    *d++ = (start.b * oppAmount + end.b * amount);
#else
    *d++ = OECF(start.r * oppAmount + end.r * amount);
    *d++ = OECF(start.g * oppAmount + end.g * amount);
    *d++ = OECF(start.b * oppAmount + end.b * amount);
#endif
    *d++ = a;
    dst += 4 * sizeof(float);
}

void GradientCache::generateTexture(uint32_t* colors, float* positions, const uint32_t width,
                                    const uint32_t height, Texture* texture) {
    const GLsizei rowBytes = width * sourceBytesPerPixel();
    uint8_t pixels[rowBytes * height];

    static ChannelMixer gMixers[] = {
            // colors are stored gamma-encoded
            &android::uirenderer::GradientCache::mixBytes,
            // colors are stored in linear (linear blending on)
            // or gamma-encoded (linear blending off)
            &android::uirenderer::GradientCache::mixFloats,
    };
    ChannelMixer mix = gMixers[mUseFloatTexture];

    FloatColor start;
    start.set(colors[0]);

    FloatColor end;
    end.set(colors[1]);

    int currentPos = 1;
    float startPos = positions[0];
    float distance = positions[1] - startPos;

    uint8_t* dst = pixels;
    for (uint32_t x = 0; x < width; x++) {
        float pos = x / float(width - 1);
        if (pos > positions[currentPos]) {
            start = end;
            startPos = positions[currentPos];

            currentPos++;

            end.set(colors[currentPos]);
            distance = positions[currentPos] - startPos;
        }

        float amount = (pos - startPos) / distance;
        (this->*mix)(start, end, amount, dst);
    }

    memcpy(pixels + rowBytes, pixels, rowBytes);

    if (mUseFloatTexture) {
        texture->upload(GL_RGBA16F, width, height, GL_RGBA, GL_FLOAT, pixels);
    } else {
        GLint internalFormat = mHasLinearBlending ? GL_SRGB8_ALPHA8 : GL_RGBA;
        texture->upload(internalFormat, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
    }

    texture->setFilter(GL_LINEAR);
    texture->setWrap(GL_CLAMP_TO_EDGE);
}

};  // namespace uirenderer
};  // namespace android
