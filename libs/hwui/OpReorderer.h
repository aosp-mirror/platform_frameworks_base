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

        Count // must be last
    };
}

class OpReorderer : public CanvasStateClient {
    typedef std::function<void(void*, const RecordedOp&, const BakedOpState&)> BakedOpDispatcher;

    /**
     * Stores the deferred render operations and state used to compute ordering
     * for a single FBO/layer.
     */
    class LayerReorderer {
    public:
        LayerReorderer(uint32_t width, uint32_t height)
                : width(width)
                , height(height) {}

        // iterate back toward target to see if anything drawn since should overlap the new op
        // if no target, merging ops still iterate to find similar batch to insert after
        void locateInsertIndex(int batchId, const Rect& clippedBounds,
                BatchBase** targetBatch, size_t* insertBatchIndex) const;

        void deferUnmergeableOp(LinearAllocator& allocator, BakedOpState* op, batchid_t batchId);

        // insertion point of a new batch, will hopefully be immediately after similar batch
        // (generally, should be similar shader)
        void deferMergeableOp(LinearAllocator& allocator,
                BakedOpState* op, batchid_t batchId, mergeid_t mergeId);

        void replayBakedOpsImpl(void* arg, BakedOpDispatcher* receivers) const;

        bool empty() const {
            return mBatches.empty();
        }

        void clear() {
            mBatches.clear();
        }

        void dump() const;

        OffscreenBuffer* offscreenBuffer = nullptr;
        const BeginLayerOp* beginLayerOp = nullptr;
        const uint32_t width;
        const uint32_t height;
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
    // TODO: not final, just presented this way for simplicity. Layers too?
    OpReorderer(const SkRect& clip, uint32_t viewportWidth, uint32_t viewportHeight,
            const std::vector< sp<RenderNode> >& nodes);

    OpReorderer(int viewportWidth, int viewportHeight, const DisplayList& displayList);

    virtual ~OpReorderer() {}

    /**
     * replayBakedOps() is templated based on what class will receive ops being replayed.
     *
     * It constructs a lookup array of lambdas, which allows a recorded BakeOpState to use
     * state->op->opId to lookup a receiver that will be called when the op is replayed.
     *
     * For example a BitmapOp would resolve, via the lambda lookup, to calling:
     *
     * StaticDispatcher::onBitmapOp(Renderer& renderer, const BitmapOp& op, const BakedOpState& state);
     */
#define BAKED_OP_RECEIVER(Type) \
    [](void* internalRenderer, const RecordedOp& op, const BakedOpState& state) { \
        StaticDispatcher::on##Type(*(static_cast<Renderer*>(internalRenderer)), static_cast<const Type&>(op), state); \
    },
    template <typename StaticDispatcher, typename Renderer>
    void replayBakedOps(Renderer& renderer) {
        static BakedOpDispatcher receivers[] = {
            MAP_OPS(BAKED_OP_RECEIVER)
        };

        // Relay through layers in reverse order, since layers
        // later in the list will be drawn by earlier ones
        for (int i = mLayerReorderers.size() - 1; i >= 1; i--) {
            LayerReorderer& layer = mLayerReorderers[i];
            if (!layer.empty()) {
                layer.offscreenBuffer = renderer.startLayer(layer.width, layer.height);
                layer.replayBakedOpsImpl((void*)&renderer, receivers);
                renderer.endLayer();
            }
        }

        const LayerReorderer& fbo0 = mLayerReorderers[0];
        renderer.startFrame(fbo0.width, fbo0.height);
        fbo0.replayBakedOpsImpl((void*)&renderer, receivers);
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
    LayerReorderer& currentLayer() { return mLayerReorderers[mLayerStack.back()]; }

    BakedOpState* tryBakeOpState(const RecordedOp& recordedOp) {
        return BakedOpState::tryConstruct(mAllocator, *mCanvasState.currentSnapshot(), recordedOp);
    }

    void deferImpl(const DisplayList& displayList);

    void replayBakedOpsImpl(void* arg, BakedOpDispatcher* receivers);

    /**
     * Declares all OpReorderer::onXXXXOp() methods for every RecordedOp type.
     *
     * These private methods are called from within deferImpl to defer each individual op
     * type differently.
     */
#define INTERNAL_OP_HANDLER(Type) \
    void on##Type(const Type& op);
    MAP_OPS(INTERNAL_OP_HANDLER)

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
