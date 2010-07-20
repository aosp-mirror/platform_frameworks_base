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

#include <GLES2/gl2.h>

#include <SkCanvas.h>
#include <SkGradientShader.h>

#include "GradientCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

GradientCache::GradientCache(uint32_t maxByteSize):
        mCache(GenerationCache<SkShader*, Texture*>::kUnlimitedCapacity),
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

void GradientCache::operator()(SkShader*& shader, Texture*& texture) {
    if (shader) {
        const uint32_t size = texture->width * texture->height * 4;
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

Texture* GradientCache::get(SkShader* shader) {
    Texture* texture = mCache.get(shader);
    return texture;
}

void GradientCache::remove(SkShader* shader) {
    mCache.remove(shader);
}

void GradientCache::clear() {
    mCache.clear();
}

Texture* GradientCache::addLinearGradient(SkShader* shader, float* bounds, uint32_t* colors,
        float* positions, int count, SkShader::TileMode tileMode) {
    SkBitmap bitmap;
    bitmap.setConfig(SkBitmap::kARGB_8888_Config, 1024, 1);
    bitmap.allocPixels();
    bitmap.eraseColor(0);

    SkCanvas canvas(bitmap);

    SkPoint points[2];
    points[0].set(0.0f, 0.0f);
    points[1].set(bitmap.width(), 0.0f);

    SkShader* localShader = SkGradientShader::CreateLinear(points,
            reinterpret_cast<const SkColor*>(colors), positions, count, tileMode);

    SkPaint p;
    p.setStyle(SkPaint::kStrokeAndFill_Style);
    p.setShader(localShader)->unref();

    canvas.drawRectCoords(0.0f, 0.0f, bitmap.width(), 1.0f, p);

    // Asume the cache is always big enough
    const uint32_t size = bitmap.rowBytes() * bitmap.height();
    while (mSize + size > mMaxSize) {
        mCache.removeOldest();
    }

    Texture* texture = new Texture;
    generateTexture(&bitmap, texture);

    mSize += size;
    mCache.put(shader, texture);

    return texture;
}

void GradientCache::generateTexture(SkBitmap* bitmap, Texture* texture) {
    SkAutoLockPixels autoLock(*bitmap);
    if (!bitmap->readyToDraw()) {
        LOGE("Cannot generate texture from shader");
        return;
    }

    texture->generation = bitmap->getGenerationID();
    texture->width = bitmap->width();
    texture->height = bitmap->height();

    glGenTextures(1, &texture->id);

    glBindTexture(GL_TEXTURE_2D, texture->id);
    glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());

    texture->blend = !bitmap->isOpaque();
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bitmap->rowBytesAsPixels(), texture->height, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, bitmap->getPixels());

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

}; // namespace uirenderer
}; // namespace android
