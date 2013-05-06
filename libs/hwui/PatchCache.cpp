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

#include <utils/JenkinsHash.h>
#include <utils/Log.h>

#include "Caches.h"
#include "PatchCache.h"
#include "Properties.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Constructors/destructor
///////////////////////////////////////////////////////////////////////////////

PatchCache::PatchCache(): mCache(LruCache<PatchDescription, Patch*>::kUnlimitedCapacity) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_PATCH_CACHE_SIZE, property, NULL) > 0) {
        INIT_LOGD("  Setting patch cache size to %skB", property);
        mMaxSize = KB(atoi(property));
    } else {
        INIT_LOGD("  Using default patch cache size of %.2fkB", DEFAULT_PATCH_CACHE_SIZE);
        mMaxSize = KB(DEFAULT_PATCH_CACHE_SIZE);
    }
    mSize = 0;
}

PatchCache::~PatchCache() {
    clear();
}

void PatchCache::init(Caches& caches) {
    glGenBuffers(1, &mMeshBuffer);
    caches.bindMeshBuffer(mMeshBuffer);
    caches.resetVertexPointers();

    glBufferData(GL_ARRAY_BUFFER, mMaxSize, NULL, GL_DYNAMIC_DRAW);
}

///////////////////////////////////////////////////////////////////////////////
// Caching
///////////////////////////////////////////////////////////////////////////////

hash_t PatchCache::PatchDescription::hash() const {
    uint32_t hash = JenkinsHashMix(0, android::hash_type(mPatch));
    hash = JenkinsHashMix(hash, mBitmapWidth);
    hash = JenkinsHashMix(hash, mBitmapHeight);
    hash = JenkinsHashMix(hash, mPixelWidth);
    hash = JenkinsHashMix(hash, mPixelHeight);
    return JenkinsHashWhiten(hash);
}

int PatchCache::PatchDescription::compare(const PatchCache::PatchDescription& lhs,
            const PatchCache::PatchDescription& rhs) {
    return memcmp(&lhs, &rhs, sizeof(PatchDescription));
}

void PatchCache::clear() {
    glDeleteBuffers(1, &mMeshBuffer);
    clearCache();
    mSize = 0;
}

void PatchCache::clearCache() {
    LruCache<PatchDescription, Patch*>::Iterator i(mCache);
    while (i.next()) {
        delete i.value();
    }
    mCache.clear();
}

const Patch* PatchCache::get(const AssetAtlas::Entry* entry,
        const uint32_t bitmapWidth, const uint32_t bitmapHeight,
        const float pixelWidth, const float pixelHeight, const Res_png_9patch* patch) {

    const PatchDescription description(bitmapWidth, bitmapHeight, pixelWidth, pixelHeight, patch);
    const Patch* mesh = mCache.get(description);

    if (!mesh) {
        Patch* newMesh = new Patch();
        TextureVertex* vertices;

        if (entry) {
            vertices = newMesh->createMesh(bitmapWidth, bitmapHeight,
                    0.0f, 0.0f, pixelWidth, pixelHeight, entry->uvMapper, patch);
        } else {
            vertices = newMesh->createMesh(bitmapWidth, bitmapHeight,
                    0.0f, 0.0f, pixelWidth, pixelHeight, patch);
        }

        if (vertices) {
            Caches& caches = Caches::getInstance();
            caches.bindMeshBuffer(mMeshBuffer);
            caches.resetVertexPointers();

            // TODO: Simply remove the oldest items until we have enough room
            // This will require to keep a list of free blocks in the VBO
            uint32_t size = newMesh->getSize();
            if (mSize + size > mMaxSize) {
                clearCache();
                glBufferData(GL_ARRAY_BUFFER, mMaxSize, NULL, GL_DYNAMIC_DRAW);
                mSize = 0;
            }

            newMesh->offset = (GLintptr) mSize;
            newMesh->textureOffset = newMesh->offset + gMeshTextureOffset;
            mSize += size;

            glBufferSubData(GL_ARRAY_BUFFER, newMesh->offset, size, vertices);

            delete[] vertices;
        }

        mCache.put(description, newMesh);
        return newMesh;
    }

    return mesh;
}

}; // namespace uirenderer
}; // namespace android
