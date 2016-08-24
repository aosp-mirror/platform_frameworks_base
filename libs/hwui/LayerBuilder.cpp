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

#include "LayerBuilder.h"

#include "BakedOpState.h"
#include "RenderNode.h"
#include "utils/PaintUtils.h"
#include "utils/TraceUtils.h"

#include <utils/TypeHelpers.h>

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
        ALOGD("    Batch %p, id %d, merging %d, count %d, bounds " RECT_STRING,
                this, mBatchId, mMerging, (int) mOps.size(), RECT_ARGS(mBounds));
    }
protected:
    batchid_t mBatchId;
    Rect mBounds;
    std::vector<BakedOpState*> mOps;
    bool mMerging;
};

class OpBatch : public BatchBase {
public:
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
    MergingOpBatch(batchid_t batchId, BakedOpState* op)
            : BatchBase(batchId, op, true)
            , mClipSideFlags(op->computedState.clipSideFlags) {
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
        // Note: don't check color, since all currently mergeable ops can merge across colors
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

        // Local masks prevent merge, since they're potentially in different coordinate spaces
        if (lhs->computedState.localProjectionPathMask
                || rhs->computedState.localProjectionPathMask) return false;

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

        // Because a new op must have passed canMergeWith(), we know it's passed the clipping compat
        // check, and doesn't extend past a side of the clip that's in use by the merged batch.
        // Therefore it's safe to simply always merge flags, and use the bounds as the clip rect.
        mClipSideFlags |= op->computedState.clipSideFlags;
    }

    int getClipSideFlags() const { return mClipSideFlags; }
    const Rect& getClipRect() const { return mBounds; }

private:
    int mClipSideFlags;
};

LayerBuilder::LayerBuilder(uint32_t width, uint32_t height,
        const Rect& repaintRect, const BeginLayerOp* beginLayerOp, RenderNode* renderNode)
        : width(width)
        , height(height)
        , repaintRect(repaintRect)
        , repaintClip(repaintRect)
        , offscreenBuffer(renderNode ? renderNode->getLayer() : nullptr)
        , beginLayerOp(beginLayerOp)
        , renderNode(renderNode) {}

// iterate back toward target to see if anything drawn since should overlap the new op
// if no target, merging ops still iterate to find similar batch to insert after
void LayerBuilder::locateInsertIndex(int batchId, const Rect& clippedBounds,
        BatchBase** targetBatch, size_t* insertBatchIndex) const {
    for (int i = mBatches.size() - 1; i >= 0; i--) {
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

void LayerBuilder::deferLayerClear(const Rect& rect) {
    mClearRects.push_back(rect);
}

void LayerBuilder::onDeferOp(LinearAllocator& allocator, const BakedOpState* bakedState) {
    if (bakedState->op->opId != RecordedOpId::CopyToLayerOp) {
        // First non-CopyToLayer, so stop stashing up layer clears for unclipped save layers,
        // and issue them together in one draw.
        flushLayerClears(allocator);

        if (CC_UNLIKELY(activeUnclippedSaveLayers.empty()
                && bakedState->computedState.opaqueOverClippedBounds
                && bakedState->computedState.clippedBounds.contains(repaintRect)
                && !Properties::debugOverdraw)) {
            // discard all deferred drawing ops, since new one will occlude them
            clear();
        }
    }
}

void LayerBuilder::flushLayerClears(LinearAllocator& allocator) {
    if (CC_UNLIKELY(!mClearRects.empty())) {
        const int vertCount = mClearRects.size() * 4;
        // put the verts in the frame allocator, since
        //     1) SimpleRectsOps needs verts, not rects
        //     2) even if mClearRects stored verts, std::vectors will move their contents
        Vertex* const verts = (Vertex*) allocator.create_trivial_array<Vertex>(vertCount);

        Vertex* currentVert = verts;
        Rect bounds = mClearRects[0];
        for (auto&& rect : mClearRects) {
            bounds.unionWith(rect);
            Vertex::set(currentVert++, rect.left, rect.top);
            Vertex::set(currentVert++, rect.right, rect.top);
            Vertex::set(currentVert++, rect.left, rect.bottom);
            Vertex::set(currentVert++, rect.right, rect.bottom);
        }
        mClearRects.clear(); // discard rects before drawing so this method isn't reentrant

        // One or more unclipped saveLayers have been enqueued, with deferred clears.
        // Flush all of these clears with a single draw
        SkPaint* paint = allocator.create<SkPaint>();
        paint->setXfermodeMode(SkXfermode::kClear_Mode);
        SimpleRectsOp* op = allocator.create_trivial<SimpleRectsOp>(bounds,
                Matrix4::identity(), nullptr, paint,
                verts, vertCount);
        BakedOpState* bakedState = BakedOpState::directConstruct(allocator,
                &repaintClip, bounds, *op);
        deferUnmergeableOp(allocator, bakedState, OpBatchType::Vertices);
    }
}

void LayerBuilder::deferUnmergeableOp(LinearAllocator& allocator,
        BakedOpState* op, batchid_t batchId) {
    onDeferOp(allocator, op);
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
        targetBatch = allocator.create<OpBatch>(batchId, op);
        mBatchLookup[batchId] = targetBatch;
        mBatches.insert(mBatches.begin() + insertBatchIndex, targetBatch);
    }
}

void LayerBuilder::deferMergeableOp(LinearAllocator& allocator,
        BakedOpState* op, batchid_t batchId, mergeid_t mergeId) {
    onDeferOp(allocator, op);
    MergingOpBatch* targetBatch = nullptr;

    // Try to merge with any existing batch with same mergeId
    auto getResult = mMergingBatchLookup[batchId].find(mergeId);
    if (getResult != mMergingBatchLookup[batchId].end()) {
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
        targetBatch = allocator.create<MergingOpBatch>(batchId, op);
        mMergingBatchLookup[batchId].insert(std::make_pair(mergeId, targetBatch));

        mBatches.insert(mBatches.begin() + insertBatchIndex, targetBatch);
    }
}

void LayerBuilder::replayBakedOpsImpl(void* arg,
        BakedOpReceiver* unmergedReceivers, MergedOpReceiver* mergedReceivers) const {
    if (renderNode) {
        ATRACE_FORMAT_BEGIN("Issue HW Layer DisplayList %s %ux%u",
                renderNode->getName(), width, height);
    } else {
        ATRACE_BEGIN("flush drawing commands");
    }

    for (const BatchBase* batch : mBatches) {
        size_t size = batch->getOps().size();
        if (size > 1 && batch->isMerging()) {
            int opId = batch->getOps()[0]->op->opId;
            const MergingOpBatch* mergingBatch = static_cast<const MergingOpBatch*>(batch);
            MergedBakedOpList data = {
                    batch->getOps().data(),
                    size,
                    mergingBatch->getClipSideFlags(),
                    mergingBatch->getClipRect()
            };
            mergedReceivers[opId](arg, data);
        } else {
            for (const BakedOpState* op : batch->getOps()) {
                unmergedReceivers[op->op->opId](arg, *op);
            }
        }
    }
    ATRACE_END();
}

void LayerBuilder::clear() {
    mBatches.clear();
    for (int i = 0; i < OpBatchType::Count; i++) {
        mBatchLookup[i] = nullptr;
        mMergingBatchLookup[i].clear();
    }
}

void LayerBuilder::dump() const {
    ALOGD("LayerBuilder %p, %ux%u buffer %p, blo %p, rn %p (%s)",
            this, width, height, offscreenBuffer, beginLayerOp,
            renderNode, renderNode ? renderNode->getName() : "-");
    for (const BatchBase* batch : mBatches) {
        batch->dump();
    }
}

} // namespace uirenderer
} // namespace android
