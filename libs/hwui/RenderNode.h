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
#ifndef RENDERNODE_H
#define RENDERNODE_H

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
class DisplayListCanvas;
class OpenGLRenderer;
class Rect;
class Layer;
class SkiaShader;

class ClipRectOp;
class SaveLayerOp;
class SaveOp;
class RestoreToCountOp;
class DrawRenderNodeOp;
class TreeInfo;

namespace proto {
class RenderNode;
}

/**
 * Primary class for storing recorded canvas commands, as well as per-View/ViewGroup display properties.
 *
 * Recording of canvas commands is somewhat similar to SkPicture, except the canvas-recording
 * functionality is split between DisplayListCanvas (which manages the recording), DisplayList
 * (which holds the actual data), and DisplayList (which holds properties and performs playback onto
 * a renderer).
 *
 * Note that DisplayList is swapped out from beneath an individual RenderNode when a view's
 * recorded stream of canvas operations is refreshed. The RenderNode (and its properties) stay
 * attached.
 */
class RenderNode : public VirtualLightRefBase {
friend class TestUtils; // allow TestUtils to access syncDisplayList / syncProperties
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

    void debugDumpLayers(const char* prefix);

    ANDROID_API void setStagingDisplayList(DisplayList* newData);

    void computeOrdering();

    void defer(DeferStateStruct& deferStruct, const int level);
    void replay(ReplayStateStruct& replayStruct, const int level);

    ANDROID_API void output(uint32_t level = 1);
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
            char* lastPeriod = strrchr(name, '.');
            if (lastPeriod) {
                mName.setTo(lastPeriod + 1);
            } else {
                mName.setTo(name);
            }
        }
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

    int getWidth() {
        return properties().getWidth();
    }

    int getHeight() {
        return properties().getHeight();
    }

    ANDROID_API virtual void prepareTree(TreeInfo& info);
    void destroyHardwareResources();

    // UI thread only!
    ANDROID_API void addAnimator(const sp<BaseRenderNodeAnimator>& animator);

    AnimatorManager& animators() { return mAnimatorManager; }

    // Returns false if the properties dictate the subtree contained in this RenderNode won't render
    bool applyViewProperties(CanvasState& canvasState) const;

    void applyViewPropertyTransforms(mat4& matrix, bool true3dTransform = false) const;

    bool nothingToDraw() const {
        const Outline& outline = properties().getOutline();
        return mDisplayList == nullptr
                || properties().getAlpha() <= 0
                || (outline.getShouldClip() && outline.isEmpty())
                || properties().getScaleX() == 0
                || properties().getScaleY() == 0;
    }

    // Only call if RenderNode has DisplayList...
    const DisplayList& getDisplayList() const {
        return *mDisplayList;
    }

private:
    typedef key_value_pair_t<float, DrawRenderNodeOp*> ZDrawRenderNodeOpPair;

    static size_t findNonNegativeIndex(const std::vector<ZDrawRenderNodeOpPair>& nodes) {
        for (size_t i = 0; i < nodes.size(); i++) {
            if (nodes[i].key >= 0.0f) return i;
        }
        return nodes.size();
    }

    enum class ChildrenSelectMode {
        NegativeZChildren,
        PositiveZChildren
    };

    void computeOrderingImpl(DrawRenderNodeOp* opState,
            std::vector<DrawRenderNodeOp*>* compositedChildrenOfProjectionSurface,
            const mat4* transformFromProjectionSurface);

    template <class T>
    inline void setViewProperties(OpenGLRenderer& renderer, T& handler);

    void buildZSortedChildList(const DisplayList::Chunk& chunk,
            std::vector<ZDrawRenderNodeOpPair>& zTranslatedNodes);

    template<class T>
    inline void issueDrawShadowOperation(const Matrix4& transformFromParent, T& handler);

    template <class T>
    inline void issueOperationsOf3dChildren(ChildrenSelectMode mode,
            const Matrix4& initialTransform, const std::vector<ZDrawRenderNodeOpPair>& zTranslatedNodes,
            OpenGLRenderer& renderer, T& handler);

    template <class T>
    inline void issueOperationsOfProjectedChildren(OpenGLRenderer& renderer, T& handler);

    /**
     * Issue the RenderNode's operations into a handler, recursing for subtrees through
     * DrawRenderNodeOp's defer() or replay() methods
     */
    template <class T>
    inline void issueOperations(OpenGLRenderer& renderer, T& handler);

    class TextContainer {
    public:
        size_t length() const {
            return mByteLength;
        }

        const char* text() const {
            return (const char*) mText;
        }

        size_t mByteLength;
        const char* mText;
    };


    void syncProperties();
    void syncDisplayList();

    void prepareTreeImpl(TreeInfo& info, bool functorsNeedLayer);
    void pushStagingPropertiesChanges(TreeInfo& info);
    void pushStagingDisplayListChanges(TreeInfo& info);
    void prepareSubTree(TreeInfo& info, bool functorsNeedLayer, DisplayList* subtree);
    void applyLayerPropertiesToLayer(TreeInfo& info);
    void prepareLayer(TreeInfo& info, uint32_t dirtyMask);
    void pushLayerUpdate(TreeInfo& info);
    void deleteDisplayList();
    void damageSelf(TreeInfo& info);

    void incParentRefCount() { mParentCount++; }
    void decParentRefCount();

    String8 mName;

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
    Layer* mLayer;

    /**
     * Draw time state - these properties are only set and used during rendering
     */

    // for projection surfaces, contains a list of all children items
    std::vector<DrawRenderNodeOp*> mProjectedNodes;

    // How many references our parent(s) have to us. Typically this should alternate
    // between 2 and 1 (when a staging push happens we inc first then dec)
    // When this hits 0 we are no longer in the tree, so any hardware resources
    // (specifically Layers) should be released.
    // This is *NOT* thread-safe, and should therefore only be tracking
    // mDisplayList, not mStagingDisplayList.
    uint32_t mParentCount;
}; // class RenderNode

} /* namespace uirenderer */
} /* namespace android */

#endif /* RENDERNODE_H */
