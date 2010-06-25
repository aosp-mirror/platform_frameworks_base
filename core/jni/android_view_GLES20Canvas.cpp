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

#include "jni.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <SkCanvas.h>
#include <SkMatrix.h>
#include <SkPaint.h>
#include <SkXfermode.h>

#include <OpenGLRenderer.h>
#include <Rect.h>
#include <ui/Rect.h>

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

static OpenGLRenderer* android_view_GLES20Renderer_createRenderer(JNIEnv* env, jobject canvas) {
    return new OpenGLRenderer;
}

static void android_view_GLES20Renderer_destroyRenderer(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer) {
    delete renderer;
}

// ----------------------------------------------------------------------------
// Setup
// ----------------------------------------------------------------------------

static void android_view_GLES20Renderer_setViewport(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jint width, jint height) {
    renderer->setViewport(width, height);
}

static void android_view_GLES20Renderer_prepare(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer) {
    renderer->prepare();
}

// ----------------------------------------------------------------------------
// State
// ----------------------------------------------------------------------------

static jint android_view_GLES20Renderer_save(JNIEnv* env, jobject canvas, OpenGLRenderer* renderer,
        jint flags) {
    return renderer->save(flags);
}

static jint android_view_GLES20Renderer_getSaveCount(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer) {
    return renderer->getSaveCount();
}

static void android_view_GLES20Renderer_restore(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer) {
    renderer->restore();
}

static void android_view_GLES20Renderer_restoreToCount(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jint saveCount) {
    renderer->restoreToCount(saveCount);
}

// ----------------------------------------------------------------------------
// Clipping
// ----------------------------------------------------------------------------

static bool android_view_GLES20Renderer_quickReject(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        SkCanvas::EdgeType edge) {
    return renderer->quickReject(left, top, right, bottom);
}

static bool android_view_GLES20Renderer_clipRectF(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom) {
    return renderer->clipRect(left, top, right, bottom);
}

static bool android_view_GLES20Renderer_clipRect(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jint left, jint top, jint right, jint bottom) {
    return renderer->clipRect(float(left), float(top), float(right), float(bottom));
}

static bool android_view_GLES20Renderer_getClipBounds(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jobject rect) {

	const android::uirenderer::Rect& bounds(renderer->getClipBounds());

	env->CallVoidMethod(rect, gRectClassInfo.set,
			int(bounds.left), int(bounds.top), int(bounds.right), int(bounds.bottom));

	return !bounds.isEmpty();
}

// ----------------------------------------------------------------------------
// Transforms
// ----------------------------------------------------------------------------

static void android_view_GLES20Renderer_translate(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat dx, jfloat dy) {
	renderer->translate(dx, dy);
}

static void android_view_GLES20Renderer_rotate(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat degrees) {
	renderer->rotate(degrees);
}

static void android_view_GLES20Renderer_scale(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat sx, jfloat sy) {
	renderer->scale(sx, sy);
}

static void android_view_GLES20Renderer_setMatrix(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkMatrix* matrix) {
	renderer->setMatrix(matrix);
}

static void android_view_GLES20Renderer_getMatrix(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkMatrix* matrix) {
	renderer->getMatrix(matrix);
}

static void android_view_GLES20Renderer_concatMatrix(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, SkMatrix* matrix) {
	renderer->concatMatrix(matrix);
}

// ----------------------------------------------------------------------------
// Drawing
// ----------------------------------------------------------------------------

static void android_view_GLES20Renderer_drawColor(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jint color, jint mode) {
    renderer->drawColor(color, (SkXfermode::Mode) mode);
}

static void android_view_GLES20Renderer_drawRect(JNIEnv* env, jobject canvas,
        OpenGLRenderer* renderer, jfloat left, jfloat top, jfloat right, jfloat bottom,
        SkPaint* paint) {
    renderer->drawRect(left, top, right, bottom, paint);
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/GLES20Canvas";

static JNINativeMethod gMethods[] = {
    {   "nCreateRenderer",    "()I",       (void*) android_view_GLES20Renderer_createRenderer },
    {   "nDestroyRenderer",   "(I)V",      (void*) android_view_GLES20Renderer_destroyRenderer },
    {   "nSetViewport",       "(III)V",    (void*) android_view_GLES20Renderer_setViewport },
    {   "nPrepare",           "(I)V",      (void*) android_view_GLES20Renderer_prepare },

    {   "nSave",              "(II)I",     (void*) android_view_GLES20Renderer_save },
    {   "nRestore",           "(I)V",      (void*) android_view_GLES20Renderer_restore },
    {   "nRestoreToCount",    "(II)V",     (void*) android_view_GLES20Renderer_restoreToCount },
    {   "nGetSaveCount",      "(I)I",      (void*) android_view_GLES20Renderer_getSaveCount },

    {   "nQuickReject",       "(IFFFFI)Z", (void*) android_view_GLES20Renderer_quickReject },
    {   "nClipRect",          "(IFFFF)Z",  (void*) android_view_GLES20Renderer_clipRectF },
    {   "nClipRect",          "(IIIII)Z",  (void*) android_view_GLES20Renderer_clipRect },

    {   "nTranslate",         "(IFF)V",    (void*) android_view_GLES20Renderer_translate },
    {   "nRotate",            "(IF)V",     (void*) android_view_GLES20Renderer_rotate },
    {   "nScale",             "(IFF)V",    (void*) android_view_GLES20Renderer_scale },

    {   "nSetMatrix",         "(II)V",     (void*) android_view_GLES20Renderer_setMatrix },
    {   "nGetMatrix",         "(II)V",     (void*) android_view_GLES20Renderer_getMatrix },
    {   "nConcatMatrix",      "(II)V",     (void*) android_view_GLES20Renderer_concatMatrix },

    {   "nDrawColor",         "(III)V",    (void*) android_view_GLES20Renderer_drawColor },
    {   "nDrawRect",          "(IFFFFI)V", (void*) android_view_GLES20Renderer_drawRect },

    {   "nGetClipBounds",     "(ILandroid/graphics/Rect;)Z",
            (void*) android_view_GLES20Renderer_getClipBounds },
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
