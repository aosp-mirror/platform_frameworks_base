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

#include "Debug.h"
#include "DisplayListOp.h"
#include "OpenGLRenderer.h"

#if DEBUG_DEFER
    #define DEFER_LOGD(...) ALOGD(__VA_ARGS__)
#else
    #define DEFER_LOGD(...)
#endif

namespace android {
namespace uirenderer {

/////////////////////////////////////////////////////////////////////////////////
// Operation Batches
/////////////////////////////////////////////////////////////////////////////////

class DrawOpBatch {
public:
    DrawOpBatch() { mOps.clear(); }

    virtual ~DrawOpBatch() { mOps.clear(); }

    void add(DrawOp* op) {
        // NOTE: ignore empty bounds special case, since we don't merge across those ops
        mBounds.unionWith(op->state.mBounds);
        mOps.add(op);
    }

    virtual bool intersects(Rect& rect) {
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

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty) {
        DEFER_LOGD("replaying draw batch %p", this);

        status_t status = DrawGlInfo::kStatusDone;
        DisplayListLogBuffer& logBuffer = DisplayListLogBuffer::getInstance();
        for (unsigned int i = 0; i < mOps.size(); i++) {
            DrawOp* op = mOps[i];

            renderer.restoreDisplayState(op->state, kStateDeferFlag_Draw);

#if DEBUG_DISPLAY_LIST_OPS_AS_EVENTS
            renderer.eventMark(op->name());
#endif
            status |= op->applyDraw(renderer, dirty, 0);
            logBuffer.writeCommand(0, op->name());
        }
        return status;
    }

    inline int count() const { return mOps.size(); }
private:
    Vector<DrawOp*> mOps;
    Rect mBounds;
};

class StateOpBatch : public DrawOpBatch {
public:
    // creates a single operation batch
    StateOpBatch(StateOp* op) : mOp(op) {}

    bool intersects(Rect& rect) {
        // if something checks for intersection, it's trying to go backwards across a state op,
        // something not currently supported - state ops are always barriers
        CRASH();
        return false;
    }

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty) {
        DEFER_LOGD("replaying state op batch %p", this);
        renderer.restoreDisplayState(mOp->state, 0);

        // use invalid save count because it won't be used at flush time - RestoreToCountOp is the
        // only one to use it, and we don't use that class at flush time, instead calling
        // renderer.restoreToCount directly
        int saveCount = -1;
        mOp->applyState(renderer, saveCount);
        return DrawGlInfo::kStatusDone;
    }

private:
    StateOp* mOp;
};

class RestoreToCountBatch : public DrawOpBatch {
public:
    RestoreToCountBatch(int restoreCount) : mRestoreCount(restoreCount) {}

    bool intersects(Rect& rect) {
        // if something checks for intersection, it's trying to go backwards across a state op,
        // something not currently supported - state ops are always barriers
        CRASH();
        return false;
    }

    virtual status_t replay(OpenGLRenderer& renderer, Rect& dirty) {
        DEFER_LOGD("batch %p restoring to count %d", this, mRestoreCount);
        renderer.restoreToCount(mRestoreCount);
        return DrawGlInfo::kStatusDone;
    }

private:
    /*
     * The count used here represents the flush() time saveCount. This is as opposed to the
     * DisplayList record time, or defer() time values (which are RestoreToCountOp's mCount, and
     * (saveCount + mCount) respectively). Since the count is different from the original
     * RestoreToCountOp, we don't store a pointer to the op, as elsewhere.
     */
    const int mRestoreCount;
};

/////////////////////////////////////////////////////////////////////////////////
// DeferredDisplayList
/////////////////////////////////////////////////////////////////////////////////

void DeferredDisplayList::resetBatchingState() {
    for (int i = 0; i < kOpBatch_Count; i++) {
        mBatchIndices[i] = -1;
    }
}

void DeferredDisplayList::clear() {
    resetBatchingState();
    mComplexClipStackStart = -1;

    for (unsigned int i = 0; i < mBatches.size(); i++) {
        delete mBatches[i];
    }
    mBatches.clear();
    mSaveStack.clear();
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
void DeferredDisplayList::addRestoreToCount(OpenGLRenderer& renderer, int newSaveCount) {
    DEFER_LOGD("%p addRestoreToCount %d", this, newSaveCount);

    if (recordingComplexClip() && newSaveCount <= mComplexClipStackStart) {
        mComplexClipStackStart = -1;
        resetBatchingState();
    }

    if (mSaveStack.isEmpty() || newSaveCount > mSaveStack.top()) {
        return;
    }

    while (!mSaveStack.isEmpty() && mSaveStack.top() >= newSaveCount) mSaveStack.pop();

    storeRestoreToCountBarrier(mSaveStack.size() + 1);
}

void DeferredDisplayList::addDrawOp(OpenGLRenderer& renderer, DrawOp* op) {
    if (renderer.storeDisplayState(op->state, getDrawOpDeferFlags())) {
        return; // quick rejected
    }

    op->onDrawOpDeferred(renderer);

    if (CC_UNLIKELY(renderer.getCaches().drawReorderDisabled)) {
        // TODO: elegant way to reuse batches?
        DrawOpBatch* b = new DrawOpBatch();
        b->add(op);
        mBatches.add(b);
        return;
    }

    // disallowReorder isn't set, so find the latest batch of the new op's type, and try to merge
    // the new op into it
    DrawOpBatch* targetBatch = NULL;
    int batchId = op->getBatchId();

    if (!mBatches.isEmpty()) {
        if (op->state.mBounds.isEmpty()) {
            // don't know the bounds for op, so add to last batch and start from scratch on next op
            mBatches.top()->add(op);
            for (int i = 0; i < kOpBatch_Count; i++) {
                mBatchIndices[i] = -1;
            }
#if DEBUG_DEFER
            DEFER_LOGD("Warning: Encountered op with empty bounds, resetting batches");
            op->output(2);
#endif
            return;
        }

        if (batchId >= 0 && mBatchIndices[batchId] != -1) {
            int targetIndex = mBatchIndices[batchId];
            targetBatch = mBatches[targetIndex];
            // iterate back toward target to see if anything drawn since should overlap the new op
            for (int i = mBatches.size() - 1; i > targetIndex; i--) {
                DrawOpBatch* overBatch = mBatches[i];
                if (overBatch->intersects(op->state.mBounds)) {
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
        targetBatch = new DrawOpBatch();
        mBatches.add(targetBatch);
        if (batchId >= 0) {
            mBatchIndices[batchId] = mBatches.size() - 1;
        }
    }
    targetBatch->add(op);
}

void DeferredDisplayList::storeStateOpBarrier(OpenGLRenderer& renderer, StateOp* op) {
    DEFER_LOGD("%p adding state op barrier at pos %d", this, mBatches.size());

    renderer.storeDisplayState(op->state, getStateOpDeferFlags());
    mBatches.add(new StateOpBatch(op));
    resetBatchingState();
}

void DeferredDisplayList::storeRestoreToCountBarrier(int newSaveCount) {
    DEFER_LOGD("%p adding restore to count %d barrier, pos %d",
            this, newSaveCount, mBatches.size());

    mBatches.add(new RestoreToCountBatch(newSaveCount));
    resetBatchingState();
}

/////////////////////////////////////////////////////////////////////////////////
// Replay / flush
/////////////////////////////////////////////////////////////////////////////////

static status_t replayBatchList(Vector<DrawOpBatch*>& batchList,
        OpenGLRenderer& renderer, Rect& dirty) {
    status_t status = DrawGlInfo::kStatusDone;

    int opCount = 0;
    for (unsigned int i = 0; i < batchList.size(); i++) {
        status |= batchList[i]->replay(renderer, dirty);
        opCount += batchList[i]->count();
    }
    DEFER_LOGD("--flushed, drew %d batches (total %d ops)", batchList.size(), opCount);
    return status;
}

status_t DeferredDisplayList::flush(OpenGLRenderer& renderer, Rect& dirty) {
    ATRACE_NAME("flush drawing commands");
    status_t status = DrawGlInfo::kStatusDone;

    if (isEmpty()) return status; // nothing to flush

    DEFER_LOGD("--flushing");
    renderer.eventMark("Flush");

    DrawModifiers restoreDrawModifiers = renderer.getDrawModifiers();
    renderer.restoreToCount(1);
    status |= replayBatchList(mBatches, renderer, dirty);
    renderer.setDrawModifiers(restoreDrawModifiers);

    DEFER_LOGD("--flush complete, returning %x", status);

    clear();
    return status;
}

}; // namespace uirenderer
}; // namespace android
