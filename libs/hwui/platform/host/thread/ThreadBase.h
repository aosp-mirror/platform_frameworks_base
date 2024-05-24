/*
 * Copyright (C) 2024 The Android Open Source Project
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

#include <utils/Thread.h>

#include <algorithm>

#include "thread/WorkQueue.h"
#include "utils/Macros.h"

namespace android::uirenderer {

class ThreadBase : public Thread {
    PREVENT_COPY_AND_ASSIGN(ThreadBase);

public:
    ThreadBase() : Thread(false), mQueue([this]() { mCondition.notify_all(); }, mLock) {}

    WorkQueue& queue() { return mQueue; }

    void requestExit() { Thread::requestExit(); }

    void start(const char* name = "ThreadBase") { Thread::run(name); }

    void join() { Thread::join(); }

    bool isRunning() const { return Thread::isRunning(); }

protected:
    void waitForWork() {
        std::unique_lock lock{mLock};
        nsecs_t nextWakeup = mQueue.nextWakeup(lock);
        std::chrono::nanoseconds duration = std::chrono::nanoseconds::max();
        if (nextWakeup < std::numeric_limits<nsecs_t>::max()) {
            int timeout = nextWakeup - WorkQueue::clock::now();
            if (timeout < 0) timeout = 0;
            duration = std::chrono::nanoseconds(timeout);
        }
        mCondition.wait_for(lock, duration);
    }

    void processQueue() { mQueue.process(); }

    virtual bool threadLoop() override {
        while (!exitPending()) {
            waitForWork();
            processQueue();
        }
        return false;
    }

private:
    WorkQueue mQueue;
    std::mutex mLock;
    std::condition_variable mCondition;
};

}  // namespace android::uirenderer

#endif  // HWUI_THREADBASE_H
