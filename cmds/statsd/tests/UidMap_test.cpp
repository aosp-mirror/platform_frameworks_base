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

#include "packages/UidMap.h"
#include "config/ConfigKey.h"

#include <gtest/gtest.h>

#include <stdio.h>

using namespace android;

namespace android {
namespace os {
namespace statsd {

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

TEST(UidMapTest, TestClearingOutput) {
    UidMap m;

    ConfigKey config1(1, "config1");
    ConfigKey config2(1, "config2");

    m.OnConfigUpdated(config1);

    vector<int32_t> uids;
    vector<int32_t> versions;
    vector<String16> apps;
    uids.push_back(1000);
    uids.push_back(1000);
    apps.push_back(String16(kApp1.c_str()));
    apps.push_back(String16(kApp2.c_str()));
    versions.push_back(4);
    versions.push_back(5);
    m.updateMap(1, uids, versions, apps);

    UidMapping results = m.getOutput(2, config1);
    EXPECT_EQ(1, results.snapshots_size());

    // It should be cleared now
    results = m.getOutput(3, config1);
    EXPECT_EQ(0, results.snapshots_size());

    // Now add another configuration.
    m.OnConfigUpdated(config2);
    m.updateApp(5, String16(kApp1.c_str()), 1000, 40);
    results = m.getOutput(6, config1);
    EXPECT_EQ(0, results.snapshots_size());
    EXPECT_EQ(1, results.changes_size());

    // Now we still haven't been able to delete anything
    m.updateApp(7, String16(kApp2.c_str()), 1001, 41);
    results = m.getOutput(8, config1);
    EXPECT_EQ(0, results.snapshots_size());
    EXPECT_EQ(2, results.changes_size());

    results = m.getOutput(9, config2);
    EXPECT_EQ(0, results.snapshots_size());
    EXPECT_EQ(2, results.changes_size());
    // At this point both should be cleared.
    EXPECT_EQ(0, m.mOutput.snapshots_size());
    EXPECT_EQ(0, m.mOutput.changes_size());
}
#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
