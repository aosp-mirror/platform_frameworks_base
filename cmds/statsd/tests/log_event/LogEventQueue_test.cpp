// Copyright (C) 2019 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

#include "logd/LogEventQueue.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <stdio.h>

#include <thread>

#include "stats_event.h"
#include "tests/statsd_test_util.h"

namespace android {
namespace os {
namespace statsd {

using namespace android;
using namespace testing;

using std::unique_ptr;

namespace {

std::unique_ptr<LogEvent> makeLogEvent(uint64_t timestampNs) {
    AStatsEvent* statsEvent = AStatsEvent_obtain();
    AStatsEvent_setAtomId(statsEvent, 10);
    AStatsEvent_overwriteTimestamp(statsEvent, timestampNs);

    std::unique_ptr<LogEvent> logEvent = std::make_unique<LogEvent>(/*uid=*/0, /*pid=*/0);
    parseStatsEventToLogEvent(statsEvent, logEvent.get());
    return logEvent;
}

} // anonymous namespace

#ifdef __ANDROID__
TEST(LogEventQueue_test, TestGoodConsumer) {
    LogEventQueue queue(50);
    int64_t timeBaseNs = 100;
    std::thread writer([&queue, timeBaseNs] {
        for (int i = 0; i < 100; i++) {
            int64_t oldestEventNs;
            bool success = queue.push(makeLogEvent(timeBaseNs + i * 1000), &oldestEventNs);
            EXPECT_TRUE(success);
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }
    });

    std::thread reader([&queue, timeBaseNs] {
        for (int i = 0; i < 100; i++) {
            auto event = queue.waitPop();
            EXPECT_TRUE(event != nullptr);
            // All events are in right order.
            EXPECT_EQ(timeBaseNs + i * 1000, event->GetElapsedTimestampNs());
        }
    });

    reader.join();
    writer.join();
}

TEST(LogEventQueue_test, TestSlowConsumer) {
    LogEventQueue queue(50);
    int64_t timeBaseNs = 100;
    std::thread writer([&queue, timeBaseNs] {
        int failure_count = 0;
        int64_t oldestEventNs;
        for (int i = 0; i < 100; i++) {
            bool success = queue.push(makeLogEvent(timeBaseNs + i * 1000), &oldestEventNs);
            if (!success) failure_count++;
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
        }

        // There is some remote chance that reader thread not get chance to run before writer thread
        // ends. That's why the following comparison is not "==".
        // There will be at least 45 events lost due to overflow.
        EXPECT_TRUE(failure_count >= 45);
        // The oldest event must be at least the 6th event.
        EXPECT_TRUE(oldestEventNs <= (100 + 5 * 1000));
    });

    std::thread reader([&queue, timeBaseNs] {
        // The consumer quickly processed 5 events, then it got stuck (not reading anymore).
        for (int i = 0; i < 5; i++) {
            auto event = queue.waitPop();
            EXPECT_TRUE(event != nullptr);
            // All events are in right order.
            EXPECT_EQ(timeBaseNs + i * 1000, event->GetElapsedTimestampNs());
        }
    });

    reader.join();
    writer.join();
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
