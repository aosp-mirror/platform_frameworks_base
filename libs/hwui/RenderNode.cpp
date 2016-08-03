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

#include "RenderNode.h"

#include "BakedOpRenderer.h"
#include "DamageAccumulator.h"
#include "Debug.h"
#include "OpDumper.h"
#include "RecordedOp.h"
#include "TreeInfo.h"
#include "utils/MathUtils.h"
#include "utils/TraceUtils.h"
#include "VectorDrawable.h"
#include "renderstate/RenderState.h"
#include "renderthread/CanvasContext.h"

#include "protos/hwui.pb.h"
#include "protos/ProtoHelpers.h"

#include <algorithm>
#include <sstream>
#include <string>

namespace android {
namespace uirenderer {

RenderNode::RenderNode()
        : mDirtyPropertyFields(0)
        , mNeedsDisplayListSync(false)
        , mDisplayList(nullptr)
        , mStagingDisplayList(nullptr)
        , mAnimatorManager(*this)
        , mParentCount(0) {
}

RenderNode::~RenderNode() {
    deleteDisplayList(nullptr);
    delete mStagingDisplayList;
    LOG_ALWAYS_FATAL_IF(mLayer, "layer missed detachment!");
}

void RenderNode::setStagingDisplayList(DisplayList* displayList, TreeObserver* observer) {
    mNeedsDisplayListSync = true;
    delete mStagingDisplayList;
    mStagingDisplayList = displayList;
    // If mParentCount == 0 we are the sole reference to this RenderNode,
    // so immediately free the old display list
    if (!mParentCount && !mStagingDisplayList) {
        deleteDisplayList(observer);
    }
}

/**
 * This function is a simplified version of replay(), where we simply retrieve and log the
 * display list. This function should remain in sync with the replay() function.
 */
void RenderNode::output(uint32_t level, const char* label) {
    ALOGD("%s (%s %p%s%s%s%s%s)",
            label,
            getName(),
            this,
            (MathUtils::isZero(properties().getAlpha()) ? ", zero alpha" : ""),
            (properties().hasShadow() ? ", casting shadow" : ""),
            (isRenderable() ? "" : ", empty"),
            (properties().getProjectBackwards() ? ", projected" : ""),
            (mLayer != nullptr ? ", on HW Layer" : ""));
    properties().debugOutputProperties(level + 1);

    if (mDisplayList) {
        for (auto&& op : mDisplayList->getOps()) {
            std::stringstream strout;
            OpDumper::dump(*op, strout, level + 1);
            if (op->opId == RecordedOpId::RenderNodeOp) {
                auto rnOp = reinterpret_cast<const RenderNodeOp*>(op);
                rnOp->renderNode->output(level + 1, strout.str().c_str());
            } else {
                ALOGD("%s", strout.str().c_str());
            }
        }
    }
    ALOGD("%*s/RenderNode(%s %p)", level * 2, "", getName(), this);
}

void RenderNode::copyTo(proto::RenderNode *pnode) {
    pnode->set_id(static_cast<uint64_t>(
            reinterpret_cast<uintptr_t>(this)));
    pnode->set_name(mName.string(), mName.length());

    proto::RenderProperties* pprops = pnode->mutable_properties();
    pprops->set_left(properties().getLeft());
    pprops->set_top(properties().getTop());
    pprops->set_right(properties().getRight());
    pprops->set_bottom(properties().getBottom());
    pprops->set_clip_flags(properties().getClippingFlags());
    pprops->set_alpha(properties().getAlpha());
    pprops->set_translation_x(properties().getTranslationX());
    pprops->set_translation_y(properties().getTranslationY());
    pprops->set_translation_z(properties().getTranslationZ());
    pprops->set_elevation(properties().getElevation());
    pprops->set_rotation(properties().getRotation());
    pprops->set_rotation_x(properties().getRotationX());
    pprops->set_rotation_y(properties().getRotationY());
    pprops->set_scale_x(properties().getScaleX());
    pprops->set_scale_y(properties().getScaleY());
    pprops->set_pivot_x(properties().getPivotX());
    pprops->set_pivot_y(properties().getPivotY());
    pprops->set_has_overlapping_rendering(properties().getHasOverlappingRendering());
    pprops->set_pivot_explicitly_set(properties().isPivotExplicitlySet());
    pprops->set_project_backwards(properties().getProjectBackwards());
    pprops->set_projection_receiver(properties().isProjectionReceiver());
    set(pprops->mutable_clip_bounds(), properties().getClipBounds());

    const Outline& outline = properties().getOutline();
    if (outline.getType() != Outline::Type::None) {
        proto::Outline* poutline = pprops->mutable_outline();
        poutline->clear_path();
        if (outline.getType() == Outline::Type::Empty) {
            poutline->set_type(proto::Outline_Type_Empty);
        } else if (outline.getType() == Outline::Type::ConvexPath) {
            poutline->set_type(proto::Outline_Type_ConvexPath);
            if (const SkPath* path = outline.getPath()) {
                set(poutline->mutable_path(), *path);
            }
        } else if (outline.getType() == Outline::Type::RoundRect) {
            poutline->set_type(proto::Outline_Type_RoundRect);
        } else {
            ALOGW("Uknown outline type! %d", static_cast<int>(outline.getType()));
            poutline->set_type(proto::Outline_Type_None);
        }
        poutline->set_should_clip(outline.getShouldClip());
        poutline->set_alpha(outline.getAlpha());
        poutline->set_radius(outline.getRadius());
        set(poutline->mutable_bounds(), outline.getBounds());
    } else {
        pprops->clear_outline();
    }

    const RevealClip& revealClip = properties().getRevealClip();
    if (revealClip.willClip()) {
        proto::RevealClip* prevealClip = pprops->mutable_reveal_clip();
        prevealClip->set_x(revealClip.getX());
        prevealClip->set_y(revealClip.getY());
        prevealClip->set_radius(revealClip.getRadius());
    } else {
        pprops->clear_reveal_clip();
    }

    pnode->clear_children();
    if (mDisplayList) {
        for (auto&& child : mDisplayList->getChildren()) {
            child->renderNode->copyTo(pnode->add_children());
        }
    }
}

int RenderNode::getDebugSize() {
    int size = sizeof(RenderNode);
    if (mStagingDisplayList) {
        size += mStagingDisplayList->getUsedSize();
    }
    if (mDisplayList && mDisplayList != mStagingDisplayList) {
        size += mDisplayList->getUsedSize();
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

void RenderNode::removeAnimator(const sp<BaseRenderNodeAnimator>& animator) {
    mAnimatorManager.removeAnimator(animator);
}

void RenderNode::damageSelf(TreeInfo& info) {
    if (isRenderable()) {
        if (properties().getClipDamageToBounds()) {
            info.damageAccumulator->dirty(0, 0, properties().getWidth(), properties().getHeight());
        } else {
            // Hope this is big enough?
            // TODO: Get this from the display list ops or something
            info.damageAccumulator->dirty(DIRTY_MIN, DIRTY_MIN, DIRTY_MAX, DIRTY_MAX);
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

static OffscreenBuffer* createLayer(RenderState& renderState, uint32_t width, uint32_t height) {
    return renderState.layerPool().get(renderState, width, height);
}

static void destroyLayer(OffscreenBuffer* layer) {
    RenderState& renderState = layer->renderState;
    renderState.layerPool().putOrDelete(layer);
}

static bool layerMatchesWidthAndHeight(OffscreenBuffer* layer, int width, int height) {
    return layer->viewportWidth == (uint32_t) width && layer->viewportHeight == (uint32_t)height;
}

void RenderNode::pushLayerUpdate(TreeInfo& info) {
    LayerType layerType = properties().effectiveLayerType();
    // If we are not a layer OR we cannot be rendered (eg, view was detached)
    // we need to destroy any Layers we may have had previously
    if (CC_LIKELY(layerType != LayerType::RenderLayer)
            || CC_UNLIKELY(!isRenderable())
            || CC_UNLIKELY(properties().getWidth() == 0)
            || CC_UNLIKELY(properties().getHeight() == 0)) {
        if (CC_UNLIKELY(mLayer)) {
            destroyLayer(mLayer);
            mLayer = nullptr;
        }
        return;
    }

    bool transformUpdateNeeded = false;
    if (!mLayer) {
        mLayer = createLayer(info.canvasContext.getRenderState(), getWidth(), getHeight());
        damageSelf(info);
        transformUpdateNeeded = true;
    } else if (!layerMatchesWidthAndHeight(mLayer, getWidth(), getHeight())) {
        // TODO: remove now irrelevant, currently enqueued damage (respecting damage ordering)
        // Or, ideally, maintain damage between frames on node/layer so ordering is always correct
        RenderState& renderState = mLayer->renderState;
        if (properties().fitsOnLayer()) {
            mLayer = renderState.layerPool().resize(mLayer, getWidth(), getHeight());
        } else {
            destroyLayer(mLayer);
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
            std::ostringstream err;
            err << "Unable to create layer for " << getName();
            const int maxTextureSize = Caches::getInstance().maxTextureSize;
            if (getWidth() > maxTextureSize || getHeight() > maxTextureSize) {
                err << ", size " << getWidth() << "x" << getHeight()
                        << " exceeds max size " << maxTextureSize;
            } else {
                err << ", see logcat for more info";
            }
            info.errorHandler->onError(err.str());
        }
        return;
    }

    if (transformUpdateNeeded && mLayer) {
        // update the transform in window of the layer to reset its origin wrt light source position
        Matrix4 windowTransform;
        info.damageAccumulator->computeCurrentTransform(&windowTransform);
        mLayer->setWindowTransform(windowTransform);
    }

    info.layerUpdateQueue->enqueueLayerWithDamage(this, dirty);

    // There might be prefetched layers that need to be accounted for.
    // That might be us, so tell CanvasContext that this layer is in the
    // tree and should not be destroyed.
    info.canvasContext.markLayerInUse(this);
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
    if (info.mode == TreeInfo::MODE_FULL && mStagingDisplayList) {
        willHaveFunctor = !mStagingDisplayList->getFunctors().empty();
    } else if (mDisplayList) {
        willHaveFunctor = !mDisplayList->getFunctors().empty();
    }
    bool childFunctorsNeedLayer = mProperties.prepareForFunctorPresence(
            willHaveFunctor, functorsNeedLayer);

    if (CC_UNLIKELY(mPositionListener.get())) {
        mPositionListener->onPositionUpdated(*this, info);
    }

    prepareLayer(info, animatorDirtyMask);
    if (info.mode == TreeInfo::MODE_FULL) {
        pushStagingDisplayListChanges(info);
    }
    prepareSubTree(info, childFunctorsNeedLayer, mDisplayList);

    if (mDisplayList) {
        for (auto& vectorDrawable : mDisplayList->getVectorDrawables()) {
            // If any vector drawable in the display list needs update, damage the node.
            if (vectorDrawable->isDirty()) {
                damageSelf(info);
            }
            vectorDrawable->setPropertyChangeWillBeConsumed(true);
        }
    }
    pushLayerUpdate(info);

    info.damageAccumulator->popTransform();
}

void RenderNode::syncProperties() {
    mProperties = mStagingProperties;
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
        syncProperties();
        // We could try to be clever and only re-damage if the matrix changed.
        // However, we don't need to worry about that. The cost of over-damaging
        // here is only going to be a single additional map rect of this node
        // plus a rect join(). The parent's transform (and up) will only be
        // performed once.
        info.damageAccumulator->pushTransform(this);
        damageSelf(info);
    }
}

void RenderNode::syncDisplayList(TreeInfo* info) {
    // Make sure we inc first so that we don't fluctuate between 0 and 1,
    // which would thrash the layer cache
    if (mStagingDisplayList) {
        for (auto&& child : mStagingDisplayList->getChildren()) {
            child->renderNode->incParentRefCount();
        }
    }
    deleteDisplayList(info ? info->observer : nullptr, info);
    mDisplayList = mStagingDisplayList;
    mStagingDisplayList = nullptr;
    if (mDisplayList) {
        for (auto& iter : mDisplayList->getFunctors()) {
            (*iter.functor)(DrawGlInfo::kModeSync, nullptr);
        }
        for (auto& vectorDrawable : mDisplayList->getVectorDrawables()) {
            vectorDrawable->syncProperties();
        }
    }
}

void RenderNode::pushStagingDisplayListChanges(TreeInfo& info) {
    if (mNeedsDisplayListSync) {
        mNeedsDisplayListSync = false;
        // Damage with the old display list first then the new one to catch any
        // changes in isRenderable or, in the future, bounds
        damageSelf(info);
        syncDisplayList(&info);
        damageSelf(info);
    }
}

void RenderNode::deleteDisplayList(TreeObserver* observer, TreeInfo* info) {
    if (mDisplayList) {
        for (auto&& child : mDisplayList->getChildren()) {
            child->renderNode->decParentRefCount(observer, info);
        }
    }
    delete mDisplayList;
    mDisplayList = nullptr;
}

void RenderNode::prepareSubTree(TreeInfo& info, bool functorsNeedLayer, DisplayList* subtree) {
    if (subtree) {
        TextureCache& cache = Caches::getInstance().textureCache;
        info.out.hasFunctors |= subtree->getFunctors().size();
        for (auto&& bitmapResource : subtree->getBitmapResources()) {
            void* ownerToken = &info.canvasContext;
            info.prepareTextures = cache.prefetchAndMarkInUse(ownerToken, bitmapResource);
        }
        for (auto&& op : subtree->getChildren()) {
            RenderNode* childNode = op->renderNode;
            info.damageAccumulator->pushTransform(&op->localMatrix);
            bool childFunctorsNeedLayer = functorsNeedLayer; // TODO! || op->mRecordedWithPotentialStencilClip;
            childNode->prepareTreeImpl(info, childFunctorsNeedLayer);
            info.damageAccumulator->popTransform();
        }
    }
}

void RenderNode::destroyHardwareResources(TreeObserver* observer, TreeInfo* info) {
    if (mLayer) {
        destroyLayer(mLayer);
        mLayer = nullptr;
    }
    if (mDisplayList) {
        for (auto&& child : mDisplayList->getChildren()) {
            child->renderNode->destroyHardwareResources(observer, info);
        }
        if (mNeedsDisplayListSync) {
            // Next prepare tree we are going to push a new display list, so we can
            // drop our current one now
            deleteDisplayList(observer, info);
        }
    }
}

void RenderNode::decParentRefCount(TreeObserver* observer, TreeInfo* info) {
    LOG_ALWAYS_FATAL_IF(!mParentCount, "already 0!");
    mParentCount--;
    if (!mParentCount) {
        if (observer) {
            observer->onMaybeRemovedFromTree(this);
        }
        if (CC_UNLIKELY(mPositionListener.get())) {
            mPositionListener->onPositionLost(*this, info);
        }
        // If a child of ours is being attached to our parent then this will incorrectly
        // destroy its hardware resources. However, this situation is highly unlikely
        // and the failure is "just" that the layer is re-created, so this should
        // be safe enough
        destroyHardwareResources(observer, info);
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
    if (mDisplayList == nullptr) return;
    for (unsigned int i = 0; i < mDisplayList->getChildren().size(); i++) {
        RenderNodeOp* childOp = mDisplayList->getChildren()[i];
        childOp->renderNode->computeOrderingImpl(childOp, &mProjectedNodes, &mat4::identity());
    }
}

void RenderNode::computeOrderingImpl(
        RenderNodeOp* opState,
        std::vector<RenderNodeOp*>* compositedChildrenOfProjectionSurface,
        const mat4* transformFromProjectionSurface) {
    mProjectedNodes.clear();
    if (mDisplayList == nullptr || mDisplayList->isEmpty()) return;

    // TODO: should avoid this calculation in most cases
    // TODO: just calculate single matrix, down to all leaf composited elements
    Matrix4 localTransformFromProjectionSurface(*transformFromProjectionSurface);
    localTransformFromProjectionSurface.multiply(opState->localMatrix);

    if (properties().getProjectBackwards()) {
        // composited projectee, flag for out of order draw, save matrix, and store in proj surface
        opState->skipInOrderDraw = true;
        opState->transformFromCompositingAncestor = localTransformFromProjectionSurface;
        compositedChildrenOfProjectionSurface->push_back(opState);
    } else {
        // standard in order draw
        opState->skipInOrderDraw = false;
    }

    if (mDisplayList->getChildren().size() > 0) {
        const bool isProjectionReceiver = mDisplayList->projectionReceiveIndex >= 0;
        bool haveAppliedPropertiesToProjection = false;
        for (unsigned int i = 0; i < mDisplayList->getChildren().size(); i++) {
            RenderNodeOp* childOp = mDisplayList->getChildren()[i];
            RenderNode* child = childOp->renderNode;

            std::vector<RenderNodeOp*>* projectionChildren = nullptr;
            const mat4* projectionTransform = nullptr;
            if (isProjectionReceiver && !child->properties().getProjectBackwards()) {
                // if receiving projections, collect projecting descendant

                // Note that if a direct descendant is projecting backwards, we pass its
                // grandparent projection collection, since it shouldn't project onto its
                // parent, where it will already be drawing.
                projectionChildren = &mProjectedNodes;
                projectionTransform = &mat4::identity();
            } else {
                if (!haveAppliedPropertiesToProjection) {
                    applyViewPropertyTransforms(localTransformFromProjectionSurface);
                    haveAppliedPropertiesToProjection = true;
                }
                projectionChildren = compositedChildrenOfProjectionSurface;
                projectionTransform = &localTransformFromProjectionSurface;
            }
            child->computeOrderingImpl(childOp, projectionChildren, projectionTransform);
        }
    }
}

} /* namespace uirenderer */
} /* namespace android */
