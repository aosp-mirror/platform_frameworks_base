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

#define ATRACE_TAG ATRACE_TAG_VIEW
#include "GraphicsJNI.h"

#include <Animator.h>
#include <DamageAccumulator.h>
#include <Matrix.h>
#include <RenderNode.h>
#ifdef __ANDROID__ // Layoutlib does not support CanvasContext
#include <renderthread/CanvasContext.h>
#endif
#include <TreeInfo.h>
#include <effects/StretchEffect.h>
#include <gui/TraceUtils.h>
#include <hwui/Paint.h>

namespace android {

using namespace uirenderer;

#define SET_AND_DIRTY(prop, val, dirtyFlag) \
    (reinterpret_cast<RenderNode*>(renderNodePtr)->mutateStagingProperties().prop(val) \
        ? (reinterpret_cast<RenderNode*>(renderNodePtr)->setPropertyFieldsDirty(dirtyFlag), true) \
        : false)

// ----------------------------------------------------------------------------
// DisplayList view properties
// ----------------------------------------------------------------------------

static void android_view_RenderNode_output(JNIEnv* env, jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->output();
}

static jint android_view_RenderNode_getUsageSize(JNIEnv* env, jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->getUsageSize();
}

static jint android_view_RenderNode_getAllocatedSize(JNIEnv* env, jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->getAllocatedSize();
}

static jlong android_view_RenderNode_create(JNIEnv* env, jobject, jstring name) {
    RenderNode* renderNode = new RenderNode();
    renderNode->incStrong(0);
    if (name != NULL) {
        const char* textArray = env->GetStringUTFChars(name, NULL);
        renderNode->setName(textArray);
        env->ReleaseStringUTFChars(name, textArray);
    }
    return reinterpret_cast<jlong>(renderNode);
}

static void releaseRenderNode(RenderNode* renderNode) {
    renderNode->decStrong(0);
}

static jlong android_view_RenderNode_getNativeFinalizer(JNIEnv* env,
        jobject clazz) {
    return static_cast<jlong>(reinterpret_cast<uintptr_t>(&releaseRenderNode));
}

static void android_view_RenderNode_discardDisplayList(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->discardStagingDisplayList();
}

static jboolean android_view_RenderNode_isValid(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->isValid();
}

// ----------------------------------------------------------------------------
// RenderProperties - setters
// ----------------------------------------------------------------------------

static jboolean android_view_RenderNode_setLayerType(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jint jlayerType) {
    LayerType layerType = static_cast<LayerType>(jlayerType);
    return SET_AND_DIRTY(mutateLayerProperties().setType, layerType, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setLayerPaint(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jlong paintPtr) {
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    return SET_AND_DIRTY(mutateLayerProperties().setFromPaint, paint, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setStaticMatrix(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jlong matrixPtr) {
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    return SET_AND_DIRTY(setStaticMatrix, matrix, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setAnimationMatrix(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jlong matrixPtr) {
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    return SET_AND_DIRTY(setAnimationMatrix, matrix, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setClipToBounds(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jboolean clipToBounds) {
    return SET_AND_DIRTY(setClipToBounds, clipToBounds, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setClipBounds(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jint left, jint top, jint right, jint bottom) {
    android::uirenderer::Rect clipBounds(left, top, right, bottom);
    return SET_AND_DIRTY(setClipBounds, clipBounds, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setClipBoundsEmpty(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return SET_AND_DIRTY(setClipBoundsEmpty,, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setProjectBackwards(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jboolean shouldProject) {
    return SET_AND_DIRTY(setProjectBackwards, shouldProject, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setProjectionReceiver(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jboolean shouldRecieve) {
    return SET_AND_DIRTY(setProjectionReceiver, shouldRecieve, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setOutlineRoundRect(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jint left, jint top, jint right, jint bottom, jfloat radius, jfloat alpha) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setRoundRect(left, top, right, bottom,
            radius, alpha);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setOutlinePath(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jlong outlinePathPtr, jfloat alpha) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    SkPath* outlinePath = reinterpret_cast<SkPath*>(outlinePathPtr);
    renderNode->mutateStagingProperties().mutableOutline().setPath(outlinePath, alpha);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setOutlineEmpty(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setEmpty();
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setOutlineNone(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setNone();
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_clearStretch(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    auto& stretch = renderNode->mutateStagingProperties()
            .mutateLayerProperties().mutableStretchEffect();
    if (stretch.isEmpty()) {
        return false;
    }
    stretch.setEmpty();
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_stretch(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
                                                jfloat vX, jfloat vY, jfloat maxX,
                                                jfloat maxY) {
    auto* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    StretchEffect effect = StretchEffect({.fX = vX, .fY = vY}, maxX, maxY);
    renderNode->mutateStagingProperties().mutateLayerProperties().mutableStretchEffect().mergeWith(
            effect);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_hasShadow(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().hasShadow();
}

static jboolean android_view_RenderNode_setSpotShadowColor(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jint shadowColor) {
    return SET_AND_DIRTY(setSpotShadowColor,
            static_cast<SkColor>(shadowColor), RenderNode::GENERIC);
}

static jint android_view_RenderNode_getSpotShadowColor(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getSpotShadowColor();
}

static jboolean android_view_RenderNode_setAmbientShadowColor(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jint shadowColor) {
    return SET_AND_DIRTY(setAmbientShadowColor,
            static_cast<SkColor>(shadowColor), RenderNode::GENERIC);
}

static jint android_view_RenderNode_getAmbientShadowColor(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getAmbientShadowColor();
}

static jboolean android_view_RenderNode_setClipToOutline(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jboolean clipToOutline) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setShouldClip(clipToOutline);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setRevealClip(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jboolean shouldClip,
        jfloat x, jfloat y, jfloat radius) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableRevealClip().set(
            shouldClip, x, y, radius);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setAlpha(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float alpha) {
    return SET_AND_DIRTY(setAlpha, alpha, RenderNode::ALPHA);
}

static jboolean android_view_RenderNode_setRenderEffect(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jlong renderEffectPtr) {
    SkImageFilter* imageFilter = reinterpret_cast<SkImageFilter*>(renderEffectPtr);
    return SET_AND_DIRTY(mutateLayerProperties().setImageFilter, imageFilter, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setBackdropRenderEffect(
        CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jlong renderEffectPtr) {
    SkImageFilter* imageFilter = reinterpret_cast<SkImageFilter*>(renderEffectPtr);
    return SET_AND_DIRTY(mutateLayerProperties().setBackdropImageFilter, imageFilter,
                         RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setHasOverlappingRendering(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        bool hasOverlappingRendering) {
    return SET_AND_DIRTY(setHasOverlappingRendering, hasOverlappingRendering,
            RenderNode::GENERIC);
}

static void android_view_RenderNode_setUsageHint(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jint usageHint) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->setUsageHint(static_cast<UsageHint>(usageHint));
}

static jboolean android_view_RenderNode_setElevation(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float elevation) {
    return SET_AND_DIRTY(setElevation, elevation, RenderNode::Z);
}

static jboolean android_view_RenderNode_setTranslationX(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float tx) {
    return SET_AND_DIRTY(setTranslationX, tx, RenderNode::TRANSLATION_X | RenderNode::X);
}

static jboolean android_view_RenderNode_setTranslationY(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float ty) {
    return SET_AND_DIRTY(setTranslationY, ty, RenderNode::TRANSLATION_Y | RenderNode::Y);
}

static jboolean android_view_RenderNode_setTranslationZ(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float tz) {
    return SET_AND_DIRTY(setTranslationZ, tz, RenderNode::TRANSLATION_Z | RenderNode::Z);
}

static jboolean android_view_RenderNode_setRotation(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float rotation) {
    return SET_AND_DIRTY(setRotation, rotation, RenderNode::ROTATION);
}

static jboolean android_view_RenderNode_setRotationX(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float rx) {
    return SET_AND_DIRTY(setRotationX, rx, RenderNode::ROTATION_X);
}

static jboolean android_view_RenderNode_setRotationY(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float ry) {
    return SET_AND_DIRTY(setRotationY, ry, RenderNode::ROTATION_Y);
}

static jboolean android_view_RenderNode_setScaleX(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float sx) {
    return SET_AND_DIRTY(setScaleX, sx, RenderNode::SCALE_X);
}

static jboolean android_view_RenderNode_setScaleY(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float sy) {
    return SET_AND_DIRTY(setScaleY, sy, RenderNode::SCALE_Y);
}

static jboolean android_view_RenderNode_setPivotX(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float px) {
    return SET_AND_DIRTY(setPivotX, px, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setPivotY(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float py) {
    return SET_AND_DIRTY(setPivotY, py, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_resetPivot(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return SET_AND_DIRTY(resetPivot, /* void */, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setCameraDistance(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, float distance) {
    return SET_AND_DIRTY(setCameraDistance, distance, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setLeft(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, int left) {
    return SET_AND_DIRTY(setLeft, left, RenderNode::X);
}

static jboolean android_view_RenderNode_setTop(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, int top) {
    return SET_AND_DIRTY(setTop, top, RenderNode::Y);
}

static jboolean android_view_RenderNode_setRight(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, int right) {
    return SET_AND_DIRTY(setRight, right, RenderNode::X);
}

static jboolean android_view_RenderNode_setBottom(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, int bottom) {
    return SET_AND_DIRTY(setBottom, bottom, RenderNode::Y);
}

static jint android_view_RenderNode_getLeft(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getLeft();
}

static jint android_view_RenderNode_getTop(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getTop();
}

static jint android_view_RenderNode_getRight(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getRight();
}

static jint android_view_RenderNode_getBottom(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getBottom();
}

static jboolean android_view_RenderNode_setLeftTopRightBottom(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        int left, int top, int right, int bottom) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    if (renderNode->mutateStagingProperties().setLeftTopRightBottom(left, top, right, bottom)) {
        renderNode->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        return true;
    }
    return false;
}

static jboolean android_view_RenderNode_offsetLeftAndRight(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jint offset) {
    return SET_AND_DIRTY(offsetLeftRight, offset, RenderNode::X);
}

static jboolean android_view_RenderNode_offsetTopAndBottom(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jint offset) {
    return SET_AND_DIRTY(offsetTopBottom, offset, RenderNode::Y);
}

// ----------------------------------------------------------------------------
// RenderProperties - getters
// ----------------------------------------------------------------------------

static jboolean android_view_RenderNode_hasOverlappingRendering(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().hasOverlappingRendering();
}

static jboolean android_view_RenderNode_getAnimationMatrix(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jlong outMatrixPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    SkMatrix* outMatrix = reinterpret_cast<SkMatrix*>(outMatrixPtr);

    const SkMatrix* animationMatrix = renderNode->stagingProperties().getAnimationMatrix();

    if (animationMatrix) {
        *outMatrix = *animationMatrix;
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_view_RenderNode_getClipToBounds(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getClipToBounds();
}

static jboolean android_view_RenderNode_getClipToOutline(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getOutline().getShouldClip();
}

static jfloat android_view_RenderNode_getAlpha(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getAlpha();
}

static jfloat android_view_RenderNode_getCameraDistance(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getCameraDistance();
}

static jfloat android_view_RenderNode_getScaleX(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getScaleX();
}

static jfloat android_view_RenderNode_getScaleY(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getScaleY();
}

static jfloat android_view_RenderNode_getElevation(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getElevation();
}

static jfloat android_view_RenderNode_getTranslationX(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getTranslationX();
}

static jfloat android_view_RenderNode_getTranslationY(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getTranslationY();
}

static jfloat android_view_RenderNode_getTranslationZ(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getTranslationZ();
}

static jfloat android_view_RenderNode_getRotation(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getRotation();
}

static jfloat android_view_RenderNode_getRotationX(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getRotationX();
}

static jfloat android_view_RenderNode_getRotationY(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getRotationY();
}

static jboolean android_view_RenderNode_isPivotExplicitlySet(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().isPivotExplicitlySet();
}

static jboolean android_view_RenderNode_hasIdentityMatrix(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().updateMatrix();
    return !renderNode->stagingProperties().hasTransformMatrix();
}

static jint android_view_RenderNode_getLayerType(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return static_cast<int>(renderNode->stagingProperties().layerProperties().type());
}

// ----------------------------------------------------------------------------
// RenderProperties - computed getters
// ----------------------------------------------------------------------------

static void getTransformMatrix(jlong renderNodePtr, jlong outMatrixPtr) {
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

static void android_view_RenderNode_getTransformMatrix(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jlong outMatrixPtr) {
    getTransformMatrix(renderNodePtr, outMatrixPtr);
}

static void android_view_RenderNode_getInverseTransformMatrix(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr,
        jlong outMatrixPtr) {
    // load transform matrix
    getTransformMatrix(renderNodePtr, outMatrixPtr);
    SkMatrix* outMatrix = reinterpret_cast<SkMatrix*>(outMatrixPtr);

    // return it inverted
    if (!outMatrix->invert(outMatrix)) {
        // failed to load inverse, pass back identity
        outMatrix->setIdentity();
    }
}

static jfloat android_view_RenderNode_getPivotX(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().updateMatrix();
    return renderNode->stagingProperties().getPivotX();
}

static jfloat android_view_RenderNode_getPivotY(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().updateMatrix();
    return renderNode->stagingProperties().getPivotY();
}

static jint android_view_RenderNode_getWidth(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getWidth();
}

static jint android_view_RenderNode_getHeight(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getHeight();
}

static jboolean android_view_RenderNode_setAllowForceDark(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr, jboolean allow) {
    return SET_AND_DIRTY(setAllowForceDark, allow, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_getAllowForceDark(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getAllowForceDark();
}

static jlong android_view_RenderNode_getUniqueId(CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->uniqueId();
}

static void android_view_RenderNode_setIsTextureView(
        CRITICAL_JNI_PARAMS_COMMA jlong renderNodePtr) {
    reinterpret_cast<RenderNode*>(renderNodePtr)->setIsTextureView();
}

// ----------------------------------------------------------------------------
// RenderProperties - Animations
// ----------------------------------------------------------------------------

static void android_view_RenderNode_addAnimator(JNIEnv* env, jobject clazz, jlong renderNodePtr,
        jlong animatorPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    RenderPropertyAnimator* animator = reinterpret_cast<RenderPropertyAnimator*>(animatorPtr);
    renderNode->addAnimator(animator);
}

static void android_view_RenderNode_endAllAnimators(JNIEnv* env, jobject clazz,
        jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->animators().endAllStagingAnimators();
}

static void android_view_RenderNode_forceEndAnimators(JNIEnv* env, jobject clazz,
                                                      jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->animators().forceEndAnimators();
}

// ----------------------------------------------------------------------------
// SurfaceView position callback
// ----------------------------------------------------------------------------

struct {
    jclass clazz;
    jmethodID callPositionChanged;
    jmethodID callPositionChanged2;
    jmethodID callApplyStretch;
    jmethodID callPositionLost;
} gPositionListener;

static void android_view_RenderNode_requestPositionUpdates(JNIEnv* env, jobject,
        jlong renderNodePtr, jobject listener) {
    class PositionListenerTrampoline : public RenderNode::PositionListener {
    public:
        PositionListenerTrampoline(JNIEnv* env, jobject listener) {
            env->GetJavaVM(&mVm);
            mListener = env->NewGlobalRef(listener);
        }

        virtual ~PositionListenerTrampoline() {
            jnienv()->DeleteGlobalRef(mListener);
            mListener = nullptr;
        }

        virtual void onPositionUpdated(RenderNode& node, const TreeInfo& info) override {
            if (CC_UNLIKELY(!mListener || !info.updateWindowPositions)) return;

            const RenderProperties& props = node.properties();
            const bool enableClip = Properties::clipSurfaceViews;

            Matrix4 transform;
            SkIRect clipBounds;
            if (enableClip) {
                uirenderer::Rect initialClipBounds;
                const auto clipFlags = props.getClippingFlags();
                if (clipFlags) {
                    props.getClippingRectForFlags(clipFlags, &initialClipBounds);
                } else {
                    // Works for RenderNode::damageSelf()
                    initialClipBounds.set(DIRTY_MIN, DIRTY_MIN, DIRTY_MAX, DIRTY_MAX);
                }
                clipBounds =
                        info.damageAccumulator
                                ->computeClipAndTransform(initialClipBounds.toSkRect(), &transform)
                                .roundOut();
            } else {
                info.damageAccumulator->computeCurrentTransform(&transform);
            }
            bool useStretchShader =
                    Properties::getStretchEffectBehavior() != StretchEffectBehavior::UniformScale;
            // Compute the transform bounds first before calculating the stretch
            uirenderer::Rect bounds(props.getWidth(), props.getHeight());
            transform.mapRect(bounds);

            bool hasStretch = useStretchShader && info.stretchEffectCount;
            if (hasStretch) {
                handleStretchEffect(info, bounds);
            }

            if (CC_LIKELY(transform.isPureTranslate()) && !hasStretch) {
                // snap/round the computed bounds, so they match the rounding behavior
                // of the clear done in SurfaceView#draw().
                bounds.snapGeometryToPixelBoundaries(false);
            } else {
                // Conservatively round out so the punched hole (in the ZOrderOnTop = true case)
                // doesn't extend beyond the other window
                bounds.roundOut();
            }

            if (mPreviousPosition == bounds && mPreviousClip == clipBounds) {
                return;
            }
            mPreviousPosition = bounds;
            mPreviousClip = clipBounds;

            ATRACE_NAME("Update SurfaceView position");

#ifdef __ANDROID__ // Layoutlib does not support CanvasContext
            JNIEnv* env = jnienv();
            // Update the new position synchronously. We cannot defer this to
            // a worker pool to process asynchronously because the UI thread
            // may be unblocked by the time a worker thread can process this,
            // In particular if the app removes a view from the view tree before
            // this callback is dispatched, then we lose the position
            // information for this frame.
            jboolean keepListening;
            if (!enableClip) {
                keepListening = env->CallStaticBooleanMethod(
                        gPositionListener.clazz, gPositionListener.callPositionChanged, mListener,
                        static_cast<jlong>(info.canvasContext.getFrameNumber()),
                        static_cast<jint>(bounds.left), static_cast<jint>(bounds.top),
                        static_cast<jint>(bounds.right), static_cast<jint>(bounds.bottom));
            } else {
                keepListening = env->CallStaticBooleanMethod(
                        gPositionListener.clazz, gPositionListener.callPositionChanged2, mListener,
                        static_cast<jlong>(info.canvasContext.getFrameNumber()),
                        static_cast<jint>(bounds.left), static_cast<jint>(bounds.top),
                        static_cast<jint>(bounds.right), static_cast<jint>(bounds.bottom),
                        static_cast<jint>(clipBounds.fLeft), static_cast<jint>(clipBounds.fTop),
                        static_cast<jint>(clipBounds.fRight),
                        static_cast<jint>(clipBounds.fBottom));
            }
            if (!keepListening) {
                env->DeleteGlobalRef(mListener);
                mListener = nullptr;
            }
#endif
        }

        virtual void onPositionLost(RenderNode& node, const TreeInfo* info) override {
            if (CC_UNLIKELY(!mListener || (info && !info->updateWindowPositions))) return;

            if (mPreviousPosition.isEmpty()) {
                return;
            }
            mPreviousPosition.setEmpty();

            ATRACE_NAME("SurfaceView position lost");
            JNIEnv* env = jnienv();
#ifdef __ANDROID__ // Layoutlib does not support CanvasContext
            // Update the lost position synchronously. We cannot defer this to
            // a worker pool to process asynchronously because the UI thread
            // may be unblocked by the time a worker thread can process this,
            // In particular if a view's rendernode is readded to the scene
            // before this callback is dispatched, then we report that we lost
            // position information on the wrong frame, which can be problematic
            // for views like SurfaceView which rely on RenderNode callbacks
            // for driving visibility.
            jboolean keepListening = env->CallStaticBooleanMethod(
                    gPositionListener.clazz, gPositionListener.callPositionLost, mListener,
                    info ? info->canvasContext.getFrameNumber() : 0);
            if (!keepListening) {
                env->DeleteGlobalRef(mListener);
                mListener = nullptr;
            }
#endif
        }

    private:
        JNIEnv* jnienv() {
            JNIEnv* env;
            if (mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", mVm);
            }
            return env;
        }

        void handleStretchEffect(const TreeInfo& info, uirenderer::Rect& targetBounds) {
            // Search up to find the nearest stretcheffect parent
            const DamageAccumulator::StretchResult result =
                info.damageAccumulator->findNearestStretchEffect();
            const StretchEffect* effect = result.stretchEffect;
            if (effect) {
                // Compute the number of pixels that the stretching container
                // scales by.
                // Then compute the scale factor that the child would need
                // to scale in order to occupy the same pixel bounds.
                auto& parentBounds = result.parentBounds;
                auto parentWidth = parentBounds.width();
                auto parentHeight = parentBounds.height();
                auto& stretchDirection = effect->getStretchDirection();
                auto stretchX = stretchDirection.x();
                auto stretchY = stretchDirection.y();
                auto stretchXPixels = parentWidth * std::abs(stretchX);
                auto stretchYPixels = parentHeight * std::abs(stretchY);
                SkMatrix stretchMatrix;

                auto childScaleX = 1 + (stretchXPixels / targetBounds.getWidth());
                auto childScaleY = 1 + (stretchYPixels / targetBounds.getHeight());
                auto pivotX = stretchX > 0 ? targetBounds.left : targetBounds.right;
                auto pivotY = stretchY > 0 ? targetBounds.top : targetBounds.bottom;
                stretchMatrix.setScale(childScaleX, childScaleY, pivotX, pivotY);
                SkRect rect = SkRect::MakeLTRB(targetBounds.left, targetBounds.top,
                                               targetBounds.right, targetBounds.bottom);
                SkRect dst = stretchMatrix.mapRect(rect);
                targetBounds.left = dst.left();
                targetBounds.top = dst.top();
                targetBounds.right = dst.right();
                targetBounds.bottom = dst.bottom();
            } else {
                return;
            }

            if (Properties::getStretchEffectBehavior() ==
                StretchEffectBehavior::Shader) {
                JNIEnv* env = jnienv();

#ifdef __ANDROID__  // Layoutlib does not support CanvasContext
                SkVector stretchDirection = effect->getStretchDirection();
                jboolean keepListening = env->CallStaticBooleanMethod(
                        gPositionListener.clazz, gPositionListener.callApplyStretch, mListener,
                        info.canvasContext.getFrameNumber(), result.width, result.height,
                        stretchDirection.fX, stretchDirection.fY, effect->maxStretchAmountX,
                        effect->maxStretchAmountY, targetBounds.left, targetBounds.top,
                        targetBounds.right, targetBounds.bottom);
                if (!keepListening) {
                    env->DeleteGlobalRef(mListener);
                    mListener = nullptr;
                }
#endif
            }
        }

        JavaVM* mVm;
        jobject mListener;
        uirenderer::Rect mPreviousPosition;
        uirenderer::Rect mPreviousClip;
    };

    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->setPositionListener(new PositionListenerTrampoline(env, listener));
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/graphics/RenderNode";

static const JNINativeMethod gMethods[] = {
        // ----------------------------------------------------------------------------
        // Regular JNI
        // ----------------------------------------------------------------------------
        {"nCreate", "(Ljava/lang/String;)J", (void*)android_view_RenderNode_create},
        {"nGetNativeFinalizer", "()J", (void*)android_view_RenderNode_getNativeFinalizer},
        {"nOutput", "(J)V", (void*)android_view_RenderNode_output},
        {"nGetUsageSize", "(J)I", (void*)android_view_RenderNode_getUsageSize},
        {"nGetAllocatedSize", "(J)I", (void*)android_view_RenderNode_getAllocatedSize},
        {"nAddAnimator", "(JJ)V", (void*)android_view_RenderNode_addAnimator},
        {"nEndAllAnimators", "(J)V", (void*)android_view_RenderNode_endAllAnimators},
        {"nForceEndAnimators", "(J)V", (void*)android_view_RenderNode_forceEndAnimators},
        {"nRequestPositionUpdates", "(JLjava/lang/ref/WeakReference;)V",
         (void*)android_view_RenderNode_requestPositionUpdates},

        // ----------------------------------------------------------------------------
        // Critical JNI via @CriticalNative annotation in RenderNode.java
        // ----------------------------------------------------------------------------
        {"nDiscardDisplayList", "(J)V", (void*)android_view_RenderNode_discardDisplayList},
        {"nIsValid", "(J)Z", (void*)android_view_RenderNode_isValid},
        {"nSetLayerType", "(JI)Z", (void*)android_view_RenderNode_setLayerType},
        {"nGetLayerType", "(J)I", (void*)android_view_RenderNode_getLayerType},
        {"nSetLayerPaint", "(JJ)Z", (void*)android_view_RenderNode_setLayerPaint},
        {"nSetStaticMatrix", "(JJ)Z", (void*)android_view_RenderNode_setStaticMatrix},
        {"nSetAnimationMatrix", "(JJ)Z", (void*)android_view_RenderNode_setAnimationMatrix},
        {"nGetAnimationMatrix", "(JJ)Z", (void*)android_view_RenderNode_getAnimationMatrix},
        {"nSetClipToBounds", "(JZ)Z", (void*)android_view_RenderNode_setClipToBounds},
        {"nGetClipToBounds", "(J)Z", (void*)android_view_RenderNode_getClipToBounds},
        {"nSetClipBounds", "(JIIII)Z", (void*)android_view_RenderNode_setClipBounds},
        {"nSetClipBoundsEmpty", "(J)Z", (void*)android_view_RenderNode_setClipBoundsEmpty},
        {"nSetProjectBackwards", "(JZ)Z", (void*)android_view_RenderNode_setProjectBackwards},
        {"nSetProjectionReceiver", "(JZ)Z", (void*)android_view_RenderNode_setProjectionReceiver},

        {"nSetOutlineRoundRect", "(JIIIIFF)Z", (void*)android_view_RenderNode_setOutlineRoundRect},
        {"nSetOutlinePath", "(JJF)Z", (void*)android_view_RenderNode_setOutlinePath},
        {"nSetOutlineEmpty", "(J)Z", (void*)android_view_RenderNode_setOutlineEmpty},
        {"nSetOutlineNone", "(J)Z", (void*)android_view_RenderNode_setOutlineNone},
        {"nClearStretch", "(J)Z", (void*)android_view_RenderNode_clearStretch},
        {"nStretch", "(JFFFF)Z", (void*)android_view_RenderNode_stretch},
        {"nHasShadow", "(J)Z", (void*)android_view_RenderNode_hasShadow},
        {"nSetSpotShadowColor", "(JI)Z", (void*)android_view_RenderNode_setSpotShadowColor},
        {"nGetSpotShadowColor", "(J)I", (void*)android_view_RenderNode_getSpotShadowColor},
        {"nSetAmbientShadowColor", "(JI)Z", (void*)android_view_RenderNode_setAmbientShadowColor},
        {"nGetAmbientShadowColor", "(J)I", (void*)android_view_RenderNode_getAmbientShadowColor},
        {"nSetClipToOutline", "(JZ)Z", (void*)android_view_RenderNode_setClipToOutline},
        {"nSetRevealClip", "(JZFFF)Z", (void*)android_view_RenderNode_setRevealClip},

        {"nSetAlpha", "(JF)Z", (void*)android_view_RenderNode_setAlpha},
        {"nSetRenderEffect", "(JJ)Z", (void*)android_view_RenderNode_setRenderEffect},
        {"nSetBackdropRenderEffect", "(JJ)Z",
         (void*)android_view_RenderNode_setBackdropRenderEffect},
        {"nSetHasOverlappingRendering", "(JZ)Z",
         (void*)android_view_RenderNode_setHasOverlappingRendering},
        {"nSetUsageHint", "(JI)V", (void*)android_view_RenderNode_setUsageHint},
        {"nSetElevation", "(JF)Z", (void*)android_view_RenderNode_setElevation},
        {"nSetTranslationX", "(JF)Z", (void*)android_view_RenderNode_setTranslationX},
        {"nSetTranslationY", "(JF)Z", (void*)android_view_RenderNode_setTranslationY},
        {"nSetTranslationZ", "(JF)Z", (void*)android_view_RenderNode_setTranslationZ},
        {"nSetRotation", "(JF)Z", (void*)android_view_RenderNode_setRotation},
        {"nSetRotationX", "(JF)Z", (void*)android_view_RenderNode_setRotationX},
        {"nSetRotationY", "(JF)Z", (void*)android_view_RenderNode_setRotationY},
        {"nSetScaleX", "(JF)Z", (void*)android_view_RenderNode_setScaleX},
        {"nSetScaleY", "(JF)Z", (void*)android_view_RenderNode_setScaleY},
        {"nSetPivotX", "(JF)Z", (void*)android_view_RenderNode_setPivotX},
        {"nSetPivotY", "(JF)Z", (void*)android_view_RenderNode_setPivotY},
        {"nResetPivot", "(J)Z", (void*)android_view_RenderNode_resetPivot},
        {"nSetCameraDistance", "(JF)Z", (void*)android_view_RenderNode_setCameraDistance},
        {"nSetLeft", "(JI)Z", (void*)android_view_RenderNode_setLeft},
        {"nSetTop", "(JI)Z", (void*)android_view_RenderNode_setTop},
        {"nSetRight", "(JI)Z", (void*)android_view_RenderNode_setRight},
        {"nSetBottom", "(JI)Z", (void*)android_view_RenderNode_setBottom},
        {"nGetLeft", "(J)I", (void*)android_view_RenderNode_getLeft},
        {"nGetTop", "(J)I", (void*)android_view_RenderNode_getTop},
        {"nGetRight", "(J)I", (void*)android_view_RenderNode_getRight},
        {"nGetBottom", "(J)I", (void*)android_view_RenderNode_getBottom},
        {"nSetLeftTopRightBottom", "(JIIII)Z",
         (void*)android_view_RenderNode_setLeftTopRightBottom},
        {"nOffsetLeftAndRight", "(JI)Z", (void*)android_view_RenderNode_offsetLeftAndRight},
        {"nOffsetTopAndBottom", "(JI)Z", (void*)android_view_RenderNode_offsetTopAndBottom},

        {"nHasOverlappingRendering", "(J)Z",
         (void*)android_view_RenderNode_hasOverlappingRendering},
        {"nGetClipToOutline", "(J)Z", (void*)android_view_RenderNode_getClipToOutline},
        {"nGetAlpha", "(J)F", (void*)android_view_RenderNode_getAlpha},
        {"nGetCameraDistance", "(J)F", (void*)android_view_RenderNode_getCameraDistance},
        {"nGetScaleX", "(J)F", (void*)android_view_RenderNode_getScaleX},
        {"nGetScaleY", "(J)F", (void*)android_view_RenderNode_getScaleY},
        {"nGetElevation", "(J)F", (void*)android_view_RenderNode_getElevation},
        {"nGetTranslationX", "(J)F", (void*)android_view_RenderNode_getTranslationX},
        {"nGetTranslationY", "(J)F", (void*)android_view_RenderNode_getTranslationY},
        {"nGetTranslationZ", "(J)F", (void*)android_view_RenderNode_getTranslationZ},
        {"nGetRotation", "(J)F", (void*)android_view_RenderNode_getRotation},
        {"nGetRotationX", "(J)F", (void*)android_view_RenderNode_getRotationX},
        {"nGetRotationY", "(J)F", (void*)android_view_RenderNode_getRotationY},
        {"nIsPivotExplicitlySet", "(J)Z", (void*)android_view_RenderNode_isPivotExplicitlySet},
        {"nHasIdentityMatrix", "(J)Z", (void*)android_view_RenderNode_hasIdentityMatrix},

        {"nGetTransformMatrix", "(JJ)V", (void*)android_view_RenderNode_getTransformMatrix},
        {"nGetInverseTransformMatrix", "(JJ)V",
         (void*)android_view_RenderNode_getInverseTransformMatrix},

        {"nGetPivotX", "(J)F", (void*)android_view_RenderNode_getPivotX},
        {"nGetPivotY", "(J)F", (void*)android_view_RenderNode_getPivotY},
        {"nGetWidth", "(J)I", (void*)android_view_RenderNode_getWidth},
        {"nGetHeight", "(J)I", (void*)android_view_RenderNode_getHeight},
        {"nSetAllowForceDark", "(JZ)Z", (void*)android_view_RenderNode_setAllowForceDark},
        {"nGetAllowForceDark", "(J)Z", (void*)android_view_RenderNode_getAllowForceDark},
        {"nGetUniqueId", "(J)J", (void*)android_view_RenderNode_getUniqueId},
        {"nSetIsTextureView", "(J)V", (void*)android_view_RenderNode_setIsTextureView},
};

int register_android_view_RenderNode(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "android/graphics/RenderNode$PositionUpdateListener");
    gPositionListener.clazz = MakeGlobalRefOrDie(env, clazz);
    gPositionListener.callPositionChanged = GetStaticMethodIDOrDie(
            env, clazz, "callPositionChanged", "(Ljava/lang/ref/WeakReference;JIIII)Z");
    gPositionListener.callPositionChanged2 = GetStaticMethodIDOrDie(
            env, clazz, "callPositionChanged2", "(Ljava/lang/ref/WeakReference;JIIIIIIII)Z");
    gPositionListener.callApplyStretch = GetStaticMethodIDOrDie(
            env, clazz, "callApplyStretch", "(Ljava/lang/ref/WeakReference;JFFFFFFFFFF)Z");
    gPositionListener.callPositionLost = GetStaticMethodIDOrDie(
            env, clazz, "callPositionLost", "(Ljava/lang/ref/WeakReference;J)Z");
    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

};

