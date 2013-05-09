/*
 * Copyright (C) 2013 The Android Open Source Project
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

#define LOG_TAG "OpenGLRenderer"
#define ATRACE_TAG ATRACE_TAG_VIEW

#include <SkCanvas.h>

#include <utils/Trace.h>

#include "Caches.h"
#include "Debug.h"
#include "DeferredDisplayList.h"
#include "DisplayListOp.h"
#include "OpenGLRenderer.h"

#if DEBUG_DEFER
    #define DEFER_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define DEFER_LOGD(...)
#endif

namespace android {
namespace uirenderer {

// Depth of the save stack at the beginning of batch playback at flush time
#define FLUSH_SAVE_STACK_DEPTH 2

#define DEBUG_COLOR_BARRIER          0x1f000000
#define DEBUG_COLOR_MERGEDBATCH      0x5f7f7fff
#define DEBUG_COLOR_MERGEDBATCH_SOLO 0x5f7fff7f

/////////////////////////////////////////////////////////////////////////////////
// Operation Batches
/////////////////////////////////////////////////////////////////////////////////

class Batch {
public:
    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int index) = 0;
    virtual ~Batch() {}
};

class DrawBatch : public Batch {
public:
    DrawBatch(int batchId, mergeid_t mergeId) : mBatchId(batchId), mMergeId(mergeId) {
        mOps.clear();
    }

    virtual ~DrawBatch() { mOps.clear(); }

    void add(DrawOp* op) {
        // NOTE: ignore empty bounds special case, since we don't merge across those ops
        mBounds.unionWith(op->state.mBounds);
        mOps.add(op);
    }

    bool intersects(Rect& rect) {
        if (!rect.intersects(mBounds)) return false;

        for (unsigned int i = 0; i < mOps.size(); i++) {
            if (rect.intersects(mOps[i]->state.mBounds)) {
#if DEBUG_DEFER
                DEFER_LOGD("op intersects with op %p with bounds %f %f %f %f:", mOps[i],
                        mOps[i]->state.mBounds.left, mOps[i]->state.mBounds.top,
                        mOps[i]->state.mBounds.right, mOps[i]->state.mBounds.bottom);
                mOps[i]->output(2);
#endif
                return true;
            }
        }
        return false;
    }

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int index) {
        DEFER_LOGD("%d  replaying DrawingBatch %p, with %d ops (batch id %x, merge id %p)",
                index, this, mOps.size(), mOps[0]->getBatchId(), mOps[0]->getMergeId());

        status_t status = DrawGlInfo::kStatusDone;
        DisplayListLogBuffer& logBuffer = DisplayListLogBuffer::getInstance();
        for (unsigned int i = 0; i < mOps.size(); i++) {
            DrawOp* op = mOps[i];

            renderer.restoreDisplayState(op->state);

#if DEBUG_DISPLAY_LIST_OPS_AS_EVENTS
            renderer.eventMark(op->name());
#endif
            logBuffer.writeCommand(0, op->name());
            status |= op->applyDraw(renderer, dirty);

#if DEBUG_MERGE_BEHAVIOR
            Rect& bounds = mOps[i]->state.mBounds;
            int batchColor = 0x1f000000;
            if (getBatchId() & 0x1) batchColor |= 0x0000ff;
            if (getBatchId() & 0x2) batchColor |= 0x00ff00;
            if (getBatchId() & 0x4) batchColor |= 0xff0000;
            renderer.drawScreenSpaceColorRect(bounds.left, bounds.top, bounds.right, bounds.bottom,
                    batchColor);
#endif
        }
        return status;
    }

    inline int getBatchId() const { return mBatchId; }
    inline mergeid_t getMergeId() const { return mMergeId; }
    inline int count() const { return mOps.size(); }

protected:
    Vector<DrawOp*> mOps;
    Rect mBounds;
private:
    int mBatchId;
    mergeid_t mMergeId;
};

// compare alphas approximately, with a small margin
#define NEQ_FALPHA(lhs, rhs) \
        fabs((float)lhs - (float)rhs) > 0.001f

class MergingDrawBatch : public DrawBatch {
public:
    MergingDrawBatch(int batchId, mergeid_t mergeId) : DrawBatch(batchId, mergeId) {}

    /*
     * Checks if a (mergeable) op can be merged into this batch
     *
     * If true, the op's multiDraw must be guaranteed to handle both ops simultaneously, so it is
     * important to consider all paint attributes used in the draw calls in deciding both a) if an
     * op tries to merge at all, and b) if the op
     *
     * False positives can lead to information from the paints of subsequent merged operations being
     * dropped, so we make simplifying qualifications on the ops that can merge, per op type.
     */
    bool canMergeWith(DrawOp* op) {
        if (!op->state.mMatrix.isPureTranslate()) return false;

        bool isTextBatch = getBatchId() == DeferredDisplayList::kOpBatch_Text ||
                getBatchId() == DeferredDisplayList::kOpBatch_ColorText;

        // Overlapping other operations is only allowed for text without shadow. For other ops,
        // multiDraw isn't guaranteed to overdraw correctly
        if (!isTextBatch || op->state.mDrawModifiers.mHasShadow) {
            if (intersects(op->state.mBounds)) return false;
        }

        const DeferredDisplayState& lhs = op->state;
        const DeferredDisplayState& rhs = mOps[0]->state;

        if (NEQ_FALPHA(lhs.mAlpha, rhs.mAlpha)) return false;

        // if paints are equal, then modifiers + paint attribs don't need to be compared
        if (op->mPaint == mOps[0]->mPaint) return true;

        if (op->getPaintAlpha() != mOps[0]->getPaintAlpha()) return false;

        /* Draw Modifiers compatibility check
         *
         * Shadows are ignored, as only text uses them, and in that case they are drawn
         * per-DrawTextOp, before the unified text draw. Because of this, it's always safe to merge
         * text UNLESS a later draw's shadow should overlays a previous draw's text. This is covered
         * above with the intersection check.
         *
         * OverrideLayerAlpha is also ignored, as it's only used for drawing layers, which are never
         * merged.
         *
         * These ignore cases prevent us from simply memcmp'ing the drawModifiers
         */

        const DrawModifiers& lhsMod = lhs.mDrawModifiers;
        const DrawModifiers& rhsMod = rhs.mDrawModifiers;
        if (lhsMod.mShader != rhsMod.mShader) return false;
        if (lhsMod.mColorFilter != rhsMod.mColorFilter) return false;

        // Draw filter testing expects bit fields to be clear if filter not set.
        if (lhsMod.mHasDrawFilter != rhsMod.mHasDrawFilter) return false;
        if (lhsMod.mPaintFilterClearBits != rhsMod.mPaintFilterClearBits) return false;
        if (lhsMod.mPaintFilterSetBits != rhsMod.mPaintFilterSetBits) return false;

        return true;
    }

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int index) {
        DEFER_LOGD("%d  replaying DrawingBatch %p, with %d ops (batch id %x, merge id %p)",
                index, this, mOps.size(), getBatchId(), getMergeId());
        if (mOps.size() == 1) {
            return DrawBatch::replay(renderer, dirty, false);
        }

        DrawOp* op = mOps[0];
        DisplayListLogBuffer& buffer = DisplayListLogBuffer::getInstance();
        buffer.writeCommand(0, "multiDraw");
        buffer.writeCommand(1, op->name());
        status_t status = op->multiDraw(renderer, dirty, mOps, mBounds);

#if DEBUG_MERGE_BEHAVIOR
        renderer.drawScreenSpaceColorRect(mBounds.left, mBounds.top, mBounds.right, mBounds.bottom,
                DEBUG_COLOR_MERGEDBATCH);
#endif
        return status;
    }
};

class StateOpBatch : public Batch {
public:
    // creates a single operation batch
    StateOpBatch(StateOp* op) : mOp(op) {}

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int index) {
        DEFER_LOGD("replaying state op batch %p", this);
        renderer.restoreDisplayState(mOp->state);

        // use invalid save count because it won't be used at flush time - RestoreToCountOp is the
        // only one to use it, and we don't use that class at flush time, instead calling
        // renderer.restoreToCount directly
        int saveCount = -1;
        mOp->applyState(renderer, saveCount);
        return DrawGlInfo::kStatusDone;
    }

private:
    const StateOp* mOp;
};

class RestoreToCountBatch : public Batch {
public:
    RestoreToCountBatch(StateOp* op, int restoreCount) : mOp(op), mRestoreCount(restoreCount) {}

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int index) {
        DEFER_LOGD("batch %p restoring to count %d", this, mRestoreCount);

        renderer.restoreDisplayState(mOp->state);
        renderer.restoreToCount(mRestoreCount);
        return DrawGlInfo::kStatusDone;
    }

private:
    // we use the state storage for the RestoreToCountOp, but don't replay the op itself
    const StateOp* mOp;
    /*
     * The count used here represents the flush() time saveCount. This is as opposed to the
     * DisplayList record time, or defer() time values (which are RestoreToCountOp's mCount, and
     * (saveCount + mCount) respectively). Since the count is different from the original
     * RestoreToCountOp, we don't store a pointer to the op, as elsewhere.
     */
    const int mRestoreCount;
};

#if DEBUG_MERGE_BEHAVIOR
class BarrierDebugBatch : public Batch {
    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty, int index) {
        renderer.drawScreenSpaceColorRect(0, 0, 10000, 10000, DEBUG_COLOR_BARRIER);
        return DrawGlInfo::kStatusDrew;
    }
};
#endif

/////////////////////////////////////////////////////////////////////////////////
// DeferredDisplayList
/////////////////////////////////////////////////////////////////////////////////

void DeferredDisplayList::resetBatchingState() {
    for (int i = 0; i < kOpBatch_Count; i++) {
        mBatchLookup[i] = NULL;
        mMergingBatches[i].clear();
    }
#if DEBUG_MERGE_BEHAVIOR
    if (mBatches.size() != 0) {
        mBatches.add(new BarrierDebugBatch());
    }
#endif
    mEarliestBatchIndex = mBatches.size();
}

void DeferredDisplayList::clear() {
    resetBatchingState();
    mComplexClipStackStart = -1;

    for (unsigned int i = 0; i < mBatches.size(); i++) {
        delete mBatches[i];
    }
    mBatches.clear();
    mSaveStack.clear();
    mEarliestBatchIndex = 0;
}

/////////////////////////////////////////////////////////////////////////////////
// Operation adding
/////////////////////////////////////////////////////////////////////////////////

int DeferredDisplayList::getStateOpDeferFlags() const {
    // For both clipOp and save(Layer)Op, we don't want to save drawing info, and only want to save
    // the clip if we aren't recording a complex clip (and can thus trust it to be a rect)
    return recordingComplexClip() ? 0 : kStateDeferFlag_Clip;
}

int DeferredDisplayList::getDrawOpDeferFlags() const {
    return kStateDeferFlag_Draw | getStateOpDeferFlags();
}

/**
 * When an clipping operation occurs that could cause a complex clip, record the operation and all
 * subsequent clipOps, save/restores (if the clip flag is set). During a flush, instead of loading
 * the clip from deferred state, we play back all of the relevant state operations that generated
 * the complex clip.
 *
 * Note that we don't need to record the associated restore operation, since operations at defer
 * time record whether they should store the renderer's current clip
 */
void DeferredDisplayList::addClip(OpenGLRenderer& renderer, ClipOp* op) {
    if (recordingComplexClip() || op->canCauseComplexClip() || !renderer.hasRectToRectTransform()) {
        DEFER_LOGD("%p Received complex clip operation %p", this, op);

        // NOTE: defer clip op before setting mComplexClipStackStart so previous clip is recorded
        storeStateOpBarrier(renderer, op);

        if (!recordingComplexClip()) {
            mComplexClipStackStart = renderer.getSaveCount() - 1;
            DEFER_LOGD("    Starting complex clip region, start is %d", mComplexClipStackStart);
        }
    }
}

/**
 * For now, we record save layer operations as barriers in the batch list, preventing drawing
 * operations from reordering around the saveLayer and it's associated restore()
 *
 * In the future, we should send saveLayer commands (if they can be played out of order) and their
 * contained drawing operations to a seperate list of batches, so that they may draw at the
 * beginning of the frame. This would avoid targetting and removing an FBO in the middle of a frame.
 *
 * saveLayer operations should be pulled to the beginning of the frame if the canvas doesn't have a
 * complex clip, and if the flags (kClip_SaveFlag & kClipToLayer_SaveFlag) are set.
 */
void DeferredDisplayList::addSaveLayer(OpenGLRenderer& renderer,
        SaveLayerOp* op, int newSaveCount) {
    DEFER_LOGD("%p adding saveLayerOp %p, flags %x, new count %d",
            this, op, op->getFlags(), newSaveCount);

    storeStateOpBarrier(renderer, op);
    mSaveStack.push(newSaveCount);
}

/**
 * Takes save op and it's return value - the new save count - and stores it into the stream as a
 * barrier if it's needed to properly modify a complex clip
 */
void DeferredDisplayList::addSave(OpenGLRenderer& renderer, SaveOp* op, int newSaveCount) {
    int saveFlags = op->getFlags();
    DEFER_LOGD("%p adding saveOp %p, flags %x, new count %d", this, op, saveFlags, newSaveCount);

    if (recordingComplexClip() && (saveFlags & SkCanvas::kClip_SaveFlag)) {
        // store and replay the save operation, as it may be needed to correctly playback the clip
        DEFER_LOGD("    adding save barrier with new save count %d", newSaveCount);
        storeStateOpBarrier(renderer, op);
        mSaveStack.push(newSaveCount);
    }
}

/**
 * saveLayer() commands must be associated with a restoreToCount batch that will clean up and draw
 * the layer in the deferred list
 *
 * other save() commands which occur as children of a snapshot with complex clip will be deferred,
 * and must be restored
 *
 * Either will act as a barrier to draw operation reordering, as we want to play back layer
 * save/restore and complex canvas modifications (including save/restore) in order.
 */
void DeferredDisplayList::addRestoreToCount(OpenGLRenderer& renderer, StateOp* op,
        int newSaveCount) {
    DEFER_LOGD("%p addRestoreToCount %d", this, newSaveCount);

    if (recordingComplexClip() && newSaveCount <= mComplexClipStackStart) {
        mComplexClipStackStart = -1;
        resetBatchingState();
    }

    if (mSaveStack.isEmpty() || newSaveCount > mSaveStack.top()) {
        return;
    }

    while (!mSaveStack.isEmpty() && mSaveStack.top() >= newSaveCount) mSaveStack.pop();

    storeRestoreToCountBarrier(renderer, op, mSaveStack.size() + FLUSH_SAVE_STACK_DEPTH);
}

void DeferredDisplayList::addDrawOp(OpenGLRenderer& renderer, DrawOp* op) {
    if (renderer.storeDisplayState(op->state, getDrawOpDeferFlags())) {
        return; // quick rejected
    }

    int batchId = kOpBatch_None;
    mergeid_t mergeId = (mergeid_t) -1;
    bool mergeable = op->onDefer(renderer, &batchId, &mergeId);

    // complex clip has a complex set of expectations on the renderer state - for now, avoid taking
    // the merge path in those cases
    mergeable &= !recordingComplexClip();

    if (CC_UNLIKELY(renderer.getCaches().drawReorderDisabled)) {
        // TODO: elegant way to reuse batches?
        DrawBatch* b = new DrawBatch(batchId, mergeId);
        b->add(op);
        mBatches.add(b);
        return;
    }

    // find the latest batch of the new op's type, and try to merge the new op into it
    DrawBatch* targetBatch = NULL;

    // insertion point of a new batch, will hopefully be immediately after similar batch
    // (eventually, should be similar shader)
    int insertBatchIndex = mBatches.size();
    if (!mBatches.isEmpty()) {
        if (op->state.mBounds.isEmpty()) {
            // don't know the bounds for op, so add to last batch and start from scratch on next op
            DrawBatch* b = new DrawBatch(batchId, mergeId);
            b->add(op);
            mBatches.add(b);
            resetBatchingState();
#if DEBUG_DEFER
            DEFER_LOGD("Warning: Encountered op with empty bounds, resetting batches");
            op->output(2);
#endif
            return;
        }

        if (mergeable) {
            // Try to merge with any existing batch with same mergeId.
            if (mMergingBatches[batchId].get(mergeId, targetBatch)) {
                if (!((MergingDrawBatch*) targetBatch)->canMergeWith(op)) {
                    targetBatch = NULL;
                }
            }
        } else {
            // join with similar, non-merging batch
            targetBatch = (DrawBatch*)mBatchLookup[batchId];
        }

        if (targetBatch || mergeable) {
            // iterate back toward target to see if anything drawn since should overlap the new op
            // if no target, merging ops still interate to find similar batch to insert after
            for (int i = mBatches.size() - 1; i >= mEarliestBatchIndex; i--) {
                DrawBatch* overBatch = (DrawBatch*)mBatches[i];

                if (overBatch == targetBatch) break;

                // TODO: also consider shader shared between batch types
                if (batchId == overBatch->getBatchId()) {
                    insertBatchIndex = i + 1;
                    if (!targetBatch) break; // found insert position, quit
                }

                if (overBatch->intersects(op->state.mBounds)) {
                    // NOTE: it may be possible to optimize for special cases where two operations
                    // of the same batch/paint could swap order, such as with a non-mergeable
                    // (clipped) and a mergeable text operation
                    targetBatch = NULL;
#if DEBUG_DEFER
                    DEFER_LOGD("op couldn't join batch %d, was intersected by batch %d",
                            targetIndex, i);
                    op->output(2);
#endif
                    break;
                }
            }
        }
    }

    if (!targetBatch) {
        if (mergeable) {
            targetBatch = new MergingDrawBatch(batchId, mergeId);
            mMergingBatches[batchId].put(mergeId, targetBatch);
        } else {
            targetBatch = new DrawBatch(batchId, mergeId);
            mBatchLookup[batchId] = targetBatch;
            DEFER_LOGD("creating Batch %p, bid %x, at %d",
                    targetBatch, batchId, insertBatchIndex);
        }

        mBatches.insertAt(targetBatch, insertBatchIndex);
    }

    targetBatch->add(op);
}

void DeferredDisplayList::storeStateOpBarrier(OpenGLRenderer& renderer, StateOp* op) {
    DEFER_LOGD("%p adding state op barrier at pos %d", this, mBatches.size());

    renderer.storeDisplayState(op->state, getStateOpDeferFlags());
    mBatches.add(new StateOpBatch(op));
    resetBatchingState();
}

void DeferredDisplayList::storeRestoreToCountBarrier(OpenGLRenderer& renderer, StateOp* op,
        int newSaveCount) {
    DEFER_LOGD("%p adding restore to count %d barrier, pos %d",
            this, newSaveCount, mBatches.size());

    // store displayState for the restore operation, as it may be associated with a saveLayer that
    // doesn't have kClip_SaveFlag set
    renderer.storeDisplayState(op->state, getStateOpDeferFlags());
    mBatches.add(new RestoreToCountBatch(op, newSaveCount));
    resetBatchingState();
}

/////////////////////////////////////////////////////////////////////////////////
// Replay / flush
/////////////////////////////////////////////////////////////////////////////////

static status_t replayBatchList(const Vector<Batch*>& batchList,
        OpenGLRenderer& renderer, Rect& dirty) {
    status_t status = DrawGlInfo::kStatusDone;

    for (unsigned int i = 0; i < batchList.size(); i++) {
        status |= batchList[i]->replay(renderer, dirty, i);
    }
    DEFER_LOGD("--flushed, drew %d batches", batchList.size());
    return status;
}

status_t DeferredDisplayList::flush(OpenGLRenderer& renderer, Rect& dirty) {
    ATRACE_NAME("flush drawing commands");
    Caches::getInstance().fontRenderer->endPrecaching();

    status_t status = DrawGlInfo::kStatusDone;

    if (isEmpty()) return status; // nothing to flush
    renderer.restoreToCount(1);

    DEFER_LOGD("--flushing");
    renderer.eventMark("Flush");

    // save and restore (with draw modifiers) so that reordering doesn't affect final state
    DrawModifiers restoreDrawModifiers = renderer.getDrawModifiers();
    renderer.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);

    // NOTE: depth of the save stack at this point, before playback, should be reflected in
    // FLUSH_SAVE_STACK_DEPTH, so that save/restores match up correctly
    status |= replayBatchList(mBatches, renderer, dirty);

    renderer.restoreToCount(1);
    renderer.setDrawModifiers(restoreDrawModifiers);

    DEFER_LOGD("--flush complete, returning %x", status);
    clear();
    return status;
}

}; // namespace uirenderer
}; // namespace android
