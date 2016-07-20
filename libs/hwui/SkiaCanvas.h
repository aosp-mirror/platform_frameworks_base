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

#include "hwui/Canvas.h"
#include "CanvasProperty.h"
#include "DeferredLayerUpdater.h"
#include "RenderNode.h"
#include "VectorDrawable.h"

#include <SkCanvas.h>
#include <SkClipStack.h>
#include <SkTArray.h>

namespace android {

// Holds an SkCanvas reference plus additional native data.
class SkiaCanvas : public Canvas {
public:
    explicit SkiaCanvas(const SkBitmap& bitmap);

    /**
     *  Create a new SkiaCanvas.
     *
     *  @param canvas SkCanvas to handle calls made to this SkiaCanvas. Must
     *      not be NULL. This constructor will ref() the SkCanvas, and unref()
     *      it in its destructor.
     */
    explicit SkiaCanvas(SkCanvas* canvas) : mCanvas(canvas) {
        SkASSERT(canvas);
        canvas->ref();
    }

    virtual SkCanvas* asSkCanvas() override {
        return mCanvas.get();
    }

    virtual void resetRecording(int width, int height) override {
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

    virtual void setHighContrastText(bool highContrastText) override {
        mHighContrastText = highContrastText;
    }
    virtual bool isHighContrastText() override { return mHighContrastText; }

    virtual int getSaveCount() const override;
    virtual int save(SaveFlags::Flags flags) override;
    virtual void restore() override;
    virtual void restoreToCount(int saveCount) override;

    virtual int saveLayer(float left, float top, float right, float bottom,
                const SkPaint* paint, SaveFlags::Flags flags) override;
    virtual int saveLayerAlpha(float left, float top, float right, float bottom,
            int alpha, SaveFlags::Flags flags) override;

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
    virtual bool clipRect(float left, float top, float right, float bottom,
            SkRegion::Op op) override;
    virtual bool clipPath(const SkPath* path, SkRegion::Op op) override;
    virtual bool clipRegion(const SkRegion* region, SkRegion::Op op) override;

    virtual SkDrawFilter* getDrawFilter() override;
    virtual void setDrawFilter(SkDrawFilter* drawFilter) override;

    virtual void drawColor(int color, SkXfermode::Mode mode) override;
    virtual void drawPaint(const SkPaint& paint) override;

    virtual void drawPoint(float x, float y, const SkPaint& paint) override;
    virtual void drawPoints(const float* points, int count, const SkPaint& paint) override;
    virtual void drawLine(float startX, float startY, float stopX, float stopY,
            const SkPaint& paint) override;
    virtual void drawLines(const float* points, int count, const SkPaint& paint) override;
    virtual void drawRect(float left, float top, float right, float bottom,
            const SkPaint& paint) override;
    virtual void drawRegion(const SkRegion& region, const SkPaint& paint) override;
    virtual void drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint& paint) override;
    virtual void drawCircle(float x, float y, float radius, const SkPaint& paint) override;
    virtual void drawOval(float left, float top, float right, float bottom,
            const SkPaint& paint) override;
    virtual void drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint& paint) override;
    virtual void drawPath(const SkPath& path, const SkPaint& paint) override;
    virtual void drawVertices(SkCanvas::VertexMode vertexMode, int vertexCount,
            const float* verts, const float* tex, const int* colors,
            const uint16_t* indices, int indexCount, const SkPaint& paint) override;

    virtual void drawBitmap(const SkBitmap& bitmap, float left, float top,
            const SkPaint* paint) override;
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

    virtual bool drawTextAbsolutePos() const  override { return true; }
    virtual void drawVectorDrawable(VectorDrawableRoot* vectorDrawable) override;

    virtual void drawRoundRect(uirenderer::CanvasPropertyPrimitive* left,
            uirenderer::CanvasPropertyPrimitive* top, uirenderer::CanvasPropertyPrimitive* right,
            uirenderer::CanvasPropertyPrimitive* bottom, uirenderer::CanvasPropertyPrimitive* rx,
            uirenderer::CanvasPropertyPrimitive* ry, uirenderer::CanvasPropertyPaint* paint) override;
    virtual void drawCircle(uirenderer::CanvasPropertyPrimitive* x,
            uirenderer::CanvasPropertyPrimitive* y, uirenderer::CanvasPropertyPrimitive* radius,
            uirenderer::CanvasPropertyPaint* paint) override;

    virtual void drawLayer(uirenderer::DeferredLayerUpdater* layerHandle) override;
    virtual void drawRenderNode(uirenderer::RenderNode* renderNode) override;
    virtual void callDrawGLFunction(Functor* functor,
            uirenderer::GlFunctorLifecycleListener* listener) override;

protected:
    explicit SkiaCanvas() {}
    void reset(SkCanvas* skiaCanvas);
    void drawDrawable(SkDrawable* drawable) { mCanvas->drawDrawable(drawable); }

    virtual void drawGlyphs(const uint16_t* text, const float* positions, int count,
            const SkPaint& paint, float x, float y,
            float boundsLeft, float boundsTop, float boundsRight, float boundsBottom,
            float totalAdvance) override;
    virtual void drawGlyphsOnPath(const uint16_t* glyphs, int count, const SkPath& path,
            float hOffset, float vOffset, const SkPaint& paint) override;

private:
    struct SaveRec {
        int              saveCount;
        SaveFlags::Flags saveFlags;
    };

    bool mHighContrastText = false;

    void recordPartialSave(SaveFlags::Flags flags);
    void saveClipsForFrame(SkTArray<SkClipStack::Element>& clips, int frameSaveCount);
    void applyClips(const SkTArray<SkClipStack::Element>& clips);

    void drawPoints(const float* points, int count, const SkPaint& paint,
            SkCanvas::PointMode mode);

    sk_sp<SkCanvas> mCanvas;
    std::unique_ptr<SkDeque> mSaveStack; // lazily allocated, tracks partial saves.
};

} // namespace android

