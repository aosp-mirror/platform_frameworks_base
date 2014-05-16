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

#include <Animator.h>
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

static void android_view_RenderNode_output(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->output();
}

static jlong android_view_RenderNode_create(JNIEnv* env, jobject clazz, jstring name) {
    RenderNode* renderNode = new RenderNode();
    renderNode->incStrong(0);
    if (name != NULL) {
        const char* textArray = env->GetStringUTFChars(name, NULL);
        renderNode->setName(textArray);
        env->ReleaseStringUTFChars(name, textArray);
    }
    return reinterpret_cast<jlong>(renderNode);
}

static void android_view_RenderNode_destroyRenderNode(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->decStrong(0);
}

static void android_view_RenderNode_setDisplayListData(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong newDataPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    DisplayListData* newData = reinterpret_cast<DisplayListData*>(newDataPtr);
    renderNode->setStagingDisplayList(newData);
}

// ----------------------------------------------------------------------------
// RenderProperties - setters
// ----------------------------------------------------------------------------

static void android_view_RenderNode_setCaching(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean caching) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setCaching(caching);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setStaticMatrix(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong matrixPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    renderNode->mutateStagingProperties().setStaticMatrix(matrix);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setAnimationMatrix(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong matrixPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    renderNode->mutateStagingProperties().setAnimationMatrix(matrix);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setClipToBounds(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean clipToBounds) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setClipToBounds(clipToBounds);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setProjectBackwards(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean shouldProject) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setProjectBackwards(shouldProject);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setProjectionReceiver(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean shouldRecieve) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setProjectionReceiver(shouldRecieve);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setOutlineRoundRect(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jint left, jint top,
        jint right, jint bottom, jfloat radius) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setRoundRect(left, top, right, bottom, radius);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setOutlineConvexPath(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong outlinePathPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    SkPath* outlinePath = reinterpret_cast<SkPath*>(outlinePathPtr);
    renderNode->mutateStagingProperties().mutableOutline().setConvexPath(outlinePath);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setOutlineEmpty(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setEmpty();
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setClipToOutline(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean clipToOutline) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setShouldClip(clipToOutline);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setRevealClip(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean shouldClip, jboolean inverseClip,
        jfloat x, jfloat y, jfloat radius) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableRevealClip().set(
            shouldClip, inverseClip, x, y, radius);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setAlpha(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float alpha) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setAlpha(alpha);
    renderNode->setPropertyFieldsDirty(RenderNode::ALPHA);
}

static void android_view_RenderNode_setHasOverlappingRendering(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, bool hasOverlappingRendering) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setHasOverlappingRendering(hasOverlappingRendering);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setElevation(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float elevation) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setElevation(elevation);
    renderNode->setPropertyFieldsDirty(RenderNode::Z);
}

static void android_view_RenderNode_setTranslationX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float tx) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setTranslationX(tx);
    renderNode->setPropertyFieldsDirty(RenderNode::TRANSLATION_X | RenderNode::X);
}

static void android_view_RenderNode_setTranslationY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float ty) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setTranslationY(ty);
    renderNode->setPropertyFieldsDirty(RenderNode::TRANSLATION_Y | RenderNode::Y);
}

static void android_view_RenderNode_setTranslationZ(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float tz) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setTranslationZ(tz);
    renderNode->setPropertyFieldsDirty(RenderNode::TRANSLATION_Z | RenderNode::Z);
}

static void android_view_RenderNode_setRotation(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float rotation) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setRotation(rotation);
    renderNode->setPropertyFieldsDirty(RenderNode::ROTATION);
}

static void android_view_RenderNode_setRotationX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float rx) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setRotationX(rx);
    renderNode->setPropertyFieldsDirty(RenderNode::ROTATION_X);
}

static void android_view_RenderNode_setRotationY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float ry) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setRotationY(ry);
    renderNode->setPropertyFieldsDirty(RenderNode::ROTATION_Y);
}

static void android_view_RenderNode_setScaleX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float sx) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setScaleX(sx);
    renderNode->setPropertyFieldsDirty(RenderNode::SCALE_X);
}

static void android_view_RenderNode_setScaleY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float sy) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setScaleY(sy);
    renderNode->setPropertyFieldsDirty(RenderNode::SCALE_Y);
}

static void android_view_RenderNode_setPivotX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float px) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setPivotX(px);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setPivotY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float py) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setPivotY(py);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setCameraDistance(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float distance) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setCameraDistance(distance);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
}

static void android_view_RenderNode_setLeft(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, int left) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setLeft(left);
    renderNode->setPropertyFieldsDirty(RenderNode::X);
}

static void android_view_RenderNode_setTop(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, int top) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setTop(top);
    renderNode->setPropertyFieldsDirty(RenderNode::Y);
}

static void android_view_RenderNode_setRight(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, int right) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setRight(right);
    renderNode->setPropertyFieldsDirty(RenderNode::X);
}

static void android_view_RenderNode_setBottom(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, int bottom) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setBottom(bottom);
    renderNode->setPropertyFieldsDirty(RenderNode::Y);
}

static void android_view_RenderNode_setLeftTopRightBottom(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, int left, int top,
        int right, int bottom) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().setLeftTopRightBottom(left, top, right, bottom);
    renderNode->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
}

static void android_view_RenderNode_offsetLeftAndRight(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float offset) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().offsetLeftRight(offset);
    renderNode->setPropertyFieldsDirty(RenderNode::X);
}

static void android_view_RenderNode_offsetTopAndBottom(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float offset) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().offsetTopBottom(offset);
    renderNode->setPropertyFieldsDirty(RenderNode::Y);
}

// ----------------------------------------------------------------------------
// RenderProperties - getters
// ----------------------------------------------------------------------------

static jboolean android_view_RenderNode_hasOverlappingRendering(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().hasOverlappingRendering();
}

static jboolean android_view_RenderNode_getClipToOutline(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getOutline().getShouldClip();
}

static jfloat android_view_RenderNode_getAlpha(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getAlpha();
}

static jfloat android_view_RenderNode_getLeft(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getLeft();
}

static jfloat android_view_RenderNode_getTop(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getTop();
}

static jfloat android_view_RenderNode_getRight(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getRight();
}

static jfloat android_view_RenderNode_getBottom(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getBottom();
}

static jfloat android_view_RenderNode_getCameraDistance(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getCameraDistance();
}

static jfloat android_view_RenderNode_getScaleX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getScaleX();
}

static jfloat android_view_RenderNode_getScaleY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getScaleY();
}

static jfloat android_view_RenderNode_getElevation(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getElevation();
}

static jfloat android_view_RenderNode_getTranslationX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getTranslationX();
}

static jfloat android_view_RenderNode_getTranslationY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getTranslationY();
}

static jfloat android_view_RenderNode_getTranslationZ(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getTranslationZ();
}

static jfloat android_view_RenderNode_getRotation(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getRotation();
}

static jfloat android_view_RenderNode_getRotationX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getRotationX();
}

static jfloat android_view_RenderNode_getRotationY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getRotationY();
}

static jboolean android_view_RenderNode_isPivotExplicitlySet(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().isPivotExplicitlySet();
}

static jboolean android_view_RenderNode_hasIdentityMatrix(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().updateMatrix();
    return !renderNode->stagingProperties().hasTransformMatrix();
}

// ----------------------------------------------------------------------------
// RenderProperties - computed getters
// ----------------------------------------------------------------------------

static void android_view_RenderNode_getTransformMatrix(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong outMatrixPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    SkMatrix* outMatrix = reinterpret_cast<SkMatrix*>(outMatrixPtr);

    renderNode->mutateStagingProperties().updateMatrix();
    const SkMatrix* transformMatrix = renderNode->stagingProperties().getTransformMatrix();

    if (transformMatrix) {
        *outMatrix = *transformMatrix;
    } else {
        outMatrix->setIdentity();
    }
}

static void android_view_RenderNode_getInverseTransformMatrix(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong outMatrixPtr) {
    // load transform matrix
    android_view_RenderNode_getTransformMatrix(env, clazz, renderNodePtr, outMatrixPtr);
    SkMatrix* outMatrix = reinterpret_cast<SkMatrix*>(outMatrixPtr);

    // return it inverted
    if (!outMatrix->invert(outMatrix)) {
        // failed to load inverse, pass back identity
        outMatrix->setIdentity();
    }
}

static jfloat android_view_RenderNode_getPivotX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().updateMatrix();
    return renderNode->stagingProperties().getPivotX();
}

static jfloat android_view_RenderNode_getPivotY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().updateMatrix();
    return renderNode->stagingProperties().getPivotY();
}

// ----------------------------------------------------------------------------
// RenderProperties - Animations
// ----------------------------------------------------------------------------

static void android_view_RenderNode_addAnimator(JNIEnv* env, jobject clazz,
        jlong renderNodePtr, jlong animatorPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    RenderPropertyAnimator* animator = reinterpret_cast<RenderPropertyAnimator*>(animatorPtr);
    renderNode->addAnimator(animator);
}

static void android_view_RenderNode_removeAnimator(JNIEnv* env, jobject clazz,
        jlong renderNodePtr, jlong animatorPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    RenderPropertyAnimator* animator = reinterpret_cast<RenderPropertyAnimator*>(animatorPtr);
    renderNode->removeAnimator(animator);
}


#endif // USE_OPENGL_RENDERER

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/RenderNode";

static JNINativeMethod gMethods[] = {
#ifdef USE_OPENGL_RENDERER
    { "nCreate",               "(Ljava/lang/String;)J",    (void*) android_view_RenderNode_create },
    { "nDestroyRenderNode",   "(J)V",   (void*) android_view_RenderNode_destroyRenderNode },
    { "nSetDisplayListData",   "(JJ)V", (void*) android_view_RenderNode_setDisplayListData },
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
    { "nSetRevealClip",        "(JZZFFF)V", (void*) android_view_RenderNode_setRevealClip },

    { "nSetAlpha",             "(JF)V",  (void*) android_view_RenderNode_setAlpha },
    { "nSetHasOverlappingRendering", "(JZ)V",
            (void*) android_view_RenderNode_setHasOverlappingRendering },
    { "nSetElevation",         "(JF)V",  (void*) android_view_RenderNode_setElevation },
    { "nSetTranslationX",      "(JF)V",  (void*) android_view_RenderNode_setTranslationX },
    { "nSetTranslationY",      "(JF)V",  (void*) android_view_RenderNode_setTranslationY },
    { "nSetTranslationZ",      "(JF)V",  (void*) android_view_RenderNode_setTranslationZ },
    { "nSetRotation",          "(JF)V",  (void*) android_view_RenderNode_setRotation },
    { "nSetRotationX",         "(JF)V",  (void*) android_view_RenderNode_setRotationX },
    { "nSetRotationY",         "(JF)V",  (void*) android_view_RenderNode_setRotationY },
    { "nSetScaleX",            "(JF)V",  (void*) android_view_RenderNode_setScaleX },
    { "nSetScaleY",            "(JF)V",  (void*) android_view_RenderNode_setScaleY },
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
    { "nGetClipToOutline",        "(J)Z",  (void*) android_view_RenderNode_getClipToOutline },
    { "nGetAlpha",                "(J)F",  (void*) android_view_RenderNode_getAlpha },
    { "nGetLeft",                 "(J)F",  (void*) android_view_RenderNode_getLeft },
    { "nGetTop",                  "(J)F",  (void*) android_view_RenderNode_getTop },
    { "nGetRight",                "(J)F",  (void*) android_view_RenderNode_getRight },
    { "nGetBottom",               "(J)F",  (void*) android_view_RenderNode_getBottom },
    { "nGetCameraDistance",       "(J)F",  (void*) android_view_RenderNode_getCameraDistance },
    { "nGetScaleX",               "(J)F",  (void*) android_view_RenderNode_getScaleX },
    { "nGetScaleY",               "(J)F",  (void*) android_view_RenderNode_getScaleY },
    { "nGetElevation",            "(J)F",  (void*) android_view_RenderNode_getElevation },
    { "nGetTranslationX",         "(J)F",  (void*) android_view_RenderNode_getTranslationX },
    { "nGetTranslationY",         "(J)F",  (void*) android_view_RenderNode_getTranslationY },
    { "nGetTranslationZ",         "(J)F",  (void*) android_view_RenderNode_getTranslationZ },
    { "nGetRotation",             "(J)F",  (void*) android_view_RenderNode_getRotation },
    { "nGetRotationX",            "(J)F",  (void*) android_view_RenderNode_getRotationX },
    { "nGetRotationY",            "(J)F",  (void*) android_view_RenderNode_getRotationY },
    { "nIsPivotExplicitlySet",    "(J)Z",  (void*) android_view_RenderNode_isPivotExplicitlySet },
    { "nHasIdentityMatrix",       "(J)Z",  (void*) android_view_RenderNode_hasIdentityMatrix },

    { "nGetTransformMatrix",       "(JJ)V", (void*) android_view_RenderNode_getTransformMatrix },
    { "nGetInverseTransformMatrix","(JJ)V", (void*) android_view_RenderNode_getInverseTransformMatrix },

    { "nGetPivotX",                "(J)F",  (void*) android_view_RenderNode_getPivotX },
    { "nGetPivotY",                "(J)F",  (void*) android_view_RenderNode_getPivotY },

    { "nAddAnimator",              "(JJ)V", (void*) android_view_RenderNode_addAnimator },
    { "nRemoveAnimator",           "(JJ)V", (void*) android_view_RenderNode_removeAnimator },
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

