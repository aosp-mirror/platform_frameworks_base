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
#include "Patch.h"

namespace android {
namespace uirenderer {

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
    PatchCache();
    ~PatchCache();
    void init(Caches& caches);

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

private:
    void clearCache();
    void createVertexBuffer();

    struct PatchDescription {
        PatchDescription(): mPatch(NULL), mBitmapWidth(0), mBitmapHeight(0),
                mPixelWidth(0), mPixelHeight(0) {
        }

        PatchDescription(const uint32_t bitmapWidth, const uint32_t bitmapHeight,
                const float pixelWidth, const float pixelHeight, const Res_png_9patch* patch):
                mPatch(patch), mBitmapWidth(bitmapWidth), mBitmapHeight(bitmapHeight),
                mPixelWidth(pixelWidth), mPixelHeight(pixelHeight) {
        }

        hash_t hash() const;

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

    uint32_t mMaxSize;
    uint32_t mSize;

    LruCache<PatchDescription, Patch*> mCache;

    GLuint mMeshBuffer;

    uint32_t mGenerationId;
}; // class PatchCache

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_PATCH_CACHE_H
