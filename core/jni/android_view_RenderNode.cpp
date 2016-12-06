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

static JNIEnv* getenv(JavaVM* vm) {
    JNIEnv* env;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        LOG_ALWAYS_FATAL("Failed to get JNIEnv for JavaVM: %p", vm);
    }
    return env;
}

static jmethodID gOnRenderNodeDetached;

class RenderNodeContext : public VirtualLightRefBase {
public:
    RenderNodeContext(JNIEnv* env, jobject jobjRef) {
        env->GetJavaVM(&mVm);
        // This holds a weak ref because otherwise there's a cyclic global ref
        // with this holding a strong global ref to the view which holds
        // a strong ref to RenderNode which holds a strong ref to this.
        mWeakRef = env->NewWeakGlobalRef(jobjRef);
    }

    virtual ~RenderNodeContext() {
        JNIEnv* env = getenv(mVm);
        env->DeleteWeakGlobalRef(mWeakRef);
    }

    jobject acquireLocalRef(JNIEnv* env) {
        return env->NewLocalRef(mWeakRef);
    }

private:
    JavaVM* mVm;
    jweak mWeakRef;
};

// Called by ThreadedRenderer's JNI layer
void onRenderNodeRemoved(JNIEnv* env, RenderNode* node) {
    auto context = reinterpret_cast<RenderNodeContext*>(node->getUserContext());
    if (!context) return;
    jobject jnode = context->acquireLocalRef(env);
    if (!jnode) {
        // The owning node has been GC'd, release the context
        node->setUserContext(nullptr);
        return;
    }
    env->CallVoidMethod(jnode, gOnRenderNodeDetached);
    env->DeleteLocalRef(jnode);
}

// ----------------------------------------------------------------------------
// DisplayList view properties
// ----------------------------------------------------------------------------

static void android_view_RenderNode_output(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->output();
}

static jint android_view_RenderNode_getDebugSize(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->getDebugSize();
}

static jlong android_view_RenderNode_create(JNIEnv* env, jobject thiz,
        jstring name) {
    RenderNode* renderNode = new RenderNode();
    renderNode->incStrong(0);
    if (name != NULL) {
        const char* textArray = env->GetStringUTFChars(name, NULL);
        renderNode->setName(textArray);
        env->ReleaseStringUTFChars(name, textArray);
    }
    renderNode->setUserContext(new RenderNodeContext(env, thiz));
    return reinterpret_cast<jlong>(renderNode);
}

static void android_view_RenderNode_destroyRenderNode(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->decStrong(0);
}

static void android_view_RenderNode_setDisplayList(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong displayListPtr) {
    class RemovedObserver : public TreeObserver {
    public:
        virtual void onMaybeRemovedFromTree(RenderNode* node) override {
            maybeRemovedNodes.insert(sp<RenderNode>(node));
        }
        std::set< sp<RenderNode> > maybeRemovedNodes;
    };

    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    DisplayList* newData = reinterpret_cast<DisplayList*>(displayListPtr);
    RemovedObserver observer;
    renderNode->setStagingDisplayList(newData, &observer);
    for (auto& node : observer.maybeRemovedNodes) {
        if (node->hasParents()) continue;
        onRenderNodeRemoved(env, node.get());
    }
}

// ----------------------------------------------------------------------------
// RenderProperties - setters
// ----------------------------------------------------------------------------

static jboolean android_view_RenderNode_setLayerType(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jint jlayerType) {
    LayerType layerType = static_cast<LayerType>(jlayerType);
    return SET_AND_DIRTY(mutateLayerProperties().setType, layerType, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setLayerPaint(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong paintPtr) {
    Paint* paint = reinterpret_cast<Paint*>(paintPtr);
    return SET_AND_DIRTY(mutateLayerProperties().setFromPaint, paint, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setStaticMatrix(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong matrixPtr) {
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    return SET_AND_DIRTY(setStaticMatrix, matrix, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setAnimationMatrix(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong matrixPtr) {
    SkMatrix* matrix = reinterpret_cast<SkMatrix*>(matrixPtr);
    return SET_AND_DIRTY(setAnimationMatrix, matrix, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setClipToBounds(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean clipToBounds) {
    return SET_AND_DIRTY(setClipToBounds, clipToBounds, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setClipBounds(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jint left, jint top, jint right, jint bottom) {
    android::uirenderer::Rect clipBounds(left, top, right, bottom);
    return SET_AND_DIRTY(setClipBounds, clipBounds, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setClipBoundsEmpty(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    return SET_AND_DIRTY(setClipBoundsEmpty,, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setProjectBackwards(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean shouldProject) {
    return SET_AND_DIRTY(setProjectBackwards, shouldProject, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setProjectionReceiver(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean shouldRecieve) {
    return SET_AND_DIRTY(setProjectionReceiver, shouldRecieve, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setOutlineRoundRect(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jint left, jint top,
        jint right, jint bottom, jfloat radius, jfloat alpha) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setRoundRect(left, top, right, bottom,
            radius, alpha);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setOutlineConvexPath(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jlong outlinePathPtr, jfloat alpha) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    SkPath* outlinePath = reinterpret_cast<SkPath*>(outlinePathPtr);
    renderNode->mutateStagingProperties().mutableOutline().setConvexPath(outlinePath, alpha);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setOutlineEmpty(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setEmpty();
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setOutlineNone(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setNone();
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_hasShadow(JNIEnv* env,
        jobject clazz, jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    return renderNode->stagingProperties().hasShadow();
}

static jboolean android_view_RenderNode_setClipToOutline(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean clipToOutline) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableOutline().setShouldClip(clipToOutline);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setRevealClip(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jboolean shouldClip,
        jfloat x, jfloat y, jfloat radius) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->mutateStagingProperties().mutableRevealClip().set(
            shouldClip, x, y, radius);
    renderNode->setPropertyFieldsDirty(RenderNode::GENERIC);
    return true;
}

static jboolean android_view_RenderNode_setAlpha(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float alpha) {
    return SET_AND_DIRTY(setAlpha, alpha, RenderNode::ALPHA);
}

static jboolean android_view_RenderNode_setHasOverlappingRendering(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, bool hasOverlappingRendering) {
    return SET_AND_DIRTY(setHasOverlappingRendering, hasOverlappingRendering,
            RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setElevation(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float elevation) {
    return SET_AND_DIRTY(setElevation, elevation, RenderNode::Z);
}

static jboolean android_view_RenderNode_setTranslationX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float tx) {
    return SET_AND_DIRTY(setTranslationX, tx, RenderNode::TRANSLATION_X | RenderNode::X);
}

static jboolean android_view_RenderNode_setTranslationY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float ty) {
    return SET_AND_DIRTY(setTranslationY, ty, RenderNode::TRANSLATION_Y | RenderNode::Y);
}

static jboolean android_view_RenderNode_setTranslationZ(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float tz) {
    return SET_AND_DIRTY(setTranslationZ, tz, RenderNode::TRANSLATION_Z | RenderNode::Z);
}

static jboolean android_view_RenderNode_setRotation(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float rotation) {
    return SET_AND_DIRTY(setRotation, rotation, RenderNode::ROTATION);
}

static jboolean android_view_RenderNode_setRotationX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float rx) {
    return SET_AND_DIRTY(setRotationX, rx, RenderNode::ROTATION_X);
}

static jboolean android_view_RenderNode_setRotationY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float ry) {
    return SET_AND_DIRTY(setRotationY, ry, RenderNode::ROTATION_Y);
}

static jboolean android_view_RenderNode_setScaleX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float sx) {
    return SET_AND_DIRTY(setScaleX, sx, RenderNode::SCALE_X);
}

static jboolean android_view_RenderNode_setScaleY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float sy) {
    return SET_AND_DIRTY(setScaleY, sy, RenderNode::SCALE_Y);
}

static jboolean android_view_RenderNode_setPivotX(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float px) {
    return SET_AND_DIRTY(setPivotX, px, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setPivotY(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float py) {
    return SET_AND_DIRTY(setPivotY, py, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setCameraDistance(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, float distance) {
    return SET_AND_DIRTY(setCameraDistance, distance, RenderNode::GENERIC);
}

static jboolean android_view_RenderNode_setLeft(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, int left) {
    return SET_AND_DIRTY(setLeft, left, RenderNode::X);
}

static jboolean android_view_RenderNode_setTop(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, int top) {
    return SET_AND_DIRTY(setTop, top, RenderNode::Y);
}

static jboolean android_view_RenderNode_setRight(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, int right) {
    return SET_AND_DIRTY(setRight, right, RenderNode::X);
}

static jboolean android_view_RenderNode_setBottom(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, int bottom) {
    return SET_AND_DIRTY(setBottom, bottom, RenderNode::Y);
}

static jboolean android_view_RenderNode_setLeftTopRightBottom(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, int left, int top,
        int right, int bottom) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    if (renderNode->mutateStagingProperties().setLeftTopRightBottom(left, top, right, bottom)) {
        renderNode->setPropertyFieldsDirty(RenderNode::X | RenderNode::Y);
        return true;
    }
    return false;
}

static jboolean android_view_RenderNode_offsetLeftAndRight(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jint offset) {
    return SET_AND_DIRTY(offsetLeftRight, offset, RenderNode::X);
}

static jboolean android_view_RenderNode_offsetTopAndBottom(JNIEnv* env,
        jobject clazz, jlong renderNodePtr, jint offset) {
    return SET_AND_DIRTY(offsetTopBottom, offset, RenderNode::Y);
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

static void android_view_RenderNode_endAllAnimators(JNIEnv* env, jobject clazz,
        jlong renderNodePtr) {
    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->animators().endAllStagingAnimators();
}

// ----------------------------------------------------------------------------
// SurfaceView position callback
// ----------------------------------------------------------------------------

jmethodID gSurfaceViewPositionUpdateMethod;
jmethodID gSurfaceViewPositionLostMethod;

static void android_view_RenderNode_requestPositionUpdates(JNIEnv* env, jobject,
        jlong renderNodePtr, jobject surfaceview) {
    class SurfaceViewPositionUpdater : public RenderNode::PositionListener {
    public:
        SurfaceViewPositionUpdater(JNIEnv* env, jobject surfaceview) {
            env->GetJavaVM(&mVm);
            mWeakRef = env->NewWeakGlobalRef(surfaceview);
        }

        virtual ~SurfaceViewPositionUpdater() {
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
            bounds.left -= info.windowInsetLeft;
            bounds.right -= info.windowInsetLeft;
            bounds.top -= info.windowInsetTop;
            bounds.bottom -= info.windowInsetTop;

            if (CC_LIKELY(transform.isPureTranslate())) {
                // snap/round the computed bounds, so they match the rounding behavior
                // of the clear done in SurfaceView#draw().
                bounds.snapToPixelBoundaries();
            } else {
                // Conservatively round out so the punched hole (in the ZOrderOnTop = true case)
                // doesn't extend beyond the other window
                bounds.roundOut();
            }

            incStrong(0);
            auto functor = std::bind(
                std::mem_fn(&SurfaceViewPositionUpdater::doUpdatePositionAsync), this,
                (jlong) info.canvasContext.getFrameNumber(),
                (jint) bounds.left, (jint) bounds.top,
                (jint) bounds.right, (jint) bounds.bottom);

            info.canvasContext.enqueueFrameWork(std::move(functor));
        }

        virtual void onPositionLost(RenderNode& node, const TreeInfo* info) override {
            if (CC_UNLIKELY(!mWeakRef || (info && !info->updateWindowPositions))) return;

            ATRACE_NAME("SurfaceView position lost");
            JNIEnv* env = jnienv();
            jobject localref = env->NewLocalRef(mWeakRef);
            if (CC_UNLIKELY(!localref)) {
                jnienv()->DeleteWeakGlobalRef(mWeakRef);
                mWeakRef = nullptr;
                return;
            }

            env->CallVoidMethod(localref, gSurfaceViewPositionLostMethod,
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
                env->CallVoidMethod(localref, gSurfaceViewPositionUpdateMethod,
                        frameNumber, left, top, right, bottom);
                env->DeleteLocalRef(localref);
            }

            // We need to release ourselves here
            decStrong(0);
        }

        JavaVM* mVm;
        jobject mWeakRef;
    };

    RenderNode* renderNode = reinterpret_cast<RenderNode*>(renderNodePtr);
    renderNode->setPositionListener(new SurfaceViewPositionUpdater(env, surfaceview));
}

// ----------------------------------------------------------------------------
// JNI Glue
// ----------------------------------------------------------------------------

const char* const kClassPathName = "android/view/RenderNode";

static const JNINativeMethod gMethods[] = {
    { "nCreate",               "(Ljava/lang/String;)J", (void*) android_view_RenderNode_create },
    { "nDestroyRenderNode",    "(J)V",    (void*) android_view_RenderNode_destroyRenderNode },
    { "nSetDisplayList",       "(JJ)V",   (void*) android_view_RenderNode_setDisplayList },
    { "nOutput",               "(J)V",    (void*) android_view_RenderNode_output },
    { "nGetDebugSize",         "(J)I",    (void*) android_view_RenderNode_getDebugSize },

    { "nSetLayerType",         "!(JI)Z",  (void*) android_view_RenderNode_setLayerType },
    { "nSetLayerPaint",        "!(JJ)Z",  (void*) android_view_RenderNode_setLayerPaint },
    { "nSetStaticMatrix",      "!(JJ)Z",  (void*) android_view_RenderNode_setStaticMatrix },
    { "nSetAnimationMatrix",   "!(JJ)Z",  (void*) android_view_RenderNode_setAnimationMatrix },
    { "nSetClipToBounds",      "!(JZ)Z",  (void*) android_view_RenderNode_setClipToBounds },
    { "nSetClipBounds",        "!(JIIII)Z", (void*) android_view_RenderNode_setClipBounds },
    { "nSetClipBoundsEmpty",   "!(J)Z",   (void*) android_view_RenderNode_setClipBoundsEmpty },
    { "nSetProjectBackwards",  "!(JZ)Z",  (void*) android_view_RenderNode_setProjectBackwards },
    { "nSetProjectionReceiver","!(JZ)Z",  (void*) android_view_RenderNode_setProjectionReceiver },

    { "nSetOutlineRoundRect",  "!(JIIIIFF)Z", (void*) android_view_RenderNode_setOutlineRoundRect },
    { "nSetOutlineConvexPath", "!(JJF)Z", (void*) android_view_RenderNode_setOutlineConvexPath },
    { "nSetOutlineEmpty",      "!(J)Z",   (void*) android_view_RenderNode_setOutlineEmpty },
    { "nSetOutlineNone",       "!(J)Z",   (void*) android_view_RenderNode_setOutlineNone },
    { "nHasShadow",            "!(J)Z",   (void*) android_view_RenderNode_hasShadow },
    { "nSetClipToOutline",     "!(JZ)Z",  (void*) android_view_RenderNode_setClipToOutline },
    { "nSetRevealClip",        "!(JZFFF)Z", (void*) android_view_RenderNode_setRevealClip },

    { "nSetAlpha",             "!(JF)Z",  (void*) android_view_RenderNode_setAlpha },
    { "nSetHasOverlappingRendering", "!(JZ)Z",
            (void*) android_view_RenderNode_setHasOverlappingRendering },
    { "nSetElevation",         "!(JF)Z",  (void*) android_view_RenderNode_setElevation },
    { "nSetTranslationX",      "!(JF)Z",  (void*) android_view_RenderNode_setTranslationX },
    { "nSetTranslationY",      "!(JF)Z",  (void*) android_view_RenderNode_setTranslationY },
    { "nSetTranslationZ",      "!(JF)Z",  (void*) android_view_RenderNode_setTranslationZ },
    { "nSetRotation",          "!(JF)Z",  (void*) android_view_RenderNode_setRotation },
    { "nSetRotationX",         "!(JF)Z",  (void*) android_view_RenderNode_setRotationX },
    { "nSetRotationY",         "!(JF)Z",  (void*) android_view_RenderNode_setRotationY },
    { "nSetScaleX",            "!(JF)Z",  (void*) android_view_RenderNode_setScaleX },
    { "nSetScaleY",            "!(JF)Z",  (void*) android_view_RenderNode_setScaleY },
    { "nSetPivotX",            "!(JF)Z",  (void*) android_view_RenderNode_setPivotX },
    { "nSetPivotY",            "!(JF)Z",  (void*) android_view_RenderNode_setPivotY },
    { "nSetCameraDistance",    "!(JF)Z",  (void*) android_view_RenderNode_setCameraDistance },
    { "nSetLeft",              "!(JI)Z",  (void*) android_view_RenderNode_setLeft },
    { "nSetTop",               "!(JI)Z",  (void*) android_view_RenderNode_setTop },
    { "nSetRight",             "!(JI)Z",  (void*) android_view_RenderNode_setRight },
    { "nSetBottom",            "!(JI)Z",  (void*) android_view_RenderNode_setBottom },
    { "nSetLeftTopRightBottom","!(JIIII)Z", (void*) android_view_RenderNode_setLeftTopRightBottom },
    { "nOffsetLeftAndRight",   "!(JI)Z",  (void*) android_view_RenderNode_offsetLeftAndRight },
    { "nOffsetTopAndBottom",   "!(JI)Z",  (void*) android_view_RenderNode_offsetTopAndBottom },

    { "nHasOverlappingRendering", "!(J)Z",  (void*) android_view_RenderNode_hasOverlappingRendering },
    { "nGetClipToOutline",        "!(J)Z",  (void*) android_view_RenderNode_getClipToOutline },
    { "nGetAlpha",                "!(J)F",  (void*) android_view_RenderNode_getAlpha },
    { "nGetCameraDistance",       "!(J)F",  (void*) android_view_RenderNode_getCameraDistance },
    { "nGetScaleX",               "!(J)F",  (void*) android_view_RenderNode_getScaleX },
    { "nGetScaleY",               "!(J)F",  (void*) android_view_RenderNode_getScaleY },
    { "nGetElevation",            "!(J)F",  (void*) android_view_RenderNode_getElevation },
    { "nGetTranslationX",         "!(J)F",  (void*) android_view_RenderNode_getTranslationX },
    { "nGetTranslationY",         "!(J)F",  (void*) android_view_RenderNode_getTranslationY },
    { "nGetTranslationZ",         "!(J)F",  (void*) android_view_RenderNode_getTranslationZ },
    { "nGetRotation",             "!(J)F",  (void*) android_view_RenderNode_getRotation },
    { "nGetRotationX",            "!(J)F",  (void*) android_view_RenderNode_getRotationX },
    { "nGetRotationY",            "!(J)F",  (void*) android_view_RenderNode_getRotationY },
    { "nIsPivotExplicitlySet",    "!(J)Z",  (void*) android_view_RenderNode_isPivotExplicitlySet },
    { "nHasIdentityMatrix",       "!(J)Z",  (void*) android_view_RenderNode_hasIdentityMatrix },

    { "nGetTransformMatrix",       "!(JJ)V", (void*) android_view_RenderNode_getTransformMatrix },
    { "nGetInverseTransformMatrix","!(JJ)V", (void*) android_view_RenderNode_getInverseTransformMatrix },

    { "nGetPivotX",                "!(J)F",  (void*) android_view_RenderNode_getPivotX },
    { "nGetPivotY",                "!(J)F",  (void*) android_view_RenderNode_getPivotY },

    { "nAddAnimator",              "(JJ)V", (void*) android_view_RenderNode_addAnimator },
    { "nEndAllAnimators",          "(J)V", (void*) android_view_RenderNode_endAllAnimators },

    { "nRequestPositionUpdates",   "(JLandroid/view/SurfaceView;)V", (void*) android_view_RenderNode_requestPositionUpdates },
};

int register_android_view_RenderNode(JNIEnv* env) {
    jclass clazz = FindClassOrDie(env, "android/view/SurfaceView");
    gSurfaceViewPositionUpdateMethod = GetMethodIDOrDie(env, clazz,
            "updateWindowPosition_renderWorker", "(JIIII)V");
    gSurfaceViewPositionLostMethod = GetMethodIDOrDie(env, clazz,
            "windowPositionLost_uiRtSync", "(J)V");
    clazz = FindClassOrDie(env, "android/view/RenderNode");
    gOnRenderNodeDetached = GetMethodIDOrDie(env, clazz,
            "onRenderNodeDetached", "()V");
    return RegisterMethodsOrDie(env, kClassPathName, gMethods, NELEM(gMethods));
}

};

