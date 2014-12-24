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

#ifndef ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
#define ANDROID_HWUI_DISPLAY_LIST_RENDERER_H

#include <SkDrawFilter.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkPath.h>
#include <SkRegion.h>
#include <SkTLazy.h>
#include <cutils/compiler.h>

#include "CanvasState.h"
#include "DisplayList.h"
#include "DisplayListLogBuffer.h"
#include "RenderNode.h"
#include "Renderer.h"
#include "ResourceCache.h"

namespace android {
namespace uirenderer {

///////////////////////////////////////////////////////////////////////////////
// Defines
///////////////////////////////////////////////////////////////////////////////

// Debug
#if DEBUG_DISPLAY_LIST
    #define DISPLAY_LIST_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define DISPLAY_LIST_LOGD(...)
#endif

///////////////////////////////////////////////////////////////////////////////
// Display list
///////////////////////////////////////////////////////////////////////////////

class DeferredDisplayList;
class DeferredLayerUpdater;
class DisplayListRenderer;
class DisplayListOp;
class DisplayListRenderer;
class DrawOp;
class RenderNode;
class StateOp;

/**
 * Records drawing commands in a display list for later playback into an OpenGLRenderer.
 */
class ANDROID_API DisplayListRenderer: public Renderer, public CanvasStateClient {
public:
    DisplayListRenderer();
    virtual ~DisplayListRenderer();

    void insertReorderBarrier(bool enableReorder);

    DisplayListData* finishRecording();

// ----------------------------------------------------------------------------
// Frame state operations
// ----------------------------------------------------------------------------
    virtual void prepareDirty(float left, float top, float right,
            float bottom, bool opaque) override;
    virtual void prepare(bool opaque) override {
        prepareDirty(0.0f, 0.0f, mState.getWidth(), mState.getHeight(), opaque);
    }
    virtual bool finish() override;
    virtual void interrupt();
    virtual void resume();

// ----------------------------------------------------------------------------
// Canvas state operations
// ----------------------------------------------------------------------------
    virtual void setViewport(int width, int height) override { mState.setViewport(width, height); }

    // Save (layer)
    virtual int getSaveCount() const override { return mState.getSaveCount(); }
    virtual int save(int flags) override;
    virtual void restore() override;
    virtual void restoreToCount(int saveCount) override;
    virtual int saveLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags) override;

    // Matrix
    virtual void getMatrix(SkMatrix* outMatrix) const override { mState.getMatrix(outMatrix); }

    virtual void translate(float dx, float dy, float dz = 0.0f) override;
    virtual void rotate(float degrees) override;
    virtual void scale(float sx, float sy) override;
    virtual void skew(float sx, float sy) override;

    virtual void setMatrix(const SkMatrix& matrix) override;
    virtual void concatMatrix(const SkMatrix& matrix) override;

    // Clip
    virtual bool clipRect(float left, float top, float right, float bottom,
            SkRegion::Op op) override;
    virtual bool clipPath(const SkPath* path, SkRegion::Op op) override;
    virtual bool clipRegion(const SkRegion* region, SkRegion::Op op) override;

    // Misc
    virtual void setDrawFilter(SkDrawFilter* filter) override;
    virtual const Rect& getLocalClipBounds() const override { return mState.getLocalClipBounds(); }
    const Rect& getRenderTargetClipBounds() const { return mState.getRenderTargetClipBounds(); }
    virtual bool quickRejectConservative(float left, float top,
            float right, float bottom) const override {
        return mState.quickRejectConservative(left, top, right, bottom);
    }

    bool isCurrentTransformSimple() {
        return mState.currentTransform()->isSimple();
    }

// ----------------------------------------------------------------------------
// Canvas draw operations
// ----------------------------------------------------------------------------
    virtual void drawColor(int color, SkXfermode::Mode mode) override;

    // Bitmap-based
    virtual void drawBitmap(const SkBitmap* bitmap, const SkPaint* paint) override;
    virtual void drawBitmap(const SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint) override;
    virtual void drawBitmapData(const SkBitmap* bitmap, const SkPaint* paint) override;
    virtual void drawBitmapMesh(const SkBitmap* bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint) override;
    virtual void drawPatch(const SkBitmap* bitmap, const Res_png_9patch* patch,
            float left, float top, float right, float bottom, const SkPaint* paint) override;

    // Shapes
    virtual void drawRect(float left, float top, float right, float bottom,
            const SkPaint* paint) override;
    virtual void drawRects(const float* rects, int count, const SkPaint* paint) override;
    virtual void drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint* paint) override;
    virtual void drawRoundRect(CanvasPropertyPrimitive* left, CanvasPropertyPrimitive* top,
                CanvasPropertyPrimitive* right, CanvasPropertyPrimitive* bottom,
                CanvasPropertyPrimitive* rx, CanvasPropertyPrimitive* ry,
                CanvasPropertyPaint* paint);
    virtual void drawCircle(float x, float y, float radius, const SkPaint* paint) override;
    virtual void drawCircle(CanvasPropertyPrimitive* x, CanvasPropertyPrimitive* y,
                CanvasPropertyPrimitive* radius, CanvasPropertyPaint* paint);
    virtual void drawOval(float left, float top, float right, float bottom,
            const SkPaint* paint) override;
    virtual void drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint* paint) override;
    virtual void drawPath(const SkPath* path, const SkPaint* paint) override;
    virtual void drawLines(const float* points, int count, const SkPaint* paint) override;
    virtual void drawPoints(const float* points, int count, const SkPaint* paint) override;

    // Text
    virtual void drawText(const char* text, int bytesCount, int count, float x, float y,
            const float* positions, const SkPaint* paint, float totalAdvance, const Rect& bounds,
            DrawOpMode drawOpMode = kDrawOpMode_Immediate) override;
    virtual void drawTextOnPath(const char* text, int bytesCount, int count, const SkPath* path,
            float hOffset, float vOffset, const SkPaint* paint) override;
    virtual void drawPosText(const char* text, int bytesCount, int count,
            const float* positions, const SkPaint* paint) override;

// ----------------------------------------------------------------------------
// Canvas draw operations - special
// ----------------------------------------------------------------------------
    virtual void drawLayer(DeferredLayerUpdater* layerHandle, float x, float y);
    virtual void drawRenderNode(RenderNode* renderNode, Rect& dirty, int32_t replayFlags) override;

    // TODO: rename for consistency
    virtual void callDrawGLFunction(Functor* functor, Rect& dirty) override;

    void setHighContrastText(bool highContrastText) {
        mHighContrastText = highContrastText;
    }

// ----------------------------------------------------------------------------
// CanvasState callbacks
// ----------------------------------------------------------------------------
    virtual void onViewportInitialized() override { }
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) override { }
    virtual GLuint onGetTargetFbo() const override { return -1; }

private:

    CanvasState mState;

    enum DeferredBarrierType {
        kBarrier_None,
        kBarrier_InOrder,
        kBarrier_OutOfOrder,
    };

    void flushRestoreToCount();
    void flushTranslate();
    void flushReorderBarrier();

    LinearAllocator& alloc() { return mDisplayListData->allocator; }

    // Each method returns final index of op
    size_t addOpAndUpdateChunk(DisplayListOp* op);
    // flushes any deferred operations, and appends the op
    size_t flushAndAddOp(DisplayListOp* op);

    size_t addStateOp(StateOp* op);
    size_t addDrawOp(DrawOp* op);
    size_t addRenderNodeOp(DrawRenderNodeOp* op);


    template<class T>
    inline const T* refBuffer(const T* srcBuffer, int32_t count) {
        if (!srcBuffer) return nullptr;

        T* dstBuffer = (T*) mDisplayListData->allocator.alloc(count * sizeof(T));
        memcpy(dstBuffer, srcBuffer, count * sizeof(T));
        return dstBuffer;
    }

    inline char* refText(const char* text, size_t byteLength) {
        return (char*) refBuffer<uint8_t>((uint8_t*)text, byteLength);
    }

    inline const SkPath* refPath(const SkPath* path) {
        if (!path) return nullptr;

        const SkPath* cachedPath = mPathMap.valueFor(path);
        if (cachedPath == nullptr || cachedPath->getGenerationID() != path->getGenerationID()) {
            SkPath* newPathCopy = new SkPath(*path);
            newPathCopy->setSourcePath(path);
            cachedPath = newPathCopy;
            std::unique_ptr<const SkPath> copy(newPathCopy);
            mDisplayListData->paths.push_back(std::move(copy));

            // replaceValueFor() performs an add if the entry doesn't exist
            mPathMap.replaceValueFor(path, cachedPath);
        }
        if (mDisplayListData->sourcePaths.indexOf(path) < 0) {
            mResourceCache.incrementRefcount(path);
            mDisplayListData->sourcePaths.add(path);
        }
        return cachedPath;
    }

    inline const SkPaint* refPaint(const SkPaint* paint) {
        if (!paint) return nullptr;

        // If there is a draw filter apply it here and store the modified paint
        // so that we don't need to modify the paint every time we access it.
        SkTLazy<SkPaint> filteredPaint;
        if (mDrawFilter.get()) {
            paint = filteredPaint.init();
            mDrawFilter->filter(filteredPaint.get(), SkDrawFilter::kPaint_Type);
        }

        // compute the hash key for the paint and check the cache.
        const uint32_t key = paint->getHash();
        const SkPaint* cachedPaint = mPaintMap.valueFor(key);
        // In the unlikely event that 2 unique paints have the same hash we do a
        // object equality check to ensure we don't erroneously dedup them.
        if (cachedPaint == nullptr || *cachedPaint != *paint) {
            cachedPaint = new SkPaint(*paint);
            std::unique_ptr<const SkPaint> copy(cachedPaint);
            mDisplayListData->paints.push_back(std::move(copy));

            // replaceValueFor() performs an add if the entry doesn't exist
            mPaintMap.replaceValueFor(key, cachedPaint);
        }

        return cachedPaint;
    }

    inline SkPaint* copyPaint(const SkPaint* paint) {
        if (!paint) return nullptr;

        SkPaint* returnPaint = new SkPaint(*paint);
        std::unique_ptr<const SkPaint> copy(returnPaint);
        mDisplayListData->paints.push_back(std::move(copy));

        return returnPaint;
    }

    inline const SkRegion* refRegion(const SkRegion* region) {
        if (!region) {
            return region;
        }

        const SkRegion* cachedRegion = mRegionMap.valueFor(region);
        // TODO: Add generation ID to SkRegion
        if (cachedRegion == nullptr) {
            std::unique_ptr<const SkRegion> copy(new SkRegion(*region));
            cachedRegion = copy.get();
            mDisplayListData->regions.push_back(std::move(copy));

            // replaceValueFor() performs an add if the entry doesn't exist
            mRegionMap.replaceValueFor(region, cachedRegion);
        }

        return cachedRegion;
    }

    inline const SkBitmap* refBitmap(const SkBitmap* bitmap) {
        // Note that this assumes the bitmap is immutable. There are cases this won't handle
        // correctly, such as creating the bitmap from scratch, drawing with it, changing its
        // contents, and drawing again. The only fix would be to always copy it the first time,
        // which doesn't seem worth the extra cycles for this unlikely case.
        mDisplayListData->bitmapResources.add(bitmap);
        mResourceCache.incrementRefcount(bitmap);
        return bitmap;
    }

    inline const SkBitmap* refBitmapData(const SkBitmap* bitmap) {
        mDisplayListData->ownedBitmapResources.add(bitmap);
        mResourceCache.incrementRefcount(bitmap);
        return bitmap;
    }

    inline const Res_png_9patch* refPatch(const Res_png_9patch* patch) {
        mDisplayListData->patchResources.add(patch);
        mResourceCache.incrementRefcount(patch);
        return patch;
    }

    DefaultKeyedVector<uint32_t, const SkPaint*> mPaintMap;
    DefaultKeyedVector<const SkPath*, const SkPath*> mPathMap;
    DefaultKeyedVector<const SkRegion*, const SkRegion*> mRegionMap;

    ResourceCache& mResourceCache;
    DisplayListData* mDisplayListData;

    float mTranslateX;
    float mTranslateY;
    bool mHasDeferredTranslate;
    DeferredBarrierType mDeferredBarrierType;
    bool mHighContrastText;

    int mRestoreSaveCount;

    SkAutoTUnref<SkDrawFilter> mDrawFilter;

    friend class RenderNode;

}; // class DisplayListRenderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
