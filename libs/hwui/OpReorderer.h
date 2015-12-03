/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef ANDROID_HWUI_OP_REORDERER_H
#define ANDROID_HWUI_OP_REORDERER_H

#include "BakedOpState.h"
#include "CanvasState.h"
#include "DisplayList.h"
#include "RecordedOp.h"

#include <vector>
#include <unordered_map>

struct SkRect;

namespace android {
namespace uirenderer {

class BakedOpState;
class BatchBase;
class LayerUpdateQueue;
class MergingOpBatch;
class OffscreenBuffer;
class OpBatch;
class Rect;

typedef int batchid_t;
typedef const void* mergeid_t;

namespace OpBatchType {
    enum {
        None = 0, // Don't batch
        Bitmap,
        Patch,
        AlphaVertices,
        Vertices,
        AlphaMaskTexture,
        Text,
        ColorText,
        Shadow,

        Count // must be last
    };
}

class OpReorderer : public CanvasStateClient {
    typedef void (*BakedOpReceiver)(void*, const BakedOpState&);
    typedef void (*MergedOpReceiver)(void*, const MergedBakedOpList& opList);

    /**
     * Stores the deferred render operations and state used to compute ordering
     * for a single FBO/layer.
     */
    class LayerReorderer {
    public:
        // Create LayerReorderer for Fbo0
        LayerReorderer(uint32_t width, uint32_t height, const Rect& repaintRect)
                : LayerReorderer(width, height, repaintRect, nullptr, nullptr) {};

        // Create LayerReorderer for an offscreen layer, where beginLayerOp is present for a
        // saveLayer, renderNode is present for a HW layer.
        LayerReorderer(uint32_t width, uint32_t height,
                const Rect& repaintRect, const BeginLayerOp* beginLayerOp, RenderNode* renderNode);

        // iterate back toward target to see if anything drawn since should overlap the new op
        // if no target, merging ops still iterate to find similar batch to insert after
        void locateInsertIndex(int batchId, const Rect& clippedBounds,
                BatchBase** targetBatch, size_t* insertBatchIndex) const;

        void deferUnmergeableOp(LinearAllocator& allocator, BakedOpState* op, batchid_t batchId);

        // insertion point of a new batch, will hopefully be immediately after similar batch
        // (generally, should be similar shader)
        void deferMergeableOp(LinearAllocator& allocator,
                BakedOpState* op, batchid_t batchId, mergeid_t mergeId);

        void replayBakedOpsImpl(void* arg, BakedOpReceiver* receivers, MergedOpReceiver*) const;

        bool empty() const {
            return mBatches.empty();
        }

        void clear() {
            mBatches.clear();
        }

        void dump() const;

        const uint32_t width;
        const uint32_t height;
        const Rect repaintRect;
        OffscreenBuffer* offscreenBuffer;
        const BeginLayerOp* beginLayerOp;
        const RenderNode* renderNode;
    private:
        std::vector<BatchBase*> mBatches;

        /**
         * Maps the mergeid_t returned by an op's getMergeId() to the most recently seen
         * MergingDrawBatch of that id. These ids are unique per draw type and guaranteed to not
         * collide, which avoids the need to resolve mergeid collisions.
         */
        std::unordered_map<mergeid_t, MergingOpBatch*> mMergingBatchLookup[OpBatchType::Count];

        // Maps batch ids to the most recent *non-merging* batch of that id
        OpBatch* mBatchLookup[OpBatchType::Count] = { nullptr };
    };

public:
    OpReorderer(const LayerUpdateQueue& layers, const SkRect& clip,
            uint32_t viewportWidth, uint32_t viewportHeight,
            const std::vector< sp<RenderNode> >& nodes, const Vector3& lightCenter);

    virtual ~OpReorderer() {}

    /**
     * replayBakedOps() is templated based on what class will receive ops being replayed.
     *
     * It constructs a lookup array of lambdas, which allows a recorded BakeOpState to use
     * state->op->opId to lookup a receiver that will be called when the op is replayed.
     *
     */
    template <typename StaticDispatcher, typename Renderer>
    void replayBakedOps(Renderer& renderer) {
        /**
         * defines a LUT of lambdas which allow a recorded BakedOpState to use state->op->opId to
         * dispatch the op via a method on a static dispatcher when the op is replayed.
         *
         * For example a BitmapOp would resolve, via the lambda lookup, to calling:
         *
         * StaticDispatcher::onBitmapOp(Renderer& renderer, const BitmapOp& op, const BakedOpState& state);
         */
        #define X(Type) \
                [](void* renderer, const BakedOpState& state) { \
                    StaticDispatcher::on##Type(*(static_cast<Renderer*>(renderer)), static_cast<const Type&>(*(state.op)), state); \
                },
        static BakedOpReceiver unmergedReceivers[] = {
            MAP_OPS(X)
        };
        #undef X

        /**
         * defines a LUT of lambdas which allow merged arrays of BakedOpState* to be passed to a
         * static dispatcher when the group of merged ops is replayed. Unmergeable ops trigger
         * a LOG_ALWAYS_FATAL().
         */
        #define X(Type) \
                [](void* renderer, const MergedBakedOpList& opList) { \
                    LOG_ALWAYS_FATAL("op type %d does not support merging", opList.states[0]->op->opId); \
                },
        #define Y(Type) \
                [](void* renderer, const MergedBakedOpList& opList) { \
                    StaticDispatcher::onMerged##Type##s(*(static_cast<Renderer*>(renderer)), opList); \
                },
        static MergedOpReceiver mergedReceivers[] = {
            MAP_OPS_BASED_ON_MERGEABILITY(X, Y)
        };
        #undef X
        #undef Y

        // Relay through layers in reverse order, since layers
        // later in the list will be drawn by earlier ones
        for (int i = mLayerReorderers.size() - 1; i >= 1; i--) {
            LayerReorderer& layer = mLayerReorderers[i];
            if (layer.renderNode) {
                // cached HW layer - can't skip layer if empty
                renderer.startRepaintLayer(layer.offscreenBuffer, layer.repaintRect);
                layer.replayBakedOpsImpl((void*)&renderer, unmergedReceivers, mergedReceivers);
                renderer.endLayer();
            } else if (!layer.empty()) { // save layer - skip entire layer if empty
                layer.offscreenBuffer = renderer.startTemporaryLayer(layer.width, layer.height);
                layer.replayBakedOpsImpl((void*)&renderer, unmergedReceivers, mergedReceivers);
                renderer.endLayer();
            }
        }

        const LayerReorderer& fbo0 = mLayerReorderers[0];
        renderer.startFrame(fbo0.width, fbo0.height, fbo0.repaintRect);
        fbo0.replayBakedOpsImpl((void*)&renderer, unmergedReceivers, mergedReceivers);
        renderer.endFrame();
    }

    void dump() const {
        for (auto&& layer : mLayerReorderers) {
            layer.dump();
        }
    }

    ///////////////////////////////////////////////////////////////////
    /// CanvasStateClient interface
    ///////////////////////////////////////////////////////////////////
    virtual void onViewportInitialized() override;
    virtual void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) override;
    virtual GLuint getTargetFbo() const override { return 0; }

private:
    enum class ChildrenSelectMode {
        Negative,
        Positive
    };
    void saveForLayer(uint32_t layerWidth, uint32_t layerHeight,
            float contentTranslateX, float contentTranslateY,
            const Rect& repaintRect,
            const Vector3& lightCenter,
            const BeginLayerOp* beginLayerOp, RenderNode* renderNode);
    void restoreForLayer();

    LayerReorderer& currentLayer() { return mLayerReorderers[mLayerStack.back()]; }

    BakedOpState* tryBakeOpState(const RecordedOp& recordedOp) {
        return BakedOpState::tryConstruct(mAllocator, *mCanvasState.currentSnapshot(), recordedOp);
    }

    // should always be surrounded by a save/restore pair, and not called if DisplayList is null
    void deferNodePropsAndOps(RenderNode& node);

    template <typename V>
    void defer3dChildren(ChildrenSelectMode mode, const V& zTranslatedNodes);

    void deferShadow(const RenderNodeOp& casterOp);

    void deferProjectedChildren(const RenderNode& renderNode);

    void deferNodeOps(const RenderNode& renderNode);

    void deferRenderNodeOp(const RenderNodeOp& op);

    void replayBakedOpsImpl(void* arg, BakedOpReceiver* receivers);

    SkPath* createFrameAllocatedPath() {
        mFrameAllocatedPaths.emplace_back(new SkPath);
        return mFrameAllocatedPaths.back().get();
    }
    /**
     * Declares all OpReorderer::onXXXXOp() methods for every RecordedOp type.
     *
     * These private methods are called from within deferImpl to defer each individual op
     * type differently.
     */
#define INTERNAL_OP_HANDLER(Type) \
    void on##Type(const Type& op);
    MAP_OPS(INTERNAL_OP_HANDLER)

    std::vector<std::unique_ptr<SkPath> > mFrameAllocatedPaths;

    // List of every deferred layer's render state. Replayed in reverse order to render a frame.
    std::vector<LayerReorderer> mLayerReorderers;

    /*
     * Stack of indices within mLayerReorderers representing currently active layers. If drawing
     * layerA within a layerB, will contain, in order:
     *  - 0 (representing FBO 0, always present)
     *  - layerB's index
     *  - layerA's index
     *
     * Note that this doesn't vector doesn't always map onto all values of mLayerReorderers. When a
     * layer is finished deferring, it will still be represented in mLayerReorderers, but it's index
     * won't be in mLayerStack. This is because it can be replayed, but can't have any more drawing
     * ops added to it.
    */
    std::vector<size_t> mLayerStack;

    CanvasState mCanvasState;

    // contains ResolvedOps and Batches
    LinearAllocator mAllocator;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_OP_REORDERER_H
