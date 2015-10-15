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

#include "OpReorderer.h"

#include "utils/PaintUtils.h"
#include "RenderNode.h"

#include "SkCanvas.h"
#include "utils/Trace.h"

namespace android {
namespace uirenderer {

class BatchBase {

public:
    BatchBase(batchid_t batchId, BakedOpState* op, bool merging)
        : mBatchId(batchId)
        , mMerging(merging) {
        mBounds = op->computedState.clippedBounds;
        mOps.push_back(op);
    }

    bool intersects(const Rect& rect) const {
        if (!rect.intersects(mBounds)) return false;

        for (const BakedOpState* op : mOps) {
            if (rect.intersects(op->computedState.clippedBounds)) {
                return true;
            }
        }
        return false;
    }

    batchid_t getBatchId() const { return mBatchId; }
    bool isMerging() const { return mMerging; }

    const std::vector<BakedOpState*>& getOps() const { return mOps; }

    void dump() const {
        ALOGD("    Batch %p, merging %d, bounds " RECT_STRING, this, mMerging, RECT_ARGS(mBounds));
    }
protected:
    batchid_t mBatchId;
    Rect mBounds;
    std::vector<BakedOpState*> mOps;
    bool mMerging;
};

class OpBatch : public BatchBase {
public:
    static void* operator new(size_t size, LinearAllocator& allocator) {
        return allocator.alloc(size);
    }

    OpBatch(batchid_t batchId, BakedOpState* op)
            : BatchBase(batchId, op, false) {
    }

    void batchOp(BakedOpState* op) {
        mBounds.unionWith(op->computedState.clippedBounds);
        mOps.push_back(op);
    }
};

class MergingOpBatch : public BatchBase {
public:
    static void* operator new(size_t size, LinearAllocator& allocator) {
        return allocator.alloc(size);
    }

    MergingOpBatch(batchid_t batchId, BakedOpState* op)
            : BatchBase(batchId, op, true) {
    }

    /*
     * Helper for determining if a new op can merge with a MergingDrawBatch based on their bounds
     * and clip side flags. Positive bounds delta means new bounds fit in old.
     */
    static inline bool checkSide(const int currentFlags, const int newFlags, const int side,
            float boundsDelta) {
        bool currentClipExists = currentFlags & side;
        bool newClipExists = newFlags & side;

        // if current is clipped, we must be able to fit new bounds in current
        if (boundsDelta > 0 && currentClipExists) return false;

        // if new is clipped, we must be able to fit current bounds in new
        if (boundsDelta < 0 && newClipExists) return false;

        return true;
    }

    static bool paintIsDefault(const SkPaint& paint) {
        return paint.getAlpha() == 255
                && paint.getColorFilter() == nullptr
                && paint.getShader() == nullptr;
    }

    static bool paintsAreEquivalent(const SkPaint& a, const SkPaint& b) {
        return a.getAlpha() == b.getAlpha()
                && a.getColorFilter() == b.getColorFilter()
                && a.getShader() == b.getShader();
    }

    /*
     * Checks if a (mergeable) op can be merged into this batch
     *
     * If true, the op's multiDraw must be guaranteed to handle both ops simultaneously, so it is
     * important to consider all paint attributes used in the draw calls in deciding both a) if an
     * op tries to merge at all, and b) if the op can merge with another set of ops
     *
     * False positives can lead to information from the paints of subsequent merged operations being
     * dropped, so we make simplifying qualifications on the ops that can merge, per op type.
     */
    bool canMergeWith(BakedOpState* op) const {
        bool isTextBatch = getBatchId() == OpBatchType::Text
                || getBatchId() == OpBatchType::ColorText;

        // Overlapping other operations is only allowed for text without shadow. For other ops,
        // multiDraw isn't guaranteed to overdraw correctly
        if (!isTextBatch || PaintUtils::hasTextShadow(op->op->paint)) {
            if (intersects(op->computedState.clippedBounds)) return false;
        }

        const BakedOpState* lhs = op;
        const BakedOpState* rhs = mOps[0];

        if (!MathUtils::areEqual(lhs->alpha, rhs->alpha)) return false;

        // Identical round rect clip state means both ops will clip in the same way, or not at all.
        // As the state objects are const, we can compare their pointers to determine mergeability
        if (lhs->roundRectClipState != rhs->roundRectClipState) return false;
        if (lhs->projectionPathMask != rhs->projectionPathMask) return false;

        /* Clipping compatibility check
         *
         * Exploits the fact that if a op or batch is clipped on a side, its bounds will equal its
         * clip for that side.
         */
        const int currentFlags = mClipSideFlags;
        const int newFlags = op->computedState.clipSideFlags;
        if (currentFlags != OpClipSideFlags::None || newFlags != OpClipSideFlags::None) {
            const Rect& opBounds = op->computedState.clippedBounds;
            float boundsDelta = mBounds.left - opBounds.left;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Left, boundsDelta)) return false;
            boundsDelta = mBounds.top - opBounds.top;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Top, boundsDelta)) return false;

            // right and bottom delta calculation reversed to account for direction
            boundsDelta = opBounds.right - mBounds.right;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Right, boundsDelta)) return false;
            boundsDelta = opBounds.bottom - mBounds.bottom;
            if (!checkSide(currentFlags, newFlags, OpClipSideFlags::Bottom, boundsDelta)) return false;
        }

        const SkPaint* newPaint = op->op->paint;
        const SkPaint* oldPaint = mOps[0]->op->paint;

        if (newPaint == oldPaint) {
            // if paints are equal, then modifiers + paint attribs don't need to be compared
            return true;
        } else if (newPaint && !oldPaint) {
            return paintIsDefault(*newPaint);
        } else if (!newPaint && oldPaint) {
            return paintIsDefault(*oldPaint);
        }
        return paintsAreEquivalent(*newPaint, *oldPaint);
    }

    void mergeOp(BakedOpState* op) {
        mBounds.unionWith(op->computedState.clippedBounds);
        mOps.push_back(op);

        const int newClipSideFlags = op->computedState.clipSideFlags;
        mClipSideFlags |= newClipSideFlags;

        const Rect& opClip = op->computedState.clipRect;
        if (newClipSideFlags & OpClipSideFlags::Left) mClipRect.left = opClip.left;
        if (newClipSideFlags & OpClipSideFlags::Top) mClipRect.top = opClip.top;
        if (newClipSideFlags & OpClipSideFlags::Right) mClipRect.right = opClip.right;
        if (newClipSideFlags & OpClipSideFlags::Bottom) mClipRect.bottom = opClip.bottom;
    }

private:
    int mClipSideFlags = 0;
    Rect mClipRect;
};

class NullClient: public CanvasStateClient {
    void onViewportInitialized() override {}
    void onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {}
    GLuint getTargetFbo() const override { return 0; }
};
static NullClient sNullClient;

OpReorderer::OpReorderer()
        : mCanvasState(sNullClient) {
}

void OpReorderer::defer(const SkRect& clip, int viewportWidth, int viewportHeight,
        const std::vector< sp<RenderNode> >& nodes) {
    mCanvasState.initializeSaveStack(viewportWidth, viewportHeight,
            clip.fLeft, clip.fTop, clip.fRight, clip.fBottom,
            Vector3());
    for (const sp<RenderNode>& node : nodes) {
        if (node->nothingToDraw()) continue;

        // TODO: dedupe this code with onRenderNode()
        mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);
        if (node->applyViewProperties(mCanvasState)) {
            // not rejected do ops...
            const DisplayListData& data = node->getDisplayListData();
            deferImpl(data.getChunks(), data.getOps());
        }
        mCanvasState.restore();
    }
}

void OpReorderer::defer(int viewportWidth, int viewportHeight,
        const std::vector<DisplayListData::Chunk>& chunks, const std::vector<RecordedOp*>& ops) {
    ATRACE_NAME("prepare drawing commands");
    mCanvasState.initializeSaveStack(viewportWidth, viewportHeight,
            0, 0, viewportWidth, viewportHeight, Vector3());
    deferImpl(chunks, ops);
}

/**
 * Used to define a list of lambdas referencing private OpReorderer::onXXXXOp() methods.
 *
 * This allows opIds embedded in the RecordedOps to be used for dispatching to these lambdas. E.g. a
 * BitmapOp op then would be dispatched to OpReorderer::onBitmapOp(const BitmapOp&)
 */
#define OP_RECIEVER(Type) \
        [](OpReorderer& reorderer, const RecordedOp& op) { reorderer.on##Type(static_cast<const Type&>(op)); },
void OpReorderer::deferImpl(const std::vector<DisplayListData::Chunk>& chunks,
        const std::vector<RecordedOp*>& ops) {
    static std::function<void(OpReorderer& reorderer, const RecordedOp&)> receivers[] = {
        MAP_OPS(OP_RECIEVER)
    };
    for (const DisplayListData::Chunk& chunk : chunks) {
        for (size_t opIndex = chunk.beginOpIndex; opIndex < chunk.endOpIndex; opIndex++) {
            const RecordedOp* op = ops[opIndex];
            receivers[op->opId](*this, *op);
        }
    }
}

void OpReorderer::replayBakedOpsImpl(void* arg, BakedOpReceiver* receivers) {
    ATRACE_NAME("flush drawing commands");
    for (const BatchBase* batch : mBatches) {
        // TODO: different behavior based on batch->isMerging()
        for (const BakedOpState* op : batch->getOps()) {
            receivers[op->op->opId](arg, *op->op, *op);
        }
    }
}

BakedOpState* OpReorderer::bakeOpState(const RecordedOp& recordedOp) {
    return BakedOpState::tryConstruct(mAllocator, *mCanvasState.currentSnapshot(), recordedOp);
}

void OpReorderer::onRenderNodeOp(const RenderNodeOp& op) {
    if (op.renderNode->nothingToDraw()) {
        return;
    }
    mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);

    // apply state from RecordedOp
    mCanvasState.concatMatrix(op.localMatrix);
    mCanvasState.clipRect(op.localClipRect.left, op.localClipRect.top,
            op.localClipRect.right, op.localClipRect.bottom, SkRegion::kIntersect_Op);

    // apply RenderProperties state
    if (op.renderNode->applyViewProperties(mCanvasState)) {
        // not rejected do ops...
        const DisplayListData& data = op.renderNode->getDisplayListData();
        deferImpl(data.getChunks(), data.getOps());
    }
    mCanvasState.restore();
}

static batchid_t tessellatedBatchId(const SkPaint& paint) {
    return paint.getPathEffect()
            ? OpBatchType::AlphaMaskTexture
            : (paint.isAntiAlias() ? OpBatchType::AlphaVertices : OpBatchType::Vertices);
}

void OpReorderer::onBitmapOp(const BitmapOp& op) {
    BakedOpState* bakedStateOp = bakeOpState(op);
    if (!bakedStateOp) return; // quick rejected

    mergeid_t mergeId = (mergeid_t) op.bitmap->getGenerationID();
    // TODO: AssetAtlas

    deferMergeableOp(bakedStateOp, OpBatchType::Bitmap, mergeId);
}

void OpReorderer::onRectOp(const RectOp& op) {
    BakedOpState* bakedStateOp = bakeOpState(op);
    if (!bakedStateOp) return; // quick rejected
    deferUnmergeableOp(bakedStateOp, tessellatedBatchId(*op.paint));
}

void OpReorderer::onSimpleRectsOp(const SimpleRectsOp& op) {
    BakedOpState* bakedStateOp = bakeOpState(op);
    if (!bakedStateOp) return; // quick rejected
    deferUnmergeableOp(bakedStateOp, OpBatchType::Vertices);
}

// iterate back toward target to see if anything drawn since should overlap the new op
// if no target, merging ops still interate to find similar batch to insert after
void OpReorderer::locateInsertIndex(int batchId, const Rect& clippedBounds,
        BatchBase** targetBatch, size_t* insertBatchIndex) const {
    for (int i = mBatches.size() - 1; i >= mEarliestBatchIndex; i--) {
        BatchBase* overBatch = mBatches[i];

        if (overBatch == *targetBatch) break;

        // TODO: also consider shader shared between batch types
        if (batchId == overBatch->getBatchId()) {
            *insertBatchIndex = i + 1;
            if (!*targetBatch) break; // found insert position, quit
        }

        if (overBatch->intersects(clippedBounds)) {
            // NOTE: it may be possible to optimize for special cases where two operations
            // of the same batch/paint could swap order, such as with a non-mergeable
            // (clipped) and a mergeable text operation
            *targetBatch = nullptr;
            break;
        }
    }
}

void OpReorderer::deferUnmergeableOp(BakedOpState* op, batchid_t batchId) {
    OpBatch* targetBatch = mBatchLookup[batchId];

    size_t insertBatchIndex = mBatches.size();
    if (targetBatch) {
        locateInsertIndex(batchId, op->computedState.clippedBounds,
                (BatchBase**)(&targetBatch), &insertBatchIndex);
    }

    if (targetBatch) {
        targetBatch->batchOp(op);
    } else  {
        // new non-merging batch
        targetBatch = new (mAllocator) OpBatch(batchId, op);
        mBatchLookup[batchId] = targetBatch;
        mBatches.insert(mBatches.begin() + insertBatchIndex, targetBatch);
    }
}

// insertion point of a new batch, will hopefully be immediately after similar batch
// (generally, should be similar shader)
void OpReorderer::deferMergeableOp(BakedOpState* op, batchid_t batchId, mergeid_t mergeId) {
    MergingOpBatch* targetBatch = nullptr;

    // Try to merge with any existing batch with same mergeId
    auto getResult = mMergingBatches[batchId].find(mergeId);
    if (getResult != mMergingBatches[batchId].end()) {
        targetBatch = getResult->second;
        if (!targetBatch->canMergeWith(op)) {
            targetBatch = nullptr;
        }
    }

    size_t insertBatchIndex = mBatches.size();
    locateInsertIndex(batchId, op->computedState.clippedBounds,
            (BatchBase**)(&targetBatch), &insertBatchIndex);

    if (targetBatch) {
        targetBatch->mergeOp(op);
    } else  {
        // new merging batch
        targetBatch = new (mAllocator) MergingOpBatch(batchId, op);
        mMergingBatches[batchId].insert(std::make_pair(mergeId, targetBatch));

        mBatches.insert(mBatches.begin() + insertBatchIndex, targetBatch);
    }
}

void OpReorderer::dump() {
    for (const BatchBase* batch : mBatches) {
        batch->dump();
    }
}

} // namespace uirenderer
} // namespace android
