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

#include "binder/IBinder.h"
#include "config/ConfigKey.h"
#include "config/ConfigListener.h"

#include <map>
#include <mutex>
#include <set>
#include <string>

#include <stdio.h>

namespace android {
namespace os {
namespace statsd {

/**
 * Keeps track of which configurations have been set from various sources.
 */
class ConfigManager : public virtual android::RefBase {
public:
    ConfigManager();
    virtual ~ConfigManager();

    /**
     * Initialize ConfigListener by reading from disk and get updates.
     */
    void Startup();

    /*
     * Dummy initializer for tests.
     */
    void StartupForTest();

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
    void SetConfigReceiver(const ConfigKey& key, const sp<IBinder>& intentSender);

    /**
     * Returns the package name and class name representing the broadcast receiver for this config.
     */
    const sp<android::IBinder> GetConfigReceiver(const ConfigKey& key) const;

    /**
     * Returns all config keys registered.
     */
    std::vector<ConfigKey> GetAllConfigKeys() const;

    /**
     * Erase any broadcast receiver associated with this config key.
     */
    void RemoveConfigReceiver(const ConfigKey& key);

    /**
     * Sets the broadcast receiver that is notified whenever the list of active configs
     * changes for this uid.
     */
    void SetActiveConfigsChangedReceiver(const int uid, const sp<IBinder>& intentSender);

    /**
     * Returns the broadcast receiver for active configs changed for this uid.
     */

    const sp<IBinder> GetActiveConfigsChangedReceiver(const int uid) const;

    /**
     * Erase any active configs changed broadcast receiver associated with this uid.
     */
    void RemoveActiveConfigsChangedReceiver(const int uid);

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
     * Remove all of the configs from memory.
     */
    void RemoveAllConfigs();

    /**
     * Text dump of our state for debugging.
     */
    void Dump(FILE* out);

private:
    mutable std::mutex mMutex;

    /**
     * Save the configs to disk.
     */
    void update_saved_configs_locked(const ConfigKey& key,
                                     const std::vector<uint8_t>& buffer,
                                     const int numBytes);

    /**
     * Remove saved configs from disk.
     */
    void remove_saved_configs(const ConfigKey& key);

    /**
     * Maps from uid to the config keys that have been set.
     */
    std::map<int, std::set<ConfigKey>> mConfigs;

    /**
     * Each config key can be subscribed by up to one receiver, specified as IBinder from
     * PendingIntent.
     */
    std::map<ConfigKey, sp<android::IBinder>> mConfigReceivers;

    /**
     * Each uid can be subscribed by up to one receiver to notify that the list of active configs
     * for this uid has changed. The receiver is specified as IBinder from PendingIntent.
     */
     std::map<int, sp<android::IBinder>> mActiveConfigsChangedReceivers;

    /**
     * The ConfigListeners that will be told about changes.
     */
    std::vector<sp<ConfigListener>> mListeners;
};

}  // namespace statsd
}  // namespace os
}  // namespace android
