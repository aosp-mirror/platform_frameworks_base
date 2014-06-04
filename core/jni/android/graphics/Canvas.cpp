/*
 * Copyright (C) 2006-2007 The Android Open Source Project
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

#include "jni.h"
#include "GraphicsJNI.h"
#include <android_runtime/AndroidRuntime.h>

#include "SkCanvas.h"
#include "SkDevice.h"
#include "SkDrawFilter.h"
#include "SkGraphics.h"
#include "SkPorterDuff.h"
#include "SkShader.h"
#include "SkTemplates.h"

#ifdef USE_MINIKIN
#include <minikin/Layout.h>
#include "MinikinSkia.h"
#include "MinikinUtils.h"
#endif

#include "TextLayout.h"
#include "TextLayoutCache.h"
#include "TypefaceImpl.h"

#include "unicode/ubidi.h"
#include "unicode/ushape.h"

#include <utils/Log.h>

namespace android {

// Holds an SkCanvas reference plus additional native data.
class NativeCanvasWrapper {
public:
    NativeCanvasWrapper(SkCanvas* canvas)
        : mCanvas(canvas) { }

    SkCanvas* getCanvas() const {
        return mCanvas.get();
    }

    void setCanvas(SkCanvas* canvas) {
        SkASSERT(canvas);
        mCanvas.reset(canvas);
    }

private:
    SkAutoTUnref<SkCanvas> mCanvas;
};

class ClipCopier : public SkCanvas::ClipVisitor {
public:
    ClipCopier(SkCanvas* dstCanvas) : m_dstCanvas(dstCanvas) {}

    virtual void clipRect(const SkRect& rect, SkRegion::Op op, bool antialias) {
        m_dstCanvas->clipRect(rect, op, antialias);
    }
    virtual void clipRRect(const SkRRect& rrect, SkRegion::Op op, bool antialias) {
        m_dstCanvas->clipRRect(rrect, op, antialias);
    }
    virtual void clipPath(const SkPath& path, SkRegion::Op op, bool antialias) {
        m_dstCanvas->clipPath(path, op, antialias);
    }

private:
    SkCanvas* m_dstCanvas;
};

// Returns true if the SkCanvas's clip is non-empty.
static jboolean hasNonEmptyClip(const SkCanvas& canvas) {
    bool emptyClip = canvas.isClipEmpty();
    return emptyClip ? JNI_FALSE : JNI_TRUE;
}

class SkCanvasGlue {
public:

    // Get the SkCanvas for a given native handle.
    static inline SkCanvas* getNativeCanvas(jlong nativeHandle) {
        SkASSERT(nativeHandle);
        NativeCanvasWrapper* wrapper = reinterpret_cast<NativeCanvasWrapper*>(nativeHandle);
        SkCanvas* canvas = wrapper->getCanvas();
        SkASSERT(canvas);

        return canvas;
    }

    // Construct an SkCanvas from the bitmap.
    static SkCanvas* createCanvas(SkBitmap* bitmap) {
        if (bitmap) {
            return SkNEW_ARGS(SkCanvas, (*bitmap));
        }

        // Create an empty bitmap device to prevent callers from crashing
        // if they attempt to draw into this canvas.
        SkBitmap emptyBitmap;
        return new SkCanvas(emptyBitmap);
    }

    // Copy the canvas matrix & clip state.
    static void copyCanvasState(SkCanvas* srcCanvas, SkCanvas* dstCanvas) {
        if (srcCanvas && dstCanvas) {
            dstCanvas->setMatrix(srcCanvas->getTotalMatrix());
            if (NULL != srcCanvas->getDevice() && NULL != dstCanvas->getDevice()) {
                ClipCopier copier(dstCanvas);
                srcCanvas->replayClips(&copier);
            }
        }
    }

    // Native JNI handlers
    static void finalizer(JNIEnv* env, jobject clazz, jlong nativeHandle) {
        NativeCanvasWrapper* wrapper = reinterpret_cast<NativeCanvasWrapper*>(nativeHandle);
        delete wrapper;
    }

    // Native wrapper constructor used by Canvas(Bitmap)
    static jlong initRaster(JNIEnv* env, jobject, jlong bitmapHandle) {
        // No check - 0 is a valid bitmapHandle.
        SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
        SkCanvas* canvas = createCanvas(bitmap);

        return reinterpret_cast<jlong>(new NativeCanvasWrapper(canvas));
    }

    // Native wrapper constructor used by Canvas(native_canvas)
    static jlong initCanvas(JNIEnv* env, jobject, jlong canvasHandle) {
        SkCanvas* canvas = reinterpret_cast<SkCanvas*>(canvasHandle);
        return reinterpret_cast<jlong>(new NativeCanvasWrapper(canvas));
    }

    // Set the given bitmap as the new draw target (wrapped in a new SkCanvas),
    // optionally copying canvas matrix & clip state.
    static void setBitmap(JNIEnv* env, jobject, jlong canvasHandle, jlong bitmapHandle,
                          jboolean copyState) {
        NativeCanvasWrapper* wrapper = reinterpret_cast<NativeCanvasWrapper*>(canvasHandle);
        SkCanvas* newCanvas = createCanvas(reinterpret_cast<SkBitmap*>(bitmapHandle));
        NPE_CHECK_RETURN_VOID(env, newCanvas);

        if (copyState == JNI_TRUE) {
            copyCanvasState(wrapper->getCanvas(), newCanvas);
        }

        // setCanvas() unrefs the old canvas.
        wrapper->setCanvas(newCanvas);
    }

    static void freeCaches(JNIEnv* env, jobject) {
        SkGraphics::PurgeFontCache();
    }

    static void freeTextLayoutCaches(JNIEnv* env, jobject) {
        TextLayoutEngine::getInstance().purgeCaches();
    }

    static jboolean isOpaque(JNIEnv*, jobject, jlong canvasHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        bool result = canvas->getDevice()->accessBitmap(false).isOpaque();
        return result ? JNI_TRUE : JNI_FALSE;
    }

    static jint getWidth(JNIEnv*, jobject, jlong canvasHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        int width = canvas->getDevice()->accessBitmap(false).width();
        return static_cast<jint>(width);
    }

    static jint getHeight(JNIEnv*, jobject, jlong canvasHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        int height = canvas->getDevice()->accessBitmap(false).height();
        return static_cast<jint>(height);
    }

    static jint save(JNIEnv*, jobject, jlong canvasHandle, jint flagsHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkCanvas::SaveFlags flags = static_cast<SkCanvas::SaveFlags>(flagsHandle);
        return static_cast<jint>(canvas->save(flags));
    }

    static jint saveLayer(JNIEnv* env, jobject, jlong canvasHandle,
                          jfloat l, jfloat t, jfloat r, jfloat b,
                          jlong paintHandle, jint flags) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint  = reinterpret_cast<SkPaint*>(paintHandle);
        SkRect bounds;
        bounds.set(l, t, r, b);
        int result = canvas->saveLayer(&bounds, paint,
                                      static_cast<SkCanvas::SaveFlags>(flags));
        return static_cast<jint>(result);
    }

    static jint saveLayerAlpha(JNIEnv* env, jobject, jlong canvasHandle,
                               jfloat l, jfloat t, jfloat r, jfloat b,
                               jint alpha, jint flags) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkRect  bounds;
        bounds.set(l, t, r, b);
        int result = canvas->saveLayerAlpha(&bounds, alpha,
                                      static_cast<SkCanvas::SaveFlags>(flags));
        return static_cast<jint>(result);
    }

    static void restore(JNIEnv* env, jobject, jlong canvasHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        if (canvas->getSaveCount() <= 1) {  // cannot restore anymore
            doThrowISE(env, "Underflow in restore");
            return;
        }
        canvas->restore();
    }

    static jint getSaveCount(JNIEnv*, jobject, jlong canvasHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        return static_cast<jint>(canvas->getSaveCount());
    }

    static void restoreToCount(JNIEnv* env, jobject, jlong canvasHandle,
                               jint restoreCount) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        if (restoreCount < 1) {
            doThrowIAE(env, "Underflow in restoreToCount");
            return;
        }
        canvas->restoreToCount(restoreCount);
    }

    static void translate(JNIEnv*, jobject, jlong canvasHandle,
                          jfloat dx, jfloat dy) {
        getNativeCanvas(canvasHandle)->translate(dx, dy);
    }

    static void scale__FF(JNIEnv*, jobject, jlong canvasHandle,
                          jfloat sx, jfloat sy) {
        getNativeCanvas(canvasHandle)->scale(sx, sy);
    }

    static void rotate__F(JNIEnv*, jobject, jlong canvasHandle,
                          jfloat degrees) {
        getNativeCanvas(canvasHandle)->rotate(degrees);
    }

    static void skew__FF(JNIEnv*, jobject, jlong canvasHandle,
                         jfloat sx, jfloat sy) {
        getNativeCanvas(canvasHandle)->skew(sx, sy);
    }

    static void concat(JNIEnv* env, jobject, jlong canvasHandle,
                       jlong matrixHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        const SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        canvas->concat(*matrix);
    }

    static void setMatrix(JNIEnv* env, jobject, jlong canvasHandle,
                          jlong matrixHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        const SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        if (NULL == matrix) {
            canvas->resetMatrix();
        } else {
            canvas->setMatrix(*matrix);
        }
    }

    static jboolean clipRect(JNIEnv*, jobject, jlong canvasHandle,
                                  jfloat left, jfloat top, jfloat right,
                                  jfloat bottom, jint op) {
        SkRect  r;
        r.set(left, top, right, bottom);
        SkCanvas* c = getNativeCanvas(canvasHandle);
        c->clipRect(r, static_cast<SkRegion::Op>(op));
        return hasNonEmptyClip(*c);
    }

    static jboolean clipPath(JNIEnv* env, jobject, jlong canvasHandle,
                             jlong pathHandle, jint op) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        canvas->clipPath(*reinterpret_cast<SkPath*>(pathHandle),
                static_cast<SkRegion::Op>(op));
        return hasNonEmptyClip(*canvas);
    }

    static jboolean clipRegion(JNIEnv* env, jobject, jlong canvasHandle,
                               jlong deviceRgnHandle, jint op) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkRegion* deviceRgn = reinterpret_cast<SkRegion*>(deviceRgnHandle);
        canvas->clipRegion(*deviceRgn, static_cast<SkRegion::Op>(op));
        return hasNonEmptyClip(*canvas);
    }

    static void setDrawFilter(JNIEnv* env, jobject, jlong canvasHandle,
                              jlong filterHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        canvas->setDrawFilter(reinterpret_cast<SkDrawFilter*>(filterHandle));
    }

    static jboolean quickReject__Path(JNIEnv* env, jobject, jlong canvasHandle,
                                       jlong pathHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        bool result = canvas->quickReject(*reinterpret_cast<SkPath*>(pathHandle));
        return result ? JNI_TRUE : JNI_FALSE;
    }

    static jboolean quickReject__FFFF(JNIEnv* env, jobject, jlong canvasHandle,
                                       jfloat left, jfloat top, jfloat right,
                                       jfloat bottom) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkRect r;
        r.set(left, top, right, bottom);
        bool result = canvas->quickReject(r);
        return result ? JNI_TRUE : JNI_FALSE;
    }

    static void drawRGB(JNIEnv* env, jobject, jlong canvasHandle,
                        jint r, jint g, jint b) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        canvas->drawARGB(0xFF, r, g, b);
    }

    static void drawARGB(JNIEnv* env, jobject, jlong canvasHandle,
                         jint a, jint r, jint g, jint b) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        canvas->drawARGB(a, r, g, b);
    }

    static void drawColor__I(JNIEnv* env, jobject, jlong canvasHandle,
                             jint color) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        canvas->drawColor(color);
    }

    static void drawColor__II(JNIEnv* env, jobject, jlong canvasHandle,
                              jint color, jint modeHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPorterDuff::Mode mode = static_cast<SkPorterDuff::Mode>(modeHandle);
        canvas->drawColor(color, SkPorterDuff::ToXfermodeMode(mode));
    }

    static void drawPaint(JNIEnv* env, jobject, jlong canvasHandle,
                          jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        canvas->drawPaint(*paint);
    }

    static void doPoints(JNIEnv* env, jlong canvasHandle,
                         jfloatArray jptsArray, jint offset, jint count,
                         jlong paintHandle, jint modeHandle) {
        NPE_CHECK_RETURN_VOID(env, jptsArray);
        SkCanvas::PointMode mode = static_cast<SkCanvas::PointMode>(modeHandle);
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        const SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);

        AutoJavaFloatArray autoPts(env, jptsArray);
        float* floats = autoPts.ptr();
        const int length = autoPts.length();

        if ((offset | count) < 0 || offset + count > length) {
            doThrowAIOOBE(env);
            return;
        }

        // now convert the floats into SkPoints
        count >>= 1;    // now it is the number of points
        SkAutoSTMalloc<32, SkPoint> storage(count);
        SkPoint* pts = storage.get();
        const float* src = floats + offset;
        for (int i = 0; i < count; i++) {
            pts[i].set(src[0], src[1]);
            src += 2;
        }
        canvas->drawPoints(mode, count, pts, *paint);
    }

    static void drawPoints(JNIEnv* env, jobject, jlong canvasHandle,
                           jfloatArray jptsArray, jint offset,
                           jint count, jlong paintHandle) {
        doPoints(env, canvasHandle, jptsArray, offset, count, paintHandle,
                 SkCanvas::kPoints_PointMode);
    }

    static void drawLines(JNIEnv* env, jobject, jlong canvasHandle,
                          jfloatArray jptsArray, jint offset, jint count,
                          jlong paintHandle) {
        doPoints(env, canvasHandle, jptsArray, offset, count, paintHandle,
                 SkCanvas::kLines_PointMode);
    }

    static void drawPoint(JNIEnv*, jobject, jlong canvasHandle, jfloat x, jfloat y,
                          jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        const SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        canvas->drawPoint(x, y, *paint);
    }

    static void drawLine__FFFFPaint(JNIEnv* env, jobject, jlong canvasHandle,
                                    jfloat startX, jfloat startY, jfloat stopX,
                                    jfloat stopY, jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        canvas->drawLine(startX, startY, stopX, stopY, *paint);
    }

    static void drawRect__FFFFPaint(JNIEnv* env, jobject, jlong canvasHandle,
                                    jfloat left, jfloat top, jfloat right,
                                    jfloat bottom, jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        canvas->drawRectCoords(left, top, right, bottom, *paint);
    }

    static void drawOval(JNIEnv* env, jobject, jlong canvasHandle, jobject joval,
                         jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        SkRect oval;
        GraphicsJNI::jrectf_to_rect(env, joval, &oval);
        canvas->drawOval(oval, *paint);
    }

    static void drawCircle(JNIEnv* env, jobject, jlong canvasHandle, jfloat cx,
                           jfloat cy, jfloat radius, jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        canvas->drawCircle(cx, cy, radius, *paint);
    }

    static void drawArc(JNIEnv* env, jobject, jlong canvasHandle, jobject joval,
                        jfloat startAngle, jfloat sweepAngle,
                        jboolean useCenter, jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        SkRect oval;
        GraphicsJNI::jrectf_to_rect(env, joval, &oval);
        canvas->drawArc(oval, startAngle, sweepAngle, useCenter, *paint);
    }

    static void drawRoundRect(JNIEnv* env, jobject, jlong canvasHandle,
            jfloat left, jfloat top, jfloat right, jfloat bottom, jfloat rx, jfloat ry,
            jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        SkRect rect = SkRect::MakeLTRB(left, top, right, bottom);
        canvas->drawRoundRect(rect, rx, ry, *paint);
    }

    static void drawPath(JNIEnv* env, jobject, jlong canvasHandle, jlong pathHandle,
                         jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        canvas->drawPath(*path, *paint);
    }

    static void drawBitmap__BitmapFFPaint(JNIEnv* env, jobject jcanvas,
                                          jlong canvasHandle, jlong bitmapHandle,
                                          jfloat left, jfloat top,
                                          jlong paintHandle, jint canvasDensity,
                                          jint screenDensity, jint bitmapDensity) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);

        if (canvasDensity == bitmapDensity || canvasDensity == 0
                || bitmapDensity == 0) {
            if (screenDensity != 0 && screenDensity != bitmapDensity) {
                SkPaint filteredPaint;
                if (paint) {
                    filteredPaint = *paint;
                }
                filteredPaint.setFilterLevel(SkPaint::kLow_FilterLevel);
                canvas->drawBitmap(*bitmap, left, top, &filteredPaint);
            } else {
                canvas->drawBitmap(*bitmap, left, top, paint);
            }
        } else {
            canvas->save();
            SkScalar scale = canvasDensity / (float)bitmapDensity;
            canvas->translate(left, top);
            canvas->scale(scale, scale);

            SkPaint filteredPaint;
            if (paint) {
                filteredPaint = *paint;
            }
            filteredPaint.setFilterLevel(SkPaint::kLow_FilterLevel);

            canvas->drawBitmap(*bitmap, 0, 0, &filteredPaint);

            canvas->restore();
        }
    }

    static void doDrawBitmap(JNIEnv* env, SkCanvas* canvas, SkBitmap* bitmap,
                        jobject srcIRect, const SkRect& dst, SkPaint* paint,
                        jint screenDensity, jint bitmapDensity) {
        SkIRect    src, *srcPtr = NULL;

        if (NULL != srcIRect) {
            GraphicsJNI::jrect_to_irect(env, srcIRect, &src);
            srcPtr = &src;
        }

        if (screenDensity != 0 && screenDensity != bitmapDensity) {
            SkPaint filteredPaint;
            if (paint) {
                filteredPaint = *paint;
            }
            filteredPaint.setFilterLevel(SkPaint::kLow_FilterLevel);
            canvas->drawBitmapRect(*bitmap, srcPtr, dst, &filteredPaint);
        } else {
            canvas->drawBitmapRect(*bitmap, srcPtr, dst, paint);
        }
    }

    static void drawBitmapRF(JNIEnv* env, jobject, jlong canvasHandle,
                             jlong bitmapHandle, jobject srcIRect,
                             jobject dstRectF, jlong paintHandle,
                             jint screenDensity, jint bitmapDensity) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        SkRect      dst;
        GraphicsJNI::jrectf_to_rect(env, dstRectF, &dst);
        doDrawBitmap(env, canvas, bitmap, srcIRect, dst, paint,
                screenDensity, bitmapDensity);
    }

    static void drawBitmapRR(JNIEnv* env, jobject, jlong canvasHandle,
                             jlong bitmapHandle, jobject srcIRect,
                             jobject dstRect, jlong paintHandle,
                             jint screenDensity, jint bitmapDensity) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        SkRect      dst;
        GraphicsJNI::jrect_to_rect(env, dstRect, &dst);
        doDrawBitmap(env, canvas, bitmap, srcIRect, dst, paint,
                screenDensity, bitmapDensity);
    }

    static void drawBitmapArray(JNIEnv* env, jobject, jlong canvasHandle,
                                jintArray jcolors, jint offset, jint stride,
                                jfloat x, jfloat y, jint width, jint height,
                                jboolean hasAlpha, jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        SkBitmap    bitmap;
        bitmap.setConfig(hasAlpha ? SkBitmap::kARGB_8888_Config :
                         SkBitmap::kRGB_565_Config, width, height);
        if (!bitmap.allocPixels()) {
            return;
        }

        if (!GraphicsJNI::SetPixels(env, jcolors, offset, stride,
                0, 0, width, height, bitmap, true)) {
            return;
        }

        canvas->drawBitmap(bitmap, x, y, paint);
    }

    static void drawBitmapMatrix(JNIEnv* env, jobject, jlong canvasHandle,
                                 jlong bitmapHandle, jlong matrixHandle,
                                 jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        const SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
        const SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        const SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        canvas->drawBitmapMatrix(*bitmap, *matrix, paint);
    }

    static void drawBitmapMesh(JNIEnv* env, jobject, jlong canvasHandle,
                          jlong bitmapHandle, jint meshWidth, jint meshHeight,
                          jfloatArray jverts, jint vertIndex, jintArray jcolors,
                          jint colorIndex, jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        const SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
        const SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);

        const int ptCount = (meshWidth + 1) * (meshHeight + 1);
        const int indexCount = meshWidth * meshHeight * 6;

        AutoJavaFloatArray  vertA(env, jverts, vertIndex + (ptCount << 1));
        AutoJavaIntArray    colorA(env, jcolors, colorIndex + ptCount);

        /*  Our temp storage holds 2 or 3 arrays.
            texture points [ptCount * sizeof(SkPoint)]
            optionally vertex points [ptCount * sizeof(SkPoint)] if we need a
                copy to convert from float to fixed
            indices [ptCount * sizeof(uint16_t)]
        */
        ssize_t storageSize = ptCount * sizeof(SkPoint); // texs[]
        storageSize += indexCount * sizeof(uint16_t);  // indices[]

        SkAutoMalloc storage(storageSize);
        SkPoint* texs = (SkPoint*)storage.get();
        SkPoint* verts;
        uint16_t* indices;
#ifdef SK_SCALAR_IS_FLOAT
        verts = (SkPoint*)(vertA.ptr() + vertIndex);
        indices = (uint16_t*)(texs + ptCount);
#else
        SkASSERT(false);
#endif

        // cons up texture coordinates and indices
        {
            const SkScalar w = SkIntToScalar(bitmap->width());
            const SkScalar h = SkIntToScalar(bitmap->height());
            const SkScalar dx = w / meshWidth;
            const SkScalar dy = h / meshHeight;

            SkPoint* texsPtr = texs;
            SkScalar y = 0;
            for (int i = 0; i <= meshHeight; i++) {
                if (i == meshHeight) {
                    y = h;  // to ensure numerically we hit h exactly
                }
                SkScalar x = 0;
                for (int j = 0; j < meshWidth; j++) {
                    texsPtr->set(x, y);
                    texsPtr += 1;
                    x += dx;
                }
                texsPtr->set(w, y);
                texsPtr += 1;
                y += dy;
            }
            SkASSERT(texsPtr - texs == ptCount);
        }

        // cons up indices
        {
            uint16_t* indexPtr = indices;
            int index = 0;
            for (int i = 0; i < meshHeight; i++) {
                for (int j = 0; j < meshWidth; j++) {
                    // lower-left triangle
                    *indexPtr++ = index;
                    *indexPtr++ = index + meshWidth + 1;
                    *indexPtr++ = index + meshWidth + 2;
                    // upper-right triangle
                    *indexPtr++ = index;
                    *indexPtr++ = index + meshWidth + 2;
                    *indexPtr++ = index + 1;
                    // bump to the next cell
                    index += 1;
                }
                // bump to the next row
                index += 1;
            }
            SkASSERT(indexPtr - indices == indexCount);
            SkASSERT((char*)indexPtr - (char*)storage.get() == storageSize);
        }

        // double-check that we have legal indices
#ifdef SK_DEBUG
        {
            for (int i = 0; i < indexCount; i++) {
                SkASSERT((unsigned)indices[i] < (unsigned)ptCount);
            }
        }
#endif

        // cons-up a shader for the bitmap
        SkPaint tmpPaint;
        if (paint) {
            tmpPaint = *paint;
        }
        SkShader* shader = SkShader::CreateBitmapShader(*bitmap,
                        SkShader::kClamp_TileMode, SkShader::kClamp_TileMode);
        SkSafeUnref(tmpPaint.setShader(shader));

        canvas->drawVertices(SkCanvas::kTriangles_VertexMode, ptCount, verts,
                             texs, (const SkColor*)colorA.ptr(), NULL, indices,
                             indexCount, tmpPaint);
    }

    static void drawVertices(JNIEnv* env, jobject, jlong canvasHandle,
                             jint modeHandle, jint vertexCount,
                             jfloatArray jverts, jint vertIndex,
                             jfloatArray jtexs, jint texIndex,
                             jintArray jcolors, jint colorIndex,
                             jshortArray jindices, jint indexIndex,
                             jint indexCount, jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkCanvas::VertexMode mode = static_cast<SkCanvas::VertexMode>(modeHandle);
        const SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);

        AutoJavaFloatArray  vertA(env, jverts, vertIndex + vertexCount);
        AutoJavaFloatArray  texA(env, jtexs, texIndex + vertexCount);
        AutoJavaIntArray    colorA(env, jcolors, colorIndex + vertexCount);
        AutoJavaShortArray  indexA(env, jindices, indexIndex + indexCount);

        const int ptCount = vertexCount >> 1;

        SkPoint* verts;
        SkPoint* texs = NULL;
#ifdef SK_SCALAR_IS_FLOAT
        verts = (SkPoint*)(vertA.ptr() + vertIndex);
        if (jtexs != NULL) {
            texs = (SkPoint*)(texA.ptr() + texIndex);
        }
#else
        SkASSERT(false);
#endif

        const SkColor* colors = NULL;
        const uint16_t* indices = NULL;
        if (jcolors != NULL) {
            colors = (const SkColor*)(colorA.ptr() + colorIndex);
        }
        if (jindices != NULL) {
            indices = (const uint16_t*)(indexA.ptr() + indexIndex);
        }

        canvas->drawVertices(mode, ptCount, verts, texs, colors, NULL,
                             indices, indexCount, *paint);
    }


    static void drawText___CIIFFIPaintTypeface(JNIEnv* env, jobject, jlong canvasHandle,
                                               jcharArray text, jint index, jint count,
                                               jfloat x, jfloat y, jint flags,
                                               jlong paintHandle, jlong typefaceHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);
        jchar* textArray = env->GetCharArrayElements(text, NULL);
        drawTextWithGlyphs(canvas, textArray + index, 0, count, x, y, flags, paint, typeface);
        env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
    }

    static void drawText__StringIIFFIPaintTypeface(JNIEnv* env, jobject,
                                                   jlong canvasHandle, jstring text,
                                                   jint start, jint end,
                                                   jfloat x, jfloat y, jint flags,
                                                   jlong paintHandle, jlong typefaceHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);
        const jchar* textArray = env->GetStringChars(text, NULL);
        drawTextWithGlyphs(canvas, textArray, start, end, x, y, flags, paint, typeface);
        env->ReleaseStringChars(text, textArray);
    }

#ifdef USE_MINIKIN
    class DrawTextFunctor {
    public:
        DrawTextFunctor(const Layout& layout, SkCanvas* canvas, jfloat x, jfloat y, SkPaint* paint,
                    uint16_t* glyphs, SkPoint* pos)
                : layout(layout), canvas(canvas), x(x), y(y), paint(paint), glyphs(glyphs),
                    pos(pos) { }

        void operator()(SkTypeface* t, size_t start, size_t end) {
            for (size_t i = start; i < end; i++) {
                glyphs[i] = layout.getGlyphId(i);
                pos[i].fX = x + layout.getX(i);
                pos[i].fY = y + layout.getY(i);
            }
            paint->setTypeface(t);
            canvas->drawPosText(glyphs + start, (end - start) << 1, pos + start, *paint);
        }
    private:
        const Layout& layout;
        SkCanvas* canvas;
        jfloat x;
        jfloat y;
        SkPaint* paint;
        uint16_t* glyphs;
        SkPoint* pos;
    };

    static void drawGlyphsToSkia(SkCanvas* canvas, SkPaint* paint, const Layout& layout, float x, float y) {
        size_t nGlyphs = layout.nGlyphs();
        uint16_t* glyphs = new uint16_t[nGlyphs];
        SkPoint* pos = new SkPoint[nGlyphs];

        x += MinikinUtils::xOffsetForTextAlign(paint, layout);
        SkPaint::Align align = paint->getTextAlign();
        paint->setTextAlign(SkPaint::kLeft_Align);
        paint->setTextEncoding(SkPaint::kGlyphID_TextEncoding);
        DrawTextFunctor f(layout, canvas, x, y, paint, glyphs, pos);
        MinikinUtils::forFontRun(layout, f);
        doDrawTextDecorations(canvas, x, y, layout.getAdvance(), paint);
        paint->setTextAlign(align);
        delete[] glyphs;
        delete[] pos;
    }
#endif

    static void drawTextWithGlyphs(SkCanvas* canvas, const jchar* textArray,
            int start, int end,
            jfloat x, jfloat y, int flags, SkPaint* paint, TypefaceImpl* typeface) {

        jint count = end - start;
        drawTextWithGlyphs(canvas, textArray + start, 0, count, count, x, y, flags, paint, typeface);
    }

    static void drawTextWithGlyphs(SkCanvas* canvas, const jchar* textArray,
            int start, int count, int contextCount,
            jfloat x, jfloat y, int flags, SkPaint* paint, TypefaceImpl* typeface) {

#ifdef USE_MINIKIN
        Layout layout;
        MinikinUtils::SetLayoutProperties(&layout, paint, flags, typeface);
        layout.doLayout(textArray + start, count);
        drawGlyphsToSkia(canvas, paint, layout, x, y);
#else
        sp<TextLayoutValue> value = TextLayoutEngine::getInstance().getValue(paint,
                textArray, start, count, contextCount, flags);
        if (value == NULL) {
            return;
        }
        SkPaint::Align align = paint->getTextAlign();
        if (align == SkPaint::kCenter_Align) {
            x -= 0.5 * value->getTotalAdvance();
        } else if (align == SkPaint::kRight_Align) {
            x -= value->getTotalAdvance();
        }
        paint->setTextAlign(SkPaint::kLeft_Align);
        doDrawGlyphsPos(canvas, value->getGlyphs(), value->getPos(), 0, value->getGlyphsCount(), x, y, flags, paint);
        doDrawTextDecorations(canvas, x, y, value->getTotalAdvance(), paint);
        paint->setTextAlign(align);
#endif
    }

// Same values used by Skia
#define kStdStrikeThru_Offset   (-6.0f / 21.0f)
#define kStdUnderline_Offset    (1.0f / 9.0f)
#define kStdUnderline_Thickness (1.0f / 18.0f)

static void doDrawTextDecorations(SkCanvas* canvas, jfloat x, jfloat y, jfloat length, SkPaint* paint) {
    uint32_t flags;
    SkDrawFilter* drawFilter = canvas->getDrawFilter();
    if (drawFilter) {
        SkPaint paintCopy(*paint);
        drawFilter->filter(&paintCopy, SkDrawFilter::kText_Type);
        flags = paintCopy.getFlags();
    } else {
        flags = paint->getFlags();
    }
    if (flags & (SkPaint::kUnderlineText_Flag | SkPaint::kStrikeThruText_Flag)) {
        SkScalar left = x;
        SkScalar right = x + length;
        float textSize = paint->getTextSize();
        float strokeWidth = fmax(textSize * kStdUnderline_Thickness, 1.0f);
        if (flags & SkPaint::kUnderlineText_Flag) {
            SkScalar top = y + textSize * kStdUnderline_Offset - 0.5f * strokeWidth;
            SkScalar bottom = y + textSize * kStdUnderline_Offset + 0.5f * strokeWidth;
            canvas->drawRectCoords(left, top, right, bottom, *paint);
        }
        if (flags & SkPaint::kStrikeThruText_Flag) {
            SkScalar top = y + textSize * kStdStrikeThru_Offset - 0.5f * strokeWidth;
            SkScalar bottom = y + textSize * kStdStrikeThru_Offset + 0.5f * strokeWidth;
            canvas->drawRectCoords(left, top, right, bottom, *paint);
        }
    }
}

    static void doDrawGlyphs(SkCanvas* canvas, const jchar* glyphArray, int index, int count,
            jfloat x, jfloat y, int flags, SkPaint* paint) {
        // Beware: this needs Glyph encoding (already done on the Paint constructor)
        canvas->drawText(glyphArray + index * 2, count * 2, x, y, *paint);
    }

    static void doDrawGlyphsPos(SkCanvas* canvas, const jchar* glyphArray, const jfloat* posArray,
            int index, int count, jfloat x, jfloat y, int flags, SkPaint* paint) {
        SkPoint* posPtr = new SkPoint[count];
        for (int indx = 0; indx < count; indx++) {
            posPtr[indx].fX = x + posArray[indx * 2];
            posPtr[indx].fY = y + posArray[indx * 2 + 1];
        }
        canvas->drawPosText(glyphArray, count << 1, posPtr, *paint);
        delete[] posPtr;
    }

    static void drawTextRun___CIIIIFFIPaintTypeface(
            JNIEnv* env, jobject, jlong canvasHandle, jcharArray text, jint index,
            jint count, jint contextIndex, jint contextCount,
            jfloat x, jfloat y, jint dirFlags, jlong paintHandle, jlong typefaceHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);

        jchar* chars = env->GetCharArrayElements(text, NULL);
        drawTextWithGlyphs(canvas, chars + contextIndex, index - contextIndex,
                count, contextCount, x, y, dirFlags, paint, typeface);
        env->ReleaseCharArrayElements(text, chars, JNI_ABORT);
    }

    static void drawTextRun__StringIIIIFFIPaintTypeface(
            JNIEnv* env, jobject obj, jlong canvasHandle, jstring text, jint start,
            jint end, jint contextStart, jint contextEnd,
            jfloat x, jfloat y, jint dirFlags, jlong paintHandle, jlong typefaceHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefaceHandle);

        jint count = end - start;
        jint contextCount = contextEnd - contextStart;
        const jchar* chars = env->GetStringChars(text, NULL);
        drawTextWithGlyphs(canvas, chars + contextStart, start - contextStart,
                count, contextCount, x, y, dirFlags, paint, typeface);
        env->ReleaseStringChars(text, chars);
    }

    static void drawPosText___CII_FPaint(JNIEnv* env, jobject, jlong canvasHandle,
                                         jcharArray text, jint index, jint count,
                                         jfloatArray pos, jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        jchar* textArray = text ? env->GetCharArrayElements(text, NULL) : NULL;
        jsize textCount = text ? env->GetArrayLength(text) : NULL;
        float* posArray = pos ? env->GetFloatArrayElements(pos, NULL) : NULL;
        int posCount = pos ? env->GetArrayLength(pos) >> 1: 0;
        SkPoint* posPtr = posCount > 0 ? new SkPoint[posCount] : NULL;
        int indx;
        for (indx = 0; indx < posCount; indx++) {
            posPtr[indx].fX = posArray[indx << 1];
            posPtr[indx].fY = posArray[(indx << 1) + 1];
        }

        SkPaint::TextEncoding encoding = paint->getTextEncoding();
        paint->setTextEncoding(SkPaint::kUTF16_TextEncoding);
        canvas->drawPosText(textArray + index, count << 1, posPtr, *paint);
        paint->setTextEncoding(encoding);

        if (text) {
            env->ReleaseCharArrayElements(text, textArray, 0);
        }
        if (pos) {
            env->ReleaseFloatArrayElements(pos, posArray, 0);
        }
        delete[] posPtr;
    }

    static void drawPosText__String_FPaint(JNIEnv* env, jobject,
                                           jlong canvasHandle, jstring text,
                                           jfloatArray pos,
                                           jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        const void* text_ = text ? env->GetStringChars(text, NULL) : NULL;
        int byteLength = text ? env->GetStringLength(text) : 0;
        float* posArray = pos ? env->GetFloatArrayElements(pos, NULL) : NULL;
        int posCount = pos ? env->GetArrayLength(pos) >> 1: 0;
        SkPoint* posPtr = posCount > 0 ? new SkPoint[posCount] : NULL;

        for (int indx = 0; indx < posCount; indx++) {
            posPtr[indx].fX = posArray[indx << 1];
            posPtr[indx].fY = posArray[(indx << 1) + 1];
        }

        SkPaint::TextEncoding encoding = paint->getTextEncoding();
        paint->setTextEncoding(SkPaint::kUTF16_TextEncoding);
        canvas->drawPosText(text_, byteLength << 1, posPtr, *paint);
        paint->setTextEncoding(encoding);

        if (text) {
            env->ReleaseStringChars(text, (const jchar*) text_);
        }
        if (pos) {
            env->ReleaseFloatArrayElements(pos, posArray, 0);
        }
        delete[] posPtr;
    }

    static void drawTextOnPath___CIIPathFFPaint(JNIEnv* env, jobject,
            jlong canvasHandle, jcharArray text, jint index, jint count,
            jlong pathHandle, jfloat hOffset, jfloat vOffset, jint bidiFlags, jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);

        jchar* textArray = env->GetCharArrayElements(text, NULL);
        TextLayout::drawTextOnPath(paint, textArray + index, count, bidiFlags, hOffset, vOffset,
                                   path, canvas);
        env->ReleaseCharArrayElements(text, textArray, 0);
    }

    static void drawTextOnPath__StringPathFFPaint(JNIEnv* env, jobject,
            jlong canvasHandle, jstring text, jlong pathHandle,
            jfloat hOffset, jfloat vOffset, jint bidiFlags, jlong paintHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
        SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
        const jchar* text_ = env->GetStringChars(text, NULL);
        int count = env->GetStringLength(text);
        TextLayout::drawTextOnPath(paint, text_, count, bidiFlags, hOffset, vOffset,
                                   path, canvas);
        env->ReleaseStringChars(text, text_);
    }


    // This function is a mirror of SkCanvas::getClipBounds except that it does
    // not outset the edge of the clip to account for anti-aliasing. There is
    // a skia bug to investigate pushing this logic into back into skia.
    // (see https://code.google.com/p/skia/issues/detail?id=1303)
    static bool getHardClipBounds(SkCanvas* canvas, SkRect* bounds) {
        SkIRect ibounds;
        if (!canvas->getClipDeviceBounds(&ibounds)) {
            return false;
        }

        SkMatrix inverse;
        // if we can't invert the CTM, we can't return local clip bounds
        if (!canvas->getTotalMatrix().invert(&inverse)) {
            if (bounds) {
                bounds->setEmpty();
            }
            return false;
        }

        if (NULL != bounds) {
            SkRect r = SkRect::Make(ibounds);
            inverse.mapRect(bounds, r);
        }
        return true;
    }

    static jboolean getClipBounds(JNIEnv* env, jobject, jlong canvasHandle,
                                  jobject bounds) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkRect   r;
        SkIRect ir;
        bool result = getHardClipBounds(canvas, &r);

        if (!result) {
            r.setEmpty();
        }
        r.round(&ir);

        (void)GraphicsJNI::irect_to_jrect(ir, env, bounds);
        return result ? JNI_TRUE : JNI_FALSE;
    }

    static void getCTM(JNIEnv* env, jobject, jlong canvasHandle,
                       jlong matrixHandle) {
        SkCanvas* canvas = getNativeCanvas(canvasHandle);
        SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
        *matrix = canvas->getTotalMatrix();
    }
};

static JNINativeMethod gCanvasMethods[] = {
    {"finalizer", "(J)V", (void*) SkCanvasGlue::finalizer},
    {"initRaster", "(J)J", (void*) SkCanvasGlue::initRaster},
    {"initCanvas", "(J)J", (void*) SkCanvasGlue::initCanvas},
    {"native_setBitmap", "(JJZ)V", (void*) SkCanvasGlue::setBitmap},
    {"native_isOpaque","(J)Z", (void*) SkCanvasGlue::isOpaque},
    {"native_getWidth","(J)I", (void*) SkCanvasGlue::getWidth},
    {"native_getHeight","(J)I", (void*) SkCanvasGlue::getHeight},
    {"native_save","(JI)I", (void*) SkCanvasGlue::save},
    {"native_saveLayer","(JFFFFJI)I", (void*) SkCanvasGlue::saveLayer},
    {"native_saveLayerAlpha","(JFFFFII)I", (void*) SkCanvasGlue::saveLayerAlpha},
    {"native_restore","(J)V", (void*) SkCanvasGlue::restore},
    {"native_getSaveCount","(J)I", (void*) SkCanvasGlue::getSaveCount},
    {"native_restoreToCount","(JI)V", (void*) SkCanvasGlue::restoreToCount},
    {"native_translate","(JFF)V", (void*) SkCanvasGlue::translate},
    {"native_scale","(JFF)V", (void*) SkCanvasGlue::scale__FF},
    {"native_rotate","(JF)V", (void*) SkCanvasGlue::rotate__F},
    {"native_skew","(JFF)V", (void*) SkCanvasGlue::skew__FF},
    {"native_concat","(JJ)V", (void*) SkCanvasGlue::concat},
    {"native_setMatrix","(JJ)V", (void*) SkCanvasGlue::setMatrix},
    {"native_clipRect","(JFFFFI)Z", (void*) SkCanvasGlue::clipRect},
    {"native_clipPath","(JJI)Z", (void*) SkCanvasGlue::clipPath},
    {"native_clipRegion","(JJI)Z", (void*) SkCanvasGlue::clipRegion},
    {"nativeSetDrawFilter", "(JJ)V", (void*) SkCanvasGlue::setDrawFilter},
    {"native_getClipBounds","(JLandroid/graphics/Rect;)Z",
        (void*) SkCanvasGlue::getClipBounds},
    {"native_getCTM", "(JJ)V", (void*)SkCanvasGlue::getCTM},
    {"native_quickReject","(JJ)Z", (void*) SkCanvasGlue::quickReject__Path},
    {"native_quickReject","(JFFFF)Z", (void*)SkCanvasGlue::quickReject__FFFF},
    {"native_drawRGB","(JIII)V", (void*) SkCanvasGlue::drawRGB},
    {"native_drawARGB","(JIIII)V", (void*) SkCanvasGlue::drawARGB},
    {"native_drawColor","(JI)V", (void*) SkCanvasGlue::drawColor__I},
    {"native_drawColor","(JII)V", (void*) SkCanvasGlue::drawColor__II},
    {"native_drawPaint","(JJ)V", (void*) SkCanvasGlue::drawPaint},
    {"native_drawPoint", "(JFFJ)V", (void*) SkCanvasGlue::drawPoint},
    {"native_drawPoints", "(J[FIIJ)V", (void*) SkCanvasGlue::drawPoints},
    {"native_drawLines", "(J[FIIJ)V", (void*) SkCanvasGlue::drawLines},
    {"native_drawLine","(JFFFFJ)V", (void*) SkCanvasGlue::drawLine__FFFFPaint},
    {"native_drawRect","(JFFFFJ)V", (void*) SkCanvasGlue::drawRect__FFFFPaint},
    {"native_drawOval","(JLandroid/graphics/RectF;J)V",
        (void*) SkCanvasGlue::drawOval},
    {"native_drawCircle","(JFFFJ)V", (void*) SkCanvasGlue::drawCircle},
    {"native_drawArc","(JLandroid/graphics/RectF;FFZJ)V",
        (void*) SkCanvasGlue::drawArc},
    {"native_drawRoundRect","(JFFFFFFJ)V",
        (void*) SkCanvasGlue::drawRoundRect},
    {"native_drawPath","(JJJ)V", (void*) SkCanvasGlue::drawPath},
    {"native_drawBitmap","(JJFFJIII)V",
        (void*) SkCanvasGlue::drawBitmap__BitmapFFPaint},
    {"native_drawBitmap","(JJLandroid/graphics/Rect;Landroid/graphics/RectF;JII)V",
        (void*) SkCanvasGlue::drawBitmapRF},
    {"native_drawBitmap","(JJLandroid/graphics/Rect;Landroid/graphics/Rect;JII)V",
        (void*) SkCanvasGlue::drawBitmapRR},
    {"native_drawBitmap", "(J[IIIFFIIZJ)V",
    (void*)SkCanvasGlue::drawBitmapArray},
    {"nativeDrawBitmapMatrix", "(JJJJ)V",
        (void*)SkCanvasGlue::drawBitmapMatrix},
    {"nativeDrawBitmapMesh", "(JJII[FI[IIJ)V",
        (void*)SkCanvasGlue::drawBitmapMesh},
    {"nativeDrawVertices", "(JII[FI[FI[II[SIIJ)V",
        (void*)SkCanvasGlue::drawVertices},
    {"native_drawText","(J[CIIFFIJJ)V",
        (void*) SkCanvasGlue::drawText___CIIFFIPaintTypeface},
    {"native_drawText","(JLjava/lang/String;IIFFIJJ)V",
        (void*) SkCanvasGlue::drawText__StringIIFFIPaintTypeface},
    {"native_drawTextRun","(J[CIIIIFFIJJ)V",
        (void*) SkCanvasGlue::drawTextRun___CIIIIFFIPaintTypeface},
    {"native_drawTextRun","(JLjava/lang/String;IIIIFFIJJ)V",
        (void*) SkCanvasGlue::drawTextRun__StringIIIIFFIPaintTypeface},
    {"native_drawPosText","(J[CII[FJ)V",
        (void*) SkCanvasGlue::drawPosText___CII_FPaint},
    {"native_drawPosText","(JLjava/lang/String;[FJ)V",
        (void*) SkCanvasGlue::drawPosText__String_FPaint},
    {"native_drawTextOnPath","(J[CIIJFFIJ)V",
        (void*) SkCanvasGlue::drawTextOnPath___CIIPathFFPaint},
    {"native_drawTextOnPath","(JLjava/lang/String;JFFIJ)V",
        (void*) SkCanvasGlue::drawTextOnPath__StringPathFFPaint},

    {"freeCaches", "()V", (void*) SkCanvasGlue::freeCaches},

    {"freeTextLayoutCaches", "()V", (void*) SkCanvasGlue::freeTextLayoutCaches}
};

///////////////////////////////////////////////////////////////////////////////

#include <android_runtime/AndroidRuntime.h>

#define REG(env, name, array) \
    result = android::AndroidRuntime::registerNativeMethods(env, name, array, \
                                                    SK_ARRAY_COUNT(array));  \
    if (result < 0) return result

int register_android_graphics_Canvas(JNIEnv* env) {
    int result;

    REG(env, "android/graphics/Canvas", gCanvasMethods);

    return result;
}

} // namespace android

// GraphicsJNI helper for external clients.
// We keep the implementation here to avoid exposing NativeCanvasWrapper
// externally.
SkCanvas* GraphicsJNI::getNativeCanvas(jlong nativeHandle) {
    return android::SkCanvasGlue::getNativeCanvas(nativeHandle);
}
