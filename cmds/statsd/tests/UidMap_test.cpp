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
#include "StatsLogProcessor.h"
#include "config/ConfigKey.h"
#include "guardrail/StatsdStats.h"
#include "logd/LogEvent.h"
#include "hash.h"
#include "statslog.h"
#include "statsd_test_util.h"

#include <android/util/ProtoOutputStream.h>
#include <gtest/gtest.h>

#include <stdio.h>

using namespace android;

namespace android {
namespace os {
namespace statsd {

using android::util::ProtoOutputStream;

#ifdef __ANDROID__
const string kApp1 = "app1.sharing.1";
const string kApp2 = "app2.sharing.1";

TEST(UidMapTest, TestIsolatedUID) {
    sp<UidMap> m = new UidMap();
    sp<AlarmMonitor> anomalyAlarmMonitor;
    sp<AlarmMonitor> subscriberAlarmMonitor;
    // Construct the processor with a dummy sendBroadcast function that does nothing.
    StatsLogProcessor p(m, anomalyAlarmMonitor, subscriberAlarmMonitor, 0,
        [](const ConfigKey& key) {return true;});
    LogEvent addEvent(android::util::ISOLATED_UID_CHANGED, 1);
    addEvent.write(100);  // parent UID
    addEvent.write(101);  // isolated UID
    addEvent.write(1);    // Indicates creation.
    addEvent.init();

    EXPECT_EQ(101, m->getHostUidOrSelf(101));

    p.OnLogEvent(&addEvent);
    EXPECT_EQ(100, m->getHostUidOrSelf(101));

    LogEvent removeEvent(android::util::ISOLATED_UID_CHANGED, 1);
    removeEvent.write(100);  // parent UID
    removeEvent.write(101);  // isolated UID
    removeEvent.write(0);    // Indicates removal.
    removeEvent.init();
    p.OnLogEvent(&removeEvent);
    EXPECT_EQ(101, m->getHostUidOrSelf(101));
}

TEST(UidMapTest, TestMatching) {
    UidMap m;
    vector<int32_t> uids;
    vector<int64_t> versions;
    vector<String16> apps;

    uids.push_back(1000);
    uids.push_back(1000);
    apps.push_back(String16(kApp1.c_str()));
    apps.push_back(String16(kApp2.c_str()));
    versions.push_back(4);
    versions.push_back(5);
    m.updateMap(1, uids, versions, apps);
    EXPECT_TRUE(m.hasApp(1000, kApp1));
    EXPECT_TRUE(m.hasApp(1000, kApp2));
    EXPECT_FALSE(m.hasApp(1000, "not.app"));

    std::set<string> name_set = m.getAppNamesFromUid(1000u, true /* returnNormalized */);
    EXPECT_EQ(name_set.size(), 2u);
    EXPECT_TRUE(name_set.find(kApp1) != name_set.end());
    EXPECT_TRUE(name_set.find(kApp2) != name_set.end());

    name_set = m.getAppNamesFromUid(12345, true /* returnNormalized */);
    EXPECT_TRUE(name_set.empty());
}

TEST(UidMapTest, TestAddAndRemove) {
    UidMap m;
    vector<int32_t> uids;
    vector<int64_t> versions;
    vector<String16> apps;

    uids.push_back(1000);
    uids.push_back(1000);
    apps.push_back(String16(kApp1.c_str()));
    apps.push_back(String16(kApp2.c_str()));
    versions.push_back(4);
    versions.push_back(5);
    m.updateMap(1, uids, versions, apps);

    std::set<string> name_set = m.getAppNamesFromUid(1000, true /* returnNormalized */);
    EXPECT_EQ(name_set.size(), 2u);
    EXPECT_TRUE(name_set.find(kApp1) != name_set.end());
    EXPECT_TRUE(name_set.find(kApp2) != name_set.end());

    // Update the app1 version.
    m.updateApp(2, String16(kApp1.c_str()), 1000, 40);
    EXPECT_EQ(40, m.getAppVersion(1000, kApp1));

    name_set = m.getAppNamesFromUid(1000, true /* returnNormalized */);
    EXPECT_EQ(name_set.size(), 2u);
    EXPECT_TRUE(name_set.find(kApp1) != name_set.end());
    EXPECT_TRUE(name_set.find(kApp2) != name_set.end());

    m.removeApp(3, String16(kApp1.c_str()), 1000);
    EXPECT_FALSE(m.hasApp(1000, kApp1));
    EXPECT_TRUE(m.hasApp(1000, kApp2));
    name_set = m.getAppNamesFromUid(1000, true /* returnNormalized */);
    EXPECT_EQ(name_set.size(), 1u);
    EXPECT_TRUE(name_set.find(kApp1) == name_set.end());
    EXPECT_TRUE(name_set.find(kApp2) != name_set.end());

    // Remove app2.
    m.removeApp(4, String16(kApp2.c_str()), 1000);
    EXPECT_FALSE(m.hasApp(1000, kApp1));
    EXPECT_FALSE(m.hasApp(1000, kApp2));
    name_set = m.getAppNamesFromUid(1000, true /* returnNormalized */);
    EXPECT_TRUE(name_set.empty());
}

TEST(UidMapTest, TestUpdateApp) {
    UidMap m;
    m.updateMap(1, {1000, 1000}, {4, 5}, {String16(kApp1.c_str()), String16(kApp2.c_str())});
    std::set<string> name_set = m.getAppNamesFromUid(1000, true /* returnNormalized */);
    EXPECT_EQ(name_set.size(), 2u);
    EXPECT_TRUE(name_set.find(kApp1) != name_set.end());
    EXPECT_TRUE(name_set.find(kApp2) != name_set.end());

    // Adds a new name for uid 1000.
    m.updateApp(2, String16("NeW_aPP1_NAmE"), 1000, 40);
    name_set = m.getAppNamesFromUid(1000, true /* returnNormalized */);
    EXPECT_EQ(name_set.size(), 3u);
    EXPECT_TRUE(name_set.find(kApp1) != name_set.end());
    EXPECT_TRUE(name_set.find(kApp2) != name_set.end());
    EXPECT_TRUE(name_set.find("NeW_aPP1_NAmE") == name_set.end());
    EXPECT_TRUE(name_set.find("new_app1_name") != name_set.end());

    // This name is also reused by another uid 2000.
    m.updateApp(3, String16("NeW_aPP1_NAmE"), 2000, 1);
    name_set = m.getAppNamesFromUid(2000, true /* returnNormalized */);
    EXPECT_EQ(name_set.size(), 1u);
    EXPECT_TRUE(name_set.find("NeW_aPP1_NAmE") == name_set.end());
    EXPECT_TRUE(name_set.find("new_app1_name") != name_set.end());
}

static void protoOutputStreamToUidMapping(ProtoOutputStream* proto, UidMapping* results) {
    vector<uint8_t> bytes;
    bytes.resize(proto->size());
    size_t pos = 0;
    auto iter = proto->data();
    while (iter.readBuffer() != NULL) {
        size_t toRead = iter.currentToRead();
        std::memcpy(&((bytes)[pos]), iter.readBuffer(), toRead);
        pos += toRead;
        iter.rp()->move(toRead);
    }
    results->ParseFromArray(bytes.data(), bytes.size());
}

// Test that uid map returns at least one snapshot even if we already obtained
// this snapshot from a previous call to getData.
TEST(UidMapTest, TestOutputIncludesAtLeastOneSnapshot) {
    UidMap m;
    // Initialize single config key.
    ConfigKey config1(1, StringToId("config1"));
    m.OnConfigUpdated(config1);
    vector<int32_t> uids;
    vector<int64_t> versions;
    vector<String16> apps;
    uids.push_back(1000);
    apps.push_back(String16(kApp2.c_str()));
    versions.push_back(5);
    m.updateMap(1, uids, versions, apps);

    // Set the last timestamp for this config key to be newer.
    m.mLastUpdatePerConfigKey[config1] = 2;

    ProtoOutputStream proto;
    m.appendUidMap(3, config1, nullptr, &proto);

    // Check there's still a uidmap attached this one.
    UidMapping results;
    protoOutputStreamToUidMapping(&proto, &results);
    EXPECT_EQ(1, results.snapshots_size());
}

TEST(UidMapTest, TestRemovedAppRetained) {
    UidMap m;
    // Initialize single config key.
    ConfigKey config1(1, StringToId("config1"));
    m.OnConfigUpdated(config1);
    vector<int32_t> uids;
    vector<int64_t> versions;
    vector<String16> apps;
    uids.push_back(1000);
    apps.push_back(String16(kApp2.c_str()));
    versions.push_back(5);
    m.updateMap(1, uids, versions, apps);
    m.removeApp(2, String16(kApp2.c_str()), 1000);

    ProtoOutputStream proto;
    m.appendUidMap(3, config1, nullptr, &proto);

    // Snapshot should still contain this item as deleted.
    UidMapping results;
    protoOutputStreamToUidMapping(&proto, &results);
    EXPECT_EQ(1, results.snapshots(0).package_info_size());
    EXPECT_EQ(true, results.snapshots(0).package_info(0).deleted());
}

TEST(UidMapTest, TestRemovedAppOverGuardrail) {
    UidMap m;
    // Initialize single config key.
    ConfigKey config1(1, StringToId("config1"));
    m.OnConfigUpdated(config1);
    vector<int32_t> uids;
    vector<int64_t> versions;
    vector<String16> apps;
    const int maxDeletedApps = StatsdStats::kMaxDeletedAppsInUidMap;
    for (int j = 0; j < maxDeletedApps + 10; j++) {
        uids.push_back(j);
        apps.push_back(String16(kApp1.c_str()));
        versions.push_back(j);
    }
    m.updateMap(1, uids, versions, apps);

    // First, verify that we have the expected number of items.
    UidMapping results;
    ProtoOutputStream proto;
    m.appendUidMap(3, config1, nullptr, &proto);
    protoOutputStreamToUidMapping(&proto, &results);
    EXPECT_EQ(maxDeletedApps + 10, results.snapshots(0).package_info_size());

    // Now remove all the apps.
    m.updateMap(1, uids, versions, apps);
    for (int j = 0; j < maxDeletedApps + 10; j++) {
        m.removeApp(4, String16(kApp1.c_str()), j);
    }

    proto.clear();
    m.appendUidMap(5, config1, nullptr, &proto);
    // Snapshot drops the first nine items.
    protoOutputStreamToUidMapping(&proto, &results);
    EXPECT_EQ(maxDeletedApps, results.snapshots(0).package_info_size());
}

TEST(UidMapTest, TestClearingOutput) {
    UidMap m;

    ConfigKey config1(1, StringToId("config1"));
    ConfigKey config2(1, StringToId("config2"));

    m.OnConfigUpdated(config1);

    vector<int32_t> uids;
    vector<int64_t> versions;
    vector<String16> apps;
    uids.push_back(1000);
    uids.push_back(1000);
    apps.push_back(String16(kApp1.c_str()));
    apps.push_back(String16(kApp2.c_str()));
    versions.push_back(4);
    versions.push_back(5);
    m.updateMap(1, uids, versions, apps);

    ProtoOutputStream proto;
    m.appendUidMap(2, config1, nullptr, &proto);
    UidMapping results;
    protoOutputStreamToUidMapping(&proto, &results);
    EXPECT_EQ(1, results.snapshots_size());

    // We have to keep at least one snapshot in memory at all times.
    proto.clear();
    m.appendUidMap(2, config1, nullptr, &proto);
    protoOutputStreamToUidMapping(&proto, &results);
    EXPECT_EQ(1, results.snapshots_size());

    // Now add another configuration.
    m.OnConfigUpdated(config2);
    m.updateApp(5, String16(kApp1.c_str()), 1000, 40);
    EXPECT_EQ(1U, m.mChanges.size());
    proto.clear();
    m.appendUidMap(6, config1, nullptr, &proto);
    protoOutputStreamToUidMapping(&proto, &results);
    EXPECT_EQ(1, results.snapshots_size());
    EXPECT_EQ(1, results.changes_size());
    EXPECT_EQ(1U, m.mChanges.size());

    // Add another delta update.
    m.updateApp(7, String16(kApp2.c_str()), 1001, 41);
    EXPECT_EQ(2U, m.mChanges.size());

    // We still can't remove anything.
    proto.clear();
    m.appendUidMap(8, config1, nullptr, &proto);
    protoOutputStreamToUidMapping(&proto, &results);
    EXPECT_EQ(1, results.snapshots_size());
    EXPECT_EQ(1, results.changes_size());
    EXPECT_EQ(2U, m.mChanges.size());

    proto.clear();
    m.appendUidMap(9, config2, nullptr, &proto);
    protoOutputStreamToUidMapping(&proto, &results);
    EXPECT_EQ(1, results.snapshots_size());
    EXPECT_EQ(2, results.changes_size());
    // At this point both should be cleared.
    EXPECT_EQ(0U, m.mChanges.size());
}

TEST(UidMapTest, TestMemoryComputed) {
    UidMap m;

    ConfigKey config1(1, StringToId("config1"));
    m.OnConfigUpdated(config1);

    size_t startBytes = m.mBytesUsed;
    vector<int32_t> uids;
    vector<int64_t> versions;
    vector<String16> apps;
    uids.push_back(1000);
    apps.push_back(String16(kApp1.c_str()));
    versions.push_back(1);
    m.updateMap(1, uids, versions, apps);

    m.updateApp(3, String16(kApp1.c_str()), 1000, 40);

    ProtoOutputStream proto;
    vector<uint8_t> bytes;
    m.appendUidMap(2, config1, nullptr, &proto);
    size_t prevBytes = m.mBytesUsed;

    m.appendUidMap(4, config1, nullptr, &proto);
    EXPECT_TRUE(m.mBytesUsed < prevBytes);
}

TEST(UidMapTest, TestMemoryGuardrail) {
    UidMap m;
    string buf;

    ConfigKey config1(1, StringToId("config1"));
    m.OnConfigUpdated(config1);

    size_t startBytes = m.mBytesUsed;
    vector<int32_t> uids;
    vector<int64_t> versions;
    vector<String16> apps;
    for (int i = 0; i < 100; i++) {
        uids.push_back(1);
        buf = "EXTREMELY_LONG_STRING_FOR_APP_TO_WASTE_MEMORY." + to_string(i);
        apps.push_back(String16(buf.c_str()));
        versions.push_back(1);
    }
    m.updateMap(1, uids, versions, apps);

    m.updateApp(3, String16("EXTREMELY_LONG_STRING_FOR_APP_TO_WASTE_MEMORY.0"), 1000, 2);
    EXPECT_EQ(1U, m.mChanges.size());

    // Now force deletion by limiting the memory to hold one delta change.
    m.maxBytesOverride = 80; // Since the app string alone requires >45 characters.
    m.updateApp(5, String16("EXTREMELY_LONG_STRING_FOR_APP_TO_WASTE_MEMORY.0"), 1000, 4);
    EXPECT_EQ(1U, m.mChanges.size());
}

#else
GTEST_LOG_(INFO) << "This test does nothing.\n";
#endif

}  // namespace statsd
}  // namespace os
}  // namespace android
