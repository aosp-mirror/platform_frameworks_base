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

#include <utils/Log.h>

namespace android {
using namespace uirenderer::renderthread;
ANDROID_SINGLETON_STATIC_INSTANCE(RenderThread);

namespace uirenderer {
namespace renderthread {

RenderThread::RenderThread() : Thread(true), Singleton<RenderThread>()
        , mQueueHead(0), mQueueTail(0) {
    mLooper = new Looper(false);
    run("RenderThread");
}

RenderThread::~RenderThread() {
}

bool RenderThread::threadLoop() {
    for (;;) {
        int result = mLooper->pollAll(-1);
        if (result == Looper::POLL_ERROR) {
            // TODO Something?
            break;
        }
        // Process our queue, if we have anything
        while (RenderTask* task = nextTask()) {
            task->run();
            delete task;
        }
    }

    return false;
}

void RenderThread::queue(RenderTask* task) {
    AutoMutex _lock(mLock);
    if (mQueueTail) {
        mQueueTail->mNext = task;
    } else {
        mQueueHead = task;
    }
    mQueueTail = task;
    if (mQueueHead == task) {
        // Only wake if this is the first task
        mLooper->wake();
    }
}

RenderTask* RenderThread::nextTask() {
    AutoMutex _lock(mLock);
    RenderTask* ret = mQueueHead;
    if (ret) {
        if (mQueueTail == mQueueHead) {
            mQueueTail = mQueueHead = 0;
        } else {
            mQueueHead = ret->mNext;
        }
        ret->mNext = 0;
    }
    return ret;
}

} /* namespace renderthread */
} /* namespace uirenderer */
} /* namespace android */
