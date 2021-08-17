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

#ifndef FRAMEWORKS_BASE_COMMONPOOL_H
#define FRAMEWORKS_BASE_COMMONPOOL_H

#include "utils/Macros.h"

#include <log/log.h>

#include <condition_variable>
#include <functional>
#include <future>
#include <mutex>
#include <vector>

namespace android {
namespace uirenderer {

template <class T, int SIZE>
class ArrayQueue {
    PREVENT_COPY_AND_ASSIGN(ArrayQueue);
    static_assert(SIZE > 0, "Size must be positive");

public:
    ArrayQueue() = default;
    ~ArrayQueue() = default;

    constexpr size_t capacity() const { return SIZE; }
    constexpr bool hasWork() const { return mHead != mTail; }
    constexpr bool hasSpace() const { return ((mHead + 1) % SIZE) != mTail; }
    constexpr int size() const {
        if (mHead > mTail) {
            return mHead - mTail;
        } else {
            return mTail - mHead + SIZE;
        }
    }

    constexpr void push(T&& t) {
        int newHead = (mHead + 1) % SIZE;
        LOG_ALWAYS_FATAL_IF(newHead == mTail, "no space");

        mBuffer[mHead] = std::move(t);
        mHead = newHead;
    }

    constexpr T pop() {
        LOG_ALWAYS_FATAL_IF(mTail == mHead, "empty");
        int index = mTail;
        mTail = (mTail + 1) % SIZE;
        T ret = std::move(mBuffer[index]);
        mBuffer[index] = nullptr;
        return ret;
    }

private:
    T mBuffer[SIZE];
    int mHead = 0;
    int mTail = 0;
};

class CommonPool {
    PREVENT_COPY_AND_ASSIGN(CommonPool);

public:
    using Task = std::function<void()>;
    static constexpr auto THREAD_COUNT = 2;
    static constexpr auto QUEUE_SIZE = 128;

    static void post(Task&& func);

    template <class F>
    static auto async(F&& func) -> std::future<decltype(func())> {
        typedef std::packaged_task<decltype(func())()> task_t;
        auto task = std::make_shared<task_t>(std::forward<F>(func));
        post([task]() { std::invoke(*task); });
        return task->get_future();
    }

    template <class F>
    static auto runSync(F&& func) -> decltype(func()) {
        std::packaged_task<decltype(func())()> task{std::forward<F>(func)};
        post([&task]() { std::invoke(task); });
        return task.get_future().get();
    };

    static std::vector<int> getThreadIds();

    // For testing purposes only, blocks until all worker threads are parked.
    static void waitForIdle();

private:
    static CommonPool& instance();

    CommonPool();
    ~CommonPool() {}

    void enqueue(Task&&);
    void doWaitForIdle();

    void workerLoop();

    std::vector<int> mWorkerThreadIds;

    std::mutex mLock;
    std::condition_variable mCondition;
    int mWaitingThreads = 0;
    ArrayQueue<Task, QUEUE_SIZE> mWorkQueue;
};

}  // namespace uirenderer
}  // namespace android

#endif  // FRAMEWORKS_BASE_COMMONPOOL_H
