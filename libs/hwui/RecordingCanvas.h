/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef ANDROID_HWUI_RECORDING_CANVAS_H
#define ANDROID_HWUI_RECORDING_CANVAS_H

#include "CanvasState.h"
#include "DisplayList.h"
#include "ResourceCache.h"
#include "SkiaCanvasProxy.h"
#include "Snapshot.h"
#include "hwui/Canvas.h"
#include "utils/LinearAllocator.h"
#include "utils/Macros.h"
#include "utils/NinePatch.h"

#include <SkDrawFilter.h>
#include <SkPaint.h>
#include <SkTLazy.h>

#include <vector>

namespace android {
namespace uirenderer {

struct ClipBase;
class DeferredLayerUpdater;
struct RecordedOp;

class ANDROID_API RecordingCanvas: public Canvas, public CanvasStateClient {
    enum class DeferredBarrierType {
        None,
        InOrder,
        OutOfOrder,
    };
public:
    RecordingCanvas(size_t width, size_t height);
    virtual ~RecordingCanvas();

    virtual void resetRecording(int width, int height) override;
    virtual WARN_UNUSED_RESULT DisplayList* finishRecording() override;
// ----------------------------------------------------------------------------
// MISC HWUI OPERATIONS - TODO: CATEGORIZE
// ----------------------------------------------------------------------------
    virtual void insertReorderBarrier(bool enableReorder) override;

    virtual void drawLayer(DeferredLayerUpdater* layerHandle) override;
    virtual void drawRenderNode(RenderNode* renderNode) override;
    virtual void callDrawGLFunction(Functor* functor,
            GlFunctorLifecycleListener* listener) override;

// ----------------------------------------------------------------------------
// CanvasStateClient interface
// ----------------------------------------------------------------------------
    virtual void onViewportInitialized() override;
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) override;
    virtual GLuint getTargetFbo() const override { return -1; }

// ----------------------------------------------------------------------------
// HWUI Canvas draw operations
// ----------------------------------------------------------------------------

    virtual void drawRoundRect(CanvasPropertyPrimitive* left, CanvasPropertyPrimitive* top,
            CanvasPropertyPrimitive* right, CanvasPropertyPrimitive* bottom,
            CanvasPropertyPrimitive* rx, CanvasPropertyPrimitive* ry,
            CanvasPropertyPaint* paint) override;
    virtual void drawCircle(CanvasPropertyPrimitive* x, CanvasPropertyPrimitive* y,
            CanvasPropertyPrimitive* radius, CanvasPropertyPaint* paint) override;

// ----------------------------------------------------------------------------
// android/graphics/Canvas interface
// ----------------------------------------------------------------------------
    virtual SkCanvas* asSkCanvas() override;

    virtual void setBitmap(const SkBitmap& bitmap) override {
        LOG_ALWAYS_FATAL("RecordingCanvas is not backed by a bitmap.");
    }

    virtual bool isOpaque() override { return false; }
    virtual int width() override { return mState.getWidth(); }
    virtual int height() override { return mState.getHeight(); }

    virtual void setHighContrastText(bool highContrastText) override {
        mHighContrastText = highContrastText;
    }
    virtual bool isHighContrastText() override { return mHighContrastText; }

// ----------------------------------------------------------------------------
// android/graphics/Canvas state operations
// ----------------------------------------------------------------------------
    // Save (layer)
    virtual int getSaveCount() const override { return mState.getSaveCount(); }
    virtual int save(SaveFlags::Flags flags) override;
    virtual void restore() override;
    virtual void restoreToCount(int saveCount) override;

    virtual int saveLayer(float left, float top, float right, float bottom, const SkPaint* paint,
        SaveFlags::Flags flags) override;
    virtual int saveLayerAlpha(float left, float top, float right, float bottom,
            int alpha, SaveFlags::Flags flags) override {
        SkPaint paint;
        paint.setAlpha(alpha);
        return saveLayer(left, top, right, bottom, &paint, flags);
    }

    // Matrix
    virtual void getMatrix(SkMatrix* outMatrix) const override { mState.getMatrix(outMatrix); }
    virtual void setMatrix(const SkMatrix& matrix) override { mState.setMatrix(matrix); }

    virtual void concat(const SkMatrix& matrix) override { mState.concatMatrix(matrix); }
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
    virtual void setDrawFilter(SkDrawFilter* filter) override {
        mDrawFilter.reset(SkSafeRef(filter));
    }

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
    virtual void drawPoints(const float* points, int floatCount, const SkPaint& paint) override;
    virtual void drawLine(float startX, float startY, float stopX, float stopY,
            const SkPaint& paint) override {
        float points[4] = { startX, startY, stopX, stopY };
        drawLines(points, 4, paint);
    }
    virtual void drawLines(const float* points, int floatCount, const SkPaint& paint) override;
    virtual void drawRect(float left, float top, float right, float bottom, const SkPaint& paint) override;
    virtual void drawRegion(const SkRegion& region, const SkPaint& paint) override;
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
        { /* RecordingCanvas does not support drawVertices(); ignore */ }

    virtual void drawVectorDrawable(VectorDrawableRoot* tree) override;

    // Bitmap-based
    virtual void drawBitmap(const SkBitmap& bitmap, float left, float top, const SkPaint* paint) override;
    virtual void drawBitmap(const SkBitmap& bitmap, const SkMatrix& matrix,
                            const SkPaint* paint) override;
    virtual void drawBitmap(const SkBitmap& bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint) override;
    virtual void drawBitmapMesh(const SkBitmap& bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint) override;
    virtual void drawNinePatch(const SkBitmap& bitmap, const android::Res_png_9patch& chunk,
            float dstLeft, float dstTop, float dstRight, float dstBottom,
            const SkPaint* paint) override;

    // Text
    virtual bool drawTextAbsolutePos() const override { return false; }

protected:
    virtual void drawGlyphs(const uint16_t* text, const float* positions, int count,
            const SkPaint& paint, float x, float y,
            float boundsLeft, float boundsTop, float boundsRight, float boundsBottom,
            float totalAdvance) override;
    virtual void drawGlyphsOnPath(const uint16_t* glyphs, int count, const SkPath& path,
            float hOffset, float vOffset, const SkPaint& paint) override;

private:
    const ClipBase* getRecordedClip() {
        return mState.writableSnapshot()->mutateClipArea().serializeClip(alloc());
    }

    void drawBitmap(const SkBitmap* bitmap, const SkPaint* paint);
    void drawSimpleRects(const float* rects, int vertexCount, const SkPaint* paint);


    int addOp(RecordedOp* op);
// ----------------------------------------------------------------------------
// lazy object copy
// ----------------------------------------------------------------------------
    LinearAllocator& alloc() { return mDisplayList->allocator; }

    void refBitmapsInShader(const SkShader* shader);

    template<class T>
    inline const T* refBuffer(const T* srcBuffer, int32_t count) {
        if (!srcBuffer) return nullptr;

        T* dstBuffer = (T*) mDisplayList->allocator.alloc<T>(count * sizeof(T));
        memcpy(dstBuffer, srcBuffer, count * sizeof(T));
        return dstBuffer;
    }

    inline const SkPath* refPath(const SkPath* path) {
        if (!path) return nullptr;

        // The points/verbs within the path are refcounted so this copy operation
        // is inexpensive and maintains the generationID of the original path.
        const SkPath* cachedPath = new SkPath(*path);
        mDisplayList->pathResources.push_back(cachedPath);
        return cachedPath;
    }

    /**
     * Returns a RenderThread-safe, const copy of the SkPaint parameter passed in
     * (with deduping based on paint hash / equality check)
     */
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
            mDisplayList->paints.emplace_back(cachedPaint);
            // replaceValueFor() performs an add if the entry doesn't exist
            mPaintMap.replaceValueFor(key, cachedPaint);
            refBitmapsInShader(cachedPaint->getShader());
        }

        return cachedPaint;
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
            mDisplayList->regions.push_back(std::move(copy));

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
        SkBitmap* localBitmap = alloc().create<SkBitmap>(bitmap);
        mDisplayList->bitmapResources.push_back(localBitmap);
        return localBitmap;
    }

    inline const Res_png_9patch* refPatch(const Res_png_9patch* patch) {
        mDisplayList->patchResources.push_back(patch);
        mResourceCache.incrementRefcount(patch);
        return patch;
    }

    DefaultKeyedVector<uint32_t, const SkPaint*> mPaintMap;
    DefaultKeyedVector<const SkPath*, const SkPath*> mPathMap;
    DefaultKeyedVector<const SkRegion*, const SkRegion*> mRegionMap;

    CanvasState mState;
    std::unique_ptr<SkiaCanvasProxy> mSkiaCanvasProxy;
    ResourceCache& mResourceCache;
    DeferredBarrierType mDeferredBarrierType = DeferredBarrierType::None;
    const ClipBase* mDeferredBarrierClip = nullptr;
    DisplayList* mDisplayList = nullptr;
    bool mHighContrastText = false;
    SkAutoTUnref<SkDrawFilter> mDrawFilter;
}; // class RecordingCanvas

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_RECORDING_CANVAS_H
