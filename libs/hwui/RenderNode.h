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

#ifndef LOG_TAG
    #define LOG_TAG "OpenGLRenderer"
#endif

#include <set>
#include <vector>

#include <SkCamera.h>
#include <SkMatrix.h>

#include <private/hwui/DrawGlInfo.h>

#include <utils/KeyedVector.h>
#include <utils/LinearAllocator.h>
#include <utils/RefBase.h>
#include <utils/SortedVector.h>
#include <utils/String8.h>
#include <utils/Vector.h>

#include <cutils/compiler.h>

#include <androidfw/ResourceTypes.h>

#include "Debug.h"
#include "Matrix.h"
#include "DeferredDisplayList.h"
#include "DisplayList.h"
#include "RenderProperties.h"
#include "TreeInfo.h"

class SkBitmap;
class SkPaint;
class SkPath;
class SkRegion;

namespace android {
namespace uirenderer {

class DeferredDisplayList;
class DisplayListOp;
class DisplayListRenderer;
class OpenGLRenderer;
class Rect;
class Layer;
class SkiaShader;

class ClipRectOp;
class SaveLayerOp;
class SaveOp;
class RestoreToCountOp;
class DrawDisplayListOp;

/**
 * Primary class for storing recorded canvas commands, as well as per-View/ViewGroup display properties.
 *
 * Recording of canvas commands is somewhat similar to SkPicture, except the canvas-recording
 * functionality is split between DisplayListRenderer (which manages the recording), DisplayListData
 * (which holds the actual data), and DisplayList (which holds properties and performs playback onto
 * a renderer).
 *
 * Note that DisplayListData is swapped out from beneath an individual DisplayList when a view's
 * recorded stream of canvas operations is refreshed. The DisplayList (and its properties) stay
 * attached.
 */
class RenderNode : public VirtualLightRefBase {
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
    };

    ANDROID_API RenderNode();
    ANDROID_API virtual ~RenderNode();

    // See flags defined in DisplayList.java
    enum ReplayFlag {
        kReplayFlag_ClipChildren = 0x1
    };

    ANDROID_API static void outputLogBuffer(int fd);

    ANDROID_API void setStagingDisplayList(DisplayListData* newData);

    void computeOrdering();

    void deferNodeTree(DeferStateStruct& deferStruct);
    void deferNodeInParent(DeferStateStruct& deferStruct, const int level);

    void replayNodeTree(ReplayStateStruct& replayStruct);
    void replayNodeInParent(ReplayStateStruct& replayStruct, const int level);

    ANDROID_API void output(uint32_t level = 1);
    ANDROID_API int getDebugSize();

    bool isRenderable() const {
        return mDisplayListData && mDisplayListData->hasDrawOps;
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

    // UI thread only!
    ANDROID_API void addAnimator(const sp<BaseRenderNodeAnimator>& animator) {
        animator->onAttached(this);
        mStagingAnimators.insert(animator);
        mNeedsAnimatorsSync = true;
    }

    // UI thread only!
    ANDROID_API void removeAnimator(const sp<BaseRenderNodeAnimator>& animator) {
        mStagingAnimators.erase(animator);
        mNeedsAnimatorsSync = true;
    }

protected:
    virtual void damageSelf(TreeInfo& info);

private:
    typedef key_value_pair_t<float, DrawDisplayListOp*> ZDrawDisplayListOpPair;

    static size_t findNonNegativeIndex(const Vector<ZDrawDisplayListOpPair>& nodes) {
        for (size_t i = 0; i < nodes.size(); i++) {
            if (nodes[i].key >= 0.0f) return i;
        }
        return nodes.size();
    }

    enum ChildrenSelectMode {
        kNegativeZChildren,
        kPositiveZChildren
    };

    void applyViewPropertyTransforms(mat4& matrix, bool true3dTransform = false);

    void computeOrderingImpl(DrawDisplayListOp* opState,
            const SkPath* outlineOfProjectionSurface,
            Vector<DrawDisplayListOp*>* compositedChildrenOfProjectionSurface,
            const mat4* transformFromProjectionSurface);

    template <class T>
    inline void setViewProperties(OpenGLRenderer& renderer, T& handler);

    void buildZSortedChildList(Vector<ZDrawDisplayListOpPair>& zTranslatedNodes);

    template<class T>
    inline void issueDrawShadowOperation(const Matrix4& transformFromParent, T& handler);

    template <class T>
    inline void issueOperationsOf3dChildren(const Vector<ZDrawDisplayListOpPair>& zTranslatedNodes,
            ChildrenSelectMode mode, OpenGLRenderer& renderer, T& handler);

    template <class T>
    inline void issueOperationsOfProjectedChildren(OpenGLRenderer& renderer, T& handler);

    /**
     * Issue the RenderNode's operations into a handler, recursing for subtrees through
     * DrawDisplayListOp's defer() or replay() methods
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

    void prepareTreeImpl(TreeInfo& info);
    void pushStagingChanges(TreeInfo& info);
    void evaluateAnimations(TreeInfo& info);
    void prepareSubTree(TreeInfo& info, DisplayListData* subtree);

    String8 mName;

    uint32_t mDirtyPropertyFields;
    RenderProperties mProperties;
    RenderProperties mStagingProperties;

    bool mNeedsDisplayListDataSync;
    DisplayListData* mDisplayListData;
    DisplayListData* mStagingDisplayListData;

    bool mNeedsAnimatorsSync;
    std::set< sp<BaseRenderNodeAnimator> > mStagingAnimators;
    std::vector< sp<BaseRenderNodeAnimator> > mAnimators;

    /**
     * Draw time state - these properties are only set and used during rendering
     */

    // for projection surfaces, contains a list of all children items
    Vector<DrawDisplayListOp*> mProjectedNodes;
}; // class RenderNode

} /* namespace uirenderer */
} /* namespace android */

#endif /* RENDERNODE_H */
