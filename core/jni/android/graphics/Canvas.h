/*
 * Copyright (C) 2014 The Android Open Source Project
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

#ifndef ANDROID_GRAPHICS_CANVAS_H
#define ANDROID_GRAPHICS_CANVAS_H

#include "SkBitmap.h"
#include "SkCanvas.h"
#include "SkMatrix.h"

namespace android {

class Canvas {
public:
    virtual ~Canvas() {};

    static Canvas* create_canvas(SkBitmap* bitmap);
    static Canvas* create_canvas(SkCanvas* skiaCanvas);

    // TODO: enable HWUI to either create similar canvas wrapper or subclass
    //       directly from Canvas
    //static Canvas* create_canvas(uirenderer::Renderer* renderer);

    // TODO: this is a temporary affordance until all necessary logic can be
    //       moved within this interface! Further, the return value should
    //       NOT be unref'd and is valid until this canvas is destroyed or a
    //       new bitmap is set.
    virtual SkCanvas* getSkCanvas() = 0;

    virtual void setBitmap(SkBitmap* bitmap, bool copyState) = 0;

    virtual bool isOpaque() = 0;
    virtual int width() = 0;
    virtual int height() = 0;

// ----------------------------------------------------------------------------
// Canvas state operations
// ----------------------------------------------------------------------------
    // Save (layer)
    virtual int getSaveCount() const = 0;
    virtual int save(SkCanvas::SaveFlags flags) = 0;
    virtual void restore() = 0;
    virtual void restoreToCount(int saveCount) = 0;

    virtual int saveLayer(float left, float top, float right, float bottom,
                const SkPaint* paint, SkCanvas::SaveFlags flags) = 0;
    virtual int saveLayerAlpha(float left, float top, float right, float bottom,
            int alpha, SkCanvas::SaveFlags flags) = 0;

    // Matrix
    virtual void getMatrix(SkMatrix* outMatrix) const = 0;
    virtual void setMatrix(const SkMatrix& matrix) = 0;

    virtual void concat(const SkMatrix& matrix) = 0;
    virtual void rotate(float degrees) = 0;
    virtual void scale(float sx, float sy) = 0;
    virtual void skew(float sx, float sy) = 0;
    virtual void translate(float dx, float dy) = 0;

    // clip
    virtual bool getClipBounds(SkRect* outRect) const = 0;
    virtual bool quickRejectRect(float left, float top, float right, float bottom) const = 0;
    virtual bool quickRejectPath(const SkPath& path) const = 0;

    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op) = 0;
    virtual bool clipPath(const SkPath* path, SkRegion::Op op) = 0;
    virtual bool clipRegion(const SkRegion* region, SkRegion::Op op) = 0;

    // filters
    virtual SkDrawFilter* getDrawFilter() = 0;
    virtual void setDrawFilter(SkDrawFilter* drawFilter) = 0;

// ----------------------------------------------------------------------------
// Canvas draw operations
// ----------------------------------------------------------------------------
    virtual void drawColor(int color, SkXfermode::Mode mode) = 0;
    virtual void drawPaint(const SkPaint& paint) = 0;

    // Geometry
    virtual void drawPoint(float x, float y, const SkPaint& paint) = 0;
    virtual void drawPoints(const float* points, int count, const SkPaint& paint) = 0;
    virtual void drawLine(float startX, float startY, float stopX, float stopY,
                const SkPaint& paint) = 0;
    virtual void drawLines(const float* points, int count, const SkPaint& paint) = 0;
    virtual void drawRect(float left, float top, float right, float bottom,
            const SkPaint& paint) = 0;
    virtual void drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint& paint) = 0;
    virtual void drawCircle(float x, float y, float radius, const SkPaint& paint) = 0;
    virtual void drawOval(float left, float top, float right, float bottom,
            const SkPaint& paint) = 0;
    virtual void drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint& paint) = 0;
    virtual void drawPath(const SkPath& path, const SkPaint& paint) = 0;
    virtual void drawVertices(SkCanvas::VertexMode vertexMode, int vertexCount,
                              const float* verts, const float* tex, const int* colors,
                              const uint16_t* indices, int indexCount, const SkPaint& paint) = 0;

    // Bitmap-based
    virtual void drawBitmap(const SkBitmap& bitmap, float left, float top,
            const SkPaint* paint) = 0;
    virtual void drawBitmap(const SkBitmap& bitmap, const SkMatrix& matrix,
            const SkPaint* paint) = 0;
    virtual void drawBitmap(const SkBitmap& bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint) = 0;
    virtual void drawBitmapMesh(const SkBitmap& bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint) = 0;

    // Text
    virtual void drawText(const uint16_t* text, const float* positions, int count,
            const SkPaint& paint, float x, float y,
            float boundsLeft, float boundsTop, float boundsRight, float boundsBottom) = 0;
    virtual void drawPosText(const uint16_t* text, const float* positions, int count,
            int posCount, const SkPaint& paint) = 0;
    virtual void drawTextOnPath(const uint16_t* glyphs, int count, const SkPath& path,
            float hOffset, float vOffset, const SkPaint& paint) = 0;

    /*
     * Specifies if the positions passed to ::drawText are absolute or relative
     * to the (x,y) value provided.
     *
     * If true the (x,y) values are ignored. Otherwise, those (x,y) values need
     * to be added to each glyph's position to get its absolute position.
     */
    virtual bool drawTextAbsolutePos() const = 0;
};

}; // namespace android
#endif // ANDROID_GRAPHICS_CANVAS_H
