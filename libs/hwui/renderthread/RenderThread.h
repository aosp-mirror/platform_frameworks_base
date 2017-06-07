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

#ifndef RENDERTHREAD_H_
#define RENDERTHREAD_H_

#include "RenderTask.h"

#include "../JankTracker.h"
#include "CacheManager.h"
#include "TimeLord.h"

#include <GrContext.h>
#include <cutils/compiler.h>
#include <SkBitmap.h>
#include <ui/DisplayInfo.h>
#include <utils/Looper.h>
#include <utils/Thread.h>

#include <memory>
#include <set>

namespace android {

class Bitmap;
class DisplayEventReceiver;

namespace uirenderer {

class Readback;
class RenderState;
class TestUtils;

namespace renderthread {

class CanvasContext;
class DispatchFrameCallbacks;
class EglManager;
class RenderProxy;
class VulkanManager;

class TaskQueue {
public:
    TaskQueue();

    RenderTask* next();
    void queue(RenderTask* task);
    void queueAtFront(RenderTask* task);
    RenderTask* peek();
    void remove(RenderTask* task);

private:
    RenderTask* mHead;
    RenderTask* mTail;
};

// Mimics android.view.Choreographer.FrameCallback
class IFrameCallback {
public:
    virtual void doFrame() = 0;

protected:
    ~IFrameCallback() {}
};

class ANDROID_API RenderThread : public Thread {
    PREVENT_COPY_AND_ASSIGN(RenderThread);
public:
    // RenderThread takes complete ownership of tasks that are queued
    // and will delete them after they are run
    ANDROID_API void queue(RenderTask* task);
    ANDROID_API void queueAndWait(RenderTask* task);
    ANDROID_API void queueAtFront(RenderTask* task);
    void queueAt(RenderTask* task, nsecs_t runAtNs);
    void remove(RenderTask* task);

    // Mimics android.view.Choreographer
    void postFrameCallback(IFrameCallback* callback);
    bool removeFrameCallback(IFrameCallback* callback);
    // If the callback is currently registered, it will be pushed back until
    // the next vsync. If it is not currently registered this does nothing.
    void pushBackFrameCallback(IFrameCallback* callback);

    TimeLord& timeLord() { return mTimeLord; }
    RenderState& renderState() const { return *mRenderState; }
    EglManager& eglManager() const { return *mEglManager; }
    JankTracker& jankTracker() { return *mJankTracker; }
    Readback& readback();

    const DisplayInfo& mainDisplayInfo() { return mDisplayInfo; }

    GrContext* getGrContext() const { return mGrContext.get(); }
    void setGrContext(GrContext* cxt);

    CacheManager& cacheManager() { return *mCacheManager; }
    VulkanManager& vulkanManager() { return *mVkManager; }

    sk_sp<Bitmap> allocateHardwareBitmap(SkBitmap& skBitmap);
    void dumpGraphicsMemory(int fd);

protected:
    virtual bool threadLoop() override;

private:
    friend class DispatchFrameCallbacks;
    friend class RenderProxy;
    friend class android::uirenderer::TestUtils;

    RenderThread();
    virtual ~RenderThread();

    static bool hasInstance();
    static RenderThread& getInstance();

    void initThreadLocals();
    void initializeDisplayEventReceiver();
    static int displayEventReceiverCallback(int fd, int events, void* data);
    void drainDisplayEventQueue();
    void dispatchFrameCallbacks();
    void requestVsync();

    // Returns the next task to be run. If this returns NULL nextWakeup is set
    // to the time to requery for the nextTask to run. mNextWakeup is also
    // set to this time
    RenderTask* nextTask(nsecs_t* nextWakeup);

    sp<Looper> mLooper;
    Mutex mLock;

    nsecs_t mNextWakeup;
    TaskQueue mQueue;

    DisplayInfo mDisplayInfo;

    DisplayEventReceiver* mDisplayEventReceiver;
    bool mVsyncRequested;
    std::set<IFrameCallback*> mFrameCallbacks;
    // We defer the actual registration of these callbacks until
    // both mQueue *and* mDisplayEventReceiver have been drained off all
    // immediate events. This makes sure that we catch the next vsync, not
    // the previous one
    std::set<IFrameCallback*> mPendingRegistrationFrameCallbacks;
    bool mFrameCallbackTaskPending;
    DispatchFrameCallbacks* mFrameCallbackTask;

    TimeLord mTimeLord;
    RenderState* mRenderState;
    EglManager* mEglManager;

    JankTracker* mJankTracker = nullptr;
    Readback* mReadback = nullptr;

    sk_sp<GrContext> mGrContext;
    CacheManager* mCacheManager;
    VulkanManager* mVkManager;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* RENDERTHREAD_H_ */
