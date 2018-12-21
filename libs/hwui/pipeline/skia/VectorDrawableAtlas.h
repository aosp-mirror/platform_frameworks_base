/*
 * Copyright (C) 2017 The Android Open Source Project
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

#pragma once

#include <SkSurface.h>
#include <utils/FatVector.h>
#include <utils/RefBase.h>
#include <utils/Thread.h>
#include <list>
#include <map>

class GrRectanizer;

namespace android {
namespace uirenderer {
namespace skiapipeline {

typedef uintptr_t AtlasKey;

#define INVALID_ATLAS_KEY 0

struct AtlasEntry {
    sk_sp<SkSurface> surface;
    SkRect rect;
    AtlasKey key = INVALID_ATLAS_KEY;
};

/**
 * VectorDrawableAtlas provides offscreen buffers used to draw VD and AnimatedVD.
 * VectorDrawableAtlas can allocate a standalone surface or provide a subrect from a shared surface.
 * VectorDrawableAtlas is owned by the CacheManager and weak pointers are kept by each
 * VectorDrawable that is using it. VectorDrawableAtlas and its surface can be deleted at any time,
 * except during a renderFrame call. VectorDrawable does not contain a pointer to atlas SkSurface
 * nor any coordinates into the atlas, but instead holds a rectangle "id", which is resolved only
 * when drawing. This design makes VectorDrawableAtlas free to move the data internally.
 * At draw time a VectorDrawable may find, that its atlas has been deleted, which will make it
 * draw in a standalone cache surface not part of an atlas. In this case VD won't use
 * VectorDrawableAtlas until the next frame.
 * VectorDrawableAtlas tries to fit VDs in the atlas SkSurface. If there is not enough space in
 * the atlas, VectorDrawableAtlas creates a standalone surface for each VD.
 * When a VectorDrawable is deleted, it invokes VectorDrawableAtlas::releaseEntry, which is keeping
 * track of free spaces and allow to reuse the surface for another VD.
 */
// TODO: Check if not using atlas for AnimatedVD is more efficient.
// TODO: For low memory situations, when there are no paint effects in VD, we may render without an
// TODO: offscreen surface.
class VectorDrawableAtlas : public virtual RefBase {
public:
    enum class StorageMode { allowSharedSurface, disallowSharedSurface };

    explicit VectorDrawableAtlas(size_t surfaceArea,
                                 StorageMode storageMode = StorageMode::allowSharedSurface);

    /**
     * "prepareForDraw" may allocate a new surface if needed. It may schedule to repack the
     * atlas at a later time.
     */
    void prepareForDraw(GrContext* context);

    /**
     * Repack the atlas if needed, by moving used rectangles into a new atlas surface.
     * The goal of repacking is to fix a fragmented atlas.
     */
    void repackIfNeeded(GrContext* context);

    /**
     * Returns true if atlas is fragmented and repack is needed.
     */
    bool isFragmented();

    /**
     * "requestNewEntry" is called by VectorDrawable to allocate a new rectangle area from the atlas
     * or create a standalone surface if atlas is full.
     * On success it returns a non-negative unique id, which can be used later with "getEntry" and
     * "releaseEntry".
     */
    AtlasEntry requestNewEntry(int width, int height, GrContext* context);

    /**
     * "getEntry" extracts coordinates and surface of a previously created rectangle.
     * "atlasKey" is an unique id created by "requestNewEntry". Passing a non-existing "atlasKey" is
     * causing an undefined behaviour.
     * On success it returns a rectangle Id -> may be same or different from "atlasKey" if
     * implementation decides to move the record internally.
     */
    AtlasEntry getEntry(AtlasKey atlasKey);

    /**
     * "releaseEntry" is invoked when a VectorDrawable is deleted. Passing a non-existing "atlasKey"
     * is causing an undefined behaviour. This is the only function in the class that can be
     * invoked from any thread. It will marshal internally to render thread if needed.
     */
    void releaseEntry(AtlasKey atlasKey);

    void setStorageMode(StorageMode mode);

    /**
     * "delayedReleaseEntries" is indirectly invoked by "releaseEntry", when "releaseEntry" is
     * invoked from a non render thread.
     */
    void delayedReleaseEntries();

private:
    struct CacheEntry {
        CacheEntry(const SkRect& newVDrect, const SkRect& newRect,
                   const sk_sp<SkSurface>& newSurface)
                : VDrect(newVDrect), rect(newRect), surface(newSurface) {}

        /**
         * size and position of VectorDrawable into the atlas or in "this.surface"
         */
        SkRect VDrect;

        /**
         * rect allocated in atlas surface or "this.surface". It may be bigger than "VDrect"
         */
        SkRect rect;

        /**
         * this surface is used if atlas is full or VD is too big
         */
        sk_sp<SkSurface> surface;

        /**
         * iterator is used to delete self with a constant complexity (without traversing the list)
         */
        std::list<CacheEntry>::iterator eraseIt;
    };

    /**
     * atlas surface shared by all VDs
     */
    sk_sp<SkSurface> mSurface;

    std::unique_ptr<GrRectanizer> mRectanizer;
    const int mWidth;
    const int mHeight;

    /**
     * "mRects" keeps records only for rectangles used by VDs. List has nice properties: constant
     * complexity to insert and erase and references are not invalidated by insert/erase.
     */
    std::list<CacheEntry> mRects;

    /**
     * Rectangles freed by "releaseEntry" are removed from "mRects" and added to "mFreeRects".
     * "mFreeRects" is using for an index the rectangle area. There could be more than one free
     * rectangle with the same area, which is the reason to use "multimap" instead of "map".
     */
    std::multimap<size_t, SkRect> mFreeRects;

    /**
     * area in atlas used by VectorDrawables (area in standalone surface not counted)
     */
    int mPixelUsedByVDs = 0;

    /**
     * area allocated in mRectanizer
     */
    int mPixelAllocated = 0;

    /**
     * Consecutive times we had to allocate standalone surfaces, because atlas was full.
     */
    int mConsecutiveFailures = 0;

    /**
     * mStorageMode allows using a shared surface to store small vector drawables.
     * Using a shared surface can boost the performance by allowing GL ops to be batched, but may
     * consume more memory.
     */
    StorageMode mStorageMode;

    /**
     * mKeysForRelease is used by releaseEntry implementation to pass atlas keys from an arbitrary
     * calling thread to the render thread.
     */
    std::vector<AtlasKey> mKeysForRelease;

    /**
     * A lock used to protect access to mKeysForRelease.
     */
    Mutex mReleaseKeyLock;

    sk_sp<SkSurface> createSurface(int width, int height, GrContext* context);

    inline bool fitInAtlas(int width, int height) {
        return 2 * width < mWidth && 2 * height < mHeight;
    }

    void repack(GrContext* context);

    static bool compareCacheEntry(const CacheEntry& first, const CacheEntry& second);
};

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
