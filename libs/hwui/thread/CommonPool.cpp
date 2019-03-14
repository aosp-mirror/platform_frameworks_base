/*
 * Copyright (C) 2019 The Android Open Source Project
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

#include "CommonPool.h"

#include <sys/resource.h>
#include <utils/Trace.h>
#include "renderthread/RenderThread.h"

#include <array>

namespace android {
namespace uirenderer {

CommonPool::CommonPool() {
    ATRACE_CALL();

    CommonPool* pool = this;
    // Create 2 workers
    for (int i = 0; i < THREAD_COUNT; i++) {
        std::thread worker([pool, i] {
            {
                std::array<char, 20> name{"hwuiTask"};
                snprintf(name.data(), name.size(), "hwuiTask%d", i);
                auto self = pthread_self();
                pthread_setname_np(self, name.data());
                setpriority(PRIO_PROCESS, 0, PRIORITY_FOREGROUND);
                auto startHook = renderthread::RenderThread::getOnStartHook();
                if (startHook) {
                    startHook(name.data());
                }
            }
            pool->workerLoop();
        });
        worker.detach();
    }
}

void CommonPool::post(Task&& task) {
    static CommonPool pool;
    pool.enqueue(std::move(task));
}

void CommonPool::enqueue(Task&& task) {
    std::unique_lock lock(mLock);
    while (!mWorkQueue.hasSpace()) {
        lock.unlock();
        usleep(100);
        lock.lock();
    }
    mWorkQueue.push(std::move(task));
    if (mWaitingThreads == THREAD_COUNT || (mWaitingThreads > 0 && mWorkQueue.size() > 1)) {
        mCondition.notify_one();
    }
}

void CommonPool::workerLoop() {
    std::unique_lock lock(mLock);
    while (true) {
        if (!mWorkQueue.hasWork()) {
            mWaitingThreads++;
            mCondition.wait(lock);
            mWaitingThreads--;
        }
        // Need to double-check that work is still available now that we have the lock
        // It may have already been grabbed by a different thread
        while (mWorkQueue.hasWork()) {
            auto work = mWorkQueue.pop();
            lock.unlock();
            work();
            lock.lock();
        }
    }
}

}  // namespace uirenderer
}  // namespace android