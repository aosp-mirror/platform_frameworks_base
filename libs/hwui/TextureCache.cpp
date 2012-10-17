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

#include <utils/threads.h>

#include "Caches.h"
#include "TextureCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

TextureCache::TextureCache():
        mCache(GenerationCache<SkBitmap*, Texture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_TEXTURE_CACHE_SIZE)),
        mFlushRate(DEFAULT_TEXTURE_CACHE_FLUSH_RATE) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_TEXTURE_CACHE_SIZE, property, NULL) > 0) {
        INIT_LOGD("  Setting texture cache size to %sMB", property);
        setMaxSize(MB(atof(property)));
    } else {
        INIT_LOGD("  Using default texture cache size of %.2fMB", DEFAULT_TEXTURE_CACHE_SIZE);
    }

    if (property_get(PROPERTY_TEXTURE_CACHE_FLUSH_RATE, property, NULL) > 0) {
        float flushRate = atof(property);
        INIT_LOGD("  Setting texture cache flush rate to %.2f%%", flushRate * 100.0f);
        setFlushRate(flushRate);
    } else {
        INIT_LOGD("  Using default texture cache flush rate of %.2f%%",
                DEFAULT_TEXTURE_CACHE_FLUSH_RATE * 100.0f);
    }

    init();
}

TextureCache::TextureCache(uint32_t maxByteSize):
        mCache(GenerationCache<SkBitmap*, Texture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(maxByteSize) {
    init();
}

TextureCache::~TextureCache() {
    mCache.clear();
}

void TextureCache::init() {
    mCache.setOnEntryRemovedListener(this);

    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &mMaxTextureSize);
    INIT_LOGD("    Maximum texture dimension is %d pixels", mMaxTextureSize);

    mDebugEnabled = readDebugLevel() & kDebugCaches;
}

///////////////////////////////////////////////////////////////////////////////
// Size management
///////////////////////////////////////////////////////////////////////////////

uint32_t TextureCache::getSize() {
    return mSize;
}

uint32_t TextureCache::getMaxSize() {
    return mMaxSize;
}

void TextureCache::setMaxSize(uint32_t maxSize) {
    mMaxSize = maxSize;
    while (mSize > mMaxSize) {
        mCache.removeOldest();
    }
}

void TextureCache::setFlushRate(float flushRate) {
    mFlushRate = fmaxf(0.0f, fminf(1.0f, flushRate));
}

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void TextureCache::operator()(SkBitmap*& bitmap, Texture*& texture) {
    // This will be called already locked
    if (texture) {
        mSize -= texture->bitmapSize;
        TEXTURE_LOGD("TextureCache::callback: name, removed size, mSize = %d, %d, %d",
                texture->id, texture->bitmapSize, mSize);
        if (mDebugEnabled) {
            ALOGD("Texture deleted, size = %d", texture->bitmapSize);
        }
        glDeleteTextures(1, &texture->id);
        delete texture;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

Texture* TextureCache::get(SkBitmap* bitmap) {
    Texture* texture = mCache.get(bitmap);

    if (!texture) {
        if (bitmap->width() > mMaxTextureSize || bitmap->height() > mMaxTextureSize) {
            ALOGW("Bitmap too large to be uploaded into a texture (%dx%d, max=%dx%d)",
                    bitmap->width(), bitmap->height(), mMaxTextureSize, mMaxTextureSize);
            return NULL;
        }

        const uint32_t size = bitmap->rowBytes() * bitmap->height();
        // Don't even try to cache a bitmap that's bigger than the cache
        if (size < mMaxSize) {
            while (mSize + size > mMaxSize) {
                mCache.removeOldest();
            }
        }

        texture = new Texture;
        texture->bitmapSize = size;
        generateTexture(bitmap, texture, false);

        if (size < mMaxSize) {
            mSize += size;
            TEXTURE_LOGD("TextureCache::get: create texture(%p): name, size, mSize = %d, %d, %d",
                     bitmap, texture->id, size, mSize);
            if (mDebugEnabled) {
                ALOGD("Texture created, size = %d", size);
            }
            mCache.put(bitmap, texture);
        } else {
            texture->cleanup = true;
        }
    } else if (bitmap->getGenerationID() != texture->generation) {
        generateTexture(bitmap, texture, true);
    }

    return texture;
}

Texture* TextureCache::getTransient(SkBitmap* bitmap) {
    Texture* texture = new Texture;
    texture->bitmapSize = bitmap->rowBytes() * bitmap->height();
    texture->cleanup = true;

    generateTexture(bitmap, texture, false);

    return texture;
}

void TextureCache::remove(SkBitmap* bitmap) {
    mCache.remove(bitmap);
}

void TextureCache::removeDeferred(SkBitmap* bitmap) {
    Mutex::Autolock _l(mLock);
    mGarbage.push(bitmap);
}

void TextureCache::clearGarbage() {
    Mutex::Autolock _l(mLock);
    size_t count = mGarbage.size();
    for (size_t i = 0; i < count; i++) {
        mCache.remove(mGarbage.itemAt(i));
    }
    mGarbage.clear();
}

void TextureCache::clear() {
    mCache.clear();
    TEXTURE_LOGD("TextureCache:clear(), mSize = %d", mSize);
}

void TextureCache::flush() {
    if (mFlushRate >= 1.0f || mCache.size() == 0) return;
    if (mFlushRate <= 0.0f) {
        clear();
        return;
    }

    uint32_t targetSize = uint32_t(mSize * mFlushRate);
    TEXTURE_LOGD("TextureCache::flush: target size: %d", targetSize);

    while (mSize > targetSize) {
        mCache.removeOldest();
    }
}

void TextureCache::generateTexture(SkBitmap* bitmap, Texture* texture, bool regenerate) {
    SkAutoLockPixels alp(*bitmap);

    if (!bitmap->readyToDraw()) {
        ALOGE("Cannot generate texture from bitmap");
        return;
    }

    // We could also enable mipmapping if both bitmap dimensions are powers
    // of 2 but we'd have to deal with size changes. Let's keep this simple
    const bool canMipMap = Caches::getInstance().extensions.hasNPot();

    // If the texture had mipmap enabled but not anymore,
    // force a glTexImage2D to discard the mipmap levels
    const bool resize = !regenerate || bitmap->width() != int(texture->width) ||
            bitmap->height() != int(texture->height) ||
            (regenerate && canMipMap && texture->mipMap && !bitmap->hasHardwareMipMap());

    if (!regenerate) {
        glGenTextures(1, &texture->id);
    }

    texture->generation = bitmap->getGenerationID();
    texture->width = bitmap->width();
    texture->height = bitmap->height();

    glBindTexture(GL_TEXTURE_2D, texture->id);

    switch (bitmap->getConfig()) {
    case SkBitmap::kA8_Config:
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        uploadToTexture(resize, GL_ALPHA, bitmap->rowBytesAsPixels(), texture->height,
                GL_UNSIGNED_BYTE, bitmap->getPixels());
        texture->blend = true;
        break;
    case SkBitmap::kRGB_565_Config:
        glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());
        uploadToTexture(resize, GL_RGB, bitmap->rowBytesAsPixels(), texture->height,
                GL_UNSIGNED_SHORT_5_6_5, bitmap->getPixels());
        texture->blend = false;
        break;
    case SkBitmap::kARGB_8888_Config:
        glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());
        uploadToTexture(resize, GL_RGBA, bitmap->rowBytesAsPixels(), texture->height,
                GL_UNSIGNED_BYTE, bitmap->getPixels());
        // Do this after calling getPixels() to make sure Skia's deferred
        // decoding happened
        texture->blend = !bitmap->isOpaque();
        break;
    case SkBitmap::kARGB_4444_Config:
    case SkBitmap::kIndex8_Config:
        glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());
        uploadLoFiTexture(resize, bitmap, texture->width, texture->height);
        texture->blend = !bitmap->isOpaque();
        break;
    default:
        ALOGW("Unsupported bitmap config: %d", bitmap->getConfig());
        break;
    }

    if (canMipMap) {
        texture->mipMap = bitmap->hasHardwareMipMap();
        if (texture->mipMap) {
            glGenerateMipmap(GL_TEXTURE_2D);
        }
    }

    if (!regenerate) {
        texture->setFilter(GL_NEAREST);
        texture->setWrap(GL_CLAMP_TO_EDGE);
    }
}

void TextureCache::uploadLoFiTexture(bool resize, SkBitmap* bitmap,
        uint32_t width, uint32_t height) {
    SkBitmap rgbaBitmap;
    rgbaBitmap.setConfig(SkBitmap::kARGB_8888_Config, width, height);
    rgbaBitmap.allocPixels();
    rgbaBitmap.eraseColor(0);
    rgbaBitmap.setIsOpaque(bitmap->isOpaque());

    SkCanvas canvas(rgbaBitmap);
    canvas.drawBitmap(*bitmap, 0.0f, 0.0f, NULL);

    uploadToTexture(resize, GL_RGBA, rgbaBitmap.rowBytesAsPixels(), height,
            GL_UNSIGNED_BYTE, rgbaBitmap.getPixels());
}

void TextureCache::uploadToTexture(bool resize, GLenum format, GLsizei width, GLsizei height,
        GLenum type, const GLvoid * data) {
    if (resize) {
        glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, type, data);
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, data);
    }
}

}; // namespace uirenderer
}; // namespace android
