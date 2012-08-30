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

#define LOG_TAG "OpenGLRenderer"

#include <utils/threads.h>

#include "Caches.h"
#include "Debug.h"
#include "GradientCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

#define GRADIENT_TEXTURE_HEIGHT 2
#define GRADIENT_BYTES_PER_PIXEL 4

///////////////////////////////////////////////////////////////////////////////
// Functions
///////////////////////////////////////////////////////////////////////////////

template<typename T>
static inline T min(T a, T b) {
    return a < b ? a : b;
}

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

GradientCache::GradientCache():
        mCache(GenerationCache<GradientCacheEntry, Texture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_GRADIENT_CACHE_SIZE)) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_GRADIENT_CACHE_SIZE, property, NULL) > 0) {
        INIT_LOGD("  Setting gradient cache size to %sMB", property);
        setMaxSize(MB(atof(property)));
    } else {
        INIT_LOGD("  Using default gradient cache size of %.2fMB", DEFAULT_GRADIENT_CACHE_SIZE);
    }

    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &mMaxTextureSize);

    mCache.setOnEntryRemovedListener(this);
}

GradientCache::GradientCache(uint32_t maxByteSize):
        mCache(GenerationCache<GradientCacheEntry, Texture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(maxByteSize) {
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

void GradientCache::setMaxSize(uint32_t maxSize) {
    mMaxSize = maxSize;
    while (mSize > mMaxSize) {
        mCache.removeOldest();
    }
}

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void GradientCache::operator()(GradientCacheEntry& shader, Texture*& texture) {
    if (texture) {
        const uint32_t size = texture->width * texture->height * GRADIENT_BYTES_PER_PIXEL;
        mSize -= size;
    }

    if (texture) {
        glDeleteTextures(1, &texture->id);
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

void GradientCache::getGradientInfo(const uint32_t* colors, const int count,
        GradientInfo& info) {
    uint32_t width = 256 * (count - 1);

    if (!Caches::getInstance().extensions.hasNPot()) {
        width = 1 << (31 - __builtin_clz(width));
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

Texture* GradientCache::addLinearGradient(GradientCacheEntry& gradient,
        uint32_t* colors, float* positions, int count) {

    GradientInfo info;
    getGradientInfo(colors, count, info);

    Texture* texture = new Texture;
    texture->width = info.width;
    texture->height = GRADIENT_TEXTURE_HEIGHT;
    texture->blend = info.hasAlpha;
    texture->generation = 1;

    // Asume the cache is always big enough
    const uint32_t size = texture->width * texture->height * GRADIENT_BYTES_PER_PIXEL;
    while (mSize + size > mMaxSize) {
        mCache.removeOldest();
    }

    generateTexture(colors, positions, count, texture);

    mSize += size;
    mCache.put(gradient, texture);

    return texture;
}

void GradientCache::generateTexture(uint32_t* colors, float* positions,
        int count, Texture* texture) {

    const uint32_t width = texture->width;
    const GLsizei rowBytes = width * GRADIENT_BYTES_PER_PIXEL;
    uint32_t pixels[width * texture->height];

    int currentPos = 1;

    float startA = (colors[0] >> 24) & 0xff;
    float startR = (colors[0] >> 16) & 0xff;
    float startG = (colors[0] >>  8) & 0xff;
    float startB = (colors[0] >>  0) & 0xff;

    float endA = (colors[1] >> 24) & 0xff;
    float endR = (colors[1] >> 16) & 0xff;
    float endG = (colors[1] >>  8) & 0xff;
    float endB = (colors[1] >>  0) & 0xff;

    float start = positions[0];
    float distance = positions[1] - start;

    uint8_t* p = (uint8_t*) pixels;
    for (uint32_t x = 0; x < width; x++) {
        float pos = x / float(width - 1);
        if (pos > positions[currentPos]) {
            startA = endA;
            startR = endR;
            startG = endG;
            startB = endB;
            start = positions[currentPos];

            currentPos++;

            endA = (colors[currentPos] >> 24) & 0xff;
            endR = (colors[currentPos] >> 16) & 0xff;
            endG = (colors[currentPos] >>  8) & 0xff;
            endB = (colors[currentPos] >>  0) & 0xff;
            distance = positions[currentPos] - start;
        }

        float amount = (pos - start) / distance;
        float oppAmount = 1.0f - amount;

        const float alpha = startA * oppAmount + endA * amount;
        const float a = alpha / 255.0f;
        *p++ = uint8_t(a * (startR * oppAmount + endR * amount));
        *p++ = uint8_t(a * (startG * oppAmount + endG * amount));
        *p++ = uint8_t(a * (startB * oppAmount + endB * amount));
        *p++ = uint8_t(alpha);
    }

    for (int i = 1; i < GRADIENT_TEXTURE_HEIGHT; i++) {
        memcpy(pixels + width * i, pixels, rowBytes);
    }

    glGenTextures(1, &texture->id);

    glBindTexture(GL_TEXTURE_2D, texture->id);
    glPixelStorei(GL_UNPACK_ALIGNMENT, GRADIENT_BYTES_PER_PIXEL);

    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, texture->height, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, pixels);

    texture->setFilter(GL_LINEAR);
    texture->setWrap(GL_CLAMP_TO_EDGE);
}

}; // namespace uirenderer
}; // namespace android
