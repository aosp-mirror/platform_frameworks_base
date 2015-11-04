/*
 * Copyright (C) 2014 The Android Open Source Project
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
#define LOG_TAG "OpenGLRenderer"

#include "RenderNode.h"

#include <algorithm>
#include <string>

#include <SkCanvas.h>
#include <algorithm>


#include "DamageAccumulator.h"
#include "Debug.h"
#include "DisplayListOp.h"
#include "LayerRenderer.h"
#include "OpenGLRenderer.h"
#include "TreeInfo.h"
#include "utils/MathUtils.h"
#include "utils/TraceUtils.h"
#include "renderthread/CanvasContext.h"

namespace android {
namespace uirenderer {

void RenderNode::debugDumpLayers(const char* prefix) {
    if (mLayer) {
        ALOGD("%sNode %p (%s) has layer %p (fbo = %u, wasBuildLayered = %s)",
                prefix, this, getName(), mLayer, mLayer->getFbo(),
                mLayer->wasBuildLayered ? "true" : "false");
    }
    if (mDisplayListData) {
        for (size_t i = 0; i < mDisplayListData->children().size(); i++) {
            mDisplayListData->children()[i]->mRenderNode->debugDumpLayers(prefix);
        }
    }
}

RenderNode::RenderNode()
        : mDirtyPropertyFields(0)
        , mNeedsDisplayListDataSync(false)
        , mDisplayListData(nullptr)
        , mStagingDisplayListData(nullptr)
        , mAnimatorManager(*this)
        , mLayer(nullptr)
        , mParentCount(0) {
}

RenderNode::~RenderNode() {
    deleteDisplayListData();
    delete mStagingDisplayListData;
    if (mLayer) {
        ALOGW("Memory Warning: Layer %p missed its detachment, held on to for far too long!", mLayer);
        mLayer->postDecStrong();
        mLayer = nullptr;
    }
}

void RenderNode::setStagingDisplayList(DisplayListData* data) {
    mNeedsDisplayListDataSync = true;
    delete mStagingDisplayListData;
    mStagingDisplayListData = data;
}

/**
 * This function is a simplified version of replay(), where we simply retrieve and log the
 * display list. This function should remain in sync with the replay() function.
 */
void RenderNode::output(uint32_t level) {
    ALOGD("%*sStart display list (%p, %s%s%s%s%s%s)", (level - 1) * 2, "", this,
            getName(),
            (MathUtils::isZero(properties().getAlpha()) ? ", zero alpha" : ""),
            (properties().hasShadow() ? ", casting shadow" : ""),
            (isRenderable() ? "" : ", empty"),
            (properties().getProjectBackwards() ? ", projected" : ""),
            (mLayer != nullptr ? ", on HW Layer" : ""));
    ALOGD("%*s%s %d", level * 2, "", "Save",
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);

    properties().debugOutputProperties(level);
    int flags = DisplayListOp::kOpLogFlag_Recurse;
    if (mDisplayListData) {
        // TODO: consider printing the chunk boundaries here
        for (unsigned int i = 0; i < mDisplayListData->displayListOps.size(); i++) {
            mDisplayListData->displayListOps[i]->output(level, flags);
        }
    }

    ALOGD("%*sDone (%p, %s)", (level - 1) * 2, "", this, getName());
}

int RenderNode::getDebugSize() {
    int size = sizeof(RenderNode);
    if (mStagingDisplayListData) {
        size += mStagingDisplayListData->getUsedSize();
    }
    if (mDisplayListData && mDisplayListData != mStagingDisplayListData) {
        size += mDisplayListData->getUsedSize();
    }
    return size;
}

void RenderNode::prepareTree(TreeInfo& info) {
    ATRACE_CALL();
    LOG_ALWAYS_FATAL_IF(!info.damageAccumulator, "DamageAccumulator missing");

    // Functors don't correctly handle stencil usage of overdraw debugging - shove 'em in a layer.
    bool functorsNeedLayer = Properties::debugOverdraw;

    prepareTreeImpl(info, functorsNeedLayer);
}

void RenderNode::addAnimator(const sp<BaseRenderNodeAnimator>& animator) {
    mAnimatorManager.addAnimator(animator);
}

void RenderNode::damageSelf(TreeInfo& info) {
    if (isRenderable()) {
        if (properties().getClipDamageToBounds()) {
            info.damageAccumulator->dirty(0, 0, properties().getWidth(), properties().getHeight());
        } else {
            // Hope this is big enough?
            // TODO: Get this from the display list ops or something
            info.damageAccumulator->dirty(INT_MIN, INT_MIN, INT_MAX, INT_MAX);
        }
    }
}

void RenderNode::prepareLayer(TreeInfo& info, uint32_t dirtyMask) {
    LayerType layerType = properties().effectiveLayerType();
    if (CC_UNLIKELY(layerType == LayerType::RenderLayer)) {
        // Damage applied so far needs to affect our parent, but does not require
        // the layer to be updated. So we pop/push here to clear out the current
        // damage and get a clean state for display list or children updates to
        // affect, which will require the layer to be updated
        info.damageAccumulator->popTransform();
        info.damageAccumulator->pushTransform(this);
        if (dirtyMask & DISPLAY_LIST) {
            damageSelf(info);
        }
    }
}

void RenderNode::pushLayerUpdate(TreeInfo& info) {
    LayerType layerType = properties().effectiveLayerType();
    // If we are not a layer OR we cannot be rendered (eg, view was detached)
    // we need to destroy any Layers we may have had previously
    if (CC_LIKELY(layerType != LayerType::RenderLayer) || CC_UNLIKELY(!isRenderable())) {
        if (CC_UNLIKELY(mLayer)) {
            LayerRenderer::destroyLayer(mLayer);
            mLayer = nullptr;
        }
        return;
    }

    bool transformUpdateNeeded = false;
    if (!mLayer) {
        mLayer = LayerRenderer::createRenderLayer(info.renderState, getWidth(), getHeight());
        applyLayerPropertiesToLayer(info);
        damageSelf(info);
        transformUpdateNeeded = true;
    } else if (mLayer->layer.getWidth() != getWidth() || mLayer->layer.getHeight() != getHeight()) {
        if (!LayerRenderer::resizeLayer(mLayer, getWidth(), getHeight())) {
            LayerRenderer::destroyLayer(mLayer);
            mLayer = nullptr;
        }
        damageSelf(info);
        transformUpdateNeeded = true;
    }

    SkRect dirty;
    info.damageAccumulator->peekAtDirty(&dirty);

    if (!mLayer) {
        Caches::getInstance().dumpMemoryUsage();
        if (info.errorHandler) {
            std::string msg = "Unable to create layer for ";
            msg += getName();
            info.errorHandler->onError(msg);
        }
        return;
    }

    if (transformUpdateNeeded) {
        // update the transform in window of the layer to reset its origin wrt light source position
        Matrix4 windowTransform;
        info.damageAccumulator->computeCurrentTransform(&windowTransform);
        mLayer->setWindowTransform(windowTransform);
    }

    if (dirty.intersect(0, 0, getWidth(), getHeight())) {
        dirty.roundOut(&dirty);
        mLayer->updateDeferred(this, dirty.fLeft, dirty.fTop, dirty.fRight, dirty.fBottom);
    }
    // This is not inside the above if because we may have called
    // updateDeferred on a previous prepare pass that didn't have a renderer
    if (info.renderer && mLayer->deferredUpdateScheduled) {
        info.renderer->pushLayerUpdate(mLayer);
    }

    if (info.canvasContext) {
        // There might be prefetched layers that need to be accounted for.
        // That might be us, so tell CanvasContext that this layer is in the
        // tree and should not be destroyed.
        info.canvasContext->markLayerInUse(this);
    }
}

/**
 * Traverse down the the draw tree to prepare for a frame.
 *
 * MODE_FULL = UI Thread-driven (thus properties must be synced), otherwise RT driven
 *
 * While traversing down the tree, functorsNeedLayer flag is set to true if anything that uses the
 * stencil buffer may be needed. Views that use a functor to draw will be forced onto a layer.
 */
void RenderNode::prepareTreeImpl(TreeInfo& info, bool functorsNeedLayer) {
    info.damageAccumulator->pushTransform(this);

    if (info.mode == TreeInfo::MODE_FULL) {
        pushStagingPropertiesChanges(info);
    }
    uint32_t animatorDirtyMask = 0;
    if (CC_LIKELY(info.runAnimations)) {
        animatorDirtyMask = mAnimatorManager.animate(info);
    }

    bool willHaveFunctor = false;
    if (info.mode == TreeInfo::MODE_FULL && mStagingDisplayListData) {
        willHaveFunctor = !mStagingDisplayListData->functors.isEmpty();
    } else if (mDisplayListData) {
        willHaveFunctor = !mDisplayListData->functors.isEmpty();
    }
    bool childFunctorsNeedLayer = mProperties.prepareForFunctorPresence(
            willHaveFunctor, functorsNeedLayer);

    prepareLayer(info, animatorDirtyMask);
    if (info.mode == TreeInfo::MODE_FULL) {
        pushStagingDisplayListChanges(info);
    }
    prepareSubTree(info, childFunctorsNeedLayer, mDisplayListData);
    pushLayerUpdate(info);

    info.damageAccumulator->popTransform();
}

void RenderNode::pushStagingPropertiesChanges(TreeInfo& info) {
    // Push the animators first so that setupStartValueIfNecessary() is called
    // before properties() is trampled by stagingProperties(), as they are
    // required by some animators.
    if (CC_LIKELY(info.runAnimations)) {
        mAnimatorManager.pushStaging();
    }
    if (mDirtyPropertyFields) {
        mDirtyPropertyFields = 0;
        damageSelf(info);
        info.damageAccumulator->popTransform();
        mProperties = mStagingProperties;
        applyLayerPropertiesToLayer(info);
        // We could try to be clever and only re-damage if the matrix changed.
        // However, we don't need to worry about that. The cost of over-damaging
        // here is only going to be a single additional map rect of this node
        // plus a rect join(). The parent's transform (and up) will only be
        // performed once.
        info.damageAccumulator->pushTransform(this);
        damageSelf(info);
    }
}

void RenderNode::applyLayerPropertiesToLayer(TreeInfo& info) {
    if (CC_LIKELY(!mLayer)) return;

    const LayerProperties& props = properties().layerProperties();
    mLayer->setAlpha(props.alpha(), props.xferMode());
    mLayer->setColorFilter(props.colorFilter());
    mLayer->setBlend(props.needsBlending());
}

void RenderNode::pushStagingDisplayListChanges(TreeInfo& info) {
    if (mNeedsDisplayListDataSync) {
        mNeedsDisplayListDataSync = false;
        // Make sure we inc first so that we don't fluctuate between 0 and 1,
        // which would thrash the layer cache
        if (mStagingDisplayListData) {
            for (size_t i = 0; i < mStagingDisplayListData->children().size(); i++) {
                mStagingDisplayListData->children()[i]->mRenderNode->incParentRefCount();
            }
        }
        // Damage with the old display list first then the new one to catch any
        // changes in isRenderable or, in the future, bounds
        damageSelf(info);
        deleteDisplayListData();
        // TODO: Remove this caches stuff
        if (mStagingDisplayListData && mStagingDisplayListData->functors.size()) {
            Caches::getInstance().registerFunctors(mStagingDisplayListData->functors.size());
        }
        mDisplayListData = mStagingDisplayListData;
        mStagingDisplayListData = nullptr;
        if (mDisplayListData) {
            for (size_t i = 0; i < mDisplayListData->functors.size(); i++) {
                (*mDisplayListData->functors[i])(DrawGlInfo::kModeSync, nullptr);
            }
        }
        damageSelf(info);
    }
}

void RenderNode::deleteDisplayListData() {
    if (mDisplayListData) {
        for (size_t i = 0; i < mDisplayListData->children().size(); i++) {
            mDisplayListData->children()[i]->mRenderNode->decParentRefCount();
        }
        if (mDisplayListData->functors.size()) {
            Caches::getInstance().unregisterFunctors(mDisplayListData->functors.size());
        }
    }
    delete mDisplayListData;
    mDisplayListData = nullptr;
}

void RenderNode::prepareSubTree(TreeInfo& info, bool functorsNeedLayer, DisplayListData* subtree) {
    if (subtree) {
        TextureCache& cache = Caches::getInstance().textureCache;
        info.out.hasFunctors |= subtree->functors.size();
        for (size_t i = 0; info.prepareTextures && i < subtree->bitmapResources.size(); i++) {
            info.prepareTextures = cache.prefetchAndMarkInUse(
                    info.canvasContext, subtree->bitmapResources[i]);
        }
        for (size_t i = 0; i < subtree->children().size(); i++) {
            DrawRenderNodeOp* op = subtree->children()[i];
            RenderNode* childNode = op->mRenderNode;
            info.damageAccumulator->pushTransform(&op->mTransformFromParent);
            bool childFunctorsNeedLayer = functorsNeedLayer
                    // Recorded with non-rect clip, or canvas-rotated by parent
                    || op->mRecordedWithPotentialStencilClip;
            childNode->prepareTreeImpl(info, childFunctorsNeedLayer);
            info.damageAccumulator->popTransform();
        }
    }
}

void RenderNode::destroyHardwareResources() {
    if (mLayer) {
        LayerRenderer::destroyLayer(mLayer);
        mLayer = nullptr;
    }
    if (mDisplayListData) {
        for (size_t i = 0; i < mDisplayListData->children().size(); i++) {
            mDisplayListData->children()[i]->mRenderNode->destroyHardwareResources();
        }
        if (mNeedsDisplayListDataSync) {
            // Next prepare tree we are going to push a new display list, so we can
            // drop our current one now
            deleteDisplayListData();
        }
    }
}

void RenderNode::decParentRefCount() {
    LOG_ALWAYS_FATAL_IF(!mParentCount, "already 0!");
    mParentCount--;
    if (!mParentCount) {
        // If a child of ours is being attached to our parent then this will incorrectly
        // destroy its hardware resources. However, this situation is highly unlikely
        // and the failure is "just" that the layer is re-created, so this should
        // be safe enough
        destroyHardwareResources();
    }
}

/*
 * For property operations, we pass a savecount of 0, since the operations aren't part of the
 * displaylist, and thus don't have to compensate for the record-time/playback-time discrepancy in
 * base saveCount (i.e., how RestoreToCount uses saveCount + properties().getCount())
 */
#define PROPERTY_SAVECOUNT 0

template <class T>
void RenderNode::setViewProperties(OpenGLRenderer& renderer, T& handler) {
#if DEBUG_DISPLAY_LIST
    properties().debugOutputProperties(handler.level() + 1);
#endif
    if (properties().getLeft() != 0 || properties().getTop() != 0) {
        renderer.translate(properties().getLeft(), properties().getTop());
    }
    if (properties().getStaticMatrix()) {
        renderer.concatMatrix(*properties().getStaticMatrix());
    } else if (properties().getAnimationMatrix()) {
        renderer.concatMatrix(*properties().getAnimationMatrix());
    }
    if (properties().hasTransformMatrix()) {
        if (properties().isTransformTranslateOnly()) {
            renderer.translate(properties().getTranslationX(), properties().getTranslationY());
        } else {
            renderer.concatMatrix(*properties().getTransformMatrix());
        }
    }
    const bool isLayer = properties().effectiveLayerType() != LayerType::None;
    int clipFlags = properties().getClippingFlags();
    if (properties().getAlpha() < 1) {
        if (isLayer) {
            clipFlags &= ~CLIP_TO_BOUNDS; // bounds clipping done by layer
        }
        if (CC_LIKELY(isLayer || !properties().getHasOverlappingRendering())) {
            // simply scale rendering content's alpha
            renderer.scaleAlpha(properties().getAlpha());
        } else {
            // savelayer needed to create an offscreen buffer
            Rect layerBounds(0, 0, getWidth(), getHeight());
            if (clipFlags) {
                properties().getClippingRectForFlags(clipFlags, &layerBounds);
                clipFlags = 0; // all clipping done by savelayer
            }
            SaveLayerOp* op = new (handler.allocator()) SaveLayerOp(
                    layerBounds.left, layerBounds.top,
                    layerBounds.right, layerBounds.bottom,
                    (int) (properties().getAlpha() * 255),
                    SkCanvas::kHasAlphaLayer_SaveFlag | SkCanvas::kClipToLayer_SaveFlag);
            handler(op, PROPERTY_SAVECOUNT, properties().getClipToBounds());
        }

        if (CC_UNLIKELY(ATRACE_ENABLED() && properties().promotedToLayer())) {
            // pretend alpha always causes savelayer to warn about
            // performance problem affecting old versions
            ATRACE_FORMAT("%s alpha caused saveLayer %dx%d", getName(),
                    static_cast<int>(getWidth()),
                    static_cast<int>(getHeight()));
        }
    }
    if (clipFlags) {
        Rect clipRect;
        properties().getClippingRectForFlags(clipFlags, &clipRect);
        ClipRectOp* op = new (handler.allocator()) ClipRectOp(
                clipRect.left, clipRect.top, clipRect.right, clipRect.bottom,
                SkRegion::kIntersect_Op);
        handler(op, PROPERTY_SAVECOUNT, properties().getClipToBounds());
    }

    // TODO: support nesting round rect clips
    if (mProperties.getRevealClip().willClip()) {
        Rect bounds;
        mProperties.getRevealClip().getBounds(&bounds);
        renderer.setClippingRoundRect(handler.allocator(), bounds, mProperties.getRevealClip().getRadius());
    } else if (mProperties.getOutline().willClip()) {
        renderer.setClippingOutline(handler.allocator(), &(mProperties.getOutline()));
    }
}

/**
 * Apply property-based transformations to input matrix
 *
 * If true3dTransform is set to true, the transform applied to the input matrix will use true 4x4
 * matrix computation instead of the Skia 3x3 matrix + camera hackery.
 */
void RenderNode::applyViewPropertyTransforms(mat4& matrix, bool true3dTransform) const {
    if (properties().getLeft() != 0 || properties().getTop() != 0) {
        matrix.translate(properties().getLeft(), properties().getTop());
    }
    if (properties().getStaticMatrix()) {
        mat4 stat(*properties().getStaticMatrix());
        matrix.multiply(stat);
    } else if (properties().getAnimationMatrix()) {
        mat4 anim(*properties().getAnimationMatrix());
        matrix.multiply(anim);
    }

    bool applyTranslationZ = true3dTransform && !MathUtils::isZero(properties().getZ());
    if (properties().hasTransformMatrix() || applyTranslationZ) {
        if (properties().isTransformTranslateOnly()) {
            matrix.translate(properties().getTranslationX(), properties().getTranslationY(),
                    true3dTransform ? properties().getZ() : 0.0f);
        } else {
            if (!true3dTransform) {
                matrix.multiply(*properties().getTransformMatrix());
            } else {
                mat4 true3dMat;
                true3dMat.loadTranslate(
                        properties().getPivotX() + properties().getTranslationX(),
                        properties().getPivotY() + properties().getTranslationY(),
                        properties().getZ());
                true3dMat.rotate(properties().getRotationX(), 1, 0, 0);
                true3dMat.rotate(properties().getRotationY(), 0, 1, 0);
                true3dMat.rotate(properties().getRotation(), 0, 0, 1);
                true3dMat.scale(properties().getScaleX(), properties().getScaleY(), 1);
                true3dMat.translate(-properties().getPivotX(), -properties().getPivotY());

                matrix.multiply(true3dMat);
            }
        }
    }
}

/**
 * Organizes the DisplayList hierarchy to prepare for background projection reordering.
 *
 * This should be called before a call to defer() or drawDisplayList()
 *
 * Each DisplayList that serves as a 3d root builds its list of composited children,
 * which are flagged to not draw in the standard draw loop.
 */
void RenderNode::computeOrdering() {
    ATRACE_CALL();
    mProjectedNodes.clear();

    // TODO: create temporary DDLOp and call computeOrderingImpl on top DisplayList so that
    // transform properties are applied correctly to top level children
    if (mDisplayListData == nullptr) return;
    for (unsigned int i = 0; i < mDisplayListData->children().size(); i++) {
        DrawRenderNodeOp* childOp = mDisplayListData->children()[i];
        childOp->mRenderNode->computeOrderingImpl(childOp,
                properties().getOutline().getPath(), &mProjectedNodes, &mat4::identity());
    }
}

void RenderNode::computeOrderingImpl(
        DrawRenderNodeOp* opState,
        const SkPath* outlineOfProjectionSurface,
        Vector<DrawRenderNodeOp*>* compositedChildrenOfProjectionSurface,
        const mat4* transformFromProjectionSurface) {
    mProjectedNodes.clear();
    if (mDisplayListData == nullptr || mDisplayListData->isEmpty()) return;

    // TODO: should avoid this calculation in most cases
    // TODO: just calculate single matrix, down to all leaf composited elements
    Matrix4 localTransformFromProjectionSurface(*transformFromProjectionSurface);
    localTransformFromProjectionSurface.multiply(opState->mTransformFromParent);

    if (properties().getProjectBackwards()) {
        // composited projectee, flag for out of order draw, save matrix, and store in proj surface
        opState->mSkipInOrderDraw = true;
        opState->mTransformFromCompositingAncestor.load(localTransformFromProjectionSurface);
        compositedChildrenOfProjectionSurface->add(opState);
    } else {
        // standard in order draw
        opState->mSkipInOrderDraw = false;
    }

    if (mDisplayListData->children().size() > 0) {
        const bool isProjectionReceiver = mDisplayListData->projectionReceiveIndex >= 0;
        bool haveAppliedPropertiesToProjection = false;
        for (unsigned int i = 0; i < mDisplayListData->children().size(); i++) {
            DrawRenderNodeOp* childOp = mDisplayListData->children()[i];
            RenderNode* child = childOp->mRenderNode;

            const SkPath* projectionOutline = nullptr;
            Vector<DrawRenderNodeOp*>* projectionChildren = nullptr;
            const mat4* projectionTransform = nullptr;
            if (isProjectionReceiver && !child->properties().getProjectBackwards()) {
                // if receiving projections, collect projecting descendant

                // Note that if a direct descendant is projecting backwards, we pass its
                // grandparent projection collection, since it shouldn't project onto its
                // parent, where it will already be drawing.
                projectionOutline = properties().getOutline().getPath();
                projectionChildren = &mProjectedNodes;
                projectionTransform = &mat4::identity();
            } else {
                if (!haveAppliedPropertiesToProjection) {
                    applyViewPropertyTransforms(localTransformFromProjectionSurface);
                    haveAppliedPropertiesToProjection = true;
                }
                projectionOutline = outlineOfProjectionSurface;
                projectionChildren = compositedChildrenOfProjectionSurface;
                projectionTransform = &localTransformFromProjectionSurface;
            }
            child->computeOrderingImpl(childOp,
                    projectionOutline, projectionChildren, projectionTransform);
        }
    }
}

class DeferOperationHandler {
public:
    DeferOperationHandler(DeferStateStruct& deferStruct, int level)
        : mDeferStruct(deferStruct), mLevel(level) {}
    inline void operator()(DisplayListOp* operation, int saveCount, bool clipToBounds) {
        operation->defer(mDeferStruct, saveCount, mLevel, clipToBounds);
    }
    inline LinearAllocator& allocator() { return *(mDeferStruct.mAllocator); }
    inline void startMark(const char* name) {} // do nothing
    inline void endMark() {}
    inline int level() { return mLevel; }
    inline int replayFlags() { return mDeferStruct.mReplayFlags; }
    inline SkPath* allocPathForFrame() { return mDeferStruct.allocPathForFrame(); }

private:
    DeferStateStruct& mDeferStruct;
    const int mLevel;
};

void RenderNode::defer(DeferStateStruct& deferStruct, const int level) {
    DeferOperationHandler handler(deferStruct, level);
    issueOperations<DeferOperationHandler>(deferStruct.mRenderer, handler);
}

class ReplayOperationHandler {
public:
    ReplayOperationHandler(ReplayStateStruct& replayStruct, int level)
        : mReplayStruct(replayStruct), mLevel(level) {}
    inline void operator()(DisplayListOp* operation, int saveCount, bool clipToBounds) {
#if DEBUG_DISPLAY_LIST_OPS_AS_EVENTS
        mReplayStruct.mRenderer.eventMark(operation->name());
#endif
        operation->replay(mReplayStruct, saveCount, mLevel, clipToBounds);
    }
    inline LinearAllocator& allocator() { return *(mReplayStruct.mAllocator); }
    inline void startMark(const char* name) {
        mReplayStruct.mRenderer.startMark(name);
    }
    inline void endMark() {
        mReplayStruct.mRenderer.endMark();
    }
    inline int level() { return mLevel; }
    inline int replayFlags() { return mReplayStruct.mReplayFlags; }
    inline SkPath* allocPathForFrame() { return mReplayStruct.allocPathForFrame(); }

private:
    ReplayStateStruct& mReplayStruct;
    const int mLevel;
};

void RenderNode::replay(ReplayStateStruct& replayStruct, const int level) {
    ReplayOperationHandler handler(replayStruct, level);
    issueOperations<ReplayOperationHandler>(replayStruct.mRenderer, handler);
}

void RenderNode::buildZSortedChildList(const DisplayListData::Chunk& chunk,
        Vector<ZDrawRenderNodeOpPair>& zTranslatedNodes) {
    if (chunk.beginChildIndex == chunk.endChildIndex) return;

    for (unsigned int i = chunk.beginChildIndex; i < chunk.endChildIndex; i++) {
        DrawRenderNodeOp* childOp = mDisplayListData->children()[i];
        RenderNode* child = childOp->mRenderNode;
        float childZ = child->properties().getZ();

        if (!MathUtils::isZero(childZ) && chunk.reorderChildren) {
            zTranslatedNodes.add(ZDrawRenderNodeOpPair(childZ, childOp));
            childOp->mSkipInOrderDraw = true;
        } else if (!child->properties().getProjectBackwards()) {
            // regular, in order drawing DisplayList
            childOp->mSkipInOrderDraw = false;
        }
    }

    // Z sort any 3d children (stable-ness makes z compare fall back to standard drawing order)
    std::stable_sort(zTranslatedNodes.begin(), zTranslatedNodes.end());
}

template <class T>
void RenderNode::issueDrawShadowOperation(const Matrix4& transformFromParent, T& handler) {
    if (properties().getAlpha() <= 0.0f
            || properties().getOutline().getAlpha() <= 0.0f
            || !properties().getOutline().getPath()
            || properties().getScaleX() == 0
            || properties().getScaleY() == 0) {
        // no shadow to draw
        return;
    }

    mat4 shadowMatrixXY(transformFromParent);
    applyViewPropertyTransforms(shadowMatrixXY);

    // Z matrix needs actual 3d transformation, so mapped z values will be correct
    mat4 shadowMatrixZ(transformFromParent);
    applyViewPropertyTransforms(shadowMatrixZ, true);

    const SkPath* casterOutlinePath = properties().getOutline().getPath();
    const SkPath* revealClipPath = properties().getRevealClip().getPath();
    if (revealClipPath && revealClipPath->isEmpty()) return;

    float casterAlpha = properties().getAlpha() * properties().getOutline().getAlpha();


    // holds temporary SkPath to store the result of intersections
    SkPath* frameAllocatedPath = nullptr;
    const SkPath* outlinePath = casterOutlinePath;

    // intersect the outline with the reveal clip, if present
    if (revealClipPath) {
        frameAllocatedPath = handler.allocPathForFrame();

        Op(*outlinePath, *revealClipPath, kIntersect_PathOp, frameAllocatedPath);
        outlinePath = frameAllocatedPath;
    }

    // intersect the outline with the clipBounds, if present
    if (properties().getClippingFlags() & CLIP_TO_CLIP_BOUNDS) {
        if (!frameAllocatedPath) {
            frameAllocatedPath = handler.allocPathForFrame();
        }

        Rect clipBounds;
        properties().getClippingRectForFlags(CLIP_TO_CLIP_BOUNDS, &clipBounds);
        SkPath clipBoundsPath;
        clipBoundsPath.addRect(clipBounds.left, clipBounds.top,
                clipBounds.right, clipBounds.bottom);

        Op(*outlinePath, clipBoundsPath, kIntersect_PathOp, frameAllocatedPath);
        outlinePath = frameAllocatedPath;
    }

    DisplayListOp* shadowOp  = new (handler.allocator()) DrawShadowOp(
            shadowMatrixXY, shadowMatrixZ, casterAlpha, outlinePath);
    handler(shadowOp, PROPERTY_SAVECOUNT, properties().getClipToBounds());
}

#define SHADOW_DELTA 0.1f

template <class T>
void RenderNode::issueOperationsOf3dChildren(ChildrenSelectMode mode,
        const Matrix4& initialTransform, const Vector<ZDrawRenderNodeOpPair>& zTranslatedNodes,
        OpenGLRenderer& renderer, T& handler) {
    const int size = zTranslatedNodes.size();
    if (size == 0
            || (mode == kNegativeZChildren && zTranslatedNodes[0].key > 0.0f)
            || (mode == kPositiveZChildren && zTranslatedNodes[size - 1].key < 0.0f)) {
        // no 3d children to draw
        return;
    }

    // Apply the base transform of the parent of the 3d children. This isolates
    // 3d children of the current chunk from transformations made in previous chunks.
    int rootRestoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag);
    renderer.setMatrix(initialTransform);

    /**
     * Draw shadows and (potential) casters mostly in order, but allow the shadows of casters
     * with very similar Z heights to draw together.
     *
     * This way, if Views A & B have the same Z height and are both casting shadows, the shadows are
     * underneath both, and neither's shadow is drawn on top of the other.
     */
    const size_t nonNegativeIndex = findNonNegativeIndex(zTranslatedNodes);
    size_t drawIndex, shadowIndex, endIndex;
    if (mode == kNegativeZChildren) {
        drawIndex = 0;
        endIndex = nonNegativeIndex;
        shadowIndex = endIndex; // draw no shadows
    } else {
        drawIndex = nonNegativeIndex;
        endIndex = size;
        shadowIndex = drawIndex; // potentially draw shadow for each pos Z child
    }

    DISPLAY_LIST_LOGD("%*s%d %s 3d children:", (handler.level() + 1) * 2, "",
            endIndex - drawIndex, mode == kNegativeZChildren ? "negative" : "positive");

    float lastCasterZ = 0.0f;
    while (shadowIndex < endIndex || drawIndex < endIndex) {
        if (shadowIndex < endIndex) {
            DrawRenderNodeOp* casterOp = zTranslatedNodes[shadowIndex].value;
            RenderNode* caster = casterOp->mRenderNode;
            const float casterZ = zTranslatedNodes[shadowIndex].key;
            // attempt to render the shadow if the caster about to be drawn is its caster,
            // OR if its caster's Z value is similar to the previous potential caster
            if (shadowIndex == drawIndex || casterZ - lastCasterZ < SHADOW_DELTA) {
                caster->issueDrawShadowOperation(casterOp->mTransformFromParent, handler);

                lastCasterZ = casterZ; // must do this even if current caster not casting a shadow
                shadowIndex++;
                continue;
            }
        }

        // only the actual child DL draw needs to be in save/restore,
        // since it modifies the renderer's matrix
        int restoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag);

        DrawRenderNodeOp* childOp = zTranslatedNodes[drawIndex].value;

        renderer.concatMatrix(childOp->mTransformFromParent);
        childOp->mSkipInOrderDraw = false; // this is horrible, I'm so sorry everyone
        handler(childOp, renderer.getSaveCount() - 1, properties().getClipToBounds());
        childOp->mSkipInOrderDraw = true;

        renderer.restoreToCount(restoreTo);
        drawIndex++;
    }
    renderer.restoreToCount(rootRestoreTo);
}

template <class T>
void RenderNode::issueOperationsOfProjectedChildren(OpenGLRenderer& renderer, T& handler) {
    DISPLAY_LIST_LOGD("%*s%d projected children:", (handler.level() + 1) * 2, "", mProjectedNodes.size());
    const SkPath* projectionReceiverOutline = properties().getOutline().getPath();
    int restoreTo = renderer.getSaveCount();

    LinearAllocator& alloc = handler.allocator();
    handler(new (alloc) SaveOp(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag),
            PROPERTY_SAVECOUNT, properties().getClipToBounds());

    // Transform renderer to match background we're projecting onto
    // (by offsetting canvas by translationX/Y of background rendernode, since only those are set)
    const DisplayListOp* op =
            (mDisplayListData->displayListOps[mDisplayListData->projectionReceiveIndex]);
    const DrawRenderNodeOp* backgroundOp = reinterpret_cast<const DrawRenderNodeOp*>(op);
    const RenderProperties& backgroundProps = backgroundOp->mRenderNode->properties();
    renderer.translate(backgroundProps.getTranslationX(), backgroundProps.getTranslationY());

    // If the projection reciever has an outline, we mask projected content to it
    // (which we know, apriori, are all tessellated paths)
    renderer.setProjectionPathMask(alloc, projectionReceiverOutline);

    // draw projected nodes
    for (size_t i = 0; i < mProjectedNodes.size(); i++) {
        DrawRenderNodeOp* childOp = mProjectedNodes[i];

        // matrix save, concat, and restore can be done safely without allocating operations
        int restoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag);
        renderer.concatMatrix(childOp->mTransformFromCompositingAncestor);
        childOp->mSkipInOrderDraw = false; // this is horrible, I'm so sorry everyone
        handler(childOp, renderer.getSaveCount() - 1, properties().getClipToBounds());
        childOp->mSkipInOrderDraw = true;
        renderer.restoreToCount(restoreTo);
    }

    handler(new (alloc) RestoreToCountOp(restoreTo),
            PROPERTY_SAVECOUNT, properties().getClipToBounds());
}

/**
 * This function serves both defer and replay modes, and will organize the displayList's component
 * operations for a single frame:
 *
 * Every 'simple' state operation that affects just the matrix and alpha (or other factors of
 * DeferredDisplayState) may be issued directly to the renderer, but complex operations (with custom
 * defer logic) and operations in displayListOps are issued through the 'handler' which handles the
 * defer vs replay logic, per operation
 */
template <class T>
void RenderNode::issueOperations(OpenGLRenderer& renderer, T& handler) {
    if (mDisplayListData->isEmpty()) {
        DISPLAY_LIST_LOGD("%*sEmpty display list (%p, %s)", handler.level() * 2, "",
                this, getName());
        return;
    }

    const bool drawLayer = (mLayer && (&renderer != mLayer->renderer.get()));
    // If we are updating the contents of mLayer, we don't want to apply any of
    // the RenderNode's properties to this issueOperations pass. Those will all
    // be applied when the layer is drawn, aka when this is true.
    const bool useViewProperties = (!mLayer || drawLayer);
    if (useViewProperties) {
        const Outline& outline = properties().getOutline();
        if (properties().getAlpha() <= 0
                || (outline.getShouldClip() && outline.isEmpty())
                || properties().getScaleX() == 0
                || properties().getScaleY() == 0) {
            DISPLAY_LIST_LOGD("%*sRejected display list (%p, %s)", handler.level() * 2, "",
                    this, getName());
            return;
        }
    }

    handler.startMark(getName());

#if DEBUG_DISPLAY_LIST
    const Rect& clipRect = renderer.getLocalClipBounds();
    DISPLAY_LIST_LOGD("%*sStart display list (%p, %s), localClipBounds: %.0f, %.0f, %.0f, %.0f",
            handler.level() * 2, "", this, getName(),
            clipRect.left, clipRect.top, clipRect.right, clipRect.bottom);
#endif

    LinearAllocator& alloc = handler.allocator();
    int restoreTo = renderer.getSaveCount();
    handler(new (alloc) SaveOp(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag),
            PROPERTY_SAVECOUNT, properties().getClipToBounds());

    DISPLAY_LIST_LOGD("%*sSave %d %d", (handler.level() + 1) * 2, "",
            SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag, restoreTo);

    if (useViewProperties) {
        setViewProperties<T>(renderer, handler);
    }

    bool quickRejected = properties().getClipToBounds()
            && renderer.quickRejectConservative(0, 0, properties().getWidth(), properties().getHeight());
    if (!quickRejected) {
        Matrix4 initialTransform(*(renderer.currentTransform()));
        renderer.setBaseTransform(initialTransform);

        if (drawLayer) {
            handler(new (alloc) DrawLayerOp(mLayer, 0, 0),
                    renderer.getSaveCount() - 1, properties().getClipToBounds());
        } else {
            const int saveCountOffset = renderer.getSaveCount() - 1;
            const int projectionReceiveIndex = mDisplayListData->projectionReceiveIndex;
            for (size_t chunkIndex = 0; chunkIndex < mDisplayListData->getChunks().size(); chunkIndex++) {
                const DisplayListData::Chunk& chunk = mDisplayListData->getChunks()[chunkIndex];

                Vector<ZDrawRenderNodeOpPair> zTranslatedNodes;
                buildZSortedChildList(chunk, zTranslatedNodes);

                issueOperationsOf3dChildren(kNegativeZChildren,
                        initialTransform, zTranslatedNodes, renderer, handler);


                for (size_t opIndex = chunk.beginOpIndex; opIndex < chunk.endOpIndex; opIndex++) {
                    DisplayListOp *op = mDisplayListData->displayListOps[opIndex];
#if DEBUG_DISPLAY_LIST
                    op->output(handler.level() + 1);
#endif
                    handler(op, saveCountOffset, properties().getClipToBounds());

                    if (CC_UNLIKELY(!mProjectedNodes.isEmpty() && projectionReceiveIndex >= 0 &&
                        opIndex == static_cast<size_t>(projectionReceiveIndex))) {
                        issueOperationsOfProjectedChildren(renderer, handler);
                    }
                }

                issueOperationsOf3dChildren(kPositiveZChildren,
                        initialTransform, zTranslatedNodes, renderer, handler);
            }
        }
    }

    DISPLAY_LIST_LOGD("%*sRestoreToCount %d", (handler.level() + 1) * 2, "", restoreTo);
    handler(new (alloc) RestoreToCountOp(restoreTo),
            PROPERTY_SAVECOUNT, properties().getClipToBounds());

    DISPLAY_LIST_LOGD("%*sDone (%p, %s)", handler.level() * 2, "", this, getName());
    handler.endMark();
}

} /* namespace uirenderer */
} /* namespace android */
