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

#include "RenderThread.h"

#if defined(HAVE_PTHREADS)
#include <sys/resource.h>
#endif
#include <gui/DisplayEventReceiver.h>
#include <utils/Log.h>

#include "../RenderState.h"
#include "CanvasContext.h"
#include "EglManager.h"
#include "RenderProxy.h"

namespace android {
using namespace uirenderer::renderthread;
ANDROID_SINGLETON_STATIC_INSTANCE(RenderThread);

namespace uirenderer {
namespace renderthread {

// Number of events to read at a time from the DisplayEventReceiver pipe.
// The value should be large enough that we can quickly drain the pipe
// using just a few large reads.
static const size_t EVENT_BUFFER_SIZE = 100;

// Slight delay to give the UI time to push us a new frame before we replay
static const nsecs_t DISPATCH_FRAME_CALLBACKS_DELAY = milliseconds_to_nanoseconds(4);

TaskQueue::TaskQueue() : mHead(0), mTail(0) {}

RenderTask* TaskQueue::next() {
    RenderTask* ret = mHead;
    if (ret) {
        mHead = ret->mNext;
        if (!mHead) {
            mTail = 0;
        }
        ret->mNext = 0;
    }
    return ret;
}

RenderTask* TaskQueue::peek() {
    return mHead;
}

void TaskQueue::queue(RenderTask* task) {
    // Since the RenderTask itself forms the linked list it is not allowed
    // to have the same task queued twice
    LOG_ALWAYS_FATAL_IF(task->mNext || mTail == task, "Task is already in the queue!");
    if (mTail) {
        // Fast path if we can just append
        if (mTail->mRunAt <= task->mRunAt) {
            mTail->mNext = task;
            mTail = task;
        } else {
            // Need to find the proper insertion point
            RenderTask* previous = 0;
            RenderTask* next = mHead;
            while (next && next->mRunAt <= task->mRunAt) {
                previous = next;
                next = next->mNext;
            }
            if (!previous) {
                task->mNext = mHead;
                mHead = task;
            } else {
                previous->mNext = task;
                if (next) {
                    task->mNext = next;
                } else {
                    mTail = task;
                }
            }
        }
    } else {
        mTail = mHead = task;
    }
}

void TaskQueue::queueAtFront(RenderTask* task) {
    if (mTail) {
        task->mNext = mHead;
        mHead = task;
    } else {
        mTail = mHead = task;
    }
}

void TaskQueue::remove(RenderTask* task) {
    // TaskQueue is strict here to enforce that users are keeping track of
    // their RenderTasks due to how their memory is managed
    LOG_ALWAYS_FATAL_IF(!task->mNext && mTail != task,
            "Cannot remove a task that isn't in the queue!");

    // If task is the head we can just call next() to pop it off
    // Otherwise we need to scan through to find the task before it
    if (peek() == task) {
        next();
    } else {
        RenderTask* previous = mHead;
        while (previous->mNext != task) {
            previous = previous->mNext;
        }
        previous->mNext = task->mNext;
        if (mTail == task) {
            mTail = previous;
        }
    }
}

class DispatchFrameCallbacks : public RenderTask {
private:
    RenderThread* mRenderThread;
public:
    DispatchFrameCallbacks(RenderThread* rt) : mRenderThread(rt) {}

    virtual void run() {
        mRenderThread->dispatchFrameCallbacks();
    }
};

RenderThread::RenderThread() : Thread(true), Singleton<RenderThread>()
        , mNextWakeup(LLONG_MAX)
        , mDisplayEventReceiver(0)
        , mVsyncRequested(false)
        , mFrameCallbackTaskPending(false)
        , mFrameCallbackTask(0)
        , mRenderState(NULL)
        , mEglManager(NULL) {
    mFrameCallbackTask = new DispatchFrameCallbacks(this);
    mLooper = new Looper(false);
    run("RenderThread");
}

RenderThread::~RenderThread() {
    LOG_ALWAYS_FATAL("Can't destroy the render thread");
}

void RenderThread::initializeDisplayEventReceiver() {
    LOG_ALWAYS_FATAL_IF(mDisplayEventReceiver, "Initializing a second DisplayEventReceiver?");
    mDisplayEventReceiver = new DisplayEventReceiver();
    status_t status = mDisplayEventReceiver->initCheck();
    LOG_ALWAYS_FATAL_IF(status != NO_ERROR, "Initialization of DisplayEventReceiver "
            "failed with status: %d", status);

    // Register the FD
    mLooper->addFd(mDisplayEventReceiver->getFd(), 0,
            Looper::EVENT_INPUT, RenderThread::displayEventReceiverCallback, this);
}

void RenderThread::initThreadLocals() {
    initializeDisplayEventReceiver();
    mEglManager = new EglManager(*this);
    mRenderState = new RenderState(*this);
}

int RenderThread::displayEventReceiverCallback(int fd, int events, void* data) {
    if (events & (Looper::EVENT_ERROR | Looper::EVENT_HANGUP)) {
        ALOGE("Display event receiver pipe was closed or an error occurred.  "
                "events=0x%x", events);
        return 0; // remove the callback
    }

    if (!(events & Looper::EVENT_INPUT)) {
        ALOGW("Received spurious callback for unhandled poll event.  "
                "events=0x%x", events);
        return 1; // keep the callback
    }

    reinterpret_cast<RenderThread*>(data)->drainDisplayEventQueue();

    return 1; // keep the callback
}

static nsecs_t latestVsyncEvent(DisplayEventReceiver* receiver) {
    DisplayEventReceiver::Event buf[EVENT_BUFFER_SIZE];
    nsecs_t latest = 0;
    ssize_t n;
    while ((n = receiver->getEvents(buf, EVENT_BUFFER_SIZE)) > 0) {
        for (ssize_t i = 0; i < n; i++) {
            const DisplayEventReceiver::Event& ev = buf[i];
            switch (ev.header.type) {
            case DisplayEventReceiver::DISPLAY_EVENT_VSYNC:
                latest = ev.header.timestamp;
                break;
            }
        }
    }
    if (n < 0) {
        ALOGW("Failed to get events from display event receiver, status=%d", status_t(n));
    }
    return latest;
}

void RenderThread::drainDisplayEventQueue() {
    ATRACE_CALL();
    nsecs_t vsyncEvent = latestVsyncEvent(mDisplayEventReceiver);
    if (vsyncEvent > 0) {
        mVsyncRequested = false;
        if (mTimeLord.vsyncReceived(vsyncEvent) && !mFrameCallbackTaskPending) {
            ATRACE_NAME("queue mFrameCallbackTask");
            mFrameCallbackTaskPending = true;
            nsecs_t runAt = (vsyncEvent + DISPATCH_FRAME_CALLBACKS_DELAY);
            queueAt(mFrameCallbackTask, runAt);
        }
    }
}

void RenderThread::dispatchFrameCallbacks() {
    ATRACE_CALL();
    mFrameCallbackTaskPending = false;

    std::set<IFrameCallback*> callbacks;
    mFrameCallbacks.swap(callbacks);

    if (callbacks.size()) {
        // Assume one of them will probably animate again so preemptively
        // request the next vsync in case it occurs mid-frame
        requestVsync();
        for (std::set<IFrameCallback*>::iterator it = callbacks.begin(); it != callbacks.end(); it++) {
            (*it)->doFrame();
        }
    }
}

void RenderThread::requestVsync() {
    if (!mVsyncRequested) {
        mVsyncRequested = true;
        status_t status = mDisplayEventReceiver->requestNextVsync();
        LOG_ALWAYS_FATAL_IF(status != NO_ERROR,
                "requestNextVsync failed with status: %d", status);
    }
}

bool RenderThread::threadLoop() {
#if defined(HAVE_PTHREADS)
    setpriority(PRIO_PROCESS, 0, PRIORITY_DISPLAY);
#endif
    initThreadLocals();

    int timeoutMillis = -1;
    for (;;) {
        int result = mLooper->pollOnce(timeoutMillis);
        LOG_ALWAYS_FATAL_IF(result == Looper::POLL_ERROR,
                "RenderThread Looper POLL_ERROR!");

        nsecs_t nextWakeup;
        // Process our queue, if we have anything
        while (RenderTask* task = nextTask(&nextWakeup)) {
            task->run();
            // task may have deleted itself, do not reference it again
        }
        if (nextWakeup == LLONG_MAX) {
            timeoutMillis = -1;
        } else {
            nsecs_t timeoutNanos = nextWakeup - systemTime(SYSTEM_TIME_MONOTONIC);
            timeoutMillis = nanoseconds_to_milliseconds(timeoutNanos);
            if (timeoutMillis < 0) {
                timeoutMillis = 0;
            }
        }

        if (mPendingRegistrationFrameCallbacks.size() && !mFrameCallbackTaskPending) {
            drainDisplayEventQueue();
            mFrameCallbacks.insert(
                    mPendingRegistrationFrameCallbacks.begin(), mPendingRegistrationFrameCallbacks.end());
            mPendingRegistrationFrameCallbacks.clear();
            requestVsync();
        }

        if (!mFrameCallbackTaskPending && !mVsyncRequested && mFrameCallbacks.size()) {
            // TODO: Clean this up. This is working around an issue where a combination
            // of bad timing and slow drawing can result in dropping a stale vsync
            // on the floor (correct!) but fails to schedule to listen for the
            // next vsync (oops), so none of the callbacks are run.
            requestVsync();
        }
    }

    return false;
}

void RenderThread::queue(RenderTask* task) {
    AutoMutex _lock(mLock);
    mQueue.queue(task);
    if (mNextWakeup && task->mRunAt < mNextWakeup) {
        mNextWakeup = 0;
        mLooper->wake();
    }
}

void RenderThread::queueAtFront(RenderTask* task) {
    AutoMutex _lock(mLock);
    mQueue.queueAtFront(task);
    mLooper->wake();
}

void RenderThread::queueAt(RenderTask* task, nsecs_t runAtNs) {
    task->mRunAt = runAtNs;
    queue(task);
}

void RenderThread::remove(RenderTask* task) {
    AutoMutex _lock(mLock);
    mQueue.remove(task);
}

void RenderThread::postFrameCallback(IFrameCallback* callback) {
    mPendingRegistrationFrameCallbacks.insert(callback);
}

bool RenderThread::removeFrameCallback(IFrameCallback* callback) {
    size_t erased;
    erased = mFrameCallbacks.erase(callback);
    erased |= mPendingRegistrationFrameCallbacks.erase(callback);
    return erased;
}

void RenderThread::pushBackFrameCallback(IFrameCallback* callback) {
    if (mFrameCallbacks.erase(callback)) {
        mPendingRegistrationFrameCallbacks.insert(callback);
    }
}

RenderTask* RenderThread::nextTask(nsecs_t* nextWakeup) {
    AutoMutex _lock(mLock);
    RenderTask* next = mQueue.peek();
    if (!next) {
        mNextWakeup = LLONG_MAX;
    } else {
        mNextWakeup = next->mRunAt;
        // Most tasks won't be delayed, so avoid unnecessary systemTime() calls
        if (next->mRunAt <= 0 || next->mRunAt <= systemTime(SYSTEM_TIME_MONOTONIC)) {
            next = mQueue.next();
        } else {
            next = 0;
        }
    }
    if (nextWakeup) {
        *nextWakeup = mNextWakeup;
    }
    return next;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
