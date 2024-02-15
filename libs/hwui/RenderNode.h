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

#pragma once

#include <SkMatrix.h>

#include <utils/LinearAllocator.h>
#include <utils/RefBase.h>
#include <utils/String8.h>

#include <cutils/compiler.h>

#include <androidfw/ResourceTypes.h>

#include <ui/FatVector.h>

#include "AnimatorManager.h"
#include "CanvasTransform.h"
#include "Debug.h"
#include "DisplayList.h"
#include "Matrix.h"
#include "RenderProperties.h"
#include "pipeline/skia/HolePunch.h"
#include "pipeline/skia/SkiaDisplayList.h"
#include "pipeline/skia/SkiaLayer.h"

#include <vector>
#include <pipeline/skia/StretchMask.h>

class SkBitmap;
class SkPaint;
class SkPath;
class SkRegion;
class SkSurface;

namespace android {
namespace uirenderer {

class CanvasState;
class Rect;
class SkiaShader;
struct RenderNodeOp;

class TreeInfo;
class TreeObserver;

namespace proto {
class RenderNode;
}

/**
 * Primary class for storing recorded canvas commands, as well as per-View/ViewGroup display
 * properties.
 *
 * Recording of canvas commands is somewhat similar to SkPicture, except the canvas-recording
 * functionality is split between RecordingCanvas (which manages the recording), DisplayList
 * (which holds the actual data), and RenderNode (which holds properties used for render playback).
 *
 * Note that DisplayList is swapped out from beneath an individual RenderNode when a view's
 * recorded stream of canvas operations is refreshed. The RenderNode (and its properties) stay
 * attached.
 */
class RenderNode : public VirtualLightRefBase {
    friend class TestUtils;  // allow TestUtils to access syncDisplayList / syncProperties

public:
    enum DirtyPropertyMask {
        GENERIC = 1 << 1,
        TRANSLATION_X = 1 << 2,
        TRANSLATION_Y = 1 << 3,
        TRANSLATION_Z = 1 << 4,
        SCALE_X = 1 << 5,
        SCALE_Y = 1 << 6,
        ROTATION = 1 << 7,
        ROTATION_X = 1 << 8,
        ROTATION_Y = 1 << 9,
        X = 1 << 10,
        Y = 1 << 11,
        Z = 1 << 12,
        ALPHA = 1 << 13,
        DISPLAY_LIST = 1 << 14,
    };

    RenderNode();
    virtual ~RenderNode();

    // See flags defined in DisplayList.java
    enum ReplayFlag { kReplayFlag_ClipChildren = 0x1 };

    void setStagingDisplayList(DisplayList&& newData);
    void discardStagingDisplayList();

    void output();
    int getUsageSize();
    int getAllocatedSize();

    bool isRenderable() const { return mDisplayList.hasContent(); }

    bool hasProjectionReceiver() const {
        return mDisplayList.containsProjectionReceiver();
    }

    const char* getName() const { return mName.c_str(); }

    void setName(const char* name) {
        if (name) {
            const char* lastPeriod = strrchr(name, '.');
            if (lastPeriod) {
                mName = (lastPeriod + 1);
            } else {
                mName = name;
            }
        }
    }

    StretchMask& getStretchMask() { return mStretchMask; }

    bool isPropertyFieldDirty(DirtyPropertyMask field) const {
        return mDirtyPropertyFields & field;
    }

    void setPropertyFieldsDirty(uint32_t fields) { mDirtyPropertyFields |= fields; }

    const RenderProperties& properties() const { return mProperties; }

    RenderProperties& animatorProperties() { return mProperties; }

    const RenderProperties& stagingProperties() { return mStagingProperties; }

    RenderProperties& mutateStagingProperties() { return mStagingProperties; }

    bool isValid() { return mValid; }

    int getWidth() const { return properties().getWidth(); }

    int getHeight() const { return properties().getHeight(); }

    virtual void prepareTree(TreeInfo& info);
    void destroyHardwareResources(TreeInfo* info = nullptr);
    void destroyLayers();

    // UI thread only!
    void addAnimator(const sp<BaseRenderNodeAnimator>& animator);
    void removeAnimator(const sp<BaseRenderNodeAnimator>& animator);

    // This can only happen during pushStaging()
    void onAnimatorTargetChanged(BaseRenderNodeAnimator* animator) {
        mAnimatorManager.onAnimatorTargetChanged(animator);
    }

    AnimatorManager& animators() { return mAnimatorManager; }

    void applyViewPropertyTransforms(mat4& matrix, bool true3dTransform = false) const;

    bool nothingToDraw() const {
        const Outline& outline = properties().getOutline();
        return !mDisplayList.isValid() || properties().getAlpha() <= 0 ||
               (outline.getShouldClip() && outline.isEmpty()) || properties().getScaleX() == 0 ||
               properties().getScaleY() == 0;
    }

    const DisplayList& getDisplayList() const { return mDisplayList; }
    // TODO: can this be cleaned up?
    DisplayList& getDisplayList() { return mDisplayList; }

    // Note: The position callbacks are relying on the listener using
    // the frameNumber to appropriately batch/synchronize these transactions.
    // There is no other filtering/batching to ensure that only the "final"
    // state called once per frame.
    class PositionListener : public VirtualLightRefBase {
    public:
        virtual ~PositionListener() {}
        // Called when the RenderNode's position changes
        virtual void onPositionUpdated(RenderNode& node, const TreeInfo& info) = 0;
        // Called when the RenderNode no longer has a position. As in, it's
        // no longer being drawn.
        // Note, tree info might be null
        virtual void onPositionLost(RenderNode& node, const TreeInfo* info) = 0;
    };

    void setPositionListener(PositionListener* listener) {
        mStagingPositionListener = listener;
        mPositionListenerDirty = true;
    }

    // This is only modified in MODE_FULL, so it can be safely accessed
    // on the UI thread.
    bool hasParents() { return mParentCount; }

    void onRemovedFromTree(TreeInfo* info);

    // Called by CanvasContext to promote a RenderNode to be a root node
    void makeRoot() { incParentRefCount(); }

    // Called by CanvasContext when it drops a RenderNode from being a root node
    void clearRoot();

    void output(std::ostream& output, uint32_t level);

    void visit(std::function<void(const RenderNode&)>) const;

    void setUsageHint(UsageHint usageHint) { mUsageHint = usageHint; }

    UsageHint usageHint() const { return mUsageHint; }

    int64_t uniqueId() const { return mUniqueId; }

    void setIsTextureView() { mIsTextureView = true; }
    bool isTextureView() const { return mIsTextureView; }

    void markDrawStart(SkCanvas& canvas);
    void markDrawEnd(SkCanvas& canvas);

private:
    void computeOrderingImpl(RenderNodeOp* opState,
                             std::vector<RenderNodeOp*>* compositedChildrenOfProjectionSurface,
                             const mat4* transformFromProjectionSurface);

    void syncProperties();
    void syncDisplayList(TreeObserver& observer, TreeInfo* info);
    void handleForceDark(TreeInfo* info);
    bool shouldEnableForceDark(TreeInfo* info);

    void prepareTreeImpl(TreeObserver& observer, TreeInfo& info, bool functorsNeedLayer);
    void pushStagingPropertiesChanges(TreeInfo& info);
    void pushStagingDisplayListChanges(TreeObserver& observer, TreeInfo& info);
    void prepareLayer(TreeInfo& info, uint32_t dirtyMask);
    void pushLayerUpdate(TreeInfo& info);
    void deleteDisplayList(TreeObserver& observer, TreeInfo* info = nullptr);
    void damageSelf(TreeInfo& info);

    void incParentRefCount() { mParentCount++; }
    void decParentRefCount(TreeObserver& observer, TreeInfo* info = nullptr);

    const int64_t mUniqueId;
    String8 mName;

    uint32_t mDirtyPropertyFields;
    RenderProperties mProperties;
    RenderProperties mStagingProperties;

    // Owned by UI. Set when DL is set, cleared when DL cleared or when node detached
    // (likely by parent re-record/removal)
    bool mValid = false;

    bool mNeedsDisplayListSync;
    // WARNING: Do not delete this directly, you must go through deleteDisplayList()!
    DisplayList mDisplayList;
    DisplayList mStagingDisplayList;

    int64_t mDamageGenerationId = 0;

    friend class AnimatorManager;
    AnimatorManager mAnimatorManager;

    /**
     * Draw time state - these properties are only set and used during rendering
     */

    // for projection surfaces, contains a list of all children items
    std::vector<RenderNodeOp*> mProjectedNodes;

    // How many references our parent(s) have to us. Typically this should alternate
    // between 2 and 1 (when a staging push happens we inc first then dec)
    // When this hits 0 we are no longer in the tree, so any hardware resources
    // (specifically Layers) should be released.
    // This is *NOT* thread-safe, and should therefore only be tracking
    // mDisplayList, not mStagingDisplayList.
    uint32_t mParentCount;

    bool mPositionListenerDirty = false;
    sp<PositionListener> mStagingPositionListener;
    sp<PositionListener> mPositionListener;

    UsageHint mUsageHint = UsageHint::Unknown;

    bool mHasHolePunches;
    StretchMask mStretchMask;

    bool mIsTextureView = false;

    // METHODS & FIELDS ONLY USED BY THE SKIA RENDERER
public:
    /**
     * Detach and transfer ownership of an already allocated displayList for use
     * in recording updated content for this renderNode
     */
    std::unique_ptr<skiapipeline::SkiaDisplayList> detachAvailableList() {
        return std::move(mAvailableDisplayList);
    }

    bool hasHolePunches() { return mHasHolePunches; }

    /**
     * Attach unused displayList to this node for potential future reuse.
     */
    void attachAvailableList(skiapipeline::SkiaDisplayList* skiaDisplayList) {
        mAvailableDisplayList.reset(skiaDisplayList);
    }

    /**
     * Returns true if an offscreen layer from any renderPipeline is attached
     * to this node.
     */
    bool hasLayer() const { return mSkiaLayer.get(); }

    /**
     * Used by the RenderPipeline to attach an offscreen surface to the RenderNode.
     * The surface is then will be used to store the contents of a layer.
     */
    void setLayerSurface(sk_sp<SkSurface> layer) {
        if (layer.get()) {
            if (!mSkiaLayer.get()) {
                mSkiaLayer = std::make_unique<skiapipeline::SkiaLayer>();
            }
            mSkiaLayer->layerSurface = std::move(layer);
            mSkiaLayer->inverseTransformInWindow.loadIdentity();
        } else {
            mSkiaLayer.reset();
        }

        mProperties.mutateLayerProperties().mutableStretchEffect().clear();
        mStretchMask.clear();
        // Clear out the previous snapshot and the image filter the previous
        // snapshot was created with whenever the layer changes.
        mSnapshotResult.snapshot = nullptr;
        mTargetImageFilter = nullptr;
    }

    /**
     * If the RenderNode is of type LayerType::RenderLayer then this method will
     * return the an offscreen rendering surface that is used to both render into
     * the layer and composite the layer into its parent.  If the type is not
     * LayerType::RenderLayer then it will return a nullptr.
     *
     * NOTE: this function is only guaranteed to return accurate results after
     *       prepareTree has been run for this RenderNode
     */
    SkSurface* getLayerSurface() const {
        return mSkiaLayer.get() ? mSkiaLayer->layerSurface.get() : nullptr;
    }

    struct SnapshotResult {
        sk_sp<SkImage> snapshot;
        SkIRect outSubset;
        SkIPoint outOffset;
    };

    std::optional<SnapshotResult> updateSnapshotIfRequired(GrRecordingContext* context,
                                            const SkImageFilter* imageFilter,
                                            const SkIRect& clipBounds);

    skiapipeline::SkiaLayer* getSkiaLayer() const { return mSkiaLayer.get(); }

    /**
     * Returns the path that represents the outline of RenderNode intersected with
     * the provided rect.  This call will internally cache the resulting path in
     * order to potentially return that path for subsequent calls to this method.
     * By reusing the same path we get better performance on the GPU backends since
     * those resources are cached in the hardware based on the path's genID.
     *
     * The returned path is only guaranteed to be valid until this function is called
     * again or the RenderNode's outline is mutated.
     */
    const SkPath* getClippedOutline(const SkRect& clipRect) const;

private:
    /**
     * If this RenderNode has been used in a previous frame then the SkiaDisplayList
     * from that frame is cached here until one of the following conditions is met:
     *  1) The RenderNode is deleted (causing this to be deleted)
     *  2) It is replaced with the displayList from the next completed frame
     *  3) It is detached and used to to record a new displayList for a later frame
     */
    std::unique_ptr<skiapipeline::SkiaDisplayList> mAvailableDisplayList;

    /**
     * An offscreen rendering target used to contain the contents this RenderNode
     * when it has been set to draw as a LayerType::RenderLayer.
     */
    std::unique_ptr<skiapipeline::SkiaLayer> mSkiaLayer;

    /**
     * SkImageFilter used to create the mSnapshotResult
     */
    sk_sp<SkImageFilter> mTargetImageFilter;
    uint32_t mTargetImageFilterLayerSurfaceGenerationId = 0;

    /**
     * Clip bounds used to create the mSnapshotResult
     */
    SkIRect mImageFilterClipBounds;

    /**
     * Result of the most recent snapshot with additional metadata used to
     * determine how to draw the contents
     */
    SnapshotResult mSnapshotResult;

    struct ClippedOutlineCache {
        // keys
        uint32_t outlineID = 0;
        SkRect clipRect;

        // value
        SkPath clippedOutline;
    };
    mutable ClippedOutlineCache mClippedOutlineCache;
};  // class RenderNode

class MarkAndSweepRemoved : public TreeObserver {
    PREVENT_COPY_AND_ASSIGN(MarkAndSweepRemoved);

public:
    explicit MarkAndSweepRemoved(TreeInfo* info) : mTreeInfo(info) {}

    void onMaybeRemovedFromTree(RenderNode* node) override { mMarked.emplace_back(node); }

    ~MarkAndSweepRemoved() {
        for (auto& node : mMarked) {
            if (!node->hasParents()) {
                node->onRemovedFromTree(mTreeInfo);
            }
        }
    }

private:
    FatVector<sp<RenderNode>, 10> mMarked;
    TreeInfo* mTreeInfo;
};

} /* namespace uirenderer */
} /* namespace android */
