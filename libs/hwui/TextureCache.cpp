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

#include <GLES2/gl2.h>

#include <utils/Mutex.h>

#include "Caches.h"
#include "DeviceInfo.h"
#include "Properties.h"
#include "Texture.h"
#include "TextureCache.h"
#include "hwui/Bitmap.h"
#include "utils/TraceUtils.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

TextureCache::TextureCache()
        : mCache(LruCache<uint32_t, Texture*>::kUnlimitedCapacity)
        , mSize(0)
        , mMaxSize(DeviceInfo::multiplyByResolution(4 * 6))  // 6 screen-sized RGBA_8888 bitmaps
        , mFlushRate(.4f) {
    mCache.setOnEntryRemovedListener(this);
    mMaxTextureSize = DeviceInfo::get()->maxTextureSize();
    mDebugEnabled = Properties::debugLevel & kDebugCaches;
}

TextureCache::~TextureCache() {
    this->clear();
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

///////////////////////////////////////////////////////////////////////////////
// Callbacks
///////////////////////////////////////////////////////////////////////////////

void TextureCache::operator()(uint32_t&, Texture*& texture) {
    // This will be called already locked
    if (texture) {
        mSize -= texture->bitmapSize;
        TEXTURE_LOGD("TextureCache::callback: name, removed size, mSize = %d, %d, %d", texture->id,
                     texture->bitmapSize, mSize);
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

void TextureCache::resetMarkInUse(void* ownerToken) {
    LruCache<uint32_t, Texture*>::Iterator iter(mCache);
    while (iter.next()) {
        if (iter.value()->isInUse == ownerToken) {
            iter.value()->isInUse = nullptr;
        }
    }
}

bool TextureCache::canMakeTextureFromBitmap(Bitmap* bitmap) {
    if (bitmap->width() > mMaxTextureSize || bitmap->height() > mMaxTextureSize) {
        ALOGW("Bitmap too large to be uploaded into a texture (%dx%d, max=%dx%d)", bitmap->width(),
              bitmap->height(), mMaxTextureSize, mMaxTextureSize);
        return false;
    }
    return true;
}

Texture* TextureCache::createTexture(Bitmap* bitmap) {
    Texture* texture = new Texture(Caches::getInstance());
    texture->bitmapSize = bitmap->rowBytes() * bitmap->height();
    texture->generation = bitmap->getGenerationID();
    texture->upload(*bitmap);
    return texture;
}

// Returns a prepared Texture* that either is already in the cache or can fit
// in the cache (and is thus added to the cache)
Texture* TextureCache::getCachedTexture(Bitmap* bitmap) {
    if (bitmap->isHardware()) {
        auto textureIterator = mHardwareTextures.find(bitmap->getStableID());
        if (textureIterator == mHardwareTextures.end()) {
            Texture* texture = createTexture(bitmap);
            mHardwareTextures.insert(
                    std::make_pair(bitmap->getStableID(), std::unique_ptr<Texture>(texture)));
            if (mDebugEnabled) {
                ALOGD("Texture created for hw bitmap size = %d", texture->bitmapSize);
            }
            return texture;
        }
        return textureIterator->second.get();
    }

    Texture* texture = mCache.get(bitmap->getStableID());

    if (!texture) {
        if (!canMakeTextureFromBitmap(bitmap)) {
            return nullptr;
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
            texture = createTexture(bitmap);
            mSize += size;
            TEXTURE_LOGD("TextureCache::get: create texture(%p): name, size, mSize = %d, %d, %d",
                         bitmap, texture->id, size, mSize);
            if (mDebugEnabled) {
                ALOGD("Texture created, size = %d", size);
            }
            mCache.put(bitmap->getStableID(), texture);
        }
    } else if (!texture->isInUse && bitmap->getGenerationID() != texture->generation) {
        // Texture was in the cache but is dirty, re-upload
        // TODO: Re-adjust the cache size if the bitmap's dimensions have changed
        texture->upload(*bitmap);
        texture->generation = bitmap->getGenerationID();
    }

    return texture;
}

bool TextureCache::prefetchAndMarkInUse(void* ownerToken, Bitmap* bitmap) {
    Texture* texture = getCachedTexture(bitmap);
    if (texture) {
        texture->isInUse = ownerToken;
    }
    return texture;
}

bool TextureCache::prefetch(Bitmap* bitmap) {
    return getCachedTexture(bitmap);
}

Texture* TextureCache::get(Bitmap* bitmap) {
    Texture* texture = getCachedTexture(bitmap);

    if (!texture) {
        if (!canMakeTextureFromBitmap(bitmap)) {
            return nullptr;
        }
        texture = createTexture(bitmap);
        texture->cleanup = true;
    }

    return texture;
}

bool TextureCache::destroyTexture(uint32_t pixelRefStableID) {
    auto hardwareIter = mHardwareTextures.find(pixelRefStableID);
    if (hardwareIter != mHardwareTextures.end()) {
        hardwareIter->second->deleteTexture();
        mHardwareTextures.erase(hardwareIter);
        return true;
    }
    return mCache.remove(pixelRefStableID);
}

void TextureCache::clear() {
    mCache.clear();
    for (auto& iter : mHardwareTextures) {
        iter.second->deleteTexture();
    }
    mHardwareTextures.clear();
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

};  // namespace uirenderer
};  // namespace android
