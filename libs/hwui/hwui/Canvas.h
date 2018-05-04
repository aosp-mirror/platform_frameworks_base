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

#pragma once

#include <cutils/compiler.h>
#include <utils/Functor.h>

#include <androidfw/ResourceTypes.h>
#include "GlFunctorLifecycleListener.h"
#include "Properties.h"
#include "utils/Macros.h"

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkMatrix.h>

class SkAnimatedImage;
class SkCanvasState;
class SkVertices;

namespace minikin {
class Layout;
class MeasuredText;
enum class Bidi : uint8_t;
}

namespace android {

namespace uirenderer {
class CanvasPropertyPaint;
class CanvasPropertyPrimitive;
class DeferredLayerUpdater;
class RenderNode;

namespace skiapipeline {
class SkiaDisplayList;
}

/**
 * Data structure that holds the list of commands used in display list stream
 */
using DisplayList = skiapipeline::SkiaDisplayList;
}

namespace SaveFlags {

// These must match the corresponding Canvas API constants.
enum {
    Matrix = 0x01,
    Clip = 0x02,
    HasAlphaLayer = 0x04,
    ClipToLayer = 0x10,

    // Helper constant
    MatrixClip = Matrix | Clip,
};
typedef uint32_t Flags;

}  // namespace SaveFlags

namespace uirenderer {
class SkiaCanvasProxy;
namespace VectorDrawable {
class Tree;
};
};
typedef uirenderer::VectorDrawable::Tree VectorDrawableRoot;

typedef std::function<void(uint16_t* text, float* positions)> ReadGlyphFunc;

class AnimatedImageDrawable;
class Bitmap;
class Paint;
struct Typeface;

class ANDROID_API Canvas {
public:
    virtual ~Canvas(){};

    static Canvas* create_canvas(const SkBitmap& bitmap);

    /**
     *  Create a new Canvas object that records view system drawing operations for deferred
     *  rendering. A canvas returned by this call supports calls to the resetRecording(...) and
     *  finishRecording() calls.  The latter call returns a DisplayList that is specific to the
     *  RenderPipeline defined by Properties::getRenderPipelineType().
     *
     *  @param width of the requested Canvas.
     *  @param height of the requested Canvas.
     *  @param renderNode is an optional parameter that specifies the node that will consume the
     *      DisplayList produced by the returned Canvas.  This enables the reuse of select C++
     *      objects as a speed optimization.
     *  @return new non-null Canvas Object.  The type of DisplayList produced by this canvas is
            determined based on Properties::getRenderPipelineType().
     *
     */
    static WARN_UNUSED_RESULT Canvas* create_recording_canvas(
            int width, int height, uirenderer::RenderNode* renderNode = nullptr);

    /**
     *  Create a new Canvas object which delegates to an SkCanvas.
     *
     *  @param skiaCanvas Must not be NULL. All drawing calls will be
     *      delegated to this object. This function will call ref() on the
     *      SkCanvas, and the returned Canvas will unref() it upon
     *      destruction.
     *  @return new non-null Canvas Object.  The type of DisplayList produced by this canvas is
     *      determined based on  Properties::getRenderPipelineType().
     */
    static Canvas* create_canvas(SkCanvas* skiaCanvas);

    /**
     *  Sets the target SDK version used to build the app.
     *
     *  @param apiLevel API level
     *
     */
    static void setCompatibilityVersion(int apiLevel);

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

    virtual void resetRecording(int width, int height,
                                uirenderer::RenderNode* renderNode = nullptr) = 0;
    virtual uirenderer::DisplayList* finishRecording() = 0;
    virtual void insertReorderBarrier(bool enableReorder) = 0;

    bool isHighContrastText() const { return uirenderer::Properties::enableHighContrastText; }

    virtual void drawRoundRect(uirenderer::CanvasPropertyPrimitive* left,
                               uirenderer::CanvasPropertyPrimitive* top,
                               uirenderer::CanvasPropertyPrimitive* right,
                               uirenderer::CanvasPropertyPrimitive* bottom,
                               uirenderer::CanvasPropertyPrimitive* rx,
                               uirenderer::CanvasPropertyPrimitive* ry,
                               uirenderer::CanvasPropertyPaint* paint) = 0;
    virtual void drawCircle(uirenderer::CanvasPropertyPrimitive* x,
                            uirenderer::CanvasPropertyPrimitive* y,
                            uirenderer::CanvasPropertyPrimitive* radius,
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

    virtual int saveLayer(float left, float top, float right, float bottom, const SkPaint* paint,
                          SaveFlags::Flags flags) = 0;
    virtual int saveLayerAlpha(float left, float top, float right, float bottom, int alpha,
                               SaveFlags::Flags flags) = 0;

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

    virtual bool clipRect(float left, float top, float right, float bottom, SkClipOp op) = 0;
    virtual bool clipPath(const SkPath* path, SkClipOp op) = 0;

    // filters
    virtual SkDrawFilter* getDrawFilter() = 0;
    virtual void setDrawFilter(SkDrawFilter* drawFilter) = 0;

    // WebView only
    virtual SkCanvasState* captureCanvasState() const { return nullptr; }

    // ----------------------------------------------------------------------------
    // Canvas draw operations
    // ----------------------------------------------------------------------------
    virtual void drawColor(int color, SkBlendMode mode) = 0;
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
    virtual void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry,
                               const SkPaint& paint) = 0;
    virtual void drawCircle(float x, float y, float radius, const SkPaint& paint) = 0;
    virtual void drawOval(float left, float top, float right, float bottom,
                          const SkPaint& paint) = 0;
    virtual void drawArc(float left, float top, float right, float bottom, float startAngle,
                         float sweepAngle, bool useCenter, const SkPaint& paint) = 0;
    virtual void drawPath(const SkPath& path, const SkPaint& paint) = 0;
    virtual void drawVertices(const SkVertices*, SkBlendMode, const SkPaint& paint) = 0;

    // Bitmap-based
    virtual void drawBitmap(Bitmap& bitmap, float left, float top, const SkPaint* paint) = 0;
    virtual void drawBitmap(Bitmap& bitmap, const SkMatrix& matrix, const SkPaint* paint) = 0;
    virtual void drawBitmap(Bitmap& bitmap, float srcLeft, float srcTop, float srcRight,
                            float srcBottom, float dstLeft, float dstTop, float dstRight,
                            float dstBottom, const SkPaint* paint) = 0;
    virtual void drawBitmapMesh(Bitmap& bitmap, int meshWidth, int meshHeight,
                                const float* vertices, const int* colors, const SkPaint* paint) = 0;
    virtual void drawNinePatch(Bitmap& bitmap, const android::Res_png_9patch& chunk, float dstLeft,
                               float dstTop, float dstRight, float dstBottom,
                               const SkPaint* paint) = 0;

    virtual double drawAnimatedImage(AnimatedImageDrawable* imgDrawable) = 0;

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
    virtual void drawVectorDrawable(VectorDrawableRoot* tree) = 0;

    /**
     * Converts utf16 text to glyphs, calculating position and boundary,
     * and delegating the final draw to virtual drawGlyphs method.
     */
    void drawText(const uint16_t* text, int start, int count, int contextCount, float x, float y,
                  minikin::Bidi bidiFlags, const Paint& origPaint, const Typeface* typeface,
                  minikin::MeasuredText* mt);

    void drawTextOnPath(const uint16_t* text, int count, minikin::Bidi bidiFlags,
                        const SkPath& path, float hOffset, float vOffset, const Paint& paint,
                        const Typeface* typeface);

    static int GetApiLevel() { return sApiLevel; }

protected:
    void drawTextDecorations(float x, float y, float length, const SkPaint& paint);

    /**
     * glyphFunc: valid only for the duration of the call and should not be cached.
     * drawText: count is of glyphs
     * totalAdvance: used to define width of text decorations (underlines, strikethroughs).
     */
    virtual void drawGlyphs(ReadGlyphFunc glyphFunc, int count, const SkPaint& paint, float x,
                            float y, float boundsLeft, float boundsTop, float boundsRight,
                            float boundsBottom, float totalAdvance) = 0;
    virtual void drawLayoutOnPath(const minikin::Layout& layout, float hOffset, float vOffset,
                                  const SkPaint& paint, const SkPath& path, size_t start,
                                  size_t end) = 0;
    static int sApiLevel;

    friend class DrawTextFunctor;
    friend class DrawTextOnPathFunctor;
    friend class uirenderer::SkiaCanvasProxy;
};

};  // namespace android
