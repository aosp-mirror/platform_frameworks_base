/*
 * Copyright (C) 2020 The Android Open Source Project
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
#include "utils/NamedLatch.h"

#include <gtest/gtest.h>

#include <chrono>
#include <set>
#include <thread>
#include <vector>

#ifdef __ANDROID__

using namespace std;
using std::this_thread::sleep_for;

namespace android {
namespace os {
namespace statsd {

TEST(NamedLatchTest, TestWait) {
    int numEvents = 5;
    string t1 = "t1", t2 = "t2", t3 = "t3", t4 = "t4", t5 = "t5";
    set<string> eventNames = {t1, t2, t3, t4, t5};

    NamedLatch latch(eventNames);
    vector<thread> threads;
    vector<bool> done(numEvents, false);

    int i = 0;
    for (const string& eventName : eventNames) {
        threads.emplace_back([&done, &eventName, &latch, i] {
            sleep_for(chrono::milliseconds(3));
            done[i] = true;
            latch.countDown(eventName);
        });
        i++;
    }

    latch.wait();

    for (i = 0; i < numEvents; i++) {
        EXPECT_EQ(done[i], 1);
    }

    for (i = 0; i < numEvents; i++) {
        threads[i].join();
    }
}

TEST(NamedLatchTest, TestNoWorkers) {
    NamedLatch latch({});
    latch.wait();
    // Ensure that latch does not wait if no events need to countDown.
}

TEST(NamedLatchTest, TestCountDownCalledBySameEventName) {
    string t1 = "t1", t2 = "t2";
    set<string> eventNames = {t1, t2};

    NamedLatch latch(eventNames);

    thread waiterThread([&latch] { latch.wait(); });

    latch.countDown(t1);
    latch.countDown(t1);

    // Ensure that the latch's remaining threads still has t2.
    latch.mMutex.lock();
    ASSERT_EQ(latch.mRemainingEventNames.size(), 1);
    EXPECT_NE(latch.mRemainingEventNames.find(t2), latch.mRemainingEventNames.end());
    latch.mMutex.unlock();

    latch.countDown(t2);
    waiterThread.join();
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
