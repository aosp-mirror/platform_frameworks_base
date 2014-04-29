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

#include <memory>
#include <set>

#include <cutils/compiler.h>
#include <utils/Looper.h>
#include <utils/Mutex.h>
#include <utils/Singleton.h>
#include <utils/Thread.h>

namespace android {
class DisplayEventReceiver;

namespace uirenderer {
namespace renderthread {

class DispatchFrameCallbacks;

class TaskQueue {
public:
    TaskQueue();

    RenderTask* next();
    void queue(RenderTask* task);
    RenderTask* peek();
    void remove(RenderTask* task);

private:
    RenderTask* mHead;
    RenderTask* mTail;
};

// Mimics android.view.Choreographer.FrameCallback
class IFrameCallback {
public:
    virtual void doFrame(nsecs_t frameTimeNanos) = 0;

protected:
    ~IFrameCallback() {}
};

class ANDROID_API RenderThread : public Thread, public Singleton<RenderThread> {
public:
    // RenderThread takes complete ownership of tasks that are queued
    // and will delete them after they are run
    ANDROID_API void queue(RenderTask* task);
    void queueDelayed(RenderTask* task, int delayMs);
    void remove(RenderTask* task);

    // Mimics android.view.Choreographer
    void postFrameCallback(IFrameCallback* callback);
    void removeFrameCallback(IFrameCallback* callback);

protected:
    virtual bool threadLoop();

private:
    friend class Singleton<RenderThread>;
    friend class DispatchFrameCallbacks;

    RenderThread();
    virtual ~RenderThread();

    void initializeDisplayEventReceiver();
    static int displayEventReceiverCallback(int fd, int events, void* data);
    void drainDisplayEventQueue();
    void dispatchFrameCallbacks();

    // Returns the next task to be run. If this returns NULL nextWakeup is set
    // to the time to requery for the nextTask to run. mNextWakeup is also
    // set to this time
    RenderTask* nextTask(nsecs_t* nextWakeup);

    sp<Looper> mLooper;
    Mutex mLock;

    nsecs_t mNextWakeup;
    TaskQueue mQueue;

    DisplayEventReceiver* mDisplayEventReceiver;
    bool mVsyncRequested;
    std::set<IFrameCallback*> mFrameCallbacks;
    bool mFrameCallbackTaskPending;
    DispatchFrameCallbacks* mFrameCallbackTask;
    nsecs_t mFrameTime;
};

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
#endif /* RENDERTHREAD_H_ */
