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
    return os << "StatsdConfig{id=" << config.config_id() << "}";
}

}  // namespace statsd
}  // namespace os
}  // namespace android

/**
 * Mock ConfigListener
 */
class MockListener : public ConfigListener {
public:
    MOCK_METHOD2(OnConfigUpdated, void(const ConfigKey& key, const StatsdConfig& config));
    MOCK_METHOD1(OnConfigRemoved, void(const ConfigKey& key));
};

/**
 * Validate that the ConfigKey is the one we wanted.
 */
MATCHER_P2(ConfigKeyEq, uid, name, "") {
    return arg.GetUid() == uid && arg.GetName() == name;
}

/**
 * Validate that the StatsdConfig is the one we wanted.
 */
MATCHER_P(StatsdConfigEq, configId, "") {
    return arg.config_id() == configId;
}

/**
 * Test the addOrUpdate and remove methods
 */
TEST(ConfigManagerTest, TestAddUpdateRemove) {
    sp<MockListener> listener = new StrictMock<MockListener>();

    sp<ConfigManager> manager = new ConfigManager();
    manager->AddListener(listener);

    StatsdConfig config91;
    config91.set_config_id(91);
    StatsdConfig config92;
    config92.set_config_id(92);
    StatsdConfig config93;
    config93.set_config_id(93);
    StatsdConfig config94;
    config94.set_config_id(94);

    {
        InSequence s;

        // The built-in fake one.
        // TODO: Remove this when we get rid of the fake one, and make this
        // test loading one from disk somewhere.
        EXPECT_CALL(*(listener.get()),
                    OnConfigUpdated(ConfigKeyEq(0, "fake"), StatsdConfigEq(12345)))
                .RetiresOnSaturation();
        manager->Startup();

        // Add another one
        EXPECT_CALL(*(listener.get()), OnConfigUpdated(ConfigKeyEq(1, "zzz"), StatsdConfigEq(91)))
                .RetiresOnSaturation();
        manager->UpdateConfig(ConfigKey(1, "zzz"), config91);

        // Update It
        EXPECT_CALL(*(listener.get()), OnConfigUpdated(ConfigKeyEq(1, "zzz"), StatsdConfigEq(92)))
                .RetiresOnSaturation();
        manager->UpdateConfig(ConfigKey(1, "zzz"), config92);

        // Add one with the same uid but a different name
        EXPECT_CALL(*(listener.get()), OnConfigUpdated(ConfigKeyEq(1, "yyy"), StatsdConfigEq(93)))
                .RetiresOnSaturation();
        manager->UpdateConfig(ConfigKey(1, "yyy"), config93);

        // Add one with the same name but a different uid
        EXPECT_CALL(*(listener.get()), OnConfigUpdated(ConfigKeyEq(2, "zzz"), StatsdConfigEq(94)))
                .RetiresOnSaturation();
        manager->UpdateConfig(ConfigKey(2, "zzz"), config94);

        // Remove (1,yyy)
        EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(1, "yyy")))
                .RetiresOnSaturation();
        manager->RemoveConfig(ConfigKey(1, "yyy"));

        // Remove (2,zzz)
        EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(2, "zzz")))
                .RetiresOnSaturation();
        manager->RemoveConfig(ConfigKey(2, "zzz"));

        // Remove (1,zzz)
        EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(1, "zzz")))
                .RetiresOnSaturation();
        manager->RemoveConfig(ConfigKey(1, "zzz"));

        // Remove (2,zzz) again and we shouldn't get the callback
        manager->RemoveConfig(ConfigKey(2, "zzz"));
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

    EXPECT_CALL(*(listener.get()), OnConfigUpdated(_, _)).Times(6);
    EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(2, "xxx")));
    EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(2, "yyy")));
    EXPECT_CALL(*(listener.get()), OnConfigRemoved(ConfigKeyEq(2, "zzz")));

    manager->Startup();
    manager->UpdateConfig(ConfigKey(1, "aaa"), config);
    manager->UpdateConfig(ConfigKey(2, "xxx"), config);
    manager->UpdateConfig(ConfigKey(2, "yyy"), config);
    manager->UpdateConfig(ConfigKey(2, "zzz"), config);
    manager->UpdateConfig(ConfigKey(3, "bbb"), config);

    manager->RemoveConfigs(2);
}
