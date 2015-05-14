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

#include <vector>

#include <utils/Condition.h>
#include <utils/Mutex.h>
#include <utils/StrongPointer.h>

#include "RenderTask.h"

#include "../Rect.h"
#include "../TreeInfo.h"

namespace android {
namespace uirenderer {

class DeferredLayerUpdater;
class DisplayListData;
class RenderNode;

namespace renderthread {

class CanvasContext;
class RenderThread;

enum SyncResult {
    kSync_OK = 0,
    kSync_UIRedrawRequired = 1 << 0,
    kSync_LostSurfaceRewardIfFound = 1 << 1,
};

/*
 * This is a special Super Task. It is re-used multiple times by RenderProxy,
 * and contains state (such as layer updaters & new DisplayListDatas) that is
 * tracked across many frames not just a single frame.
 * It is the sync-state task, and will kick off the post-sync draw
 */
class DrawFrameTask : public RenderTask {
public:
    DrawFrameTask();
    virtual ~DrawFrameTask();

    void setContext(RenderThread* thread, CanvasContext* context);

    void pushLayerUpdate(DeferredLayerUpdater* layer);
    void removeLayerUpdate(DeferredLayerUpdater* layer);

    void setDensity(float density) { mDensity = density; }
    int drawFrame(nsecs_t frameTimeNanos, nsecs_t recordDurationNanos);

    virtual void run();

private:
    void postAndWait();
    bool syncFrameState(TreeInfo& info);
    void unblockUiThread();

    Mutex mLock;
    Condition mSignal;

    RenderThread* mRenderThread;
    CanvasContext* mContext;

    /*********************************************
     *  Single frame data
     *********************************************/
    nsecs_t mFrameTimeNanos;
    nsecs_t mRecordDurationNanos;
    float mDensity;
    std::vector< sp<DeferredLayerUpdater> > mLayers;

    int mSyncResult;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */

#endif /* DRAWFRAMETASK_H */
