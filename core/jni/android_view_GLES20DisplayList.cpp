/*
 * Copyright (C) 2012 The Android Open Source Project
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

#include <EGL/egl.h>

#include "jni.h"
#include "GraphicsJNI.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <DisplayListRenderer.h>

namespace android {

using namespace uirenderer;

/**
 * Note: OpenGLRenderer JNI layer is generated and compiled only on supported
 *       devices. This means all the logic must be compiled only when the
 *       preprocessor variable USE_OPENGL_RENDERER is defined.
 */
#ifdef USE_OPENGL_RENDERER

static void android_view_GLES20DisplayList_reset(JNIEnv* env,
        jobject clazz, DisplayList* displayList) {
    displayList->reset();
}

// ----------------------------------------------------------------------------
// DisplayList view properties
// ----------------------------------------------------------------------------

static void android_view_GLES20DisplayList_setCaching(JNIEnv* env,
        jobject clazz, DisplayList* displayList, jboolean caching) {
    displayList->setCaching(caching);
}

static void android_view_GLES20DisplayList_setStaticMatrix(JNIEnv* env,
        jobject clazz, DisplayList* displayList, SkMatrix* matrix) {
    displayList->setStaticMatrix(matrix);
}

static void android_view_GLES20DisplayList_setAnimationMatrix(JNIEnv* env,
        jobject clazz, DisplayList* displayList, SkMatrix* matrix) {
    displayList->setAnimationMatrix(matrix);
}

static void android_view_GLES20DisplayList_setClipChildren(JNIEnv* env,
        jobject clazz, DisplayList* displayList, jboolean clipChildren) {
    displayList->setClipChildren(clipChildren);
}

static void android_view_GLES20DisplayList_setAlpha(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float alpha) {
    displayList->setAlpha(alpha);
}

static void android_view_GLES20DisplayList_setHasOverlappingRendering(JNIEnv* env,
        jobject clazz, DisplayList* displayList, bool hasOverlappingRendering) {
    displayList->setHasOverlappingRendering(hasOverlappingRendering);
}

static void android_view_GLES20DisplayList_setTranslationX(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float tx) {
    displayList->setTranslationX(tx);
}

static void android_view_GLES20DisplayList_setTranslationY(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float ty) {
    displayList->setTranslationY(ty);
}

static void android_view_GLES20DisplayList_setRotation(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float rotation) {
    displayList->setRotation(rotation);
}

static void android_view_GLES20DisplayList_setRotationX(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float rx) {
    displayList->setRotationX(rx);
}

static void android_view_GLES20DisplayList_setRotationY(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float ry) {
    displayList->setRotationY(ry);
}

static void android_view_GLES20DisplayList_setScaleX(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float sx) {
    displayList->setScaleX(sx);
}

static void android_view_GLES20DisplayList_setScaleY(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float sy) {
    displayList->setScaleY(sy);
}

static void android_view_GLES20DisplayList_setTransformationInfo(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float alpha,
        float translationX, float translationY, float rotation, float rotationX, float rotationY,
        float scaleX, float scaleY) {
    displayList->setAlpha(alpha);
    displayList->setTranslationX(translationX);
    displayList->setTranslationY(translationY);
    displayList->setRotation(rotation);
    displayList->setRotationX(rotationX);
    displayList->setRotationY(rotationY);
    displayList->setScaleX(scaleX);
    displayList->setScaleY(scaleY);
}

static void android_view_GLES20DisplayList_setPivotX(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float px) {
    displayList->setPivotX(px);
}

static void android_view_GLES20DisplayList_setPivotY(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float py) {
    displayList->setPivotY(py);
}

static void android_view_GLES20DisplayList_setCameraDistance(JNIEnv* env,
        jobject clazz, DisplayList* displayList, float distance) {
    displayList->setCameraDistance(distance);
}

static void android_view_GLES20DisplayList_setLeft(JNIEnv* env,
        jobject clazz, DisplayList* displayList, int left) {
    displayList->setLeft(left);
}

static void android_view_GLES20DisplayList_setTop(JNIEnv* env,
        jobject clazz, DisplayList* displayList, int top) {
    displayList->setTop(top);
}

static void android_view_GLES20DisplayList_setRight(JNIEnv* env,
        jobject clazz, DisplayList* displayList, int right) {
    displayList->setRight(right);
}

static void android_view_GLES20DisplayList_setBottom(JNIEnv* env,
        jobject clazz, DisplayList* displayList, int bottom) {
    displayList->setBottom(bottom);
}

static void android_view_GLES20DisplayList_setLeftTop(JNIEnv* env,
        jobject clazz, DisplayList* displayList, int left, int top) {
    displayList->setLeftTop(left, top);
}

static void android_view_GLES20DisplayList_setLeftTopRightBottom(JNIEnv* env,
        jobject clazz, DisplayList* displayList, int left, int top,
        int right, int bottom) {
    displayList->setLeftTopRightBottom(left, top, right, bottom);
}

static void android_view_GLES20DisplayList_offsetLeftRight(JNIEnv* env,
        jobject clazz, DisplayList* displayList, int offset) {
    displayList->offsetLeftRight(offset);
}

static void android_view_GLES20DisplayList_offsetTopBottom(JNIEnv* env,
        jobject clazz, DisplayList* displayList, int offset) {
    displayList->offsetTopBottom(offset);
}

#endif // USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/GLES20DisplayList";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nReset",                "(I)V",   (void*) android_view_GLES20DisplayList_reset },
    { "nSetCaching",           "(IZ)V",  (void*) android_view_GLES20DisplayList_setCaching },
    { "nSetStaticMatrix",      "(II)V",  (void*) android_view_GLES20DisplayList_setStaticMatrix },
    { "nSetAnimationMatrix",   "(II)V",  (void*) android_view_GLES20DisplayList_setAnimationMatrix },
    { "nSetClipChildren",      "(IZ)V",  (void*) android_view_GLES20DisplayList_setClipChildren },
    { "nSetAlpha",             "(IF)V",  (void*) android_view_GLES20DisplayList_setAlpha },
    { "nSetHasOverlappingRendering", "(IZ)V",
            (void*) android_view_GLES20DisplayList_setHasOverlappingRendering },
    { "nSetTranslationX",      "(IF)V",  (void*) android_view_GLES20DisplayList_setTranslationX },
    { "nSetTranslationY",      "(IF)V",  (void*) android_view_GLES20DisplayList_setTranslationY },
    { "nSetRotation",          "(IF)V",  (void*) android_view_GLES20DisplayList_setRotation },
    { "nSetRotationX",         "(IF)V",  (void*) android_view_GLES20DisplayList_setRotationX },
    { "nSetRotationY",         "(IF)V",  (void*) android_view_GLES20DisplayList_setRotationY },
    { "nSetScaleX",            "(IF)V",  (void*) android_view_GLES20DisplayList_setScaleX },
    { "nSetScaleY",            "(IF)V",  (void*) android_view_GLES20DisplayList_setScaleY },
    { "nSetTransformationInfo","(IFFFFFFFF)V",
            (void*) android_view_GLES20DisplayList_setTransformationInfo },
    { "nSetPivotX",            "(IF)V",  (void*) android_view_GLES20DisplayList_setPivotX },
    { "nSetPivotY",            "(IF)V",  (void*) android_view_GLES20DisplayList_setPivotY },
    { "nSetCameraDistance",    "(IF)V",  (void*) android_view_GLES20DisplayList_setCameraDistance },
    { "nSetLeft",              "(II)V",  (void*) android_view_GLES20DisplayList_setLeft },
    { "nSetTop",               "(II)V",  (void*) android_view_GLES20DisplayList_setTop },
    { "nSetRight",             "(II)V",  (void*) android_view_GLES20DisplayList_setRight },
    { "nSetBottom",            "(II)V",  (void*) android_view_GLES20DisplayList_setBottom },
    { "nSetLeftTop",           "(III)V", (void*) android_view_GLES20DisplayList_setLeftTop },
    { "nSetLeftTopRightBottom","(IIIII)V",
            (void*) android_view_GLES20DisplayList_setLeftTopRightBottom },
    { "nOffsetLeftRight",      "(II)V",  (void*) android_view_GLES20DisplayList_offsetLeftRight },
    { "nOffsetTopBottom",      "(II)V",  (void*) android_view_GLES20DisplayList_offsetTopBottom },

#endif
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

int register_android_view_GLES20DisplayList(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};

