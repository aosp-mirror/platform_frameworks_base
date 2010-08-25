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

#include "TextureCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

TextureCache::TextureCache():
        mCache(GenerationCache<SkBitmap*, Texture*>::kUnlimitedCapacity),
        mSize(0), mMaxSize(MB(DEFAULT_TEXTURE_CACHE_SIZE)) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_TEXTURE_CACHE_SIZE, property, NULL) > 0) {
        LOGD("  Setting texture cache size to %sMB", property);
        setMaxSize(MB(atof(property)));
    } else {
        LOGD("  Using default texture cache size of %.2fMB", DEFAULT_TEXTURE_CACHE_SIZE);
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
    LOGD("    Maximum texture dimension is %d pixels", mMaxTextureSize);
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

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void TextureCache::operator()(SkBitmap*& bitmap, Texture*& texture) {
    if (bitmap) {
        const uint32_t size = bitmap->rowBytes() * bitmap->height();
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

Texture* TextureCache::get(SkBitmap* bitmap) {
    Texture* texture = mCache.get(bitmap);
    if (!texture) {
        if (bitmap->width() > mMaxTextureSize || bitmap->height() > mMaxTextureSize) {
            LOGW("Bitmap too large to be uploaded into a texture");
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
        generateTexture(bitmap, texture, false);

        if (size < mMaxSize) {
            mSize += size;
            mCache.put(bitmap, texture);
        } else {
            texture->cleanup = true;
        }
    } else if (bitmap->getGenerationID() != texture->generation) {
        generateTexture(bitmap, texture, true);
    }

    return texture;
}

void TextureCache::remove(SkBitmap* bitmap) {
    mCache.remove(bitmap);
}

void TextureCache::clear() {
    mCache.clear();
}

void TextureCache::generateTexture(SkBitmap* bitmap, Texture* texture, bool regenerate) {
    SkAutoLockPixels alp(*bitmap);
    if (!bitmap->readyToDraw()) {
        LOGE("Cannot generate texture from bitmap");
        return;
    }

    if (!regenerate) {
        texture->generation = bitmap->getGenerationID();
        texture->width = bitmap->width();
        texture->height = bitmap->height();

        glGenTextures(1, &texture->id);
    }

    glBindTexture(GL_TEXTURE_2D, texture->id);
    glPixelStorei(GL_UNPACK_ALIGNMENT, bitmap->bytesPerPixel());

    switch (bitmap->getConfig()) {
    case SkBitmap::kA8_Config:
        texture->blend = true;
        glTexImage2D(GL_TEXTURE_2D, 0, GL_ALPHA, bitmap->rowBytesAsPixels(), texture->height, 0,
                GL_ALPHA, GL_UNSIGNED_BYTE, bitmap->getPixels());
        break;
    case SkBitmap::kRGB_565_Config:
        texture->blend = false;
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGB, bitmap->rowBytesAsPixels(), texture->height, 0,
                GL_RGB, GL_UNSIGNED_SHORT_5_6_5, bitmap->getPixels());
        break;
    case SkBitmap::kARGB_8888_Config:
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, bitmap->rowBytesAsPixels(), texture->height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, bitmap->getPixels());
        // Do this after calling getPixels() to make sure Skia's deferred
        // decoding happened
        texture->blend = !bitmap->isOpaque();
        break;
    default:
        break;
    }

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
}

}; // namespace uirenderer
}; // namespace android
