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

PatchCache::PatchCache():
        mSize(0), mCache(LruCache<PatchDescription, Patch*>::kUnlimitedCapacity),
        mMeshBuffer(0), mFreeBlocks(NULL), mGenerationId(0) {
    char property[PROPERTY_VALUE_MAX];
    if (property_get(PROPERTY_PATCH_CACHE_SIZE, property, NULL) > 0) {
        INIT_LOGD("  Setting patch cache size to %skB", property);
        mMaxSize = KB(atoi(property));
    } else {
        INIT_LOGD("  Using default patch cache size of %.2fkB", DEFAULT_PATCH_CACHE_SIZE);
        mMaxSize = KB(DEFAULT_PATCH_CACHE_SIZE);
    }
}

PatchCache::~PatchCache() {
    clear();
}

void PatchCache::init(Caches& caches) {
    bool created = false;
    if (!mMeshBuffer) {
        glGenBuffers(1, &mMeshBuffer);
        created = true;
    }

    caches.bindMeshBuffer(mMeshBuffer);
    caches.resetVertexPointers();

    if (created) {
        createVertexBuffer();
    }
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
    clearCache();

    if (mMeshBuffer) {
        Caches::getInstance().unbindMeshBuffer();
        glDeleteBuffers(1, &mMeshBuffer);
        mMeshBuffer = 0;
        mSize = 0;
    }
}

void PatchCache::clearCache() {
    LruCache<PatchDescription, Patch*>::Iterator i(mCache);
    while (i.next()) {
        delete i.value();
    }
    mCache.clear();

    BufferBlock* block = mFreeBlocks;
    while (block) {
        BufferBlock* next = block->next;
        delete block;
        block = next;
    }
    mFreeBlocks = NULL;
}

void PatchCache::remove(Vector<patch_pair_t>& patchesToRemove, Res_png_9patch* patch) {
    LruCache<PatchDescription, Patch*>::Iterator i(mCache);
    while (i.next()) {
        const PatchDescription& key = i.key();
        if (key.getPatch() == patch) {
            patchesToRemove.push(patch_pair_t(&key, i.value()));
        }
    }
}

void PatchCache::removeDeferred(Res_png_9patch* patch) {
    Mutex::Autolock _l(mLock);
    mGarbage.push(patch);
}

void PatchCache::clearGarbage() {
    Vector<patch_pair_t> patchesToRemove;

    { // scope for the mutex
        Mutex::Autolock _l(mLock);
        size_t count = mGarbage.size();
        for (size_t i = 0; i < count; i++) {
            remove(patchesToRemove, mGarbage[i]);
        }
        mGarbage.clear();
    }

    // TODO: We could sort patchesToRemove by offset to merge
    // adjacent free blocks
    for (size_t i = 0; i < patchesToRemove.size(); i++) {
        const patch_pair_t& pair = patchesToRemove[i];

        // Add a new free block to the list
        const Patch* patch = pair.getSecond();
        BufferBlock* block = new BufferBlock(patch->offset, patch->getSize());
        block->next = mFreeBlocks;
        mFreeBlocks = block;

        mSize -= patch->getSize();

        mCache.remove(*pair.getFirst());
    }

#if DEBUG_PATCHES
    if (patchesToRemove.size() > 0) {
        dumpFreeBlocks("Removed garbage");
    }
#endif
}

void PatchCache::createVertexBuffer() {
    glBufferData(GL_ARRAY_BUFFER, mMaxSize, NULL, GL_DYNAMIC_DRAW);
    mSize = 0;
    mFreeBlocks = new BufferBlock(0, mMaxSize);
    mGenerationId++;
}

/**
 * Sets the mesh's offsets and copies its associated vertices into
 * the mesh buffer (VBO).
 */
void PatchCache::setupMesh(Patch* newMesh, TextureVertex* vertices) {
    // This call ensures the VBO exists and that it is bound
    init(Caches::getInstance());

    // If we're running out of space, let's clear the entire cache
    uint32_t size = newMesh->getSize();
    if (mSize + size > mMaxSize) {
        clearCache();
        createVertexBuffer();
    }

    // Find a block where we can fit the mesh
    BufferBlock* previous = NULL;
    BufferBlock* block = mFreeBlocks;
    while (block) {
        // The mesh fits
        if (block->size >= size) {
            break;
        }
        previous = block;
        block = block->next;
    }

    // We have enough space left in the buffer, but it's
    // too fragmented, let's clear the cache
    if (!block) {
        clearCache();
        createVertexBuffer();
        previous = NULL;
        block = mFreeBlocks;
    }

    // Copy the 9patch mesh in the VBO
    newMesh->offset = (GLintptr) (block->offset);
    newMesh->textureOffset = newMesh->offset + gMeshTextureOffset;
    glBufferSubData(GL_ARRAY_BUFFER, newMesh->offset, size, vertices);

    // Remove the block since we've used it entirely
    if (block->size == size) {
        if (previous) {
            previous->next = block->next;
        } else {
            mFreeBlocks = block->next;
        }
    } else {
        // Resize the block now that it's occupied
        block->offset += size;
        block->size -= size;
    }

    mSize += size;
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
            // An atlas entry has a UV mapper
            vertices = newMesh->createMesh(bitmapWidth, bitmapHeight,
                    pixelWidth, pixelHeight, entry->uvMapper, patch);
        } else {
            vertices = newMesh->createMesh(bitmapWidth, bitmapHeight,
                    pixelWidth, pixelHeight, patch);
        }

        if (vertices) {
            setupMesh(newMesh, vertices);
        }

#if DEBUG_PATCHES
        dumpFreeBlocks("Adding patch");
#endif

        mCache.put(description, newMesh);
        return newMesh;
    }

    return mesh;
}

#if DEBUG_PATCHES
void PatchCache::dumpFreeBlocks(const char* prefix) {
    String8 dump;
    BufferBlock* block = mFreeBlocks;
    while (block) {
        dump.appendFormat("->(%d, %d)", block->offset, block->size);
        block = block->next;
    }
    ALOGD("%s: Free blocks%s", prefix, dump.string());
}
#endif

}; // namespace uirenderer
}; // namespace android
