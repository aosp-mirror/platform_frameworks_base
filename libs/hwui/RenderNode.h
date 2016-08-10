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

#include <SkCamera.h>
#include <SkMatrix.h>

#include <utils/LinearAllocator.h>
#include <utils/RefBase.h>
#include <utils/String8.h>

#include <cutils/compiler.h>

#include <androidfw/ResourceTypes.h>

#include "AnimatorManager.h"
#include "Debug.h"
#include "DisplayList.h"
#include "Matrix.h"
#include "RenderProperties.h"

#include <vector>

class SkBitmap;
class SkPaint;
class SkPath;
class SkRegion;

namespace android {
namespace uirenderer {

class CanvasState;
class DisplayListOp;
class FrameBuilder;
class OffscreenBuffer;
class Rect;
class SkiaShader;
struct RenderNodeOp;

class TreeInfo;
class TreeObserver;

namespace proto {
class RenderNode;
}

/**
 * Primary class for storing recorded canvas commands, as well as per-View/ViewGroup display properties.
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
friend class TestUtils; // allow TestUtils to access syncDisplayList / syncProperties
friend class FrameBuilder;
public:
    enum DirtyPropertyMask {
        GENERIC         = 1 << 1,
        TRANSLATION_X   = 1 << 2,
        TRANSLATION_Y   = 1 << 3,
        TRANSLATION_Z   = 1 << 4,
        SCALE_X         = 1 << 5,
        SCALE_Y         = 1 << 6,
        ROTATION        = 1 << 7,
        ROTATION_X      = 1 << 8,
        ROTATION_Y      = 1 << 9,
        X               = 1 << 10,
        Y               = 1 << 11,
        Z               = 1 << 12,
        ALPHA           = 1 << 13,
        DISPLAY_LIST    = 1 << 14,
    };

    ANDROID_API RenderNode();
    ANDROID_API virtual ~RenderNode();

    // See flags defined in DisplayList.java
    enum ReplayFlag {
        kReplayFlag_ClipChildren = 0x1
    };

    ANDROID_API void setStagingDisplayList(DisplayList* newData, TreeObserver* observer);

    void computeOrdering();

    ANDROID_API void output();
    ANDROID_API int getDebugSize();
    void copyTo(proto::RenderNode* node);

    bool isRenderable() const {
        return mDisplayList && !mDisplayList->isEmpty();
    }

    bool hasProjectionReceiver() const {
        return mDisplayList && mDisplayList->projectionReceiveIndex >= 0;
    }

    const char* getName() const {
        return mName.string();
    }

    void setName(const char* name) {
        if (name) {
            const char* lastPeriod = strrchr(name, '.');
            if (lastPeriod) {
                mName.setTo(lastPeriod + 1);
            } else {
                mName.setTo(name);
            }
        }
    }

    VirtualLightRefBase* getUserContext() const {
        return mUserContext.get();
    }

    void setUserContext(VirtualLightRefBase* context) {
        mUserContext = context;
    }

    bool isPropertyFieldDirty(DirtyPropertyMask field) const {
        return mDirtyPropertyFields & field;
    }

    void setPropertyFieldsDirty(uint32_t fields) {
        mDirtyPropertyFields |= fields;
    }

    const RenderProperties& properties() const {
        return mProperties;
    }

    RenderProperties& animatorProperties() {
        return mProperties;
    }

    const RenderProperties& stagingProperties() {
        return mStagingProperties;
    }

    RenderProperties& mutateStagingProperties() {
        return mStagingProperties;
    }

    int getWidth() const {
        return properties().getWidth();
    }

    int getHeight() const {
        return properties().getHeight();
    }

    ANDROID_API virtual void prepareTree(TreeInfo& info);
    void destroyHardwareResources(TreeObserver* observer, TreeInfo* info = nullptr);

    // UI thread only!
    ANDROID_API void addAnimator(const sp<BaseRenderNodeAnimator>& animator);
    void removeAnimator(const sp<BaseRenderNodeAnimator>& animator);

    // This can only happen during pushStaging()
    void onAnimatorTargetChanged(BaseRenderNodeAnimator* animator) {
        mAnimatorManager.onAnimatorTargetChanged(animator);
    }

    AnimatorManager& animators() { return mAnimatorManager; }

    void applyViewPropertyTransforms(mat4& matrix, bool true3dTransform = false) const;

    bool nothingToDraw() const {
        const Outline& outline = properties().getOutline();
        return mDisplayList == nullptr
                || properties().getAlpha() <= 0
                || (outline.getShouldClip() && outline.isEmpty())
                || properties().getScaleX() == 0
                || properties().getScaleY() == 0;
    }

    const DisplayList* getDisplayList() const {
        return mDisplayList;
    }
    OffscreenBuffer* getLayer() const { return mLayer; }
    OffscreenBuffer** getLayerHandle() { return &mLayer; } // ugh...

    // Note: The position callbacks are relying on the listener using
    // the frameNumber to appropriately batch/synchronize these transactions.
    // There is no other filtering/batching to ensure that only the "final"
    // state called once per frame.
    class ANDROID_API PositionListener : public VirtualLightRefBase {
    public:
        virtual ~PositionListener() {}
        // Called when the RenderNode's position changes
        virtual void onPositionUpdated(RenderNode& node, const TreeInfo& info) = 0;
        // Called when the RenderNode no longer has a position. As in, it's
        // no longer being drawn.
        // Note, tree info might be null
        virtual void onPositionLost(RenderNode& node, const TreeInfo* info) = 0;
    };

    // Note this is not thread safe, this needs to be called
    // before the RenderNode is used for drawing.
    // RenderNode takes ownership of the pointer
    ANDROID_API void setPositionListener(PositionListener* listener) {
        mPositionListener = listener;
    }

    // This is only modified in MODE_FULL, so it can be safely accessed
    // on the UI thread.
    ANDROID_API bool hasParents() {
        return mParentCount;
    }

private:
    void computeOrderingImpl(RenderNodeOp* opState,
            std::vector<RenderNodeOp*>* compositedChildrenOfProjectionSurface,
            const mat4* transformFromProjectionSurface);

    void syncProperties();
    void syncDisplayList(TreeInfo* info);

    void prepareTreeImpl(TreeInfo& info, bool functorsNeedLayer);
    void pushStagingPropertiesChanges(TreeInfo& info);
    void pushStagingDisplayListChanges(TreeInfo& info);
    void prepareSubTree(TreeInfo& info, bool functorsNeedLayer, DisplayList* subtree);
    void prepareLayer(TreeInfo& info, uint32_t dirtyMask);
    void pushLayerUpdate(TreeInfo& info);
    void deleteDisplayList(TreeObserver* observer, TreeInfo* info = nullptr);
    void damageSelf(TreeInfo& info);

    void incParentRefCount() { mParentCount++; }
    void decParentRefCount(TreeObserver* observer, TreeInfo* info = nullptr);
    void output(std::ostream& output, uint32_t level);

    String8 mName;
    sp<VirtualLightRefBase> mUserContext;

    uint32_t mDirtyPropertyFields;
    RenderProperties mProperties;
    RenderProperties mStagingProperties;

    bool mNeedsDisplayListSync;
    // WARNING: Do not delete this directly, you must go through deleteDisplayList()!
    DisplayList* mDisplayList;
    DisplayList* mStagingDisplayList;

    friend class AnimatorManager;
    AnimatorManager mAnimatorManager;

    // Owned by RT. Lifecycle is managed by prepareTree(), with the exception
    // being in ~RenderNode() which may happen on any thread.
    OffscreenBuffer* mLayer = nullptr;

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

    sp<PositionListener> mPositionListener;
}; // class RenderNode

} /* namespace uirenderer */
} /* namespace android */
