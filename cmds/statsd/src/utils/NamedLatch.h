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
#pragma once

#include <gtest/gtest_prod.h>

#include <condition_variable>
#include <mutex>
#include <set>

namespace android {
namespace os {
namespace statsd {

/**
 * This class provides a threading primitive similar to a latch.
 * The primary difference is that it waits for named events to occur instead of waiting for
 * N threads to reach a certain point.
 *
 * It uses a condition variable under the hood.
 */
class NamedLatch {
public:
    explicit NamedLatch(const std::set<std::string>& eventNames);

    NamedLatch(const NamedLatch&) = delete;
    NamedLatch& operator=(const NamedLatch&) = delete;

    // Mark a specific event as completed. If this event has called countDown already or if the
    // event was not specified in the constructor, the function is a no-op.
    void countDown(const std::string& eventName);

    // Blocks the calling thread until all events in eventNames have called countDown.
    void wait() const;

private:
    mutable std::mutex mMutex;
    mutable std::condition_variable mConditionVariable;
    std::set<std::string> mRemainingEventNames;

    FRIEND_TEST(NamedLatchTest, TestCountDownCalledBySameEventName);
};
}  // namespace statsd
}  // namespace os
}  // namespace android
