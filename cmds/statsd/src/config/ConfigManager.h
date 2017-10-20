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
     * Call to load the saved configs from disk.
     *
     * TODO: Implement me
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
    void update_saved_configs();

    /**
     * The Configs that have been set
     */
    unordered_map<ConfigKey, StatsdConfig> mConfigs;

    /**
     * The ConfigListeners that will be told about changes.
     */
    vector<sp<ConfigListener>> mListeners;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
