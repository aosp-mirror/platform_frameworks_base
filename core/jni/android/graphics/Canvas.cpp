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
#include "SkGraphics.h"
#include "SkImageRef_GlobalPool.h"
#include "SkPorterDuff.h"
#include "SkShader.h"
#include "SkTemplates.h"

#include "TextLayout.h"
#include "TextLayoutCache.h"

#include "unicode/ubidi.h"
#include "unicode/ushape.h"

#include <utils/Log.h>

#define TIME_DRAWx

static uint32_t get_thread_msec() {
#if defined(HAVE_POSIX_CLOCKS)
    struct timespec tm;
    
    clock_gettime(CLOCK_THREAD_CPUTIME_ID, &tm);
    
    return tm.tv_sec * 1000LL + tm.tv_nsec / 1000000;
#else
    struct timeval tv;
    
    gettimeofday(&tv, NULL);
    return tv.tv_sec * 1000LL + tv.tv_usec / 1000;
#endif
}

namespace android {

class SkCanvasGlue {
public:

    static void finalizer(JNIEnv* env, jobject clazz, SkCanvas* canvas) {
        canvas->unref();
    }

    static SkCanvas* initRaster(JNIEnv* env, jobject, SkBitmap* bitmap) {
        return bitmap ? new SkCanvas(*bitmap) : new SkCanvas;
    }
    
    static void freeCaches(JNIEnv* env, jobject) {
        // these are called in no particular order
        SkImageRef_GlobalPool::SetRAMUsed(0);
        SkGraphics::SetFontCacheUsed(0);
    }
    
    static jboolean isOpaque(JNIEnv* env, jobject jcanvas) {
        NPE_CHECK_RETURN_ZERO(env, jcanvas);
        SkCanvas* canvas = GraphicsJNI::getNativeCanvas(env, jcanvas);
        return canvas->getDevice()->accessBitmap(false).isOpaque();
    }
    
    static int getWidth(JNIEnv* env, jobject jcanvas) {
        NPE_CHECK_RETURN_ZERO(env, jcanvas);
        SkCanvas* canvas = GraphicsJNI::getNativeCanvas(env, jcanvas);
        return canvas->getDevice()->accessBitmap(false).width();
    }
    
    static int getHeight(JNIEnv* env, jobject jcanvas) {
        NPE_CHECK_RETURN_ZERO(env, jcanvas);
        SkCanvas* canvas = GraphicsJNI::getNativeCanvas(env, jcanvas);
        return canvas->getDevice()->accessBitmap(false).height();
    }

    static void setBitmap(JNIEnv* env, jobject, SkCanvas* canvas, SkBitmap* bitmap) {
        if (bitmap) {
            canvas->setBitmapDevice(*bitmap);
        } else {
            canvas->setDevice(NULL);
        }
    }
 
    static int saveAll(JNIEnv* env, jobject jcanvas) {
        NPE_CHECK_RETURN_ZERO(env, jcanvas);
        return GraphicsJNI::getNativeCanvas(env, jcanvas)->save();
    }
    
    static int save(JNIEnv* env, jobject jcanvas, SkCanvas::SaveFlags flags) {
        NPE_CHECK_RETURN_ZERO(env, jcanvas);
        return GraphicsJNI::getNativeCanvas(env, jcanvas)->save(flags);
    }
    
    static int saveLayer(JNIEnv* env, jobject, SkCanvas* canvas, jobject bounds,
                         SkPaint* paint, int flags) {
        SkRect* bounds_ = NULL;
        SkRect  storage;
        if (bounds != NULL) {
            GraphicsJNI::jrectf_to_rect(env, bounds, &storage);
            bounds_ = &storage;
        }
        return canvas->saveLayer(bounds_, paint, (SkCanvas::SaveFlags)flags);
    }
 
    static int saveLayer4F(JNIEnv* env, jobject, SkCanvas* canvas,
                           jfloat l, jfloat t, jfloat r, jfloat b,
                           SkPaint* paint, int flags) {
        SkRect bounds;
        bounds.set(SkFloatToScalar(l), SkFloatToScalar(t), SkFloatToScalar(r),
                   SkFloatToScalar(b));
        return canvas->saveLayer(&bounds, paint, (SkCanvas::SaveFlags)flags);
    }
 
    static int saveLayerAlpha(JNIEnv* env, jobject, SkCanvas* canvas,
                              jobject bounds, int alpha, int flags) {
        SkRect* bounds_ = NULL;
        SkRect  storage;
        if (bounds != NULL) {
            GraphicsJNI::jrectf_to_rect(env, bounds, &storage);
            bounds_ = &storage;
        }
        return canvas->saveLayerAlpha(bounds_, alpha,
                                      (SkCanvas::SaveFlags)flags);
    }
 
    static int saveLayerAlpha4F(JNIEnv* env, jobject, SkCanvas* canvas,
                                jfloat l, jfloat t, jfloat r, jfloat b,
                                int alpha, int flags) {
        SkRect  bounds;
        bounds.set(SkFloatToScalar(l), SkFloatToScalar(t), SkFloatToScalar(r),
                   SkFloatToScalar(b));
        return canvas->saveLayerAlpha(&bounds, alpha,
                                      (SkCanvas::SaveFlags)flags);
    }
 
    static void restore(JNIEnv* env, jobject jcanvas) {
        NPE_CHECK_RETURN_VOID(env, jcanvas);
        SkCanvas* canvas = GraphicsJNI::getNativeCanvas(env, jcanvas);
        if (canvas->getSaveCount() <= 1) {  // cannot restore anymore
            doThrowISE(env, "Underflow in restore");
            return;
        }
        canvas->restore();
    }
 
    static int getSaveCount(JNIEnv* env, jobject jcanvas) {
        NPE_CHECK_RETURN_ZERO(env, jcanvas);
        return GraphicsJNI::getNativeCanvas(env, jcanvas)->getSaveCount();
    }
 
    static void restoreToCount(JNIEnv* env, jobject jcanvas, int restoreCount) {
        NPE_CHECK_RETURN_VOID(env, jcanvas);
        SkCanvas* canvas = GraphicsJNI::getNativeCanvas(env, jcanvas);
        if (restoreCount < 1) {
            doThrowIAE(env, "Underflow in restoreToCount");
            return;
        }
        canvas->restoreToCount(restoreCount);
    }
 
    static void translate(JNIEnv* env, jobject jcanvas, jfloat dx, jfloat dy) {
        NPE_CHECK_RETURN_VOID(env, jcanvas);
        SkScalar dx_ = SkFloatToScalar(dx);
        SkScalar dy_ = SkFloatToScalar(dy);
        (void)GraphicsJNI::getNativeCanvas(env, jcanvas)->translate(dx_, dy_);
    }
 
    static void scale__FF(JNIEnv* env, jobject jcanvas, jfloat sx, jfloat sy) {
        NPE_CHECK_RETURN_VOID(env, jcanvas);
        SkScalar sx_ = SkFloatToScalar(sx);
        SkScalar sy_ = SkFloatToScalar(sy);
        (void)GraphicsJNI::getNativeCanvas(env, jcanvas)->scale(sx_, sy_);
    }
 
    static void rotate__F(JNIEnv* env, jobject jcanvas, jfloat degrees) {
        NPE_CHECK_RETURN_VOID(env, jcanvas);
        SkScalar degrees_ = SkFloatToScalar(degrees);
        (void)GraphicsJNI::getNativeCanvas(env, jcanvas)->rotate(degrees_);
    }
 
    static void skew__FF(JNIEnv* env, jobject jcanvas, jfloat sx, jfloat sy) {
        NPE_CHECK_RETURN_VOID(env, jcanvas);
        SkScalar sx_ = SkFloatToScalar(sx);
        SkScalar sy_ = SkFloatToScalar(sy);
        (void)GraphicsJNI::getNativeCanvas(env, jcanvas)->skew(sx_, sy_);
    }
 
    static void concat(JNIEnv* env, jobject, SkCanvas* canvas,
                       const SkMatrix* matrix) {
        canvas->concat(*matrix);
    }
    
    static void setMatrix(JNIEnv* env, jobject, SkCanvas* canvas,
                          const SkMatrix* matrix) {
        if (NULL == matrix) {
            canvas->resetMatrix();
        } else {
            canvas->setMatrix(*matrix);
        }
    }
    
    static jboolean clipRect_FFFF(JNIEnv* env, jobject jcanvas, jfloat left,
                                  jfloat top, jfloat right, jfloat bottom) {
        NPE_CHECK_RETURN_ZERO(env, jcanvas);
        SkRect  r;
        r.set(SkFloatToScalar(left), SkFloatToScalar(top),
              SkFloatToScalar(right), SkFloatToScalar(bottom));
        SkCanvas* c = GraphicsJNI::getNativeCanvas(env, jcanvas);
        return c->clipRect(r);
    }
    
    static jboolean clipRect_IIII(JNIEnv* env, jobject jcanvas, jint left,
                                  jint top, jint right, jint bottom) {
        NPE_CHECK_RETURN_ZERO(env, jcanvas);
        SkRect  r;
        r.set(SkIntToScalar(left), SkIntToScalar(top),
              SkIntToScalar(right), SkIntToScalar(bottom));
        return GraphicsJNI::getNativeCanvas(env, jcanvas)->clipRect(r);
    }
    
    static jboolean clipRect_RectF(JNIEnv* env, jobject jcanvas, jobject rectf) {
        NPE_CHECK_RETURN_ZERO(env, jcanvas);
        NPE_CHECK_RETURN_ZERO(env, rectf);
        SkCanvas* c = GraphicsJNI::getNativeCanvas(env, jcanvas);
        SkRect tmp;
        return c->clipRect(*GraphicsJNI::jrectf_to_rect(env, rectf, &tmp));
    }
    
    static jboolean clipRect_Rect(JNIEnv* env, jobject jcanvas, jobject rect) {
        NPE_CHECK_RETURN_ZERO(env, jcanvas);
        NPE_CHECK_RETURN_ZERO(env, rect);
        SkCanvas* c = GraphicsJNI::getNativeCanvas(env, jcanvas);
        SkRect tmp;
        return c->clipRect(*GraphicsJNI::jrect_to_rect(env, rect, &tmp));
    }
    
    static jboolean clipRect(JNIEnv* env, jobject, SkCanvas* canvas,
                             float left, float top, float right, float bottom,
                             int op) {
        SkRect rect;
        rect.set(SkFloatToScalar(left), SkFloatToScalar(top),
                 SkFloatToScalar(right), SkFloatToScalar(bottom));
        return canvas->clipRect(rect, (SkRegion::Op)op);
    }
 
    static jboolean clipPath(JNIEnv* env, jobject, SkCanvas* canvas,
                             SkPath* path, int op) {
        return canvas->clipPath(*path, (SkRegion::Op)op);
    }
 
    static jboolean clipRegion(JNIEnv* env, jobject, SkCanvas* canvas,
                               SkRegion* deviceRgn, int op) {
        return canvas->clipRegion(*deviceRgn, (SkRegion::Op)op);
    }
    
    static void setDrawFilter(JNIEnv* env, jobject, SkCanvas* canvas,
                              SkDrawFilter* filter) {
        canvas->setDrawFilter(filter);
    }
    
    static jboolean quickReject__RectFI(JNIEnv* env, jobject, SkCanvas* canvas,
                                        jobject rect, int edgetype) {
        SkRect rect_;
        GraphicsJNI::jrectf_to_rect(env, rect, &rect_);
        return canvas->quickReject(rect_, (SkCanvas::EdgeType)edgetype);
    }
 
    static jboolean quickReject__PathI(JNIEnv* env, jobject, SkCanvas* canvas,
                                       SkPath* path, int edgetype) {
        return canvas->quickReject(*path, (SkCanvas::EdgeType)edgetype);
    }
 
    static jboolean quickReject__FFFFI(JNIEnv* env, jobject, SkCanvas* canvas,
                                       jfloat left, jfloat top, jfloat right,
                                       jfloat bottom, int edgetype) {
        SkRect r;
        r.set(SkFloatToScalar(left), SkFloatToScalar(top),
              SkFloatToScalar(right), SkFloatToScalar(bottom));
        return canvas->quickReject(r, (SkCanvas::EdgeType)edgetype);
    }
 
    static void drawRGB(JNIEnv* env, jobject, SkCanvas* canvas,
                        jint r, jint g, jint b) {
        canvas->drawARGB(0xFF, r, g, b);
    }
 
    static void drawARGB(JNIEnv* env, jobject, SkCanvas* canvas,
                         jint a, jint r, jint g, jint b) {
        canvas->drawARGB(a, r, g, b);
    }
 
    static void drawColor__I(JNIEnv* env, jobject, SkCanvas* canvas,
                             jint color) {
        canvas->drawColor(color);
    }
 
    static void drawColor__II(JNIEnv* env, jobject, SkCanvas* canvas,
                              jint color, SkPorterDuff::Mode mode) {
        canvas->drawColor(color, SkPorterDuff::ToXfermodeMode(mode));
    }
 
    static void drawPaint(JNIEnv* env, jobject, SkCanvas* canvas,
                          SkPaint* paint) {
        canvas->drawPaint(*paint);
    }
    
    static void doPoints(JNIEnv* env, jobject jcanvas, jfloatArray jptsArray,
                         jint offset, jint count, jobject jpaint,
                         SkCanvas::PointMode mode) {
        NPE_CHECK_RETURN_VOID(env, jcanvas);
        NPE_CHECK_RETURN_VOID(env, jptsArray);
        NPE_CHECK_RETURN_VOID(env, jpaint);
        SkCanvas* canvas = GraphicsJNI::getNativeCanvas(env, jcanvas);
        const SkPaint& paint = *GraphicsJNI::getNativePaint(env, jpaint);
        
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
            pts[i].set(SkFloatToScalar(src[0]), SkFloatToScalar(src[1]));
            src += 2;
        }        
        canvas->drawPoints(mode, count, pts, paint);
    }
    
    static void drawPoints(JNIEnv* env, jobject jcanvas, jfloatArray jptsArray,
                           jint offset, jint count, jobject jpaint) {
        doPoints(env, jcanvas, jptsArray, offset, count, jpaint,
                 SkCanvas::kPoints_PointMode);
    }
    
    static void drawLines(JNIEnv* env, jobject jcanvas, jfloatArray jptsArray,
                           jint offset, jint count, jobject jpaint) {
        doPoints(env, jcanvas, jptsArray, offset, count, jpaint,
                 SkCanvas::kLines_PointMode);
    }
    
    static void drawPoint(JNIEnv* env, jobject jcanvas, float x, float y,
                          jobject jpaint) {
        NPE_CHECK_RETURN_VOID(env, jcanvas);
        NPE_CHECK_RETURN_VOID(env, jpaint);
        SkCanvas* canvas = GraphicsJNI::getNativeCanvas(env, jcanvas);
        const SkPaint& paint = *GraphicsJNI::getNativePaint(env, jpaint);
        
        canvas->drawPoint(SkFloatToScalar(x), SkFloatToScalar(y), paint);
    }
 
    static void drawLine__FFFFPaint(JNIEnv* env, jobject, SkCanvas* canvas,
                                    jfloat startX, jfloat startY, jfloat stopX,
                                    jfloat stopY, SkPaint* paint) {
        canvas->drawLine(SkFloatToScalar(startX), SkFloatToScalar(startY),
                         SkFloatToScalar(stopX), SkFloatToScalar(stopY),
                         *paint);
    }
 
    static void drawRect__RectFPaint(JNIEnv* env, jobject, SkCanvas* canvas,
                                     jobject rect, SkPaint* paint) {
        SkRect rect_;
        GraphicsJNI::jrectf_to_rect(env, rect, &rect_);
        canvas->drawRect(rect_, *paint);
    }
 
    static void drawRect__FFFFPaint(JNIEnv* env, jobject, SkCanvas* canvas,
                                    jfloat left, jfloat top, jfloat right,
                                    jfloat bottom, SkPaint* paint) {
        SkScalar left_ = SkFloatToScalar(left);
        SkScalar top_ = SkFloatToScalar(top);
        SkScalar right_ = SkFloatToScalar(right);
        SkScalar bottom_ = SkFloatToScalar(bottom);
        canvas->drawRectCoords(left_, top_, right_, bottom_, *paint);
    }
 
    static void drawOval(JNIEnv* env, jobject, SkCanvas* canvas, jobject joval,
                         SkPaint* paint) {
        SkRect oval;
        GraphicsJNI::jrectf_to_rect(env, joval, &oval);
        canvas->drawOval(oval, *paint);
    }
 
    static void drawCircle(JNIEnv* env, jobject, SkCanvas* canvas, jfloat cx,
                           jfloat cy, jfloat radius, SkPaint* paint) {
        canvas->drawCircle(SkFloatToScalar(cx), SkFloatToScalar(cy),
                           SkFloatToScalar(radius), *paint);
    }
 
    static void drawArc(JNIEnv* env, jobject, SkCanvas* canvas, jobject joval,
                        jfloat startAngle, jfloat sweepAngle,
                        jboolean useCenter, SkPaint* paint) {
        SkRect oval;
        GraphicsJNI::jrectf_to_rect(env, joval, &oval);
        canvas->drawArc(oval, SkFloatToScalar(startAngle),
                        SkFloatToScalar(sweepAngle), useCenter, *paint);
    }
 
    static void drawRoundRect(JNIEnv* env, jobject, SkCanvas* canvas,
                              jobject jrect, jfloat rx, jfloat ry,
                              SkPaint* paint) {
        SkRect rect;
        GraphicsJNI::jrectf_to_rect(env, jrect, &rect);
        canvas->drawRoundRect(rect, SkFloatToScalar(rx), SkFloatToScalar(ry),
                              *paint);
    }
 
    static void drawPath(JNIEnv* env, jobject, SkCanvas* canvas, SkPath* path,
                         SkPaint* paint) {
        canvas->drawPath(*path, *paint);
    }
 
    static void drawPicture(JNIEnv* env, jobject, SkCanvas* canvas,
                            SkPicture* picture) {
        SkASSERT(canvas);
        SkASSERT(picture);
        
#ifdef TIME_DRAW
        SkMSec now = get_thread_msec(); //SkTime::GetMSecs();
#endif
        canvas->drawPicture(*picture);
#ifdef TIME_DRAW
        LOGD("---- picture playback %d ms\n", get_thread_msec() - now);
#endif
    }

    static void drawBitmap__BitmapFFPaint(JNIEnv* env, jobject jcanvas,
                                          SkCanvas* canvas, SkBitmap* bitmap,
                                          jfloat left, jfloat top,
                                          SkPaint* paint, jint canvasDensity,
                                          jint screenDensity, jint bitmapDensity) {
        SkScalar left_ = SkFloatToScalar(left);
        SkScalar top_ = SkFloatToScalar(top);

        if (canvasDensity == bitmapDensity || canvasDensity == 0
                || bitmapDensity == 0) {
            if (screenDensity != 0 && screenDensity != bitmapDensity) {
                SkPaint filteredPaint;
                if (paint) {
                    filteredPaint = *paint;
                }
                filteredPaint.setFilterBitmap(true);
                canvas->drawBitmap(*bitmap, left_, top_, &filteredPaint);
            } else {
                canvas->drawBitmap(*bitmap, left_, top_, paint);
            }
        } else {
            canvas->save();
            SkScalar scale = SkFloatToScalar(canvasDensity / (float)bitmapDensity);
            canvas->translate(left_, top_);
            canvas->scale(scale, scale);

            SkPaint filteredPaint;
            if (paint) {
                filteredPaint = *paint;
            }
            filteredPaint.setFilterBitmap(true);

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
            filteredPaint.setFilterBitmap(true);
            canvas->drawBitmapRect(*bitmap, srcPtr, dst, &filteredPaint);
        } else {
            canvas->drawBitmapRect(*bitmap, srcPtr, dst, paint);
        }
    }

    static void drawBitmapRF(JNIEnv* env, jobject, SkCanvas* canvas,
                             SkBitmap* bitmap, jobject srcIRect,
                             jobject dstRectF, SkPaint* paint,
                             jint screenDensity, jint bitmapDensity) {
        SkRect      dst;
        GraphicsJNI::jrectf_to_rect(env, dstRectF, &dst);
        doDrawBitmap(env, canvas, bitmap, srcIRect, dst, paint,
                screenDensity, bitmapDensity);
    }
    
    static void drawBitmapRR(JNIEnv* env, jobject, SkCanvas* canvas,
                             SkBitmap* bitmap, jobject srcIRect,
                             jobject dstRect, SkPaint* paint,
                             jint screenDensity, jint bitmapDensity) {
        SkRect      dst;
        GraphicsJNI::jrect_to_rect(env, dstRect, &dst);
        doDrawBitmap(env, canvas, bitmap, srcIRect, dst, paint,
                screenDensity, bitmapDensity);
    }
    
    static void drawBitmapArray(JNIEnv* env, jobject, SkCanvas* canvas,
                                jintArray jcolors, int offset, int stride,
                                jfloat x, jfloat y, int width, int height,
                                jboolean hasAlpha, SkPaint* paint)
    {
        SkBitmap    bitmap;
        
        bitmap.setConfig(hasAlpha ? SkBitmap::kARGB_8888_Config :
                         SkBitmap::kRGB_565_Config, width, height);
        if (!bitmap.allocPixels()) {
            return;
        }
        
        if (!GraphicsJNI::SetPixels(env, jcolors, offset, stride,
                                    0, 0, width, height, bitmap)) {
            return;
        }
        
        canvas->drawBitmap(bitmap, SkFloatToScalar(x), SkFloatToScalar(y),
                           paint);
    }
    
    static void drawBitmapMatrix(JNIEnv* env, jobject, SkCanvas* canvas,
                                 const SkBitmap* bitmap, const SkMatrix* matrix,
                                 const SkPaint* paint) {
        canvas->drawBitmapMatrix(*bitmap, *matrix, paint);
    }
    
    static void drawBitmapMesh(JNIEnv* env, jobject, SkCanvas* canvas,
                          const SkBitmap* bitmap, int meshWidth, int meshHeight,
                          jfloatArray jverts, int vertIndex, jintArray jcolors,
                          int colorIndex, const SkPaint* paint) {

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
#ifdef SK_SCALAR_IS_FIXED
        storageSize += ptCount * sizeof(SkPoint);  // storage for verts
#endif
        storageSize += indexCount * sizeof(uint16_t);  // indices[]

        SkAutoMalloc storage(storageSize);
        SkPoint* texs = (SkPoint*)storage.get();
        SkPoint* verts;
        uint16_t* indices;
#ifdef SK_SCALAR_IS_FLOAT
        verts = (SkPoint*)(vertA.ptr() + vertIndex);
        indices = (uint16_t*)(texs + ptCount);
#else
        verts = texs + ptCount;
        indices = (uint16_t*)(verts + ptCount);
        // convert floats to fixed
        {
            const float* src = vertA.ptr() + vertIndex;
            for (int i = 0; i < ptCount; i++) {
                verts[i].set(SkFloatToFixed(src[0]), SkFloatToFixed(src[1]));
                src += 2;
            }
        }
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

    static void drawVertices(JNIEnv* env, jobject, SkCanvas* canvas,
                             SkCanvas::VertexMode mode, int vertexCount,
                             jfloatArray jverts, int vertIndex,
                             jfloatArray jtexs, int texIndex,
                             jintArray jcolors, int colorIndex,
                             jshortArray jindices, int indexIndex,
                             int indexCount, const SkPaint* paint) {

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
        int count = ptCount;    // for verts
        if (jtexs != NULL) {
            count += ptCount;   // += for texs
        }
        SkAutoMalloc storage(count * sizeof(SkPoint));
        verts = (SkPoint*)storage.get();        
        const float* src = vertA.ptr() + vertIndex;
        for (int i = 0; i < ptCount; i++) {
            verts[i].set(SkFloatToFixed(src[0]), SkFloatToFixed(src[1]));
            src += 2;
        }
        if (jtexs != NULL) {
            texs = verts + ptCount;
            src = texA.ptr() + texIndex;
            for (int i = 0; i < ptCount; i++) {
                texs[i].set(SkFloatToFixed(src[0]), SkFloatToFixed(src[1]));
                src += 2;
            }
        }
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


    static void drawText___CIIFFIPaint(JNIEnv* env, jobject, SkCanvas* canvas,
                                      jcharArray text, int index, int count,
                                      jfloat x, jfloat y, int flags, SkPaint* paint) {
        jchar* textArray = env->GetCharArrayElements(text, NULL);
        drawTextWithGlyphs(canvas, textArray + index, 0, count, x, y, flags, paint);
        env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
    }

    static void drawText__StringIIFFIPaint(JNIEnv* env, jobject,
                                          SkCanvas* canvas, jstring text,
                                          int start, int end,
                                          jfloat x, jfloat y, int flags, SkPaint* paint) {
        const jchar* textArray = env->GetStringChars(text, NULL);
        drawTextWithGlyphs(canvas, textArray, start, end, x, y, flags, paint);
        env->ReleaseStringChars(text, textArray);
    }

    static void drawTextWithGlyphs(SkCanvas* canvas, const jchar* textArray,
            int start, int end,
            jfloat x, jfloat y, int flags, SkPaint* paint) {

        jint count = end - start;
        drawTextWithGlyphs(canvas, textArray + start, 0, count, count, x, y, flags, paint);
    }

    static void drawTextWithGlyphs(SkCanvas* canvas, const jchar* textArray,
            int start, int count, int contextCount,
            jfloat x, jfloat y, int flags, SkPaint* paint) {

        sp<TextLayoutCacheValue> value;
#if USE_TEXT_LAYOUT_CACHE
        value = TextLayoutCache::getInstance().getValue(paint, textArray, start, count,
                contextCount, flags);
        if (value == NULL) {
            LOGE("Cannot get TextLayoutCache value for text = '%s'",
                    String8(textArray + start, count).string());
            return ;
        }
#else
        value = new TextLayoutCacheValue(contextCount);
        TextLayoutEngine::getInstance().computeValues(value.get(), paint,
                reinterpret_cast<const UChar*>(textArray), start, count, contextCount, flags);
#endif
        doDrawGlyphs(canvas, value->getGlyphs(), 0, value->getGlyphsCount(), x, y, flags, paint);
    }

    static void doDrawGlyphs(SkCanvas* canvas, const jchar* glyphArray, int index, int count,
            jfloat x, jfloat y, int flags, SkPaint* paint) {
        // TODO: need to suppress this code after the GL renderer is modified for not
        // copying the paint

        // Beware: this needs Glyph encoding (already done on the Paint constructor)
        canvas->drawText(glyphArray + index * 2, count * 2, x, y, *paint);
    }

    static void drawTextRun___CIIIIFFIPaint(
        JNIEnv* env, jobject, SkCanvas* canvas, jcharArray text, int index,
        int count, int contextIndex, int contextCount,
        jfloat x, jfloat y, int dirFlags, SkPaint* paint) {

        jchar* chars = env->GetCharArrayElements(text, NULL);
        drawTextWithGlyphs(canvas, chars + contextIndex, index - contextIndex,
                count, contextCount, x, y, dirFlags, paint);
        env->ReleaseCharArrayElements(text, chars, JNI_ABORT);
    }

    static void drawTextRun__StringIIIIFFIPaint(
        JNIEnv* env, jobject obj, SkCanvas* canvas, jstring text, jint start,
        jint end, jint contextStart, jint contextEnd,
        jfloat x, jfloat y, jint dirFlags, SkPaint* paint) {

        jint count = end - start;
        jint contextCount = contextEnd - contextStart;
        const jchar* chars = env->GetStringChars(text, NULL);
        drawTextWithGlyphs(canvas, chars + contextStart, start - contextStart,
                count, contextCount, x, y, dirFlags, paint);
        env->ReleaseStringChars(text, chars);
    }

    static void drawPosText___CII_FPaint(JNIEnv* env, jobject, SkCanvas* canvas,
                                         jcharArray text, int index, int count,
                                         jfloatArray pos, SkPaint* paint) {
        jchar* textArray = text ? env->GetCharArrayElements(text, NULL) : NULL;
        jsize textCount = text ? env->GetArrayLength(text) : NULL;
        float* posArray = pos ? env->GetFloatArrayElements(pos, NULL) : NULL;
        int posCount = pos ? env->GetArrayLength(pos) >> 1: 0;
        SkPoint* posPtr = posCount > 0 ? new SkPoint[posCount] : NULL;
        int indx;
        for (indx = 0; indx < posCount; indx++) {
            posPtr[indx].fX = SkFloatToScalar(posArray[indx << 1]);
            posPtr[indx].fY = SkFloatToScalar(posArray[(indx << 1) + 1]);
        }
        canvas->drawPosText(textArray + index, count << 1, posPtr, *paint);
        if (text) {
            env->ReleaseCharArrayElements(text, textArray, 0);
        }
        if (pos) {
            env->ReleaseFloatArrayElements(pos, posArray, 0);
        }
        delete[] posPtr;
    }

    static void drawPosText__String_FPaint(JNIEnv* env, jobject,
                                           SkCanvas* canvas, jstring text,
                                           jfloatArray pos,
                                           SkPaint* paint) {
        const void* text_ = text ? env->GetStringChars(text, NULL) : NULL;
        int byteLength = text ? env->GetStringLength(text) : 0;
        float* posArray = pos ? env->GetFloatArrayElements(pos, NULL) : NULL;
        int posCount = pos ? env->GetArrayLength(pos) >> 1: 0;
        SkPoint* posPtr = posCount > 0 ? new SkPoint[posCount] : NULL;

        for (int indx = 0; indx < posCount; indx++) {
            posPtr[indx].fX = SkFloatToScalar(posArray[indx << 1]);
            posPtr[indx].fY = SkFloatToScalar(posArray[(indx << 1) + 1]);
        }
        canvas->drawPosText(text_, byteLength << 1, posPtr, *paint);
        if (text) {
            env->ReleaseStringChars(text, (const jchar*) text_);
        }
        if (pos) {
            env->ReleaseFloatArrayElements(pos, posArray, 0);
        }
        delete[] posPtr;
    }

    static void drawTextOnPath___CIIPathFFPaint(JNIEnv* env, jobject,
            SkCanvas* canvas, jcharArray text, int index, int count,
            SkPath* path, jfloat hOffset, jfloat vOffset, jint bidiFlags, SkPaint* paint) {

        jchar* textArray = env->GetCharArrayElements(text, NULL);
        TextLayout::drawTextOnPath(paint, textArray + index, count, bidiFlags, hOffset, vOffset,
                                   path, canvas);
        env->ReleaseCharArrayElements(text, textArray, 0);
    }

    static void drawTextOnPath__StringPathFFPaint(JNIEnv* env, jobject,
            SkCanvas* canvas, jstring text, SkPath* path,
            jfloat hOffset, jfloat vOffset, jint bidiFlags, SkPaint* paint) {
        const jchar* text_ = env->GetStringChars(text, NULL);
        int count = env->GetStringLength(text);
        TextLayout::drawTextOnPath(paint, text_, count, bidiFlags, hOffset, vOffset,
                                   path, canvas);
        env->ReleaseStringChars(text, text_);
    }

    static bool getClipBounds(JNIEnv* env, jobject, SkCanvas* canvas,
                              jobject bounds) {
        SkRect   r;
        SkIRect ir;
        bool     result = canvas->getClipBounds(&r, SkCanvas::kBW_EdgeType);

        r.round(&ir);
        (void)GraphicsJNI::irect_to_jrect(ir, env, bounds);
        return result;
    }

    static void getCTM(JNIEnv* env, jobject, SkCanvas* canvas,
                       SkMatrix* matrix) {
        *matrix = canvas->getTotalMatrix();
    }
};

static JNINativeMethod gCanvasMethods[] = {
    {"finalizer", "(I)V", (void*) SkCanvasGlue::finalizer},
    {"initRaster","(I)I", (void*) SkCanvasGlue::initRaster},
    {"isOpaque","()Z", (void*) SkCanvasGlue::isOpaque},
    {"getWidth","()I", (void*) SkCanvasGlue::getWidth},
    {"getHeight","()I", (void*) SkCanvasGlue::getHeight},
    {"native_setBitmap","(II)V", (void*) SkCanvasGlue::setBitmap},
    {"save","()I", (void*) SkCanvasGlue::saveAll},
    {"save","(I)I", (void*) SkCanvasGlue::save},
    {"native_saveLayer","(ILandroid/graphics/RectF;II)I",
        (void*) SkCanvasGlue::saveLayer},
    {"native_saveLayer","(IFFFFII)I", (void*) SkCanvasGlue::saveLayer4F},
    {"native_saveLayerAlpha","(ILandroid/graphics/RectF;II)I",
        (void*) SkCanvasGlue::saveLayerAlpha},
    {"native_saveLayerAlpha","(IFFFFII)I",
        (void*) SkCanvasGlue::saveLayerAlpha4F},
    {"restore","()V", (void*) SkCanvasGlue::restore},
    {"getSaveCount","()I", (void*) SkCanvasGlue::getSaveCount},
    {"restoreToCount","(I)V", (void*) SkCanvasGlue::restoreToCount},
    {"translate","(FF)V", (void*) SkCanvasGlue::translate},
    {"scale","(FF)V", (void*) SkCanvasGlue::scale__FF},
    {"rotate","(F)V", (void*) SkCanvasGlue::rotate__F},
    {"skew","(FF)V", (void*) SkCanvasGlue::skew__FF},
    {"native_concat","(II)V", (void*) SkCanvasGlue::concat},
    {"native_setMatrix","(II)V", (void*) SkCanvasGlue::setMatrix},
    {"clipRect","(FFFF)Z", (void*) SkCanvasGlue::clipRect_FFFF},
    {"clipRect","(IIII)Z", (void*) SkCanvasGlue::clipRect_IIII},
    {"clipRect","(Landroid/graphics/RectF;)Z",
        (void*) SkCanvasGlue::clipRect_RectF},
    {"clipRect","(Landroid/graphics/Rect;)Z",
        (void*) SkCanvasGlue::clipRect_Rect},
    {"native_clipRect","(IFFFFI)Z", (void*) SkCanvasGlue::clipRect},
    {"native_clipPath","(III)Z", (void*) SkCanvasGlue::clipPath},
    {"native_clipRegion","(III)Z", (void*) SkCanvasGlue::clipRegion},
    {"nativeSetDrawFilter", "(II)V", (void*) SkCanvasGlue::setDrawFilter},
    {"native_getClipBounds","(ILandroid/graphics/Rect;)Z",
        (void*) SkCanvasGlue::getClipBounds},
    {"native_getCTM", "(II)V", (void*)SkCanvasGlue::getCTM},
    {"native_quickReject","(ILandroid/graphics/RectF;I)Z",
        (void*) SkCanvasGlue::quickReject__RectFI},
    {"native_quickReject","(III)Z", (void*) SkCanvasGlue::quickReject__PathI},
    {"native_quickReject","(IFFFFI)Z", (void*)SkCanvasGlue::quickReject__FFFFI},
    {"native_drawRGB","(IIII)V", (void*) SkCanvasGlue::drawRGB},
    {"native_drawARGB","(IIIII)V", (void*) SkCanvasGlue::drawARGB},
    {"native_drawColor","(II)V", (void*) SkCanvasGlue::drawColor__I},
    {"native_drawColor","(III)V", (void*) SkCanvasGlue::drawColor__II},
    {"native_drawPaint","(II)V", (void*) SkCanvasGlue::drawPaint},
    {"drawPoint", "(FFLandroid/graphics/Paint;)V",
    (void*) SkCanvasGlue::drawPoint},
    {"drawPoints", "([FIILandroid/graphics/Paint;)V",
        (void*) SkCanvasGlue::drawPoints},
    {"drawLines", "([FIILandroid/graphics/Paint;)V",
        (void*) SkCanvasGlue::drawLines},
    {"native_drawLine","(IFFFFI)V", (void*) SkCanvasGlue::drawLine__FFFFPaint},
    {"native_drawRect","(ILandroid/graphics/RectF;I)V",
        (void*) SkCanvasGlue::drawRect__RectFPaint},
    {"native_drawRect","(IFFFFI)V", (void*) SkCanvasGlue::drawRect__FFFFPaint},
    {"native_drawOval","(ILandroid/graphics/RectF;I)V",
        (void*) SkCanvasGlue::drawOval},
    {"native_drawCircle","(IFFFI)V", (void*) SkCanvasGlue::drawCircle},
    {"native_drawArc","(ILandroid/graphics/RectF;FFZI)V",
        (void*) SkCanvasGlue::drawArc},
    {"native_drawRoundRect","(ILandroid/graphics/RectF;FFI)V",
        (void*) SkCanvasGlue::drawRoundRect},
    {"native_drawPath","(III)V", (void*) SkCanvasGlue::drawPath},
    {"native_drawBitmap","(IIFFIIII)V",
        (void*) SkCanvasGlue::drawBitmap__BitmapFFPaint},
    {"native_drawBitmap","(IILandroid/graphics/Rect;Landroid/graphics/RectF;III)V",
        (void*) SkCanvasGlue::drawBitmapRF},
    {"native_drawBitmap","(IILandroid/graphics/Rect;Landroid/graphics/Rect;III)V",
        (void*) SkCanvasGlue::drawBitmapRR},
    {"native_drawBitmap", "(I[IIIFFIIZI)V",
    (void*)SkCanvasGlue::drawBitmapArray},
    {"nativeDrawBitmapMatrix", "(IIII)V",
        (void*)SkCanvasGlue::drawBitmapMatrix},
    {"nativeDrawBitmapMesh", "(IIII[FI[III)V",
        (void*)SkCanvasGlue::drawBitmapMesh},
    {"nativeDrawVertices", "(III[FI[FI[II[SIII)V",
        (void*)SkCanvasGlue::drawVertices},
    {"native_drawText","(I[CIIFFII)V",
        (void*) SkCanvasGlue::drawText___CIIFFIPaint},
    {"native_drawText","(ILjava/lang/String;IIFFII)V",
        (void*) SkCanvasGlue::drawText__StringIIFFIPaint},
    {"native_drawTextRun","(I[CIIIIFFII)V",
        (void*) SkCanvasGlue::drawTextRun___CIIIIFFIPaint},
    {"native_drawTextRun","(ILjava/lang/String;IIIIFFII)V",
        (void*) SkCanvasGlue::drawTextRun__StringIIIIFFIPaint},
    {"native_drawPosText","(I[CII[FI)V",
        (void*) SkCanvasGlue::drawPosText___CII_FPaint},
    {"native_drawPosText","(ILjava/lang/String;[FI)V",
        (void*) SkCanvasGlue::drawPosText__String_FPaint},
    {"native_drawTextOnPath","(I[CIIIFFII)V",
        (void*) SkCanvasGlue::drawTextOnPath___CIIPathFFPaint},
    {"native_drawTextOnPath","(ILjava/lang/String;IFFII)V",
        (void*) SkCanvasGlue::drawTextOnPath__StringPathFFPaint},
    {"native_drawPicture", "(II)V", (void*) SkCanvasGlue::drawPicture},

    {"freeCaches", "()V", (void*) SkCanvasGlue::freeCaches}
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

}
