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

#include <mutex>
#include <set>

namespace android {
namespace os {
namespace statsd {

/**
 * This class provides a utility to wait for a set of named conditions to occur.
 *
 * It will execute the trigger runnable in a detached thread once all conditions have been marked
 * true.
 */
class MultiConditionTrigger {
public:
    explicit MultiConditionTrigger(const std::set<std::string>& conditionNames,
                                   std::function<void()> trigger);

    MultiConditionTrigger(const MultiConditionTrigger&) = delete;
    MultiConditionTrigger& operator=(const MultiConditionTrigger&) = delete;

    // Mark a specific condition as true. If this condition has called markComplete already or if
    // the event was not specified in the constructor, the function is a no-op.
    void markComplete(const std::string& eventName);

private:
    mutable std::mutex mMutex;
    std::set<std::string> mRemainingConditionNames;
    std::function<void()> mTrigger;
    bool mCompleted;

    FRIEND_TEST(MultiConditionTriggerTest, TestCountDownCalledBySameEventName);
};
}  // namespace statsd
}  // namespace os
}  // namespace android
