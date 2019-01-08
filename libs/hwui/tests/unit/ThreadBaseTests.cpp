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

#include <gtest/gtest.h>

#include "thread/ThreadBase.h"
#include "utils/TimeUtils.h"

#include <chrono>
#include "unistd.h"

using namespace android;
using namespace android::uirenderer;

static ThreadBase& thread() {
    class TestThread : public ThreadBase, public virtual RefBase {};
    static sp<TestThread> thread = []() -> auto {
        sp<TestThread> ret{new TestThread};
        ret->start("TestThread");
        return ret;
    }
    ();
    return *thread;
}

static WorkQueue& queue() {
    return thread().queue();
}

TEST(ThreadBase, post) {
    std::atomic_bool ran(false);
    queue().post([&ran]() { ran = true; });
    for (int i = 0; !ran && i < 1000; i++) {
        usleep(1);
    }
    ASSERT_TRUE(ran) << "Failed to flip atomic after 1 second";
}

TEST(ThreadBase, postDelay) {
    using clock = WorkQueue::clock;

    std::promise<nsecs_t> ranAtPromise;
    auto queuedAt = clock::now();
    queue().postDelayed(100_us, [&]() { ranAtPromise.set_value(clock::now()); });
    auto ranAt = ranAtPromise.get_future().get();
    auto ranAfter = ranAt - queuedAt;
    ASSERT_TRUE(ranAfter > 90_us) << "Ran after " << ns2us(ranAfter) << "us <= 90us";
}

TEST(ThreadBase, runSync) {
    pid_t thisTid = gettid();
    pid_t otherTid = thisTid;

    auto result = queue().runSync([&otherTid]() -> auto {
        otherTid = gettid();
        return 42;
    });

    ASSERT_EQ(42, result);
    ASSERT_NE(thisTid, otherTid);
}

TEST(ThreadBase, async) {
    pid_t thisTid = gettid();
    pid_t thisPid = getpid();

    auto otherTid = queue().async([]() -> auto { return gettid(); });
    auto otherPid = queue().async([]() -> auto { return getpid(); });
    auto result = queue().async([]() -> auto { return 42; });

    ASSERT_NE(thisTid, otherTid.get());
    ASSERT_EQ(thisPid, otherPid.get());
    ASSERT_EQ(42, result.get());
}

TEST(ThreadBase, lifecyclePerf) {
    struct EventCount {
        std::atomic_int construct{0};
        std::atomic_int destruct{0};
        std::atomic_int copy{0};
        std::atomic_int move{0};
    };

    struct Counter {
        explicit Counter(EventCount* count) : mCount(count) { mCount->construct++; }

        Counter(const Counter& other) : mCount(other.mCount) {
            if (mCount) mCount->copy++;
        }

        Counter(Counter&& other) : mCount(other.mCount) {
            other.mCount = nullptr;
            if (mCount) mCount->move++;
        }

        Counter& operator=(const Counter& other) {
            mCount = other.mCount;
            if (mCount) mCount->copy++;
            return *this;
        }

        Counter& operator=(Counter&& other) {
            mCount = other.mCount;
            other.mCount = nullptr;
            if (mCount) mCount->move++;
            return *this;
        }

        ~Counter() {
            if (mCount) mCount->destruct++;
        }

        EventCount* mCount;
    };

    EventCount count;
    {
        Counter counter{&count};
        queue().runSync([c = std::move(counter)](){});
    }
    ASSERT_EQ(1, count.construct.load());
    ASSERT_EQ(1, count.destruct.load());
    ASSERT_EQ(0, count.copy.load());
    ASSERT_LE(1, count.move.load());
}

int lifecycleTestHelper(const sp<VirtualLightRefBase>& test) {
    return queue().runSync([t = test]()->int { return t->getStrongCount(); });
}

TEST(ThreadBase, lifecycle) {
    sp<VirtualLightRefBase> dummyObject{new VirtualLightRefBase};
    ASSERT_EQ(1, dummyObject->getStrongCount());
    ASSERT_EQ(2, queue().runSync([dummyObject]() -> int { return dummyObject->getStrongCount(); }));
    ASSERT_EQ(1, dummyObject->getStrongCount());
    ASSERT_EQ(2, lifecycleTestHelper(dummyObject));
    ASSERT_EQ(1, dummyObject->getStrongCount());
}
