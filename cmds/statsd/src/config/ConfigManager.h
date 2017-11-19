/*
 * Copyright (C) 2017 The Android Open Source Project
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

#include "config/ConfigKey.h"
#include "config/ConfigListener.h"

#include <string>
#include <unordered_map>

#include <stdio.h>

namespace android {
namespace os {
namespace statsd {

using android::RefBase;
using std::string;
using std::unordered_map;
using std::vector;
using std::pair;

/**
 * Keeps track of which configurations have been set from various sources.
 *
 * TODO: Store the configs persistently too.
 * TODO: Dump method for debugging.
 */
class ConfigManager : public virtual RefBase {
public:
    ConfigManager();
    virtual ~ConfigManager();

    /**
     * Initialize ConfigListener by reading from disk and get updates.
     */
    void Startup();

    /**
     * Someone else wants to know about the configs.
     */
    void AddListener(const sp<ConfigListener>& listener);

    /**
     * A configuration was added or updated.
     *
     * Reports this to listeners.
     */
    void UpdateConfig(const ConfigKey& key, const StatsdConfig& data);

    /**
     * Sets the broadcast receiver for a configuration key.
     */
    void SetConfigReceiver(const ConfigKey& key, const string& pkg, const string& cls);

    /**
     * Returns the package name and class name representing the broadcast receiver for this config.
     */
    const pair<string, string> GetConfigReceiver(const ConfigKey& key);

    /**
     * Returns all config keys registered.
     */
    vector<ConfigKey> GetAllConfigKeys();

    /**
     * Erase any broadcast receiver associated with this config key.
     */
    void RemoveConfigReceiver(const ConfigKey& key);

    /**
     * A configuration was removed.
     *
     * Reports this to listeners.
     */
    void RemoveConfig(const ConfigKey& key);

    /**
     * Remove all of the configs for the given uid.
     */
    void RemoveConfigs(int uid);

    /**
     * Text dump of our state for debugging.
     */
    void Dump(FILE* out);

private:
    /**
     * Save the configs to disk.
     */
    void update_saved_configs(const ConfigKey& key, const StatsdConfig& config);

    /**
     * Remove saved configs from disk.
     */
    void remove_saved_configs(const ConfigKey& key);

    /**
     * The Configs that have been set. Each config should
     */
    unordered_map<ConfigKey, StatsdConfig> mConfigs;

    /**
     * Each config key can be subscribed by up to one receiver, specified as the package name and
     * class name.
     */
    unordered_map<ConfigKey, pair<string, string>> mConfigReceivers;

    /**
     * The ConfigListeners that will be told about changes.
     */
    vector<sp<ConfigListener>> mListeners;

    /**
     * Call to load the saved configs from disk.
     */
    void readConfigFromDisk();
};

}  // namespace statsd
}  // namespace os
}  // namespace android
