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
#ifndef DRAWFRAMETASK_H
#define DRAWFRAMETASK_H

#include <optional>
#include <vector>

#include <performance_hint_private.h>
#include <utils/Condition.h>
#include <utils/Mutex.h>
#include <utils/StrongPointer.h>

#include "RenderTask.h"

#include "../FrameInfo.h"
#include "../Rect.h"
#include "../TreeInfo.h"

namespace android {
namespace uirenderer {

class DeferredLayerUpdater;
class RenderNode;

namespace renderthread {

class CanvasContext;
class RenderThread;

namespace SyncResult {
enum {
    OK = 0,
    UIRedrawRequired = 1 << 0,
    LostSurfaceRewardIfFound = 1 << 1,
    ContextIsStopped = 1 << 2,
    FrameDropped = 1 << 3,
};
}

/*
 * This is a special Super Task. It is re-used multiple times by RenderProxy,
 * and contains state (such as layer updaters & new DisplayLists) that is
 * tracked across many frames not just a single frame.
 * It is the sync-state task, and will kick off the post-sync draw
 */
class DrawFrameTask {
public:
    DrawFrameTask();
    virtual ~DrawFrameTask();

    void setContext(RenderThread* thread, CanvasContext* context, RenderNode* targetNode,
                    int32_t uiThreadId, int32_t renderThreadId);
    void setContentDrawBounds(int left, int top, int right, int bottom) {
        mContentDrawBounds.set(left, top, right, bottom);
    }

    void pushLayerUpdate(DeferredLayerUpdater* layer);
    void removeLayerUpdate(DeferredLayerUpdater* layer);

    int drawFrame();

    int64_t* frameInfo() { return mFrameInfo; }

    void run();

    void setFrameCallback(std::function<void(int64_t)>&& callback) {
        mFrameCallback = std::move(callback);
    }

    void setFrameCompleteCallback(std::function<void(int64_t)>&& callback) {
        mFrameCompleteCallback = std::move(callback);
    }

private:
    class HintSessionWrapper {
    public:
        HintSessionWrapper(int32_t uiThreadId, int32_t renderThreadId);
        ~HintSessionWrapper();

        void updateTargetWorkDuration(long targetDurationNanos);
        void reportActualWorkDuration(long actualDurationNanos);

    private:
        APerformanceHintSession* mHintSession = nullptr;
    };

    void postAndWait();
    bool syncFrameState(TreeInfo& info);
    void unblockUiThread();

    Mutex mLock;
    Condition mSignal;

    RenderThread* mRenderThread;
    CanvasContext* mContext;
    RenderNode* mTargetNode = nullptr;
    int32_t mUiThreadId = -1;
    int32_t mRenderThreadId = -1;
    Rect mContentDrawBounds;

    /*********************************************
     *  Single frame data
     *********************************************/
    std::vector<sp<DeferredLayerUpdater> > mLayers;

    int mSyncResult;
    int64_t mSyncQueued;

    int64_t mFrameInfo[UI_THREAD_FRAME_INFO_SIZE];

    std::function<void(int64_t)> mFrameCallback;
    std::function<void(int64_t)> mFrameCompleteCallback;

    nsecs_t mLastDequeueBufferDuration = 0;
    nsecs_t mLastTargetWorkDuration = 0;
    std::optional<HintSessionWrapper> mHintSessionWrapper;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* DRAWFRAMETASK_H */
