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

#include <gtest/gtest.h>

#include "thread/CommonPool.h"

#include <array>
#include <condition_variable>
#include <set>
#include <thread>
#include "unistd.h"

using namespace android;
using namespace android::uirenderer;

TEST(CommonPool, post) {
    std::atomic_bool ran(false);
    CommonPool::post([&ran] { ran = true; });
    for (int i = 0; !ran && i < 1000; i++) {
        usleep(1);
    }
    EXPECT_TRUE(ran) << "Failed to flip atomic after 1 second";
}

TEST(CommonPool, threadCount) {
    std::set<pid_t> threads;
    std::array<std::future<pid_t>, 64> futures;
    for (int i = 0; i < futures.size(); i++) {
        futures[i] = CommonPool::async([] {
            usleep(10);
            return gettid();
        });
    }
    for (auto& f : futures) {
        threads.insert(f.get());
    }
    EXPECT_EQ(threads.size(), CommonPool::THREAD_COUNT);
    EXPECT_EQ(0, threads.count(gettid()));
}

TEST(CommonPool, singleThread) {
    std::mutex mutex;
    std::condition_variable fence;
    bool isProcessing = false;
    bool queuedSecond = false;

    auto f1 = CommonPool::async([&] {
        {
            std::unique_lock lock{mutex};
            isProcessing = true;
            fence.notify_all();
            while (!queuedSecond) {
                fence.wait(lock);
            }
        }
        return gettid();
    });

    {
        std::unique_lock lock{mutex};
        while (!isProcessing) {
            fence.wait(lock);
        }
    }

    auto f2 = CommonPool::async([] {
        return gettid();
    });

    {
        std::unique_lock lock{mutex};
        queuedSecond = true;
        fence.notify_all();
    }

    auto tid1 = f1.get();
    auto tid2 = f2.get();
    EXPECT_EQ(tid1, tid2);
    EXPECT_NE(gettid(), tid1);
}

TEST(CommonPool, fullQueue) {
    std::mutex lock;
    std::condition_variable fence;
    bool signaled = false;
    static constexpr auto QUEUE_COUNT = CommonPool::THREAD_COUNT + CommonPool::QUEUE_SIZE + 10;
    std::atomic_int queuedCount{0};
    std::array<std::future<void>, QUEUE_COUNT> futures;

    std::thread queueThread{[&] {
        for (int i = 0; i < QUEUE_COUNT; i++) {
            futures[i] = CommonPool::async([&] {
                std::unique_lock _lock{lock};
                while (!signaled) {
                    fence.wait(_lock);
                }
            });
            queuedCount++;
        }
    }};

    int previous;
    do {
        previous = queuedCount.load();
        usleep(10000);
    } while (previous != queuedCount.load());

    EXPECT_GT(queuedCount.load(), CommonPool::QUEUE_SIZE);
    EXPECT_LT(queuedCount.load(), QUEUE_COUNT);

    {
        std::unique_lock _lock{lock};
        signaled = true;
        fence.notify_all();
    }

    queueThread.join();
    EXPECT_EQ(queuedCount.load(), QUEUE_COUNT);

    // Ensure all our tasks are finished before return as they have references to the stack
    for (auto& f : futures) {
        f.get();
    }
}