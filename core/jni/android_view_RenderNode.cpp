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
#define ATRACE_TAG ATRACE_TAG_VIEW

#include <EGL/egl.h>

#include "jni.h"
#include "GraphicsJNI.h"
#include <nativehelper/JNIHelp.h>
#include <android_runtime/AndroidRuntime.h>

#include <Animator.h>
#include <DamageAccumulator.h>
#include <Matrix.h>
#include <RenderNode.h>
#include <renderthread/CanvasContext.h>
#include <TreeInfo.h>
#include <hwui/Paint.h>

#include "core_jni_helpers.h"

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

static jint android_view_RenderNode_getDebugSize(JNIEnv* env, jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->getDebugSize();
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

static void android_view_RenderNode_setDisplayList(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong displayListPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    DisplayList* newData = reinterpret_cast<DisplayList*>(displayListPtr);
    renderNode->setStagingDisplayList(newData);
}

static jboolean android_view_RenderNode_isValid(jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->isValid();
}

// ----------------------------------------------------------------------------
// RenderProperties - setters
// ----------------------------------------------------------------------------

static jboolean android_view_RenderNode_setLayerType(jlong renderNodePtr, jint jlayerType) {
    LayerType layerType = static_cast<LayerType>(jlayerType);
    return SET_AND_DIRTY(mutateLayerProperties().setType, layerType, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setLayerPaint(jlong renderNodePtr, jlong paintPtr) {
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    return SET_AND_DIRTY(mutateLayerProperties().setFromPaint, paint, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setStaticMatrix(jlong renderNodePtr, jlong matrixPtr) {
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    return SET_AND_DIRTY(setStaticMatrix, matrix, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setAnimationMatrix(jlong renderNodePtr, jlong matrixPtr) {
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    return SET_AND_DIRTY(setAnimationMatrix, matrix, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setClipToBounds(jlong renderNodePtr,
        jboolean clipToBounds) {
    return SET_AND_DIRTY(setClipToBounds, clipToBounds, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setClipBounds(jlong renderNodePtr,
        jint left, jint top, jint right, jint bottom) {
    android::uirenderer::Rect clipBounds(left, top, right, bottom);
    return SET_AND_DIRTY(setClipBounds, clipBounds, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setClipBoundsEmpty(jlong renderNodePtr) {
    return SET_AND_DIRTY(setClipBoundsEmpty,, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setProjectBackwards(jlong renderNodePtr,
        jboolean shouldProject) {
    return SET_AND_DIRTY(setProjectBackwards, shouldProject, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setProjectionReceiver(jlong renderNodePtr,
        jboolean shouldRecieve) {
    return SET_AND_DIRTY(setProjectionReceiver, shouldRecieve, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setOutlineRoundRect(jlong renderNodePtr,
        jint left, jint top, jint right, jint bottom, jfloat radius, jfloat alpha) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setRoundRect(left, top, right, bottom,
            radius, alpha);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setOutlineConvexPath(jlong renderNodePtr,
        jlong outlinePathPtr, jfloat alpha) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    SkPath* outlinePath = reinterpret_cast<SkPath*>(outlinePathPtr);
    renderNode->mutateStagingProperties().mutableOutline().setConvexPath(outlinePath, alpha);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setOutlineEmpty(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setEmpty();
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setOutlineNone(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setNone();
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_hasShadow(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().hasShadow();
}

static jboolean android_view_RenderNode_setSpotShadowColor(jlong renderNodePtr, jint shadowColor) {
    return SET_AND_DIRTY(setSpotShadowColor,
            static_cast<SkColor>(shadowColor), RenderNode::GENERIC);
}

static jint android_view_RenderNode_getSpotShadowColor(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getSpotShadowColor();
}

static jboolean android_view_RenderNode_setAmbientShadowColor(jlong renderNodePtr,
        jint shadowColor) {
    return SET_AND_DIRTY(setAmbientShadowColor,
            static_cast<SkColor>(shadowColor), RenderNode::GENERIC);
}

static jint android_view_RenderNode_getAmbientShadowColor(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getAmbientShadowColor();
}

static jboolean android_view_RenderNode_setClipToOutline(jlong renderNodePtr,
        jboolean clipToOutline) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setShouldClip(clipToOutline);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setRevealClip(jlong renderNodePtr, jboolean shouldClip,
        jfloat x, jfloat y, jfloat radius) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableRevealClip().set(
            shouldClip, x, y, radius);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setAlpha(jlong renderNodePtr, float alpha) {
    return SET_AND_DIRTY(setAlpha, alpha, RenderNode::ALPHA);
}

static jboolean android_view_RenderNode_setHasOverlappingRendering(jlong renderNodePtr,
        bool hasOverlappingRendering) {
    return SET_AND_DIRTY(setHasOverlappingRendering, hasOverlappingRendering,
            RenderNode::GENERIC);
}

static void android_view_RenderNode_setUsageHint(jlong renderNodePtr, jint usageHint) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->setUsageHint(static_cast<UsageHint>(usageHint));
}

static jboolean android_view_RenderNode_setElevation(jlong renderNodePtr, float elevation) {
    return SET_AND_DIRTY(setElevation, elevation, RenderNode::Z);
}

static jboolean android_view_RenderNode_setTranslationX(jlong renderNodePtr, float tx) {
    return SET_AND_DIRTY(setTranslationX, tx, RenderNode::TRANSLATION_X | RenderNode::X);
}

static jboolean android_view_RenderNode_setTranslationY(jlong renderNodePtr, float ty) {
    return SET_AND_DIRTY(setTranslationY, ty, RenderNode::TRANSLATION_Y | RenderNode::Y);
}

static jboolean android_view_RenderNode_setTranslationZ(jlong renderNodePtr, float tz) {
    return SET_AND_DIRTY(setTranslationZ, tz, RenderNode::TRANSLATION_Z | RenderNode::Z);
}

static jboolean android_view_RenderNode_setRotation(jlong renderNodePtr, float rotation) {
    return SET_AND_DIRTY(setRotation, rotation, RenderNode::ROTATION);
}

static jboolean android_view_RenderNode_setRotationX(jlong renderNodePtr, float rx) {
    return SET_AND_DIRTY(setRotationX, rx, RenderNode::ROTATION_X);
}

static jboolean android_view_RenderNode_setRotationY(jlong renderNodePtr, float ry) {
    return SET_AND_DIRTY(setRotationY, ry, RenderNode::ROTATION_Y);
}

static jboolean android_view_RenderNode_setScaleX(jlong renderNodePtr, float sx) {
    return SET_AND_DIRTY(setScaleX, sx, RenderNode::SCALE_X);
}

static jboolean android_view_RenderNode_setScaleY(jlong renderNodePtr, float sy) {
    return SET_AND_DIRTY(setScaleY, sy, RenderNode::SCALE_Y);
}

static jboolean android_view_RenderNode_setPivotX(jlong renderNodePtr, float px) {
    return SET_AND_DIRTY(setPivotX, px, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setPivotY(jlong renderNodePtr, float py) {
    return SET_AND_DIRTY(setPivotY, py, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_resetPivot(jlong renderNodePtr) {
    return SET_AND_DIRTY(resetPivot, /* void */, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setCameraDistance(jlong renderNodePtr, float distance) {
    return SET_AND_DIRTY(setCameraDistance, distance, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setLeft(jlong renderNodePtr, int left) {
    return SET_AND_DIRTY(setLeft, left, RenderNode::X);
}

static jboolean android_view_RenderNode_setTop(jlong renderNodePtr, int top) {
    return SET_AND_DIRTY(setTop, top, RenderNode::Y);
}

static jboolean android_view_RenderNode_setRight(jlong renderNodePtr, int right) {
    return SET_AND_DIRTY(setRight, right, RenderNode::X);
}

static jboolean android_view_RenderNode_setBottom(jlong renderNodePtr, int bottom) {
    return SET_AND_DIRTY(setBottom, bottom, RenderNode::Y);
}

static jint android_view_RenderNode_getLeft(jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getLeft();
}

static jint android_view_RenderNode_getTop(jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getTop();
}

static jint android_view_RenderNode_getRight(jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getRight();
}

static jint android_view_RenderNode_getBottom(jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getBottom();
}

static jboolean android_view_RenderNode_setLeftTopRightBottom(jlong renderNodePtr,
        int left, int top, int right, int bottom) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    if (renderNode->mutateStagingProperties().setLeftTopRightBottom(left, top, right, bottom)) {
        renderNode->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        return true;
    }
    return false;
}

static jboolean android_view_RenderNode_offsetLeftAndRight(jlong renderNodePtr, jint offset) {
    return SET_AND_DIRTY(offsetLeftRight, offset, RenderNode::X);
}

static jboolean android_view_RenderNode_offsetTopAndBottom(jlong renderNodePtr, jint offset) {
    return SET_AND_DIRTY(offsetTopBottom, offset, RenderNode::Y);
}

// ----------------------------------------------------------------------------
// RenderProperties - getters
// ----------------------------------------------------------------------------

static jboolean android_view_RenderNode_hasOverlappingRendering(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().hasOverlappingRendering();
}

static jboolean android_view_RenderNode_getAnimationMatrix(jlong renderNodePtr, jlong outMatrixPtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    SkMatrix* outMatrix = reinterpret_cast<SkMatrix*>(outMatrixPtr);

    const SkMatrix* animationMatrix = renderNode->stagingProperties().getAnimationMatrix();

    if (animationMatrix) {
        *outMatrix = *animationMatrix;
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean android_view_RenderNode_getClipToBounds(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getClipToBounds();
}

static jboolean android_view_RenderNode_getClipToOutline(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getOutline().getShouldClip();
}

static jfloat android_view_RenderNode_getAlpha(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getAlpha();
}

static jfloat android_view_RenderNode_getCameraDistance(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getCameraDistance();
}

static jfloat android_view_RenderNode_getScaleX(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getScaleX();
}

static jfloat android_view_RenderNode_getScaleY(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getScaleY();
}

static jfloat android_view_RenderNode_getElevation(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getElevation();
}

static jfloat android_view_RenderNode_getTranslationX(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getTranslationX();
}

static jfloat android_view_RenderNode_getTranslationY(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getTranslationY();
}

static jfloat android_view_RenderNode_getTranslationZ(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getTranslationZ();
}

static jfloat android_view_RenderNode_getRotation(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getRotation();
}

static jfloat android_view_RenderNode_getRotationX(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getRotationX();
}

static jfloat android_view_RenderNode_getRotationY(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().getRotationY();
}

static jboolean android_view_RenderNode_isPivotExplicitlySet(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().isPivotExplicitlySet();
}

static jboolean android_view_RenderNode_hasIdentityMatrix(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().updateMatrix();
    return !renderNode->stagingProperties().hasTransformMatrix();
}

static jint android_view_RenderNode_getLayerType(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return static_cast<int>(renderNode->stagingProperties().layerProperties().type());
}

// ----------------------------------------------------------------------------
// RenderProperties - computed getters
// ----------------------------------------------------------------------------

static void android_view_RenderNode_getTransformMatrix(jlong renderNodePtr, jlong outMatrixPtr) {
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

static void android_view_RenderNode_getInverseTransformMatrix(jlong renderNodePtr,
        jlong outMatrixPtr) {
    // load transform matrix
    android_view_RenderNode_getTransformMatrix(renderNodePtr, outMatrixPtr);
    SkMatrix* outMatrix = reinterpret_cast<SkMatrix*>(outMatrixPtr);

    // return it inverted
    if (!outMatrix->invert(outMatrix)) {
        // failed to load inverse, pass back identity
        outMatrix->setIdentity();
    }
}

static jfloat android_view_RenderNode_getPivotX(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().updateMatrix();
    return renderNode->stagingProperties().getPivotX();
}

static jfloat android_view_RenderNode_getPivotY(jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().updateMatrix();
    return renderNode->stagingProperties().getPivotY();
}

static jint android_view_RenderNode_getWidth(jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getWidth();
}

static jint android_view_RenderNode_getHeight(jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getHeight();
}

static jboolean android_view_RenderNode_setAllowForceDark(jlong renderNodePtr, jboolean allow) {
    return SET_AND_DIRTY(setAllowForceDark, allow, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_getAllowForceDark(jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->stagingProperties().getAllowForceDark();
}

static jlong android_view_RenderNode_getUniqueId(jlong renderNodePtr) {
    return reinterpret_cast<RenderNode*>(renderNodePtr)->uniqueId();
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

// ----------------------------------------------------------------------------
// SurfaceView position callback
// ----------------------------------------------------------------------------

jmethodID gPositionListener_PositionChangedMethod;
jmethodID gPositionListener_PositionLostMethod;

static void android_view_RenderNode_requestPositionUpdates(JNIEnv* env, jobject,
        jlong renderNodePtr, jobject listener) {
    class PositionListenerTrampoline : public RenderNode::PositionListener {
    public:
        PositionListenerTrampoline(JNIEnv* env, jobject listener) {
            env->GetJavaVM(&mVm);
            mWeakRef = env->NewWeakGlobalRef(listener);
        }

        virtual ~PositionListenerTrampoline() {
            jnienv()->DeleteWeakGlobalRef(mWeakRef);
            mWeakRef = nullptr;
        }

        virtual void onPositionUpdated(RenderNode& node, const TreeInfo& info) override {
            if (CC_UNLIKELY(!mWeakRef || !info.updateWindowPositions)) return;

            Matrix4 transform;
            info.damageAccumulator->computeCurrentTransform(&transform);
            const RenderProperties& props = node.properties();
            uirenderer::Rect bounds(props.getWidth(), props.getHeight());
            transform.mapRect(bounds);

            if (CC_LIKELY(transform.isPureTranslate())) {
                // snap/round the computed bounds, so they match the rounding behavior
                // of the clear done in SurfaceView#draw().
                bounds.snapToPixelBoundaries();
            } else {
                // Conservatively round out so the punched hole (in the ZOrderOnTop = true case)
                // doesn't extend beyond the other window
                bounds.roundOut();
            }

            if (mPreviousPosition == bounds) {
                return;
            }
            mPreviousPosition = bounds;

            incStrong(0);
            auto functor = std::bind(
                std::mem_fn(&PositionListenerTrampoline::doUpdatePositionAsync), this,
                (jlong) info.canvasContext.getFrameNumber(),
                (jint) bounds.left, (jint) bounds.top,
                (jint) bounds.right, (jint) bounds.bottom);

            info.canvasContext.enqueueFrameWork(std::move(functor));
        }

        virtual void onPositionLost(RenderNode& node, const TreeInfo* info) override {
            if (CC_UNLIKELY(!mWeakRef || (info && !info->updateWindowPositions))) return;

            if (mPreviousPosition.isEmpty()) {
                return;
            }
            mPreviousPosition.setEmpty();

            ATRACE_NAME("SurfaceView position lost");
            JNIEnv* env = jnienv();
            jobject localref = env->NewLocalRef(mWeakRef);
            if (CC_UNLIKELY(!localref)) {
                jnienv()->DeleteWeakGlobalRef(mWeakRef);
                mWeakRef = nullptr;
                return;
            }

            // TODO: Remember why this is synchronous and then make a comment
            env->CallVoidMethod(localref, gPositionListener_PositionLostMethod,
                    info ? info->canvasContext.getFrameNumber() : 0);
            env->DeleteLocalRef(localref);
        }

    private:
        JNIEnv* jnienv() {
            JNIEnv* env;
            if (mVm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
                LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", mVm);
            }
            return env;
        }

        void doUpdatePositionAsync(jlong frameNumber, jint left, jint top,
                jint right, jint bottom) {
            ATRACE_NAME("Update SurfaceView position");

            JNIEnv* env = jnienv();
            jobject localref = env->NewLocalRef(mWeakRef);
            if (CC_UNLIKELY(!localref)) {
                env->DeleteWeakGlobalRef(mWeakRef);
                mWeakRef = nullptr;
            } else {
                env->CallVoidMethod(localref, gPositionListener_PositionChangedMethod,
                        frameNumber, left, top, right, bottom);
                env->DeleteLocalRef(localref);
            }

            // We need to release ourselves here
            decStrong(0);
        }

        JavaVM* mVm;
        jobject mWeakRef;
        uirenderer::Rect mPreviousPosition;
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
    { "nCreate",               "(Ljava/lang/String;)J", (void*) android_view_RenderNode_create },
    { "nGetNativeFinalizer",   "()J",    (void*) android_view_RenderNode_getNativeFinalizer },
    { "nOutput",               "(J)V",    (void*) android_view_RenderNode_output },
    { "nGetDebugSize",         "(J)I",    (void*) android_view_RenderNode_getDebugSize },
    { "nAddAnimator",              "(JJ)V", (void*) android_view_RenderNode_addAnimator },
    { "nEndAllAnimators",          "(J)V", (void*) android_view_RenderNode_endAllAnimators },
    { "nRequestPositionUpdates",   "(JLandroid/graphics/RenderNode$PositionUpdateListener;)V", (void*) android_view_RenderNode_requestPositionUpdates },
    { "nSetDisplayList",       "(JJ)V",   (void*) android_view_RenderNode_setDisplayList },


// ----------------------------------------------------------------------------
// Fast JNI via @CriticalNative annotation in RenderNode.java
// ----------------------------------------------------------------------------
    { "nSetDisplayList",       "(JJ)V",   (void*) android_view_RenderNode_setDisplayList },


// ----------------------------------------------------------------------------
// Critical JNI via @CriticalNative annotation in RenderNode.java
// ----------------------------------------------------------------------------
    { "nIsValid",              "(J)Z",   (void*) android_view_RenderNode_isValid },
    { "nSetLayerType",         "(JI)Z",  (void*) android_view_RenderNode_setLayerType },
    { "nGetLayerType",         "(J)I",   (void*) android_view_RenderNode_getLayerType },
    { "nSetLayerPaint",        "(JJ)Z",  (void*) android_view_RenderNode_setLayerPaint },
    { "nSetStaticMatrix",      "(JJ)Z",  (void*) android_view_RenderNode_setStaticMatrix },
    { "nSetAnimationMatrix",   "(JJ)Z",  (void*) android_view_RenderNode_setAnimationMatrix },
    { "nGetAnimationMatrix",   "(JJ)Z",  (void*) android_view_RenderNode_getAnimationMatrix },
    { "nSetClipToBounds",      "(JZ)Z",  (void*) android_view_RenderNode_setClipToBounds },
    { "nGetClipToBounds",      "(J)Z",   (void*) android_view_RenderNode_getClipToBounds },
    { "nSetClipBounds",        "(JIIII)Z", (void*) android_view_RenderNode_setClipBounds },
    { "nSetClipBoundsEmpty",   "(J)Z",   (void*) android_view_RenderNode_setClipBoundsEmpty },
    { "nSetProjectBackwards",  "(JZ)Z",  (void*) android_view_RenderNode_setProjectBackwards },
    { "nSetProjectionReceiver","(JZ)Z",  (void*) android_view_RenderNode_setProjectionReceiver },

    { "nSetOutlineRoundRect",  "(JIIIIFF)Z", (void*) android_view_RenderNode_setOutlineRoundRect },
    { "nSetOutlineConvexPath", "(JJF)Z", (void*) android_view_RenderNode_setOutlineConvexPath },
    { "nSetOutlineEmpty",      "(J)Z",   (void*) android_view_RenderNode_setOutlineEmpty },
    { "nSetOutlineNone",       "(J)Z",   (void*) android_view_RenderNode_setOutlineNone },
    { "nHasShadow",            "(J)Z",   (void*) android_view_RenderNode_hasShadow },
    { "nSetSpotShadowColor",   "(JI)Z",  (void*) android_view_RenderNode_setSpotShadowColor },
    { "nGetSpotShadowColor",   "(J)I",   (void*) android_view_RenderNode_getSpotShadowColor },
    { "nSetAmbientShadowColor","(JI)Z",  (void*) android_view_RenderNode_setAmbientShadowColor },
    { "nGetAmbientShadowColor","(J)I",   (void*) android_view_RenderNode_getAmbientShadowColor },
    { "nSetClipToOutline",     "(JZ)Z",  (void*) android_view_RenderNode_setClipToOutline },
    { "nSetRevealClip",        "(JZFFF)Z", (void*) android_view_RenderNode_setRevealClip },

    { "nSetAlpha",             "(JF)Z",  (void*) android_view_RenderNode_setAlpha },
    { "nSetHasOverlappingRendering", "(JZ)Z",
            (void*) android_view_RenderNode_setHasOverlappingRendering },
    { "nSetUsageHint",    "(JI)V", (void*) android_view_RenderNode_setUsageHint },
    { "nSetElevation",         "(JF)Z",  (void*) android_view_RenderNode_setElevation },
    { "nSetTranslationX",      "(JF)Z",  (void*) android_view_RenderNode_setTranslationX },
    { "nSetTranslationY",      "(JF)Z",  (void*) android_view_RenderNode_setTranslationY },
    { "nSetTranslationZ",      "(JF)Z",  (void*) android_view_RenderNode_setTranslationZ },
    { "nSetRotation",          "(JF)Z",  (void*) android_view_RenderNode_setRotation },
    { "nSetRotationX",         "(JF)Z",  (void*) android_view_RenderNode_setRotationX },
    { "nSetRotationY",         "(JF)Z",  (void*) android_view_RenderNode_setRotationY },
    { "nSetScaleX",            "(JF)Z",  (void*) android_view_RenderNode_setScaleX },
    { "nSetScaleY",            "(JF)Z",  (void*) android_view_RenderNode_setScaleY },
    { "nSetPivotX",            "(JF)Z",  (void*) android_view_RenderNode_setPivotX },
    { "nSetPivotY",            "(JF)Z",  (void*) android_view_RenderNode_setPivotY },
    { "nResetPivot",           "(J)Z",   (void*) android_view_RenderNode_resetPivot },
    { "nSetCameraDistance",    "(JF)Z",  (void*) android_view_RenderNode_setCameraDistance },
    { "nSetLeft",              "(JI)Z",  (void*) android_view_RenderNode_setLeft },
    { "nSetTop",               "(JI)Z",  (void*) android_view_RenderNode_setTop },
    { "nSetRight",             "(JI)Z",  (void*) android_view_RenderNode_setRight },
    { "nSetBottom",            "(JI)Z",  (void*) android_view_RenderNode_setBottom },
    { "nGetLeft",              "(J)I",  (void*) android_view_RenderNode_getLeft },
    { "nGetTop",               "(J)I",  (void*) android_view_RenderNode_getTop },
    { "nGetRight",             "(J)I",  (void*) android_view_RenderNode_getRight },
    { "nGetBottom",            "(J)I",  (void*) android_view_RenderNode_getBottom },
    { "nSetLeftTopRightBottom","(JIIII)Z", (void*) android_view_RenderNode_setLeftTopRightBottom },
    { "nOffsetLeftAndRight",   "(JI)Z",  (void*) android_view_RenderNode_offsetLeftAndRight },
    { "nOffsetTopAndBottom",   "(JI)Z",  (void*) android_view_RenderNode_offsetTopAndBottom },

    { "nHasOverlappingRendering", "(J)Z",  (void*) android_view_RenderNode_hasOverlappingRendering },
    { "nGetClipToOutline",        "(J)Z",  (void*) android_view_RenderNode_getClipToOutline },
    { "nGetAlpha",                "(J)F",  (void*) android_view_RenderNode_getAlpha },
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
    { "nGetWidth",                 "(J)I",  (void*) android_view_RenderNode_getWidth },
    { "nGetHeight",                "(J)I",  (void*) android_view_RenderNode_getHeight },
    { "nSetAllowForceDark",        "(JZ)Z", (void*) android_view_RenderNode_setAllowForceDark },
    { "nGetAllowForceDark",        "(J)Z",  (void*) android_view_RenderNode_getAllowForceDark },
    { "nGetUniqueId",              "(J)J",  (void*) android_view_RenderNode_getUniqueId },
};

int register_android_view_RenderNode(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "android/graphics/RenderNode$PositionUpdateListener");
    gPositionListener_PositionChangedMethod = GetMethodIDOrDie(env, clazz,
            "positionChanged", "(JIIII)V");
    gPositionListener_PositionLostMethod = GetMethodIDOrDie(env, clazz,
            "positionLost", "(J)V");
    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

};

