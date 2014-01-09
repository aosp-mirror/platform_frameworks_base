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

static void android_view_DisplayList_reset(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->reset();
}

static jint android_view_DisplayList_getDisplayListSize(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getSize();
}

static void android_view_DisplayList_setDisplayListName(JNIEnv* env,
        jobject clazz, jint displayListPtr, jstring name) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    if (name != NULL) {
        const char* textArray = env->GetStringUTFChars(name, NULL);
        displayList->setName(textArray);
        env->ReleaseStringUTFChars(name, textArray);
    }
}

static void android_view_DisplayList_output(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->output();
}

static void android_view_DisplayList_destroyDisplayList(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    DisplayList::destroyDisplayListDeferred(displayList);
}

// ----------------------------------------------------------------------------
// DisplayList view properties
// ----------------------------------------------------------------------------

static void android_view_DisplayList_setCaching(JNIEnv* env,
        jobject clazz, jint displayListPtr, jboolean caching) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setCaching(caching);
}

static void android_view_DisplayList_setStaticMatrix(JNIEnv* env,
        jobject clazz, jint displayListPtr, jint matrixPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    displayList->setStaticMatrix(matrix);
}

static void android_view_DisplayList_setAnimationMatrix(JNIEnv* env,
        jobject clazz, jint displayListPtr, jint matrixPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    displayList->setAnimationMatrix(matrix);
}

static void android_view_DisplayList_setClipToBounds(JNIEnv* env,
        jobject clazz, jint displayListPtr, jboolean clipToBounds) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setClipToBounds(clipToBounds);
}

static void android_view_DisplayList_setIsContainedVolume(JNIEnv* env,
        jobject clazz, jint displayListPtr, jboolean isContainedVolume) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setIsContainedVolume(isContainedVolume);
}

static void android_view_DisplayList_setProjectToContainedVolume(JNIEnv* env,
        jobject clazz, jint displayListPtr, jboolean shouldProject) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setProjectToContainedVolume(projectToContainedVolume);
}

static void android_view_DisplayList_setAlpha(JNIEnv* env,
        jobject clazz, jint displayListPtr, float alpha) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setAlpha(alpha);
}

static void android_view_DisplayList_setHasOverlappingRendering(JNIEnv* env,
        jobject clazz, jint displayListPtr, bool hasOverlappingRendering) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setHasOverlappingRendering(hasOverlappingRendering);
}

static void android_view_DisplayList_setTranslationX(JNIEnv* env,
        jobject clazz, jint displayListPtr, float tx) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setTranslationX(tx);
}

static void android_view_DisplayList_setTranslationY(JNIEnv* env,
        jobject clazz, jint displayListPtr, float ty) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setTranslationY(ty);
}

static void android_view_DisplayList_setTranslationZ(JNIEnv* env,
        jobject clazz, jint displayListPtr, float tz) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setTranslationZ(tz);
}

static void android_view_DisplayList_setRotation(JNIEnv* env,
        jobject clazz, jint displayListPtr, float rotation) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setRotation(rotation);
}

static void android_view_DisplayList_setRotationX(JNIEnv* env,
        jobject clazz, jint displayListPtr, float rx) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setRotationX(rx);
}

static void android_view_DisplayList_setRotationY(JNIEnv* env,
        jobject clazz, jint displayListPtr, float ry) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setRotationY(ry);
}

static void android_view_DisplayList_setScaleX(JNIEnv* env,
        jobject clazz, jint displayListPtr, float sx) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setScaleX(sx);
}

static void android_view_DisplayList_setScaleY(JNIEnv* env,
        jobject clazz, jint displayListPtr, float sy) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setScaleY(sy);
}

static void android_view_DisplayList_setTransformationInfo(JNIEnv* env,
        jobject clazz, jint displayListPtr, float alpha,
        float translationX, float translationY, float translationZ,
        float rotation, float rotationX, float rotationY, float scaleX, float scaleY) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setAlpha(alpha);
    displayList->setTranslationX(translationX);
    displayList->setTranslationY(translationY);
    displayList->setTranslationZ(translationZ);
    displayList->setRotation(rotation);
    displayList->setRotationX(rotationX);
    displayList->setRotationY(rotationY);
    displayList->setScaleX(scaleX);
    displayList->setScaleY(scaleY);
}

static void android_view_DisplayList_setPivotX(JNIEnv* env,
        jobject clazz, jint displayListPtr, float px) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setPivotX(px);
}

static void android_view_DisplayList_setPivotY(JNIEnv* env,
        jobject clazz, jint displayListPtr, float py) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setPivotY(py);
}

static void android_view_DisplayList_setCameraDistance(JNIEnv* env,
        jobject clazz, jint displayListPtr, float distance) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setCameraDistance(distance);
}

static void android_view_DisplayList_setLeft(JNIEnv* env,
        jobject clazz, jint displayListPtr, int left) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setLeft(left);
}

static void android_view_DisplayList_setTop(JNIEnv* env,
        jobject clazz, jint displayListPtr, int top) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setTop(top);
}

static void android_view_DisplayList_setRight(JNIEnv* env,
        jobject clazz, jint displayListPtr, int right) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setRight(right);
}

static void android_view_DisplayList_setBottom(JNIEnv* env,
        jobject clazz, jint displayListPtr, int bottom) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setBottom(bottom);
}

static void android_view_DisplayList_setLeftTopRightBottom(JNIEnv* env,
        jobject clazz, jint displayListPtr, int left, int top,
        int right, int bottom) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->setLeftTopRightBottom(left, top, right, bottom);
}

static void android_view_DisplayList_offsetLeftAndRight(JNIEnv* env,
        jobject clazz, jint displayListPtr, float offset) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->offsetLeftRight(offset);
}

static void android_view_DisplayList_offsetTopAndBottom(JNIEnv* env,
        jobject clazz, jint displayListPtr, float offset) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    displayList->offsetTopBottom(offset);
}

static void android_view_DisplayList_getMatrix(JNIEnv* env,
        jobject clazz, jint displayListPtr, jint matrixPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    SkMatrix* source = displayList->getStaticMatrix();
    if (source) {
        matrix->setConcat(SkMatrix::I(), *source);
    } else {
        matrix->setIdentity();
    }
}

static jboolean android_view_DisplayList_hasOverlappingRendering(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->hasOverlappingRendering();
}

static jfloat android_view_DisplayList_getAlpha(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getAlpha();
}

static jfloat android_view_DisplayList_getLeft(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getLeft();
}

static jfloat android_view_DisplayList_getTop(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getTop();
}

static jfloat android_view_DisplayList_getRight(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getRight();
}

static jfloat android_view_DisplayList_getBottom(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getBottom();
}

static jfloat android_view_DisplayList_getCameraDistance(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getCameraDistance();
}

static jfloat android_view_DisplayList_getScaleX(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getScaleX();
}

static jfloat android_view_DisplayList_getScaleY(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getScaleY();
}

static jfloat android_view_DisplayList_getTranslationX(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getTranslationX();
}

static jfloat android_view_DisplayList_getTranslationY(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getTranslationY();
}

static jfloat android_view_DisplayList_getRotation(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getRotation();
}

static jfloat android_view_DisplayList_getRotationX(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getRotationX();
}

static jfloat android_view_DisplayList_getRotationY(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getRotationY();
}

static jfloat android_view_DisplayList_getPivotX(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getPivotX();
}

static jfloat android_view_DisplayList_getPivotY(JNIEnv* env,
        jobject clazz, jint displayListPtr) {
    DisplayList* displayList = reinterpret_cast<DisplayList*>(displayListPtr);
    return displayList->getPivotY();
}

#endif // USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/DisplayList";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nDestroyDisplayList",   "(I)V",   (void*) android_view_DisplayList_destroyDisplayList },
    { "nGetDisplayListSize",   "(I)I",   (void*) android_view_DisplayList_getDisplayListSize },
    { "nSetDisplayListName",   "(ILjava/lang/String;)V",
            (void*) android_view_DisplayList_setDisplayListName },
    { "nOutput",               "(I)V",  (void*) android_view_DisplayList_output },

    { "nReset",                "(I)V",   (void*) android_view_DisplayList_reset },
    { "nSetCaching",           "(IZ)V",  (void*) android_view_DisplayList_setCaching },
    { "nSetStaticMatrix",      "(II)V",  (void*) android_view_DisplayList_setStaticMatrix },
    { "nSetAnimationMatrix",   "(II)V",  (void*) android_view_DisplayList_setAnimationMatrix },
    { "nSetClipToBounds",      "(IZ)V",  (void*) android_view_DisplayList_setClipToBounds },
    { "nSetIsContainedVolume", "(IZ)V",  (void*) android_view_DisplayList_setIsContainedVolume },
    { "nSetProjectToContainedVolume", "(IZ)V",
            (void*) android_view_DisplayList_setProjectToContainedVolume },
    { "nSetAlpha",             "(IF)V",  (void*) android_view_DisplayList_setAlpha },
    { "nSetHasOverlappingRendering", "(IZ)V",
            (void*) android_view_DisplayList_setHasOverlappingRendering },
    { "nSetTranslationX",      "(IF)V",  (void*) android_view_DisplayList_setTranslationX },
    { "nSetTranslationY",      "(IF)V",  (void*) android_view_DisplayList_setTranslationY },
    { "nSetTranslationZ",      "(IF)V",  (void*) android_view_DisplayList_setTranslationZ },
    { "nSetRotation",          "(IF)V",  (void*) android_view_DisplayList_setRotation },
    { "nSetRotationX",         "(IF)V",  (void*) android_view_DisplayList_setRotationX },
    { "nSetRotationY",         "(IF)V",  (void*) android_view_DisplayList_setRotationY },
    { "nSetScaleX",            "(IF)V",  (void*) android_view_DisplayList_setScaleX },
    { "nSetScaleY",            "(IF)V",  (void*) android_view_DisplayList_setScaleY },
    { "nSetTransformationInfo","(IFFFFFFFFF)V",
            (void*) android_view_DisplayList_setTransformationInfo },
    { "nSetPivotX",            "(IF)V",  (void*) android_view_DisplayList_setPivotX },
    { "nSetPivotY",            "(IF)V",  (void*) android_view_DisplayList_setPivotY },
    { "nSetCameraDistance",    "(IF)V",  (void*) android_view_DisplayList_setCameraDistance },
    { "nSetLeft",              "(II)V",  (void*) android_view_DisplayList_setLeft },
    { "nSetTop",               "(II)V",  (void*) android_view_DisplayList_setTop },
    { "nSetRight",             "(II)V",  (void*) android_view_DisplayList_setRight },
    { "nSetBottom",            "(II)V",  (void*) android_view_DisplayList_setBottom },
    { "nSetLeftTopRightBottom","(IIIII)V", (void*) android_view_DisplayList_setLeftTopRightBottom },
    { "nOffsetLeftAndRight",   "(IF)V",  (void*) android_view_DisplayList_offsetLeftAndRight },
    { "nOffsetTopAndBottom",   "(IF)V",  (void*) android_view_DisplayList_offsetTopAndBottom },

    { "nGetMatrix",               "(II)V", (void*) android_view_DisplayList_getMatrix },
    { "nHasOverlappingRendering", "(I)Z",  (void*) android_view_DisplayList_hasOverlappingRendering },
    { "nGetAlpha",                "(I)F",  (void*) android_view_DisplayList_getAlpha },
    { "nGetLeft",                 "(I)F",  (void*) android_view_DisplayList_getLeft },
    { "nGetTop",                  "(I)F",  (void*) android_view_DisplayList_getTop },
    { "nGetRight",                "(I)F",  (void*) android_view_DisplayList_getRight },
    { "nGetBottom",               "(I)F",  (void*) android_view_DisplayList_getBottom },
    { "nGetCameraDistance",       "(I)F",  (void*) android_view_DisplayList_getCameraDistance },
    { "nGetScaleX",               "(I)F",  (void*) android_view_DisplayList_getScaleX },
    { "nGetScaleY",               "(I)F",  (void*) android_view_DisplayList_getScaleY },
    { "nGetTranslationX",         "(I)F",  (void*) android_view_DisplayList_getTranslationX },
    { "nGetTranslationY",         "(I)F",  (void*) android_view_DisplayList_getTranslationY },
    { "nGetRotation",             "(I)F",  (void*) android_view_DisplayList_getRotation },
    { "nGetRotationX",            "(I)F",  (void*) android_view_DisplayList_getRotationX },
    { "nGetRotationY",            "(I)F",  (void*) android_view_DisplayList_getRotationY },
    { "nGetPivotX",               "(I)F",  (void*) android_view_DisplayList_getPivotX },
    { "nGetPivotY",               "(I)F",  (void*) android_view_DisplayList_getPivotY },
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

int register_android_view_DisplayList(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};

