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

TEST(StatsServiceTest, TestGetUidFromArgs) {
    Vector<String8> args;
    args.push(String8("-1"));
    args.push(String8("0"));
    args.push(String8("1"));
    args.push(String8("9999999999999999999999999999999999"));
    args.push(String8("a1"));
    args.push(String8(""));

    int32_t uid;

    StatsService service(nullptr);
    service.mEngBuild = true;

    // "-1"
    EXPECT_FALSE(service.getUidFromArgs(args, 0, uid));

    // "0"
    EXPECT_TRUE(service.getUidFromArgs(args, 1, uid));
    EXPECT_EQ(0, uid);

    // "1"
    EXPECT_TRUE(service.getUidFromArgs(args, 2, uid));
    EXPECT_EQ(1, uid);

    // "999999999999999999"
    EXPECT_FALSE(service.getUidFromArgs(args, 3, uid));

    // "a1"
    EXPECT_FALSE(service.getUidFromArgs(args, 4, uid));

    // ""
    EXPECT_FALSE(service.getUidFromArgs(args, 5, uid));

    // For a non-userdebug, uid "1" cannot be impersonated.
    service.mEngBuild = false;
    EXPECT_FALSE(service.getUidFromArgs(args, 2, uid));
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
