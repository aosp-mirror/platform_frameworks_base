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

#ifndef ANDROID_HWUI_PATCH_CACHE_H
#define ANDROID_HWUI_PATCH_CACHE_H

#include <GLES2/gl2.h>

#include <utils/LruCache.h>

#include <androidfw/ResourceTypes.h>

#include "AssetAtlas.h"
#include "Debug.h"
#include "utils/Pair.h"

namespace android {
namespace uirenderer {

class Patch;

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_PATCHES
    #define PATCH_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define PATCH_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Cache
///////////////////////////////////////////////////////////////////////////////

class Caches;

class PatchCache {
public:
    PatchCache(RenderState& renderState);
    ~PatchCache();
    void init();

    const Patch* get(const AssetAtlas::Entry* entry,
            const uint32_t bitmapWidth, const uint32_t bitmapHeight,
            const float pixelWidth, const float pixelHeight, const Res_png_9patch* patch);
    void clear();

    uint32_t getSize() const {
        return mSize;
    }

    uint32_t getMaxSize() const {
        return mMaxSize;
    }

    GLuint getMeshBuffer() const {
        return mMeshBuffer;
    }

    uint32_t getGenerationId() const {
        return mGenerationId;
    }

    /**
     * Removes the entries associated with the specified 9-patch. This is meant
     * to be called from threads that are not the EGL context thread (GC thread
     * on the VM side for instance.)
     */
    void removeDeferred(Res_png_9patch* patch);

    /**
     * Process deferred removals.
     */
    void clearGarbage();


private:
    struct PatchDescription {
        PatchDescription(): mPatch(nullptr), mBitmapWidth(0), mBitmapHeight(0),
                mPixelWidth(0), mPixelHeight(0) {
        }

        PatchDescription(const uint32_t bitmapWidth, const uint32_t bitmapHeight,
                const float pixelWidth, const float pixelHeight, const Res_png_9patch* patch):
                mPatch(patch), mBitmapWidth(bitmapWidth), mBitmapHeight(bitmapHeight),
                mPixelWidth(pixelWidth), mPixelHeight(pixelHeight) {
        }

        hash_t hash() const;

        const Res_png_9patch* getPatch() const { return mPatch; }

        static int compare(const PatchDescription& lhs, const PatchDescription& rhs);

        bool operator==(const PatchDescription& other) const {
            return compare(*this, other) == 0;
        }

        bool operator!=(const PatchDescription& other) const {
            return compare(*this, other) != 0;
        }

        friend inline int strictly_order_type(const PatchDescription& lhs,
                const PatchDescription& rhs) {
            return PatchDescription::compare(lhs, rhs) < 0;
        }

        friend inline int compare_type(const PatchDescription& lhs,
                const PatchDescription& rhs) {
            return PatchDescription::compare(lhs, rhs);
        }

        friend inline hash_t hash_type(const PatchDescription& entry) {
            return entry.hash();
        }

    private:
        const Res_png_9patch* mPatch;
        uint32_t mBitmapWidth;
        uint32_t mBitmapHeight;
        float mPixelWidth;
        float mPixelHeight;

    }; // struct PatchDescription

    /**
     * A buffer block represents an empty range in the mesh buffer
     * that can be used to store vertices.
     *
     * The patch cache maintains a linked-list of buffer blocks
     * to track available regions of memory in the VBO.
     */
    struct BufferBlock {
        BufferBlock(uint32_t offset, uint32_t size): offset(offset), size(size), next(nullptr) {
        }

        uint32_t offset;
        uint32_t size;

        BufferBlock* next;
    }; // struct BufferBlock

    typedef Pair<const PatchDescription*, Patch*> patch_pair_t;

    void clearCache();
    void createVertexBuffer();

    void setupMesh(Patch* newMesh);

    void remove(Vector<patch_pair_t>& patchesToRemove, Res_png_9patch* patch);

#if DEBUG_PATCHES
    void dumpFreeBlocks(const char* prefix);
#endif

    RenderState& mRenderState;
    const uint32_t mMaxSize;
    uint32_t mSize;

    LruCache<PatchDescription, Patch*> mCache;

    GLuint mMeshBuffer;
    // First available free block inside the mesh buffer
    BufferBlock* mFreeBlocks;

    uint32_t mGenerationId;

    // Garbage tracking, required to handle GC events on the VM side
    Vector<Res_png_9patch*> mGarbage;
    mutable Mutex mLock;
}; // class PatchCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATCH_CACHE_H
