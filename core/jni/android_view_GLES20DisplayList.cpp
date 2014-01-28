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

#include <DisplayList.h>
#include <DisplayListRenderer.h>

namespace android {

using namespace uirenderer;

/**
 * Note: OpenGLRenderer JNI layer is generated and compiled only on supported
 *       devices. This means all the logic must be compiled only when the
 *       preprocessor variable USE_OPENGL_RENDERER is defined.
 */
#ifdef USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// DisplayList view properties
// ----------------------------------------------------------------------------

static void android_view_GLES20DisplayList_reset(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->reset();
}

static jint android_view_GLES20DisplayList_getDisplayListSize(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getSize();
}

static void android_view_GLES20DisplayList_setDisplayListName(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jstring name) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    if (name != NULL) {
        const char* textArray = env->GetStringUTFChars(name, NULL);
        displayList->setName(textArray);
        env->ReleaseStringUTFChars(name, textArray);
    }
}

static void android_view_GLES20DisplayList_destroyDisplayList(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    DisplayList::destroyDisplayListDeferred(displayList);
}

// ----------------------------------------------------------------------------
// DisplayList view properties
// ----------------------------------------------------------------------------

static void android_view_GLES20DisplayList_setCaching(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jboolean caching) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setCaching(caching);
}

//serban
static void android_view_GLES20DisplayList_setStaticMatrix(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jlong matrixHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    displayList->setStaticMatrix(matrix);
}

static void android_view_GLES20DisplayList_setAnimationMatrix(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jlong matrixHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    displayList->setAnimationMatrix(matrix);
}

static void android_view_GLES20DisplayList_setClipToBounds(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jboolean clipToBounds) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setClipToBounds(clipToBounds);
}

static void android_view_GLES20DisplayList_setAlpha(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat alpha) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setAlpha(alpha);
}

static void android_view_GLES20DisplayList_setHasOverlappingRendering(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jboolean hasOverlappingRendering) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setHasOverlappingRendering(hasOverlappingRendering);
}

static void android_view_GLES20DisplayList_setTranslationX(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat tx) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setTranslationX(tx);
}

static void android_view_GLES20DisplayList_setTranslationY(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat ty) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setTranslationY(ty);
}

static void android_view_GLES20DisplayList_setRotation(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat rotation) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setRotation(rotation);
}

static void android_view_GLES20DisplayList_setRotationX(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat rx) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setRotationX(rx);
}

static void android_view_GLES20DisplayList_setRotationY(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat ry) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setRotationY(ry);
}

static void android_view_GLES20DisplayList_setScaleX(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat sx) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setScaleX(sx);
}

static void android_view_GLES20DisplayList_setScaleY(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat sy) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setScaleY(sy);
}

static void android_view_GLES20DisplayList_setTransformationInfo(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat alpha,
        jfloat translationX, jfloat translationY, jfloat rotation, jfloat rotationX, jfloat rotationY,
        jfloat scaleX, jfloat scaleY) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
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
        jobject clazz, jlong displayListHandle, jfloat px) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setPivotX(px);
}

static void android_view_GLES20DisplayList_setPivotY(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat py) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setPivotY(py);
}

static void android_view_GLES20DisplayList_setCameraDistance(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat distance) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setCameraDistance(distance);
}

static void android_view_GLES20DisplayList_setLeft(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jint left) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setLeft(left);
}

static void android_view_GLES20DisplayList_setTop(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jint top) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setTop(top);
}

static void android_view_GLES20DisplayList_setRight(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jint right) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setRight(right);
}

static void android_view_GLES20DisplayList_setBottom(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jint bottom) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setBottom(bottom);
}

static void android_view_GLES20DisplayList_setLeftTopRightBottom(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jint left, jint top,
        int right, int bottom) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->setLeftTopRightBottom(left, top, right, bottom);
}

static void android_view_GLES20DisplayList_offsetLeftAndRight(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat offset) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->offsetLeftRight(offset);
}

static void android_view_GLES20DisplayList_offsetTopAndBottom(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jfloat offset) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    displayList->offsetTopBottom(offset);
}

static void android_view_GLES20DisplayList_getMatrix(JNIEnv* env,
        jobject clazz, jlong displayListHandle, jlong matrixHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixHandle);
    SkMatrix* source = displayList->getStaticMatrix();
    if (source) {
        matrix->setConcat(SkMatrix::I(), *source);
    } else {
        matrix->setIdentity();
    }
}

static jboolean android_view_GLES20DisplayList_hasOverlappingRendering(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->hasOverlappingRendering();
}

static jfloat android_view_GLES20DisplayList_getAlpha(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getAlpha();
}

static jfloat android_view_GLES20DisplayList_getLeft(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getLeft();
}

static jfloat android_view_GLES20DisplayList_getTop(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getTop();
}

static jfloat android_view_GLES20DisplayList_getRight(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getRight();
}

static jfloat android_view_GLES20DisplayList_getBottom(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getBottom();
}

static jfloat android_view_GLES20DisplayList_getCameraDistance(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getCameraDistance();
}

static jfloat android_view_GLES20DisplayList_getScaleX(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getScaleX();
}

static jfloat android_view_GLES20DisplayList_getScaleY(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getScaleY();
}

static jfloat android_view_GLES20DisplayList_getTranslationX(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getTranslationX();
}

static jfloat android_view_GLES20DisplayList_getTranslationY(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getTranslationY();
}

static jfloat android_view_GLES20DisplayList_getRotation(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getRotation();
}

static jfloat android_view_GLES20DisplayList_getRotationX(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getRotationX();
}

static jfloat android_view_GLES20DisplayList_getRotationY(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getRotationY();
}

static jfloat android_view_GLES20DisplayList_getPivotX(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getPivotX();
}

static jfloat android_view_GLES20DisplayList_getPivotY(JNIEnv* env,
        jobject clazz, jlong displayListHandle) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListHandle);
    return displayList->getPivotY();
}

#endif // USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/GLES20DisplayList";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nDestroyDisplayList",   "(J)V",   (void*) android_view_GLES20DisplayList_destroyDisplayList },
    { "nGetDisplayListSize",   "(J)I",   (void*) android_view_GLES20DisplayList_getDisplayListSize },
    { "nSetDisplayListName",   "(JLjava/lang/String;)V",
            (void*) android_view_GLES20DisplayList_setDisplayListName },

    { "nReset",                "(J)V",   (void*) android_view_GLES20DisplayList_reset },
    { "nSetCaching",           "(JZ)V",  (void*) android_view_GLES20DisplayList_setCaching },
    { "nSetStaticMatrix",      "(JJ)V",  (void*) android_view_GLES20DisplayList_setStaticMatrix },
    { "nSetAnimationMatrix",   "(JJ)V",  (void*) android_view_GLES20DisplayList_setAnimationMatrix },
    { "nSetClipToBounds",      "(JZ)V",  (void*) android_view_GLES20DisplayList_setClipToBounds },
    { "nSetAlpha",             "(JF)V",  (void*) android_view_GLES20DisplayList_setAlpha },
    { "nSetHasOverlappingRendering", "(JZ)V",
            (void*) android_view_GLES20DisplayList_setHasOverlappingRendering },
    { "nSetTranslationX",      "(JF)V",  (void*) android_view_GLES20DisplayList_setTranslationX },
    { "nSetTranslationY",      "(JF)V",  (void*) android_view_GLES20DisplayList_setTranslationY },
    { "nSetRotation",          "(JF)V",  (void*) android_view_GLES20DisplayList_setRotation },
    { "nSetRotationX",         "(JF)V",  (void*) android_view_GLES20DisplayList_setRotationX },
    { "nSetRotationY",         "(JF)V",  (void*) android_view_GLES20DisplayList_setRotationY },
    { "nSetScaleX",            "(JF)V",  (void*) android_view_GLES20DisplayList_setScaleX },
    { "nSetScaleY",            "(JF)V",  (void*) android_view_GLES20DisplayList_setScaleY },
    { "nSetTransformationInfo","(JFFFFFFFF)V",
            (void*) android_view_GLES20DisplayList_setTransformationInfo },
    { "nSetPivotX",            "(JF)V",  (void*) android_view_GLES20DisplayList_setPivotX },
    { "nSetPivotY",            "(JF)V",  (void*) android_view_GLES20DisplayList_setPivotY },
    { "nSetCameraDistance",    "(JF)V",  (void*) android_view_GLES20DisplayList_setCameraDistance },
    { "nSetLeft",              "(JI)V",  (void*) android_view_GLES20DisplayList_setLeft },
    { "nSetTop",               "(JI)V",  (void*) android_view_GLES20DisplayList_setTop },
    { "nSetRight",             "(JI)V",  (void*) android_view_GLES20DisplayList_setRight },
    { "nSetBottom",            "(JI)V",  (void*) android_view_GLES20DisplayList_setBottom },
    { "nSetLeftTopRightBottom","(JIIII)V",
            (void*) android_view_GLES20DisplayList_setLeftTopRightBottom },
    { "nOffsetLeftAndRight",   "(JF)V",  (void*) android_view_GLES20DisplayList_offsetLeftAndRight },
    { "nOffsetTopAndBottom",   "(JF)V",  (void*) android_view_GLES20DisplayList_offsetTopAndBottom },


    { "nGetMatrix",               "(JJ)V", (void*) android_view_GLES20DisplayList_getMatrix },
    { "nHasOverlappingRendering", "(J)Z",  (void*) android_view_GLES20DisplayList_hasOverlappingRendering },
    { "nGetAlpha",                "(J)F",  (void*) android_view_GLES20DisplayList_getAlpha },
    { "nGetLeft",                 "(J)F",  (void*) android_view_GLES20DisplayList_getLeft },
    { "nGetTop",                  "(J)F",  (void*) android_view_GLES20DisplayList_getTop },
    { "nGetRight",                "(J)F",  (void*) android_view_GLES20DisplayList_getRight },
    { "nGetBottom",               "(J)F",  (void*) android_view_GLES20DisplayList_getBottom },
    { "nGetCameraDistance",       "(J)F",  (void*) android_view_GLES20DisplayList_getCameraDistance },
    { "nGetScaleX",               "(J)F",  (void*) android_view_GLES20DisplayList_getScaleX },
    { "nGetScaleY",               "(J)F",  (void*) android_view_GLES20DisplayList_getScaleY },
    { "nGetTranslationX",         "(J)F",  (void*) android_view_GLES20DisplayList_getTranslationX },
    { "nGetTranslationY",         "(J)F",  (void*) android_view_GLES20DisplayList_getTranslationY },
    { "nGetRotation",             "(J)F",  (void*) android_view_GLES20DisplayList_getRotation },
    { "nGetRotationX",            "(J)F",  (void*) android_view_GLES20DisplayList_getRotationX },
    { "nGetRotationY",            "(J)F",  (void*) android_view_GLES20DisplayList_getRotationY },
    { "nGetPivotX",               "(J)F",  (void*) android_view_GLES20DisplayList_getPivotX },
    { "nGetPivotY",               "(J)F",  (void*) android_view_GLES20DisplayList_getPivotY },
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

