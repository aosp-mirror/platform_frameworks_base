// Copyright (C) 2017 The Android Open Source Project
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

#include "src/guardrail/StatsdStats.h"

#include <gtest/gtest.h>

#ifdef __ANDROID__

namespace android {
namespace os {
namespace statsd {

TEST(StatsdStatsTest, TestConfigAdd) {
    // TODO: implement
}

TEST(StatsdStatsTest, TestConfigRemove) {
    // TODO: implement
}

TEST(StatsdStatsTest, TestMatcherReport) {
    // TODO: implement
}

TEST(StatsdStatsTest, TestConditionReport) {
    // TODO: implement
}

TEST(StatsdStatsTest, TestAtomLog) {
    // TODO: implement
}

}  // namespace statsd
}  // namespace os
}  // namespace android
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif
