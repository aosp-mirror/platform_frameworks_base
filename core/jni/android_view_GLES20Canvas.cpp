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

#include "android_view_GraphicBuffer.h"

#include <android_runtime/AndroidRuntime.h>
#include <android_runtime/android_graphics_SurfaceTexture.h>

#include <androidfw/ResourceTypes.h>

#include <gui/GLConsumer.h>

#include <private/hwui/DrawGlInfo.h>

#include <cutils/properties.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkRegion.h>
#include <SkScalerContext.h>
#include <SkTemplates.h>
#include <SkXfermode.h>

#include <DisplayList.h>
#include <DisplayListRenderer.h>
#include <LayerRenderer.h>
#include <OpenGLRenderer.h>
#include <SkiaShader.h>
#include <SkiaColorFilter.h>
#include <Stencil.h>
#include <Rect.h>

#include <TextLayout.h>
#include <TextLayoutCache.h>

namespace android {

using namespace uirenderer;

/**
 * Note: OpenGLRenderer JNI layer is generated and compiled only on supported
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

#define MODIFIER_SHADOW 1
#define MODIFIER_SHADER 2
#define MODIFIER_COLOR_FILTER 4

// ----------------------------------------------------------------------------

static struct {
    jmethodID set;
} gRectClassInfo;

// ----------------------------------------------------------------------------
// Caching
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_flushCaches(JNIEnv* env, jobject clazz,
        jint mode) {
    if (Caches::hasInstance()) {
        Caches::getInstance().flush(static_cast<Caches::FlushMode>(mode));
    }
}

static jboolean android_view_GLES20Canvas_initCaches(JNIEnv* env, jobject clazz) {
    if (Caches::hasInstance()) {
        return Caches::getInstance().init() ? JNI_TRUE : JNI_FALSE;
    }
    return JNI_FALSE;
}

static void android_view_GLES20Canvas_terminateCaches(JNIEnv* env, jobject clazz) {
    if (Caches::hasInstance()) {
        Caches::getInstance().terminate();
    }
}

// ----------------------------------------------------------------------------
// Caching
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_initAtlas(JNIEnv* env, jobject clazz,
        jobject graphicBuffer, jintArray atlasMapArray, jint count) {

    sp<GraphicBuffer> buffer = graphicBufferForJavaObject(env, graphicBuffer);
    jint* atlasMap = env->GetIntArrayElements(atlasMapArray, NULL);

    Caches::getInstance().assetAtlas.init(buffer, atlasMap, count);

    env->ReleaseIntArrayElements(atlasMapArray, atlasMap, 0);
}

// ----------------------------------------------------------------------------
// Constructors
// ----------------------------------------------------------------------------

static jlong android_view_GLES20Canvas_createRenderer(JNIEnv* env, jobject clazz) {
    RENDERER_LOGD("Create OpenGLRenderer");
    OpenGLRenderer* renderer = new OpenGLRenderer();
    renderer->initProperties();
    return reinterpret_cast<jlong>(renderer);
}

static void android_view_GLES20Canvas_destroyRenderer(JNIEnv* env, jobject clazz,
        jlong rendererHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    RENDERER_LOGD("Destroy OpenGLRenderer");
    delete renderer;
}

// ----------------------------------------------------------------------------
// Setup
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_setViewport(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jint width, jint height) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->setViewport(width, height);
}

static jint android_view_GLES20Canvas_prepare(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jboolean opaque) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    return renderer->prepare(opaque);
}

static jint android_view_GLES20Canvas_prepareDirty(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jint left, jint top, jint right, jint bottom,
        jboolean opaque) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    return renderer->prepareDirty(left, top, right, bottom, opaque);
}

static void android_view_GLES20Canvas_finish(JNIEnv* env, jobject clazz,
        jlong rendererHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->finish();
}

static jint android_view_GLES20Canvas_getStencilSize(JNIEnv* env, jobject clazz) {
    return Stencil::getStencilSize();
}

static void android_view_GLES20Canvas_setName(JNIEnv* env,
        jobject clazz, jlong rendererHandle, jstring name) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    if (name != NULL) {
        const char* textArray = env->GetStringUTFChars(name, NULL);
        renderer->setName(textArray);
        env->ReleaseStringUTFChars(name, textArray);
    } else {
        renderer->setName(NULL);
    }
}

static void android_view_GLES20Canvas_setCountOverdrawEnabled(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jboolean enabled) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->setCountOverdrawEnabled(enabled);
}

static jfloat android_view_GLES20Canvas_getOverdraw(JNIEnv* env, jobject clazz,
        jlong rendererHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    return renderer->getOverdraw();
}

// ----------------------------------------------------------------------------
// Functor
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_callDrawGLFunction(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong functorHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    Functor* functor = reinterpret_cast<Functor*>(functorHandle);
    android::uirenderer::Rect dirty;
    return renderer->callDrawGLFunction(functor, dirty);
}

static void android_view_GLES20Canvas_detachFunctor(JNIEnv* env,
        jobject clazz, jlong rendererPtr, jlong functorPtr) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererPtr);
    Functor* functor = reinterpret_cast<Functor*>(functorPtr);
    renderer->detachFunctor(functor);
}

static void android_view_GLES20Canvas_attachFunctor(JNIEnv* env,
        jobject clazz, jlong rendererHandle, jlong functorHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    Functor* functor = reinterpret_cast<Functor*>(functorHandle);
    renderer->attachFunctor(functor);
}

static jint android_view_GLES20Canvas_invokeFunctors(JNIEnv* env,
        jobject clazz, jlong rendererHandle, jobject dirty) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    android::uirenderer::Rect bounds;
    status_t status = renderer->invokeFunctors(bounds);
    if (status != DrawGlInfo::kStatusDone && dirty != NULL) {
        env->CallVoidMethod(dirty, gRectClassInfo.set,
                int(bounds.left), int(bounds.top), int(bounds.right), int(bounds.bottom));
    }
    return status;
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

static jint android_view_GLES20Canvas_save(JNIEnv* env, jobject clazz, jlong rendererHandle,
        jint flags) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    return renderer->save(flags);
}

static jint android_view_GLES20Canvas_getSaveCount(JNIEnv* env, jobject clazz,
        jlong rendererHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    return renderer->getSaveCount();
}

static void android_view_GLES20Canvas_restore(JNIEnv* env, jobject clazz,
        jlong rendererHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->restore();
}

static void android_view_GLES20Canvas_restoreToCount(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jint saveCount) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->restoreToCount(saveCount);
}

// ----------------------------------------------------------------------------
// Layers
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_saveLayer(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jlong paintHandle, jint saveFlags) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    return renderer->saveLayer(left, top, right, bottom, paint, saveFlags);
}

static jint android_view_GLES20Canvas_saveLayerClip(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong paintHandle, jint saveFlags) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    const android::uirenderer::Rect& bounds(renderer->getClipBounds());
    return renderer->saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom,
            paint, saveFlags);
}

static jint android_view_GLES20Canvas_saveLayerAlpha(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jint alpha, jint saveFlags) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    return renderer->saveLayerAlpha(left, top, right, bottom, alpha, saveFlags);
}

static jint android_view_GLES20Canvas_saveLayerAlphaClip(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jint alpha, jint saveFlags) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    const android::uirenderer::Rect& bounds(renderer->getClipBounds());
    return renderer->saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom,
            alpha, saveFlags);
}

// ----------------------------------------------------------------------------
// Clipping
// ----------------------------------------------------------------------------

static jboolean android_view_GLES20Canvas_quickReject(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat left, jfloat top, jfloat right, jfloat bottom) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    bool result = renderer->quickRejectNoScissor(left, top, right, bottom);
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_view_GLES20Canvas_clipRectF(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jint opHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkRegion::Op op = static_cast<SkRegion::Op>(opHandle);
    bool result;
    result = renderer->clipRect(left, top, right, bottom, op);
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_view_GLES20Canvas_clipRect(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jint left, jint top, jint right, jint bottom,
        jint opHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkRegion::Op op = static_cast<SkRegion::Op>(opHandle);
    bool result = renderer->clipRect(float(left), float(top), float(right), float(bottom), op);
    return result ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_view_GLES20Canvas_clipPath(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong pathHandle, jint opHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
    SkRegion::Op op = static_cast<SkRegion::Op>(opHandle);
    return renderer->clipPath(path, op) ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_view_GLES20Canvas_clipRegion(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong regionHandle, jint opHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    SkRegion::Op op = static_cast<SkRegion::Op>(opHandle);
    return renderer->clipRegion(region, op) ? JNI_TRUE : JNI_FALSE;
}

static jboolean android_view_GLES20Canvas_getClipBounds(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jobject rect) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    const android::uirenderer::Rect& bounds(renderer->getClipBounds());

    env->CallVoidMethod(rect, gRectClassInfo.set,
            int(bounds.left), int(bounds.top), int(bounds.right), int(bounds.bottom));

    return !bounds.isEmpty() ? JNI_TRUE : JNI_FALSE;
}

// ----------------------------------------------------------------------------
// Transforms
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_translate(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat dx, jfloat dy) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->translate(dx, dy);
}

static void android_view_GLES20Canvas_rotate(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat degrees) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->rotate(degrees);
}

static void android_view_GLES20Canvas_scale(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat sx, jfloat sy) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->scale(sx, sy);
}

static void android_view_GLES20Canvas_skew(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat sx, jfloat sy) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->skew(sx, sy);
}

static void android_view_GLES20Canvas_setMatrix(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong matrixHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    renderer->setMatrix(matrix);
}

static void android_view_GLES20Canvas_getMatrix(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong matrixHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    renderer->getMatrix(matrix);
}

static void android_view_GLES20Canvas_concatMatrix(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong matrixHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    renderer->concatMatrix(matrix);
}

// ----------------------------------------------------------------------------
// Drawing
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_drawBitmap(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong bitmapHandle, jbyteArray buffer,
        jfloat left, jfloat top, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    renderer->drawBitmap(bitmap, left, top, paint);
}

static void android_view_GLES20Canvas_drawBitmapRect(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong bitmapHandle, jbyteArray buffer,
        jfloat srcLeft, jfloat srcTop, jfloat srcRight, jfloat srcBottom,
        jfloat dstLeft, jfloat dstTop, jfloat dstRight, jfloat dstBottom, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    renderer->drawBitmap(bitmap, srcLeft, srcTop, srcRight, srcBottom,
            dstLeft, dstTop, dstRight, dstBottom, paint);
}

static void android_view_GLES20Canvas_drawBitmapMatrix(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong bitmapHandle, jbyteArray buffer,
        jlong matrixHandle, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    renderer->drawBitmap(bitmap, matrix, paint);
}

static void android_view_GLES20Canvas_drawBitmapData(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jintArray colors, jint offset, jint stride,
        jfloat left, jfloat top, jint width, jint height, jboolean hasAlpha, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    SkBitmap* bitmap = new SkBitmap;
    bitmap->setConfig(hasAlpha ? SkBitmap::kARGB_8888_Config : SkBitmap::kRGB_565_Config,
            width, height);

    if (!bitmap->allocPixels()) {
        delete bitmap;
        return;
    }

    if (!GraphicsJNI::SetPixels(env, colors, offset, stride, 0, 0, width, height, *bitmap, true)) {
        delete bitmap;
        return;
    }

    renderer->drawBitmapData(bitmap, left, top, paint);

    // If the renderer is a deferred renderer it will own the bitmap
    if (!renderer->isDeferred()) {
        delete bitmap;
    }
}

static void android_view_GLES20Canvas_drawBitmapMesh(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong bitmapHandle, jbyteArray buffer,
        jint meshWidth, jint meshHeight, jfloatArray vertices, jint offset, jintArray colors,
        jint colorOffset, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    jfloat* verticesArray = vertices ? env->GetFloatArrayElements(vertices, NULL) + offset : NULL;
    jint* colorsArray = colors ? env->GetIntArrayElements(colors, NULL) + colorOffset : NULL;

    renderer->drawBitmapMesh(bitmap, meshWidth, meshHeight, verticesArray, colorsArray, paint);

    if (vertices) env->ReleaseFloatArrayElements(vertices, verticesArray, 0);
    if (colors) env->ReleaseIntArrayElements(colors, colorsArray, 0);
}

static void android_view_GLES20Canvas_drawPatch(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong bitmapHandle, jbyteArray buffer, jlong patchHandle,
        jfloat left, jfloat top, jfloat right, jfloat bottom, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    Res_png_9patch* patch  = reinterpret_cast<Res_png_9patch*>(patchHandle);
    SkPaint* paint  = reinterpret_cast<SkPaint*>(paintHandle);

    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    renderer->drawPatch(bitmap, patch, left, top, right, bottom, paint);
}

static void android_view_GLES20Canvas_drawColor(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jint color, SkXfermode::Mode mode) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->drawColor(color, mode);
}

static void android_view_GLES20Canvas_drawRect(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    renderer->drawRect(left, top, right, bottom, paint);
}

static void android_view_GLES20Canvas_drawRoundRect(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jfloat rx, jfloat ry, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    renderer->drawRoundRect(left, top, right, bottom, rx, ry, paint);
}

static void android_view_GLES20Canvas_drawCircle(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat x, jfloat y, jfloat radius, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    renderer->drawCircle(x, y, radius, paint);
}

static void android_view_GLES20Canvas_drawOval(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    renderer->drawOval(left, top, right, bottom, paint);
}

static void android_view_GLES20Canvas_drawArc(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jfloat startAngle, jfloat sweepAngle, jboolean useCenter, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    renderer->drawArc(left, top, right, bottom, startAngle, sweepAngle, useCenter, paint);
}

static void android_view_GLES20Canvas_drawRegionAsRects(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong regionHandle, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkRegion* region = reinterpret_cast<SkRegion*>(regionHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);

    if (paint->getStyle() != SkPaint::kFill_Style ||
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

static void android_view_GLES20Canvas_drawRects(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloatArray rects, jint count, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    jfloat* storage = env->GetFloatArrayElements(rects, NULL);
    renderer->drawRects(storage, count, paint);
    env->ReleaseFloatArrayElements(rects, storage, 0);
}

static void android_view_GLES20Canvas_drawPoints(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloatArray points, jint offset, jint count, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    jfloat* storage = env->GetFloatArrayElements(points, NULL);
    renderer->drawPoints(storage + offset, count, paint);
    env->ReleaseFloatArrayElements(points, storage, 0);
}

static void android_view_GLES20Canvas_drawPath(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong pathHandle, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    renderer->drawPath(path, paint);
}

static void android_view_GLES20Canvas_drawLines(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloatArray points, jint offset, jint count, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    jfloat* storage = env->GetFloatArrayElements(points, NULL);
    renderer->drawLines(storage + offset, count, paint);
    env->ReleaseFloatArrayElements(points, storage, 0);
}

// ----------------------------------------------------------------------------
// Shaders and color filters
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_resetModifiers(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jint modifiers) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    if (modifiers & MODIFIER_SHADOW) renderer->resetShadow();
    if (modifiers & MODIFIER_SHADER) renderer->resetShader();
    if (modifiers & MODIFIER_COLOR_FILTER) renderer->resetColorFilter();
}

static void android_view_GLES20Canvas_setupShader(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong shaderHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkiaShader* shader = reinterpret_cast<SkiaShader*>(shaderHandle);
    renderer->setupShader(shader);
}

static void android_view_GLES20Canvas_setupColorFilter(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong filterHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkiaColorFilter* filter = reinterpret_cast<SkiaColorFilter*>(filterHandle);
    renderer->setupColorFilter(filter);
}

static void android_view_GLES20Canvas_setupShadow(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jfloat radius, jfloat dx, jfloat dy, jint color) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->setupShadow(radius, dx, dy, color);
}

// ----------------------------------------------------------------------------
// Draw filters
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_setupPaintFilter(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jint clearBits, jint setBits) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->setupPaintFilter(clearBits, setBits);
}

static void android_view_GLES20Canvas_resetPaintFilter(JNIEnv* env, jobject clazz,
        jlong rendererHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->resetPaintFilter();
}

// ----------------------------------------------------------------------------
// Text
// ----------------------------------------------------------------------------

static float xOffsetForTextAlign(SkPaint* paint, float totalAdvance) {
    switch (paint->getTextAlign()) {
        case SkPaint::kCenter_Align:
            return -totalAdvance / 2.0f;
            break;
        case SkPaint::kRight_Align:
            return -totalAdvance;
            break;
        default:
            break;
    }
    return 0;
}

static void renderText(OpenGLRenderer* renderer, const jchar* text, int count,
        jfloat x, jfloat y, int flags, SkPaint* paint) {
    sp<TextLayoutValue> value = TextLayoutEngine::getInstance().getValue(paint,
            text, 0, count, count, flags);
    if (value == NULL) {
        return;
    }
    const jchar* glyphs = value->getGlyphs();
    size_t glyphsCount = value->getGlyphsCount();
    jfloat totalAdvance = value->getTotalAdvance();
    x += xOffsetForTextAlign(paint, totalAdvance);
    const float* positions = value->getPos();
    int bytesCount = glyphsCount * sizeof(jchar);
    const SkRect& r = value->getBounds();
    android::uirenderer::Rect bounds(r.fLeft, r.fTop, r.fRight, r.fBottom);
    bounds.translate(x, y);

    renderer->drawText((const char*) glyphs, bytesCount, glyphsCount,
            x, y, positions, paint, totalAdvance, bounds);
}

static void renderTextOnPath(OpenGLRenderer* renderer, const jchar* text, int count,
        SkPath* path, jfloat hOffset, jfloat vOffset, int flags, SkPaint* paint) {
    sp<TextLayoutValue> value = TextLayoutEngine::getInstance().getValue(paint,
            text, 0, count, count, flags);
    if (value == NULL) {
        return;
    }
    const jchar* glyphs = value->getGlyphs();
    size_t glyphsCount = value->getGlyphsCount();
    int bytesCount = glyphsCount * sizeof(jchar);
    renderer->drawTextOnPath((const char*) glyphs, bytesCount, glyphsCount, path,
            hOffset, vOffset, paint);
}

static void renderTextRun(OpenGLRenderer* renderer, const jchar* text,
        jint start, jint count, jint contextCount, jfloat x, jfloat y,
        int flags, SkPaint* paint) {
    sp<TextLayoutValue> value = TextLayoutEngine::getInstance().getValue(paint,
            text, start, count, contextCount, flags);
    if (value == NULL) {
        return;
    }
    const jchar* glyphs = value->getGlyphs();
    size_t glyphsCount = value->getGlyphsCount();
    jfloat totalAdvance = value->getTotalAdvance();
    x += xOffsetForTextAlign(paint, totalAdvance);
    const float* positions = value->getPos();
    int bytesCount = glyphsCount * sizeof(jchar);
    const SkRect& r = value->getBounds();
    android::uirenderer::Rect bounds(r.fLeft, r.fTop, r.fRight, r.fBottom);
    bounds.translate(x, y);

    renderer->drawText((const char*) glyphs, bytesCount, glyphsCount,
            x, y, positions, paint, totalAdvance, bounds);
}

static void android_view_GLES20Canvas_drawTextArray(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jcharArray text, jint index, jint count,
        jfloat x, jfloat y, jint flags, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    renderText(renderer, textArray + index, count, x, y, flags, paint);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
}

static void android_view_GLES20Canvas_drawText(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jstring text, jint start, jint end,
        jfloat x, jfloat y, jint flags, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    const jchar* textArray = env->GetStringChars(text, NULL);
    renderText(renderer, textArray + start, end - start, x, y, flags, paint);
    env->ReleaseStringChars(text, textArray);
}

static void android_view_GLES20Canvas_drawTextArrayOnPath(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jcharArray text, jint index, jint count,
        jlong pathHandle, jfloat hOffset, jfloat vOffset, jint flags, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    renderTextOnPath(renderer, textArray + index, count, path,
            hOffset, vOffset, flags, paint);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
}

static void android_view_GLES20Canvas_drawTextOnPath(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jstring text, jint start, jint end,
        jlong pathHandle, jfloat hOffset, jfloat vOffset, jint flags, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPath* path = reinterpret_cast<SkPath*>(pathHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    const jchar* textArray = env->GetStringChars(text, NULL);
    renderTextOnPath(renderer, textArray + start, end - start, path,
            hOffset, vOffset, flags, paint);
    env->ReleaseStringChars(text, textArray);
}

static void android_view_GLES20Canvas_drawTextRunArray(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jcharArray text, jint index, jint count,
        jint contextIndex, jint contextCount, jfloat x, jfloat y, jint dirFlags,
        jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    renderTextRun(renderer, textArray + contextIndex, index - contextIndex,
            count, contextCount, x, y, dirFlags, paint);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
 }

static void android_view_GLES20Canvas_drawTextRun(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jstring text, jint start, jint end,
        jint contextStart, jint contextEnd, jfloat x, jfloat y, jint dirFlags,
        jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    const jchar* textArray = env->GetStringChars(text, NULL);
    jint count = end - start;
    jint contextCount = contextEnd - contextStart;
    renderTextRun(renderer, textArray + contextStart, start - contextStart,
            count, contextCount, x, y, dirFlags, paint);
    env->ReleaseStringChars(text, textArray);
}

static void renderPosText(OpenGLRenderer* renderer, const jchar* text, int count,
        const jfloat* positions, jint dirFlags, SkPaint* paint) {
    sp<TextLayoutValue> value = TextLayoutEngine::getInstance().getValue(paint,
            text, 0, count, count, dirFlags);
    if (value == NULL) {
        return;
    }
    const jchar* glyphs = value->getGlyphs();
    size_t glyphsCount = value->getGlyphsCount();
    if (count < int(glyphsCount)) glyphsCount = count;
    int bytesCount = glyphsCount * sizeof(jchar);

    renderer->drawPosText((const char*) glyphs, bytesCount, glyphsCount, positions, paint);
}

static void android_view_GLES20Canvas_drawPosTextArray(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jcharArray text, jint index, jint count,
        jfloatArray pos, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    jfloat* positions = env->GetFloatArrayElements(pos, NULL);

    renderPosText(renderer, textArray + index, count, positions, kBidi_LTR, paint);

    env->ReleaseFloatArrayElements(pos, positions, JNI_ABORT);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
}

static void android_view_GLES20Canvas_drawPosText(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jstring text, jint start, jint end,
        jfloatArray pos, jlong paintHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    const jchar* textArray = env->GetStringChars(text, NULL);
    jfloat* positions = env->GetFloatArrayElements(pos, NULL);

    renderPosText(renderer, textArray + start, end - start, positions, kBidi_LTR, paint);

    env->ReleaseFloatArrayElements(pos, positions, JNI_ABORT);
    env->ReleaseStringChars(text, textArray);
}

// ----------------------------------------------------------------------------
// Display lists
// ----------------------------------------------------------------------------

static jlong android_view_GLES20Canvas_getDisplayList(JNIEnv* env,
        jobject clazz, jlong rendererHandle, jlong displayListHandle) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererHandle);
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    DisplayList* list = renderer->getDisplayList(displayList);
    return reinterpret_cast<jlong>(list);
}

static jlong android_view_GLES20Canvas_createDisplayListRenderer(JNIEnv* env,
        jobject clazz) {
    OpenGLRenderer* renderer = new DisplayListRenderer;
    return reinterpret_cast<jlong>(renderer);
}

static void android_view_GLES20Canvas_resetDisplayListRenderer(JNIEnv* env,
        jobject clazz, jlong rendererHandle) {
    DisplayListRenderer* renderer = reinterpret_cast<DisplayListRenderer*>(rendererHandle);
    renderer->reset();
}

static jint android_view_GLES20Canvas_drawDisplayList(JNIEnv* env,
        jobject clazz, jlong rendererHandle, jlong displayListHandle,
        jobject dirty, jint flags) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    android::uirenderer::Rect bounds;
    status_t status = renderer->drawDisplayList(displayList, bounds, flags);
    if (status != DrawGlInfo::kStatusDone && dirty != NULL) {
        env->CallVoidMethod(dirty, gRectClassInfo.set,
                int(bounds.left), int(bounds.top), int(bounds.right), int(bounds.bottom));
    }
    return status;
}

static void android_view_GLES20Canvas_outputDisplayList(JNIEnv* env,
        jobject clazz, jlong rendererHandle, jlong displayListHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    renderer->outputDisplayList(displayList);
}

// ----------------------------------------------------------------------------
// Layers
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_interrupt(JNIEnv* env, jobject clazz,
        jlong rendererHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->interrupt();
}

static void android_view_GLES20Canvas_resume(JNIEnv* env, jobject clazz,
        jlong rendererHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->resume();
}

static jlong android_view_GLES20Canvas_createLayerRenderer(JNIEnv* env,
        jobject clazz, jlong layerHandle) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    if (layer) {
        OpenGLRenderer* renderer = new LayerRenderer(layer);
        renderer->initProperties();
        return reinterpret_cast<jlong>(renderer);
    }
    return NULL;
}

static jlong android_view_GLES20Canvas_createTextureLayer(JNIEnv* env, jobject clazz,
        jboolean isOpaque, jintArray layerInfo) {
    Layer* layer = LayerRenderer::createTextureLayer(isOpaque);

    if (layer) {
        jint* storage = env->GetIntArrayElements(layerInfo, NULL);
        storage[0] = layer->getTexture();
        env->ReleaseIntArrayElements(layerInfo, storage, 0);
    }

    return reinterpret_cast<jlong>(layer);
}

static jlong android_view_GLES20Canvas_createLayer(JNIEnv* env, jobject clazz,
        jint width, jint height, jboolean isOpaque, jintArray layerInfo) {
    Layer* layer = LayerRenderer::createLayer(width, height, isOpaque);

    if (layer) {
        jint* storage = env->GetIntArrayElements(layerInfo, NULL);
        storage[0] = layer->getWidth();
        storage[1] = layer->getHeight();
        env->ReleaseIntArrayElements(layerInfo, storage, 0);
    }

    return reinterpret_cast<jlong>(layer);
}

static jboolean android_view_GLES20Canvas_resizeLayer(JNIEnv* env, jobject clazz,
        jlong layerHandle, jint width, jint height, jintArray layerInfo) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    if (LayerRenderer::resizeLayer(layer, width, height)) {
        jint* storage = env->GetIntArrayElements(layerInfo, NULL);
        storage[0] = layer->getWidth();
        storage[1] = layer->getHeight();
        env->ReleaseIntArrayElements(layerInfo, storage, 0);
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static void android_view_GLES20Canvas_setLayerPaint(JNIEnv* env, jobject clazz,
        jlong layerHandle, jlong paintHandle) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    SkPaint* paint = reinterpret_cast<SkPaint*>(paintHandle);
    if (layer) {
        layer->setPaint(paint);
    }
}

static void android_view_GLES20Canvas_setLayerColorFilter(JNIEnv* env, jobject clazz,
        jlong layerHandle, jlong colorFilterHandle) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    SkiaColorFilter* colorFilter = reinterpret_cast<SkiaColorFilter*>(colorFilterHandle);
    if (layer) {
        layer->setColorFilter(colorFilter);
    }
}

static void android_view_GLES20Canvas_setOpaqueLayer(JNIEnv* env, jobject clazz,
        jlong layerHandle, jboolean isOpaque) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    if (layer) {
        layer->setBlend(!isOpaque);
    }
}

static void android_view_GLES20Canvas_updateTextureLayer(JNIEnv* env, jobject clazz,
        jlong layerHandle, jint width, jint height, jboolean isOpaque, jobject surface) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    float transform[16];
    sp<GLConsumer> surfaceTexture(SurfaceTexture_getSurfaceTexture(env, surface));

    if (surfaceTexture->updateTexImage() == NO_ERROR) {
        int64_t frameNumber = surfaceTexture->getFrameNumber();
        // If the GLConsumer queue is in synchronous mode, need to discard all
        // but latest frame, using the frame number to tell when we no longer
        // have newer frames to target. Since we can't tell which mode it is in,
        // do this unconditionally.
        int dropCounter = 0;
        while (surfaceTexture->updateTexImage() == NO_ERROR) {
            int64_t newFrameNumber = surfaceTexture->getFrameNumber();
            if (newFrameNumber == frameNumber) break;
            frameNumber = newFrameNumber;
            dropCounter++;
        }
        #if DEBUG_RENDERER
        if (dropCounter > 0) {
            RENDERER_LOGD("Dropped %d frames on texture layer update", dropCounter);
        }
        #endif
        surfaceTexture->getTransformMatrix(transform);
        GLenum renderTarget = surfaceTexture->getCurrentTextureTarget();

        LayerRenderer::updateTextureLayer(layer, width, height, isOpaque, renderTarget, transform);
    }
}

static void android_view_GLES20Canvas_updateRenderLayer(JNIEnv* env, jobject clazz,
        jlong layerHandle, jlong rendererHandle, jlong displayListHandle,
        jint left, jint top, jint right, jint bottom) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    layer->updateDeferred(renderer, displayList, left, top, right, bottom);
}

static void android_view_GLES20Canvas_clearLayerTexture(JNIEnv* env, jobject clazz,
        jlong layerHandle) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    layer->clearTexture();
}

static void android_view_GLES20Canvas_setTextureLayerTransform(JNIEnv* env, jobject clazz,
        jlong layerHandle, jlong matrixHandle) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    layer->getTransform().load(*matrix);
}

static void android_view_GLES20Canvas_destroyLayer(JNIEnv* env, jobject clazz, jlong layerHandle) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    LayerRenderer::destroyLayer(layer);
}

static void android_view_GLES20Canvas_destroyLayerDeferred(JNIEnv* env,
        jobject clazz, jlong layerHandle) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    LayerRenderer::destroyLayerDeferred(layer);
}

static void android_view_GLES20Canvas_drawLayer(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong layerHandle, jfloat x, jfloat y) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    renderer->drawLayer(layer, x, y);
}

static jboolean android_view_GLES20Canvas_copyLayer(JNIEnv* env, jobject clazz,
        jlong layerHandle, jlong bitmapHandle) {
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    SkBitmap* bitmap = reinterpret_cast<SkBitmap*>(bitmapHandle);
    return LayerRenderer::copyLayer(layer, bitmap);
}

static void android_view_GLES20Canvas_pushLayerUpdate(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong layerHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    renderer->pushLayerUpdate(layer);
}

static void android_view_GLES20Canvas_cancelLayerUpdate(JNIEnv* env, jobject clazz,
        jlong rendererHandle, jlong layerHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    Layer* layer = reinterpret_cast<Layer*>(layerHandle);
    renderer->cancelLayerUpdate(layer);
}

static void android_view_GLES20Canvas_clearLayerUpdates(JNIEnv* env, jobject clazz,
        jlong rendererHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->clearLayerUpdates();
}

static void android_view_GLES20Canvas_flushLayerUpdates(JNIEnv* env, jobject clazz,
        jlong rendererHandle) {
    OpenGLRenderer* renderer = reinterpret_cast<OpenGLRenderer*>(rendererHandle);
    renderer->flushLayerUpdates();
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
    android::uirenderer::DisplayList::outputLogBuffer(fd);
#endif // USE_OPENGL_RENDERER
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/GLES20Canvas";

static JNINativeMethod gMethods[] = {
    { "nIsAvailable",       "()Z",             (void*) android_view_GLES20Canvas_isAvailable },

#ifdef USE_OPENGL_RENDERER
    { "nFlushCaches",       "(I)V",            (void*) android_view_GLES20Canvas_flushCaches },
    { "nInitCaches",        "()Z",             (void*) android_view_GLES20Canvas_initCaches },
    { "nTerminateCaches",   "()V",             (void*) android_view_GLES20Canvas_terminateCaches },

    { "nInitAtlas",         "(Landroid/view/GraphicBuffer;[II)V",
            (void*) android_view_GLES20Canvas_initAtlas },

    { "nCreateRenderer",    "()J",             (void*) android_view_GLES20Canvas_createRenderer },
    { "nDestroyRenderer",   "(J)V",            (void*) android_view_GLES20Canvas_destroyRenderer },
    { "nSetViewport",       "(JII)V",          (void*) android_view_GLES20Canvas_setViewport },
    { "nPrepare",           "(JZ)I",           (void*) android_view_GLES20Canvas_prepare },
    { "nPrepareDirty",      "(JIIIIZ)I",       (void*) android_view_GLES20Canvas_prepareDirty },
    { "nFinish",            "(J)V",            (void*) android_view_GLES20Canvas_finish },
    { "nSetName",           "(JLjava/lang/String;)V",
            (void*) android_view_GLES20Canvas_setName },

    { "nSetCountOverdrawEnabled", "(JZ)V",     (void*) android_view_GLES20Canvas_setCountOverdrawEnabled },
    { "nGetOverdraw",             "(J)F",      (void*) android_view_GLES20Canvas_getOverdraw },

    { "nGetStencilSize",    "()I",             (void*) android_view_GLES20Canvas_getStencilSize },

    { "nCallDrawGLFunction", "(JJ)I",          (void*) android_view_GLES20Canvas_callDrawGLFunction },
    { "nDetachFunctor",      "(JJ)V",          (void*) android_view_GLES20Canvas_detachFunctor },
    { "nAttachFunctor",      "(JJ)V",          (void*) android_view_GLES20Canvas_attachFunctor },
    { "nInvokeFunctors",     "(JLandroid/graphics/Rect;)I",
            (void*) android_view_GLES20Canvas_invokeFunctors },

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
    { "nDrawRects",         "(J[FIJ)V",        (void*) android_view_GLES20Canvas_drawRects },
    { "nDrawRoundRect",     "(JFFFFFFJ)V",     (void*) android_view_GLES20Canvas_drawRoundRect },
    { "nDrawCircle",        "(JFFFJ)V",        (void*) android_view_GLES20Canvas_drawCircle },
    { "nDrawOval",          "(JFFFFJ)V",       (void*) android_view_GLES20Canvas_drawOval },
    { "nDrawArc",           "(JFFFFFFZJ)V",    (void*) android_view_GLES20Canvas_drawArc },
    { "nDrawPoints",        "(J[FIIJ)V",       (void*) android_view_GLES20Canvas_drawPoints },

    { "nDrawPath",          "(JJJ)V",          (void*) android_view_GLES20Canvas_drawPath },
    { "nDrawLines",         "(J[FIIJ)V",       (void*) android_view_GLES20Canvas_drawLines },

    { "nResetModifiers",    "(JI)V",           (void*) android_view_GLES20Canvas_resetModifiers },
    { "nSetupShader",       "(JJ)V",           (void*) android_view_GLES20Canvas_setupShader },
    { "nSetupColorFilter",  "(JJ)V",           (void*) android_view_GLES20Canvas_setupColorFilter },
    { "nSetupShadow",       "(JFFFI)V",        (void*) android_view_GLES20Canvas_setupShadow },

    { "nSetupPaintFilter",  "(JII)V",          (void*) android_view_GLES20Canvas_setupPaintFilter },
    { "nResetPaintFilter",  "(J)V",            (void*) android_view_GLES20Canvas_resetPaintFilter },

    { "nDrawText",          "(J[CIIFFIJ)V",    (void*) android_view_GLES20Canvas_drawTextArray },
    { "nDrawText",          "(JLjava/lang/String;IIFFIJ)V",
            (void*) android_view_GLES20Canvas_drawText },

    { "nDrawTextOnPath",    "(J[CIIJFFIJ)V",   (void*) android_view_GLES20Canvas_drawTextArrayOnPath },
    { "nDrawTextOnPath",    "(JLjava/lang/String;IIJFFIJ)V",
            (void*) android_view_GLES20Canvas_drawTextOnPath },

    { "nDrawTextRun",       "(J[CIIIIFFIJ)V",  (void*) android_view_GLES20Canvas_drawTextRunArray },
    { "nDrawTextRun",       "(JLjava/lang/String;IIIIFFIJ)V",
            (void*) android_view_GLES20Canvas_drawTextRun },

    { "nDrawPosText",       "(J[CII[FJ)V",     (void*) android_view_GLES20Canvas_drawPosTextArray },
    { "nDrawPosText",       "(JLjava/lang/String;II[FJ)V",
            (void*) android_view_GLES20Canvas_drawPosText },

    { "nGetClipBounds",     "(JLandroid/graphics/Rect;)Z",
            (void*) android_view_GLES20Canvas_getClipBounds },

    { "nGetDisplayList",         "(JJ)J",      (void*) android_view_GLES20Canvas_getDisplayList },
    { "nOutputDisplayList",      "(JJ)V",      (void*) android_view_GLES20Canvas_outputDisplayList },
    { "nDrawDisplayList",        "(JJLandroid/graphics/Rect;I)I",
            (void*) android_view_GLES20Canvas_drawDisplayList },

    { "nCreateDisplayListRenderer", "()J",     (void*) android_view_GLES20Canvas_createDisplayListRenderer },
    { "nResetDisplayListRenderer",  "(J)V",    (void*) android_view_GLES20Canvas_resetDisplayListRenderer },

    { "nInterrupt",              "(J)V",       (void*) android_view_GLES20Canvas_interrupt },
    { "nResume",                 "(J)V",       (void*) android_view_GLES20Canvas_resume },

    { "nCreateLayerRenderer",    "(J)J",       (void*) android_view_GLES20Canvas_createLayerRenderer },
    { "nCreateLayer",            "(IIZ[I)J",   (void*) android_view_GLES20Canvas_createLayer },
    { "nResizeLayer",            "(JII[I)Z" ,  (void*) android_view_GLES20Canvas_resizeLayer },
    { "nSetLayerPaint",          "(JJ)V",      (void*) android_view_GLES20Canvas_setLayerPaint },
    { "nSetLayerColorFilter",    "(JJ)V",      (void*) android_view_GLES20Canvas_setLayerColorFilter },
    { "nSetOpaqueLayer",         "(JZ)V",      (void*) android_view_GLES20Canvas_setOpaqueLayer },
    { "nCreateTextureLayer",     "(Z[I)J",     (void*) android_view_GLES20Canvas_createTextureLayer },
    { "nUpdateTextureLayer",     "(JIIZLandroid/graphics/SurfaceTexture;)V",
            (void*) android_view_GLES20Canvas_updateTextureLayer },
    { "nUpdateRenderLayer",      "(JJJIIII)V", (void*) android_view_GLES20Canvas_updateRenderLayer },
    { "nClearLayerTexture",      "(J)V",       (void*) android_view_GLES20Canvas_clearLayerTexture },
    { "nDestroyLayer",           "(J)V",       (void*) android_view_GLES20Canvas_destroyLayer },
    { "nDestroyLayerDeferred",   "(J)V",       (void*) android_view_GLES20Canvas_destroyLayerDeferred },
    { "nDrawLayer",              "(JJFF)V",    (void*) android_view_GLES20Canvas_drawLayer },
    { "nCopyLayer",              "(JJ)Z",      (void*) android_view_GLES20Canvas_copyLayer },
    { "nClearLayerUpdates",      "(J)V",       (void*) android_view_GLES20Canvas_clearLayerUpdates },
    { "nFlushLayerUpdates",      "(J)V",       (void*) android_view_GLES20Canvas_flushLayerUpdates },
    { "nPushLayerUpdate",        "(JJ)V",      (void*) android_view_GLES20Canvas_pushLayerUpdate },
    { "nCancelLayerUpdate",      "(JJ)V",      (void*) android_view_GLES20Canvas_cancelLayerUpdate },

    { "nSetTextureLayerTransform", "(JJ)V",    (void*) android_view_GLES20Canvas_setTextureLayerTransform },

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
