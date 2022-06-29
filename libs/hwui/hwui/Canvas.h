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
#include <SaveFlags.h>

#include <androidfw/ResourceTypes.h>
#include "Properties.h"
#include "pipeline/skia/AnimatedDrawables.h"
#include "utils/Macros.h"

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkMatrix.h>

class SkAnimatedImage;
class SkCanvasState;
class SkRuntimeShaderBuilder;
class SkVertices;

namespace minikin {
class Font;
class Layout;
class MeasuredText;
enum class Bidi : uint8_t;
}

namespace android {
class PaintFilter;

namespace uirenderer {
class CanvasPropertyPaint;
class CanvasPropertyPrimitive;
class DeferredLayerUpdater;
class RenderNode;
namespace VectorDrawable {
class Tree;
}
}
typedef uirenderer::VectorDrawable::Tree VectorDrawableRoot;

typedef std::function<void(uint16_t* text, float* positions)> ReadGlyphFunc;

class AnimatedImageDrawable;
class Bitmap;
class Paint;
struct Typeface;

enum class DrawTextBlobMode {
    Normal,
    HctOutline,
    HctInner,
};

inline DrawTextBlobMode gDrawTextBlobMode = DrawTextBlobMode::Normal;

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

    virtual void setBitmap(const SkBitmap& bitmap) = 0;

    virtual bool isOpaque() = 0;
    virtual int width() = 0;
    virtual int height() = 0;

    // ----------------------------------------------------------------------------
    // View System operations (not exposed in public Canvas API)
    // ----------------------------------------------------------------------------

    virtual void resetRecording(int width, int height,
                                uirenderer::RenderNode* renderNode = nullptr) = 0;
    virtual void finishRecording(uirenderer::RenderNode* destination) = 0;
    virtual void enableZ(bool enableZ) = 0;

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
    virtual void drawRipple(const uirenderer::skiapipeline::RippleDrawableParams& params) = 0;

    virtual void drawLayer(uirenderer::DeferredLayerUpdater* layerHandle) = 0;
    virtual void drawRenderNode(uirenderer::RenderNode* renderNode) = 0;

    virtual void drawWebViewFunctor(int /*functor*/) {
        LOG_ALWAYS_FATAL("Not supported");
    }

    virtual void punchHole(const SkRRect& rect) = 0;

    // ----------------------------------------------------------------------------
    // Canvas state operations
    // ----------------------------------------------------------------------------

    // Save (layer)
    virtual int getSaveCount() const = 0;
    virtual int save(SaveFlags::Flags flags) = 0;
    virtual void restore() = 0;
    virtual void restoreToCount(int saveCount) = 0;
    virtual void restoreUnclippedLayer(int saveCount, const Paint& paint) = 0;

    virtual int saveLayer(float left, float top, float right, float bottom, const SkPaint* paint) = 0;
    virtual int saveLayerAlpha(float left, float top, float right, float bottom, int alpha) = 0;
    virtual int saveUnclippedLayer(int, int, int, int) = 0;

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
    // Resets clip to wide open, used to emulate the now-removed SkClipOp::kReplace on
    // apps with compatibility < P. Canvases for version P and later are restricted to
    // intersect and difference at the Java level, matching SkClipOp's definition.
    // NOTE: These functions are deprecated and will be removed in a future release
    virtual bool replaceClipRect_deprecated(float left, float top, float right, float bottom) = 0;
    virtual bool replaceClipPath_deprecated(const SkPath* path) = 0;

    // filters
    virtual PaintFilter* getPaintFilter() = 0;
    virtual void setPaintFilter(sk_sp<PaintFilter> paintFilter) = 0;

    // WebView only
    virtual SkCanvasState* captureCanvasState() const { return nullptr; }

    // ----------------------------------------------------------------------------
    // Canvas draw operations
    // ----------------------------------------------------------------------------
    virtual void drawColor(int color, SkBlendMode mode) = 0;
    virtual void drawPaint(const Paint& paint) = 0;

    // Geometry
    virtual void drawPoint(float x, float y, const Paint& paint) = 0;
    virtual void drawPoints(const float* points, int floatCount, const Paint& paint) = 0;
    virtual void drawLine(float startX, float startY, float stopX, float stopY,
                          const Paint& paint) = 0;
    virtual void drawLines(const float* points, int floatCount, const Paint& paint) = 0;
    virtual void drawRect(float left, float top, float right, float bottom,
                          const Paint& paint) = 0;
    virtual void drawRegion(const SkRegion& region, const Paint& paint) = 0;
    virtual void drawRoundRect(float left, float top, float right, float bottom, float rx, float ry,
                               const Paint& paint) = 0;
    virtual void drawDoubleRoundRect(const SkRRect& outer, const SkRRect& inner,
                                const Paint& paint) = 0;
    virtual void drawCircle(float x, float y, float radius, const Paint& paint) = 0;
    virtual void drawOval(float left, float top, float right, float bottom,
                          const Paint& paint) = 0;
    virtual void drawArc(float left, float top, float right, float bottom, float startAngle,
                         float sweepAngle, bool useCenter, const Paint& paint) = 0;
    virtual void drawPath(const SkPath& path, const Paint& paint) = 0;
    virtual void drawVertices(const SkVertices*, SkBlendMode, const Paint& paint) = 0;

    // Bitmap-based
    virtual void drawBitmap(Bitmap& bitmap, float left, float top, const Paint* paint) = 0;
    virtual void drawBitmap(Bitmap& bitmap, const SkMatrix& matrix, const Paint* paint) = 0;
    virtual void drawBitmap(Bitmap& bitmap, float srcLeft, float srcTop, float srcRight,
                            float srcBottom, float dstLeft, float dstTop, float dstRight,
                            float dstBottom, const Paint* paint) = 0;
    virtual void drawBitmapMesh(Bitmap& bitmap, int meshWidth, int meshHeight,
                                const float* vertices, const int* colors, const Paint* paint) = 0;
    virtual void drawNinePatch(Bitmap& bitmap, const android::Res_png_9patch& chunk, float dstLeft,
                               float dstTop, float dstRight, float dstBottom,
                               const Paint* paint) = 0;

    virtual double drawAnimatedImage(AnimatedImageDrawable* imgDrawable) = 0;
    virtual void drawPicture(const SkPicture& picture) = 0;

    /**
     * Draws a VectorDrawable onto the canvas.
     */
    virtual void drawVectorDrawable(VectorDrawableRoot* tree) = 0;

    void drawGlyphs(const minikin::Font& font, const int* glyphIds, const float* positions,
                    int glyphCount, const Paint& paint);

    /**
     * Converts utf16 text to glyphs, calculating position and boundary,
     * and delegating the final draw to virtual drawGlyphs method.
     */
    void drawText(const uint16_t* text, int textSize, int start, int count, int contextStart,
                  int contextCount, float x, float y, minikin::Bidi bidiFlags,
                  const Paint& origPaint, const Typeface* typeface, minikin::MeasuredText* mt);

    void drawTextOnPath(const uint16_t* text, int count, minikin::Bidi bidiFlags,
                        const SkPath& path, float hOffset, float vOffset, const Paint& paint,
                        const Typeface* typeface);

    void drawDoubleRoundRectXY(float outerLeft, float outerTop, float outerRight,
                                float outerBottom, float outerRx, float outerRy, float innerLeft,
                                float innerTop, float innerRight, float innerBottom, float innerRx,
                                float innerRy, const Paint& paint);

    void drawDoubleRoundRectRadii(float outerLeft, float outerTop, float outerRight,
                                float outerBottom, const float* outerRadii, float innerLeft,
                                float innerTop, float innerRight, float innerBottom,
                                const float* innerRadii, const Paint& paint);

    static int GetApiLevel() { return sApiLevel; }

protected:
    void drawTextDecorations(float x, float y, float length, const Paint& paint);

    /**
     * glyphFunc: valid only for the duration of the call and should not be cached.
     * drawText: count is of glyphs
     * totalAdvance: used to define width of text decorations (underlines, strikethroughs).
     */
    virtual void drawGlyphs(ReadGlyphFunc glyphFunc, int count, const Paint& paint, float x,
                            float y,float totalAdvance) = 0;
    virtual void drawLayoutOnPath(const minikin::Layout& layout, float hOffset, float vOffset,
                                  const Paint& paint, const SkPath& path, size_t start,
                                  size_t end) = 0;
    static int sApiLevel;

    friend class DrawTextFunctor;
    friend class DrawTextOnPathFunctor;
};

}  // namespace android
