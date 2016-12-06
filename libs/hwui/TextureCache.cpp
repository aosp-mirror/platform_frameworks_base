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

#include "AssetAtlas.h"
#include "Caches.h"
#include "Texture.h"
#include "TextureCache.h"
#include "Properties.h"
#include "utils/TraceUtils.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

TextureCache::TextureCache()
        : mCache(LruCache<uint32_t, Texture*>::kUnlimitedCapacity)
        , mSize(0)
        , mMaxSize(Properties::textureCacheSize)
        , mFlushRate(Properties::textureCacheFlushRate)
        , mAssetAtlas(nullptr) {
    mCache.setOnEntryRemovedListener(this);

    glGetIntegerv(GL_MAX_TEXTURE_SIZE, &mMaxTextureSize);
    INIT_LOGD("    Maximum texture dimension is %d pixels", mMaxTextureSize);

    mDebugEnabled = Properties::debugLevel & kDebugCaches;
}

TextureCache::~TextureCache() {
    mCache.clear();
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

void TextureCache::resetMarkInUse(void* ownerToken) {
    LruCache<uint32_t, Texture*>::Iterator iter(mCache);
    while (iter.next()) {
        if (iter.value()->isInUse == ownerToken) {
            iter.value()->isInUse = nullptr;
        }
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
Texture* TextureCache::getCachedTexture(const SkBitmap* bitmap, AtlasUsageType atlasUsageType) {
    if (CC_LIKELY(mAssetAtlas != nullptr) && atlasUsageType == AtlasUsageType::Use) {
        AssetAtlas::Entry* entry = mAssetAtlas->getEntry(bitmap->pixelRef());
        if (CC_UNLIKELY(entry)) {
            return entry->texture;
        }
    }

    Texture* texture = mCache.get(bitmap->pixelRef()->getStableID());

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
            texture = new Texture(Caches::getInstance());
            texture->bitmapSize = size;
            texture->generation = bitmap->getGenerationID();
            texture->upload(*bitmap);

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
        texture->upload(*bitmap);
        texture->generation = bitmap->getGenerationID();
    }

    return texture;
}

bool TextureCache::prefetchAndMarkInUse(void* ownerToken, const SkBitmap* bitmap) {
    Texture* texture = getCachedTexture(bitmap, AtlasUsageType::Use);
    if (texture) {
        texture->isInUse = ownerToken;
    }
    return texture;
}

bool TextureCache::prefetch(const SkBitmap* bitmap) {
    return getCachedTexture(bitmap, AtlasUsageType::Use);
}

Texture* TextureCache::get(const SkBitmap* bitmap, AtlasUsageType atlasUsageType) {
    Texture* texture = getCachedTexture(bitmap, atlasUsageType);

    if (!texture) {
        if (!canMakeTextureFromBitmap(bitmap)) {
            return nullptr;
        }

        const uint32_t size = bitmap->rowBytes() * bitmap->height();
        texture = new Texture(Caches::getInstance());
        texture->bitmapSize = size;
        texture->upload(*bitmap);
        texture->generation = bitmap->getGenerationID();
        texture->cleanup = true;
    }

    return texture;
}

void TextureCache::releaseTexture(uint32_t pixelRefStableID) {
    Mutex::Autolock _l(mLock);
    mGarbage.push_back(pixelRefStableID);
}

void TextureCache::clearGarbage() {
    Mutex::Autolock _l(mLock);
    size_t count = mGarbage.size();
    for (size_t i = 0; i < count; i++) {
        uint32_t pixelRefId = mGarbage[i];
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

}; // namespace uirenderer
}; // namespace android
