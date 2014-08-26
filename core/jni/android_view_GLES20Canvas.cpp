/*
 * Copyright (C) 2010 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"

#include "jni.h"
#include "GraphicsJNI.h"
#include <nativehelper/JNIHelp.h>

#include <android_runtime/AndroidRuntime.h>

#include <androidfw/ResourceTypes.h>

#include <private/hwui/DrawGlInfo.h>

#include <cutils/properties.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkImageInfo.h>
#include <SkMatrix.h>
#include <SkPorterDuff.h>
#include <SkRegion.h>
#include <SkScalerContext.h>
#include <SkTemplates.h>
#include <SkXfermode.h>

#include <DisplayListRenderer.h>
#include <Rect.h>
#include <RenderNode.h>
#include <CanvasProperty.h>
#include <Paint.h>

#include "MinikinUtils.h"

namespace android {

using namespace uirenderer;

/**
 * Note: DisplayListRenderer JNI layer is generated and compiled only on supported
 *       devices. This means all the logic must be compiled only when the
 *       preprocessor variable USE_OPENGL_RENDERER is defined.
 */
#ifdef USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// Defines
// ----------------------------------------------------------------------------

// Debug
#define DEBUG_RENDERER 0

// Debug
#if DEBUG_RENDERER
    #define RENDERER_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define RENDERER_LOGD(...)
#endif

// ----------------------------------------------------------------------------

static struct {
    jmethodID set;
} gRectClassInfo;

// ----------------------------------------------------------------------------
// Constructors
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_destroyRenderer(JNIEnv* env, jobject clazz,
        jlong rendererPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    RENDERER_LOGD("Destroy DisplayListRenderer");
    delete renderer;
}

// ----------------------------------------------------------------------------
// Setup
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_setViewport(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint width, jint height) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->setViewport(width, height);
}

static void android_view_GLES20Canvas_setHighContrastText(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jboolean highContrastText) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->setHighContrastText(highContrastText);
}

static void android_view_GLES20Canvas_insertReorderBarrier(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jboolean reorderEnable) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->insertReorderBarrier(reorderEnable);
}

static int android_view_GLES20Canvas_prepare(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jboolean opaque) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    return renderer->prepare(opaque);
}

static int android_view_GLES20Canvas_prepareDirty(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint left, jint top, jint right, jint bottom,
        jboolean opaque) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    return renderer->prepareDirty(left, top, right, bottom, opaque);
}

static void android_view_GLES20Canvas_finish(JNIEnv* env, jobject clazz,
        jlong rendererPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->finish();
}

static void android_view_GLES20Canvas_setProperty(JNIEnv* env,
        jobject clazz, jstring name, jstring value) {
    if (!Caches::hasInstance()) {
        ALOGW("can't set property, no Caches instance");
        return;
    }

    if (name == NULL || value == NULL) {
        ALOGW("can't set prop, null passed");
    }

    const char* nameCharArray = env->GetStringUTFChars(name, NULL);
    const char* valueCharArray = env->GetStringUTFChars(value, NULL);
    Caches::getInstance().setTempProperty(nameCharArray, valueCharArray);
    env->ReleaseStringUTFChars(name, nameCharArray);
    env->ReleaseStringUTFChars(name, valueCharArray);
}

// ----------------------------------------------------------------------------
// Functor
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_callDrawGLFunction(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong functorPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Functor* functor = reinterpret_cast<Functor*>(functorPtr);
    android::uirenderer::Rect dirty;
    return renderer->callDrawGLFunction(functor, dirty);
}

// ----------------------------------------------------------------------------
// Misc
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_getMaxTextureWidth(JNIEnv* env, jobject clazz) {
    return Caches::getInstance().maxTextureSize;
}

static jint android_view_GLES20Canvas_getMaxTextureHeight(JNIEnv* env, jobject clazz) {
    return Caches::getInstance().maxTextureSize;
}

// ----------------------------------------------------------------------------
// State
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_save(JNIEnv* env, jobject clazz, jlong rendererPtr,
        jint flags) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    return renderer->save(flags);
}

static jint android_view_GLES20Canvas_getSaveCount(JNIEnv* env, jobject clazz,
        jlong rendererPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    return renderer->getSaveCount();
}

static void android_view_GLES20Canvas_restore(JNIEnv* env, jobject clazz,
        jlong rendererPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->restore();
}

static void android_view_GLES20Canvas_restoreToCount(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint saveCount) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->restoreToCount(saveCount);
}

// ----------------------------------------------------------------------------
// Layers
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_saveLayer(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jlong paintPtr, jint saveFlags) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    return renderer->saveLayer(left, top, right, bottom, paint, saveFlags);
}

static jint android_view_GLES20Canvas_saveLayerClip(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong paintPtr, jint saveFlags) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    const android::uirenderer::Rect& bounds(renderer->getLocalClipBounds());
    return renderer->saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom,
            paint, saveFlags);
}

static jint android_view_GLES20Canvas_saveLayerAlpha(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jint alpha, jint saveFlags) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    return renderer->saveLayerAlpha(left, top, right, bottom, alpha, saveFlags);
}

static jint android_view_GLES20Canvas_saveLayerAlphaClip(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint alpha, jint saveFlags) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    const android::uirenderer::Rect& bounds(renderer->getLocalClipBounds());
    return renderer->saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom,
            alpha, saveFlags);
}

// ----------------------------------------------------------------------------
// Clipping
// ----------------------------------------------------------------------------

static jboolean android_view_GLES20Canvas_quickReject(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat left, jfloat top, jfloat right, jfloat bottom) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    const bool result = renderer->quickRejectConservative(left, top, right, bottom);
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_view_GLES20Canvas_clipRectF(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jint op) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    const bool result = renderer->clipRect(left, top, right, bottom,
                                           static_cast<SkRegion::Op>(op));
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_view_GLES20Canvas_clipRect(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint left, jint top, jint right, jint bottom,
        jint op) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    const bool result = renderer->clipRect(float(left), float(top), float(right),
                                           float(bottom),
                                           static_cast<SkRegion::Op>(op));
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_view_GLES20Canvas_clipPath(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong pathPtr, jint op) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    SkPath* path = reinterpret_cast<SkPath*>(pathPtr);
    const bool result = renderer->clipPath(path, static_cast<SkRegion::Op>(op));
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_view_GLES20Canvas_clipRegion(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong regionPtr, jint op) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    SkRegion* region = reinterpret_cast<SkRegion*>(regionPtr);
    const bool result = renderer->clipRegion(region, static_cast<SkRegion::Op>(op));
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_view_GLES20Canvas_getClipBounds(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jobject rect) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    const android::uirenderer::Rect& bounds(renderer->getLocalClipBounds());

    env->CallVoidMethod(rect, gRectClassInfo.set,
            int(bounds.left), int(bounds.top), int(bounds.right), int(bounds.bottom));

    return !bounds.isEmpty() ? JNI_TRUE : JNI_FALSE;
}

// ----------------------------------------------------------------------------
// Transforms
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_translate(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat dx, jfloat dy) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->translate(dx, dy);
}

static void android_view_GLES20Canvas_rotate(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat degrees) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->rotate(degrees);
}

static void android_view_GLES20Canvas_scale(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat sx, jfloat sy) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->scale(sx, sy);
}

static void android_view_GLES20Canvas_skew(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat sx, jfloat sy) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->skew(sx, sy);
}

static void android_view_GLES20Canvas_setMatrix(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong matrixPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    renderer->setMatrix(matrix ? *matrix : SkMatrix::I());
}

static void android_view_GLES20Canvas_getMatrix(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong matrixPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    renderer->getMatrix(matrix);
}

static void android_view_GLES20Canvas_concatMatrix(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong matrixPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    renderer->concatMatrix(*matrix);
}

// ----------------------------------------------------------------------------
// Drawing
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_drawBitmap(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong bitmapPtr, jbyteArray buffer,
        jfloat left, jfloat top, jlong paintPtr) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapPtr);
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);

    // apply transform directly to canvas, so it affects shaders correctly
    renderer->save(SkCanvas::kMatrix_SaveFlag);
    renderer->translate(left, top);
    renderer->drawBitmap(bitmap, paint);
    renderer->restore();
}

static void android_view_GLES20Canvas_drawBitmapRect(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong bitmapPtr, jbyteArray buffer,
        float srcLeft, float srcTop, float srcRight, float srcBottom,
        float dstLeft, float dstTop, float dstRight, float dstBottom, jlong paintPtr) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapPtr);
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawBitmap(bitmap, srcLeft, srcTop, srcRight, srcBottom,
            dstLeft, dstTop, dstRight, dstBottom, paint);
}

static void android_view_GLES20Canvas_drawBitmapMatrix(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong bitmapPtr, jbyteArray buffer,
        jlong matrixPtr, jlong paintPtr) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapPtr);
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);

    // apply transform directly to canvas, so it affects shaders correctly
    renderer->save(SkCanvas::kMatrix_SaveFlag);
    renderer->concatMatrix(*matrix);
    renderer->drawBitmap(bitmap, paint);
    renderer->restore();
}

static void android_view_GLES20Canvas_drawBitmapData(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jintArray colors, jint offset, jint stride,
        jfloat left, jfloat top, jint width, jint height, jboolean hasAlpha, jlong paintPtr) {
    // Note: If hasAlpha is false, kRGB_565_SkColorType will be used, which will
    // correct the alphaType to kOpaque_SkAlphaType.
    const SkImageInfo info = SkImageInfo::Make(width, height,
                               hasAlpha ? kN32_SkColorType : kRGB_565_SkColorType,
                               kPremul_SkAlphaType);
    SkBitmap* bitmap = new SkBitmap;
    if (!bitmap->allocPixels(info)) {
        delete bitmap;
        return;
    }

    if (!GraphicsJNI::SetPixels(env, colors, offset, stride, 0, 0, width, height, *bitmap)) {
        delete bitmap;
        return;
    }

    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);

    // apply transform directly to canvas, so it affects shaders correctly
    renderer->save(SkCanvas::kMatrix_SaveFlag);
    renderer->translate(left, top);
    renderer->drawBitmapData(bitmap, paint);
    renderer->restore();

    // Note - bitmap isn't deleted as DisplayListRenderer owns it now
}

static void android_view_GLES20Canvas_drawBitmapMesh(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong bitmapPtr, jbyteArray buffer,
        jint meshWidth, jint meshHeight, jfloatArray vertices, jint offset, jintArray colors,
        jint colorOffset, jlong paintPtr) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapPtr);
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    jfloat* verticesArray = vertices ? env->GetFloatArrayElements(vertices, NULL) + offset : NULL;
    jint* colorsArray = colors ? env->GetIntArrayElements(colors, NULL) + colorOffset : NULL;

    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawBitmapMesh(bitmap, meshWidth, meshHeight, verticesArray, colorsArray, paint);

    if (vertices) env->ReleaseFloatArrayElements(vertices, verticesArray, 0);
    if (colors) env->ReleaseIntArrayElements(colors, colorsArray, 0);
}

static void android_view_GLES20Canvas_drawPatch(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong bitmapPtr, jbyteArray buffer, jlong patchPtr,
        float left, float top, float right, float bottom, jlong paintPtr) {
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapPtr);
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Res_png_9patch* patch = reinterpret_cast<Res_png_9patch*>(patchPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawPatch(bitmap, patch, left, top, right, bottom, paint);
}

static void android_view_GLES20Canvas_drawColor(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint color, jint modeHandle) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    SkPorterDuff::Mode mode = static_cast<SkPorterDuff::Mode>(modeHandle);
    renderer->drawColor(color, SkPorterDuff::ToXfermodeMode(mode));
}

static void android_view_GLES20Canvas_drawRect(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jlong paintPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawRect(left, top, right, bottom, paint);
}

static void android_view_GLES20Canvas_drawRoundRect(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jfloat rx, jfloat ry, jlong paintPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawRoundRect(left, top, right, bottom, rx, ry, paint);
}

static void android_view_GLES20Canvas_drawCircle(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat x, jfloat y, jfloat radius, jlong paintPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawCircle(x, y, radius, paint);
}

static void android_view_GLES20Canvas_drawCircleProps(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong xPropPtr, jlong yPropPtr, jlong radiusPropPtr, jlong paintPropPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    CanvasPropertyPrimitive* xProp = reinterpret_cast<CanvasPropertyPrimitive*>(xPropPtr);
    CanvasPropertyPrimitive* yProp = reinterpret_cast<CanvasPropertyPrimitive*>(yPropPtr);
    CanvasPropertyPrimitive* radiusProp = reinterpret_cast<CanvasPropertyPrimitive*>(radiusPropPtr);
    CanvasPropertyPaint* paintProp = reinterpret_cast<CanvasPropertyPaint*>(paintPropPtr);
    renderer->drawCircle(xProp, yProp, radiusProp, paintProp);
}

static void android_view_GLES20Canvas_drawOval(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jlong paintPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawOval(left, top, right, bottom, paint);
}

static void android_view_GLES20Canvas_drawArc(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jfloat startAngle, jfloat sweepAngle, jboolean useCenter, jlong paintPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawArc(left, top, right, bottom, startAngle, sweepAngle, useCenter, paint);
}

static void android_view_GLES20Canvas_drawRegionAsRects(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong regionPtr, jlong paintPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    SkRegion* region = reinterpret_cast<SkRegion*>(regionPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    if (paint->getStyle() != Paint::kFill_Style ||
            (paint->isAntiAlias() && !renderer->isCurrentTransformSimple())) {
        SkRegion::Iterator it(*region);
        while (!it.done()) {
            const SkIRect& r = it.rect();
            renderer->drawRect(r.fLeft, r.fTop, r.fRight, r.fBottom, paint);
            it.next();
        }
    } else {
        int count = 0;
        Vector<float> rects;
        SkRegion::Iterator it(*region);
        while (!it.done()) {
            const SkIRect& r = it.rect();
            rects.push(r.fLeft);
            rects.push(r.fTop);
            rects.push(r.fRight);
            rects.push(r.fBottom);
            count += 4;
            it.next();
        }
        renderer->drawRects(rects.array(), count, paint);
    }
}

static void android_view_GLES20Canvas_drawPoints(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloatArray points, jint offset, jint count, jlong paintPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    jfloat* storage = env->GetFloatArrayElements(points, NULL);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawPoints(storage + offset, count, paint);
    env->ReleaseFloatArrayElements(points, storage, 0);
}

static void android_view_GLES20Canvas_drawPath(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong pathPtr, jlong paintPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    SkPath* path = reinterpret_cast<SkPath*>(pathPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawPath(path, paint);
}

static void android_view_GLES20Canvas_drawLines(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jfloatArray points, jint offset, jint count, jlong paintPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    jfloat* storage = env->GetFloatArrayElements(points, NULL);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    renderer->drawLines(storage + offset, count, paint);
    env->ReleaseFloatArrayElements(points, storage, 0);
}

// ----------------------------------------------------------------------------
// Draw filters
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_setupPaintFilter(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jint clearBits, jint setBits) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->setupPaintFilter(clearBits, setBits);
}

static void android_view_GLES20Canvas_resetPaintFilter(JNIEnv* env, jobject clazz,
        jlong rendererPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    renderer->resetPaintFilter();
}

// ----------------------------------------------------------------------------
// Text
// ----------------------------------------------------------------------------

class RenderTextFunctor {
public:
    RenderTextFunctor(const Layout& layout, DisplayListRenderer* renderer, jfloat x, jfloat y,
                Paint* paint, uint16_t* glyphs, float* pos, float totalAdvance,
                uirenderer::Rect& bounds)
            : layout(layout), renderer(renderer), x(x), y(y), paint(paint), glyphs(glyphs),
            pos(pos), totalAdvance(totalAdvance), bounds(bounds) { }
    void operator()(size_t start, size_t end) {
        for (size_t i = start; i < end; i++) {
            glyphs[i] = layout.getGlyphId(i);
            pos[2 * i] = layout.getX(i);
            pos[2 * i + 1] = layout.getY(i);
        }
        size_t glyphsCount = end - start;
        int bytesCount = glyphsCount * sizeof(jchar);
        renderer->drawText((const char*) (glyphs + start), bytesCount, glyphsCount,
            x, y, pos + 2 * start, paint, totalAdvance, bounds);
    }
private:
    const Layout& layout;
    DisplayListRenderer* renderer;
    jfloat x;
    jfloat y;
    Paint* paint;
    uint16_t* glyphs;
    float* pos;
    float totalAdvance;
    uirenderer::Rect& bounds;
};

static void renderTextLayout(DisplayListRenderer* renderer, Layout* layout,
    jfloat x, jfloat y, Paint* paint) {
    size_t nGlyphs = layout->nGlyphs();
    float* pos = new float[nGlyphs * 2];
    uint16_t* glyphs = new uint16_t[nGlyphs];
    MinikinRect b;
    layout->getBounds(&b);
    android::uirenderer::Rect bounds(b.mLeft, b.mTop, b.mRight, b.mBottom);
    bounds.translate(x, y);
    float totalAdvance = layout->getAdvance();

    RenderTextFunctor f(*layout, renderer, x, y, paint, glyphs, pos, totalAdvance, bounds);
    MinikinUtils::forFontRun(*layout, paint, f);
    delete[] glyphs;
    delete[] pos;
}

static void renderText(DisplayListRenderer* renderer, const jchar* text, int count,
        jfloat x, jfloat y, int bidiFlags, Paint* paint, TypefaceImpl* typeface) {
    Layout layout;
    MinikinUtils::doLayout(&layout, paint, bidiFlags, typeface, text, 0, count, count);
    x += MinikinUtils::xOffsetForTextAlign(paint, layout);
    renderTextLayout(renderer, &layout, x, y, paint);
}

class RenderTextOnPathFunctor {
public:
    RenderTextOnPathFunctor(const Layout& layout, DisplayListRenderer* renderer, float hOffset,
                float vOffset, Paint* paint, SkPath* path)
            : layout(layout), renderer(renderer), hOffset(hOffset), vOffset(vOffset),
                paint(paint), path(path) {
    }
    void operator()(size_t start, size_t end) {
        uint16_t glyphs[1];
        for (size_t i = start; i < end; i++) {
            glyphs[0] = layout.getGlyphId(i);
            float x = hOffset + layout.getX(i);
            float y = vOffset + layout.getY(i);
            renderer->drawTextOnPath((const char*) glyphs, sizeof(glyphs), 1, path, x, y, paint);
        }
    }
private:
    const Layout& layout;
    DisplayListRenderer* renderer;
    float hOffset;
    float vOffset;
    Paint* paint;
    SkPath* path;
};

static void renderTextOnPath(DisplayListRenderer* renderer, const jchar* text, int count,
        SkPath* path, jfloat hOffset, jfloat vOffset, int bidiFlags, Paint* paint,
        TypefaceImpl* typeface) {
    Layout layout;
    MinikinUtils::doLayout(&layout, paint, bidiFlags, typeface, text, 0, count, count);
    hOffset += MinikinUtils::hOffsetForTextAlign(paint, layout, *path);
    Paint::Align align = paint->getTextAlign();
    paint->setTextAlign(Paint::kLeft_Align);

    RenderTextOnPathFunctor f(layout, renderer, hOffset, vOffset, paint, path);
    MinikinUtils::forFontRun(layout, paint, f);
    paint->setTextAlign(align);
}

static void renderTextRun(DisplayListRenderer* renderer, const jchar* text,
        jint start, jint count, jint contextCount, jfloat x, jfloat y,
        int bidiFlags, Paint* paint, TypefaceImpl* typeface) {
    Layout layout;
    MinikinUtils::doLayout(&layout, paint, bidiFlags, typeface, text, start, count, contextCount);
    x += MinikinUtils::xOffsetForTextAlign(paint, layout);
    renderTextLayout(renderer, &layout, x, y, paint);
}

static void android_view_GLES20Canvas_drawTextArray(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jcharArray text, jint index, jint count,
        jfloat x, jfloat y, jint bidiFlags, jlong paintPtr, jlong typefacePtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefacePtr);

    renderText(renderer, textArray + index, count, x, y, bidiFlags, paint, typeface);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
}

static void android_view_GLES20Canvas_drawText(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jstring text, jint start, jint end,
        jfloat x, jfloat y, jint bidiFlags, jlong paintPtr, jlong typefacePtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    const jchar* textArray = env->GetStringChars(text, NULL);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefacePtr);

    renderText(renderer, textArray + start, end - start, x, y, bidiFlags, paint, typeface);
    env->ReleaseStringChars(text, textArray);
}

static void android_view_GLES20Canvas_drawTextArrayOnPath(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jcharArray text, jint index, jint count,
        jlong pathPtr, jfloat hOffset, jfloat vOffset, jint bidiFlags, jlong paintPtr,
        jlong typefacePtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    SkPath* path = reinterpret_cast<SkPath*>(pathPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefacePtr);

    renderTextOnPath(renderer, textArray + index, count, path,
            hOffset, vOffset, bidiFlags, paint, typeface);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
}

static void android_view_GLES20Canvas_drawTextOnPath(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jstring text, jint start, jint end,
        jlong pathPtr, jfloat hOffset, jfloat vOffset, jint bidiFlags, jlong paintPtr,
        jlong typefacePtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    const jchar* textArray = env->GetStringChars(text, NULL);
    SkPath* path = reinterpret_cast<SkPath*>(pathPtr);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefacePtr);

    renderTextOnPath(renderer, textArray + start, end - start, path,
            hOffset, vOffset, bidiFlags, paint, typeface);
    env->ReleaseStringChars(text, textArray);
}

static void android_view_GLES20Canvas_drawTextRunArray(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jcharArray text, jint index, jint count,
        jint contextIndex, jint contextCount, jfloat x, jfloat y, jboolean isRtl,
        jlong paintPtr, jlong typefacePtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefacePtr);

    int bidiFlags = isRtl ? kBidi_Force_RTL : kBidi_Force_LTR;
    renderTextRun(renderer, textArray + contextIndex, index - contextIndex,
            count, contextCount, x, y, bidiFlags, paint, typeface);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
 }

static void android_view_GLES20Canvas_drawTextRun(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jstring text, jint start, jint end,
        jint contextStart, int contextEnd, jfloat x, jfloat y, jboolean isRtl,
        jlong paintPtr, jlong typefacePtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    const jchar* textArray = env->GetStringChars(text, NULL);
    jint count = end - start;
    jint contextCount = contextEnd - contextStart;
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    TypefaceImpl* typeface = reinterpret_cast<TypefaceImpl*>(typefacePtr);

    int bidiFlags = isRtl ? kBidi_Force_RTL : kBidi_Force_LTR;
    renderTextRun(renderer, textArray + contextStart, start - contextStart,
            count, contextCount, x, y, bidiFlags, paint, typeface);
    env->ReleaseStringChars(text, textArray);
}

// ----------------------------------------------------------------------------
// Display lists
// ----------------------------------------------------------------------------

static jlong android_view_GLES20Canvas_finishRecording(JNIEnv* env,
        jobject clazz, jlong rendererPtr) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    return reinterpret_cast<jlong>(renderer->finishRecording());
}

static jlong android_view_GLES20Canvas_createDisplayListRenderer(JNIEnv* env, jobject clazz) {
    return reinterpret_cast<jlong>(new DisplayListRenderer);
}

static jint android_view_GLES20Canvas_drawRenderNode(JNIEnv* env,
        jobject clazz, jlong rendererPtr, jlong renderNodePtr,
        jobject dirty, jint flags) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    android::uirenderer::Rect bounds;
    status_t status = renderer->drawRenderNode(renderNode, bounds, flags);
    if (status != DrawGlInfo::kStatusDone && dirty != NULL) {
        env->CallVoidMethod(dirty, gRectClassInfo.set,
                int(bounds.left), int(bounds.top), int(bounds.right), int(bounds.bottom));
    }
    return status;
}

// ----------------------------------------------------------------------------
// Layers
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_drawLayer(JNIEnv* env, jobject clazz,
        jlong rendererPtr, jlong layerPtr, jfloat x, jfloat y) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererPtr);
    Layer* layer = reinterpret_cast<Layer*>(layerPtr);
    renderer->drawLayer(layer, x, y);
}

#endif // USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// Common
// ----------------------------------------------------------------------------

static jboolean android_view_GLES20Canvas_isAvailable(JNIEnv* env, jobject clazz) {
#ifdef USE_OPENGL_RENDERER
    char prop[PROPERTY_VALUE_MAX];
    if (property_get("ro.kernel.qemu", prop, NULL) == 0) {
        // not in the emulator
        return JNI_TRUE;
    }
    // In the emulator this property will be set to 1 when hardware GLES is
    // enabled, 0 otherwise. On old emulator versions it will be undefined.
    property_get("ro.kernel.qemu.gles", prop, "0");
    return atoi(prop) == 1 ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

// ----------------------------------------------------------------------------
// Logging
// ----------------------------------------------------------------------------

static void
android_app_ActivityThread_dumpGraphics(JNIEnv* env, jobject clazz, jobject javaFileDescriptor) {
#ifdef USE_OPENGL_RENDERER
    int fd = jniGetFDFromFileDescriptor(env, javaFileDescriptor);
    android::uirenderer::RenderNode::outputLogBuffer(fd);
#endif // USE_OPENGL_RENDERER
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/GLES20Canvas";

static JNINativeMethod gMethods[] = {
    { "nIsAvailable",       "()Z",             (void*) android_view_GLES20Canvas_isAvailable },

#ifdef USE_OPENGL_RENDERER

    { "nDestroyRenderer",   "(J)V",            (void*) android_view_GLES20Canvas_destroyRenderer },
    { "nSetViewport",       "(JII)V",          (void*) android_view_GLES20Canvas_setViewport },
    { "nSetHighContrastText","(JZ)V",          (void*) android_view_GLES20Canvas_setHighContrastText },
    { "nInsertReorderBarrier","(JZ)V",         (void*) android_view_GLES20Canvas_insertReorderBarrier },
    { "nPrepare",           "(JZ)I",           (void*) android_view_GLES20Canvas_prepare },
    { "nPrepareDirty",      "(JIIIIZ)I",       (void*) android_view_GLES20Canvas_prepareDirty },
    { "nFinish",            "(J)V",            (void*) android_view_GLES20Canvas_finish },
    { "nSetProperty",           "(Ljava/lang/String;Ljava/lang/String;)V",
            (void*) android_view_GLES20Canvas_setProperty },

    { "nCallDrawGLFunction", "(JJ)I",          (void*) android_view_GLES20Canvas_callDrawGLFunction },

    { "nSave",              "(JI)I",           (void*) android_view_GLES20Canvas_save },
    { "nRestore",           "(J)V",            (void*) android_view_GLES20Canvas_restore },
    { "nRestoreToCount",    "(JI)V",           (void*) android_view_GLES20Canvas_restoreToCount },
    { "nGetSaveCount",      "(J)I",            (void*) android_view_GLES20Canvas_getSaveCount },

    { "nSaveLayer",         "(JFFFFJI)I",      (void*) android_view_GLES20Canvas_saveLayer },
    { "nSaveLayer",         "(JJI)I",          (void*) android_view_GLES20Canvas_saveLayerClip },
    { "nSaveLayerAlpha",    "(JFFFFII)I",      (void*) android_view_GLES20Canvas_saveLayerAlpha },
    { "nSaveLayerAlpha",    "(JII)I",          (void*) android_view_GLES20Canvas_saveLayerAlphaClip },

    { "nQuickReject",       "(JFFFF)Z",        (void*) android_view_GLES20Canvas_quickReject },
    { "nClipRect",          "(JFFFFI)Z",       (void*) android_view_GLES20Canvas_clipRectF },
    { "nClipRect",          "(JIIIII)Z",       (void*) android_view_GLES20Canvas_clipRect },
    { "nClipPath",          "(JJI)Z",          (void*) android_view_GLES20Canvas_clipPath },
    { "nClipRegion",        "(JJI)Z",          (void*) android_view_GLES20Canvas_clipRegion },

    { "nTranslate",         "(JFF)V",          (void*) android_view_GLES20Canvas_translate },
    { "nRotate",            "(JF)V",           (void*) android_view_GLES20Canvas_rotate },
    { "nScale",             "(JFF)V",          (void*) android_view_GLES20Canvas_scale },
    { "nSkew",              "(JFF)V",          (void*) android_view_GLES20Canvas_skew },

    { "nSetMatrix",         "(JJ)V",           (void*) android_view_GLES20Canvas_setMatrix },
    { "nGetMatrix",         "(JJ)V",           (void*) android_view_GLES20Canvas_getMatrix },
    { "nConcatMatrix",      "(JJ)V",           (void*) android_view_GLES20Canvas_concatMatrix },

    { "nDrawBitmap",        "(JJ[BFFJ)V",      (void*) android_view_GLES20Canvas_drawBitmap },
    { "nDrawBitmap",        "(JJ[BFFFFFFFFJ)V",(void*) android_view_GLES20Canvas_drawBitmapRect },
    { "nDrawBitmap",        "(JJ[BJJ)V",       (void*) android_view_GLES20Canvas_drawBitmapMatrix },
    { "nDrawBitmap",        "(J[IIIFFIIZJ)V",  (void*) android_view_GLES20Canvas_drawBitmapData },

    { "nDrawBitmapMesh",    "(JJ[BII[FI[IIJ)V",(void*) android_view_GLES20Canvas_drawBitmapMesh },

    { "nDrawPatch",         "(JJ[BJFFFFJ)V",   (void*) android_view_GLES20Canvas_drawPatch },

    { "nDrawColor",         "(JII)V",          (void*) android_view_GLES20Canvas_drawColor },
    { "nDrawRect",          "(JFFFFJ)V",       (void*) android_view_GLES20Canvas_drawRect },
    { "nDrawRects",         "(JJJ)V",          (void*) android_view_GLES20Canvas_drawRegionAsRects },
    { "nDrawRoundRect",     "(JFFFFFFJ)V",     (void*) android_view_GLES20Canvas_drawRoundRect },
    { "nDrawCircle",        "(JFFFJ)V",        (void*) android_view_GLES20Canvas_drawCircle },
    { "nDrawCircle",        "(JJJJJ)V",        (void*) android_view_GLES20Canvas_drawCircleProps },
    { "nDrawOval",          "(JFFFFJ)V",       (void*) android_view_GLES20Canvas_drawOval },
    { "nDrawArc",           "(JFFFFFFZJ)V",    (void*) android_view_GLES20Canvas_drawArc },
    { "nDrawPoints",        "(J[FIIJ)V",       (void*) android_view_GLES20Canvas_drawPoints },

    { "nDrawPath",          "(JJJ)V",          (void*) android_view_GLES20Canvas_drawPath },
    { "nDrawLines",         "(J[FIIJ)V",       (void*) android_view_GLES20Canvas_drawLines },

    { "nSetupPaintFilter",  "(JII)V",          (void*) android_view_GLES20Canvas_setupPaintFilter },
    { "nResetPaintFilter",  "(J)V",            (void*) android_view_GLES20Canvas_resetPaintFilter },

    { "nDrawText",          "(J[CIIFFIJJ)V",   (void*) android_view_GLES20Canvas_drawTextArray },
    { "nDrawText",          "(JLjava/lang/String;IIFFIJJ)V",
            (void*) android_view_GLES20Canvas_drawText },

    { "nDrawTextOnPath",    "(J[CIIJFFIJJ)V",  (void*) android_view_GLES20Canvas_drawTextArrayOnPath },
    { "nDrawTextOnPath",    "(JLjava/lang/String;IIJFFIJJ)V",
            (void*) android_view_GLES20Canvas_drawTextOnPath },

    { "nDrawTextRun",       "(J[CIIIIFFZJJ)V",  (void*) android_view_GLES20Canvas_drawTextRunArray },
    { "nDrawTextRun",       "(JLjava/lang/String;IIIIFFZJJ)V",
            (void*) android_view_GLES20Canvas_drawTextRun },

    { "nGetClipBounds",     "(JLandroid/graphics/Rect;)Z", (void*) android_view_GLES20Canvas_getClipBounds },

    { "nFinishRecording",   "(J)J",      (void*) android_view_GLES20Canvas_finishRecording },
    { "nDrawRenderNode",    "(JJLandroid/graphics/Rect;I)I", (void*) android_view_GLES20Canvas_drawRenderNode },

    { "nCreateDisplayListRenderer", "()J",     (void*) android_view_GLES20Canvas_createDisplayListRenderer },

    { "nDrawLayer",              "(JJFF)V",    (void*) android_view_GLES20Canvas_drawLayer },

    { "nGetMaximumTextureWidth",  "()I",       (void*) android_view_GLES20Canvas_getMaxTextureWidth },
    { "nGetMaximumTextureHeight", "()I",       (void*) android_view_GLES20Canvas_getMaxTextureHeight },

#endif
};

static JNINativeMethod gActivityThreadMethods[] = {
    { "dumpGraphicsInfo",        "(Ljava/io/FileDescriptor;)V",
                                               (void*) android_app_ActivityThread_dumpGraphics }
};


#ifdef USE_OPENGL_RENDERER
    #define FIND_CLASS(var, className) \
            var = env->FindClass(className); \
            LOG_FATAL_IF(! var, "Unable to find class " className);

    #define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
            var = env->GetMethodID(clazz, methodName, methodDescriptor); \
            LOG_FATAL_IF(! var, "Unable to find method " methodName);
#else
    #define FIND_CLASS(var, className)
    #define GET_METHOD_ID(var, clazz, methodName, methodDescriptor)
#endif

int register_android_view_GLES20Canvas(JNIEnv* env) {
    jclass clazz;
    FIND_CLASS(clazz, "android/graphics/Rect");
    GET_METHOD_ID(gRectClassInfo.set, clazz, "set", "(IIII)V");

    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

const char* const kActivityThreadPathName = "android/app/ActivityThread";

int register_android_app_ActivityThread(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kActivityThreadPathName,
            gActivityThreadMethods, NELEM(gActivityThreadMethods));
}

};
