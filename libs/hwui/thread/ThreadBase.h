/*
 * Copyright (C) 2017 The Android Open Source Project
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

#ifndef HWUI_THREADBASE_H
#define HWUI_THREADBASE_H

#include "WorkQueue.h"
#include "utils/Macros.h"

#include <utils/Looper.h>
#include <utils/Thread.h>

#include <algorithm>

namespace android::uirenderer {

class ThreadBase : public Thread {
    PREVENT_COPY_AND_ASSIGN(ThreadBase);

public:
    ThreadBase()
            : Thread(false)
            , mLooper(new Looper(false))
            , mQueue([this]() { mLooper->wake(); }, mLock) {}

    WorkQueue& queue() { return mQueue; }

    void requestExit() {
        Thread::requestExit();
        mLooper->wake();
    }

    void start(const char* name = "ThreadBase") { Thread::run(name); }

    void join() { Thread::join(); }

    bool isRunning() const { return Thread::isRunning(); }

protected:
    void waitForWork() {
        nsecs_t nextWakeup;
        {
            std::unique_lock lock{mLock};
            nextWakeup = mQueue.nextWakeup(lock);
        }
        int timeout = -1;
        if (nextWakeup < std::numeric_limits<nsecs_t>::max()) {
            timeout = ns2ms(nextWakeup - WorkQueue::clock::now());
            if (timeout < 0) timeout = 0;
        }
        int result = mLooper->pollOnce(timeout);
        LOG_ALWAYS_FATAL_IF(result == Looper::POLL_ERROR, "RenderThread Looper POLL_ERROR!");
    }

    void processQueue() { mQueue.process(); }

    virtual bool threadLoop() override {
        while (!exitPending()) {
            waitForWork();
            processQueue();
        }
        return false;
    }

    sp<Looper> mLooper;

private:
    WorkQueue mQueue;
    std::mutex mLock;
};

}  // namespace android::uirenderer

#endif  // HWUI_THREADBASE_H
