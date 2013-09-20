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
        Caches::FlushMode mode) {
    if (Caches::hasInstance()) {
        Caches::getInstance().flush(mode);
    }
}

static bool android_view_GLES20Canvas_initCaches(JNIEnv* env, jobject clazz) {
    if (Caches::hasInstance()) {
        return Caches::getInstance().init();
    }
    return false;
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

static OpenGLRenderer* android_view_GLES20Canvas_createRenderer(JNIEnv* env, jobject clazz) {
    RENDERER_LOGD("Create OpenGLRenderer");
    OpenGLRenderer* renderer = new OpenGLRenderer();
    renderer->initProperties();
    return renderer;
}

static void android_view_GLES20Canvas_destroyRenderer(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer) {
    RENDERER_LOGD("Destroy OpenGLRenderer");
    delete renderer;
}

// ----------------------------------------------------------------------------
// Setup
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_setViewport(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jint width, jint height) {
    renderer->setViewport(width, height);
}

static int android_view_GLES20Canvas_prepare(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jboolean opaque) {
    return renderer->prepare(opaque);
}

static int android_view_GLES20Canvas_prepareDirty(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jint left, jint top, jint right, jint bottom,
        jboolean opaque) {
    return renderer->prepareDirty(left, top, right, bottom, opaque);
}

static void android_view_GLES20Canvas_finish(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer) {
    renderer->finish();
}

static jint android_view_GLES20Canvas_getStencilSize(JNIEnv* env, jobject clazz) {
    return Stencil::getStencilSize();
}

static void android_view_GLES20Canvas_setName(JNIEnv* env,
        jobject clazz, OpenGLRenderer* renderer, jstring name) {
    if (name != NULL) {
        const char* textArray = env->GetStringUTFChars(name, NULL);
        renderer->setName(textArray);
        env->ReleaseStringUTFChars(name, textArray);
    } else {
        renderer->setName(NULL);
    }
}

static void android_view_GLES20Canvas_setCountOverdrawEnabled(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jboolean enabled) {
    renderer->setCountOverdrawEnabled(enabled);
}

static jfloat android_view_GLES20Canvas_getOverdraw(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer) {
    return renderer->getOverdraw();
}

// ----------------------------------------------------------------------------
// Functor
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_callDrawGLFunction(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, Functor* functor) {
    android::uirenderer::Rect dirty;
    return renderer->callDrawGLFunction(functor, dirty);
}

static void android_view_GLES20Canvas_detachFunctor(JNIEnv* env,
        jobject clazz, OpenGLRenderer* renderer, Functor* functor) {
    renderer->detachFunctor(functor);
}

static void android_view_GLES20Canvas_attachFunctor(JNIEnv* env,
        jobject clazz, OpenGLRenderer* renderer, Functor* functor) {
    renderer->attachFunctor(functor);
}

static jint android_view_GLES20Canvas_invokeFunctors(JNIEnv* env,
        jobject clazz, OpenGLRenderer* renderer, jobject dirty) {
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

static jint android_view_GLES20Canvas_save(JNIEnv* env, jobject clazz, OpenGLRenderer* renderer,
        jint flags) {
    return renderer->save(flags);
}

static jint android_view_GLES20Canvas_getSaveCount(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer) {
    return renderer->getSaveCount();
}

static void android_view_GLES20Canvas_restore(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer) {
    renderer->restore();
}

static void android_view_GLES20Canvas_restoreToCount(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jint saveCount) {
    renderer->restoreToCount(saveCount);
}

// ----------------------------------------------------------------------------
// Layers
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_saveLayer(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        SkPaint* paint, jint saveFlags) {
    return renderer->saveLayer(left, top, right, bottom, paint, saveFlags);
}

static jint android_view_GLES20Canvas_saveLayerClip(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkPaint* paint, jint saveFlags) {
    const android::uirenderer::Rect& bounds(renderer->getClipBounds());
    return renderer->saveLayer(bounds.left, bounds.top, bounds.right, bounds.bottom,
            paint, saveFlags);
}

static jint android_view_GLES20Canvas_saveLayerAlpha(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jint alpha, jint saveFlags) {
    return renderer->saveLayerAlpha(left, top, right, bottom, alpha, saveFlags);
}

static jint android_view_GLES20Canvas_saveLayerAlphaClip(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jint alpha, jint saveFlags) {
    const android::uirenderer::Rect& bounds(renderer->getClipBounds());
    return renderer->saveLayerAlpha(bounds.left, bounds.top, bounds.right, bounds.bottom,
            alpha, saveFlags);
}

// ----------------------------------------------------------------------------
// Clipping
// ----------------------------------------------------------------------------

static bool android_view_GLES20Canvas_quickReject(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom) {
    return renderer->quickRejectNoScissor(left, top, right, bottom);
}

static bool android_view_GLES20Canvas_clipRectF(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        SkRegion::Op op) {
    return renderer->clipRect(left, top, right, bottom, op);
}

static bool android_view_GLES20Canvas_clipRect(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jint left, jint top, jint right, jint bottom,
        SkRegion::Op op) {
    return renderer->clipRect(float(left), float(top), float(right), float(bottom), op);
}

static bool android_view_GLES20Canvas_clipPath(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkPath* path, SkRegion::Op op) {
    return renderer->clipPath(path, op);
}

static bool android_view_GLES20Canvas_clipRegion(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkRegion* region, SkRegion::Op op) {
    return renderer->clipRegion(region, op);
}

static bool android_view_GLES20Canvas_getClipBounds(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jobject rect) {
    const android::uirenderer::Rect& bounds(renderer->getClipBounds());

    env->CallVoidMethod(rect, gRectClassInfo.set,
            int(bounds.left), int(bounds.top), int(bounds.right), int(bounds.bottom));

    return !bounds.isEmpty();
}

// ----------------------------------------------------------------------------
// Transforms
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_translate(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat dx, jfloat dy) {
    renderer->translate(dx, dy);
}

static void android_view_GLES20Canvas_rotate(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat degrees) {
    renderer->rotate(degrees);
}

static void android_view_GLES20Canvas_scale(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat sx, jfloat sy) {
    renderer->scale(sx, sy);
}

static void android_view_GLES20Canvas_skew(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat sx, jfloat sy) {
    renderer->skew(sx, sy);
}

static void android_view_GLES20Canvas_setMatrix(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkMatrix* matrix) {
    renderer->setMatrix(matrix);
}

static void android_view_GLES20Canvas_getMatrix(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkMatrix* matrix) {
    renderer->getMatrix(matrix);
}

static void android_view_GLES20Canvas_concatMatrix(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkMatrix* matrix) {
    renderer->concatMatrix(matrix);
}

// ----------------------------------------------------------------------------
// Drawing
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_drawBitmap(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkBitmap* bitmap, jbyteArray buffer,
        jfloat left, jfloat top, SkPaint* paint) {
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    renderer->drawBitmap(bitmap, left, top, paint);
}

static void android_view_GLES20Canvas_drawBitmapRect(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkBitmap* bitmap, jbyteArray buffer,
        float srcLeft, float srcTop, float srcRight, float srcBottom,
        float dstLeft, float dstTop, float dstRight, float dstBottom, SkPaint* paint) {
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    renderer->drawBitmap(bitmap, srcLeft, srcTop, srcRight, srcBottom,
            dstLeft, dstTop, dstRight, dstBottom, paint);
}

static void android_view_GLES20Canvas_drawBitmapMatrix(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkBitmap* bitmap, jbyteArray buffer,
        SkMatrix* matrix, SkPaint* paint) {
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    renderer->drawBitmap(bitmap, matrix, paint);
}

static void android_view_GLES20Canvas_drawBitmapData(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jintArray colors, jint offset, jint stride,
        jfloat left, jfloat top, jint width, jint height, jboolean hasAlpha, SkPaint* paint) {
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
        OpenGLRenderer* renderer, SkBitmap* bitmap, jbyteArray buffer,
        jint meshWidth, jint meshHeight, jfloatArray vertices, jint offset, jintArray colors,
        jint colorOffset, SkPaint* paint) {
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    jfloat* verticesArray = vertices ? env->GetFloatArrayElements(vertices, NULL) + offset : NULL;
    jint* colorsArray = colors ? env->GetIntArrayElements(colors, NULL) + colorOffset : NULL;

    renderer->drawBitmapMesh(bitmap, meshWidth, meshHeight, verticesArray, colorsArray, paint);

    if (vertices) env->ReleaseFloatArrayElements(vertices, verticesArray, 0);
    if (colors) env->ReleaseIntArrayElements(colors, colorsArray, 0);
}

static void android_view_GLES20Canvas_drawPatch(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkBitmap* bitmap, jbyteArray buffer, Res_png_9patch* patch,
        float left, float top, float right, float bottom, SkPaint* paint) {
    // This object allows the renderer to allocate a global JNI ref to the buffer object.
    JavaHeapBitmapRef bitmapRef(env, bitmap, buffer);

    renderer->drawPatch(bitmap, patch, left, top, right, bottom, paint);
}

static void android_view_GLES20Canvas_drawColor(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jint color, SkXfermode::Mode mode) {
    renderer->drawColor(color, mode);
}

static void android_view_GLES20Canvas_drawRect(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        SkPaint* paint) {
    renderer->drawRect(left, top, right, bottom, paint);
}

static void android_view_GLES20Canvas_drawRoundRect(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jfloat rx, jfloat ry, SkPaint* paint) {
    renderer->drawRoundRect(left, top, right, bottom, rx, ry, paint);
}

static void android_view_GLES20Canvas_drawCircle(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat x, jfloat y, jfloat radius, SkPaint* paint) {
    renderer->drawCircle(x, y, radius, paint);
}

static void android_view_GLES20Canvas_drawOval(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        SkPaint* paint) {
    renderer->drawOval(left, top, right, bottom, paint);
}

static void android_view_GLES20Canvas_drawArc(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jfloat startAngle, jfloat sweepAngle, jboolean useCenter, SkPaint* paint) {
    renderer->drawArc(left, top, right, bottom, startAngle, sweepAngle, useCenter, paint);
}

static void android_view_GLES20Canvas_drawRegionAsRects(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkRegion* region, SkPaint* paint) {
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
        OpenGLRenderer* renderer, jfloatArray rects, jint count, SkPaint* paint) {
    jfloat* storage = env->GetFloatArrayElements(rects, NULL);
    renderer->drawRects(storage, count, paint);
    env->ReleaseFloatArrayElements(rects, storage, 0);
}

static void android_view_GLES20Canvas_drawPoints(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloatArray points, jint offset, jint count, SkPaint* paint) {
    jfloat* storage = env->GetFloatArrayElements(points, NULL);
    renderer->drawPoints(storage + offset, count, paint);
    env->ReleaseFloatArrayElements(points, storage, 0);
}

static void android_view_GLES20Canvas_drawPath(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkPath* path, SkPaint* paint) {
    renderer->drawPath(path, paint);
}

static void android_view_GLES20Canvas_drawLines(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloatArray points, jint offset, jint count, SkPaint* paint) {
    jfloat* storage = env->GetFloatArrayElements(points, NULL);
    renderer->drawLines(storage + offset, count, paint);
    env->ReleaseFloatArrayElements(points, storage, 0);
}

// ----------------------------------------------------------------------------
// Shaders and color filters
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_resetModifiers(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jint modifiers) {
    if (modifiers & MODIFIER_SHADOW) renderer->resetShadow();
    if (modifiers & MODIFIER_SHADER) renderer->resetShader();
    if (modifiers & MODIFIER_COLOR_FILTER) renderer->resetColorFilter();
}

static void android_view_GLES20Canvas_setupShader(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkiaShader* shader) {
    renderer->setupShader(shader);
}

static void android_view_GLES20Canvas_setupColorFilter(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, SkiaColorFilter* filter) {
    renderer->setupColorFilter(filter);
}

static void android_view_GLES20Canvas_setupShadow(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jfloat radius, jfloat dx, jfloat dy, jint color) {
    renderer->setupShadow(radius, dx, dy, color);
}

// ----------------------------------------------------------------------------
// Draw filters
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_setupPaintFilter(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jint clearBits, jint setBits) {
    renderer->setupPaintFilter(clearBits, setBits);
}

static void android_view_GLES20Canvas_resetPaintFilter(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer) {
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
        OpenGLRenderer* renderer, jcharArray text, jint index, jint count,
        jfloat x, jfloat y, jint flags, SkPaint* paint) {
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    renderText(renderer, textArray + index, count, x, y, flags, paint);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
}

static void android_view_GLES20Canvas_drawText(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jstring text, jint start, jint end,
        jfloat x, jfloat y, jint flags, SkPaint* paint) {
    const jchar* textArray = env->GetStringChars(text, NULL);
    renderText(renderer, textArray + start, end - start, x, y, flags, paint);
    env->ReleaseStringChars(text, textArray);
}

static void android_view_GLES20Canvas_drawTextArrayOnPath(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jcharArray text, jint index, jint count,
        SkPath* path, jfloat hOffset, jfloat vOffset, jint flags, SkPaint* paint) {
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    renderTextOnPath(renderer, textArray + index, count, path,
            hOffset, vOffset, flags, paint);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
}

static void android_view_GLES20Canvas_drawTextOnPath(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jstring text, jint start, jint end,
        SkPath* path, jfloat hOffset, jfloat vOffset, jint flags, SkPaint* paint) {
    const jchar* textArray = env->GetStringChars(text, NULL);
    renderTextOnPath(renderer, textArray + start, end - start, path,
            hOffset, vOffset, flags, paint);
    env->ReleaseStringChars(text, textArray);
}

static void android_view_GLES20Canvas_drawTextRunArray(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jcharArray text, jint index, jint count,
        jint contextIndex, jint contextCount, jfloat x, jfloat y, jint dirFlags,
        SkPaint* paint) {
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    renderTextRun(renderer, textArray + contextIndex, index - contextIndex,
            count, contextCount, x, y, dirFlags, paint);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
 }

static void android_view_GLES20Canvas_drawTextRun(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jstring text, jint start, jint end,
        jint contextStart, int contextEnd, jfloat x, jfloat y, jint dirFlags,
        SkPaint* paint) {
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
        OpenGLRenderer* renderer, jcharArray text, jint index, jint count,
        jfloatArray pos, SkPaint* paint) {
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    jfloat* positions = env->GetFloatArrayElements(pos, NULL);

    renderPosText(renderer, textArray + index, count, positions, kBidi_LTR, paint);

    env->ReleaseFloatArrayElements(pos, positions, JNI_ABORT);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
}

static void android_view_GLES20Canvas_drawPosText(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, jstring text, jint start, jint end,
        jfloatArray pos, SkPaint* paint) {
    const jchar* textArray = env->GetStringChars(text, NULL);
    jfloat* positions = env->GetFloatArrayElements(pos, NULL);

    renderPosText(renderer, textArray + start, end - start, positions, kBidi_LTR, paint);

    env->ReleaseFloatArrayElements(pos, positions, JNI_ABORT);
    env->ReleaseStringChars(text, textArray);
}

// ----------------------------------------------------------------------------
// Display lists
// ----------------------------------------------------------------------------

static DisplayList* android_view_GLES20Canvas_getDisplayList(JNIEnv* env,
        jobject clazz, DisplayListRenderer* renderer, DisplayList* displayList) {
    return renderer->getDisplayList(displayList);
}

static OpenGLRenderer* android_view_GLES20Canvas_createDisplayListRenderer(JNIEnv* env,
        jobject clazz) {
    return new DisplayListRenderer;
}

static void android_view_GLES20Canvas_resetDisplayListRenderer(JNIEnv* env,
        jobject clazz, DisplayListRenderer* renderer) {
    renderer->reset();
}

static jint android_view_GLES20Canvas_drawDisplayList(JNIEnv* env,
        jobject clazz, OpenGLRenderer* renderer, DisplayList* displayList,
        jobject dirty, jint flags) {
    android::uirenderer::Rect bounds;
    status_t status = renderer->drawDisplayList(displayList, bounds, flags);
    if (status != DrawGlInfo::kStatusDone && dirty != NULL) {
        env->CallVoidMethod(dirty, gRectClassInfo.set,
                int(bounds.left), int(bounds.top), int(bounds.right), int(bounds.bottom));
    }
    return status;
}

static void android_view_GLES20Canvas_outputDisplayList(JNIEnv* env,
        jobject clazz, OpenGLRenderer* renderer, DisplayList* displayList) {
    renderer->outputDisplayList(displayList);
}

// ----------------------------------------------------------------------------
// Layers
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_interrupt(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer) {
    renderer->interrupt();
}

static void android_view_GLES20Canvas_resume(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer) {
    renderer->resume();
}

static OpenGLRenderer* android_view_GLES20Canvas_createLayerRenderer(JNIEnv* env,
        jobject clazz, Layer* layer) {
    if (layer) {
        OpenGLRenderer* renderer = new LayerRenderer(layer);
        renderer->initProperties();
        return renderer;
    }
    return NULL;
}

static Layer* android_view_GLES20Canvas_createTextureLayer(JNIEnv* env, jobject clazz,
        jboolean isOpaque, jintArray layerInfo) {
    Layer* layer = LayerRenderer::createTextureLayer(isOpaque);

    if (layer) {
        jint* storage = env->GetIntArrayElements(layerInfo, NULL);
        storage[0] = layer->getTexture();
        env->ReleaseIntArrayElements(layerInfo, storage, 0);
    }

    return layer;
}

static Layer* android_view_GLES20Canvas_createLayer(JNIEnv* env, jobject clazz,
        jint width, jint height, jboolean isOpaque, jintArray layerInfo) {
    Layer* layer = LayerRenderer::createLayer(width, height, isOpaque);

    if (layer) {
        jint* storage = env->GetIntArrayElements(layerInfo, NULL);
        storage[0] = layer->getWidth();
        storage[1] = layer->getHeight();
        env->ReleaseIntArrayElements(layerInfo, storage, 0);
    }

    return layer;
}

static bool android_view_GLES20Canvas_resizeLayer(JNIEnv* env, jobject clazz,
        Layer* layer, jint width, jint height, jintArray layerInfo) {
    if (LayerRenderer::resizeLayer(layer, width, height)) {
        jint* storage = env->GetIntArrayElements(layerInfo, NULL);
        storage[0] = layer->getWidth();
        storage[1] = layer->getHeight();
        env->ReleaseIntArrayElements(layerInfo, storage, 0);
        return true;
    }
    return false;
}

static void android_view_GLES20Canvas_setLayerPaint(JNIEnv* env, jobject clazz,
        Layer* layer, SkPaint* paint) {
    if (layer) {
        layer->setPaint(paint);
    }
}

static void android_view_GLES20Canvas_setLayerColorFilter(JNIEnv* env, jobject clazz,
        Layer* layer, SkiaColorFilter* colorFilter) {
    if (layer) {
        layer->setColorFilter(colorFilter);
    }
}

static void android_view_GLES20Canvas_setOpaqueLayer(JNIEnv* env, jobject clazz,
        Layer* layer, jboolean isOpaque) {
    if (layer) {
        layer->setBlend(!isOpaque);
    }
}

static void android_view_GLES20Canvas_updateTextureLayer(JNIEnv* env, jobject clazz,
        Layer* layer, jint width, jint height, jboolean isOpaque, jobject surface) {
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
        Layer* layer, OpenGLRenderer* renderer, DisplayList* displayList,
        jint left, jint top, jint right, jint bottom) {
    layer->updateDeferred(renderer, displayList, left, top, right, bottom);
}

static void android_view_GLES20Canvas_clearLayerTexture(JNIEnv* env, jobject clazz,
        Layer* layer) {
    layer->clearTexture();
}

static void android_view_GLES20Canvas_setTextureLayerTransform(JNIEnv* env, jobject clazz,
        Layer* layer, SkMatrix* matrix) {
    layer->getTransform().load(*matrix);
}

static void android_view_GLES20Canvas_destroyLayer(JNIEnv* env, jobject clazz, Layer* layer) {
    LayerRenderer::destroyLayer(layer);
}

static void android_view_GLES20Canvas_destroyLayerDeferred(JNIEnv* env,
        jobject clazz, Layer* layer) {
    LayerRenderer::destroyLayerDeferred(layer);
}

static void android_view_GLES20Canvas_drawLayer(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, Layer* layer, jfloat x, jfloat y) {
    renderer->drawLayer(layer, x, y);
}

static jboolean android_view_GLES20Canvas_copyLayer(JNIEnv* env, jobject clazz,
        Layer* layer, SkBitmap* bitmap) {
    return LayerRenderer::copyLayer(layer, bitmap);
}

static void android_view_GLES20Canvas_pushLayerUpdate(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, Layer* layer) {
    renderer->pushLayerUpdate(layer);
}

static void android_view_GLES20Canvas_cancelLayerUpdate(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer, Layer* layer) {
    renderer->cancelLayerUpdate(layer);
}

static void android_view_GLES20Canvas_clearLayerUpdates(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer) {
    renderer->clearLayerUpdates();
}

static void android_view_GLES20Canvas_flushLayerUpdates(JNIEnv* env, jobject clazz,
        OpenGLRenderer* renderer) {
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

    { "nCreateRenderer",    "()I",             (void*) android_view_GLES20Canvas_createRenderer },
    { "nDestroyRenderer",   "(I)V",            (void*) android_view_GLES20Canvas_destroyRenderer },
    { "nSetViewport",       "(III)V",          (void*) android_view_GLES20Canvas_setViewport },
    { "nPrepare",           "(IZ)I",           (void*) android_view_GLES20Canvas_prepare },
    { "nPrepareDirty",      "(IIIIIZ)I",       (void*) android_view_GLES20Canvas_prepareDirty },
    { "nFinish",            "(I)V",            (void*) android_view_GLES20Canvas_finish },
    { "nSetName",           "(ILjava/lang/String;)V",
            (void*) android_view_GLES20Canvas_setName },

    { "nSetCountOverdrawEnabled", "(IZ)V",     (void*) android_view_GLES20Canvas_setCountOverdrawEnabled },
    { "nGetOverdraw",             "(I)F",      (void*) android_view_GLES20Canvas_getOverdraw },

    { "nGetStencilSize",    "()I",             (void*) android_view_GLES20Canvas_getStencilSize },

    { "nCallDrawGLFunction", "(II)I",          (void*) android_view_GLES20Canvas_callDrawGLFunction },
    { "nDetachFunctor",      "(II)V",          (void*) android_view_GLES20Canvas_detachFunctor },
    { "nAttachFunctor",      "(II)V",          (void*) android_view_GLES20Canvas_attachFunctor },
    { "nInvokeFunctors",     "(ILandroid/graphics/Rect;)I",
            (void*) android_view_GLES20Canvas_invokeFunctors },

    { "nSave",              "(II)I",           (void*) android_view_GLES20Canvas_save },
    { "nRestore",           "(I)V",            (void*) android_view_GLES20Canvas_restore },
    { "nRestoreToCount",    "(II)V",           (void*) android_view_GLES20Canvas_restoreToCount },
    { "nGetSaveCount",      "(I)I",            (void*) android_view_GLES20Canvas_getSaveCount },

    { "nSaveLayer",         "(IFFFFII)I",      (void*) android_view_GLES20Canvas_saveLayer },
    { "nSaveLayer",         "(III)I",          (void*) android_view_GLES20Canvas_saveLayerClip },
    { "nSaveLayerAlpha",    "(IFFFFII)I",      (void*) android_view_GLES20Canvas_saveLayerAlpha },
    { "nSaveLayerAlpha",    "(III)I",          (void*) android_view_GLES20Canvas_saveLayerAlphaClip },

    { "nQuickReject",       "(IFFFF)Z",        (void*) android_view_GLES20Canvas_quickReject },
    { "nClipRect",          "(IFFFFI)Z",       (void*) android_view_GLES20Canvas_clipRectF },
    { "nClipRect",          "(IIIIII)Z",       (void*) android_view_GLES20Canvas_clipRect },
    { "nClipPath",          "(III)Z",          (void*) android_view_GLES20Canvas_clipPath },
    { "nClipRegion",        "(III)Z",          (void*) android_view_GLES20Canvas_clipRegion },

    { "nTranslate",         "(IFF)V",          (void*) android_view_GLES20Canvas_translate },
    { "nRotate",            "(IF)V",           (void*) android_view_GLES20Canvas_rotate },
    { "nScale",             "(IFF)V",          (void*) android_view_GLES20Canvas_scale },
    { "nSkew",              "(IFF)V",          (void*) android_view_GLES20Canvas_skew },

    { "nSetMatrix",         "(II)V",           (void*) android_view_GLES20Canvas_setMatrix },
    { "nGetMatrix",         "(II)V",           (void*) android_view_GLES20Canvas_getMatrix },
    { "nConcatMatrix",      "(II)V",           (void*) android_view_GLES20Canvas_concatMatrix },

    { "nDrawBitmap",        "(II[BFFI)V",      (void*) android_view_GLES20Canvas_drawBitmap },
    { "nDrawBitmap",        "(II[BFFFFFFFFI)V",(void*) android_view_GLES20Canvas_drawBitmapRect },
    { "nDrawBitmap",        "(II[BII)V",       (void*) android_view_GLES20Canvas_drawBitmapMatrix },
    { "nDrawBitmap",        "(I[IIIFFIIZI)V",  (void*) android_view_GLES20Canvas_drawBitmapData },

    { "nDrawBitmapMesh",    "(II[BII[FI[III)V",(void*) android_view_GLES20Canvas_drawBitmapMesh },

    { "nDrawPatch",         "(II[BIFFFFI)V",   (void*) android_view_GLES20Canvas_drawPatch },

    { "nDrawColor",         "(III)V",          (void*) android_view_GLES20Canvas_drawColor },
    { "nDrawRect",          "(IFFFFI)V",       (void*) android_view_GLES20Canvas_drawRect },
    { "nDrawRects",         "(III)V",          (void*) android_view_GLES20Canvas_drawRegionAsRects },
    { "nDrawRects",         "(I[FII)V",        (void*) android_view_GLES20Canvas_drawRects },
    { "nDrawRoundRect",     "(IFFFFFFI)V",     (void*) android_view_GLES20Canvas_drawRoundRect },
    { "nDrawCircle",        "(IFFFI)V",        (void*) android_view_GLES20Canvas_drawCircle },
    { "nDrawOval",          "(IFFFFI)V",       (void*) android_view_GLES20Canvas_drawOval },
    { "nDrawArc",           "(IFFFFFFZI)V",    (void*) android_view_GLES20Canvas_drawArc },
    { "nDrawPoints",        "(I[FIII)V",       (void*) android_view_GLES20Canvas_drawPoints },

    { "nDrawPath",          "(III)V",          (void*) android_view_GLES20Canvas_drawPath },
    { "nDrawLines",         "(I[FIII)V",       (void*) android_view_GLES20Canvas_drawLines },

    { "nResetModifiers",    "(II)V",           (void*) android_view_GLES20Canvas_resetModifiers },
    { "nSetupShader",       "(II)V",           (void*) android_view_GLES20Canvas_setupShader },
    { "nSetupColorFilter",  "(II)V",           (void*) android_view_GLES20Canvas_setupColorFilter },
    { "nSetupShadow",       "(IFFFI)V",        (void*) android_view_GLES20Canvas_setupShadow },

    { "nSetupPaintFilter",  "(III)V",          (void*) android_view_GLES20Canvas_setupPaintFilter },
    { "nResetPaintFilter",  "(I)V",            (void*) android_view_GLES20Canvas_resetPaintFilter },

    { "nDrawText",          "(I[CIIFFII)V",    (void*) android_view_GLES20Canvas_drawTextArray },
    { "nDrawText",          "(ILjava/lang/String;IIFFII)V",
            (void*) android_view_GLES20Canvas_drawText },

    { "nDrawTextOnPath",    "(I[CIIIFFII)V",   (void*) android_view_GLES20Canvas_drawTextArrayOnPath },
    { "nDrawTextOnPath",    "(ILjava/lang/String;IIIFFII)V",
            (void*) android_view_GLES20Canvas_drawTextOnPath },

    { "nDrawTextRun",       "(I[CIIIIFFII)V",  (void*) android_view_GLES20Canvas_drawTextRunArray },
    { "nDrawTextRun",       "(ILjava/lang/String;IIIIFFII)V",
            (void*) android_view_GLES20Canvas_drawTextRun },

    { "nDrawPosText",       "(I[CII[FI)V",     (void*) android_view_GLES20Canvas_drawPosTextArray },
    { "nDrawPosText",       "(ILjava/lang/String;II[FI)V",
            (void*) android_view_GLES20Canvas_drawPosText },

    { "nGetClipBounds",     "(ILandroid/graphics/Rect;)Z",
            (void*) android_view_GLES20Canvas_getClipBounds },

    { "nGetDisplayList",         "(II)I",      (void*) android_view_GLES20Canvas_getDisplayList },
    { "nOutputDisplayList",      "(II)V",      (void*) android_view_GLES20Canvas_outputDisplayList },
    { "nDrawDisplayList",        "(IILandroid/graphics/Rect;I)I",
            (void*) android_view_GLES20Canvas_drawDisplayList },

    { "nCreateDisplayListRenderer", "()I",     (void*) android_view_GLES20Canvas_createDisplayListRenderer },
    { "nResetDisplayListRenderer",  "(I)V",    (void*) android_view_GLES20Canvas_resetDisplayListRenderer },

    { "nInterrupt",              "(I)V",       (void*) android_view_GLES20Canvas_interrupt },
    { "nResume",                 "(I)V",       (void*) android_view_GLES20Canvas_resume },

    { "nCreateLayerRenderer",    "(I)I",       (void*) android_view_GLES20Canvas_createLayerRenderer },
    { "nCreateLayer",            "(IIZ[I)I",   (void*) android_view_GLES20Canvas_createLayer },
    { "nResizeLayer",            "(III[I)Z" ,  (void*) android_view_GLES20Canvas_resizeLayer },
    { "nSetLayerPaint",          "(II)V",      (void*) android_view_GLES20Canvas_setLayerPaint },
    { "nSetLayerColorFilter",    "(II)V",      (void*) android_view_GLES20Canvas_setLayerColorFilter },
    { "nSetOpaqueLayer",         "(IZ)V",      (void*) android_view_GLES20Canvas_setOpaqueLayer },
    { "nCreateTextureLayer",     "(Z[I)I",     (void*) android_view_GLES20Canvas_createTextureLayer },
    { "nUpdateTextureLayer",     "(IIIZLandroid/graphics/SurfaceTexture;)V",
            (void*) android_view_GLES20Canvas_updateTextureLayer },
    { "nUpdateRenderLayer",      "(IIIIIII)V", (void*) android_view_GLES20Canvas_updateRenderLayer },
    { "nClearLayerTexture",      "(I)V",       (void*) android_view_GLES20Canvas_clearLayerTexture },
    { "nDestroyLayer",           "(I)V",       (void*) android_view_GLES20Canvas_destroyLayer },
    { "nDestroyLayerDeferred",   "(I)V",       (void*) android_view_GLES20Canvas_destroyLayerDeferred },
    { "nDrawLayer",              "(IIFF)V",    (void*) android_view_GLES20Canvas_drawLayer },
    { "nCopyLayer",              "(II)Z",      (void*) android_view_GLES20Canvas_copyLayer },
    { "nClearLayerUpdates",      "(I)V",       (void*) android_view_GLES20Canvas_clearLayerUpdates },
    { "nFlushLayerUpdates",      "(I)V",       (void*) android_view_GLES20Canvas_flushLayerUpdates },
    { "nPushLayerUpdate",        "(II)V",      (void*) android_view_GLES20Canvas_pushLayerUpdate },
    { "nCancelLayerUpdate",      "(II)V",      (void*) android_view_GLES20Canvas_cancelLayerUpdate },

    { "nSetTextureLayerTransform", "(II)V",    (void*) android_view_GLES20Canvas_setTextureLayerTransform },

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
