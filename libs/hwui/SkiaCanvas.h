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
#ifdef __ANDROID__ // Layoutlib does not support hardware acceleration
#include "DeferredLayerUpdater.h"
#endif
#include <SkCanvas.h>

#include <cassert>
#include <deque>
#include <optional>

#include "RenderNode.h"
#include "VectorDrawable.h"
#include "hwui/BlurDrawLooper.h"
#include "hwui/Canvas.h"
#include "hwui/Paint.h"
#include "pipeline/skia/AnimatedDrawables.h"
#include "src/core/SkArenaAlloc.h"

enum class SkBlendMode;
class SkRRect;

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

    virtual void resetRecording(int width, int height,
                                uirenderer::RenderNode* renderNode) override {
        LOG_ALWAYS_FATAL("SkiaCanvas cannot be reset as a recording canvas");
    }

    virtual void finishRecording(uirenderer::RenderNode*) override {
        LOG_ALWAYS_FATAL("SkiaCanvas does not produce a DisplayList");
    }
    virtual void enableZ(bool enableZ) override {
        LOG_ALWAYS_FATAL("SkiaCanvas does not support enableZ");
    }

    virtual void punchHole(const SkRRect& rect, float alpha) override;

    virtual void setBitmap(const SkBitmap& bitmap) override;

    virtual bool isOpaque() override;
    virtual int width() override;
    virtual int height() override;

    virtual int getSaveCount() const override;
    virtual int save(SaveFlags::Flags flags) override;
    virtual void restore() override;
    virtual void restoreToCount(int saveCount) override;
    virtual void restoreUnclippedLayer(int saveCount, const Paint& paint) override;

    virtual int saveLayer(float left, float top, float right, float bottom, const SkPaint* paint) override;
    virtual int saveLayerAlpha(float left, float top, float right, float bottom, int alpha) override;
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
    virtual bool replaceClipRect_deprecated(float left, float top, float right,
                                            float bottom) override;
    virtual bool replaceClipPath_deprecated(const SkPath* path) override;

    virtual PaintFilter* getPaintFilter() override;
    virtual void setPaintFilter(sk_sp<PaintFilter> paintFilter) override;

    virtual SkCanvasState* captureCanvasState() const override;

    virtual void drawColor(int color, SkBlendMode mode) override;
    virtual void drawPaint(const Paint& paint) override;

    virtual void drawPoint(float x, float y, const Paint& paint) override;
    virtual void drawPoints(const float* points, int count, const Paint& paint) override;
    virtual void drawLine(float startX, float startY, float stopX, float stopY,
                          const Paint& paint) override;
    virtual void drawLines(const float* points, int count, const Paint& paint) override;
    virtual void drawRect(float left, float top, float right, float bottom,
                          const Paint& paint) override;
    virtual void drawRegion(const SkRegion& region, const Paint& paint) override;
    virtual void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry,
                               const Paint& paint) override;

    virtual void drawDoubleRoundRect(const SkRRect& outer, const SkRRect& inner,
                                     const Paint& paint) override;

    virtual void drawCircle(float x, float y, float radius, const Paint& paint) override;
    virtual void drawOval(float left, float top, float right, float bottom,
                          const Paint& paint) override;
    virtual void drawArc(float left, float top, float right, float bottom, float startAngle,
                         float sweepAngle, bool useCenter, const Paint& paint) override;
    virtual void drawPath(const SkPath& path, const Paint& paint) override;
    virtual void drawVertices(const SkVertices*, SkBlendMode, const Paint& paint) override;
    virtual void drawMesh(const SkMesh& mesh, sk_sp<SkBlender> blender,
                          const SkPaint& paint) override;

    virtual void drawBitmap(Bitmap& bitmap, float left, float top, const Paint* paint) override;
    virtual void drawBitmap(Bitmap& bitmap, const SkMatrix& matrix, const Paint* paint) override;
    virtual void drawBitmap(Bitmap& bitmap, float srcLeft, float srcTop, float srcRight,
                            float srcBottom, float dstLeft, float dstTop, float dstRight,
                            float dstBottom, const Paint* paint) override;
    virtual void drawBitmapMesh(Bitmap& bitmap, int meshWidth, int meshHeight,
                                const float* vertices, const int* colors,
                                const Paint* paint) override;
    virtual void drawNinePatch(Bitmap& bitmap, const android::Res_png_9patch& chunk, float dstLeft,
                               float dstTop, float dstRight, float dstBottom,
                               const Paint* paint) override;
    virtual double drawAnimatedImage(AnimatedImageDrawable* imgDrawable) override;
    virtual void drawLottie(LottieDrawable* lottieDrawable) override;

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
    virtual void drawRipple(const uirenderer::skiapipeline::RippleDrawableParams& params) override;

    virtual void drawLayer(uirenderer::DeferredLayerUpdater* layerHandle) override;
    virtual void drawRenderNode(uirenderer::RenderNode* renderNode) override;
    virtual void drawPicture(const SkPicture& picture) override;

protected:
    SkiaCanvas();
    SkCanvas* asSkCanvas() { return mCanvas; }
    void reset(SkCanvas* skiaCanvas);
    void drawDrawable(SkDrawable* drawable) { mCanvas->drawDrawable(drawable); }

    virtual void drawGlyphs(ReadGlyphFunc glyphFunc, int count, const Paint& paint, float x,
                            float y, float totalAdvance) override;
    virtual void drawLayoutOnPath(const minikin::Layout& layout, float hOffset, float vOffset,
                                  const Paint& paint, const SkPath& path, size_t start,
                                  size_t end) override;

    void onFilterPaint(Paint& paint);

    Paint filterPaint(const Paint& src) {
        Paint dst(src);
        this->onFilterPaint(dst);
        return dst;
    }

    // proc(const SkPaint& modifiedPaint)
    template <typename Proc>
    void applyLooper(const Paint* paint, Proc proc, void (*preFilter)(SkPaint&) = nullptr) {
        BlurDrawLooper* looper = paint ? paint->getLooper() : nullptr;
        Paint pnt = paint ? *paint : Paint();
        if (preFilter) {
            preFilter(pnt);
        }
        this->onFilterPaint(pnt);
        if (looper) {
            looper->apply(pnt, [&](SkPoint offset, const Paint& modifiedPaint) {
                mCanvas->save();
                mCanvas->translate(offset.fX, offset.fY);
                proc(modifiedPaint);
                mCanvas->restore();
            });
        } else {
            proc(pnt);
        }
    }

private:
    struct SaveRec {
        int saveCount;
        SaveFlags::Flags saveFlags;
        size_t clipIndex;

        SaveRec(int saveCount, SaveFlags::Flags saveFlags, size_t clipIndex)
                : saveCount(saveCount), saveFlags(saveFlags), clipIndex(clipIndex) {}
    };

    const SaveRec* currentSaveRec() const;
    void recordPartialSave(SaveFlags::Flags flags);

    template <typename T>
    void recordClip(const T&, SkClipOp);
    void applyPersistentClips(size_t clipStartIndex);

    void drawPoints(const float* points, int count, const Paint& paint, SkCanvas::PointMode mode);

    class Clip;

    std::unique_ptr<SkCanvas> mCanvasOwned;  // Might own a canvas we allocated.
    SkCanvas* mCanvas;                       // We do NOT own this canvas, it must survive us
                                             // unless it is the same as mCanvasOwned.get().
    std::unique_ptr<std::deque<SaveRec>> mSaveStack;  // Lazily allocated, tracks partial saves.
    std::vector<Clip> mClipStack;                     // Tracks persistent clips.
    sk_sp<PaintFilter> mPaintFilter;
};

}  // namespace android
