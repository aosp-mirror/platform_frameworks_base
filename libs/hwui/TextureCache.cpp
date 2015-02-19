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
#define ATRACE_TAG ATRACE_TAG_VIEW

#include <GLES2/gl2.h>

#include <SkCanvas.h>
#include <SkPixelRef.h>

#include <utils/Mutex.h>

#include "AssetAtlas.h"
#include "Caches.h"
#include "TextureCache.h"
#include "Properties.h"
#include "utils/TraceUtils.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

TextureCache::TextureCache():
        mCache(LruCache<uint32_t, Texture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_TEXTURE_CACHE_SIZE)),
        mFlushRate(DEFAULT_TEXTURE_CACHE_FLUSH_RATE), mAssetAtlas(0) {
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
        mCache(LruCache<uint32_t, Texture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(maxByteSize), mAssetAtlas(0) {
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

void TextureCache::operator()(uint32_t&, Texture*& texture) {
    // This will be called already locked
    if (texture) {
        mSize -= texture->bitmapSize;
        TEXTURE_LOGD("TextureCache::callback: name, removed size, mSize = %d, %d, %d",
                texture->id, texture->bitmapSize, mSize);
        if (mDebugEnabled) {
            ALOGD("Texture deleted, size = %d", texture->bitmapSize);
        }
        texture->deleteTexture();
        delete texture;
    }
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

void TextureCache::setAssetAtlas(AssetAtlas* assetAtlas) {
    mAssetAtlas = assetAtlas;
}

void TextureCache::resetMarkInUse() {
    LruCache<uint32_t, Texture*>::Iterator iter(mCache);
    while (iter.next()) {
        iter.value()->isInUse = false;
    }
}

bool TextureCache::canMakeTextureFromBitmap(const SkBitmap* bitmap) {
    if (bitmap->width() > mMaxTextureSize || bitmap->height() > mMaxTextureSize) {
        ALOGW("Bitmap too large to be uploaded into a texture (%dx%d, max=%dx%d)",
                bitmap->width(), bitmap->height(), mMaxTextureSize, mMaxTextureSize);
        return false;
    }
    return true;
}

// Returns a prepared Texture* that either is already in the cache or can fit
// in the cache (and is thus added to the cache)
Texture* TextureCache::getCachedTexture(const SkBitmap* bitmap) {
    if (CC_LIKELY(mAssetAtlas)) {
        AssetAtlas::Entry* entry = mAssetAtlas->getEntry(bitmap);
        if (CC_UNLIKELY(entry)) {
            return entry->texture;
        }
    }

    Texture* texture = mCache.get(bitmap->pixelRef()->getStableID());

    if (!texture) {
        if (!canMakeTextureFromBitmap(bitmap)) {
            return NULL;
        }

        const uint32_t size = bitmap->rowBytes() * bitmap->height();
        bool canCache = size < mMaxSize;
        // Don't even try to cache a bitmap that's bigger than the cache
        while (canCache && mSize + size > mMaxSize) {
            Texture* oldest = mCache.peekOldestValue();
            if (oldest && !oldest->isInUse) {
                mCache.removeOldest();
            } else {
                canCache = false;
            }
        }

        if (canCache) {
            texture = new Texture();
            texture->bitmapSize = size;
            generateTexture(bitmap, texture, false);

            mSize += size;
            TEXTURE_LOGD("TextureCache::get: create texture(%p): name, size, mSize = %d, %d, %d",
                     bitmap, texture->id, size, mSize);
            if (mDebugEnabled) {
                ALOGD("Texture created, size = %d", size);
            }
            mCache.put(bitmap->pixelRef()->getStableID(), texture);
        }
    } else if (!texture->isInUse && bitmap->getGenerationID() != texture->generation) {
        // Texture was in the cache but is dirty, re-upload
        // TODO: Re-adjust the cache size if the bitmap's dimensions have changed
        generateTexture(bitmap, texture, true);
    }

    return texture;
}

bool TextureCache::prefetchAndMarkInUse(const SkBitmap* bitmap) {
    Texture* texture = getCachedTexture(bitmap);
    if (texture) {
        texture->isInUse = true;
    }
    return texture;
}

Texture* TextureCache::get(const SkBitmap* bitmap) {
    Texture* texture = getCachedTexture(bitmap);

    if (!texture) {
        if (!canMakeTextureFromBitmap(bitmap)) {
            return NULL;
        }

        const uint32_t size = bitmap->rowBytes() * bitmap->height();
        texture = new Texture();
        texture->bitmapSize = size;
        generateTexture(bitmap, texture, false);
        texture->cleanup = true;
    }

    return texture;
}

Texture* TextureCache::getTransient(const SkBitmap* bitmap) {
    Texture* texture = new Texture();
    texture->bitmapSize = bitmap->rowBytes() * bitmap->height();
    texture->cleanup = true;

    generateTexture(bitmap, texture, false);

    return texture;
}

void TextureCache::releaseTexture(const SkBitmap* bitmap) {
    if (!bitmap || !bitmap->pixelRef()) return;

    Mutex::Autolock _l(mLock);
    mGarbage.push(bitmap->pixelRef()->getStableID());
}

void TextureCache::clearGarbage() {
    Mutex::Autolock _l(mLock);
    size_t count = mGarbage.size();
    for (size_t i = 0; i < count; i++) {
        uint32_t pixelRefId = mGarbage.itemAt(i);
        mCache.remove(pixelRefId);
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

void TextureCache::generateTexture(const SkBitmap* bitmap, Texture* texture, bool regenerate) {
    SkAutoLockPixels alp(*bitmap);

    if (!bitmap->readyToDraw()) {
        ALOGE("Cannot generate texture from bitmap");
        return;
    }

    ATRACE_FORMAT("Upload %ux%u Texture", bitmap->width(), bitmap->height());

    // We could also enable mipmapping if both bitmap dimensions are powers
    // of 2 but we'd have to deal with size changes. Let's keep this simple
    const bool canMipMap = Extensions::getInstance().hasNPot();

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

    Caches::getInstance().bindTexture(texture->id);

    switch (bitmap->colorType()) {
    case kAlpha_8_SkColorType:
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        uploadToTexture(resize, GL_ALPHA, bitmap->rowBytesAsPixels(), bitmap->bytesPerPixel(),
                texture->width, texture->height, GL_UNSIGNED_BYTE, bitmap->getPixels());
        texture->blend = true;
        break;
    case kRGB_565_SkColorType:
        glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());
        uploadToTexture(resize, GL_RGB, bitmap->rowBytesAsPixels(), bitmap->bytesPerPixel(),
                texture->width, texture->height, GL_UNSIGNED_SHORT_5_6_5, bitmap->getPixels());
        texture->blend = false;
        break;
    case kN32_SkColorType:
        glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());
        uploadToTexture(resize, GL_RGBA, bitmap->rowBytesAsPixels(), bitmap->bytesPerPixel(),
                texture->width, texture->height, GL_UNSIGNED_BYTE, bitmap->getPixels());
        // Do this after calling getPixels() to make sure Skia's deferred
        // decoding happened
        texture->blend = !bitmap->isOpaque();
        break;
    case kARGB_4444_SkColorType:
    case kIndex_8_SkColorType:
        glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());
        uploadLoFiTexture(resize, bitmap, texture->width, texture->height);
        texture->blend = !bitmap->isOpaque();
        break;
    default:
        ALOGW("Unsupported bitmap colorType: %d", bitmap->colorType());
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

void TextureCache::uploadLoFiTexture(bool resize, const SkBitmap* bitmap,
        uint32_t width, uint32_t height) {
    SkBitmap rgbaBitmap;
    rgbaBitmap.allocPixels(SkImageInfo::MakeN32(width, height, bitmap->alphaType()));
    rgbaBitmap.eraseColor(0);

    SkCanvas canvas(rgbaBitmap);
    canvas.drawBitmap(*bitmap, 0.0f, 0.0f, NULL);

    uploadToTexture(resize, GL_RGBA, rgbaBitmap.rowBytesAsPixels(), rgbaBitmap.bytesPerPixel(),
            width, height, GL_UNSIGNED_BYTE, rgbaBitmap.getPixels());
}

void TextureCache::uploadToTexture(bool resize, GLenum format, GLsizei stride, GLsizei bpp,
        GLsizei width, GLsizei height, GLenum type, const GLvoid * data) {
    const bool useStride = stride != width && Extensions::getInstance().hasUnpackRowLength();
    if ((stride == width) || useStride) {
        if (useStride) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, stride);
        }

        if (resize) {
            glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, type, data);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, data);
        }

        if (useStride) {
            glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
        }
    } else {
        //  With OpenGL ES 2.0 we need to copy the bitmap in a temporary buffer
        //  if the stride doesn't match the width

        GLvoid * temp = (GLvoid *) malloc(width * height * bpp);
        if (!temp) return;

        uint8_t * pDst = (uint8_t *)temp;
        uint8_t * pSrc = (uint8_t *)data;
        for (GLsizei i = 0; i < height; i++) {
            memcpy(pDst, pSrc, width * bpp);
            pDst += width * bpp;
            pSrc += stride * bpp;
        }

        if (resize) {
            glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, type, temp);
        } else {
            glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, format, type, temp);
        }

        free(temp);
    }
}

}; // namespace uirenderer
}; // namespace android
