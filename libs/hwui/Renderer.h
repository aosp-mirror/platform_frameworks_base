/*
 * Copyright (C) 2013 The Android Open Source Project
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

#ifndef ANDROID_HWUI_RENDERER_H
#define ANDROID_HWUI_RENDERER_H

#include <SkColorFilter.h>
#include <SkPaint.h>
#include <SkRegion.h>

#include <utils/String8.h>

#include "AssetAtlas.h"

namespace android {

class Functor;
struct Res_png_9patch;

namespace uirenderer {

class RenderNode;
class Layer;
class Matrix4;
class SkiaColorFilter;
class Patch;

enum DrawOpMode {
    kDrawOpMode_Immediate,
    kDrawOpMode_Defer,
    kDrawOpMode_Flush
};

/**
 * Hwui's abstract version of Canvas.
 *
 * Provides methods for frame state operations, as well as the SkCanvas style transform/clip state,
 * and varied drawing operations.
 *
 * Should at some point interact with native SkCanvas.
 */
class ANDROID_API Renderer {
public:
    virtual ~Renderer() {}

    /**
     * Safely retrieves the mode from the specified xfermode. If the specified
     * xfermode is null, the mode is assumed to be SkXfermode::kSrcOver_Mode.
     */
    static inline SkXfermode::Mode getXfermode(SkXfermode* mode) {
        SkXfermode::Mode resultMode;
        if (!SkXfermode::AsMode(mode, &resultMode)) {
            resultMode = SkXfermode::kSrcOver_Mode;
        }
        return resultMode;
    }

    // TODO: move to a method on android:Paint
    static inline bool paintWillNotDraw(const SkPaint& paint) {
        return paint.getAlpha() == 0
                && !paint.getColorFilter()
                && getXfermode(paint.getXfermode()) == SkXfermode::kSrcOver_Mode;
    }

    // TODO: move to a method on android:Paint
    static inline bool paintWillNotDrawText(const SkPaint& paint) {
        return paint.getAlpha() == 0
                && paint.getLooper() == NULL
                && !paint.getColorFilter()
                && getXfermode(paint.getXfermode()) == SkXfermode::kSrcOver_Mode;
    }

    static bool isBlendedColorFilter(const SkColorFilter* filter) {
        if (filter == NULL) {
            return false;
        }
        return (filter->getFlags() & SkColorFilter::kAlphaUnchanged_Flag) == 0;
    }

// ----------------------------------------------------------------------------
// Frame state operations
// ----------------------------------------------------------------------------
    /**
     * Sets the dimension of the underlying drawing surface. This method must
     * be called at least once every time the drawing surface changes size.
     *
     * @param width The width in pixels of the underlysing surface
     * @param height The height in pixels of the underlysing surface
     */
    virtual void setViewport(int width, int height) = 0;

    /**
     * Prepares the renderer to draw a frame. This method must be invoked
     * at the beginning of each frame. When this method is invoked, the
     * entire drawing surface is assumed to be redrawn.
     *
     * @param opaque If true, the target surface is considered opaque
     *               and will not be cleared. If false, the target surface
     *               will be cleared
     */
    virtual status_t prepare(bool opaque) = 0;

    /**
     * Prepares the renderer to draw a frame. This method must be invoked
     * at the beginning of each frame. Only the specified rectangle of the
     * frame is assumed to be dirty. A clip will automatically be set to
     * the specified rectangle.
     *
     * @param left The left coordinate of the dirty rectangle
     * @param top The top coordinate of the dirty rectangle
     * @param right The right coordinate of the dirty rectangle
     * @param bottom The bottom coordinate of the dirty rectangle
     * @param opaque If true, the target surface is considered opaque
     *               and will not be cleared. If false, the target surface
     *               will be cleared in the specified dirty rectangle
     */
    virtual status_t prepareDirty(float left, float top, float right, float bottom,
            bool opaque) = 0;

    /**
     * Indicates the end of a frame. This method must be invoked whenever
     * the caller is done rendering a frame.
     */
    virtual void finish() = 0;

// ----------------------------------------------------------------------------
// Canvas state operations
// ----------------------------------------------------------------------------
    // Save (layer)
    virtual int getSaveCount() const = 0;
    virtual int save(int flags) = 0;
    virtual void restore() = 0;
    virtual void restoreToCount(int saveCount) = 0;

    virtual int saveLayer(float left, float top, float right, float bottom,
            const SkPaint* paint, int flags) = 0;

    int saveLayerAlpha(float left, float top, float right, float bottom,
            int alpha, int flags) {
        SkPaint paint;
        paint.setAlpha(alpha);
        return saveLayer(left, top, right, bottom, &paint, flags);
    }

    // Matrix
    virtual void getMatrix(SkMatrix* outMatrix) const = 0;
    virtual void translate(float dx, float dy, float dz = 0.0f) = 0;
    virtual void rotate(float degrees) = 0;
    virtual void scale(float sx, float sy) = 0;
    virtual void skew(float sx, float sy) = 0;

    virtual void setMatrix(const SkMatrix& matrix) = 0;
    virtual void concatMatrix(const SkMatrix& matrix) = 0;

    // clip
    virtual const Rect& getLocalClipBounds() const = 0;
    virtual bool quickRejectConservative(float left, float top,
            float right, float bottom) const = 0;
    virtual bool clipRect(float left, float top, float right, float bottom, SkRegion::Op op) = 0;
    virtual bool clipPath(const SkPath* path, SkRegion::Op op) = 0;
    virtual bool clipRegion(const SkRegion* region, SkRegion::Op op) = 0;

    // Misc - should be implemented with SkPaint inspection
    virtual void resetPaintFilter() = 0;
    virtual void setupPaintFilter(int clearBits, int setBits) = 0;

// ----------------------------------------------------------------------------
// Canvas draw operations
// ----------------------------------------------------------------------------
    virtual status_t drawColor(int color, SkXfermode::Mode mode) = 0;

    // Bitmap-based
    virtual status_t drawBitmap(const SkBitmap* bitmap, const SkPaint* paint) = 0;
    virtual status_t drawBitmap(const SkBitmap* bitmap, float srcLeft, float srcTop,
            float srcRight, float srcBottom, float dstLeft, float dstTop,
            float dstRight, float dstBottom, const SkPaint* paint) = 0;
    virtual status_t drawBitmapData(const SkBitmap* bitmap, const SkPaint* paint) = 0;
    virtual status_t drawBitmapMesh(const SkBitmap* bitmap, int meshWidth, int meshHeight,
            const float* vertices, const int* colors, const SkPaint* paint) = 0;
    virtual status_t drawPatch(const SkBitmap* bitmap, const Res_png_9patch* patch,
            float left, float top, float right, float bottom, const SkPaint* paint) = 0;

    // Shapes
    virtual status_t drawRect(float left, float top, float right, float bottom,
            const SkPaint* paint) = 0;
    virtual status_t drawRects(const float* rects, int count, const SkPaint* paint) = 0;
    virtual status_t drawRoundRect(float left, float top, float right, float bottom,
            float rx, float ry, const SkPaint* paint) = 0;
    virtual status_t drawCircle(float x, float y, float radius, const SkPaint* paint) = 0;
    virtual status_t drawOval(float left, float top, float right, float bottom,
            const SkPaint* paint) = 0;
    virtual status_t drawArc(float left, float top, float right, float bottom,
            float startAngle, float sweepAngle, bool useCenter, const SkPaint* paint) = 0;
    virtual status_t drawPath(const SkPath* path, const SkPaint* paint) = 0;
    virtual status_t drawLines(const float* points, int count, const SkPaint* paint) = 0;
    virtual status_t drawPoints(const float* points, int count, const SkPaint* paint) = 0;

    // Text
    virtual status_t drawText(const char* text, int bytesCount, int count, float x, float y,
            const float* positions, const SkPaint* paint, float totalAdvance, const Rect& bounds,
            DrawOpMode drawOpMode = kDrawOpMode_Immediate) = 0;
    virtual status_t drawTextOnPath(const char* text, int bytesCount, int count, const SkPath* path,
            float hOffset, float vOffset, const SkPaint* paint) = 0;
    virtual status_t drawPosText(const char* text, int bytesCount, int count,
            const float* positions, const SkPaint* paint) = 0;

// ----------------------------------------------------------------------------
// Canvas draw operations - special
// ----------------------------------------------------------------------------
    virtual status_t drawRenderNode(RenderNode* renderNode, Rect& dirty,
            int32_t replayFlags) = 0;

    // TODO: rename for consistency
    virtual status_t callDrawGLFunction(Functor* functor, Rect& dirty) = 0;
}; // class Renderer

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_RENDERER_H
