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

#include "src/config/ConfigManager.h"
#include "src/metrics/MetricsManager.h"
#include "statsd_test_util.h"

#include <gmock/gmock.h>
#include <gtest/gtest.h>

#include <stdio.h>
#include <iostream>

using namespace android;
using namespace android::os::statsd;
using namespace testing;
using namespace std;

namespace android {
namespace os {
namespace statsd {

static ostream& operator<<(ostream& os, const StatsdConfig& config) {
    return os << "StatsdConfig{id=" << config.id() << "}";
}

}  // namespace statsd
}  // namespace os
}  // namespace android

/**
 * Mock ConfigListener
 */
class MockListener : public ConfigListener {
public:
    MOCK_METHOD3(OnConfigUpdated, void(const int64_t timestampNs, const ConfigKey& key,
                                       const StatsdConfig& config));
    MOCK_METHOD1(OnConfigRemoved, void(const ConfigKey& key));
};

/**
 * Validate that the ConfigKey is the one we wanted.
 */
MATCHER_P2(ConfigKeyEq, uid, id, "") {
    return arg.GetUid() == uid && (long long)arg.GetId() == (long long)id;
}

/**
 * Validate that the StatsdConfig is the one we wanted.
 */
MATCHER_P(StatsdConfigEq, id, 0) {
    return (long long)arg.id() == (long long)id;
}

const int64_t testConfigId = 12345;

/**
 * Test the addOrUpdate and remove methods
 */
TEST(ConfigManagerTest, TestAddUpdateRemove) {
    sp<MockListener> listener = new StrictMock<MockListener>();

    sp<ConfigManager> manager = new ConfigManager();
    manager->AddListener(listener);

    StatsdConfig config91;
    config91.set_id(91);
    StatsdConfig config92;
    config92.set_id(92);
    StatsdConfig config93;
    config93.set_id(93);
    StatsdConfig config94;
    config94.set_id(94);

    {
        InSequence s;

        manager->StartupForTest();

        // Add another one
        EXPECT_CALL(*(listener.get()), OnConfigUpdated(_, ConfigKeyEq(1, StringToId("zzz")),
            StatsdConfigEq(91)))
                .RetiresOnSaturation();
        manager->UpdateConfig(ConfigKey(1, StringToId("zzz")), config91);

        // Update It
        EXPECT_CALL(*(listener.get()), OnConfigUpdated(_, ConfigKeyEq(1, StringToId("zzz")),
            StatsdConfigEq(92)))
                .RetiresOnSaturation();
        manager->UpdateConfig(ConfigKey(1, StringToId("zzz")), config92);

        // Add one with the same uid but a different name
        EXPECT_CALL(*(listener.get()), OnConfigUpdated(_, ConfigKeyEq(1, StringToId("yyy")),
            StatsdConfigEq(93)))
                .RetiresOnSaturation();
        manager->UpdateConfig(ConfigKey(1, StringToId("yyy")), config93);

        // Add one with the same name but a different uid
        EXPECT_CALL(*(listener.get()), OnConfigUpdated(_, ConfigKeyEq(2, StringToId("zzz")),
            StatsdConfigEq(94)))
                .RetiresOnSaturation();
        manager->UpdateConfig(ConfigKey(2, StringToId("zzz")), config94);

        // Remove (1,yyy)
        EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(1, StringToId("yyy"))))
                .RetiresOnSaturation();
        manager->RemoveConfig(ConfigKey(1, StringToId("yyy")));

        // Remove (2,zzz)
        EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(2, StringToId("zzz"))))
                .RetiresOnSaturation();
        manager->RemoveConfig(ConfigKey(2, StringToId("zzz")));

        // Remove (1,zzz)
        EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(1, StringToId("zzz"))))
                .RetiresOnSaturation();
        manager->RemoveConfig(ConfigKey(1, StringToId("zzz")));

        // Remove (2,zzz) again and we shouldn't get the callback
        manager->RemoveConfig(ConfigKey(2, StringToId("zzz")));
    }
}

/**
 * Test removing all of the configs for a uid.
 */
TEST(ConfigManagerTest, TestRemoveUid) {
    sp<MockListener> listener = new StrictMock<MockListener>();

    sp<ConfigManager> manager = new ConfigManager();
    manager->AddListener(listener);

    StatsdConfig config;

    EXPECT_CALL(*(listener.get()), OnConfigUpdated(_, _, _)).Times(5);
    EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(2, StringToId("xxx"))));
    EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(2, StringToId("yyy"))));
    EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(2, StringToId("zzz"))));

    manager->StartupForTest();
    manager->UpdateConfig(ConfigKey(1, StringToId("aaa")), config);
    manager->UpdateConfig(ConfigKey(2, StringToId("xxx")), config);
    manager->UpdateConfig(ConfigKey(2, StringToId("yyy")), config);
    manager->UpdateConfig(ConfigKey(2, StringToId("zzz")), config);
    manager->UpdateConfig(ConfigKey(3, StringToId("bbb")), config);

    manager->RemoveConfigs(2);
}
