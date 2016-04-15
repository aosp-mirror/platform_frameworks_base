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

#include <cutils/compiler.h>
#include <utils/Functor.h>

#include "GlFunctorLifecycleListener.h"
#include "utils/NinePatch.h"

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkMatrix.h>

namespace android {

namespace uirenderer {
    class CanvasPropertyPaint;
    class CanvasPropertyPrimitive;
    class DeferredLayerUpdater;
    class DisplayList;
    class RenderNode;
}

namespace SaveFlags {

// These must match the corresponding Canvas API constants.
enum {
    Matrix        = 0x01,
    Clip          = 0x02,
    HasAlphaLayer = 0x04,
    ClipToLayer   = 0x10,

    // Helper constant
    MatrixClip    = Matrix | Clip,
};
typedef uint32_t Flags;

} // namespace SaveFlags

namespace uirenderer {
class SkiaCanvasProxy;
namespace VectorDrawable {
class Tree;
};
};
typedef uirenderer::VectorDrawable::Tree VectorDrawableRoot;

class Paint;
struct Typeface;

class ANDROID_API Canvas {
public:
    virtual ~Canvas() {};

    static Canvas* create_canvas(const SkBitmap& bitmap);

    static Canvas* create_recording_canvas(int width, int height);

    /**
     *  Create a new Canvas object which delegates to an SkCanvas.
     *
     *  @param skiaCanvas Must not be NULL. All drawing calls will be
     *      delegated to this object. This function will call ref() on the
     *      SkCanvas, and the returned Canvas will unref() it upon
     *      destruction.
     *  @return new Canvas object. Will not return NULL.
     */
    static Canvas* create_canvas(SkCanvas* skiaCanvas);

    /**
     *  Provides a Skia SkCanvas interface that acts as a proxy to this Canvas.
     *  It is useful for testing and clients (e.g. Picture/Movie) that expect to
     *  draw their contents into an SkCanvas.
     *
     *  The SkCanvas returned is *only* valid until another Canvas call is made
     *  that would change state (e.g. matrix or clip). Clients of asSkCanvas()
     *  are responsible for *not* persisting this pointer.
     *
     *  Further, the returned SkCanvas should NOT be unref'd and is valid until
     *  this canvas is destroyed or a new bitmap is set.
     */
    virtual SkCanvas* asSkCanvas() = 0;


    virtual void setBitmap(const SkBitmap& bitmap) = 0;

    virtual bool isOpaque() = 0;
    virtual int width() = 0;
    virtual int height() = 0;

// ----------------------------------------------------------------------------
// View System operations (not exposed in public Canvas API)
// ----------------------------------------------------------------------------

    virtual void resetRecording(int width, int height) = 0;
    virtual uirenderer::DisplayList* finishRecording() = 0;
    virtual void insertReorderBarrier(bool enableReorder) = 0;

    virtual void setHighContrastText(bool highContrastText) = 0;
    virtual bool isHighContrastText() = 0;

    virtual void drawRoundRect(uirenderer::CanvasPropertyPrimitive* left,
            uirenderer::CanvasPropertyPrimitive* top, uirenderer::CanvasPropertyPrimitive* right,
            uirenderer::CanvasPropertyPrimitive* bottom, uirenderer::CanvasPropertyPrimitive* rx,
            uirenderer::CanvasPropertyPrimitive* ry, uirenderer::CanvasPropertyPaint* paint) = 0;
    virtual void drawCircle(uirenderer::CanvasPropertyPrimitive* x,
            uirenderer::CanvasPropertyPrimitive* y, uirenderer::CanvasPropertyPrimitive* radius,
            uirenderer::CanvasPropertyPaint* paint) = 0;

    virtual void drawLayer(uirenderer::DeferredLayerUpdater* layerHandle) = 0;
    virtual void drawRenderNode(uirenderer::RenderNode* renderNode) = 0;
    virtual void callDrawGLFunction(Functor* functor,
            uirenderer::GlFunctorLifecycleListener* listener) = 0;

// ----------------------------------------------------------------------------
// Canvas state operations
// ----------------------------------------------------------------------------

    // Save (layer)
    virtual int getSaveCount() const = 0;
    virtual int save(SaveFlags::Flags flags) = 0;
    virtual void restore() = 0;
    virtual void restoreToCount(int saveCount) = 0;

    virtual int saveLayer(float left, float top, float right, float bottom,
                const SkPaint* paint, SaveFlags::Flags flags) = 0;
    virtual int saveLayerAlpha(float left, float top, float right, float bottom,
            int alpha, SaveFlags::Flags flags) = 0;

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

    virtual bool clipRect(float left, float top, float right, float bottom,
            SkRegion::Op op = SkRegion::kIntersect_Op) = 0;
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
    virtual void drawPoints(const float* points, int floatCount, const SkPaint& paint) = 0;
    virtual void drawLine(float startX, float startY, float stopX, float stopY,
                const SkPaint& paint) = 0;
    virtual void drawLines(const float* points, int floatCount, const SkPaint& paint) = 0;
    virtual void drawRect(float left, float top, float right, float bottom,
            const SkPaint& paint) = 0;
    virtual void drawRegion(const SkRegion& region, const SkPaint& paint) = 0;
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
    virtual void drawNinePatch(const SkBitmap& bitmap, const android::Res_png_9patch& chunk,
            float dstLeft, float dstTop, float dstRight, float dstBottom,
            const SkPaint* paint) = 0;

    /**
     * Specifies if the positions passed to ::drawText are absolute or relative
     * to the (x,y) value provided.
     *
     * If true the (x,y) values are ignored. Otherwise, those (x,y) values need
     * to be added to each glyph's position to get its absolute position.
     */
    virtual bool drawTextAbsolutePos() const = 0;

    /**
     * Draws a VectorDrawable onto the canvas.
     */
    virtual void drawVectorDrawable(VectorDrawableRoot* tree);

    /**
     * Converts utf16 text to glyphs, calculating position and boundary,
     * and delegating the final draw to virtual drawGlyphs method.
     */
    void drawText(const uint16_t* text, int start, int count, int contextCount,
            float x, float y, int bidiFlags, const Paint& origPaint, Typeface* typeface);

    void drawTextOnPath(const uint16_t* text, int count, int bidiFlags, const SkPath& path,
            float hOffset, float vOffset, const Paint& paint, Typeface* typeface);

protected:
    void drawTextDecorations(float x, float y, float length, const SkPaint& paint);

    /**
     * drawText: count is of glyphs
     * totalAdvance: used to define width of text decorations (underlines, strikethroughs).
     */
    virtual void drawGlyphs(const uint16_t* glyphs, const float* positions, int count,
            const SkPaint& paint, float x, float y,
            float boundsLeft, float boundsTop, float boundsRight, float boundsBottom,
            float totalAdvance) = 0;
    /** drawTextOnPath: count is of glyphs */
    virtual void drawGlyphsOnPath(const uint16_t* glyphs, int count, const SkPath& path,
            float hOffset, float vOffset, const SkPaint& paint) = 0;

    friend class DrawTextFunctor;
    friend class DrawTextOnPathFunctor;
    friend class uirenderer::SkiaCanvasProxy;
};

}; // namespace android
#endif // ANDROID_GRAPHICS_CANVAS_H
