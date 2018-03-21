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

#include "StatsService.h"
#include "config/ConfigKey.h"
#include "frameworks/base/cmds/statsd/src/statsd_config.pb.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <stdio.h>

using namespace android;
using namespace testing;

namespace android {
namespace os {
namespace statsd {

using android::util::ProtoOutputStream;

#ifdef __ANDROID__

TEST(StatsServiceTest, TestAddConfig_simple) {
    StatsService service(nullptr);
    StatsdConfig config;
    config.set_id(12345);
    string serialized = config.SerializeAsString();

    EXPECT_TRUE(
            service.addConfigurationChecked(123, 12345, {serialized.begin(), serialized.end()}));
}

TEST(StatsServiceTest, TestAddConfig_empty) {
    StatsService service(nullptr);
    string serialized = "";

    EXPECT_TRUE(
            service.addConfigurationChecked(123, 12345, {serialized.begin(), serialized.end()}));
}

TEST(StatsServiceTest, TestAddConfig_invalid) {
    StatsService service(nullptr);
    string serialized = "Invalid config!";

    EXPECT_FALSE(
            service.addConfigurationChecked(123, 12345, {serialized.begin(), serialized.end()}));
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
