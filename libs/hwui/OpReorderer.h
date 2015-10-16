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

class OpReorderer {
public:
    OpReorderer();

    // TODO: not final, just presented this way for simplicity. Layers too?
    void defer(const SkRect& clip, int viewportWidth, int viewportHeight,
            const std::vector< sp<RenderNode> >& nodes);

    void defer(int viewportWidth, int viewportHeight,
            const std::vector<DisplayList::Chunk>& chunks, const std::vector<RecordedOp*>& ops);
    typedef std::function<void(void*, const RecordedOp&, const BakedOpState&)> BakedOpReceiver;

    /**
     * replayBakedOps() is templated based on what class will recieve ops being replayed.
     *
     * It constructs a lookup array of lambdas, which allows a recorded BakeOpState to use
     * state->op->opId to lookup a receiver that will be called when the op is replayed.
     *
     * For example a BitmapOp would resolve, via the lambda lookup, to calling:
     *
     * StaticReceiver::onBitmapOp(Arg* arg, const BitmapOp& op, const BakedOpState& state);
     */
#define BAKED_OP_RECEIVER(Type) \
    [](void* internalArg, const RecordedOp& op, const BakedOpState& state) { \
        StaticReceiver::on##Type(static_cast<Arg*>(internalArg), static_cast<const Type&>(op), state); \
    },
    template <typename StaticReceiver, typename Arg>
    void replayBakedOps(Arg* arg) {
        static BakedOpReceiver receivers[] = {
            MAP_OPS(BAKED_OP_RECEIVER)
        };
        StaticReceiver::startFrame(*arg);
        replayBakedOpsImpl((void*)arg, receivers);
        StaticReceiver::endFrame(*arg);
    }
private:
    BakedOpState* bakeOpState(const RecordedOp& recordedOp);

    void deferImpl(const std::vector<DisplayList::Chunk>& chunks,
            const std::vector<RecordedOp*>& ops);

    void replayBakedOpsImpl(void* arg, BakedOpReceiver* receivers);

    /**
     * Declares all OpReorderer::onXXXXOp() methods for every RecordedOp type.
     *
     * These private methods are called from within deferImpl to defer each individual op
     * type differently.
     */
#define INTERNAL_OP_HANDLER(Type) \
    void on##Type(const Type& op);
    MAP_OPS(INTERNAL_OP_HANDLER)

    // iterate back toward target to see if anything drawn since should overlap the new op
    // if no target, merging ops still iterate to find similar batch to insert after
    void locateInsertIndex(int batchId, const Rect& clippedBounds,
            BatchBase** targetBatch, size_t* insertBatchIndex) const;

    void deferUnmergeableOp(BakedOpState* op, batchid_t batchId);

    // insertion point of a new batch, will hopefully be immediately after similar batch
    // (generally, should be similar shader)
    void deferMergeableOp(BakedOpState* op, batchid_t batchId, mergeid_t mergeId);

    void dump();

    std::vector<BatchBase*> mBatches;

    /**
     * Maps the mergeid_t returned by an op's getMergeId() to the most recently seen
     * MergingDrawBatch of that id. These ids are unique per draw type and guaranteed to not
     * collide, which avoids the need to resolve mergeid collisions.
     */
    std::unordered_map<mergeid_t, MergingOpBatch*> mMergingBatches[OpBatchType::Count];

    // Maps batch ids to the most recent *non-merging* batch of that id
    OpBatch* mBatchLookup[OpBatchType::Count] = { nullptr };
    CanvasState mCanvasState;

    // contains ResolvedOps and Batches
    LinearAllocator mAllocator;

    int mEarliestBatchIndex = 0;
};

}; // namespace uirenderer
}; // namespace android

#endif // ANDROID_HWUI_OP_REORDERER_H
