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

#include "GraphicsJNI.h"

#ifdef __ANDROID_
#include <android/api-level.h>
#else
#define __ANDROID_API_P__ 28
#endif
#include <androidfw/ResourceTypes.h>
#include <hwui/Canvas.h>
#include <hwui/Paint.h>
#include <hwui/PaintFilter.h>
#include <hwui/Typeface.h>
#include <minikin/Layout.h>
#include <nativehelper/ScopedPrimitiveArray.h>
#include <nativehelper/ScopedStringChars.h>

#include "Bitmap.h"
#include "FontUtils.h"
#include "SkBitmap.h"
#include "SkBlendMode.h"
#include "SkClipOp.h"
#include "SkColor.h"
#include "SkColorSpace.h"
#include "SkGraphics.h"
#include "SkImageInfo.h"
#include "SkMatrix.h"
#include "SkPath.h"
#include "SkPoint.h"
#include "SkRRect.h"
#include "SkRect.h"
#include "SkRefCnt.h"
#include "SkRegion.h"
#include "SkScalar.h"
#include "SkVertices.h"

namespace minikin {
class MeasuredText;
}  // namespace minikin

namespace android {

namespace CanvasJNI {

static Canvas* get_canvas(jlong canvasHandle) {
    return reinterpret_cast<Canvas*>(canvasHandle);
}

static void delete_canvas(Canvas* canvas) {
    delete canvas;
}

static jlong getNativeFinalizer(JNIEnv* env, jobject clazz) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&delete_canvas));
}

// Native wrapper constructor used by Canvas(Bitmap)
static jlong initRaster(JNIEnv* env, jobject, jlong bitmapHandle) {
    SkBitmap bitmap;
    if (bitmapHandle != 0) {
        bitmap::toBitmap(bitmapHandle).getSkBitmap(&bitmap);
    }
    return reinterpret_cast<jlong>(Canvas::create_canvas(bitmap));
}

// Set the given bitmap as the new draw target (wrapped in a new SkCanvas),
// optionally copying canvas matrix & clip state.
static void setBitmap(JNIEnv* env, jobject, jlong canvasHandle, jlong bitmapHandle) {
    SkBitmap bitmap;
    if (bitmapHandle != 0) {
        bitmap::toBitmap(bitmapHandle).getSkBitmap(&bitmap);
    }
    get_canvas(canvasHandle)->setBitmap(bitmap);
}

static jboolean isOpaque(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle) {
    return get_canvas(canvasHandle)->isOpaque() ? JNI_TRUE : JNI_FALSE;
}

static jint getWidth(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle) {
    return static_cast<jint>(get_canvas(canvasHandle)->width());
}

static jint getHeight(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle) {
    return static_cast<jint>(get_canvas(canvasHandle)->height());
}

static jint save(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jint flagsHandle) {
    SaveFlags::Flags flags = static_cast<SaveFlags::Flags>(flagsHandle);
    return static_cast<jint>(get_canvas(canvasHandle)->save(flags));
}

static jint saveLayer(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jfloat l, jfloat t,
                      jfloat r, jfloat b, jlong paintHandle) {
    Paint* paint  = reinterpret_cast<Paint*>(paintHandle);
    return static_cast<jint>(get_canvas(canvasHandle)->saveLayer(l, t, r, b, paint));
}

static jint saveLayerAlpha(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jfloat l, jfloat t,
                           jfloat r, jfloat b, jint alpha) {
    return static_cast<jint>(get_canvas(canvasHandle)->saveLayerAlpha(l, t, r, b, alpha));
}

static jint saveUnclippedLayer(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jint l, jint t, jint r, jint b) {
    return reinterpret_cast<jint>(get_canvas(canvasHandle)->saveUnclippedLayer(l, t, r, b));
}

static void restoreUnclippedLayer(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jint saveCount, jlong paintHandle) {
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->restoreUnclippedLayer(saveCount, *paint);
}

static jboolean restore(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle) {
    Canvas* canvas = get_canvas(canvasHandle);
    if (canvas->getSaveCount() <= 1) {
        return false; // cannot restore anymore
    }
    canvas->restore();
    return true; // success
}

static void restoreToCount(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jint saveCount) {
    Canvas* canvas = get_canvas(canvasHandle);
    canvas->restoreToCount(saveCount);
}

static jint getSaveCount(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle) {
    return static_cast<jint>(get_canvas(canvasHandle)->getSaveCount());
}

static void getMatrix(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jlong matrixHandle) {
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    get_canvas(canvasHandle)->getMatrix(matrix);
}

static void setMatrix(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jlong matrixHandle) {
    const SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    get_canvas(canvasHandle)->setMatrix(matrix ? *matrix : SkMatrix::I());
}

static void concat(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jlong matrixHandle) {
    const SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    get_canvas(canvasHandle)->concat(*matrix);
}

static void concat44(JNIEnv* env, jobject obj, jlong canvasHandle, jfloatArray arr) {
    jfloat* matVals = env->GetFloatArrayElements(arr, 0);
    const SkM44 matrix = SkM44::RowMajor(matVals);
    get_canvas(canvasHandle)->concat(matrix);
    env->ReleaseFloatArrayElements(arr, matVals, 0);
}

static void rotate(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jfloat degrees) {
    get_canvas(canvasHandle)->rotate(degrees);
}

static void scale(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jfloat sx, jfloat sy) {
    get_canvas(canvasHandle)->scale(sx, sy);
}

static void skew(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jfloat sx, jfloat sy) {
    get_canvas(canvasHandle)->skew(sx, sy);
}

static void translate(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jfloat dx, jfloat dy) {
    get_canvas(canvasHandle)->translate(dx, dy);
}

static jboolean getClipBounds(JNIEnv* env, jobject, jlong canvasHandle, jobject bounds) {
    SkRect   r;
    SkIRect ir;
    bool result = get_canvas(canvasHandle)->getClipBounds(&r);

    if (!result) {
        r.setEmpty();
    }
    r.round(&ir);

    (void)GraphicsJNI::irect_to_jrect(ir, env, bounds);
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean quickRejectRect(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle,
                                jfloat left, jfloat top, jfloat right, jfloat bottom) {
    bool result = get_canvas(canvasHandle)->quickRejectRect(left, top, right, bottom);
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean quickRejectPath(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jlong pathHandle) {
    SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
    bool result = get_canvas(canvasHandle)->quickRejectPath(*path);
    return result ? JNI_TRUE : JNI_FALSE;
}

// SkClipOp is a strict subset of SkRegion::Op and is castable back and forth for their
// shared operations (intersect and difference).
static_assert(SkRegion::kDifference_Op == static_cast<SkRegion::Op>(SkClipOp::kDifference), "");
static_assert(SkRegion::kIntersect_Op == static_cast<SkRegion::Op>(SkClipOp::kIntersect), "");

static jboolean clipRect(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jfloat l, jfloat t,
                         jfloat r, jfloat b, jint opHandle) {
    // The opHandle is defined in Canvas.java to be Region::Op
    SkRegion::Op rgnOp = static_cast<SkRegion::Op>(opHandle);
    bool nonEmptyClip;
    switch (rgnOp) {
        case SkRegion::Op::kIntersect_Op:
        case SkRegion::Op::kDifference_Op:
            // Intersect and difference are supported clip operations
            nonEmptyClip =
                    get_canvas(canvasHandle)->clipRect(l, t, r, b, static_cast<SkClipOp>(rgnOp));
            break;
        case SkRegion::Op::kReplace_Op:
            // Replace is emulated to support legacy apps older than P
            nonEmptyClip = get_canvas(canvasHandle)->replaceClipRect_deprecated(l, t, r, b);
            break;
        default:
            // All other operations would expand the clip and are no longer supported,
            // so log and skip (to avoid breaking legacy apps).
            ALOGW("Ignoring unsupported clip operation %d", opHandle);
            SkRect clipBounds;  // ignored
            nonEmptyClip = get_canvas(canvasHandle)->getClipBounds(&clipBounds);
            break;
    }
    return nonEmptyClip ? JNI_TRUE : JNI_FALSE;
}

static jboolean clipPath(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jlong pathHandle,
                         jint opHandle) {
    SkRegion::Op rgnOp = static_cast<SkRegion::Op>(opHandle);
    SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
    bool nonEmptyClip;
    switch (rgnOp) {
        case SkRegion::Op::kIntersect_Op:
        case SkRegion::Op::kDifference_Op:
            nonEmptyClip = get_canvas(canvasHandle)->clipPath(path, static_cast<SkClipOp>(rgnOp));
            break;
        case SkRegion::Op::kReplace_Op:
            nonEmptyClip = get_canvas(canvasHandle)->replaceClipPath_deprecated(path);
            break;
        default:
            ALOGW("Ignoring unsupported clip operation %d", opHandle);
            SkRect clipBounds;  // ignored
            nonEmptyClip = get_canvas(canvasHandle)->getClipBounds(&clipBounds);
            break;
    }
    return nonEmptyClip ? JNI_TRUE : JNI_FALSE;
}

static void drawColor(JNIEnv* env, jobject, jlong canvasHandle, jint color, jint modeHandle) {
    SkBlendMode mode = static_cast<SkBlendMode>(modeHandle);
    get_canvas(canvasHandle)->drawColor(color, mode);
}

static void drawColorLong(JNIEnv* env, jobject, jlong canvasHandle, jlong colorSpaceHandle,
        jlong colorLong, jint modeHandle) {
    SkColor4f color = GraphicsJNI::convertColorLong(colorLong);
    sk_sp<SkColorSpace> cs = GraphicsJNI::getNativeColorSpace(colorSpaceHandle);
    Paint p;
    p.setColor4f(color, cs.get());

    SkBlendMode mode = static_cast<SkBlendMode>(modeHandle);
    p.setBlendMode(mode);
    get_canvas(canvasHandle)->drawPaint(p);
}

static void drawPaint(JNIEnv* env, jobject, jlong canvasHandle, jlong paintHandle) {
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawPaint(*paint);
}

static void drawPoint(JNIEnv*, jobject, jlong canvasHandle, jfloat x, jfloat y,
                      jlong paintHandle) {
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawPoint(x, y, *paint);
}

static void drawPoints(JNIEnv* env, jobject, jlong canvasHandle, jfloatArray jptsArray,
                       jint offset, jint count, jlong paintHandle) {
    NPE_CHECK_RETURN_VOID(env, jptsArray);
    AutoJavaFloatArray autoPts(env, jptsArray);
    float* floats = autoPts.ptr();
    const int length = autoPts.length();

    if ((offset | count) < 0 || offset + count > length) {
        doThrowAIOOBE(env);
        return;
    }

    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawPoints(floats + offset, count, *paint);
}

static void drawLine(JNIEnv* env, jobject, jlong canvasHandle, jfloat startX, jfloat startY,
                     jfloat stopX, jfloat stopY, jlong paintHandle) {
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawLine(startX, startY, stopX, stopY, *paint);
}

static void drawLines(JNIEnv* env, jobject, jlong canvasHandle, jfloatArray jptsArray,
                      jint offset, jint count, jlong paintHandle) {
    NPE_CHECK_RETURN_VOID(env, jptsArray);
    AutoJavaFloatArray autoPts(env, jptsArray);
    float* floats = autoPts.ptr();
    const int length = autoPts.length();

    if ((offset | count) < 0 || offset + count > length) {
        doThrowAIOOBE(env);
        return;
    }

    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawLines(floats + offset, count, *paint);
}

static void drawRect(JNIEnv* env, jobject, jlong canvasHandle, jfloat left, jfloat top,
                     jfloat right, jfloat bottom, jlong paintHandle) {
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawRect(left, top, right, bottom, *paint);
}

static void drawDoubleRoundRectXY(JNIEnv* env, jobject, jlong canvasHandle, jfloat outerLeft,
                    jfloat outerTop, jfloat outerRight, jfloat outerBottom, jfloat outerRx,
                    jfloat outerRy, jfloat innerLeft, jfloat innerTop, jfloat innerRight,
                    jfloat innerBottom, jfloat innerRx, jfloat innerRy, jlong paintHandle) {
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawDoubleRoundRectXY(
                    outerLeft, outerTop, outerRight, outerBottom, outerRx, outerRy,
                    innerLeft, innerTop, innerRight, innerBottom, innerRx, innerRy, *paint);
}

static void drawDoubleRoundRectRadii(JNIEnv* env, jobject, jlong canvasHandle, jfloat outerLeft,
                     jfloat outerTop, jfloat outerRight, jfloat outerBottom, jfloatArray jouterRadii,
                     jfloat innerLeft, jfloat innerTop, jfloat innerRight,
                     jfloat innerBottom, jfloatArray jinnerRadii, jlong paintHandle) {
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);

    float outerRadii[8];
    float innerRadii[8];
    env->GetFloatArrayRegion(jouterRadii, 0, 8, outerRadii);
    env->GetFloatArrayRegion(jinnerRadii, 0, 8, innerRadii);
    get_canvas(canvasHandle)->drawDoubleRoundRectRadii(
                    outerLeft, outerTop, outerRight, outerBottom, outerRadii,
                    innerLeft, innerTop, innerRight, innerBottom, innerRadii, *paint);

}

static void drawRegion(JNIEnv* env, jobject, jlong canvasHandle, jlong regionHandle,
                       jlong paintHandle) {
    const SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawRegion(*region, *paint);
}

static void drawRoundRect(JNIEnv* env, jobject, jlong canvasHandle, jfloat left, jfloat top,
                          jfloat right, jfloat bottom, jfloat rx, jfloat ry, jlong paintHandle) {
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawRoundRect(left, top, right, bottom, rx, ry, *paint);
}

static void drawCircle(JNIEnv* env, jobject, jlong canvasHandle, jfloat cx, jfloat cy,
                       jfloat radius, jlong paintHandle) {
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawCircle(cx, cy, radius, *paint);
}

static void drawOval(JNIEnv* env, jobject, jlong canvasHandle, jfloat left, jfloat top,
                     jfloat right, jfloat bottom, jlong paintHandle) {
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawOval(left, top, right, bottom, *paint);
}

static void drawArc(JNIEnv* env, jobject, jlong canvasHandle, jfloat left, jfloat top,
                    jfloat right, jfloat bottom, jfloat startAngle, jfloat sweepAngle,
                    jboolean useCenter, jlong paintHandle) {
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawArc(left, top, right, bottom, startAngle, sweepAngle,
                                       useCenter, *paint);
}

static void drawPath(JNIEnv* env, jobject, jlong canvasHandle, jlong pathHandle,
                     jlong paintHandle) {
    const SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawPath(*path, *paint);
}

static void drawVertices(JNIEnv* env, jobject, jlong canvasHandle,
                         jint modeHandle, jint floatCount,
                         jfloatArray jverts, jint vertIndex,
                         jfloatArray jtexs, jint texIndex,
                         jintArray jcolors, jint colorIndex,
                         jshortArray jindices, jint indexIndex,
                         jint indexCount, jlong paintHandle) {

    const int vertexCount = floatCount >> 1;  // 2 floats per SkPoint

    AutoJavaFloatArray  vertA(env, jverts, vertIndex + floatCount);
    AutoJavaFloatArray  texA(env, jtexs, texIndex + floatCount);
    AutoJavaIntArray    colorA(env, jcolors, colorIndex + vertexCount);
    AutoJavaShortArray  indexA(env, jindices, indexIndex + indexCount);

    const float* verts = vertA.ptr() + vertIndex;
    const float* texs = texA.ptr() + vertIndex;
    const int* colors = NULL;
    const uint16_t* indices = NULL;

    if (jcolors != NULL) {
        colors = colorA.ptr() + colorIndex;
    }
    if (jindices != NULL) {
        indices = (const uint16_t*)(indexA.ptr() + indexIndex);
    }

    SkVertices::VertexMode vertexMode = static_cast<SkVertices::VertexMode>(modeHandle);
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);

    // Preserve legacy Skia behavior: ignore the shader if there are no texs set.
    Paint noShaderPaint;
    if (jtexs == NULL) {
        noShaderPaint = Paint(*paint);
        noShaderPaint.setShader(nullptr);
        paint = &noShaderPaint;
    }
    // Since https://skia-review.googlesource.com/c/skia/+/473676, Skia will blend paint and vertex
    // colors when no shader is provided. This ternary uses kDst to mimic the old behavior of
    // ignoring the paint and using the vertex colors directly when no shader is provided.
    SkBlendMode blendMode = paint->getShader() ? SkBlendMode::kModulate : SkBlendMode::kDst;

    get_canvas(canvasHandle)
            ->drawVertices(SkVertices::MakeCopy(
                                   vertexMode, vertexCount, reinterpret_cast<const SkPoint*>(verts),
                                   reinterpret_cast<const SkPoint*>(texs),
                                   reinterpret_cast<const SkColor*>(colors), indexCount, indices)
                                   .get(),
                           blendMode, *paint);
}

static void drawMesh(JNIEnv* env, jobject, jlong canvasHandle, jlong meshHandle, jint modeHandle,
                     jlong paintHandle) {
    const Mesh* mesh = reinterpret_cast<Mesh*>(meshHandle);
    SkBlendMode blendMode = static_cast<SkBlendMode>(modeHandle);
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawMesh(*mesh, SkBlender::Mode(blendMode), *paint);
}

static void drawNinePatch(JNIEnv* env, jobject, jlong canvasHandle, jlong bitmapHandle,
        jlong chunkHandle, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jlong paintHandle, jint dstDensity, jint srcDensity) {

    Canvas* canvas = get_canvas(canvasHandle);
    Bitmap& bitmap = android::bitmap::toBitmap(bitmapHandle);
    const android::Res_png_9patch* chunk = reinterpret_cast<android::Res_png_9patch*>(chunkHandle);
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);

    if (CC_LIKELY(dstDensity == srcDensity || dstDensity == 0 || srcDensity == 0)) {
        canvas->drawNinePatch(bitmap, *chunk, left, top, right, bottom, paint);
    } else {
        canvas->save(SaveFlags::MatrixClip);

        SkScalar scale = dstDensity / (float)srcDensity;
        canvas->translate(left, top);
        canvas->scale(scale, scale);

        Paint filteredPaint;
        if (paint) {
            filteredPaint = *paint;
        }
        filteredPaint.setFilterBitmap(true);

        canvas->drawNinePatch(bitmap, *chunk, 0, 0, (right-left)/scale, (bottom-top)/scale,
                &filteredPaint);

        canvas->restore();
    }
}

static void drawBitmap(JNIEnv* env, jobject, jlong canvasHandle, jlong bitmapHandle,
                       jfloat left, jfloat top, jlong paintHandle, jint canvasDensity,
                       jint screenDensity, jint bitmapDensity) {
    Canvas* canvas = get_canvas(canvasHandle);
    Bitmap& bitmap = android::bitmap::toBitmap(bitmapHandle);
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);

    if (canvasDensity == bitmapDensity || canvasDensity == 0 || bitmapDensity == 0) {
        if (screenDensity != 0 && screenDensity != bitmapDensity) {
            Paint filteredPaint;
            if (paint) {
                filteredPaint = *paint;
            }
            filteredPaint.setFilterBitmap(true);
            canvas->drawBitmap(bitmap, left, top, &filteredPaint);
        } else {
            canvas->drawBitmap(bitmap, left, top, paint);
        }
    } else {
        canvas->save(SaveFlags::MatrixClip);
        SkScalar scale = canvasDensity / (float)bitmapDensity;
        canvas->translate(left, top);
        canvas->scale(scale, scale);

        Paint filteredPaint;
        if (paint) {
            filteredPaint = *paint;
        }
        filteredPaint.setFilterBitmap(true);

        canvas->drawBitmap(bitmap, 0, 0, &filteredPaint);
        canvas->restore();
    }
}

static void drawBitmapMatrix(JNIEnv* env, jobject, jlong canvasHandle, jlong bitmapHandle,
                             jlong matrixHandle, jlong paintHandle) {
    const SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    Bitmap& bitmap = android::bitmap::toBitmap(bitmapHandle);
    get_canvas(canvasHandle)->drawBitmap(bitmap, *matrix, paint);
}

static void drawBitmapRect(JNIEnv* env, jobject, jlong canvasHandle, jlong bitmapHandle,
                           float srcLeft, float srcTop, float srcRight, float srcBottom,
                           float dstLeft, float dstTop, float dstRight, float dstBottom,
                           jlong paintHandle, jint screenDensity, jint bitmapDensity) {
    Canvas* canvas = get_canvas(canvasHandle);
    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);

    Bitmap& bitmap = android::bitmap::toBitmap(bitmapHandle);
    if (screenDensity != 0 && screenDensity != bitmapDensity) {
        Paint filteredPaint;
        if (paint) {
            filteredPaint = *paint;
        }
        filteredPaint.setFilterBitmap(true);
        canvas->drawBitmap(bitmap, srcLeft, srcTop, srcRight, srcBottom,
                           dstLeft, dstTop, dstRight, dstBottom, &filteredPaint);
    } else {
        canvas->drawBitmap(bitmap, srcLeft, srcTop, srcRight, srcBottom,
                           dstLeft, dstTop, dstRight, dstBottom, paint);
    }
}

static void drawBitmapArray(JNIEnv* env, jobject, jlong canvasHandle,
                            jintArray jcolors, jint offset, jint stride,
                            jfloat x, jfloat y, jint width, jint height,
                            jboolean hasAlpha, jlong paintHandle) {
    // Note: If hasAlpha is false, kRGB_565_SkColorType will be used, which will
    // correct the alphaType to kOpaque_SkAlphaType.
    SkImageInfo info = SkImageInfo::Make(width, height,
                           hasAlpha ? kN32_SkColorType : kRGB_565_SkColorType,
                           kPremul_SkAlphaType);
    SkBitmap bitmap;
    bitmap.setInfo(info);
    sk_sp<Bitmap> androidBitmap = Bitmap::allocateHeapBitmap(&bitmap);
    if (!androidBitmap) {
        return;
    }

    if (!GraphicsJNI::SetPixels(env, jcolors, offset, stride, 0, 0, width, height, &bitmap)) {
        return;
    }

    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    get_canvas(canvasHandle)->drawBitmap(*androidBitmap, x, y, paint);
}

static void drawBitmapMesh(JNIEnv* env, jobject, jlong canvasHandle, jlong bitmapHandle,
                           jint meshWidth, jint meshHeight, jfloatArray jverts,
                           jint vertIndex, jintArray jcolors, jint colorIndex, jlong paintHandle) {
    if (Canvas::GetApiLevel() < __ANDROID_API_P__) {
        // Before P we forgot to respect these. Now that we do respect them, explicitly
        // zero them for backward compatibility.
        vertIndex = 0;
        colorIndex = 0;
    }

    const int ptCount = (meshWidth + 1) * (meshHeight + 1);
    AutoJavaFloatArray vertA(env, jverts, vertIndex + (ptCount << 1));
    AutoJavaIntArray colorA(env, jcolors, colorIndex + ptCount);

    const Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    Bitmap& bitmap = android::bitmap::toBitmap(bitmapHandle);
    get_canvas(canvasHandle)->drawBitmapMesh(bitmap, meshWidth, meshHeight,
                                             vertA.ptr() + vertIndex*2,
                                             colorA.ptr() + colorIndex, paint);
}

static void drawGlyphs(JNIEnv* env, jobject, jlong canvasHandle, jintArray glyphIds,
                       jfloatArray positions, jint glyphOffset, jint positionOffset,
                       jint glyphCount, jlong fontHandle, jlong paintHandle) {
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    FontWrapper* font = reinterpret_cast<FontWrapper*>(fontHandle);
    AutoJavaIntArray glyphIdArray(env, glyphIds);
    AutoJavaFloatArray positionArray(env, positions);
    get_canvas(canvasHandle)->drawGlyphs(
        *font->font.get(),
        glyphIdArray.ptr() + glyphOffset,
        positionArray.ptr() + positionOffset,
        glyphCount,
        *paint);
}

static void drawTextChars(JNIEnv* env, jobject, jlong canvasHandle, jcharArray charArray,
                          jint index, jint count, jfloat x, jfloat y, jint bidiFlags,
                          jlong paintHandle) {
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    const Typeface* typeface = paint->getAndroidTypeface();
    ScopedCharArrayRO text(env, charArray);

    // The drawText API is designed to draw entire line, so ignore the text run flag and draw the
    // text as entire line mode.
    const minikin::RunFlag originalRunFlag = paint->getRunFlag();
    paint->setRunFlag(minikin::RunFlag::WHOLE_LINE);

    // drawTextString and drawTextChars doesn't use context info
    get_canvas(canvasHandle)->drawText(
            text.get() + index, count,  // text buffer
            0, count,  // draw range
            0, count,  // context range
            x, y,  // draw position
            static_cast<minikin::Bidi>(bidiFlags), *paint, typeface, nullptr /* measured text */);
    paint->setRunFlag(originalRunFlag);
}

static void drawTextString(JNIEnv* env, jobject, jlong canvasHandle, jstring strObj,
                           jint start, jint end, jfloat x, jfloat y, jint bidiFlags,
                           jlong paintHandle) {
    ScopedStringChars text(env, strObj);
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    const Typeface* typeface = paint->getAndroidTypeface();
    const int count = end - start;

    // The drawText API is designed to draw entire line, so ignore the text run flag and draw the
    // text as entire line mode.
    const minikin::RunFlag originalRunFlag = paint->getRunFlag();
    paint->setRunFlag(minikin::RunFlag::WHOLE_LINE);

    // drawTextString and drawTextChars doesn't use context info
    get_canvas(canvasHandle)->drawText(
            text.get() + start, count,  // text buffer
            0, count,  // draw range
            0, count,  // context range
            x, y,  // draw position
            static_cast<minikin::Bidi>(bidiFlags), *paint, typeface, nullptr /* measured text */);
    paint->setRunFlag(originalRunFlag);
}

static void drawTextRunChars(JNIEnv* env, jobject, jlong canvasHandle, jcharArray charArray,
                             jint index, jint count, jint contextIndex, jint contextCount,
                             jfloat x, jfloat y, jboolean isRtl, jlong paintHandle,
                             jlong mtHandle) {
    minikin::MeasuredText* mt = reinterpret_cast<minikin::MeasuredText*>(mtHandle);
    const minikin::Bidi bidiFlags = isRtl ? minikin::Bidi::FORCE_RTL : minikin::Bidi::FORCE_LTR;

    ScopedCharArrayRO text(env, charArray);
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    const Typeface* typeface = paint->getAndroidTypeface();
    get_canvas(canvasHandle)->drawText(
            text.get(), text.size(),  // text buffer
            index, count,  // draw range
            contextIndex, contextCount,  // context range,
            x, y,  // draw position
            bidiFlags, *paint, typeface, mt);
}

static void drawTextRunString(JNIEnv* env, jobject obj, jlong canvasHandle, jstring strObj,
                              jint start, jint end, jint contextStart, jint contextEnd,
                              jfloat x, jfloat y, jboolean isRtl, jlong paintHandle) {
    const minikin::Bidi bidiFlags = isRtl ? minikin::Bidi::FORCE_RTL : minikin::Bidi::FORCE_LTR;

    ScopedStringChars text(env, strObj);
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    const Typeface* typeface = paint->getAndroidTypeface();
    get_canvas(canvasHandle)->drawText(
            text.get(), text.size(),  // text buffer
            start, end - start,  // draw range
            contextStart, contextEnd - contextStart,  // context range
            x, y,  // draw position
            bidiFlags, *paint, typeface, nullptr /* measured text */);
}

static void drawTextOnPathChars(JNIEnv* env, jobject, jlong canvasHandle, jcharArray text,
                                jint index, jint count, jlong pathHandle, jfloat hOffset,
                                jfloat vOffset, jint bidiFlags, jlong paintHandle) {
    SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    const Typeface* typeface = paint->getAndroidTypeface();

    jchar* jchars = env->GetCharArrayElements(text, NULL);

    // The drawText API is designed to draw entire line, so ignore the text run flag and draw the
    // text as entire line mode.
    const minikin::RunFlag originalRunFlag = paint->getRunFlag();
    paint->setRunFlag(minikin::RunFlag::WHOLE_LINE);

    get_canvas(canvasHandle)->drawTextOnPath(jchars + index, count,
            static_cast<minikin::Bidi>(bidiFlags), *path, hOffset, vOffset, *paint, typeface);

    paint->setRunFlag(originalRunFlag);
    env->ReleaseCharArrayElements(text, jchars, 0);
}

static void drawTextOnPathString(JNIEnv* env, jobject, jlong canvasHandle, jstring text,
                                 jlong pathHandle, jfloat hOffset, jfloat vOffset,
                                 jint bidiFlags, jlong paintHandle) {
    SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
    Paint* paint = reinterpret_cast<Paint*>(paintHandle);
    const Typeface* typeface = paint->getAndroidTypeface();

    const jchar* jchars = env->GetStringChars(text, NULL);
    int count = env->GetStringLength(text);

    // The drawText API is designed to draw entire line, so ignore the text run flag and draw the
    // text as entire line mode.
    const minikin::RunFlag originalRunFlag = paint->getRunFlag();
    paint->setRunFlag(minikin::RunFlag::WHOLE_LINE);

    get_canvas(canvasHandle)->drawTextOnPath(jchars, count, static_cast<minikin::Bidi>(bidiFlags),
            *path, hOffset, vOffset, *paint, typeface);

    paint->setRunFlag(originalRunFlag);
    env->ReleaseStringChars(text, jchars);
}

static void setPaintFilter(CRITICAL_JNI_PARAMS_COMMA jlong canvasHandle, jlong filterHandle) {
    PaintFilter* paintFilter = reinterpret_cast<PaintFilter*>(filterHandle);
    get_canvas(canvasHandle)->setPaintFilter(sk_ref_sp(paintFilter));
}

static void freeCaches(JNIEnv* env, jobject) {
    SkGraphics::PurgeFontCache();
}

static void freeTextLayoutCaches(JNIEnv* env, jobject) {
    minikin::Layout::purgeCaches();
}

static void setCompatibilityVersion(JNIEnv* env, jobject, jint apiLevel) {
    Canvas::setCompatibilityVersion(apiLevel);
}

static void punchHole(JNIEnv* env, jobject, jlong canvasPtr, jfloat left, jfloat top, jfloat right,
        jfloat bottom, jfloat rx, jfloat ry, jfloat alpha) {
    auto canvas = reinterpret_cast<Canvas*>(canvasPtr);
    canvas->punchHole(SkRRect::MakeRectXY(SkRect::MakeLTRB(left, top, right, bottom), rx, ry),
                      alpha);
}

}; // namespace CanvasJNI

static const JNINativeMethod gMethods[] = {
        {"nGetNativeFinalizer", "()J", (void*)CanvasJNI::getNativeFinalizer},
        {"nFreeCaches", "()V", (void*)CanvasJNI::freeCaches},
        {"nFreeTextLayoutCaches", "()V", (void*)CanvasJNI::freeTextLayoutCaches},
        {"nSetCompatibilityVersion", "(I)V", (void*)CanvasJNI::setCompatibilityVersion},

        // ------------ @FastNative ----------------
        {"nInitRaster", "(J)J", (void*)CanvasJNI::initRaster},
        {"nSetBitmap", "(JJ)V", (void*)CanvasJNI::setBitmap},
        {"nGetClipBounds", "(JLandroid/graphics/Rect;)Z", (void*)CanvasJNI::getClipBounds},

        // ------------ @CriticalNative ----------------
        {"nIsOpaque", "(J)Z", (void*)CanvasJNI::isOpaque},
        {"nGetWidth", "(J)I", (void*)CanvasJNI::getWidth},
        {"nGetHeight", "(J)I", (void*)CanvasJNI::getHeight},
        {"nSave", "(JI)I", (void*)CanvasJNI::save},
        {"nSaveLayer", "(JFFFFJ)I", (void*)CanvasJNI::saveLayer},
        {"nSaveLayerAlpha", "(JFFFFI)I", (void*)CanvasJNI::saveLayerAlpha},
        {"nSaveUnclippedLayer", "(JIIII)I", (void*)CanvasJNI::saveUnclippedLayer},
        {"nRestoreUnclippedLayer", "(JIJ)V", (void*)CanvasJNI::restoreUnclippedLayer},
        {"nGetSaveCount", "(J)I", (void*)CanvasJNI::getSaveCount},
        {"nRestore", "(J)Z", (void*)CanvasJNI::restore},
        {"nRestoreToCount", "(JI)V", (void*)CanvasJNI::restoreToCount},
        {"nGetMatrix", "(JJ)V", (void*)CanvasJNI::getMatrix},
        {"nSetMatrix", "(JJ)V", (void*)CanvasJNI::setMatrix},
        {"nConcat", "(JJ)V", (void*)CanvasJNI::concat},
        {"nConcat", "(J[F)V", (void*)CanvasJNI::concat44},
        {"nRotate", "(JF)V", (void*)CanvasJNI::rotate},
        {"nScale", "(JFF)V", (void*)CanvasJNI::scale},
        {"nSkew", "(JFF)V", (void*)CanvasJNI::skew},
        {"nTranslate", "(JFF)V", (void*)CanvasJNI::translate},
        {"nQuickReject", "(JJ)Z", (void*)CanvasJNI::quickRejectPath},
        {"nQuickReject", "(JFFFF)Z", (void*)CanvasJNI::quickRejectRect},
        {"nClipRect", "(JFFFFI)Z", (void*)CanvasJNI::clipRect},
        {"nClipPath", "(JJI)Z", (void*)CanvasJNI::clipPath},
        {"nSetDrawFilter", "(JJ)V", (void*)CanvasJNI::setPaintFilter},
};

// If called from Canvas these are regular JNI
// If called from DisplayListCanvas they are @FastNative
static const JNINativeMethod gDrawMethods[] = {
        {"nDrawColor", "(JII)V", (void*)CanvasJNI::drawColor},
        {"nDrawColor", "(JJJI)V", (void*)CanvasJNI::drawColorLong},
        {"nDrawPaint", "(JJ)V", (void*)CanvasJNI::drawPaint},
        {"nDrawPoint", "(JFFJ)V", (void*)CanvasJNI::drawPoint},
        {"nDrawPoints", "(J[FIIJ)V", (void*)CanvasJNI::drawPoints},
        {"nDrawLine", "(JFFFFJ)V", (void*)CanvasJNI::drawLine},
        {"nDrawLines", "(J[FIIJ)V", (void*)CanvasJNI::drawLines},
        {"nDrawRect", "(JFFFFJ)V", (void*)CanvasJNI::drawRect},
        {"nDrawRegion", "(JJJ)V", (void*)CanvasJNI::drawRegion},
        {"nDrawRoundRect", "(JFFFFFFJ)V", (void*)CanvasJNI::drawRoundRect},
        {"nDrawDoubleRoundRect", "(JFFFFFFFFFFFFJ)V", (void*)CanvasJNI::drawDoubleRoundRectXY},
        {"nDrawDoubleRoundRect", "(JFFFF[FFFFF[FJ)V", (void*)CanvasJNI::drawDoubleRoundRectRadii},
        {"nDrawCircle", "(JFFFJ)V", (void*)CanvasJNI::drawCircle},
        {"nDrawOval", "(JFFFFJ)V", (void*)CanvasJNI::drawOval},
        {"nDrawArc", "(JFFFFFFZJ)V", (void*)CanvasJNI::drawArc},
        {"nDrawPath", "(JJJ)V", (void*)CanvasJNI::drawPath},
        {"nDrawVertices", "(JII[FI[FI[II[SIIJ)V", (void*)CanvasJNI::drawVertices},
        {"nDrawMesh", "(JJIJ)V", (void*)CanvasJNI::drawMesh},
        {"nDrawNinePatch", "(JJJFFFFJII)V", (void*)CanvasJNI::drawNinePatch},
        {"nDrawBitmapMatrix", "(JJJJ)V", (void*)CanvasJNI::drawBitmapMatrix},
        {"nDrawBitmapMesh", "(JJII[FI[IIJ)V", (void*)CanvasJNI::drawBitmapMesh},
        {"nDrawBitmap", "(JJFFJIII)V", (void*)CanvasJNI::drawBitmap},
        {"nDrawBitmap", "(JJFFFFFFFFJII)V", (void*)CanvasJNI::drawBitmapRect},
        {"nDrawBitmap", "(J[IIIFFIIZJ)V", (void*)CanvasJNI::drawBitmapArray},
        {"nDrawGlyphs", "(J[I[FIIIJJ)V", (void*)CanvasJNI::drawGlyphs},
        {"nDrawText", "(J[CIIFFIJ)V", (void*)CanvasJNI::drawTextChars},
        {"nDrawText", "(JLjava/lang/String;IIFFIJ)V", (void*)CanvasJNI::drawTextString},
        {"nDrawTextRun", "(J[CIIIIFFZJJ)V", (void*)CanvasJNI::drawTextRunChars},
        {"nDrawTextRun", "(JLjava/lang/String;IIIIFFZJ)V", (void*)CanvasJNI::drawTextRunString},
        {"nDrawTextOnPath", "(J[CIIJFFIJ)V", (void*)CanvasJNI::drawTextOnPathChars},
        {"nDrawTextOnPath", "(JLjava/lang/String;JFFIJ)V", (void*)CanvasJNI::drawTextOnPathString},
        {"nPunchHole", "(JFFFFFFF)V", (void*)CanvasJNI::punchHole}};

int register_android_graphics_Canvas(JNIEnv* env) {
    int ret = 0;
    ret |= RegisterMethodsOrDie(env, "android/graphics/Canvas", gMethods, NELEM(gMethods));
    ret |= RegisterMethodsOrDie(env, "android/graphics/BaseCanvas", gDrawMethods, NELEM(gDrawMethods));
    ret |= RegisterMethodsOrDie(env, "android/graphics/BaseRecordingCanvas", gDrawMethods, NELEM(gDrawMethods));
    return ret;

}

}; // namespace android
