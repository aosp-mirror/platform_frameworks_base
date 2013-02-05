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

class DrawOpBatch {
public:
    DrawOpBatch() {
        mOps.clear();
    }

    ~DrawOpBatch() {
        mOps.clear();
    }

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

    Vector<DrawOp*> mOps;
private:
    Rect mBounds;
};

void DeferredDisplayList::clear() {
    for (int i = 0; i < kOpBatch_Count; i++) {
        mBatchIndices[i] = -1;
    }
    for (unsigned int i = 0; i < mBatches.size(); i++) {
        delete mBatches[i];
    }
    mBatches.clear();
}

void DeferredDisplayList::add(DrawOp* op, bool disallowReorder) {
    if (CC_UNLIKELY(disallowReorder)) {
        if (!mBatches.isEmpty()) {
            mBatches[0]->add(op);
            return;
        }
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

status_t DeferredDisplayList::flush(OpenGLRenderer& renderer, Rect& dirty, int32_t flags,
        uint32_t level) {
    ATRACE_CALL();
    status_t status = DrawGlInfo::kStatusDone;

    if (isEmpty()) return status; // nothing to flush

    DEFER_LOGD("--flushing");
    DrawModifiers restoreDrawModifiers = renderer.getDrawModifiers();
    int restoreTo = renderer.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);
    int opCount = 0;
    for (unsigned int i = 0; i < mBatches.size(); i++) {
        DrawOpBatch* batch = mBatches[i];
        for (unsigned int j = 0; j < batch->mOps.size(); j++) {
            DrawOp* op = batch->mOps[j];

            renderer.restoreDisplayState(op->state);

#if DEBUG_DEFER
            op->output(2);
#endif
            status |= op->applyDraw(renderer, dirty, level,
                    op->state.mMultipliedAlpha >= 0, op->state.mMultipliedAlpha);
            opCount++;
        }
    }

    DEFER_LOGD("--flushed, drew %d batches (total %d ops)", mBatches.size(), opCount);
    renderer.restoreToCount(restoreTo);
    renderer.setDrawModifiers(restoreDrawModifiers);
    clear();
    return status;
}

}; // namespace uirenderer
}; // namespace android
