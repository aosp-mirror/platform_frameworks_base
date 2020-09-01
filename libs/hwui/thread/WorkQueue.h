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

#ifndef HWUI_WORKQUEUE_H
#define HWUI_WORKQUEUE_H

#include "utils/Macros.h"

#include <log/log.h>
#include <utils/Timers.h>

#include <condition_variable>
#include <functional>
#include <future>
#include <mutex>
#include <vector>

namespace android::uirenderer {

struct MonotonicClock {
    static nsecs_t now() { return systemTime(SYSTEM_TIME_MONOTONIC); }
};

class WorkQueue {
    PREVENT_COPY_AND_ASSIGN(WorkQueue);

public:
    using clock = MonotonicClock;

private:
    struct WorkItem {
        WorkItem() = delete;
        WorkItem(const WorkItem& other) = delete;
        WorkItem& operator=(const WorkItem& other) = delete;
        WorkItem(WorkItem&& other) = default;
        WorkItem& operator=(WorkItem&& other) = default;

        WorkItem(nsecs_t runAt, std::function<void()>&& work)
                : runAt(runAt), work(std::move(work)) {}

        nsecs_t runAt;
        std::function<void()> work;
    };

public:
    WorkQueue(std::function<void()>&& wakeFunc, std::mutex& lock)
            : mWakeFunc(move(wakeFunc)), mLock(lock) {}

    void process() {
        auto now = clock::now();
        std::vector<WorkItem> toProcess;
        {
            std::unique_lock _lock{mLock};
            if (mWorkQueue.empty()) return;
            toProcess = std::move(mWorkQueue);
            auto moveBack = find_if(std::begin(toProcess), std::end(toProcess),
                                    [&now](WorkItem& item) { return item.runAt > now; });
            if (moveBack != std::end(toProcess)) {
                mWorkQueue.reserve(std::distance(moveBack, std::end(toProcess)) + 5);
                std::move(moveBack, std::end(toProcess), std::back_inserter(mWorkQueue));
                toProcess.erase(moveBack, std::end(toProcess));
            }
        }
        for (auto& item : toProcess) {
            item.work();
        }
    }

    template <class F>
    void postAt(nsecs_t time, F&& func) {
        enqueue(WorkItem{time, std::function<void()>(std::forward<F>(func))});
    }

    template <class F>
    void postDelayed(nsecs_t delay, F&& func) {
        enqueue(WorkItem{clock::now() + delay, std::function<void()>(std::forward<F>(func))});
    }

    template <class F>
    void post(F&& func) {
        postAt(0, std::forward<F>(func));
    }

    template <class F>
    auto async(F&& func) -> std::future<decltype(func())> {
        typedef std::packaged_task<decltype(func())()> task_t;
        auto task = std::make_shared<task_t>(std::forward<F>(func));
        post([task]() { std::invoke(*task); });
        return task->get_future();
    }

    template <class F>
    auto runSync(F&& func) -> decltype(func()) {
        std::packaged_task<decltype(func())()> task{std::forward<F>(func)};
        post([&task]() { std::invoke(task); });
        return task.get_future().get();
    };

    nsecs_t nextWakeup(std::unique_lock<std::mutex>& lock) {
        if (mWorkQueue.empty()) {
            return std::numeric_limits<nsecs_t>::max();
        } else {
            return std::begin(mWorkQueue)->runAt;
        }
    }

private:
    void enqueue(WorkItem&& item) {
        bool needsWakeup;
        {
            std::unique_lock _lock{mLock};
            auto insertAt = std::find_if(
                    std::begin(mWorkQueue), std::end(mWorkQueue),
                    [time = item.runAt](WorkItem & item) { return item.runAt > time; });
            needsWakeup = std::begin(mWorkQueue) == insertAt;
            mWorkQueue.emplace(insertAt, std::move(item));
        }
        if (needsWakeup) {
            mWakeFunc();
        }
    }

    std::function<void()> mWakeFunc;

    std::mutex& mLock;
    std::vector<WorkItem> mWorkQueue;
};

}  // namespace android::uirenderer

#endif  // HWUI_WORKQUEUE_H
