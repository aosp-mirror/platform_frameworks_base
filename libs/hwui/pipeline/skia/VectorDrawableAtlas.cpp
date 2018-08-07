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

#include "VectorDrawableAtlas.h"

#include <GrRectanizer_pow2.h>
#include <SkCanvas.h>
#include <cmath>
#include "renderthread/RenderProxy.h"
#include "renderthread/RenderThread.h"
#include "utils/TraceUtils.h"

namespace android {
namespace uirenderer {
namespace skiapipeline {

VectorDrawableAtlas::VectorDrawableAtlas(size_t surfaceArea, StorageMode storageMode)
        : mWidth((int)std::sqrt(surfaceArea))
        , mHeight((int)std::sqrt(surfaceArea))
        , mStorageMode(storageMode) {}

void VectorDrawableAtlas::prepareForDraw(GrContext* context) {
    if (StorageMode::allowSharedSurface == mStorageMode) {
        if (!mSurface) {
            mSurface = createSurface(mWidth, mHeight, context);
            mRectanizer = std::make_unique<GrRectanizerPow2>(mWidth, mHeight);
            mPixelUsedByVDs = 0;
            mPixelAllocated = 0;
            mConsecutiveFailures = 0;
            mFreeRects.clear();
        } else {
            if (isFragmented()) {
                // Invoke repack outside renderFrame to avoid jank.
                renderthread::RenderProxy::repackVectorDrawableAtlas();
            }
        }
    }
}

#define MAX_CONSECUTIVE_FAILURES 5
#define MAX_UNUSED_RATIO 2.0f

bool VectorDrawableAtlas::isFragmented() {
    return mConsecutiveFailures > MAX_CONSECUTIVE_FAILURES &&
           mPixelUsedByVDs * MAX_UNUSED_RATIO < mPixelAllocated;
}

void VectorDrawableAtlas::repackIfNeeded(GrContext* context) {
    // We repackage when atlas failed to allocate space MAX_CONSECUTIVE_FAILURES consecutive
    // times and the atlas allocated pixels are at least MAX_UNUSED_RATIO times higher than pixels
    // used by atlas VDs.
    if (isFragmented() && mSurface) {
        repack(context);
    }
}

// compare to CacheEntry objects based on VD area.
bool VectorDrawableAtlas::compareCacheEntry(const CacheEntry& first, const CacheEntry& second) {
    return first.VDrect.width() * first.VDrect.height() <
           second.VDrect.width() * second.VDrect.height();
}

void VectorDrawableAtlas::repack(GrContext* context) {
    ATRACE_CALL();
    sk_sp<SkSurface> newSurface;
    SkCanvas* canvas = nullptr;
    if (StorageMode::allowSharedSurface == mStorageMode) {
        newSurface = createSurface(mWidth, mHeight, context);
        if (!newSurface) {
            return;
        }
        canvas = newSurface->getCanvas();
        canvas->clear(SK_ColorTRANSPARENT);
        mRectanizer = std::make_unique<GrRectanizerPow2>(mWidth, mHeight);
    } else {
        if (!mSurface) {
            return;  // nothing to repack
        }
        mRectanizer.reset();
    }
    mFreeRects.clear();
    SkImage* sourceImageAtlas = nullptr;
    if (mSurface) {
        sourceImageAtlas = mSurface->makeImageSnapshot().get();
    }

    // Sort the list by VD size, which allows for the smallest VDs to get first in the atlas.
    // Sorting is safe, because it does not affect iterator validity.
    if (mRects.size() <= 100) {
        mRects.sort(compareCacheEntry);
    }

    for (CacheEntry& entry : mRects) {
        SkRect currentVDRect = entry.VDrect;
        SkImage* sourceImage;  // copy either from the atlas or from a standalone surface
        if (entry.surface) {
            if (!fitInAtlas(currentVDRect.width(), currentVDRect.height())) {
                continue;  // don't even try to repack huge VD
            }
            sourceImage = entry.surface->makeImageSnapshot().get();
        } else {
            sourceImage = sourceImageAtlas;
        }
        size_t VDRectArea = currentVDRect.width() * currentVDRect.height();
        SkIPoint16 pos;
        if (canvas && mRectanizer->addRect(currentVDRect.width(), currentVDRect.height(), &pos)) {
            SkRect newRect =
                    SkRect::MakeXYWH(pos.fX, pos.fY, currentVDRect.width(), currentVDRect.height());
            canvas->drawImageRect(sourceImage, currentVDRect, newRect, nullptr);
            entry.VDrect = newRect;
            entry.rect = newRect;
            if (entry.surface) {
                // A rectangle moved from a standalone surface to the atlas.
                entry.surface = nullptr;
                mPixelUsedByVDs += VDRectArea;
            }
        } else {
            // Repack failed for this item. If it is not already, store it in a standalone
            // surface.
            if (!entry.surface) {
                // A rectangle moved from an atlas to a standalone surface.
                mPixelUsedByVDs -= VDRectArea;
                SkRect newRect = SkRect::MakeWH(currentVDRect.width(), currentVDRect.height());
                entry.surface = createSurface(newRect.width(), newRect.height(), context);
                auto tempCanvas = entry.surface->getCanvas();
                tempCanvas->clear(SK_ColorTRANSPARENT);
                tempCanvas->drawImageRect(sourceImageAtlas, currentVDRect, newRect, nullptr);
                entry.VDrect = newRect;
                entry.rect = newRect;
            }
        }
    }
    mPixelAllocated = mPixelUsedByVDs;
    context->flush();
    mSurface = newSurface;
    mConsecutiveFailures = 0;
}

AtlasEntry VectorDrawableAtlas::requestNewEntry(int width, int height, GrContext* context) {
    AtlasEntry result;
    if (width <= 0 || height <= 0) {
        return result;
    }

    if (mSurface) {
        const size_t area = width * height;

        // Use a rectanizer to allocate unused space from the atlas surface.
        bool notTooBig = fitInAtlas(width, height);
        SkIPoint16 pos;
        if (notTooBig && mRectanizer->addRect(width, height, &pos)) {
            mPixelUsedByVDs += area;
            mPixelAllocated += area;
            result.rect = SkRect::MakeXYWH(pos.fX, pos.fY, width, height);
            result.surface = mSurface;
            auto eraseIt = mRects.emplace(mRects.end(), result.rect, result.rect, nullptr);
            CacheEntry* entry = &(*eraseIt);
            entry->eraseIt = eraseIt;
            result.key = reinterpret_cast<AtlasKey>(entry);
            mConsecutiveFailures = 0;
            return result;
        }

        // Try to reuse atlas memory from rectangles freed by "releaseEntry".
        auto freeRectIt = mFreeRects.lower_bound(area);
        while (freeRectIt != mFreeRects.end()) {
            SkRect& freeRect = freeRectIt->second;
            if (freeRect.width() >= width && freeRect.height() >= height) {
                result.rect = SkRect::MakeXYWH(freeRect.fLeft, freeRect.fTop, width, height);
                result.surface = mSurface;
                auto eraseIt = mRects.emplace(mRects.end(), result.rect, freeRect, nullptr);
                CacheEntry* entry = &(*eraseIt);
                entry->eraseIt = eraseIt;
                result.key = reinterpret_cast<AtlasKey>(entry);
                mPixelUsedByVDs += area;
                mFreeRects.erase(freeRectIt);
                mConsecutiveFailures = 0;
                return result;
            }
            freeRectIt++;
        }

        if (notTooBig && mConsecutiveFailures <= MAX_CONSECUTIVE_FAILURES) {
            mConsecutiveFailures++;
        }
    }

    // Allocate a surface for a rectangle that is too big or if atlas is full.
    if (nullptr != context) {
        result.rect = SkRect::MakeWH(width, height);
        result.surface = createSurface(width, height, context);
        auto eraseIt = mRects.emplace(mRects.end(), result.rect, result.rect, result.surface);
        CacheEntry* entry = &(*eraseIt);
        entry->eraseIt = eraseIt;
        result.key = reinterpret_cast<AtlasKey>(entry);
    }

    return result;
}

AtlasEntry VectorDrawableAtlas::getEntry(AtlasKey atlasKey) {
    AtlasEntry result;
    if (INVALID_ATLAS_KEY != atlasKey) {
        CacheEntry* entry = reinterpret_cast<CacheEntry*>(atlasKey);
        result.rect = entry->VDrect;
        result.surface = entry->surface;
        if (!result.surface) {
            result.surface = mSurface;
        }
        result.key = atlasKey;
    }
    return result;
}

void VectorDrawableAtlas::releaseEntry(AtlasKey atlasKey) {
    if (INVALID_ATLAS_KEY != atlasKey) {
        if (!renderthread::RenderThread::isCurrent()) {
            {
                AutoMutex _lock(mReleaseKeyLock);
                mKeysForRelease.push_back(atlasKey);
            }
            // invoke releaseEntry on the renderthread
            renderthread::RenderProxy::releaseVDAtlasEntries();
            return;
        }
        CacheEntry* entry = reinterpret_cast<CacheEntry*>(atlasKey);
        if (!entry->surface) {
            // Store freed atlas rectangles in "mFreeRects" and try to reuse them later, when atlas
            // is full.
            SkRect& removedRect = entry->rect;
            size_t rectArea = removedRect.width() * removedRect.height();
            mFreeRects.emplace(rectArea, removedRect);
            SkRect& removedVDRect = entry->VDrect;
            size_t VDRectArea = removedVDRect.width() * removedVDRect.height();
            mPixelUsedByVDs -= VDRectArea;
            mConsecutiveFailures = 0;
        }
        auto eraseIt = entry->eraseIt;
        mRects.erase(eraseIt);
    }
}

void VectorDrawableAtlas::delayedReleaseEntries() {
    AutoMutex _lock(mReleaseKeyLock);
    for (auto key : mKeysForRelease) {
        releaseEntry(key);
    }
    mKeysForRelease.clear();
}

sk_sp<SkSurface> VectorDrawableAtlas::createSurface(int width, int height, GrContext* context) {
#ifndef ANDROID_ENABLE_LINEAR_BLENDING
    sk_sp<SkColorSpace> colorSpace = nullptr;
#else
    sk_sp<SkColorSpace> colorSpace = SkColorSpace::MakeSRGB();
#endif
    SkImageInfo info = SkImageInfo::MakeN32(width, height, kPremul_SkAlphaType, colorSpace);
    // This must have a top-left origin so that calls to surface->canvas->writePixels
    // performs a basic texture upload instead of a more complex drawing operation
    return SkSurface::MakeRenderTarget(context, SkBudgeted::kYes, info, 0, kTopLeft_GrSurfaceOrigin,
                                       nullptr);
}

void VectorDrawableAtlas::setStorageMode(StorageMode mode) {
    mStorageMode = mode;
    if (StorageMode::disallowSharedSurface == mStorageMode && mSurface) {
        mSurface.reset();
        mRectanizer.reset();
        mFreeRects.clear();
    }
}

} /* namespace skiapipeline */
} /* namespace uirenderer */
} /* namespace android */
