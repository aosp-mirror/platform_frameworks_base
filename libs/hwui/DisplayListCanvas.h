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

#include "Canvas.h"
#include "CanvasState.h"
#include "DisplayList.h"
#include "SkiaCanvasProxy.h"
#include "RenderNode.h"
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
class DisplayListOp;
class DrawOp;
class RenderNode;
class StateOp;

/**
 * Records drawing commands in a display list for later playback into an OpenGLRenderer.
 */
class ANDROID_API DisplayListCanvas: public Canvas, public CanvasStateClient {
public:
    DisplayListCanvas();
    virtual ~DisplayListCanvas();

    void insertReorderBarrier(bool enableReorder);

    DisplayListData* finishRecording();

// ----------------------------------------------------------------------------
// HWUI Frame state operations
// ----------------------------------------------------------------------------

    void prepareDirty(float left, float top, float right, float bottom);
    void prepare() { prepareDirty(0.0f, 0.0f, width(), height()); }
    bool finish();
    void interrupt();
    void resume();

// ----------------------------------------------------------------------------
// HWUI Canvas state operations
// ----------------------------------------------------------------------------

    void setViewport(int width, int height) { mState.setViewport(width, height); }

    const Rect& getRenderTargetClipBounds() const { return mState.getRenderTargetClipBounds(); }

    bool isCurrentTransformSimple() {
        return mState.currentTransform()->isSimple();
    }

// ----------------------------------------------------------------------------
// HWUI Canvas draw operations
// ----------------------------------------------------------------------------

    // Bitmap-based
    void drawBitmap(const SkBitmap* bitmap, const SkPaint* paint);
    // TODO: move drawPatch() to Canvas.h
    void drawPatch(const SkBitmap& bitmap, const Res_png_9patch* patch,
            float left, float top, float right, float bottom, const SkPaint* paint);

    // Shapes
    void drawRects(const float* rects, int count, const SkPaint* paint);
    void drawRoundRect(CanvasPropertyPrimitive* left, CanvasPropertyPrimitive* top,
                CanvasPropertyPrimitive* right, CanvasPropertyPrimitive* bottom,
                CanvasPropertyPrimitive* rx, CanvasPropertyPrimitive* ry,
                CanvasPropertyPaint* paint);
    void drawCircle(CanvasPropertyPrimitive* x, CanvasPropertyPrimitive* y,
                CanvasPropertyPrimitive* radius, CanvasPropertyPaint* paint);


// ----------------------------------------------------------------------------
// HWUI Canvas draw operations - special
// ----------------------------------------------------------------------------
    void drawLayer(DeferredLayerUpdater* layerHandle, float x, float y);
    void drawRenderNode(RenderNode* renderNode);

    // TODO: rename for consistency
    void callDrawGLFunction(Functor* functor);

    void setHighContrastText(bool highContrastText) {
        mHighContrastText = highContrastText;
    }

// ----------------------------------------------------------------------------
// CanvasStateClient interface
// ----------------------------------------------------------------------------
    virtual void onViewportInitialized() override { }
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) override { }
    virtual GLuint getTargetFbo() const override { return -1; }

// ----------------------------------------------------------------------------
// android/graphics/Canvas interface
// ----------------------------------------------------------------------------
    virtual SkCanvas* asSkCanvas() override;

    virtual void setBitmap(const SkBitmap& bitmap) override {
        LOG_ALWAYS_FATAL("DisplayListCanvas is not backed by a bitmap.");
    }

    virtual bool isOpaque() override { return false; }
    virtual int width() override { return mState.getWidth(); }
    virtual int height() override { return mState.getHeight(); }

// ----------------------------------------------------------------------------
// android/graphics/Canvas state operations
// ----------------------------------------------------------------------------
    // Save (layer)
    virtual int getSaveCount() const override { return mState.getSaveCount(); }
    virtual int save(SkCanvas::SaveFlags flags) override;
    virtual void restore() override;
    virtual void restoreToCount(int saveCount) override;

    virtual int saveLayer(float left, float top, float right, float bottom, const SkPaint* paint,
        SkCanvas::SaveFlags flags) override;
    virtual int saveLayerAlpha(float left, float top, float right, float bottom,
            int alpha, SkCanvas::SaveFlags flags) override {
        SkPaint paint;
        paint.setAlpha(alpha);
        return saveLayer(left, top, right, bottom, &paint, flags);
    }

    // Matrix
    virtual void getMatrix(SkMatrix* outMatrix) const override { mState.getMatrix(outMatrix); }
    virtual void setMatrix(const SkMatrix& matrix) override;
    virtual void setLocalMatrix(const SkMatrix& matrix) override;

    virtual void concat(const SkMatrix& matrix) override;
    virtual void rotate(float degrees) override;
    virtual void scale(float sx, float sy) override;
    virtual void skew(float sx, float sy) override;
    virtual void translate(float dx, float dy) override;

    // Clip
    virtual bool getClipBounds(SkRect* outRect) const override;
    virtual bool quickRejectRect(float left, float top, float right, float bottom) const override;
    virtual bool quickRejectPath(const SkPath& path) const override;

    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op) override;
    virtual bool clipPath(const SkPath* path, SkRegion::Op op) override;
    virtual bool clipRegion(const SkRegion* region, SkRegion::Op op) override;

    // Misc
    virtual SkDrawFilter* getDrawFilter() override { return mDrawFilter.get(); }
    virtual void setDrawFilter(SkDrawFilter* filter) override;

// ----------------------------------------------------------------------------
// android/graphics/Canvas draw operations
// ----------------------------------------------------------------------------
    virtual void drawColor(int color, SkXfermode::Mode mode) override;
    virtual void drawPaint(const SkPaint& paint) override;

    // Geometry
    virtual void drawPoint(float x, float y, const SkPaint& paint) override {
        float points[2] = { x, y };
        drawPoints(points, 2, paint);
    }
    virtual void drawPoints(const float* points, int count, const SkPaint& paint) override;
    virtual void drawLine(float startX, float startY, float stopX, float stopY,
            const SkPaint& paint) override {
        float points[4] = { startX, startY, stopX, stopY };
        drawLines(points, 4, paint);
    }
    virtual void drawLines(const float* points, int count, const SkPaint& paint) override;
    virtual void drawRect(float left, float top, float right, float bottom, const SkPaint& paint) override;
    virtual void drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint& paint) override;
    virtual void drawCircle(float x, float y, float radius, const SkPaint& paint) override;
    virtual void drawOval(float left, float top, float right, float bottom, const SkPaint& paint) override;
    virtual void drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint& paint) override;
    virtual void drawPath(const SkPath& path, const SkPaint& paint) override;
    virtual void drawVertices(SkCanvas::VertexMode vertexMode, int vertexCount,
            const float* verts, const float* tex, const int* colors,
            const uint16_t* indices, int indexCount, const SkPaint& paint) override
        { /* DisplayListCanvas does not support drawVertices(); ignore */ }

    // Bitmap-based
    virtual void drawBitmap(const SkBitmap& bitmap, float left, float top, const SkPaint* paint) override;
    virtual void drawBitmap(const SkBitmap& bitmap, const SkMatrix& matrix,
                            const SkPaint* paint) override;
    virtual void drawBitmap(const SkBitmap& bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint) override;
    virtual void drawBitmapMesh(const SkBitmap& bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint) override;

    // Text
    virtual void drawText(const uint16_t* glyphs, const float* positions, int count,
            const SkPaint& paint, float x, float y, float boundsLeft, float boundsTop,
            float boundsRight, float boundsBottom, float totalAdvance) override;
    virtual void drawPosText(const uint16_t* text, const float* positions, int count,
            int posCount, const SkPaint& paint) override;
    virtual void drawTextOnPath(const uint16_t* glyphs, int count, const SkPath& path,
            float hOffset, float vOffset, const SkPaint& paint) override;
    virtual bool drawTextAbsolutePos() const override { return false; }


private:

    CanvasState mState;
    std::unique_ptr<SkiaCanvasProxy> mSkiaCanvasProxy;

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

    void refBitmapsInShader(const SkShader* shader);

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

        // The points/verbs within the path are refcounted so this copy operation
        // is inexpensive and maintains the generationID of the original path.
        const SkPath* cachedPath = new SkPath(*path);
        mDisplayListData->pathResources.add(cachedPath);
        return cachedPath;
    }

    inline const SkPaint* refPaint(const SkPaint* paint) {
        if (!paint) return nullptr;

        // If there is a draw filter apply it here and store the modified paint
        // so that we don't need to modify the paint every time we access it.
        SkTLazy<SkPaint> filteredPaint;
        if (mDrawFilter.get()) {
            filteredPaint.set(*paint);
            mDrawFilter->filter(filteredPaint.get(), SkDrawFilter::kPaint_Type);
            paint = filteredPaint.get();
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
            refBitmapsInShader(cachedPaint->getShader());
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

    inline const SkBitmap* refBitmap(const SkBitmap& bitmap) {
        // Note that this assumes the bitmap is immutable. There are cases this won't handle
        // correctly, such as creating the bitmap from scratch, drawing with it, changing its
        // contents, and drawing again. The only fix would be to always copy it the first time,
        // which doesn't seem worth the extra cycles for this unlikely case.
        SkBitmap* localBitmap = new (alloc()) SkBitmap(bitmap);
        alloc().autoDestroy(localBitmap);
        mDisplayListData->bitmapResources.push_back(localBitmap);
        return localBitmap;
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

}; // class DisplayListCanvas

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_DISPLAY_LIST_RENDERER_H
