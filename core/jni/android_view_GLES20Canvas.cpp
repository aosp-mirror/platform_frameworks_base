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
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>
#include <utils/ResourceTypes.h>

#include <SkBitmap.h>
#include <SkCanvas.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkRegion.h>
#include <SkScalerContext.h>
#include <SkXfermode.h>

#include <OpenGLRenderer.h>
#include <SkiaShader.h>
#include <Rect.h>
#include <ui/Rect.h>

#include "TextLayout.h"

namespace android {

using namespace uirenderer;

// ----------------------------------------------------------------------------
// Java APIs
// ----------------------------------------------------------------------------

static struct {
    jclass clazz;
    jmethodID set;
} gRectClassInfo;

// ----------------------------------------------------------------------------
// Constructors
// ----------------------------------------------------------------------------

static OpenGLRenderer* android_view_GLES20Canvas_createRenderer(JNIEnv* env, jobject canvas) {
    return new OpenGLRenderer;
}

static void android_view_GLES20Canvas_destroyRenderer(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer) {
    delete renderer;
}

// ----------------------------------------------------------------------------
// Setup
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_setViewport(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jint width, jint height) {
    renderer->setViewport(width, height);
}

static void android_view_GLES20Canvas_prepare(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer) {
    renderer->prepare();
}

// ----------------------------------------------------------------------------
// State
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_save(JNIEnv* env, jobject canvas, OpenGLRenderer* renderer,
        jint flags) {
    return renderer->save(flags);
}

static jint android_view_GLES20Canvas_getSaveCount(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer) {
    return renderer->getSaveCount();
}

static void android_view_GLES20Canvas_restore(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer) {
    renderer->restore();
}

static void android_view_GLES20Canvas_restoreToCount(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jint saveCount) {
    renderer->restoreToCount(saveCount);
}

// ----------------------------------------------------------------------------
// Layers
// ----------------------------------------------------------------------------

static jint android_view_GLES20Canvas_saveLayer(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        SkPaint* paint, jint saveFlags) {
    return renderer->saveLayer(left, top, right, bottom, paint, saveFlags);
}

static jint android_view_GLES20Canvas_saveLayerAlpha(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        jint alpha, jint saveFlags) {
    return renderer->saveLayerAlpha(left, top, right, bottom, alpha, saveFlags);
}

// ----------------------------------------------------------------------------
// Clipping
// ----------------------------------------------------------------------------

static bool android_view_GLES20Canvas_quickReject(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        SkCanvas::EdgeType edge) {
    return renderer->quickReject(left, top, right, bottom);
}

static bool android_view_GLES20Canvas_clipRectF(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        SkRegion::Op op) {
    return renderer->clipRect(left, top, right, bottom, op);
}

static bool android_view_GLES20Canvas_clipRect(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jint left, jint top, jint right, jint bottom,
        SkRegion::Op op) {
    return renderer->clipRect(float(left), float(top), float(right), float(bottom), op);
}

static bool android_view_GLES20Canvas_getClipBounds(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jobject rect) {
    const android::uirenderer::Rect& bounds(renderer->getClipBounds());

    env->CallVoidMethod(rect, gRectClassInfo.set,
            int(bounds.left), int(bounds.top), int(bounds.right), int(bounds.bottom));

    return !bounds.isEmpty();
}

// ----------------------------------------------------------------------------
// Transforms
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_translate(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat dx, jfloat dy) {
    renderer->translate(dx, dy);
}

static void android_view_GLES20Canvas_rotate(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat degrees) {
    renderer->rotate(degrees);
}

static void android_view_GLES20Canvas_scale(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat sx, jfloat sy) {
    renderer->scale(sx, sy);
}

static void android_view_GLES20Canvas_setMatrix(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkMatrix* matrix) {
    renderer->setMatrix(matrix);
}

static void android_view_GLES20Canvas_getMatrix(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkMatrix* matrix) {
    renderer->getMatrix(matrix);
}

static void android_view_GLES20Canvas_concatMatrix(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkMatrix* matrix) {
    renderer->concatMatrix(matrix);
}

// ----------------------------------------------------------------------------
// Drawing
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_drawBitmap(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkBitmap* bitmap, float left, float top, SkPaint* paint) {
    renderer->drawBitmap(bitmap, left, top, paint);
}

static void android_view_GLES20Canvas_drawBitmapRect(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkBitmap* bitmap,
        float srcLeft, float srcTop, float srcRight, float srcBottom,
        float dstLeft, float dstTop, float dstRight, float dstBottom, SkPaint* paint) {
    renderer->drawBitmap(bitmap, srcLeft, srcTop, srcRight, srcBottom,
            dstLeft, dstTop, dstRight, dstBottom, paint);
}

static void android_view_GLES20Canvas_drawBitmapMatrix(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkBitmap* bitmap, SkMatrix* matrix, SkPaint* paint) {
    renderer->drawBitmap(bitmap, matrix, paint);
}

static void android_view_GLES20Canvas_drawPatch(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkBitmap* bitmap, jbyteArray chunks,
        float left, float top, float right, float bottom, SkPaint* paint) {
    jbyte* storage = env->GetByteArrayElements(chunks, NULL);
    Res_png_9patch* patch = reinterpret_cast<Res_png_9patch*>(storage);
    Res_png_9patch::deserialize(patch);

    renderer->drawPatch(bitmap, patch, left, top, right, bottom, paint);

    env->ReleaseByteArrayElements(chunks, storage, 0);
}

static void android_view_GLES20Canvas_drawColor(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jint color, SkXfermode::Mode mode) {
    renderer->drawColor(color, mode);
}

static void android_view_GLES20Canvas_drawRect(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        SkPaint* paint) {
    renderer->drawRect(left, top, right, bottom, paint);
}

// ----------------------------------------------------------------------------
// Shaders
// ----------------------------------------------------------------------------

static void android_view_GLES20Canvas_resetShader(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer) {
    renderer->resetShader();
}

static void android_view_GLES20Canvas_setupShader(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkiaShader* shader) {
    renderer->setupShader(shader);
}

// ----------------------------------------------------------------------------
// Text
// ----------------------------------------------------------------------------

static void renderText(OpenGLRenderer* renderer, const jchar* text, int count,
        jfloat x, jfloat y, int flags, SkPaint* paint) {
    const jchar *workText;
    jchar* buffer = NULL;
    int32_t workBytes;
    if (TextLayout::prepareText(paint, text, count, flags, &workText, &workBytes, &buffer)) {
        renderer->drawText((const char*) workText, workBytes, count, x, y, paint);
        free(buffer);
    }
}

static void android_view_GLES20Canvas_drawTextArray(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jcharArray text, int index, int count,
        jfloat x, jfloat y, int flags, SkPaint* paint) {
    jchar* textArray = env->GetCharArrayElements(text, NULL);
    renderText(renderer, textArray + index, count, x, y, flags, paint);
    env->ReleaseCharArrayElements(text, textArray, JNI_ABORT);
}

static void android_view_GLES20Canvas_drawText(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jstring text, int start, int end,
        jfloat x, jfloat y, int flags, SkPaint* paint) {
    const jchar* textArray = env->GetStringChars(text, NULL);
    renderText(renderer, textArray + start, end - start, x, y, flags, paint);
    env->ReleaseStringChars(text, textArray);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/GLES20Canvas";

static JNINativeMethod gMethods[] = {
    {   "nCreateRenderer",    "()I",             (void*) android_view_GLES20Canvas_createRenderer },
    {   "nDestroyRenderer",   "(I)V",            (void*) android_view_GLES20Canvas_destroyRenderer },
    {   "nSetViewport",       "(III)V",          (void*) android_view_GLES20Canvas_setViewport },
    {   "nPrepare",           "(I)V",            (void*) android_view_GLES20Canvas_prepare },

    {   "nSave",              "(II)I",           (void*) android_view_GLES20Canvas_save },
    {   "nRestore",           "(I)V",            (void*) android_view_GLES20Canvas_restore },
    {   "nRestoreToCount",    "(II)V",           (void*) android_view_GLES20Canvas_restoreToCount },
    {   "nGetSaveCount",      "(I)I",            (void*) android_view_GLES20Canvas_getSaveCount },

    {   "nSaveLayer",         "(IFFFFII)I",      (void*) android_view_GLES20Canvas_saveLayer },
    {   "nSaveLayerAlpha",    "(IFFFFII)I",      (void*) android_view_GLES20Canvas_saveLayerAlpha },

    {   "nQuickReject",       "(IFFFFI)Z",       (void*) android_view_GLES20Canvas_quickReject },
    {   "nClipRect",          "(IFFFFI)Z",       (void*) android_view_GLES20Canvas_clipRectF },
    {   "nClipRect",          "(IIIIII)Z",       (void*) android_view_GLES20Canvas_clipRect },

    {   "nTranslate",         "(IFF)V",          (void*) android_view_GLES20Canvas_translate },
    {   "nRotate",            "(IF)V",           (void*) android_view_GLES20Canvas_rotate },
    {   "nScale",             "(IFF)V",          (void*) android_view_GLES20Canvas_scale },

    {   "nSetMatrix",         "(II)V",           (void*) android_view_GLES20Canvas_setMatrix },
    {   "nGetMatrix",         "(II)V",           (void*) android_view_GLES20Canvas_getMatrix },
    {   "nConcatMatrix",      "(II)V",           (void*) android_view_GLES20Canvas_concatMatrix },

    {   "nDrawBitmap",        "(IIFFI)V",        (void*) android_view_GLES20Canvas_drawBitmap },
    {   "nDrawBitmap",        "(IIFFFFFFFFI)V",  (void*) android_view_GLES20Canvas_drawBitmapRect },
    {   "nDrawBitmap",        "(IIII)V",         (void*) android_view_GLES20Canvas_drawBitmapMatrix },
    {   "nDrawPatch",         "(II[BFFFFI)V",    (void*) android_view_GLES20Canvas_drawPatch },
    {   "nDrawColor",         "(III)V",          (void*) android_view_GLES20Canvas_drawColor },
    {   "nDrawRect",          "(IFFFFI)V",       (void*) android_view_GLES20Canvas_drawRect },

    {   "nResetShader",       "(I)V",            (void*) android_view_GLES20Canvas_resetShader },
    {   "nSetupShader",       "(II)V",           (void*) android_view_GLES20Canvas_setupShader },

    {   "nDrawText",          "(I[CIIFFII)V",    (void*) android_view_GLES20Canvas_drawTextArray },
    {   "nDrawText",          "(ILjava/lang/String;IIFFII)V",
            (void*) android_view_GLES20Canvas_drawText },

    {   "nGetClipBounds",     "(ILandroid/graphics/Rect;)Z",
            (void*) android_view_GLES20Canvas_getClipBounds },
};

#define FIND_CLASS(var, className) \
        var = env->FindClass(className); \
        LOG_FATAL_IF(! var, "Unable to find class " className); \
        var = jclass(env->NewGlobalRef(var));

#define GET_METHOD_ID(var, clazz, methodName, methodDescriptor) \
        var = env->GetMethodID(clazz, methodName, methodDescriptor); \
        LOG_FATAL_IF(! var, "Unable to find method " methodName);

int register_android_view_GLES20Canvas(JNIEnv* env) {
    FIND_CLASS(gRectClassInfo.clazz, "android/graphics/Rect");
    GET_METHOD_ID(gRectClassInfo.set, gRectClassInfo.clazz, "set", "(IIII)V");

    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};
