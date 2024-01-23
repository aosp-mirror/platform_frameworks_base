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

#include "DamageAccumulator.h"
#include "Debug.h"
#include "Properties.h"
#include "TreeInfo.h"
#include "VectorDrawable.h"
#include "private/hwui/WebViewFunctor.h"
#ifdef __ANDROID__
#include "renderthread/CanvasContext.h"
#else
#include "DamageAccumulator.h"
#include "pipeline/skia/SkiaDisplayList.h"
#endif
#include <gui/TraceUtils.h>
#include "utils/MathUtils.h"
#include "utils/StringUtils.h"

#include <SkPathOps.h>
#include <algorithm>
#include <atomic>
#include <sstream>
#include <string>
#include <ui/FatVector.h>

namespace android {
namespace uirenderer {

// Used for tree mutations that are purely destructive.
// Generic tree mutations should use MarkAndSweepObserver instead
class ImmediateRemoved : public TreeObserver {
public:
    explicit ImmediateRemoved(TreeInfo* info) : mTreeInfo(info) {}

    void onMaybeRemovedFromTree(RenderNode* node) override { node->onRemovedFromTree(mTreeInfo); }

private:
    TreeInfo* mTreeInfo;
};

static int64_t generateId() {
    static std::atomic<int64_t> sNextId{1};
    return sNextId++;
}

RenderNode::RenderNode()
        : mUniqueId(generateId())
        , mDirtyPropertyFields(0)
        , mNeedsDisplayListSync(false)
        , mDisplayList(nullptr)
        , mStagingDisplayList(nullptr)
        , mAnimatorManager(*this)
        , mParentCount(0) {}

RenderNode::~RenderNode() {
    ImmediateRemoved observer(nullptr);
    deleteDisplayList(observer);
    LOG_ALWAYS_FATAL_IF(hasLayer(), "layer missed detachment!");
}

void RenderNode::setStagingDisplayList(DisplayList&& newData) {
    mValid = newData.isValid();
    mNeedsDisplayListSync = true;
    mStagingDisplayList = std::move(newData);
}

void RenderNode::discardStagingDisplayList() {
    setStagingDisplayList(DisplayList());
}

/**
 * This function is a simplified version of replay(), where we simply retrieve and log the
 * display list. This function should remain in sync with the replay() function.
 */
void RenderNode::output() {
    LogcatStream strout;
    strout << "Root";
    output(strout, 0);
}

void RenderNode::output(std::ostream& output, uint32_t level) {
    output << "  (" << getName() << " " << this
           << (MathUtils::isZero(properties().getAlpha()) ? ", zero alpha" : "")
           << (properties().hasShadow() ? ", casting shadow" : "")
           << (isRenderable() ? "" : ", empty")
           << (properties().getProjectBackwards() ? ", projected" : "")
           << (hasLayer() ? ", on HW Layer" : "") << ")" << std::endl;

    properties().debugOutputProperties(output, level + 1);

    mDisplayList.output(output, level);
    output << std::string(level * 2, ' ') << "/RenderNode(" << getName() << " " << this << ")";
    output << std::endl;
}

int RenderNode::getUsageSize() {
    int size = sizeof(RenderNode);
    size += mStagingDisplayList.getUsedSize();
    size += mDisplayList.getUsedSize();
    return size;
}

int RenderNode::getAllocatedSize() {
    int size = sizeof(RenderNode);
    size += mStagingDisplayList.getAllocatedSize();
    size += mDisplayList.getAllocatedSize();
    return size;
}


void RenderNode::prepareTree(TreeInfo& info) {
    ATRACE_CALL();
    LOG_ALWAYS_FATAL_IF(!info.damageAccumulator, "DamageAccumulator missing");
    MarkAndSweepRemoved observer(&info);

    const int before = info.disableForceDark;
    prepareTreeImpl(observer, info, false);
    LOG_ALWAYS_FATAL_IF(before != info.disableForceDark, "Mis-matched force dark");
}

void RenderNode::addAnimator(const sp<BaseRenderNodeAnimator>& animator) {
    mAnimatorManager.addAnimator(animator);
}

void RenderNode::removeAnimator(const sp<BaseRenderNodeAnimator>& animator) {
    mAnimatorManager.removeAnimator(animator);
}

void RenderNode::damageSelf(TreeInfo& info) {
    if (isRenderable()) {
        mDamageGenerationId = info.damageGenerationId;
        if (properties().getClipDamageToBounds()) {
            info.damageAccumulator->dirty(0, 0, properties().getWidth(), properties().getHeight());
        } else {
            // Hope this is big enough?
            // TODO: Get this from the display list ops or something
            info.damageAccumulator->dirty(DIRTY_MIN, DIRTY_MIN, DIRTY_MAX, DIRTY_MAX);
        }
        if (!mIsTextureView) {
            info.out.solelyTextureViewUpdates = false;
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
#ifdef __ANDROID__ // Layoutlib does not support CanvasContext and Layers
    LayerType layerType = properties().effectiveLayerType();
    // If we are not a layer OR we cannot be rendered (eg, view was detached)
    // we need to destroy any Layers we may have had previously
    if (CC_LIKELY(layerType != LayerType::RenderLayer) || CC_UNLIKELY(!isRenderable()) ||
        CC_UNLIKELY(properties().getWidth() == 0) || CC_UNLIKELY(properties().getHeight() == 0) ||
        CC_UNLIKELY(!properties().fitsOnLayer())) {
        if (CC_UNLIKELY(hasLayer())) {
            this->setLayerSurface(nullptr);
        }
        return;
    }

    if (info.canvasContext.createOrUpdateLayer(this, *info.damageAccumulator, info.errorHandler)) {
        damageSelf(info);
    }

    if (!hasLayer()) {
        return;
    }

    SkRect dirty;
    info.damageAccumulator->peekAtDirty(&dirty);
    info.layerUpdateQueue->enqueueLayerWithDamage(this, dirty);
    if (!dirty.isEmpty()) {
      mStretchMask.markDirty();
    }

    // There might be prefetched layers that need to be accounted for.
    // That might be us, so tell CanvasContext that this layer is in the
    // tree and should not be destroyed.
    info.canvasContext.markLayerInUse(this);
#endif
}

/**
 * Traverse down the the draw tree to prepare for a frame.
 *
 * MODE_FULL = UI Thread-driven (thus properties must be synced), otherwise RT driven
 *
 * While traversing down the tree, functorsNeedLayer flag is set to true if anything that uses the
 * stencil buffer may be needed. Views that use a functor to draw will be forced onto a layer.
 */
void RenderNode::prepareTreeImpl(TreeObserver& observer, TreeInfo& info, bool functorsNeedLayer) {
    if (mDamageGenerationId == info.damageGenerationId && mDamageGenerationId != 0) {
        // We hit the same node a second time in the same tree. We don't know the minimal
        // damage rect anymore, so just push the biggest we can onto our parent's transform
        // We push directly onto parent in case we are clipped to bounds but have moved position.
        info.damageAccumulator->dirty(DIRTY_MIN, DIRTY_MIN, DIRTY_MAX, DIRTY_MAX);
    }
    info.damageAccumulator->pushTransform(this);

    if (info.mode == TreeInfo::MODE_FULL) {
        pushStagingPropertiesChanges(info);
    }

    if (!mProperties.getAllowForceDark()) {
        info.disableForceDark++;
    }
    if (!mProperties.layerProperties().getStretchEffect().isEmpty()) {
        info.stretchEffectCount++;
    }

    uint32_t animatorDirtyMask = 0;
    if (CC_LIKELY(info.runAnimations)) {
        animatorDirtyMask = mAnimatorManager.animate(info);
    }

    bool willHaveFunctor = false;
    if (info.mode == TreeInfo::MODE_FULL && mStagingDisplayList) {
        willHaveFunctor = mStagingDisplayList.hasFunctor();
    } else if (mDisplayList) {
        willHaveFunctor = mDisplayList.hasFunctor();
    }
    bool childFunctorsNeedLayer =
            mProperties.prepareForFunctorPresence(willHaveFunctor, functorsNeedLayer);

    if (CC_UNLIKELY(mPositionListener.get())) {
        mPositionListener->onPositionUpdated(*this, info);
    }

    prepareLayer(info, animatorDirtyMask);
    if (info.mode == TreeInfo::MODE_FULL) {
        pushStagingDisplayListChanges(observer, info);
    }

    // always damageSelf when filtering backdrop content, or else the BackdropFilterDrawable will
    // get a wrong snapshot of previous content.
    if (mProperties.layerProperties().getBackdropImageFilter()) {
        damageSelf(info);
    }

    if (mDisplayList) {
        info.out.hasFunctors |= mDisplayList.hasFunctor();
        mHasHolePunches = mDisplayList.hasHolePunches();
        bool isDirty = mDisplayList.prepareListAndChildren(
                observer, info, childFunctorsNeedLayer,
                [this](RenderNode* child, TreeObserver& observer, TreeInfo& info,
                       bool functorsNeedLayer) {
                    child->prepareTreeImpl(observer, info, functorsNeedLayer);
                    mHasHolePunches |= child->hasHolePunches();
                });
        if (isDirty) {
            damageSelf(info);
        }
    } else {
        mHasHolePunches = false;
    }
    pushLayerUpdate(info);

    if (!mProperties.getAllowForceDark()) {
        info.disableForceDark--;
    }
    if (!mProperties.layerProperties().getStretchEffect().isEmpty()) {
        info.stretchEffectCount--;
    }
    info.damageAccumulator->popTransform();
}

void RenderNode::syncProperties() {
    mProperties = mStagingProperties;
}

void RenderNode::pushStagingPropertiesChanges(TreeInfo& info) {
    if (mPositionListenerDirty) {
        mPositionListener = std::move(mStagingPositionListener);
        mStagingPositionListener = nullptr;
        mPositionListenerDirty = false;
    }

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

        auto& layerProperties = mProperties.layerProperties();
        const StretchEffect& stagingStretch = layerProperties.getStretchEffect();
        if (stagingStretch.isEmpty()) {
            mStretchMask.clear();
        }

        if (layerProperties.getImageFilter() == nullptr) {
            mSnapshotResult.snapshot = nullptr;
            mTargetImageFilter = nullptr;
        }

        // We could try to be clever and only re-damage if the matrix changed.
        // However, we don't need to worry about that. The cost of over-damaging
        // here is only going to be a single additional map rect of this node
        // plus a rect join(). The parent's transform (and up) will only be
        // performed once.
        info.damageAccumulator->pushTransform(this);
        damageSelf(info);
    }
}

std::optional<RenderNode::SnapshotResult> RenderNode::updateSnapshotIfRequired(
    GrRecordingContext* context,
    const SkImageFilter* imageFilter,
    const SkIRect& clipBounds
) {
    auto* layerSurface = getLayerSurface();
    if (layerSurface == nullptr) {
        return std::nullopt;
    }

    sk_sp<SkImage> snapshot = layerSurface->makeImageSnapshot();
    const auto subset = SkIRect::MakeWH(properties().getWidth(),
                                        properties().getHeight());
    uint32_t layerSurfaceGenerationId = layerSurface->generationID();
    // If we don't have an ImageFilter just return the snapshot
    if (imageFilter == nullptr) {
        mSnapshotResult.snapshot = snapshot;
        mSnapshotResult.outSubset = subset;
        mSnapshotResult.outOffset = SkIPoint::Make(0.0f, 0.0f);
        mImageFilterClipBounds = clipBounds;
        mTargetImageFilter = nullptr;
        mTargetImageFilterLayerSurfaceGenerationId = 0;
    } else if (mSnapshotResult.snapshot == nullptr || imageFilter != mTargetImageFilter.get() ||
               mImageFilterClipBounds != clipBounds ||
               mTargetImageFilterLayerSurfaceGenerationId != layerSurfaceGenerationId) {
        // Otherwise create a new snapshot with the given filter and snapshot
        mSnapshotResult.snapshot =
                snapshot->makeWithFilter(context,
                                         imageFilter,
                                         subset,
                                         clipBounds,
                                         &mSnapshotResult.outSubset,
                                         &mSnapshotResult.outOffset);
        mTargetImageFilter = sk_ref_sp(imageFilter);
        mImageFilterClipBounds = clipBounds;
        mTargetImageFilterLayerSurfaceGenerationId = layerSurfaceGenerationId;
    }

    return mSnapshotResult;
}

void RenderNode::syncDisplayList(TreeObserver& observer, TreeInfo* info) {
    // Make sure we inc first so that we don't fluctuate between 0 and 1,
    // which would thrash the layer cache
    if (mStagingDisplayList) {
        mStagingDisplayList.updateChildren([](RenderNode* child) { child->incParentRefCount(); });
    }
    deleteDisplayList(observer, info);
    mDisplayList = std::move(mStagingDisplayList);
    if (mDisplayList) {
        WebViewSyncData syncData {
            .applyForceDark = info && !info->disableForceDark
        };
        mDisplayList.syncContents(syncData);
        handleForceDark(info);
    }
}

void RenderNode::handleForceDark(android::uirenderer::TreeInfo *info) {
    if (CC_LIKELY(!info || info->disableForceDark)) {
        return;
    }
    auto usage = usageHint();
    FatVector<RenderNode*, 6> children;
    mDisplayList.updateChildren([&children](RenderNode* node) {
        children.push_back(node);
    });
    if (mDisplayList.hasText()) {
        usage = UsageHint::Foreground;
    }
    if (usage == UsageHint::Unknown) {
        if (children.size() > 1) {
            usage = UsageHint::Background;
        } else if (children.size() == 1 &&
                children.front()->usageHint() !=
                        UsageHint::Background) {
            usage = UsageHint::Background;
        }
    }
    if (children.size() > 1) {
        // Crude overlap check
        SkRect drawn = SkRect::MakeEmpty();
        for (auto iter = children.rbegin(); iter != children.rend(); ++iter) {
            const auto& child = *iter;
            // We use stagingProperties here because we haven't yet sync'd the children
            SkRect bounds = SkRect::MakeXYWH(child->stagingProperties().getX(), child->stagingProperties().getY(),
                    child->stagingProperties().getWidth(), child->stagingProperties().getHeight());
            if (bounds.contains(drawn)) {
                // This contains everything drawn after it, so make it a background
                child->setUsageHint(UsageHint::Background);
            }
            drawn.join(bounds);
        }
    }
    mDisplayList.applyColorTransform(
            usage == UsageHint::Background ? ColorTransform::Dark : ColorTransform::Light);
}

void RenderNode::pushStagingDisplayListChanges(TreeObserver& observer, TreeInfo& info) {
    if (mNeedsDisplayListSync) {
        mNeedsDisplayListSync = false;
        // Damage with the old display list first then the new one to catch any
        // changes in isRenderable or, in the future, bounds
        damageSelf(info);
        syncDisplayList(observer, &info);
        damageSelf(info);
    }
}

void RenderNode::deleteDisplayList(TreeObserver& observer, TreeInfo* info) {
    if (mDisplayList) {
        mDisplayList.updateChildren(
                [&observer, info](RenderNode* child) { child->decParentRefCount(observer, info); });
        mDisplayList.clear(this);
    }
}

void RenderNode::destroyHardwareResources(TreeInfo* info) {
    if (hasLayer()) {
        this->setLayerSurface(nullptr);
    }
    discardStagingDisplayList();

    ImmediateRemoved observer(info);
    deleteDisplayList(observer, info);
}

void RenderNode::destroyLayers() {
    if (hasLayer()) {
        this->setLayerSurface(nullptr);
    }

    if (mDisplayList) {
        mDisplayList.updateChildren([](RenderNode* child) { child->destroyLayers(); });
    }
}

void RenderNode::decParentRefCount(TreeObserver& observer, TreeInfo* info) {
    LOG_ALWAYS_FATAL_IF(!mParentCount, "already 0!");
    mParentCount--;
    if (!mParentCount) {
        observer.onMaybeRemovedFromTree(this);
        if (CC_UNLIKELY(mPositionListener.get())) {
            mPositionListener->onPositionLost(*this, info);
        }
    }
}

void RenderNode::onRemovedFromTree(TreeInfo* info) {
    if (Properties::enableWebViewOverlays && mDisplayList) {
        mDisplayList.onRemovedFromTree();
    }
    destroyHardwareResources(info);
}

void RenderNode::clearRoot() {
    ImmediateRemoved observer(nullptr);
    decParentRefCount(observer);
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
                true3dMat.loadTranslate(properties().getPivotX() + properties().getTranslationX(),
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

    if (Properties::getStretchEffectBehavior() == StretchEffectBehavior::UniformScale) {
        const StretchEffect& stretch = properties().layerProperties().getStretchEffect();
        if (!stretch.isEmpty()) {
            matrix.multiply(
                    stretch.makeLinearStretch(properties().getWidth(), properties().getHeight()));
        }
    }
}

const SkPath* RenderNode::getClippedOutline(const SkRect& clipRect) const {
    const SkPath* outlinePath = properties().getOutline().getPath();
    const uint32_t outlineID = outlinePath->getGenerationID();

    if (outlineID != mClippedOutlineCache.outlineID || clipRect != mClippedOutlineCache.clipRect) {
        // update the cache keys
        mClippedOutlineCache.outlineID = outlineID;
        mClippedOutlineCache.clipRect = clipRect;

        // update the cache value by recomputing a new path
        SkPath clipPath;
        clipPath.addRect(clipRect);
        Op(*outlinePath, clipPath, kIntersect_SkPathOp, &mClippedOutlineCache.clippedOutline);
    }
    return &mClippedOutlineCache.clippedOutline;
}

using StringBuffer = FatVector<char, 128>;

template <typename... T>
// TODO:__printflike(2, 3)
// Doesn't work because the warning doesn't understand string_view and doesn't like that
// it's not a C-style variadic function.
static void format(StringBuffer& buffer, const std::string_view& format, T... args) {
    buffer.resize(buffer.capacity());
    while (1) {
        int needed = snprintf(buffer.data(), buffer.size(),
                format.data(), std::forward<T>(args)...);
        if (needed < 0) {
            buffer[0] = '\0';
            buffer.resize(1);
            return;
        }
        if (needed < buffer.size()) {
            buffer.resize(needed + 1);
            return;
        }
        // If we're doing a heap alloc anyway might as well give it some slop
        buffer.resize(needed + 100);
    }
}

void RenderNode::markDrawStart(SkCanvas& canvas) {
    StringBuffer buffer;
    format(buffer, "RenderNode(id=%" PRId64 ", name='%s')", uniqueId(), getName());
    canvas.drawAnnotation(SkRect::MakeWH(getWidth(), getHeight()), buffer.data(), nullptr);
}

void RenderNode::markDrawEnd(SkCanvas& canvas) {
    StringBuffer buffer;
    format(buffer, "/RenderNode(id=%" PRId64 ", name='%s')", uniqueId(), getName());
    canvas.drawAnnotation(SkRect::MakeWH(getWidth(), getHeight()), buffer.data(), nullptr);
}

} /* namespace uirenderer */
} /* namespace android */
