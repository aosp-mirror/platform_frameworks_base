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

static void android_view_DisplayList_setDisplayListName(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jstring name) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    if (name != NULL) {
        const char* textArray = env->GetStringUTFChars(name, NULL);
        displayList->setName(textArray);
        env->ReleaseStringUTFChars(name, textArray);
    }
}

static void android_view_DisplayList_output(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->output();
}

static jlong android_view_DisplayList_create(JNIEnv* env, jobject clazz) {
    RenderNode* displayList = new RenderNode();
    return reinterpret_cast<jlong>(displayList);
}

static void android_view_DisplayList_destroyDisplayList(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    RenderNode::destroyDisplayListDeferred(displayList);
}

// ----------------------------------------------------------------------------
// DisplayList view properties
// ----------------------------------------------------------------------------

static void android_view_DisplayList_setCaching(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean caching) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setCaching(caching);
}

static void android_view_DisplayList_setStaticMatrix(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jlong matrixPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    displayList->properties().setStaticMatrix(matrix);
}

static void android_view_DisplayList_setAnimationMatrix(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jlong matrixPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    displayList->properties().setAnimationMatrix(matrix);
}

static void android_view_DisplayList_setClipToBounds(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean clipToBounds) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setClipToBounds(clipToBounds);
}

static void android_view_DisplayList_setIsolatedZVolume(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean shouldIsolate) {
    // No-op, TODO: Remove Java usage of this method
}

static void android_view_DisplayList_setProjectBackwards(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean shouldProject) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setProjectBackwards(shouldProject);
}

static void android_view_DisplayList_setProjectionReceiver(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean shouldRecieve) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setProjectionReceiver(shouldRecieve);
}

static void android_view_DisplayList_setOutline(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jlong outlinePathPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    SkPath* outline = reinterpret_cast<SkPath*>(outlinePathPtr);
    displayList->properties().setOutline(outline);
}

static void android_view_DisplayList_setClipToOutline(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean clipToOutline) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setClipToOutline(clipToOutline);
}

static void android_view_DisplayList_setCastsShadow(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean castsShadow) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setCastsShadow(castsShadow);
}

static void android_view_DisplayList_setUsesGlobalCamera(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean usesGlobalCamera) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setUsesGlobalCamera(usesGlobalCamera);
}

static void android_view_DisplayList_setAlpha(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float alpha) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setAlpha(alpha);
}

static void android_view_DisplayList_setHasOverlappingRendering(JNIEnv* env,
        jobject clazz, jlong displayListPtr, bool hasOverlappingRendering) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setHasOverlappingRendering(hasOverlappingRendering);
}

static void android_view_DisplayList_setTranslationX(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float tx) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setTranslationX(tx);
}

static void android_view_DisplayList_setTranslationY(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float ty) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setTranslationY(ty);
}

static void android_view_DisplayList_setTranslationZ(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float tz) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setTranslationZ(tz);
}

static void android_view_DisplayList_setRotation(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float rotation) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setRotation(rotation);
}

static void android_view_DisplayList_setRotationX(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float rx) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setRotationX(rx);
}

static void android_view_DisplayList_setRotationY(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float ry) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setRotationY(ry);
}

static void android_view_DisplayList_setScaleX(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float sx) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setScaleX(sx);
}

static void android_view_DisplayList_setScaleY(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float sy) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setScaleY(sy);
}

static void android_view_DisplayList_setTransformationInfo(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float alpha,
        float translationX, float translationY, float translationZ,
        float rotation, float rotationX, float rotationY, float scaleX, float scaleY) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setAlpha(alpha);
    displayList->properties().setTranslationX(translationX);
    displayList->properties().setTranslationY(translationY);
    displayList->properties().setTranslationZ(translationZ);
    displayList->properties().setRotation(rotation);
    displayList->properties().setRotationX(rotationX);
    displayList->properties().setRotationY(rotationY);
    displayList->properties().setScaleX(scaleX);
    displayList->properties().setScaleY(scaleY);
}

static void android_view_DisplayList_setPivotX(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float px) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setPivotX(px);
}

static void android_view_DisplayList_setPivotY(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float py) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setPivotY(py);
}

static void android_view_DisplayList_setCameraDistance(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float distance) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setCameraDistance(distance);
}

static void android_view_DisplayList_setLeft(JNIEnv* env,
        jobject clazz, jlong displayListPtr, int left) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setLeft(left);
}

static void android_view_DisplayList_setTop(JNIEnv* env,
        jobject clazz, jlong displayListPtr, int top) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setTop(top);
}

static void android_view_DisplayList_setRight(JNIEnv* env,
        jobject clazz, jlong displayListPtr, int right) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setRight(right);
}

static void android_view_DisplayList_setBottom(JNIEnv* env,
        jobject clazz, jlong displayListPtr, int bottom) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setBottom(bottom);
}

static void android_view_DisplayList_setLeftTopRightBottom(JNIEnv* env,
        jobject clazz, jlong displayListPtr, int left, int top,
        int right, int bottom) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().setLeftTopRightBottom(left, top, right, bottom);
}

static void android_view_DisplayList_offsetLeftAndRight(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float offset) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().offsetLeftRight(offset);
}

static void android_view_DisplayList_offsetTopAndBottom(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float offset) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->properties().offsetTopBottom(offset);
}

static jboolean android_view_DisplayList_hasOverlappingRendering(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().hasOverlappingRendering();
}

static jfloat android_view_DisplayList_getAlpha(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getAlpha();
}

static jfloat android_view_DisplayList_getLeft(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getLeft();
}

static jfloat android_view_DisplayList_getTop(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getTop();
}

static jfloat android_view_DisplayList_getRight(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getRight();
}

static jfloat android_view_DisplayList_getBottom(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getBottom();
}

static jfloat android_view_DisplayList_getCameraDistance(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getCameraDistance();
}

static jfloat android_view_DisplayList_getScaleX(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getScaleX();
}

static jfloat android_view_DisplayList_getScaleY(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getScaleY();
}

static jfloat android_view_DisplayList_getTranslationX(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getTranslationX();
}

static jfloat android_view_DisplayList_getTranslationY(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getTranslationY();
}

static jfloat android_view_DisplayList_getRotation(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getRotation();
}

static jfloat android_view_DisplayList_getRotationX(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getRotationX();
}

static jfloat android_view_DisplayList_getRotationY(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getRotationY();
}

static jfloat android_view_DisplayList_getPivotX(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getPivotX();
}

static jfloat android_view_DisplayList_getPivotY(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->properties().getPivotY();
}

#endif // USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/DisplayList";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nCreate",               "()J",    (void*) android_view_DisplayList_create },
    { "nDestroyDisplayList",   "(J)V",   (void*) android_view_DisplayList_destroyDisplayList },
    { "nSetDisplayListName",   "(JLjava/lang/String;)V",
            (void*) android_view_DisplayList_setDisplayListName },
    { "nOutput",               "(J)V",  (void*) android_view_DisplayList_output },

    { "nSetCaching",           "(JZ)V",  (void*) android_view_DisplayList_setCaching },
    { "nSetStaticMatrix",      "(JJ)V",  (void*) android_view_DisplayList_setStaticMatrix },
    { "nSetAnimationMatrix",   "(JJ)V",  (void*) android_view_DisplayList_setAnimationMatrix },
    { "nSetClipToBounds",      "(JZ)V",  (void*) android_view_DisplayList_setClipToBounds },
    { "nSetIsolatedZVolume",   "(JZ)V",  (void*) android_view_DisplayList_setIsolatedZVolume },
    { "nSetProjectBackwards",  "(JZ)V",  (void*) android_view_DisplayList_setProjectBackwards },
    { "nSetProjectionReceiver","(JZ)V",  (void*) android_view_DisplayList_setProjectionReceiver },
    { "nSetOutline",           "(JJ)V",  (void*) android_view_DisplayList_setOutline },
    { "nSetClipToOutline",     "(JZ)V",  (void*) android_view_DisplayList_setClipToOutline },
    { "nSetCastsShadow",       "(JZ)V",  (void*) android_view_DisplayList_setCastsShadow },
    { "nSetUsesGlobalCamera",  "(JZ)V",  (void*) android_view_DisplayList_setUsesGlobalCamera },
    { "nSetAlpha",             "(JF)V",  (void*) android_view_DisplayList_setAlpha },
    { "nSetHasOverlappingRendering", "(JZ)V",
            (void*) android_view_DisplayList_setHasOverlappingRendering },
    { "nSetTranslationX",      "(JF)V",  (void*) android_view_DisplayList_setTranslationX },
    { "nSetTranslationY",      "(JF)V",  (void*) android_view_DisplayList_setTranslationY },
    { "nSetTranslationZ",      "(JF)V",  (void*) android_view_DisplayList_setTranslationZ },
    { "nSetRotation",          "(JF)V",  (void*) android_view_DisplayList_setRotation },
    { "nSetRotationX",         "(JF)V",  (void*) android_view_DisplayList_setRotationX },
    { "nSetRotationY",         "(JF)V",  (void*) android_view_DisplayList_setRotationY },
    { "nSetScaleX",            "(JF)V",  (void*) android_view_DisplayList_setScaleX },
    { "nSetScaleY",            "(JF)V",  (void*) android_view_DisplayList_setScaleY },
    { "nSetTransformationInfo","(JFFFFFFFFF)V",
            (void*) android_view_DisplayList_setTransformationInfo },
    { "nSetPivotX",            "(JF)V",  (void*) android_view_DisplayList_setPivotX },
    { "nSetPivotY",            "(JF)V",  (void*) android_view_DisplayList_setPivotY },
    { "nSetCameraDistance",    "(JF)V",  (void*) android_view_DisplayList_setCameraDistance },
    { "nSetLeft",              "(JI)V",  (void*) android_view_DisplayList_setLeft },
    { "nSetTop",               "(JI)V",  (void*) android_view_DisplayList_setTop },
    { "nSetRight",             "(JI)V",  (void*) android_view_DisplayList_setRight },
    { "nSetBottom",            "(JI)V",  (void*) android_view_DisplayList_setBottom },
    { "nSetLeftTopRightBottom","(JIIII)V", (void*) android_view_DisplayList_setLeftTopRightBottom },
    { "nOffsetLeftAndRight",   "(JF)V",  (void*) android_view_DisplayList_offsetLeftAndRight },
    { "nOffsetTopAndBottom",   "(JF)V",  (void*) android_view_DisplayList_offsetTopAndBottom },

    { "nHasOverlappingRendering", "(J)Z",  (void*) android_view_DisplayList_hasOverlappingRendering },
    { "nGetAlpha",                "(J)F",  (void*) android_view_DisplayList_getAlpha },
    { "nGetLeft",                 "(J)F",  (void*) android_view_DisplayList_getLeft },
    { "nGetTop",                  "(J)F",  (void*) android_view_DisplayList_getTop },
    { "nGetRight",                "(J)F",  (void*) android_view_DisplayList_getRight },
    { "nGetBottom",               "(J)F",  (void*) android_view_DisplayList_getBottom },
    { "nGetCameraDistance",       "(J)F",  (void*) android_view_DisplayList_getCameraDistance },
    { "nGetScaleX",               "(J)F",  (void*) android_view_DisplayList_getScaleX },
    { "nGetScaleY",               "(J)F",  (void*) android_view_DisplayList_getScaleY },
    { "nGetTranslationX",         "(J)F",  (void*) android_view_DisplayList_getTranslationX },
    { "nGetTranslationY",         "(J)F",  (void*) android_view_DisplayList_getTranslationY },
    { "nGetRotation",             "(J)F",  (void*) android_view_DisplayList_getRotation },
    { "nGetRotationX",            "(J)F",  (void*) android_view_DisplayList_getRotationX },
    { "nGetRotationY",            "(J)F",  (void*) android_view_DisplayList_getRotationY },
    { "nGetPivotX",               "(J)F",  (void*) android_view_DisplayList_getPivotX },
    { "nGetPivotY",               "(J)F",  (void*) android_view_DisplayList_getPivotY },
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

