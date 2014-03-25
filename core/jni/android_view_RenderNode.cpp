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
#include <RenderNode.h>

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

static void android_view_RenderNode_setDisplayListName(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jstring name) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    if (name != NULL) {
        const char* textArray = env->GetStringUTFChars(name, NULL);
        displayList->setName(textArray);
        env->ReleaseStringUTFChars(name, textArray);
    }
}

static void android_view_RenderNode_output(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->output();
}

static jlong android_view_RenderNode_create(JNIEnv* env, jobject clazz) {
    RenderNode* displayList = new RenderNode();
    return reinterpret_cast<jlong>(displayList);
}

static void android_view_RenderNode_destroyDisplayList(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    RenderNode::destroyDisplayListDeferred(displayList);
}

// ----------------------------------------------------------------------------
// RenderProperties
// ----------------------------------------------------------------------------

static void android_view_RenderNode_setCaching(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean caching) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setCaching(caching);
}

static void android_view_RenderNode_setStaticMatrix(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jlong matrixPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    displayList->mutateStagingProperties().setStaticMatrix(matrix);
}

static void android_view_RenderNode_setAnimationMatrix(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jlong matrixPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    displayList->mutateStagingProperties().setAnimationMatrix(matrix);
}

static void android_view_RenderNode_setClipToBounds(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean clipToBounds) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setClipToBounds(clipToBounds);
}

static void android_view_RenderNode_setProjectBackwards(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean shouldProject) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setProjectBackwards(shouldProject);
}

static void android_view_RenderNode_setProjectionReceiver(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean shouldRecieve) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setProjectionReceiver(shouldRecieve);
}

static void android_view_RenderNode_setOutlineRoundRect(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jint left, jint top,
        jint right, jint bottom, jfloat radius) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().mutableOutline().setRoundRect(left, top, right, bottom, radius);
}
static void android_view_RenderNode_setOutlineConvexPath(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jlong outlinePathPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    SkPath* outlinePath = reinterpret_cast<SkPath*>(outlinePathPtr);
    displayList->mutateStagingProperties().mutableOutline().setConvexPath(outlinePath);
}
static void android_view_RenderNode_setOutlineEmpty(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().mutableOutline().setEmpty();
}

static void android_view_RenderNode_setClipToOutline(JNIEnv* env,
        jobject clazz, jlong displayListPtr, jboolean clipToOutline) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().mutableOutline().setShouldClip(clipToOutline);
}

static void android_view_RenderNode_setAlpha(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float alpha) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setAlpha(alpha);
}

static void android_view_RenderNode_setHasOverlappingRendering(JNIEnv* env,
        jobject clazz, jlong displayListPtr, bool hasOverlappingRendering) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setHasOverlappingRendering(hasOverlappingRendering);
}

static void android_view_RenderNode_setTranslationX(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float tx) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setTranslationX(tx);
}

static void android_view_RenderNode_setTranslationY(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float ty) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setTranslationY(ty);
}

static void android_view_RenderNode_setTranslationZ(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float tz) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setTranslationZ(tz);
}

static void android_view_RenderNode_setRotation(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float rotation) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setRotation(rotation);
}

static void android_view_RenderNode_setRotationX(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float rx) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setRotationX(rx);
}

static void android_view_RenderNode_setRotationY(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float ry) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setRotationY(ry);
}

static void android_view_RenderNode_setScaleX(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float sx) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setScaleX(sx);
}

static void android_view_RenderNode_setScaleY(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float sy) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setScaleY(sy);
}

static void android_view_RenderNode_setTransformationInfo(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float alpha,
        float translationX, float translationY, float translationZ,
        float rotation, float rotationX, float rotationY, float scaleX, float scaleY) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setAlpha(alpha);
    displayList->mutateStagingProperties().setTranslationX(translationX);
    displayList->mutateStagingProperties().setTranslationY(translationY);
    displayList->mutateStagingProperties().setTranslationZ(translationZ);
    displayList->mutateStagingProperties().setRotation(rotation);
    displayList->mutateStagingProperties().setRotationX(rotationX);
    displayList->mutateStagingProperties().setRotationY(rotationY);
    displayList->mutateStagingProperties().setScaleX(scaleX);
    displayList->mutateStagingProperties().setScaleY(scaleY);
}

static void android_view_RenderNode_setPivotX(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float px) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setPivotX(px);
}

static void android_view_RenderNode_setPivotY(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float py) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setPivotY(py);
}

static void android_view_RenderNode_setCameraDistance(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float distance) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setCameraDistance(distance);
}

static void android_view_RenderNode_setLeft(JNIEnv* env,
        jobject clazz, jlong displayListPtr, int left) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setLeft(left);
}

static void android_view_RenderNode_setTop(JNIEnv* env,
        jobject clazz, jlong displayListPtr, int top) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setTop(top);
}

static void android_view_RenderNode_setRight(JNIEnv* env,
        jobject clazz, jlong displayListPtr, int right) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setRight(right);
}

static void android_view_RenderNode_setBottom(JNIEnv* env,
        jobject clazz, jlong displayListPtr, int bottom) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setBottom(bottom);
}

static void android_view_RenderNode_setLeftTopRightBottom(JNIEnv* env,
        jobject clazz, jlong displayListPtr, int left, int top,
        int right, int bottom) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().setLeftTopRightBottom(left, top, right, bottom);
}

static void android_view_RenderNode_offsetLeftAndRight(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float offset) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().offsetLeftRight(offset);
}

static void android_view_RenderNode_offsetTopAndBottom(JNIEnv* env,
        jobject clazz, jlong displayListPtr, float offset) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().offsetTopBottom(offset);
}

static jboolean android_view_RenderNode_hasOverlappingRendering(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().hasOverlappingRendering();
}

static jfloat android_view_RenderNode_getAlpha(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getAlpha();
}

static jfloat android_view_RenderNode_getLeft(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getLeft();
}

static jfloat android_view_RenderNode_getTop(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getTop();
}

static jfloat android_view_RenderNode_getRight(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getRight();
}

static jfloat android_view_RenderNode_getBottom(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getBottom();
}

static jfloat android_view_RenderNode_getCameraDistance(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getCameraDistance();
}

static jfloat android_view_RenderNode_getScaleX(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getScaleX();
}

static jfloat android_view_RenderNode_getScaleY(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getScaleY();
}

static jfloat android_view_RenderNode_getTranslationX(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getTranslationX();
}

static jfloat android_view_RenderNode_getTranslationY(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getTranslationY();
}

static jfloat android_view_RenderNode_getRotation(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getRotation();
}

static jfloat android_view_RenderNode_getRotationX(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getRotationX();
}

static jfloat android_view_RenderNode_getRotationY(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    return displayList->stagingProperties().getRotationY();
}

static jfloat android_view_RenderNode_getPivotX(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().updateMatrix();
    return displayList->stagingProperties().getPivotX();
}

static jfloat android_view_RenderNode_getPivotY(JNIEnv* env,
        jobject clazz, jlong displayListPtr) {
    RenderNode* displayList = reinterpret_cast<RenderNode*>(displayListPtr);
    displayList->mutateStagingProperties().updateMatrix();
    return displayList->stagingProperties().getPivotY();
}

#endif // USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/RenderNode";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nCreate",               "()J",    (void*) android_view_RenderNode_create },
    { "nDestroyDisplayList",   "(J)V",   (void*) android_view_RenderNode_destroyDisplayList },
    { "nSetDisplayListName",   "(JLjava/lang/String;)V",
            (void*) android_view_RenderNode_setDisplayListName },
    { "nOutput",               "(J)V",  (void*) android_view_RenderNode_output },

    { "nSetCaching",           "(JZ)V",  (void*) android_view_RenderNode_setCaching },
    { "nSetStaticMatrix",      "(JJ)V",  (void*) android_view_RenderNode_setStaticMatrix },
    { "nSetAnimationMatrix",   "(JJ)V",  (void*) android_view_RenderNode_setAnimationMatrix },
    { "nSetClipToBounds",      "(JZ)V",  (void*) android_view_RenderNode_setClipToBounds },
    { "nSetProjectBackwards",  "(JZ)V",  (void*) android_view_RenderNode_setProjectBackwards },
    { "nSetProjectionReceiver","(JZ)V",  (void*) android_view_RenderNode_setProjectionReceiver },

    { "nSetOutlineRoundRect",  "(JIIIIF)V", (void*) android_view_RenderNode_setOutlineRoundRect },
    { "nSetOutlineConvexPath", "(JJ)V",  (void*) android_view_RenderNode_setOutlineConvexPath },
    { "nSetOutlineEmpty",      "(J)V",   (void*) android_view_RenderNode_setOutlineEmpty },
    { "nSetClipToOutline",     "(JZ)V",  (void*) android_view_RenderNode_setClipToOutline },

    { "nSetAlpha",             "(JF)V",  (void*) android_view_RenderNode_setAlpha },
    { "nSetHasOverlappingRendering", "(JZ)V",
            (void*) android_view_RenderNode_setHasOverlappingRendering },
    { "nSetTranslationX",      "(JF)V",  (void*) android_view_RenderNode_setTranslationX },
    { "nSetTranslationY",      "(JF)V",  (void*) android_view_RenderNode_setTranslationY },
    { "nSetTranslationZ",      "(JF)V",  (void*) android_view_RenderNode_setTranslationZ },
    { "nSetRotation",          "(JF)V",  (void*) android_view_RenderNode_setRotation },
    { "nSetRotationX",         "(JF)V",  (void*) android_view_RenderNode_setRotationX },
    { "nSetRotationY",         "(JF)V",  (void*) android_view_RenderNode_setRotationY },
    { "nSetScaleX",            "(JF)V",  (void*) android_view_RenderNode_setScaleX },
    { "nSetScaleY",            "(JF)V",  (void*) android_view_RenderNode_setScaleY },
    { "nSetTransformationInfo","(JFFFFFFFFF)V",
            (void*) android_view_RenderNode_setTransformationInfo },
    { "nSetPivotX",            "(JF)V",  (void*) android_view_RenderNode_setPivotX },
    { "nSetPivotY",            "(JF)V",  (void*) android_view_RenderNode_setPivotY },
    { "nSetCameraDistance",    "(JF)V",  (void*) android_view_RenderNode_setCameraDistance },
    { "nSetLeft",              "(JI)V",  (void*) android_view_RenderNode_setLeft },
    { "nSetTop",               "(JI)V",  (void*) android_view_RenderNode_setTop },
    { "nSetRight",             "(JI)V",  (void*) android_view_RenderNode_setRight },
    { "nSetBottom",            "(JI)V",  (void*) android_view_RenderNode_setBottom },
    { "nSetLeftTopRightBottom","(JIIII)V", (void*) android_view_RenderNode_setLeftTopRightBottom },
    { "nOffsetLeftAndRight",   "(JF)V",  (void*) android_view_RenderNode_offsetLeftAndRight },
    { "nOffsetTopAndBottom",   "(JF)V",  (void*) android_view_RenderNode_offsetTopAndBottom },

    { "nHasOverlappingRendering", "(J)Z",  (void*) android_view_RenderNode_hasOverlappingRendering },
    { "nGetAlpha",                "(J)F",  (void*) android_view_RenderNode_getAlpha },
    { "nGetLeft",                 "(J)F",  (void*) android_view_RenderNode_getLeft },
    { "nGetTop",                  "(J)F",  (void*) android_view_RenderNode_getTop },
    { "nGetRight",                "(J)F",  (void*) android_view_RenderNode_getRight },
    { "nGetBottom",               "(J)F",  (void*) android_view_RenderNode_getBottom },
    { "nGetCameraDistance",       "(J)F",  (void*) android_view_RenderNode_getCameraDistance },
    { "nGetScaleX",               "(J)F",  (void*) android_view_RenderNode_getScaleX },
    { "nGetScaleY",               "(J)F",  (void*) android_view_RenderNode_getScaleY },
    { "nGetTranslationX",         "(J)F",  (void*) android_view_RenderNode_getTranslationX },
    { "nGetTranslationY",         "(J)F",  (void*) android_view_RenderNode_getTranslationY },
    { "nGetRotation",             "(J)F",  (void*) android_view_RenderNode_getRotation },
    { "nGetRotationX",            "(J)F",  (void*) android_view_RenderNode_getRotationX },
    { "nGetRotationY",            "(J)F",  (void*) android_view_RenderNode_getRotationY },
    { "nGetPivotX",               "(J)F",  (void*) android_view_RenderNode_getPivotX },
    { "nGetPivotY",               "(J)F",  (void*) android_view_RenderNode_getPivotY },
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

int register_android_view_RenderNode(JNIEnv* env) {
    return AndroidRuntime::registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods));
}

};

