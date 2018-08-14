/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include "ClipArea.h"
#include "Rect.h"
#include "utils/Macros.h"

#include <unordered_map>
#include <vector>

struct SkRect;

namespace android {
namespace uirenderer {

class BakedOpState;
struct BeginLayerOp;
class BatchBase;
class LinearAllocator;
struct MergedBakedOpList;
class MergingOpBatch;
class OffscreenBuffer;
class OpBatch;
class RenderNode;

typedef int batchid_t;
typedef const void* mergeid_t;

namespace OpBatchType {
enum {
    Bitmap,
    MergedPatch,
    AlphaVertices,
    Vertices,
    AlphaMaskTexture,
    Text,
    ColorText,
    Shadow,
    TextureLayer,
    Functor,
    CopyToLayer,
    CopyFromLayer,

    Count  // must be last
};
}

typedef void (*BakedOpReceiver)(void*, const BakedOpState&);
typedef void (*MergedOpReceiver)(void*, const MergedBakedOpList& opList);

/**
 * Stores the deferred render operations and state used to compute ordering
 * for a single FBO/layer.
 */
class LayerBuilder {
    // Prevent copy/assign because users may stash pointer to offscreenBuffer and viewportClip
    PREVENT_COPY_AND_ASSIGN(LayerBuilder);

public:
    // Create LayerBuilder for Fbo0
    LayerBuilder(uint32_t width, uint32_t height, const Rect& repaintRect)
            : LayerBuilder(width, height, repaintRect, nullptr, nullptr){};

    // Create LayerBuilder for an offscreen layer, where beginLayerOp is present for a
    // saveLayer, renderNode is present for a HW layer.
    LayerBuilder(uint32_t width, uint32_t height, const Rect& repaintRect,
                 const BeginLayerOp* beginLayerOp, RenderNode* renderNode);

    // iterate back toward target to see if anything drawn since should overlap the new op
    // if no target, merging ops still iterate to find similar batch to insert after
    void locateInsertIndex(int batchId, const Rect& clippedBounds, BatchBase** targetBatch,
                           size_t* insertBatchIndex) const;

    void deferUnmergeableOp(LinearAllocator& allocator, BakedOpState* op, batchid_t batchId);

    // insertion point of a new batch, will hopefully be immediately after similar batch
    // (generally, should be similar shader)
    void deferMergeableOp(LinearAllocator& allocator, BakedOpState* op, batchid_t batchId,
                          mergeid_t mergeId);

    void replayBakedOpsImpl(void* arg, BakedOpReceiver* receivers, MergedOpReceiver*) const;

    void deferLayerClear(const Rect& dstRect);

    bool empty() const { return mBatches.empty(); }

    void clear();

    void dump() const;

    const uint32_t width;
    const uint32_t height;
    const Rect repaintRect;
    const ClipRect repaintClip;
    OffscreenBuffer* offscreenBuffer;
    const BeginLayerOp* beginLayerOp;
    const RenderNode* renderNode;

    // list of deferred CopyFromLayer ops, to be deferred upon encountering EndUnclippedLayerOps
    std::vector<BakedOpState*> activeUnclippedSaveLayers;

private:
    void onDeferOp(LinearAllocator& allocator, const BakedOpState* bakedState);
    void flushLayerClears(LinearAllocator& allocator);

    std::vector<BatchBase*> mBatches;

    /**
     * Maps the mergeid_t returned by an op's getMergeId() to the most recently seen
     * MergingDrawBatch of that id. These ids are unique per draw type and guaranteed to not
     * collide, which avoids the need to resolve mergeid collisions.
     */
    std::unordered_map<mergeid_t, MergingOpBatch*> mMergingBatchLookup[OpBatchType::Count];

    // Maps batch ids to the most recent *non-merging* batch of that id
    OpBatch* mBatchLookup[OpBatchType::Count] = {nullptr};

    std::vector<Rect> mClearRects;
};

};  // namespace uirenderer
};  // namespace android
