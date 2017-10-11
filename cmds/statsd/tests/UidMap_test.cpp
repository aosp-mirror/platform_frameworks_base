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

#define LOG_TAG "statsd_test"

#include <gtest/gtest.h>
#include "../src/UidMap.h"
#include <stdio.h>

using namespace android;
using namespace android::os::statsd;

#ifdef __ANDROID__
const string kApp1 = "app1.sharing.1";
const string kApp2 = "app2.sharing.1";

TEST(UidMapTest, TestMatching) {
    UidMap m;
    vector<int32_t> uids;
    vector<int32_t> versions;
    vector<String16> apps;

    uids.push_back(1000);
    uids.push_back(1000);
    apps.push_back(String16(kApp1.c_str()));
    apps.push_back(String16(kApp2.c_str()));
    versions.push_back(4);
    versions.push_back(5);
    m.updateMap(uids, versions, apps);
    EXPECT_TRUE(m.hasApp(1000, kApp1));
    EXPECT_TRUE(m.hasApp(1000, kApp2));
    EXPECT_FALSE(m.hasApp(1000, "not.app"));
}

TEST(UidMapTest, TestAddAndRemove) {
    UidMap m;
    vector<int32_t> uids;
    vector<int32_t> versions;
    vector<String16> apps;

    uids.push_back(1000);
    uids.push_back(1000);
    apps.push_back(String16(kApp1.c_str()));
    apps.push_back(String16(kApp2.c_str()));
    versions.push_back(4);
    versions.push_back(5);
    m.updateMap(uids, versions, apps);

    m.updateApp(String16(kApp1.c_str()), 1000, 40);
    EXPECT_EQ(40, m.getAppVersion(1000, kApp1));

    m.removeApp(String16(kApp1.c_str()), 1000);
    EXPECT_FALSE(m.hasApp(1000, kApp1));
    EXPECT_TRUE(m.hasApp(1000, kApp2));
}
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif