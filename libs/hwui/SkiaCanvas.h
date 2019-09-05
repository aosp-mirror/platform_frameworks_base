/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "CanvasProperty.h"
#include "DeferredLayerUpdater.h"
#include "RenderNode.h"
#include "VectorDrawable.h"
#include "hwui/Canvas.h"

#include <SkCanvas.h>

#include <cassert>
#include <optional>

namespace android {

// Holds an SkCanvas reference plus additional native data.
class SkiaCanvas : public Canvas {
public:
    explicit SkiaCanvas(const SkBitmap& bitmap);

    /**
     *  Create a new SkiaCanvas.
     *
     *  @param canvas SkCanvas to handle calls made to this SkiaCanvas. Must
     *      not be NULL. This constructor does not take ownership, so the caller
     *      must guarantee that it remains valid while the SkiaCanvas is valid.
     */
    explicit SkiaCanvas(SkCanvas* canvas);

    virtual ~SkiaCanvas();

    virtual SkCanvas* asSkCanvas() override { return mCanvas; }

    virtual void resetRecording(int width, int height,
                                uirenderer::RenderNode* renderNode) override {
        LOG_ALWAYS_FATAL("SkiaCanvas cannot be reset as a recording canvas");
    }

    virtual uirenderer::DisplayList* finishRecording() override {
        LOG_ALWAYS_FATAL("SkiaCanvas does not produce a DisplayList");
        return nullptr;
    }
    virtual void insertReorderBarrier(bool enableReorder) override {
        LOG_ALWAYS_FATAL("SkiaCanvas does not support reordering barriers");
    }

    virtual void setBitmap(const SkBitmap& bitmap) override;

    virtual bool isOpaque() override;
    virtual int width() override;
    virtual int height() override;

    virtual int getSaveCount() const override;
    virtual int save(SaveFlags::Flags flags) override;
    virtual void restore() override;
    virtual void restoreToCount(int saveCount) override;
    virtual void restoreUnclippedLayer(int saveCount, const SkPaint& paint) override;

    virtual int saveLayer(float left, float top, float right, float bottom, const SkPaint* paint,
                          SaveFlags::Flags flags) override;
    virtual int saveLayerAlpha(float left, float top, float right, float bottom, int alpha,
                               SaveFlags::Flags flags) override;
    virtual int saveUnclippedLayer(int left, int top, int right, int bottom) override;

    virtual void getMatrix(SkMatrix* outMatrix) const override;
    virtual void setMatrix(const SkMatrix& matrix) override;
    virtual void concat(const SkMatrix& matrix) override;
    virtual void rotate(float degrees) override;
    virtual void scale(float sx, float sy) override;
    virtual void skew(float sx, float sy) override;
    virtual void translate(float dx, float dy) override;

    virtual bool getClipBounds(SkRect* outRect) const override;
    virtual bool quickRejectRect(float left, float top, float right, float bottom) const override;
    virtual bool quickRejectPath(const SkPath& path) const override;
    virtual bool clipRect(float left, float top, float right, float bottom, SkClipOp op) override;
    virtual bool clipPath(const SkPath* path, SkClipOp op) override;

    virtual PaintFilter* getPaintFilter() override;
    virtual void setPaintFilter(sk_sp<PaintFilter> paintFilter) override;

    virtual SkCanvasState* captureCanvasState() const override;

    virtual void drawColor(int color, SkBlendMode mode) override;
    virtual void drawPaint(const SkPaint& paint) override;

    virtual void drawPoint(float x, float y, const SkPaint& paint) override;
    virtual void drawPoints(const float* points, int count, const SkPaint& paint) override;
    virtual void drawLine(float startX, float startY, float stopX, float stopY,
                          const SkPaint& paint) override;
    virtual void drawLines(const float* points, int count, const SkPaint& paint) override;
    virtual void drawRect(float left, float top, float right, float bottom,
                          const SkPaint& paint) override;
    virtual void drawRegion(const SkRegion& region, const SkPaint& paint) override;
    virtual void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry,
                               const SkPaint& paint) override;

   virtual void drawDoubleRoundRect(const SkRRect& outer, const SkRRect& inner,
                               const SkPaint& paint) override;

    virtual void drawCircle(float x, float y, float radius, const SkPaint& paint) override;
    virtual void drawOval(float left, float top, float right, float bottom,
                          const SkPaint& paint) override;
    virtual void drawArc(float left, float top, float right, float bottom, float startAngle,
                         float sweepAngle, bool useCenter, const SkPaint& paint) override;
    virtual void drawPath(const SkPath& path, const SkPaint& paint) override;
    virtual void drawVertices(const SkVertices*, SkBlendMode, const SkPaint& paint) override;

    virtual void drawBitmap(Bitmap& bitmap, float left, float top, const SkPaint* paint) override;
    virtual void drawBitmap(Bitmap& bitmap, const SkMatrix& matrix, const SkPaint* paint) override;
    virtual void drawBitmap(Bitmap& bitmap, float srcLeft, float srcTop, float srcRight,
                            float srcBottom, float dstLeft, float dstTop, float dstRight,
                            float dstBottom, const SkPaint* paint) override;
    virtual void drawBitmapMesh(Bitmap& bitmap, int meshWidth, int meshHeight,
                                const float* vertices, const int* colors,
                                const SkPaint* paint) override;
    virtual void drawNinePatch(Bitmap& bitmap, const android::Res_png_9patch& chunk, float dstLeft,
                               float dstTop, float dstRight, float dstBottom,
                               const SkPaint* paint) override;
    virtual double drawAnimatedImage(AnimatedImageDrawable* imgDrawable) override;

    virtual bool drawTextAbsolutePos() const override { return true; }
    virtual void drawVectorDrawable(VectorDrawableRoot* vectorDrawable) override;

    virtual void drawRoundRect(uirenderer::CanvasPropertyPrimitive* left,
                               uirenderer::CanvasPropertyPrimitive* top,
                               uirenderer::CanvasPropertyPrimitive* right,
                               uirenderer::CanvasPropertyPrimitive* bottom,
                               uirenderer::CanvasPropertyPrimitive* rx,
                               uirenderer::CanvasPropertyPrimitive* ry,
                               uirenderer::CanvasPropertyPaint* paint) override;
    virtual void drawCircle(uirenderer::CanvasPropertyPrimitive* x,
                            uirenderer::CanvasPropertyPrimitive* y,
                            uirenderer::CanvasPropertyPrimitive* radius,
                            uirenderer::CanvasPropertyPaint* paint) override;

    virtual void drawLayer(uirenderer::DeferredLayerUpdater* layerHandle) override;
    virtual void drawRenderNode(uirenderer::RenderNode* renderNode) override;
    virtual void callDrawGLFunction(Functor* functor,
                                    uirenderer::GlFunctorLifecycleListener* listener) override;

protected:
    SkiaCanvas();
    void reset(SkCanvas* skiaCanvas);
    void drawDrawable(SkDrawable* drawable) { mCanvas->drawDrawable(drawable); }

    virtual void drawGlyphs(ReadGlyphFunc glyphFunc, int count, const Paint& paint, float x,
                            float y, float boundsLeft, float boundsTop, float boundsRight,
                            float boundsBottom, float totalAdvance) override;
    virtual void drawLayoutOnPath(const minikin::Layout& layout, float hOffset, float vOffset,
                                  const Paint& paint, const SkPath& path, size_t start,
                                  size_t end) override;

    /** This class acts as a copy on write SkPaint.
     *
     *  Initially this will be the SkPaint passed to the contructor.
     *  The first time writable() is called this will become a copy of the
     *  initial SkPaint (or a default SkPaint if nullptr).
     */
    struct PaintCoW {
        PaintCoW(const SkPaint& that) : mPtr(&that) {}
        PaintCoW(const SkPaint* ptr) : mPtr(ptr) {}
        PaintCoW(const PaintCoW&) = delete;
        PaintCoW(PaintCoW&&) = delete;
        PaintCoW& operator=(const PaintCoW&) = delete;
        PaintCoW& operator=(PaintCoW&&) = delete;
        SkPaint& writeable() {
            if (!mStorage) {
                if (!mPtr) {
                    mStorage.emplace();
                } else {
                    mStorage.emplace(*mPtr);
                }
                mPtr = &*mStorage;
            }
            return *mStorage;
        }
        operator const SkPaint*() const { return mPtr; }
        const SkPaint* operator->() const { assert(mPtr); return mPtr; }
        const SkPaint& operator*() const { assert(mPtr); return *mPtr; }
        explicit operator bool() { return mPtr != nullptr; }
    private:
        const SkPaint* mPtr;
        std::optional<SkPaint> mStorage;
    };

    /** Filters the paint using the current paint filter.
     *
     *  @param paint the paint to filter. Will be initialized with the default
     *      SkPaint before filtering if filtering is required.
     */
    PaintCoW&& filterPaint(PaintCoW&& paint) const;

private:
    struct SaveRec {
        int saveCount;
        SaveFlags::Flags saveFlags;
        size_t clipIndex;
    };

    const SaveRec* currentSaveRec() const;
    void recordPartialSave(SaveFlags::Flags flags);

    template <typename T>
    void recordClip(const T&, SkClipOp);
    void applyPersistentClips(size_t clipStartIndex);

    void drawPoints(const float* points, int count, const SkPaint& paint, SkCanvas::PointMode mode);

    /** Filters the paint for bitmap drawing.
     *
     *  After filtering the paint for bitmap drawing,
     *  also calls filterPaint on the paint.
     *
     *  @param paint the paint to filter. Will be initialized with the default
     *      SkPaint before filtering if filtering is required.
     */
    PaintCoW&& filterBitmap(PaintCoW&& paint, sk_sp<SkColorFilter> colorSpaceFilter) const;

    class Clip;

    std::unique_ptr<SkCanvas> mCanvasOwned;    // might own a canvas we allocated
    SkCanvas* mCanvas;                         // we do NOT own this canvas, it must survive us
                                               // unless it is the same as mCanvasOwned.get()
    std::unique_ptr<SkDeque> mSaveStack;       // lazily allocated, tracks partial saves.
    std::vector<Clip> mClipStack;              // tracks persistent clips.
    sk_sp<PaintFilter> mPaintFilter;
};

}  // namespace android
