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

#define LOG_TAG "RenderThread"

#include "RenderThread.h"

#include "CanvasContext.h"
#include "RenderProxy.h"
#include <utils/Log.h>

namespace android {
using namespace uirenderer::renderthread;
ANDROID_SINGLETON_STATIC_INSTANCE(RenderThread);

namespace uirenderer {
namespace renderthread {

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

RenderThread::RenderThread() : Thread(true), Singleton<RenderThread>()
        , mNextWakeup(LLONG_MAX) {
    mLooper = new Looper(false);
    run("RenderThread");
}

RenderThread::~RenderThread() {
}

bool RenderThread::threadLoop() {
    int timeoutMillis = -1;
    for (;;) {
        int result = mLooper->pollAll(timeoutMillis);
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
            timeoutMillis = nextWakeup - systemTime(SYSTEM_TIME_MONOTONIC);
            if (timeoutMillis < 0) {
                timeoutMillis = 0;
            }
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

void RenderThread::queueDelayed(RenderTask* task, int delayMs) {
    nsecs_t now = systemTime(SYSTEM_TIME_MONOTONIC);
    task->mRunAt = now + delayMs;
    queue(task);
}

void RenderThread::remove(RenderTask* task) {
    AutoMutex _lock(mLock);
    mQueue.remove(task);
}

RenderTask* RenderThread::nextTask(nsecs_t* nextWakeup) {
    AutoMutex _lock(mLock);
    RenderTask* next = mQueue.peek();
    if (!next) {
        mNextWakeup = LLONG_MAX;
    } else {
        // Most tasks won't be delayed, so avoid unnecessary systemTime() calls
        if (next->mRunAt <= 0 || next->mRunAt <= systemTime(SYSTEM_TIME_MONOTONIC)) {
            next = mQueue.next();
        }
        mNextWakeup = next->mRunAt;
    }
    if (nextWakeup) {
        *nextWakeup = mNextWakeup;
    }
    return next;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
