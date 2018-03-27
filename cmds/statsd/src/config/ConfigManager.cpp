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

#include "config/ConfigManager.h"
#include "storage/StorageManager.h"

#include "stats_util.h"

#include <android-base/file.h>
#include <dirent.h>
#include <stdio.h>
#include <vector>
#include "android-base/stringprintf.h"

namespace android {
namespace os {
namespace statsd {

using std::map;
using std::pair;
using std::set;
using std::string;
using std::vector;

#define STATS_SERVICE_DIR "/data/misc/stats-service"

using android::base::StringPrintf;
using std::unique_ptr;

ConfigManager::ConfigManager() {
}

ConfigManager::~ConfigManager() {
}

void ConfigManager::Startup() {
    map<ConfigKey, StatsdConfig> configsFromDisk;
    StorageManager::readConfigFromDisk(configsFromDisk);
    for (const auto& pair : configsFromDisk) {
        UpdateConfig(pair.first, pair.second);
    }
}

void ConfigManager::StartupForTest() {
    // Dummy function to avoid reading configs from disks for tests.
}

void ConfigManager::AddListener(const sp<ConfigListener>& listener) {
    lock_guard<mutex> lock(mMutex);
    mListeners.push_back(listener);
}

void ConfigManager::UpdateConfig(const ConfigKey& key, const StatsdConfig& config) {
    vector<sp<ConfigListener>> broadcastList;
    {
        lock_guard <mutex> lock(mMutex);

        auto it = mConfigs.find(key);

        const int numBytes = config.ByteSize();
        vector<uint8_t> buffer(numBytes);
        config.SerializeToArray(&buffer[0], numBytes);

        const bool isDuplicate =
            it != mConfigs.end() &&
            StorageManager::hasIdenticalConfig(key, buffer);

        // Update saved file on disk. We still update timestamp of file when
        // there exists a duplicate configuration to avoid garbage collection.
        update_saved_configs_locked(key, buffer, numBytes);

        if (isDuplicate) return;

        // Add to set
        mConfigs.insert(key);

        for (sp<ConfigListener> listener : mListeners) {
            broadcastList.push_back(listener);
        }
    }

    // Tell everyone
    for (sp<ConfigListener> listener:broadcastList) {
        listener->OnConfigUpdated(key, config);
    }
}

void ConfigManager::SetConfigReceiver(const ConfigKey& key, const sp<IBinder>& intentSender) {
    lock_guard<mutex> lock(mMutex);
    mConfigReceivers[key] = intentSender;
}

void ConfigManager::RemoveConfigReceiver(const ConfigKey& key) {
    lock_guard<mutex> lock(mMutex);
    mConfigReceivers.erase(key);
}

void ConfigManager::RemoveConfig(const ConfigKey& key) {
    vector<sp<ConfigListener>> broadcastList;
    {
        lock_guard <mutex> lock(mMutex);

        auto it = mConfigs.find(key);
        if (it != mConfigs.end()) {
            // Remove from map
            mConfigs.erase(it);

            for (sp<ConfigListener> listener : mListeners) {
                broadcastList.push_back(listener);
            }
        }

        auto itReceiver = mConfigReceivers.find(key);
        if (itReceiver != mConfigReceivers.end()) {
            // Remove from map
            mConfigReceivers.erase(itReceiver);
        }

        // Remove from disk. There can still be a lingering file on disk so we check
        // whether or not the config was on memory.
        remove_saved_configs(key);
    }

    for (sp<ConfigListener> listener:broadcastList) {
        listener->OnConfigRemoved(key);
    }
}

void ConfigManager::remove_saved_configs(const ConfigKey& key) {
    string suffix = StringPrintf("%d_%lld", key.GetUid(), (long long)key.GetId());
    StorageManager::deleteSuffixedFiles(STATS_SERVICE_DIR, suffix.c_str());
}

void ConfigManager::RemoveConfigs(int uid) {
    vector<ConfigKey> removed;
    vector<sp<ConfigListener>> broadcastList;
    {
        lock_guard <mutex> lock(mMutex);

        for (auto it = mConfigs.begin(); it != mConfigs.end();) {
            // Remove from map
            if (it->GetUid() == uid) {
                remove_saved_configs(*it);
                removed.push_back(*it);
                mConfigReceivers.erase(*it);
                it = mConfigs.erase(it);
            } else {
                it++;
            }
        }

        for (sp<ConfigListener> listener : mListeners) {
            broadcastList.push_back(listener);
        }
    }

    // Remove separately so if they do anything in the callback they can't mess up our iteration.
    for (auto& key : removed) {
        // Tell everyone
        for (sp<ConfigListener> listener:broadcastList) {
            listener->OnConfigRemoved(key);
        }
    }
}

void ConfigManager::RemoveAllConfigs() {
    vector<ConfigKey> removed;
    vector<sp<ConfigListener>> broadcastList;
    {
        lock_guard <mutex> lock(mMutex);


        for (auto it = mConfigs.begin(); it != mConfigs.end();) {
            // Remove from map
            removed.push_back(*it);
            auto receiverIt = mConfigReceivers.find(*it);
            if (receiverIt != mConfigReceivers.end()) {
                mConfigReceivers.erase(*it);
            }
            it = mConfigs.erase(it);
        }

        for (sp<ConfigListener> listener : mListeners) {
            broadcastList.push_back(listener);
        }
    }

    // Remove separately so if they do anything in the callback they can't mess up our iteration.
    for (auto& key : removed) {
        // Tell everyone
        for (sp<ConfigListener> listener:broadcastList) {
            listener->OnConfigRemoved(key);
        }
    }
}

vector<ConfigKey> ConfigManager::GetAllConfigKeys() const {
    lock_guard<mutex> lock(mMutex);

    vector<ConfigKey> ret;
    for (auto it = mConfigs.cbegin(); it != mConfigs.cend(); ++it) {
        ret.push_back(*it);
    }
    return ret;
}

const sp<android::IBinder> ConfigManager::GetConfigReceiver(const ConfigKey& key) const {
    lock_guard<mutex> lock(mMutex);

    auto it = mConfigReceivers.find(key);
    if (it == mConfigReceivers.end()) {
        return nullptr;
    } else {
        return it->second;
    }
}

void ConfigManager::Dump(FILE* out) {
    lock_guard<mutex> lock(mMutex);

    fprintf(out, "CONFIGURATIONS (%d)\n", (int)mConfigs.size());
    fprintf(out, "     uid name\n");
    for (const auto& key : mConfigs) {
        fprintf(out, "  %6d %lld\n", key.GetUid(), (long long)key.GetId());
        auto receiverIt = mConfigReceivers.find(key);
        if (receiverIt != mConfigReceivers.end()) {
            fprintf(out, "    -> received by PendingIntent as binder\n");
        }
    }
}

void ConfigManager::update_saved_configs_locked(const ConfigKey& key,
                                                const vector<uint8_t>& buffer,
                                                const int numBytes) {
    // If there is a pre-existing config with same key we should first delete it.
    remove_saved_configs(key);

    // Then we save the latest config.
    string file_name =
        StringPrintf("%s/%ld_%d_%lld", STATS_SERVICE_DIR, time(nullptr),
                     key.GetUid(), (long long)key.GetId());
    StorageManager::writeFile(file_name.c_str(), &buffer[0], numBytes);
}

}  // namespace statsd
}  // namespace os
}  // namespace android
